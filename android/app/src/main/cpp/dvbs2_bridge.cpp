// 機器試験(Diagnostic)機能のJNIブリッジ -- iOS版
// ios/Shonan/DVBS2/{DVBS2Encoder,DVBS2Decoder,LibiioSession,DVBS2TxPipeline,DVBS2RxPipeline}.swift
// のAndroid移植。KotlinからCライブラリを直接呼べないため、iOS版がSwiftで持っていた
// ロジック(FIFO管理・libiio操作・送受信ループ)をすべてこちらのC++側に集約し、
// Kotlin側にはstart/stop/write/診断値取得程度の薄いJNI関数だけを公開する
// (dvbs2_tx_run/rx_runはコマンドライン引数ベースでブロッキング実行されるため、JNI境界を
// 呼び出しの都度またぐと逆にオーバーヘッドと複雑さが増える)。
//
// 本番のTx/Rx画面(UDP TS方式、ts_bridge.cpp)とは完全に別の経路で、PlutoのAD9361 RF
// チェーンをlibiio経由で直接叩き、aff3ct/dvbs2でDVB-S2変調・復調をオンデバイスで行う。

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csetjmp>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <functional>
#include <mutex>
#include <random>
#include <string>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <thread>
#include <unistd.h>
#include <vector>

extern "C" {
#include <iio/iio.h>
}

#include "datv_dvbs2_bridge.h"

#define LOG_TAG "Dvbs2Bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// aff3ct/streampuはstd::cout/std::cerr(fprintf(stderr, ...))へ直接ログ・例外メッセージを
// 出す設計だが、Androidにはこれらを可視化する端末が無い(デフォルトでは何も見えず消える)。
// stdout/stderrをパイプへdup2し、別スレッドでlogcatへ転送することでdatv_dvbs2_{tx,rx}_run
// 内部の例外メッセージ("[datv_dvbs2_tx_run] exception: ...")を診断可能にする。
void redirectStdioToLogcat() {
    static int pipeFds[2];
    if (pipe(pipeFds) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Dvbs2Bridge", "pipe() failed errno=%d", errno);
        return;
    }
    if (dup2(pipeFds[1], STDOUT_FILENO) < 0 || dup2(pipeFds[1], STDERR_FILENO) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "Dvbs2Bridge", "dup2() failed errno=%d", errno);
    }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    std::thread([readFd = pipeFds[0]] {
        __android_log_print(ANDROID_LOG_INFO, "Dvbs2Bridge", "logcat redirect thread started, readFd=%d", readFd);
        char buf[1024];
        ssize_t n;
        while ((n = ::read(readFd, buf, sizeof(buf) - 1)) > 0) {
            buf[n] = '\0';
            __android_log_write(ANDROID_LOG_DEBUG, "Dvbs2Native", buf);
        }
    }).detach();
}

} // namespace

namespace {

// ---------------------------------------------------------------------------
// DVBS2WorkingDirectory.swift相当: aff3ctが実行時に相対パス("../conf/mod/...")で
// 参照するconf/を、書き込み可能なcwdへ揃えるためchdirする。conf/自体のAssetsからの
// コピーはKotlin側(Dvbs2Native.ensureWorkingDirectory)が事前に済ませておく。
void setupWorkingDirectory(const std::string &cwdPath) {
    if (chdir(cwdPath.c_str()) != 0) {
        LOGE("chdir失敗 errno=%d path=%s", errno, cwdPath.c_str());
    }
}

// FIFOのファイル名部分(セッション毎に一意であればよい)を生成する。実際のディレクトリは
// 呼び出し側(Kotlin)が渡すfilesDir配下のtmpディレクトリを使う。
std::string makeFifoPath(const char *prefix) {
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    std::random_device rd;
    std::uniform_int_distribution<int> dist(0, 0xFFFFFF);
    char buf[128];
    snprintf(buf, sizeof(buf), "%s_%ld%06ld_%06x.fifo", prefix,
              static_cast<long>(tv.tv_sec), static_cast<long>(tv.tv_usec), dist(rd));
    return std::string(buf);
}

// ---------------------------------------------------------------------------
// LibiioSession.swift相当。libiio経由でPluto(AD9361)とIQサンプルをやり取りする。
class LibiioSession {
public:
    enum class Direction { Tx, Rx };
    enum class RxIQMode { Normal, SwapIQ, Conjugate };

    std::function<void(const std::string &)> onError;
    std::function<void(const uint8_t *, size_t)> onRxIQ;

    float sampleScale = 2048.0f;
    // ★Tx専用スケール。LibiioSession.swiftのtxSampleScaleと同じ理由(DVB-S2のRRCフィルタ
    // 通過後のピークがRMSの数倍になり得るため、sampleScale=2048.0のままだとDACクリッピング
    // 寸前になる。Rx側の逆変換には影響させず、Tx側だけヘッドルームを持たせる)。
    float txSampleScale = 1400.0f;
    RxIQMode rxIQMode = RxIQMode::Normal;
    static constexpr size_t txQueueMaxBytes = 8 * 1024 * 1024;

    std::atomic<bool> isConnected{false};
    std::atomic<bool> isStreaming{false};
    std::atomic<double> lastTxStarvedPercent{0.0};
    std::atomic<double> lastTxDmaRms{0.0};

    LibiioSession() : ringBuffer_(new uint8_t[txQueueMaxBytes]) {}
    ~LibiioSession() { delete[] ringBuffer_; }
    LibiioSession(const LibiioSession &) = delete;
    LibiioSession &operator=(const LibiioSession &) = delete;

