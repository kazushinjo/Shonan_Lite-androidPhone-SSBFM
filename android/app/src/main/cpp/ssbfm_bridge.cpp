// SSB/FM送受信タブのJNIブリッジ。
//
// 信号処理はLangstone-V2(G4EML Colin Durbridge氏、GPLv3、https://github.com/g4eml/Langstone-V2)の
// Lang_TRX_Pluto.py(GNU Radioフローグラフ)とLangstoneGUI_Pluto.c(Pluto制御)をC++へ移植したもの。
//   受信: Pluto 528ksps → 周波数変換+1/11間引き(48kHz) → 複素帯域フィルタ → 復調(SSB:実部+AGC /
//         FM:直交復調+ディエンファシス) → 音声フィルタ → スピーカー
//   送信: マイク48kHz → SSB:複素帯域フィルタで片側波帯を生成 / FM:プリエンファシス+周波数変調
//         → 11倍補間 → Pluto 528ksps
//   受信LOは100kHz単位で置き、希望周波数を+50〜+150kHzのオフセット側で受ける(DC付近の
//   コブを避けるLangstoneと同じ方式)。非送信中はTX LO、送信中はRX LOを電源断する。
//
// GNU Radio本体(top_block)は使わない。dvbs2rx_bridge.cppで確認済みの「tb->wait()がgr-iioの
// ブロッキングI/Oで戻らない」「同一プロセスで2回目のフローグラフ構築がSIGSEGVする」問題を避ける
// ため、libiio(v1、dvbs2_bridgeと同じ静的ライブラリ)を直接使い、DSPは自前のスレッドで回す。
//
// 528kspsはAD9361のFIRなしの下限(約2.083Msps)を下回るため、libad9361-iio
// (Analog Devices、LGPL-2.1)のad9361_set_bb_rate()と同じ手順でFIR(fir_128_4)を読み込む。
// gr-iio内蔵のlibad9361は旧API(libiio v0)向けでv1のオブジェクトを渡せないため移植した。

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <vector>

extern "C" {
#include <iio/iio.h>
}

#define LOG_TAG "SsbFmBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include "ssbfm_dsp.h"

namespace {

using namespace ssbfm;

constexpr long long kRfBandwidth = 2000000; // Langstoneと同じ

// ---------------------------------------------------------------------------
// 単一生産者・単一消費者のリングバッファ(音声用)

class FloatRing {
public:
    explicit FloatRing(size_t capacity) : buf_(capacity), cap_(capacity) {}
    size_t size() const {
        return writeIdx_.load(std::memory_order_acquire) - readIdx_.load(std::memory_order_acquire);
    }
    // 満杯なら書き込めた分だけ返す。
    size_t write(const float *src, size_t n) {
        const size_t w = writeIdx_.load(std::memory_order_relaxed);
        const size_t r = readIdx_.load(std::memory_order_acquire);
        n = std::min(n, cap_ - (w - r));
        for (size_t i = 0; i < n; i++) buf_[(w + i) % cap_] = src[i];
        writeIdx_.store(w + n, std::memory_order_release);
        return n;
    }
    bool pop(float *out) {
        const size_t r = readIdx_.load(std::memory_order_relaxed);
        if (r == writeIdx_.load(std::memory_order_acquire)) return false;
        *out = buf_[r % cap_];
        readIdx_.store(r + 1, std::memory_order_release);
        return true;
    }
    // 消費者側からのみ呼ぶ。
    void discard(size_t n) {
        const size_t r = readIdx_.load(std::memory_order_relaxed);
        n = std::min(n, writeIdx_.load(std::memory_order_acquire) - r);
        readIdx_.store(r + n, std::memory_order_release);
    }

private:
    std::vector<float> buf_;
    size_t cap_;
    std::atomic<size_t> writeIdx_{0};
    std::atomic<size_t> readIdx_{0};
};

// ---------------------------------------------------------------------------
// AAudio(スピーカー出力/マイク入力)。端末が48kHzを受け付けない場合は線形補間で変換する。

class AudioOutput {
public:
    FloatRing ring{kAudioRate}; // 1秒分
    std::atomic<bool> needsRestart{false};

    bool open() {
        AAudioStreamBuilder *builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 1);
        AAudioStreamBuilder_setSampleRate(builder, kAudioRate);
        // 用途(usage)は既定のMEDIAのまま(スピーカーから鳴る)。setUsageはAPI 28以降のため使わない。
        AAudioStreamBuilder_setDataCallback(builder, &AudioOutput::dataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, &AudioOutput::errorCallback, this);
        aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (res != AAUDIO_OK) {
            LOGE("AAudio出力を開けません: %s", AAudio_convertResultToText(res));
            stream_ = nullptr;
            return false;
        }
        channels_ = AAudioStream_getChannelCount(stream_);
        step_ = static_cast<double>(kAudioRate) / AAudioStream_getSampleRate(stream_);
        LOGI("AAudio出力: rate=%d ch=%d", AAudioStream_getSampleRate(stream_), channels_);
        res = AAudioStream_requestStart(stream_);
        if (res != AAUDIO_OK) {
            LOGE("AAudio出力を開始できません: %s", AAudio_convertResultToText(res));
            close();
            return false;
        }
        return true;
    }