    bool connect(const std::string &uri, int maxAttempts = 10) {
        if (ctx_) return true;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            iio_context *context = iio_create_context(nullptr, uri.c_str());
            int err = context ? iio_err(context) : -9999;
            if (context && err == 0) {
                ctx_ = context;
                ownsContext_ = true;
                isConnected = true;
                iio_context_set_timeout(context, 30000);
                return true;
            }
            reportError("Plutoへの接続に失敗しました(uri=" + uri + ", err=" + std::to_string(err) +
                        ", attempt=" + std::to_string(attempt) + "/" + std::to_string(maxAttempts) + ")");
            if (context) iio_context_destroy(context);
            if (attempt < maxAttempts) {
                std::this_thread::sleep_for(std::chrono::seconds(2));
            }
        }
        return false;
    }

    void attach(iio_context *sharedContext) {
        ctx_ = sharedContext;
        ownsContext_ = false;
        isConnected = true;
    }

    void disconnect() {
        stopStreaming();
        if (stream_) { iio_stream_destroy(stream_); stream_ = nullptr; }
        if (mask_) { iio_channels_mask_destroy(mask_); mask_ = nullptr; }
        if (ownsContext_ && ctx_) { iio_context_destroy(ctx_); }
        buffer_ = nullptr;
        device_ = nullptr;
        channelI_ = nullptr;
        channelQ_ = nullptr;
        phyChan_ = nullptr;
        ctx_ = nullptr;
        isConnected = false;
    }

    bool setup(Direction direction, int64_t bandwidthHz, int64_t sampleRateHz, int64_t loHz,
               const std::string &rfPort) {
        if (!ctx_) {
            reportError("先にconnectを呼んでください");
            return false;
        }
        iio_device *phy = iio_context_find_device(ctx_, "ad9361-phy");
        if (!phy) {
            reportError("ad9361-phyデバイスが見つかりません");
            return false;
        }

        // ループバックデバッグ属性は毎回明示的に0へリセットする(実機で不揮発的に
        // 残ることを確認済み、LibiioSession.swiftの★コメント参照)。
        const iio_attr *loopbackAttr = iio_device_find_debug_attr(phy, "loopback");
        if (loopbackAttr) {
            iio_attr_write_string(loopbackAttr, "0");
        }

        bool isOutput = (direction == Direction::Tx);
        iio_channel *phyChan = iio_device_find_channel(phy, "voltage0", isOutput);
        if (!phyChan) {
            reportError("ad9361-phyのvoltage0チャンネルが見つかりません");
            return false;
        }
        phyChan_ = phyChan;

        if (const iio_attr *attr = iio_channel_find_attr(phyChan, "rf_port_select")) {
            iio_attr_write_string(attr, rfPort.c_str());
        }
        writeLongLong(phyChan, "rf_bandwidth", bandwidthHz);
        writeLongLong(phyChan, "sampling_frequency", sampleRateHz);

        if (direction == Direction::Rx) {
            if (const iio_attr *attr = iio_channel_find_attr(phyChan, "gain_control_mode")) {
                iio_attr_write_string(attr, "slow_attack");
            }
        }
        if (direction == Direction::Tx) {
            if (const iio_attr *attr = iio_channel_find_attr(phyChan, "hardwaregain")) {
                iio_attr_write_double(attr, 0.0);
            }
        }

        const char *loChanID = (direction == Direction::Tx) ? "altvoltage1" : "altvoltage0";
        if (iio_channel *loChan = iio_device_find_channel(phy, loChanID, true)) {
            writeLongLong(loChan, "frequency", loHz);
        }

        const char *streamDeviceName = (direction == Direction::Tx) ? "cf-ad9361-dds-core-lpc" : "cf-ad9361-lpc";
        iio_device *dev = iio_context_find_device(ctx_, streamDeviceName);
        if (!dev) {
            reportError(std::string(streamDeviceName) + "デバイスが見つかりません");
            return false;
        }
        device_ = dev;

        // TX方向のDDSトーン生成器(altvoltage0-3)を無効化する(実際の変調波形にDDSトーンが
        // 混入しないよう、LibiioSession.swiftのsetup()と同じ対応)。
        if (direction == Direction::Tx) {
            for (const char *ddsChanID : {"altvoltage0", "altvoltage1", "altvoltage2", "altvoltage3"}) {
                iio_channel *ddsChan = iio_device_find_channel(dev, ddsChanID, true);
                if (!ddsChan) continue;
                if (const iio_attr *rawAttr = iio_channel_find_attr(ddsChan, "raw")) {
                    iio_attr_write_string(rawAttr, "0");
                }
                if (const iio_attr *scaleAttr = iio_channel_find_attr(ddsChan, "scale")) {
                    iio_attr_write_double(scaleAttr, 0.0);
                }
            }
        }

        iio_channel *chI = iio_device_find_channel(dev, "voltage0", isOutput);
        iio_channel *chQ = iio_device_find_channel(dev, "voltage1", isOutput);
        if (!chI || !chQ) {
            reportError("ストリーミングI/Qチャンネルが見つかりません");
            return false;
        }
        channelI_ = chI;
        channelQ_ = chQ;

        iio_channels_mask *m = iio_create_channels_mask(iio_device_get_channels_count(dev));
        if (!m) {
            reportError("channels maskの確保に失敗しました");
            return false;
        }
        mask_ = m;
        iio_channel_enable(chI, m);
        iio_channel_enable(chQ, m);

        iio_buffer *buf = iio_device_get_buffer(dev, 0);
        if (!buf) {
            reportError("バッファの取得に失敗しました");
            return false;
        }
        buffer_ = buf;

        constexpr size_t blockSize = 8192;
        constexpr size_t nBlocks = 32;
        iio_stream *strm = iio_buffer_create_stream(buf, nBlocks, blockSize, m);
        if (!strm || iio_err(strm) != 0) {
            reportError("ストリームの作成に失敗しました");
            return false;
        }
        stream_ = strm;
        return true;
    }

    // ★LibiioSession.swiftのstartStreaming(RXスレッドを.userInteractive、TXスレッドを
    // .utilityに設定)と同じ対応。TX(aff3ctのDVB-S2変調、CPU集約的)とRX(ネットワークI/O)を
    // 同一プロセス内で同時に動かすと、TX側の重いCPU処理がRX側のネットワーク受信タイミングを
    // 圧迫してRXが信号を検出できなくなる事象をiOS版で確認済み。RXスレッドの優先度を上げ、
    // TXスレッドの優先度を下げることで緩和する。
    void startStreaming(Direction direction) {
        if (isStreaming.exchange(true)) return;
        streamThread_ = std::thread([this, direction] {
            constexpr int kRxNice = -10; // 高優先度(Android audio thread相当)
            constexpr int kTxNice = 10;  // 低優先度(background相当)
            setpriority(PRIO_PROCESS, 0, (direction == Direction::Rx) ? kRxNice : kTxNice);
            if (direction == Direction::Tx) txLoop(); else rxLoop();
        });
    }

    void stopStreaming() {
        if (!isStreaming.exchange(false)) return;
        if (stream_) iio_stream_cancel(stream_);
        if (streamThread_.joinable()) streamThread_.join();
    }

    void requestStopStreaming() {
        if (!isStreaming.exchange(false)) return;
        if (stream_) iio_stream_cancel(stream_);
    }

    // ★LibiioSession.swiftのpushTxIQ/ringBufferと同じ理由: std::vector::insert/eraseを
    // 高頻度(数千回/秒)で呼び続けると、eraseのたびに残り要素を先頭へシフトするO(n)コピーが
    // ヒープ操作の頻発と合わさってRSSが際限なく増加する事象をiOS版で実測確認したため、
    // 固定容量の生メモリリングバッファに置き換える。
    void pushTxIQ(const uint8_t *data, size_t len) {
        std::lock_guard<std::mutex> lock(txQueueMutex_);
        if (len > txQueueMaxBytes) {
            // 新規データ自体がリング容量を超える場合は末尾のtxQueueMaxBytes分だけ残す。
            size_t skip = len - txQueueMaxBytes;
            len = txQueueMaxBytes;
            std::memcpy(ringBuffer_, data + skip, len);
            ringReadPos_ = 0;
            ringCount_ = len;
            return;
        }
        if (ringCount_ + len > txQueueMaxBytes) {
            size_t drop = ringCount_ + len - txQueueMaxBytes;
            ringReadPos_ = (ringReadPos_ + drop) % txQueueMaxBytes;
            ringCount_ -= drop;
        }
        size_t writePos = (ringReadPos_ + ringCount_) % txQueueMaxBytes;
        size_t firstChunk = std::min(len, txQueueMaxBytes - writePos);
        std::memcpy(ringBuffer_ + writePos, data, firstChunk);
        if (firstChunk < len) {
            std::memcpy(ringBuffer_, data + firstChunk, len - firstChunk);
        }
        ringCount_ += len;
    }

    int64_t txQueueBytes() {
        std::lock_guard<std::mutex> lock(txQueueMutex_);
        return static_cast<int64_t>(ringCount_);
    }

private:
    iio_context *ctx_ = nullptr;
    bool ownsContext_ = true;
    iio_device *device_ = nullptr;
    iio_channel *channelI_ = nullptr;
    iio_channel *channelQ_ = nullptr;
    iio_channel *phyChan_ = nullptr;
    iio_channels_mask *mask_ = nullptr;
    iio_buffer *buffer_ = nullptr;
    iio_stream *stream_ = nullptr;
    std::thread streamThread_;

    std::mutex txQueueMutex_;
    uint8_t *ringBuffer_ = nullptr;
    size_t ringReadPos_ = 0;
    size_t ringCount_ = 0;

    void reportError(const std::string &message) {
        if (onError) onError(message);
    }

    // AD9361のBBPLL/サンプルクロック関連レジスタは、TX/RXで共有される。現在値と異なる
    // 場合のみ書き込むことで、既に稼働中の側への不要な再キャリブレーションを避ける
    // (LibiioSession.swift writeLongLongと同じ理由)。
    void writeLongLong(iio_channel *channel, const char *name, int64_t value) {
        const iio_attr *attr = iio_channel_find_attr(channel, name);
        if (!attr) return;
        long long current = 0;
        if (iio_attr_read_longlong(attr, &current) == 0 && current == value) return;
        iio_attr_write_longlong(attr, value);
    }

    void txLoop() {
        while (isStreaming) {
            const iio_block *block = iio_stream_get_next_block(stream_);
            if (!block || iio_err(block) != 0) {
                reportError("Txブロック取得に失敗しました");
                break;
            }
            void *start = iio_block_first(block, channelI_);
            void *end = iio_block_end(block);
            if (!start || !end) continue;
            size_t byteCount = static_cast<uint8_t *>(end) - static_cast<uint8_t *>(start);
            size_t sampleCount = byteCount / sizeof(int16_t); // I,Q合わせた総int16要素数

            std::vector<float> floats(sampleCount, 0.0f);
            size_t neededBytes = sampleCount * sizeof(float);
            size_t available;
            {
                std::lock_guard<std::mutex> lock(txQueueMutex_);
                available = std::min(neededBytes, ringCount_);
                if (available > 0) {
                    auto *dstBytes = reinterpret_cast<uint8_t *>(floats.data());
                    size_t firstChunk = std::min(available, txQueueMaxBytes - ringReadPos_);
                    std::memcpy(dstBytes, ringBuffer_ + ringReadPos_, firstChunk);
                    if (firstChunk < available) {
                        std::memcpy(dstBytes + firstChunk, ringBuffer_, available - firstChunk);
                    }
                    ringReadPos_ = (ringReadPos_ + available) % txQueueMaxBytes;
                    ringCount_ -= available;
                }
            }
            size_t starvedSamples = sampleCount - (available / sizeof(float));

            auto *dst = static_cast<int16_t *>(start);
            double sumSq = 0;
            int16_t maxAbs = 0;
            for (size_t i = 0; i < sampleCount; i++) {
                float scaled = floats[i] * txSampleScale;
                if (scaled > 32767.0f) scaled = 32767.0f;
                if (scaled < -32768.0f) scaled = -32768.0f;
                dst[i] = static_cast<int16_t>(scaled);
                sumSq += static_cast<double>(dst[i]) * dst[i];
                maxAbs = std::max(maxAbs, static_cast<int16_t>(std::abs(dst[i])));
            }
            if (sampleCount > 0) {
                lastTxDmaRms = std::sqrt(sumSq / static_cast<double>(sampleCount));
            }
            double starvedPct = sampleCount > 0
                ? (100.0 * static_cast<double>(starvedSamples) / static_cast<double>(sampleCount))
                : 0.0;
            lastTxStarvedPercent = starvedPct;
        }
    }