    void close() {
        if (!stream_) return;
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }

    // 受信スレッドから呼ぶ。端末の再生クロックとPlutoのクロックのずれで溜まり過ぎた場合は
    // 新しいブロックを捨てて遅延が伸び続けないようにする。
    void push(const float *samples, int n) {
        if (ring.size() > static_cast<size_t>(kAudioRate / 4)) return;
        ring.write(samples, static_cast<size_t>(n));
    }

private:
    AAudioStream *stream_ = nullptr;
    int channels_ = 1;
    double step_ = 1.0;
    double frac_ = 1.0;
    float prev_ = 0.0f, cur_ = 0.0f;
    bool playing_ = false;

    static aaudio_data_callback_result_t dataCallback(AAudioStream *, void *user, void *audioData,
                                                      int32_t numFrames) {
        auto *self = static_cast<AudioOutput *>(user);
        auto *out = static_cast<float *>(audioData);
        // 途切れた後は60ms溜まるまで無音にして、細切れの再生を避ける。
        if (!self->playing_ && self->ring.size() >= static_cast<size_t>(kAudioRate * 6 / 100)) {
            self->playing_ = true;
        }
        for (int32_t i = 0; i < numFrames; i++) {
            float v = 0.0f;
            if (self->playing_) {
                while (self->frac_ >= 1.0) {
                    self->prev_ = self->cur_;
                    if (!self->ring.pop(&self->cur_)) {
                        self->cur_ = 0.0f;
                        self->playing_ = false;
                    }
                    self->frac_ -= 1.0;
                }
                v = self->prev_ + (self->cur_ - self->prev_) * static_cast<float>(self->frac_);
                self->frac_ += self->step_;
            }
            for (int c = 0; c < self->channels_; c++) out[i * self->channels_ + c] = v;
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCallback(AAudioStream *, void *user, aaudio_result_t error) {
        LOGW("AAudio出力エラー: %s", AAudio_convertResultToText(error));
        static_cast<AudioOutput *>(user)->needsRestart = true;
    }
};

class AudioInput {
public:
    FloatRing ring{kAudioRate}; // 1秒分
    std::atomic<float> peak{0.0f};

    bool open() {
        AAudioStreamBuilder *builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 1);
        AAudioStreamBuilder_setSampleRate(builder, kAudioRate);
        AAudioStreamBuilder_setDataCallback(builder, &AudioInput::dataCallback, this);
        aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (res != AAUDIO_OK) {
            LOGE("AAudio入力を開けません: %s", AAudio_convertResultToText(res));
            stream_ = nullptr;
            return false;
        }
        channels_ = AAudioStream_getChannelCount(stream_);
        step_ = static_cast<double>(AAudioStream_getSampleRate(stream_)) / kAudioRate;
        frac_ = 0.0;
        LOGI("AAudio入力: rate=%d ch=%d", AAudioStream_getSampleRate(stream_), channels_);
        res = AAudioStream_requestStart(stream_);
        if (res != AAUDIO_OK) {
            LOGE("AAudio入力を開始できません: %s", AAudio_convertResultToText(res));
            close();
            return false;
        }
        return true;
    }

    void close() {
        if (!stream_) return;
        AAudioStream_requestStop(stream_);
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }

private:
    AAudioStream *stream_ = nullptr;
    int channels_ = 1;
    double step_ = 1.0;
    double frac_ = 0.0;
    float prev_ = 0.0f;

    static aaudio_data_callback_result_t dataCallback(AAudioStream *, void *user, void *audioData,
                                                      int32_t numFrames) {
        auto *self = static_cast<AudioInput *>(user);
        const auto *in = static_cast<const float *>(audioData);
        float tmp[1024];
        int count = 0;
        float pk = 0.0f;
        for (int32_t i = 0; i < numFrames; i++) {
            const float cur = in[i * self->channels_];
            pk = std::max(pk, std::fabs(cur));
            // 入力側のレートが48kHz以外なら、48kHz相当の時刻で線形補間する。
            while (self->frac_ < 1.0) {
                tmp[count++] = self->prev_ + (cur - self->prev_) * static_cast<float>(self->frac_);
                self->frac_ += self->step_;
                if (count == 1024) {
                    self->ring.write(tmp, count);
                    count = 0;
                }
            }
            self->frac_ -= 1.0;
            self->prev_ = cur;
        }
        if (count > 0) self->ring.write(tmp, count);
        self->peak = std::max(pk, self->peak.load() * 0.9f);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
};

// ---------------------------------------------------------------------------
// libiio v1の属性アクセス補助

bool writeLL(const iio_channel *ch, const char *name, long long v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_write_longlong(a, v) >= 0;
}
bool readLL(const iio_channel *ch, const char *name, long long *v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_read_longlong(a, v) >= 0;
}
bool writeBool(const iio_channel *ch, const char *name, bool v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_write_bool(a, v) >= 0;
}
bool readBool(const iio_channel *ch, const char *name, bool *v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_read_bool(a, v) >= 0;
}
bool writeDouble(const iio_channel *ch, const char *name, double v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_write_double(a, v) >= 0;
}
bool readDouble(const iio_channel *ch, const char *name, double *v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_read_double(a, v) >= 0;
}
bool writeString(const iio_channel *ch, const char *name, const char *v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    return a && iio_attr_write_string(a, v) >= 0;
}
bool readString(const iio_channel *ch, const char *name, std::string *v) {
    const iio_attr *a = ch ? iio_channel_find_attr(ch, name) : nullptr;
    if (!a) return false;
    char buf[128]{};
    if (iio_attr_read_raw(a, buf, sizeof(buf) - 1) < 0) return false;
    *v = buf;
    while (!v->empty() && (v->back() == '\n' || v->back() == ' ')) v->pop_back();
    return true;
}

// libad9361-iio(ad9361_baseband_auto_rate.c)のfir_128_4。
const int16_t kFir128x4[] = {
    -15, -27, -23, -6, 17, 33, 31, 9, -23, -47, -45, -13, 34, 69, 67, 21, -49, -102, -99, -32, 69, 146, 143, 48,
    -96, -204, -200, -69, 129, 278, 275, 97, -170, -372, -371, -135, 222, 494, 497, 187, -288, -654, -665, -258,
    376, 875, 902, 363, -500, -1201, -1265, -530, 699, 1748, 1906, 845, -1089, -2922, -3424, -1697, 2326, 7714,
    12821, 15921, 15921, 12821, 7714, 2326, -1697, -3424, -2922, -1089, 845, 1906, 1748, 699, -530, -1265, -1201,
    -500, 363, 902, 875, 376, -258, -665, -654, -288, 187, 497, 494, 222, -135, -371, -372, -170, 97, 275, 278,
    129, -69, -200, -204, -96, 48, 143, 146, 69, -32, -99, -102, -49, 21, 67, 69, 34, -13, -45, -47, -23, 9, 31,
    33, 17, -6, -23, -27, -15};

// ---------------------------------------------------------------------------
// Pluto(ad9361-phy)の設定。受信・送信のIQストリームとは別コンテキストで操作する
// (LangstoneもGNU Radioとは別にGUI側からlibiioで直接LOを操作している)。

class PlutoControl {
public:
    std::string lastError;

    bool open(const std::string &uri) {
        ctx_ = iio_create_context(nullptr, uri.c_str());
        if (!ctx_ || iio_err(ctx_) != 0) {
            if (ctx_) iio_context_destroy(ctx_);
            ctx_ = nullptr;
            lastError = "Plutoへ接続できません(" + uri + ")";
            return false;
        }
        (void)iio_context_set_timeout(ctx_, 5000);
        phy_ = iio_context_find_device(ctx_, "ad9361-phy");
        if (!phy_) {
            lastError = "ad9361-phyが見つかりません";
            return false;
        }
        rxPhy_ = iio_device_find_channel(phy_, "voltage0", false);
        txPhy_ = iio_device_find_channel(phy_, "voltage0", true);
        rxLo_ = iio_device_find_channel(phy_, "altvoltage0", true);
        txLo_ = iio_device_find_channel(phy_, "altvoltage1", true);
        if (!rxPhy_ || !txPhy_ || !rxLo_ || !txLo_) {
            lastError = "ad9361-phyのチャンネルが見つかりません";
            return false;
        }
        return true;
    }

    void close() {
        if (ctx_) iio_context_destroy(ctx_);
        ctx_ = nullptr;
        phy_ = nullptr;
        rxPhy_ = txPhy_ = rxLo_ = txLo_ = nullptr;
    }

    // SSB/FM終了時にDATV側の状態へ戻すため、開始前の値を控えておく。
    void save() {
        saved_ = Saved{};
        saved_.samplingOk = readLL(txPhy_, "sampling_frequency", &saved_.sampling);
        saved_.firOk = getFirEnable(&saved_.firEnabled);
        saved_.rxLoOk = readLL(rxLo_, "frequency", &saved_.rxLo);
        saved_.txLoOk = readLL(txLo_, "frequency", &saved_.txLo);
        saved_.rxBwOk = readLL(rxPhy_, "rf_bandwidth", &saved_.rxBw);
        saved_.txBwOk = readLL(txPhy_, "rf_bandwidth", &saved_.txBw);
        saved_.txGainOk = readDouble(txPhy_, "hardwaregain", &saved_.txGain);
        saved_.gainModeOk = readString(rxPhy_, "gain_control_mode", &saved_.gainMode);
        saved_.rxGainOk = readDouble(rxPhy_, "hardwaregain", &saved_.rxGain);
        LOGI("保存: sampling=%lld fir=%d rxLo=%lld txLo=%lld gainMode=%s",
             saved_.sampling, saved_.firEnabled ? 1 : 0, saved_.rxLo, saved_.txLo, saved_.gainMode.c_str());
    }

    void restore() {
        if (!ctx_) return;
        if (saved_.samplingOk) {
            if (saved_.firOk && !saved_.firEnabled) {
                // FIR無効では2.083Msps未満を設定できないため、先に安全なレートへ上げてから無効化する。
                (void)writeLL(txPhy_, "sampling_frequency", 3000000);
                (void)setFirEnable(false);
            }
            (void)writeLL(txPhy_, "sampling_frequency", saved_.sampling);
        }
        if (saved_.rxBwOk) (void)writeLL(rxPhy_, "rf_bandwidth", saved_.rxBw);
        if (saved_.txBwOk) (void)writeLL(txPhy_, "rf_bandwidth", saved_.txBw);
        if (saved_.rxLoOk) (void)writeLL(rxLo_, "frequency", saved_.rxLo);
        if (saved_.txLoOk) (void)writeLL(txLo_, "frequency", saved_.txLo);
        if (saved_.txGainOk) (void)writeDouble(txPhy_, "hardwaregain", saved_.txGain);
        if (saved_.gainModeOk) {
            (void)writeString(rxPhy_, "gain_control_mode", saved_.gainMode.c_str());
            if (saved_.gainMode == "manual" && saved_.rxGainOk) {
                (void)writeDouble(rxPhy_, "hardwaregain", saved_.rxGain);
            }
        }
        (void)writeBool(rxLo_, "powerdown", false);
        (void)writeBool(txLo_, "powerdown", false);
        LOGI("Pluto設定を復元しました");
    }

    // libad9361のad9361_set_bb_rate()をlibiio v1へ移植したもの。
    bool setBasebandRate(long long rate) {
        bool enabled = false;
        if (!getFirEnable(&enabled)) {
            lastError = "PlutoのFIR状態を読めません";
            return false;
        }
        if (enabled) {
            long long current = 0;
            if (readLL(txPhy_, "sampling_frequency", &current) && current <= 25000000 / 12) {
                (void)writeLL(txPhy_, "sampling_frequency", 3000000);
            }
            if (!setFirEnable(false)) {
                lastError = "PlutoのFIRを無効化できません";
                return false;
            }
        }

        std::string cfg = "RX 3 GAIN -6 DEC 4\nTX 3 GAIN 0 INT 4\n";
        char line[32];
        for (int16_t t : kFir128x4) {
            snprintf(line, sizeof(line), "%d,%d\n", t, t);
            cfg += line;
        }
        cfg += "\n";
        const iio_attr *firCfg = iio_device_find_attr(phy_, "filter_fir_config");
        if (!firCfg || iio_attr_write_raw(firCfg, cfg.data(), cfg.size()) < 0) {
            lastError = "PlutoへFIR係数を書き込めません";
            return false;
        }

        // rate <= 25MHz/12 の場合(528kspsは常にこちら)
        char rates[128]{};
        const iio_attr *pathRates = iio_device_find_attr(phy_, "tx_path_rates");
        if (!pathRates || iio_attr_read_raw(pathRates, rates, sizeof(rates) - 1) < 0) {
            lastError = "Plutoのtx_path_ratesを読めません";
            return false;
        }
        int dacRate = 0, txRate = 0;
        if (sscanf(rates, "BBPLL:%*d DAC:%d T2:%*d T1:%*d TF:%*d TXSAMP:%d", &dacRate, &txRate) != 2 || txRate == 0) {
            lastError = std::string("tx_path_ratesを解釈できません: ") + rates;
            return false;
        }
        if ((dacRate / txRate) * 16 < 128) (void)writeLL(txPhy_, "sampling_frequency", 3000000);
        if (!setFirEnable(true)) {
            lastError = "PlutoのFIRを有効化できません";
            return false;
        }
        if (!writeLL(txPhy_, "sampling_frequency", rate)) {
            lastError = "Plutoのサンプルレートを設定できません";
            return false;
        }
        (void)writeLL(rxPhy_, "rf_bandwidth", kRfBandwidth);
        (void)writeLL(txPhy_, "rf_bandwidth", kRfBandwidth);
        return true;
    }

    bool setRxLo(long long hz) { return writeLL(rxLo_, "frequency", hz); }
    bool setTxLo(long long hz) { return writeLL(txLo_, "frequency", hz); }
    void setRxLoPowerdown(bool down) { (void)writeBool(rxLo_, "powerdown", down); }
    void setTxLoPowerdown(bool down) { (void)writeBool(txLo_, "powerdown", down); }

    void setRxGain(bool agc, double db) {
        if (agc) {
            (void)writeString(rxPhy_, "gain_control_mode", "slow_attack");
        } else {
            (void)writeString(rxPhy_, "gain_control_mode", "manual");
            (void)writeDouble(rxPhy_, "hardwaregain", db);
        }
    }
    void setTxAttenuation(double db) { (void)writeDouble(txPhy_, "hardwaregain", -std::fabs(db)); }
    bool readRxGain(double *db) { return readDouble(rxPhy_, "hardwaregain", db); }

private:
    struct Saved {
        long long sampling = 0, rxLo = 0, txLo = 0, rxBw = 0, txBw = 0;
        double txGain = 0, rxGain = 0;
        bool firEnabled = false;
        std::string gainMode;
        bool samplingOk = false, firOk = false, rxLoOk = false, txLoOk = false, rxBwOk = false, txBwOk = false,
             txGainOk = false, gainModeOk = false, rxGainOk = false;
    } saved_;

    iio_context *ctx_ = nullptr;
    iio_device *phy_ = nullptr;
    iio_channel *rxPhy_ = nullptr, *txPhy_ = nullptr, *rxLo_ = nullptr, *txLo_ = nullptr;

    // libad9361と同じく、デバイス属性に無ければ"out"チャンネルの属性を探す。
    bool getFirEnable(bool *enabled) {
        if (const iio_attr *a = iio_device_find_attr(phy_, "in_out_voltage_filter_fir_en")) {
            return iio_attr_read_bool(a, enabled) >= 0;
        }
        for (bool output : {false, true}) {
            if (const iio_channel *out = iio_device_find_channel(phy_, "out", output)) {
                if (readBool(out, "voltage_filter_fir_en", enabled)) return true;
            }
        }
        return false;
    }
    bool setFirEnable(bool enable) {
        if (const iio_attr *a = iio_device_find_attr(phy_, "in_out_voltage_filter_fir_en")) {
            return iio_attr_write_bool(a, enable) >= 0;
        }
        for (bool output : {false, true}) {
            if (const iio_channel *out = iio_device_find_channel(phy_, "out", output)) {
                if (writeBool(out, "voltage_filter_fir_en", enable)) return true;
            }
        }
        return false;
    }
};

// ---------------------------------------------------------------------------
// IQストリーム(受信: cf-ad9361-lpc / 送信: cf-ad9361-dds-core-lpc)。
// dvbs2_bridge.cppのLibiioSessionと同じ手順。

class IqStream {
public:
    std::string lastError;

    bool open(const std::string &uri, bool tx, size_t samplesPerBlock, size_t nBlocks) {
        ctx_ = iio_create_context(nullptr, uri.c_str());
        if (!ctx_ || iio_err(ctx_) != 0) {
            if (ctx_) iio_context_destroy(ctx_);
            ctx_ = nullptr;
            lastError = "Plutoへ接続できません(" + uri + ")";
            return false;
        }
        (void)iio_context_set_timeout(ctx_, 5000);
        iio_device *dev = iio_context_find_device(ctx_, tx ? "cf-ad9361-dds-core-lpc" : "cf-ad9361-lpc");
        if (!dev) {
            lastError = "IQストリーム用デバイスが見つかりません";
            return false;
        }
        if (tx) {
            // DDSのトーン発生器が送信波形に混ざらないよう無効化する。
            for (const char *id : {"altvoltage0", "altvoltage1", "altvoltage2", "altvoltage3"}) {
                const iio_channel *dds = iio_device_find_channel(dev, id, true);
                (void)writeString(dds, "raw", "0");
                (void)writeDouble(dds, "scale", 0.0);
            }
        }
        chI_ = iio_device_find_channel(dev, "voltage0", tx);
        const iio_channel *chQ = iio_device_find_channel(dev, "voltage1", tx);
        if (!chI_ || !chQ) {
            lastError = "IQチャンネルが見つかりません";
            return false;
        }
        mask_ = iio_create_channels_mask(iio_device_get_channels_count(dev));
        if (!mask_) {
            lastError = "channels maskを確保できません";
            return false;
        }
        iio_channel_enable(chI_, mask_);
        iio_channel_enable(chQ, mask_);
        iio_buffer *buf = iio_device_get_buffer(dev, 0);
        if (!buf) {
            lastError = "IQバッファを取得できません";
            return false;
        }
        stream_ = iio_buffer_create_stream(buf, nBlocks, samplesPerBlock, mask_);
        if (!stream_ || iio_err(stream_) != 0) {
            stream_ = nullptr;
            lastError = tx ? "送信ストリームを作成できません(Pluto側の送信処理が動作中の可能性があります)"
                           : "受信ストリームを作成できません";
            return false;
        }
        return true;
    }

    // 受信は受信済みブロック、送信は書き込み先ブロックを返す(送信側は次の呼び出しで投入される)。
    int16_t *next(size_t *count) {
        const iio_block *block = iio_stream_get_next_block(stream_);
        if (!block || iio_err(block) != 0) return nullptr;
        void *start = iio_block_first(block, chI_);
        void *end = iio_block_end(block);
        if (!start || !end) return nullptr;
        *count = static_cast<size_t>(static_cast<uint8_t *>(end) - static_cast<uint8_t *>(start)) /
                 (2 * sizeof(int16_t));
        return static_cast<int16_t *>(start);
    }

    void cancel() {
        if (stream_) iio_stream_cancel(stream_);
    }

    void close() {
        if (stream_) iio_stream_destroy(stream_);
        stream_ = nullptr;
        if (mask_) iio_channels_mask_destroy(mask_);
        mask_ = nullptr;
        if (ctx_) iio_context_destroy(ctx_);
        ctx_ = nullptr;
        chI_ = nullptr;
    }

    ~IqStream() { close(); }

private:
    iio_context *ctx_ = nullptr;
    iio_channels_mask *mask_ = nullptr;
    iio_stream *stream_ = nullptr;
    const iio_channel *chI_ = nullptr;
};

// ---------------------------------------------------------------------------

class SsbFmSession {
public:
    JavaVM *jvm = nullptr;
    jobject callbackObj = nullptr;
    jmethodID onErrorMethod = nullptr;

    RxDsp rx;
    TxDsp tx;
    std::atomic<float> micGain{1.0f};

    ~SsbFmSession() { stop(); }

    bool start(const std::string &uri, long long freqHz, int initialMode, bool rxAgc, double rxGain,
               double txAtten) {
        if (running_) return true;
        uri_ = uri;
        setMode(initialMode);
        rxAgc_ = rxAgc;
        rx.rxGainDb = static_cast<float>(rxGain);
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        if (!ctrl_.open(uri)) return fail(ctrl_.lastError);
        ctrl_.save();
        if (!ctrl_.setBasebandRate(kIqRate)) {
            std::string msg = ctrl_.lastError;
            ctrl_.restore();
            ctrl_.close();
            return fail(msg);
        }
        ctrl_.setRxGain(rxAgc, rxGain);
        ctrl_.setTxAttenuation(txAtten);
        ctrl_.setTxLoPowerdown(true);
        ctrl_.setRxLoPowerdown(false);
        freqHz_ = freqHz;
        lastRxLo_ = -1;
        if (!applyRxFrequencyLocked()) {
            ctrl_.restore();
            ctrl_.close();
            return fail("受信周波数を設定できません(" + std::to_string(freqHz) + " Hz)");
        }

        rx.reset();
        if (!rxStream_.open(uri, false, kIqPerBlock, 4)) {
            std::string msg = rxStream_.lastError;
            rxStream_.close();
            ctrl_.restore();
            ctrl_.close();
            return fail(msg);
        }
        if (!audioOut_.open()) reportError("スピーカー出力を開けません(受信は継続します)");

        running_ = true;
        rxThread_ = std::thread([this] {
            setpriority(PRIO_PROCESS, 0, -10);
            rxLoop();
        });
        monitorThread_ = std::thread([this] { monitorLoop(); });
        LOGI("開始 uri=%s freq=%lld mode=%d", uri.c_str(), freqHz, initialMode);
        return true;
    }

    void stop() {
        if (!running_.exchange(false)) return;
        setPtt(false);
        rxStream_.cancel();
        if (rxThread_.joinable()) rxThread_.join();
        if (monitorThread_.joinable()) monitorThread_.join();
        rxStream_.close();
        audioOut_.close();
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        ctrl_.restore();
        ctrl_.close();
        LOGI("停止");
    }

    bool setFrequency(long long hz) {
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        freqHz_ = hz;
        if (!running_) return true;
        if (transmitting_) return ctrl_.setTxLo(hz);
        return applyRxFrequencyLocked();
    }

    void setMode(int m) {
        rx.mode = m;
        tx.mode = m;
        rx.markFilterDirty();
        tx.markFilterDirty();
    }

    void setRxGain(bool agc, double db) {
        rxAgc_ = agc;
        if (!agc) rx.rxGainDb = static_cast<float>(db);
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        if (running_) ctrl_.setRxGain(agc, db);
    }

    void setTxAttenuation(double db) {
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        if (running_) ctrl_.setTxAttenuation(db);
    }

    bool setPtt(bool on) {
        std::lock_guard<std::mutex> pttLock(pttMutex_);
        if (on == transmitting_) return true;
        if (on) {
            if (!running_) return false;
            if (!audioIn_.open()) {
                reportError("マイクを開けません(マイクの権限を確認してください)");
                return false;
            }
            {
                std::lock_guard<std::mutex> lock(ctrlMutex_);
                // Langstoneと同じく、送信中は受信LOを止めて不要な混合を防ぐ。
                rx.muted = true;
                ctrl_.setRxLoPowerdown(true);
                (void)ctrl_.setTxLo(freqHz_);
                ctrl_.setTxLoPowerdown(false);
                transmitting_ = true;
            }
            if (!txStream_.open(uri_, true, kIqPerBlock, 4)) {
                std::string msg = txStream_.lastError;
                txStream_.close();
                endTransmit();
                return fail(msg);
            }
            tx.reset();
            txRunning_ = true;
            txThread_ = std::thread([this] {
                setpriority(PRIO_PROCESS, 0, -10);
                txLoop();
            });
            LOGI("送信開始 freq=%lld mode=%d", freqHz_.load(), tx.mode.load());
        } else {
            txRunning_ = false;
            txStream_.cancel();
            if (txThread_.joinable()) txThread_.join();
            txStream_.close();
            endTransmit();
            LOGI("送信終了");
        }
        return true;
    }

    float micLevel() const { return audioIn_.peak.load() * micGain.load(); }

private:
    std::string uri_;
    PlutoControl ctrl_;
    std::mutex ctrlMutex_;
    std::mutex pttMutex_;
    IqStream rxStream_, txStream_;
    AudioOutput audioOut_;
    AudioInput audioIn_;
    std::thread rxThread_, txThread_, monitorThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> txRunning_{false};
    std::atomic<bool> transmitting_{false};
    std::atomic<bool> rxAgc_{true};
    std::atomic<long long> freqHz_{0};
    long long lastRxLo_ = -1;

    bool fail(const std::string &message) {
        reportError(message);
        return false;
    }

    void reportError(const std::string &message) {
        LOGE("%s", message.c_str());
        if (!jvm || !callbackObj) return;
        JNIEnv *env = nullptr;
        bool attached = false;
        if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread(&env, nullptr);
            attached = true;
        }
        jstring jMsg = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(callbackObj, onErrorMethod, jMsg);
        env->DeleteLocalRef(jMsg);
        if (attached) jvm->DetachCurrentThread();
    }

    // Langstoneと同じ周波数配置: LOは100kHz単位、希望周波数を+50〜+150kHz側で受ける。
    bool applyRxFrequencyLocked() {
        const long long f = freqHz_;
        const long long offset = (f % 100000) + 50000;
        const long long lo = f - offset;
        if (lo != lastRxLo_) {
            if (!ctrl_.setRxLo(lo)) return false;
            lastRxLo_ = lo;
        }
        rx.ncoOffsetHz = static_cast<int>(offset);
        return true;
    }

    void endTransmit() {
        std::lock_guard<std::mutex> lock(ctrlMutex_);
        ctrl_.setTxLoPowerdown(true);
        ctrl_.setRxLoPowerdown(false);
        lastRxLo_ = -1;
        (void)applyRxFrequencyLocked();
        transmitting_ = false;
        audioIn_.close();
        rx.muted = false;
    }

    void rxLoop() {
        std::vector<float> audio(kIqPerBlock / kDecim + kDecim);
        while (running_) {
            size_t count = 0;
            int16_t *iq = rxStream_.next(&count);
            if (!iq) {
                if (running_) reportError("Plutoからの受信データが途切れました");
                break;
            }
            if (audio.size() < count / kDecim + 1) audio.resize(count / kDecim + 1);
            const int n = rx.process(iq, count, audio.data());
            if (n > 0) audioOut_.push(audio.data(), n);
        }
    }

    void txLoop() {
        // 送信開始直後のマイク遅延分(20ms)を溜めてから読み出す。
        const auto waitUntil = std::chrono::steady_clock::now() + std::chrono::milliseconds(20);
        while (txRunning_ && audioIn_.ring.size() < static_cast<size_t>(kAudioRate / 50) &&
               std::chrono::steady_clock::now() < waitUntil) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        std::vector<float> mic;
        while (txRunning_) {
            size_t count = 0;
            int16_t *out = txStream_.next(&count);
            if (!out) {
                if (txRunning_) reportError("Plutoへの送信データ書き込みに失敗しました");
                break;
            }
            // マイク側が溜まり過ぎたら(端末とPlutoのクロック差)古い分を捨てる。
            if (audioIn_.ring.size() > static_cast<size_t>(kAudioRate / 5)) {
                audioIn_.ring.discard(audioIn_.ring.size() - kAudioRate / 25);
            }
            const size_t nAudio = count / kDecim;
            mic.assign(nAudio, 0.0f);
            for (size_t n = 0; n < nAudio; n++) {
                if (!audioIn_.ring.pop(&mic[n])) break; // 足りない分は無音
            }
            tx.process(mic.data(), nAudio, micGain.load(), out);
            for (size_t idx = nAudio * kDecim; idx < count; idx++) out[2 * idx] = out[2 * idx + 1] = 0;
        }
    }

    // RF利得(AGC時)の読み取りとスピーカー出力の再接続を定期的に行う。
    void monitorLoop() {
        while (running_) {
            std::this_thread::sleep_for(std::chrono::milliseconds(300));
            if (!running_) break;
            if (rxAgc_ && !transmitting_) {
                std::lock_guard<std::mutex> lock(ctrlMutex_);
                double g = 0;
                if (ctrl_.readRxGain(&g)) rx.rxGainDb = static_cast<float>(g);
            }
            if (audioOut_.needsRestart.exchange(false)) {
                audioOut_.close();
                if (!audioOut_.open()) reportError("スピーカー出力を再接続できません");
            }
        }
    }
};

SsbFmSession *session(jlong handle) { return reinterpret_cast<SsbFmSession *>(handle); }

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *s = new SsbFmSession();
    env->GetJavaVM(&s->jvm);
    s->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    s->onErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeStart(
        JNIEnv *env, jobject, jlong handle, jstring jUri, jlong freqHz, jint mode, jboolean rxAgc,
        jdouble rxGainDb, jdouble txAttenDb) {
    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    std::string uriStr(uri);
    env->ReleaseStringUTFChars(jUri, uri);
    return static_cast<jboolean>(
        session(handle)->start(uriStr, freqHz, mode, rxAgc == JNI_TRUE, rxGainDb, txAttenDb));
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeStop(JNIEnv *, jobject, jlong handle) {
    session(handle)->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetFrequency(JNIEnv *, jobject, jlong handle, jlong hz) {
    return static_cast<jboolean>(session(handle)->setFrequency(hz));
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetMode(JNIEnv *, jobject, jlong handle, jint mode) {
    session(handle)->setMode(mode);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetPtt(JNIEnv *, jobject, jlong handle, jboolean on) {
    return static_cast<jboolean>(session(handle)->setPtt(on == JNI_TRUE));
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetAfGain(JNIEnv *, jobject, jlong handle, jfloat v) {
    session(handle)->rx.afGain = v;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetMicGain(JNIEnv *, jobject, jlong handle, jfloat v) {
    session(handle)->micGain = v;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetSquelch(JNIEnv *, jobject, jlong handle, jfloat db) {
    session(handle)->rx.squelchDb = db;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetRxGain(
        JNIEnv *, jobject, jlong handle, jboolean agc, jdouble db) {
    session(handle)->setRxGain(agc == JNI_TRUE, db);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeSetTxAttenuation(JNIEnv *, jobject, jlong handle, jdouble db) {
    session(handle)->setTxAttenuation(db);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeGetSpectrum(
        JNIEnv *env, jobject, jlong handle, jfloatArray out) {
    const jsize n = env->GetArrayLength(out);
    std::vector<float> tmp(static_cast<size_t>(n));
    if (!session(handle)->rx.copySpectrum(tmp.data(), n)) return JNI_FALSE;
    env->SetFloatArrayRegion(out, 0, n, tmp.data());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeGetSignalDb(JNIEnv *, jobject, jlong handle) {
    return session(handle)->rx.signalDb.load();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeGetRxGainDb(JNIEnv *, jobject, jlong handle) {
    return session(handle)->rx.rxGainDb.load();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeGetMicLevel(JNIEnv *, jobject, jlong handle) {
    return session(handle)->micLevel();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeIsSquelchOpen(JNIEnv *, jobject, jlong handle) {
    return static_cast<jboolean>(session(handle)->rx.squelchOpen.load());
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ssbfm_SsbFmNative_nativeDestroy(JNIEnv *env, jobject, jlong handle) {
    auto *s = session(handle);
    s->stop();
    env->DeleteGlobalRef(s->callbackObj);
    delete s;
}