    void rxLoop() {
        while (isStreaming) {
            const iio_block *block = iio_stream_get_next_block(stream_);
            if (!block || iio_err(block) != 0) {
                reportError("Rxブロック取得に失敗しました");
                break;
            }
            void *start = iio_block_first(block, channelI_);
            void *end = iio_block_end(block);
            if (!start || !end) continue;
            size_t byteCount = static_cast<uint8_t *>(end) - static_cast<uint8_t *>(start);
            size_t sampleCount = byteCount / sizeof(int16_t);
            auto *src = static_cast<int16_t *>(start);

            std::vector<float> floats(sampleCount, 0.0f);
            switch (rxIQMode) {
                case RxIQMode::Normal:
                    for (size_t i = 0; i < sampleCount; i++) floats[i] = static_cast<float>(src[i]) / sampleScale;
                    break;
                case RxIQMode::SwapIQ:
                    for (size_t i = 0; i + 1 < sampleCount; i += 2) {
                        floats[i] = static_cast<float>(src[i + 1]) / sampleScale;
                        floats[i + 1] = static_cast<float>(src[i]) / sampleScale;
                    }
                    break;
                case RxIQMode::Conjugate:
                    for (size_t i = 0; i + 1 < sampleCount; i += 2) {
                        floats[i] = static_cast<float>(src[i]) / sampleScale;
                        floats[i + 1] = -static_cast<float>(src[i + 1]) / sampleScale;
                    }
                    break;
            }
            if (onRxIQ) {
                onRxIQ(reinterpret_cast<const uint8_t *>(floats.data()), floats.size() * sizeof(float));
            }
        }
    }
};

// ---------------------------------------------------------------------------
// FIFOベースの1本のTx/Rxパイプライン。DVBS2Encoder/Decoder+DVBS2TxPipeline/RxPipeline
// (iOS版)を1クラスに統合したもの。
struct Dvbs2Session {
    bool isTx = false;
    std::string modCod = "QPSK-S_3/5";
    // ★aff3ct既定は0.20だが、datvplutofrmファームウェアのpluto_dvb(libdvbmod)は
    // ロールオフ0.35固定・変更不可(RollOff引数はaff3ct RXから見て非対称なので、
    // Plutoのオンボード変調と組み合わせる際はこちらを0.35に合わせる必要がある)。
    double rolloff = 0.2;
    std::string inputFifoPath;
    std::string outputFifoPath;
    int inputWriteFd = -1;
    std::thread runThread;
    std::thread readThread;       // Rx: 入力FIFO open用 / Tx: 未使用
    std::thread outputReadThread; // 出力FIFO読み取りループ用(Tx/Rx共通)
    std::atomic<bool> running{false};

    LibiioSession libiio;

    JavaVM *jvm = nullptr;
    jobject callbackObj = nullptr;
    jmethodID onErrorMethod = nullptr;
    jmethodID onTsDataMethod = nullptr; // Rxのみ

    ~Dvbs2Session() {
        stop();
    }

    JNIEnv *attachEnv(bool *didAttach) {
        JNIEnv *env = nullptr;
        if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
            *didAttach = false;
            return env;
        }
        jvm->AttachCurrentThread(&env, nullptr);
        *didAttach = true;
        return env;
    }

    void reportError(const std::string &message) {
        bool didAttach = false;
        JNIEnv *env = attachEnv(&didAttach);
        jstring jMsg = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(callbackObj, onErrorMethod, jMsg);
        env->DeleteLocalRef(jMsg);
        if (didAttach) jvm->DetachCurrentThread();
    }

    bool prepare(const std::string &plutoUri, const std::string &modCodArg, int64_t bandwidthHz,
                 int64_t sampleRateHz, int64_t loHz, const std::string &rfPort,
                 const std::string &tmpDir, iio_context *sharedContext, double rolloffArg = 0.2) {
        if (running) return false;
        modCod = modCodArg;
        rolloff = rolloffArg;

        inputFifoPath = tmpDir + "/" + makeFifoPath(isTx ? "dvbs2tx_in" : "dvbs2rx_in");
        outputFifoPath = tmpDir + "/" + makeFifoPath(isTx ? "dvbs2tx_out" : "dvbs2rx_out");
        ::unlink(inputFifoPath.c_str());
        ::unlink(outputFifoPath.c_str());
        if (mkfifo(inputFifoPath.c_str(), 0600) != 0 || mkfifo(outputFifoPath.c_str(), 0600) != 0) {
            reportError("FIFO作成に失敗しました(errno=" + std::to_string(errno) + ")");
            return false;
        }

        libiio.onError = [this](const std::string &msg) {
            reportError(std::string(isTx ? "LibiioSession(Tx): " : "LibiioSession(Rx): ") + msg);
        };

        if (sharedContext) {
            libiio.attach(sharedContext);
        } else if (!libiio.connect(plutoUri)) {
            return false;
        }
        if (!libiio.setup(isTx ? LibiioSession::Direction::Tx : LibiioSession::Direction::Rx,
                           bandwidthHz, sampleRateHz, loHz, rfPort)) {
            libiio.disconnect();
            return false;
        }
        return true;
    }

    void beginStreaming(const std::string &filesDir) {
        if (running.exchange(true)) return;

        if (isTx) {
            libiio.startStreaming(LibiioSession::Direction::Tx);
            runThread = std::thread([this, filesDir] { runTx(filesDir); });
            // datv_dvbs2_tx_run側は入力FIFOのreaderとして自分でopenする。POSIX FIFOは
            // reader/writer双方がopenするまでopen(2)がブロックするため、writer側(こちら)
            // も別スレッドで非同期にopenする(DVBS2Encoder.swift startと同じ理由。これを
            // 忘れるとdvbs2_tx側のopen(2)が永遠にブロックし、TX診断が"8/8秒"から進まなくなる)。
            readThread = std::thread([this] {
                inputWriteFd = ::open(inputFifoPath.c_str(), O_WRONLY);
                if (inputWriteFd < 0) {
                    reportError("入力FIFOのopenに失敗しました");
                }
            });
            outputReadThread = std::thread([this] { readOutputLoopTx(); });
        } else {
            runThread = std::thread([this, filesDir] { runRx(filesDir); });
            // 入力(IQサンプル)writerはdvbs2_rx側がEOFでクラッシュしないよう、
            // stop()まで開いたままにする(DVBS2Decoder.swiftと同じ理由)。
            readThread = std::thread([this] {
                inputWriteFd = ::open(inputFifoPath.c_str(), O_WRONLY);
                if (inputWriteFd < 0) {
                    reportError("入力FIFOのopenに失敗しました");
                }
            });
            libiio.onRxIQ = [this](const uint8_t *data, size_t len) {
                writeIQToDecoder(data, len);
            };
            libiio.startStreaming(LibiioSession::Direction::Rx);
            outputReadThread = std::thread([this] { readOutputLoopRx(); });
        }
    }

    // Tx: TSMuxer/TSPacketPackerが生成したTSデータを入力FIFOへ書き込む。
    void write(const uint8_t *data, size_t len) {
        if (!running || inputWriteFd < 0) return;
        ssize_t n = ::write(inputWriteFd, data, len);
        (void)n; // stop()と競合した際に発生しうるが、書き込み側は既に停止処理中なので無視してよい
    }

    void stop() {
        if (!running.exchange(false)) return;

        if (isTx) {
            datv_dvbs2_tx_request_stop();
        } else {
            datv_dvbs2_rx_request_stop();
        }

        if (inputWriteFd >= 0) { ::close(inputWriteFd); inputWriteFd = -1; }
        libiio.stopStreaming();
        libiio.disconnect();

        if (runThread.joinable()) runThread.join();
        if (readThread.joinable()) readThread.join();
        if (outputReadThread.joinable()) outputReadThread.join();

        if (!inputFifoPath.empty()) ::unlink(inputFifoPath.c_str());
        if (!outputFifoPath.empty()) ::unlink(outputFifoPath.c_str());
    }

    void requestStop() {
        if (!running.exchange(false)) return;

        if (isTx) {
            datv_dvbs2_tx_request_stop();
        } else {
            datv_dvbs2_rx_request_stop();
        }

        if (inputWriteFd >= 0) { ::close(inputWriteFd); inputWriteFd = -1; }
        libiio.requestStopStreaming();
    }

private:
    void writeIQToDecoder(const uint8_t *data, size_t len) {
        // stop()でFIFOを閉じた直後にlibiioのRxスレッドから書き込みが来る競合がありうる。
        // inputWriteFdは書き込み専用に事前openしてあるため、単にrunning=falseで無視する。
        if (!running || inputWriteFd < 0) return;
        ssize_t n = ::write(inputWriteFd, data, len);
        (void)n;
    }

    void runTx(const std::string &filesDir) {
        setupWorkingDirectory(filesDir + "/dvbs2_runtime/cwd");

        std::vector<std::string> argStrings = {
            "dvbs2_tx", "--mod-cod", modCod,
            "--src-type", "USER_BIN", "--src-path", inputFifoPath, "--src-fifo",
            "--rad-type", "USER_BIN", "--rad-tx-file-path", outputFifoPath,
            "--tx-time-limit", "0",
        };
        std::vector<char *> argv;
        for (auto &s : argStrings) argv.push_back(const_cast<char *>(s.c_str()));

        int result = datv_dvbs2_tx_run(static_cast<int>(argv.size()), argv.data());
        if (result != 0) {
            reportError("datv_dvbs2_tx_run異常終了(code=" + std::to_string(result) + ")");
        }
    }

    void runRx(const std::string &filesDir) {
        setupWorkingDirectory(filesDir + "/dvbs2_runtime/cwd");

        std::vector<std::string> argStrings = {
            "dvbs2_rx", "--mod-cod", modCod,
            "--rad-type", "USER_BIN", "--rad-rx-file-path", inputFifoPath,
            "--snk-path", outputFifoPath, "--rx-time-limit", "0",
            "--sched-r", "2", "--sched-p", "10",
            "--shp-rolloff", std::to_string(rolloff),
        };
        std::vector<char *> argv;
        for (auto &s : argStrings) argv.push_back(const_cast<char *>(s.c_str()));

        int result = datv_dvbs2_rx_run(static_cast<int>(argv.size()), argv.data());
        if (result != 0) {
            reportError("datv_dvbs2_rx_run異常終了(code=" + std::to_string(result) + ")");
        }
    }

    // Tx: dvbs2_txが出力FIFOへ書いたIQサンプル(float)をlibiioのtxQueueへ渡す。
    //
    // ★ループ条件をrunning(stop()で即falseになる)ではなくread()の戻り値のみにしている。
    // stop()はdatv_dvbs2_tx_request_stop()を呼ぶだけで、dvbs2_tx本体(runThread)が実際に
    // 終了しoutput_fileをcloseするまでには時間差がある。runningでこのループを早期に抜けて
    // fdをcloseすると、まだ書き込み中のdvbs2_tx側がwriter-without-readerの状態になり、
    // Radio_user_binary::_sendがEPIPE相当の書き込み失敗("Unknown error during file reading")
    // で例外を投げてTX診断がNG判定になる事象を実機で確認した。runThread側が自然にFIFOを
    // 閉じてread()が0(EOF)を返すまで読み切ることで、これを防ぐ。
    void readOutputLoopTx() {
        int fd = ::open(outputFifoPath.c_str(), O_RDONLY);
        if (fd < 0) {
            reportError("出力FIFOのopenに失敗しました");
            return;
        }
        std::vector<uint8_t> buf(65536);
        while (true) {
            ssize_t n = ::read(fd, buf.data(), buf.size());
            if (n <= 0) break;
            if (running) libiio.pushTxIQ(buf.data(), static_cast<size_t>(n));
        }
        ::close(fd);
    }

    // Rx: dvbs2_rxが出力FIFOへ書いた復調後TSデータをKotlinへコールバックする。
    // ★readOutputLoopTxと同じ理由でread()の戻り値のみをループ条件にする。
    void readOutputLoopRx() {
        int fd = ::open(outputFifoPath.c_str(), O_RDONLY);
        if (fd < 0) {
            reportError("出力FIFOのopenに失敗しました");
            return;
        }
        bool didAttach = false;
        JNIEnv *env = attachEnv(&didAttach);

        std::vector<uint8_t> buf(65536);
        while (true) {
            ssize_t n = ::read(fd, buf.data(), buf.size());
            if (n <= 0) break;
            if (!running) continue;
            jbyteArray array = env->NewByteArray(static_cast<jsize>(n));
            env->SetByteArrayRegion(array, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(buf.data()));
            env->CallVoidMethod(callbackObj, onTsDataMethod, array);
            env->DeleteLocalRef(array);
        }
        ::close(fd);
        if (didAttach) jvm->DetachCurrentThread();
    }
};

} // namespace

namespace {

std::mutex g_guardMutex;
thread_local sig_atomic_t g_inGuardedCall = 0;
thread_local sigjmp_buf g_guardedJmpBuf;
thread_local int g_guardedSignal = 0;
thread_local void *g_guardedFaultAddr = nullptr;
struct sigaction g_oldSegv {};
struct sigaction g_oldBus {};

void invokeOldHandler(const struct sigaction &old, int signum, siginfo_t *info, void *ucontext) {
    if (old.sa_flags & SA_SIGINFO) {
        if (old.sa_sigaction) {
            old.sa_sigaction(signum, info, ucontext);
            return;
        }
    } else if (old.sa_handler == SIG_IGN) {
        return;
    } else if (old.sa_handler != SIG_DFL && old.sa_handler != nullptr) {
        old.sa_handler(signum);
        return;
    }
    signal(signum, SIG_DFL);
    raise(signum);
}

void guardedSignalHandler(int signum, siginfo_t *info, void *ucontext) {
    if (g_inGuardedCall) {
        g_guardedSignal = signum;
        g_guardedFaultAddr = info ? info->si_addr : nullptr;
        siglongjmp(g_guardedJmpBuf, 1);
    }
    // 保護区間外(この呼び出しを行っていないスレッド、または未処理の別クラッシュ)は
    // 元のハンドラ(ART/tombstonedのシグナルチェーン)へ必ず委譲する。ここで握りつぶすと
    // 本来のクラッシュまで隠してしまう。
    invokeOldHandler(signum == SIGSEGV ? g_oldSegv : g_oldBus, signum, info, ucontext);
}

/**
 * vendored libiio(third_party、ソース改変なし)は、Pluto側でTX/RXバッファが他プロセス
 * (pluto_dvbデーモン等)に掴まれたままの状態でタイムアウトした際、内部でエラー符号化
 * ポインタ(ERR_PTR)を正しくチェックせずに参照してSIGSEGV/SIGBUSする既知の不具合を持つ
 * (実機で確認済み: signal 11、fault addrがERR_PTR(-ETIMEDOUT)+8)。この関数は
 * Dvbs2Session::prepare()の呼び出しだけをシグナルハンドラで保護し、アプリ全体を巻き込む
 * クラッシュではなく通常のエラー(reportError経由でtxError/rxErrorやログに理由・対処法が
 * 表示される)として扱えるようにする。
 *
 * 保護区間はミューテックスで排他したこの1呼び出しに限定し、既存のシグナルハンドラは
 * 必ず保存・復元して未処理時は委譲する(他スレッドで起きる本来のクラッシュを隠さないため)。
 * 捕捉後はsession(特にlibiioの内部状態)が破損している可能性があるため、以後の
 * stop()/disconnect()は行うが、再利用はせず次回呼び出しで作り直す前提とする。
 */
bool guardedPrepare(Dvbs2Session *session, const std::string &uri, const std::string &modCod,
                     int64_t bandwidthHz, int64_t sampleRateHz, int64_t loHz,
                     const std::string &rfPort, const std::string &tmpDir, double rolloff) {
    std::lock_guard<std::mutex> lock(g_guardMutex);

    struct sigaction newAction {};
    newAction.sa_sigaction = guardedSignalHandler;
    newAction.sa_flags = SA_SIGINFO;
    sigemptyset(&newAction.sa_mask);
    sigaction(SIGSEGV, &newAction, &g_oldSegv);
    sigaction(SIGBUS, &newAction, &g_oldBus);

    bool ok;
    if (sigsetjmp(g_guardedJmpBuf, 1) == 0) {
        g_inGuardedCall = 1;
        ok = session->prepare(uri, modCod, bandwidthHz, sampleRateHz, loHz, rfPort, tmpDir, nullptr,
                               rolloff);
        g_inGuardedCall = 0;
    } else {
        g_inGuardedCall = 0;
        ok = false;
        char msg[192];
        snprintf(msg, sizeof(msg),
                 "Pluto接続処理が異常終了しました(signal=%d, addr=%p)。"
                 "原因: Pluto側のTX/RXバッファが以前のセッションから解放されていない可能性があります。"
                 "対処: Plutoの電源を再投入してから再試行してください。",
                 g_guardedSignal, g_guardedFaultAddr);
        session->reportError(std::string(msg));
    }

    sigaction(SIGSEGV, &g_oldSegv, nullptr);
    sigaction(SIGBUS, &g_oldBus, nullptr);
    return ok;
}

} // namespace

// MemoryDebugBridge.swift相当。iOSはMachのtask_info(MACH_TASK_BASIC_INFO)を使うが、
// AndroidはLinux(Bionic)なので/proc/self/statusのVmRSSから読む。dvbs2_rx_lib.cppが
// Rx開始直後のメモリ急増の切り分けに使うデバッグ専用ブリッジ(datv_dvbs2_bridge.h参照)。
extern "C" double shonan_debug_memory_mb() {
    std::ifstream statusFile("/proc/self/status");
    std::string line;
    while (std::getline(statusFile, line)) {
        if (line.compare(0, 6, "VmRSS:") == 0) {
            long kb = 0;
            sscanf(line.c_str() + 6, "%ld", &kb);
            return static_cast<double>(kb) / 1024.0;
        }
    }
    return -1;
}

// ---------------------------------------------------------------------------
// RSSI測定用: IQストリーミングを開始せず、AD9361の受信LOとRSSI属性だけを操作する
// 小さなセッション。送受信パイプラインとは同時に使用しない(Android UI側で開始を抑止する)。
class RssiSession {
public:
    bool open(const char *uri) {
        ctx_ = iio_create_context(nullptr, uri);
        if (!ctx_ || iio_err(ctx_) != 0) {
            if (ctx_) iio_context_destroy(ctx_);
            ctx_ = nullptr;
            return false;
        }
        // ★30秒だと、Wi-Fiの再接続等でPlutoとのTCP接続が切れた際に1回の測定が
        // 30秒以上戻らず、「検索停止」も効かないまま画面が止まって見えた(タブレット版の実機で確認)。
        // Shonan_Lite-winのiio_attr呼び出し(timeout=2秒)に合わせて2秒にする。
        iio_context_set_timeout(ctx_, 2000);
        auto *phy = iio_context_find_device(ctx_, "ad9361-phy");
        if (!phy) return false;
        phyChan_ = iio_device_find_channel(phy, "voltage0", false);
        loChan_ = iio_device_find_channel(phy, "altvoltage0", true);
        if (!phyChan_ || !loChan_) return false;
        if (const auto *attr = iio_channel_find_attr(phyChan_, "rf_port_select")) {
            iio_attr_write_string(attr, "A_BALANCED");
        }
        // RXゲイン(AGC/手動)は呼び出し側がsetRxGain()で設定する(設定２タブと同じ設定値)。
        return true;
    }

    bool setRxGain(bool agcEnabled, int gainDb) {
        if (!phyChan_) return false;
        const auto *mode = iio_channel_find_attr(phyChan_, "gain_control_mode");
        if (!mode || iio_attr_write_string(mode, agcEnabled ? "slow_attack" : "manual") < 0) return false;
        if (agcEnabled) return true;
        const auto *gain = iio_channel_find_attr(phyChan_, "hardwaregain");
        return gain && iio_attr_write_longlong(gain, gainDb) >= 0;
    }

    double measure(int64_t frequencyHz) {
        if (!loChan_ || !phyChan_) return NAN;
        const auto *frequency = iio_channel_find_attr(loChan_, "frequency");
        const auto *rssi = iio_channel_find_attr(phyChan_, "rssi");
        if (!frequency || !rssi || iio_attr_write_longlong(frequency, frequencyHz) < 0) return NAN;
        char buffer[64]{};
        if (iio_attr_read_raw(rssi, buffer, sizeof(buffer)) <= 0) return NAN;
        return strtod(buffer, nullptr);
    }

    void close() {
        if (ctx_) iio_context_destroy(ctx_);
        ctx_ = nullptr;
        phyChan_ = nullptr;
        loChan_ = nullptr;
    }

    ~RssiSession() { close(); }

private:
    iio_context *ctx_ = nullptr;
    iio_channel *phyChan_ = nullptr;
    iio_channel *loChan_ = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_dvbs2_RssiNativeSession_nativeOpen(
        JNIEnv *env, jobject /* thiz */, jstring jUri) {
    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    auto *session = new RssiSession();
    bool ok = session->open(uri);
    env->ReleaseStringUTFChars(jUri, uri);
    if (!ok) { delete session; return 0; }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_shinjo_shonanandroid_dvbs2_RssiNativeSession_nativeMeasure(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle, jlong frequencyHz) {
    auto *session = reinterpret_cast<RssiSession *>(handle);
    return session ? session->measure(frequencyHz) : NAN;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2_RssiNativeSession_nativeSetRxGain(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle, jboolean agcEnabled, jint gainDb) {
    auto *session = reinterpret_cast<RssiSession *>(handle);
    return session && session->setRxGain(agcEnabled == JNI_TRUE, gainDb) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_RssiNativeSession_nativeClose(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    delete reinterpret_cast<RssiSession *>(handle);
}

// ---------------------------------------------------------------------------
// 実運用TX/RX開始時にPlutoのLOだけを設定する一時セッション。TS本体はUDPで別経路
// (TxController/RxController)を通るため、IQストリーミングは開始しない。
class PlutoTunerSession {
public:
    bool open(const char *uri) {
        ctx_ = iio_create_context(nullptr, uri);
        if (!ctx_ || iio_err(ctx_) != 0) {
            if (ctx_) iio_context_destroy(ctx_);
            ctx_ = nullptr;
            return false;
        }
        iio_context_set_timeout(ctx_, 30000);
        phy_ = iio_context_find_device(ctx_, "ad9361-phy");
        return phy_ != nullptr;
    }

    bool tune(bool isTx, int64_t frequencyHz) {
        if (!phy_) return false;
        auto *loChan = iio_device_find_channel(phy_, isTx ? "altvoltage1" : "altvoltage0", true);
        if (!loChan) return false;
        // SSB/FMタブ(ssbfm_bridge.cpp)は非送信中にTX LO、送信中にRX LOを電源断するため、
        // 異常終了等で電源断のまま残っていてもDATV側で確実に復帰させる。
        if (const auto *powerdown = iio_channel_find_attr(loChan, "powerdown")) {
            (void)iio_attr_write_bool(powerdown, false);
        }
        const auto *frequency = iio_channel_find_attr(loChan, "frequency");
        return frequency && iio_attr_write_longlong(frequency, frequencyHz) >= 0;
    }

    void close() {
        if (ctx_) iio_context_destroy(ctx_);
        ctx_ = nullptr;
        phy_ = nullptr;
    }

    ~PlutoTunerSession() { close(); }

private:
    iio_context *ctx_ = nullptr;
    iio_device *phy_ = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_dvbs2_PlutoTuner_nativeOpen(
        JNIEnv *env, jobject /* thiz */, jstring jUri) {
    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    auto *session = new PlutoTunerSession();
    bool ok = session->open(uri);
    env->ReleaseStringUTFChars(jUri, uri);
    if (!ok) { delete session; return 0; }
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2_PlutoTuner_nativeTune(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle, jboolean isTx, jlong frequencyHz) {
    auto *session = reinterpret_cast<PlutoTunerSession *>(handle);
    return session ? static_cast<jboolean>(session->tune(isTx, frequencyHz)) : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_PlutoTuner_nativeClose(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    delete reinterpret_cast<PlutoTunerSession *>(handle);
}

// ---------------------------------------------------------------------------
// JNI: Dvbs2TxPipeline / Dvbs2RxPipeline (共通のネイティブ実装、isTxで分岐)

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *session = new Dvbs2Session();
    session->isTx = true;
    env->GetJavaVM(&session->jvm);
    session->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    session->onErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativePrepare(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jUri, jstring jModCod,
        jlong bandwidthHz, jlong sampleRateHz, jlong loHz, jstring jTmpDir) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    const char *modCod = env->GetStringUTFChars(jModCod, nullptr);
    const char *tmpDir = env->GetStringUTFChars(jTmpDir, nullptr);
    bool ok = guardedPrepare(session, uri, modCod, bandwidthHz, sampleRateHz, loHz, "A", tmpDir, 0.2);
    env->ReleaseStringUTFChars(jUri, uri);
    env->ReleaseStringUTFChars(jModCod, modCod);
    env->ReleaseStringUTFChars(jTmpDir, tmpDir);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeBeginStreaming(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jFilesDir) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    const char *filesDir = env->GetStringUTFChars(jFilesDir, nullptr);
    session->beginStreaming(filesDir);
    env->ReleaseStringUTFChars(jFilesDir, filesDir);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeWrite(
        JNIEnv *env, jobject /* thiz */, jlong handle, jbyteArray jData) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    jsize len = env->GetArrayLength(jData);
    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    session->write(reinterpret_cast<const uint8_t *>(data), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeGetStarvedPercent(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    return session->libiio.lastTxStarvedPercent.load();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeGetDmaRms(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    return session->libiio.lastTxDmaRms.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    reinterpret_cast<Dvbs2Session *>(handle)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeRequestStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    reinterpret_cast<Dvbs2Session *>(handle)->requestStop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2TxPipeline_nativeDestroy(
        JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    env->DeleteGlobalRef(session->callbackObj);
    delete session;
}

// ---------------------------------------------------------------------------
// JNI: Dvbs2RxPipeline

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *session = new Dvbs2Session();
    session->isTx = false;
    env->GetJavaVM(&session->jvm);
    session->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    session->onErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
    session->onTsDataMethod = env->GetMethodID(cls, "onNativeTsData", "([B)V");
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativePrepare(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jUri, jstring jModCod,
        jlong bandwidthHz, jlong sampleRateHz, jlong loHz, jstring jRfPort, jstring jTmpDir,
        jdouble rolloff) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    const char *modCod = env->GetStringUTFChars(jModCod, nullptr);
    const char *rfPort = env->GetStringUTFChars(jRfPort, nullptr);
    const char *tmpDir = env->GetStringUTFChars(jTmpDir, nullptr);
    bool ok = guardedPrepare(session, uri, modCod, bandwidthHz, sampleRateHz, loHz, rfPort, tmpDir, rolloff);
    env->ReleaseStringUTFChars(jUri, uri);
    env->ReleaseStringUTFChars(jModCod, modCod);
    env->ReleaseStringUTFChars(jRfPort, rfPort);
    env->ReleaseStringUTFChars(jTmpDir, tmpDir);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativeBeginStreaming(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jFilesDir) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    const char *filesDir = env->GetStringUTFChars(jFilesDir, nullptr);
    session->beginStreaming(filesDir);
    env->ReleaseStringUTFChars(jFilesDir, filesDir);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativeStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    reinterpret_cast<Dvbs2Session *>(handle)->stop();
}

// ★datv_dvbs2_rx_is_locked()はセッション横断のグローバル状態(dvbs2_rx_lib.cpp参照、
// 同時に1本しかRXセッションを持たない前提)なのでhandleは使わない。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativeIsLocked(
        JNIEnv * /* env */, jobject /* thiz */, jlong /* handle */) {
    return static_cast<jboolean>(datv_dvbs2_rx_is_locked());
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2_Dvbs2RxPipeline_nativeDestroy(
        JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *session = reinterpret_cast<Dvbs2Session *>(handle);
    env->DeleteGlobalRef(session->callbackObj);
    delete session;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /* vm */, void * /* reserved */) {
    redirectStdioToLogcat();
    return JNI_VERSION_1_6;
}
