// SSB/FM送受信タブの信号処理(Langstone-V2 Lang_TRX_Pluto.pyのC++移植)。
// JNI・libiio・AAudioに依存しないので、ssbfm_dsp_test.cppから単体で検証できる。
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace ssbfm {

using cf = std::complex<float>;
constexpr double kPi = 3.14159265358979323846;

constexpr int kIqRate = 528000;
constexpr int kAudioRate = 48000;
constexpr int kDecim = kIqRate / kAudioRate; // 11
constexpr int kAudioPerBlock = 384;
constexpr int kIqPerBlock = kAudioPerBlock * kDecim; // 4224(8ms)
constexpr int kFftSize = 512;

// Pluto(AD9361)のIQ形式: 受信は12bit符号拡張、送信は12bitをMSB詰め(下位4bit無視)。
constexpr float kRxScale = 1.0f / 2048.0f;
constexpr float kTxScale = 32767.0f * 0.8f;

enum Mode { MODE_USB = 0, MODE_LSB = 1, MODE_FM = 2 };

// ---------------------------------------------------------------------------
// フィルタ設計(GNU Radio firdesのハミング窓設計と同等)

inline int tapsForTransition(double fs, double transitionHz) {
    int n = static_cast<int>(std::ceil(3.3 * fs / transitionHz));
    return (n % 2 == 0) ? n + 1 : n;
}

inline std::vector<float> lowpassTaps(int ntaps, double fs, double cutoff) {
    std::vector<float> taps(ntaps);
    const double m = (ntaps - 1) / 2.0;
    const double fc = cutoff / fs;
    double sum = 0;
    for (int i = 0; i < ntaps; i++) {
        const double x = i - m;
        const double sinc = (x == 0) ? 2.0 * fc : std::sin(2.0 * kPi * fc * x) / (kPi * x);
        const double w = 0.54 - 0.46 * std::cos(2.0 * kPi * i / (ntaps - 1));
        taps[i] = static_cast<float>(sinc * w);
        sum += taps[i];
    }
    for (auto &t : taps) t = static_cast<float>(t / sum);
    return taps;
}

inline std::vector<float> bandpassTaps(int ntaps, double fs, double low, double high) {
    auto hi = lowpassTaps(ntaps, fs, high);
    auto lo = lowpassTaps(ntaps, fs, low);
    for (int i = 0; i < ntaps; i++) hi[i] -= lo[i];
    return hi;
}

// firdes.complex_band_pass相当: 低域通過フィルタを通過帯域の中心へ周波数シフトする。
inline std::vector<cf> complexBandpassTaps(int ntaps, double fs, double low, double high) {
    auto lp = lowpassTaps(ntaps, fs, (high - low) / 2.0);
    const double center = (high + low) / 2.0;
    const double m = (ntaps - 1) / 2.0;
    std::vector<cf> taps(ntaps);
    for (int i = 0; i < ntaps; i++) {
        const double ph = 2.0 * kPi * center * (i - m) / fs;
        taps[i] = cf(static_cast<float>(lp[i] * std::cos(ph)), static_cast<float>(lp[i] * std::sin(ph)));
    }
    return taps;
}

// ---------------------------------------------------------------------------
// FIR(遅延線を2倍長で持ち、常に連続領域で内積を取る)

template <typename T>
class DelayLine {
public:
    void reset(int n) {
        n_ = n;
        buf_.assign(2 * n, T{});
        pos_ = 0;
    }
    // 追加後の窓: window()[0]が最新、window()[k]がk個前の入力。
    void push(T x) {
        pos_ = (pos_ == 0) ? n_ - 1 : pos_ - 1;
        buf_[pos_] = x;
        buf_[pos_ + n_] = x;
    }
    const T *window() const { return &buf_[pos_]; }
    int size() const { return n_; }

private:
    std::vector<T> buf_;
    int n_ = 0;
    int pos_ = 0;
};

inline cf dotCR(const cf *x, const float *h, int n) {
    float re = 0, im = 0;
    for (int k = 0; k < n; k++) {
        re += x[k].real() * h[k];
        im += x[k].imag() * h[k];
    }
    return {re, im};
}

inline cf dotCC(const cf *x, const cf *h, int n) {
    float re = 0, im = 0;
    for (int k = 0; k < n; k++) {
        re += x[k].real() * h[k].real() - x[k].imag() * h[k].imag();
        im += x[k].real() * h[k].imag() + x[k].imag() * h[k].real();
    }
    return {re, im};
}

inline float dotRR(const float *x, const float *h, int n) {
    float acc = 0;
    for (int k = 0; k < n; k++) acc += x[k] * h[k];
    return acc;
}

class FirCC {
public:
    void setTaps(std::vector<cf> taps) {
        taps_ = std::move(taps);
        line_.reset(static_cast<int>(taps_.size()));
    }
    cf filter(cf x) {
        line_.push(x);
        return dotCC(line_.window(), taps_.data(), line_.size());
    }

private:
    std::vector<cf> taps_;
    DelayLine<cf> line_;
};

class FirRR {
public:
    void setTaps(std::vector<float> taps) {
        taps_ = std::move(taps);
        line_.reset(static_cast<int>(taps_.size()));
    }
    float filter(float x) {
        line_.push(x);
        return dotRR(line_.window(), taps_.data(), line_.size());
    }

private:
    std::vector<float> taps_;
    DelayLine<float> line_;
};

// 周波数変換済みの528ksps複素信号を1/11に間引く。
class DecimatorCR {
public:
    void setTaps(std::vector<float> taps, int decim) {
        taps_ = std::move(taps);
        decim_ = decim;
        line_.reset(static_cast<int>(taps_.size()));
        phase_ = 0;
    }
    // 出力が得られたらtrue。
    bool push(cf x, cf *out) {
        line_.push(x);
        if (++phase_ < decim_) return false;
        phase_ = 0;
        *out = dotCR(line_.window(), taps_.data(), line_.size());
        return true;
    }

private:
    std::vector<float> taps_;
    DelayLine<cf> line_;
    int decim_ = 1;
    int phase_ = 0;
};

// 48kHz複素信号を11倍に補間する(ポリフェーズ)。
class InterpolatorCR {
public:
    void setTaps(const std::vector<float> &taps, int interp) {
        interp_ = interp;
        perPhase_ = static_cast<int>((taps.size() + interp - 1) / interp);
        phases_.assign(interp, std::vector<float>(perPhase_, 0.0f));
        for (size_t i = 0; i < taps.size(); i++) {
            phases_[i % interp][i / interp] = taps[i] * static_cast<float>(interp);
        }
        line_.reset(perPhase_);
    }
    void push(cf x, cf *out) {
        line_.push(x);
        for (int p = 0; p < interp_; p++) out[p] = dotCR(line_.window(), phases_[p].data(), perPhase_);
    }

private:
    std::vector<std::vector<float>> phases_;
    DelayLine<cf> line_;
    int interp_ = 1;
    int perPhase_ = 1;
};

// ---------------------------------------------------------------------------
// FFT(基数2、スペクトル/ウォーターフォール表示用)

class Fft {
public:
    explicit Fft(int n) : n_(n), twiddle_(n / 2), window_(n), work_(n) {
        for (int i = 0; i < n / 2; i++) {
            twiddle_[i] = std::polar(1.0f, static_cast<float>(-2.0 * kPi * i / n));
        }
        for (int i = 0; i < n; i++) {
            window_[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * i / (n - 1)));
        }
    }
    // inをn点分受け取り、fftshift済み(先頭が-fs/2)の電力(線形)を返す。
    void power(const cf *in, float *out) {
        auto &a = work_;
        for (int i = 0; i < n_; i++) a[i] = in[i] * window_[i];
        for (int i = 1, j = 0; i < n_; i++) {
            int bit = n_ >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(a[i], a[j]);
        }
        for (int len = 2; len <= n_; len <<= 1) {
            const int step = n_ / len;
            for (int i = 0; i < n_; i += len) {
                for (int k = 0; k < len / 2; k++) {
                    cf u = a[i + k];
                    cf v = a[i + k + len / 2] * twiddle_[k * step];
                    a[i + k] = u + v;
                    a[i + k + len / 2] = u - v;
                }
            }
        }
        // ハン窓のコヒーレント利得(0.5)を補正し、フルスケール正弦波が0dBになるようにする。
        const float norm = 4.0f / (static_cast<float>(n_) * static_cast<float>(n_));
        for (int i = 0; i < n_; i++) out[i] = std::norm(a[(i + n_ / 2) % n_]) * norm;
    }

private:
    int n_;
    std::vector<cf> twiddle_;
    std::vector<float> window_;
    std::vector<cf> work_;
};

// ---------------------------------------------------------------------------
// 受信: Pluto IQ(528ksps) → 48kHz音声

class RxDsp {
public:
    std::atomic<int> mode{MODE_USB};
    std::atomic<float> afGain{0.5f};
    /** 希望周波数が受信LOから何Hz上にあるか(Langstoneの方式で+50〜+150kHz)。 */
    std::atomic<int> ncoOffsetHz{0};
    std::atomic<bool> muted{false};
    std::atomic<float> squelchDb{-200.0f};
    /** 現在のPluto受信利得。Sメーターをアンテナ端の相対値にするため差し引く。 */
    std::atomic<float> rxGainDb{0.0f};

    std::atomic<float> signalDb{-200.0f};
    std::atomic<bool> squelchOpen{true};

    RxDsp() { reset(); }

    void reset() {
        decim_.setTaps(lowpassTaps(tapsForTransition(kIqRate, 8000), kIqRate, 24000), kDecim);
        audioFilter_.setTaps(bandpassTaps(tapsForTransition(kAudioRate, 250), kAudioRate, 250, 3000));
        filterDirty_ = true;
        ncoPhase_ = 0.0;
        fftFill_ = 0;
        fmPrev_ = cf(1.0f, 0.0f);
        deemph_ = 0.0f;
        agcEnv_ = 1e-3f;
        levelSmooth_ = -200.0f;
        muteGain_ = 0.0f;
        std::lock_guard<std::mutex> lock(spectrumMutex_);
        spectrumValid_ = false;
    }

    void markFilterDirty() { filterDirty_ = true; }

    // count組のIQ(int16のI,Q交互)を処理し、48kHzの音声をaudioへ書いてその数を返す。
    // audioはcount/kDecim+1要素以上を用意すること。
    int process(const int16_t *iq, size_t count, float *audio) {
        if (filterDirty_.exchange(false) || channelMode_ != mode) updateChannelFilter();

        const double inc = -2.0 * kPi * ncoOffsetHz.load() / kIqRate;
        cf rot(static_cast<float>(std::cos(ncoPhase_)), static_cast<float>(std::sin(ncoPhase_)));
        const cf step(static_cast<float>(std::cos(inc)), static_cast<float>(std::sin(inc)));
        const bool isFm = channelMode_ == MODE_FM;
        const float af = afGain.load();
        const bool isMuted = muted.load();
        int nAudio = 0;
        double power = 0.0;
        for (size_t i = 0; i < count; i++) {
            const cf x(iq[2 * i] * kRxScale, iq[2 * i + 1] * kRxScale);
            const cf mixed = x * rot;
            rot *= step;
            cf base;
            if (!decim_.push(mixed, &base)) continue;

            fftIn_[fftFill_++] = base;
            if (fftFill_ == kFftSize) {
                updateSpectrum();
                fftFill_ = 0;
            }

            const cf ch = channel_.filter(base);
            power += std::norm(ch);
            float y;
            if (isFm) {
                // 直交復調(±5kHz偏移で±1)→75usディエンファシス(analog.nbfm_rx相当)
                const float d = std::arg(ch * std::conj(fmPrev_));
                fmPrev_ = ch;
                const float demod = d * static_cast<float>(kAudioRate / (2.0 * kPi * 5000.0));
                deemph_ += 0.2425f * (demod - deemph_);
                y = deemph_ * 0.5f;
            } else {
                // 実部を取り出してAGC(analog.agc3_cc相当: 速いアタック・遅いディケイ)
                const float s = ch.real();
                const float a = std::fabs(s);
                if (a > agcEnv_) agcEnv_ += 0.02f * (a - agcEnv_);
                else agcEnv_ += 0.00005f * (a - agcEnv_);
                agcEnv_ = std::max(agcEnv_, 1e-6f);
                y = s * std::min(0.25f / agcEnv_, 3000.0f);
            }
            y = audioFilter_.filter(y);

            const bool open = squelchOpen.load() && !isMuted;
            muteGain_ += ((open ? 1.0f : 0.0f) - muteGain_) * 0.005f;
            y *= af * af * 4.0f * muteGain_;
            audio[nAudio++] = std::max(-1.0f, std::min(1.0f, y));
        }
        ncoPhase_ = std::arg(rot);

        if (nAudio > 0) {
            // Sメーター: チャンネル内電力(dBFS)から現在のRF利得を差し引いた相対値
            const float dbfs = 10.0f * std::log10(static_cast<float>(power / nAudio) + 1e-20f);
            const float level = dbfs - rxGainDb.load();
            levelSmooth_ = (level > levelSmooth_) ? level : levelSmooth_ + (level - levelSmooth_) * 0.1f;
            signalDb = levelSmooth_;
            const float sql = squelchDb.load();
            if (sql <= -199.0f) squelchOpen = true;
            else if (levelSmooth_ > sql + 1.5f) squelchOpen = true;
            else if (levelSmooth_ < sql - 1.5f) squelchOpen = false;
        }
        return nAudio;
    }

    // fftshift済み(先頭が-24kHz)の電力dB。
    bool copySpectrum(float *out, int n) {
        std::lock_guard<std::mutex> lock(spectrumMutex_);
        if (!spectrumValid_ || n != kFftSize) return false;
        for (int i = 0; i < kFftSize; i++) out[i] = 10.0f * std::log10(spectrumAvg_[i] + 1e-20f);
        return true;
    }

private:
    DecimatorCR decim_;
    FirCC channel_;
    FirRR audioFilter_;
    std::atomic<bool> filterDirty_{true};
    int channelMode_ = -1;
    double ncoPhase_ = 0.0;
    cf fmPrev_{1.0f, 0.0f};
    float deemph_ = 0.0f;
    float agcEnv_ = 1e-3f;
    float levelSmooth_ = -200.0f;
    float muteGain_ = 0.0f;

    Fft fft_{kFftSize};
    cf fftIn_[kFftSize];
    int fftFill_ = 0;
    float fftPower_[kFftSize];
    float spectrumAvg_[kFftSize];
    bool spectrumValid_ = false;
    std::mutex spectrumMutex_;

    void updateChannelFilter() {
        channelMode_ = mode;
        const int ssbTaps = tapsForTransition(kAudioRate, 250);
        switch (channelMode_) {
            case MODE_USB: channel_.setTaps(complexBandpassTaps(ssbTaps, kAudioRate, 300, 3000)); break;
            case MODE_LSB: channel_.setTaps(complexBandpassTaps(ssbTaps, kAudioRate, -3000, -300)); break;
            default: // FM(Langstoneと同じ±7.5kHz)
                channel_.setTaps(complexBandpassTaps(tapsForTransition(kAudioRate, 1500), kAudioRate, -7500, 7500));
                break;
        }
        fmPrev_ = cf(1.0f, 0.0f);
        agcEnv_ = 1e-3f;
    }

    void updateSpectrum() {
        fft_.power(fftIn_, fftPower_);
        std::lock_guard<std::mutex> lock(spectrumMutex_);
        for (int i = 0; i < kFftSize; i++) {
            spectrumAvg_[i] = spectrumValid_ ? spectrumAvg_[i] * 0.7f + fftPower_[i] * 0.3f : fftPower_[i];
        }
        spectrumValid_ = true;
    }
};

// ---------------------------------------------------------------------------
// 送信: マイク48kHz → Pluto IQ(528ksps)

class TxDsp {
public:
    std::atomic<int> mode{MODE_USB};

    TxDsp() { reset(); }

    void reset() {
        interp_.setTaps(lowpassTaps(tapsForTransition(kIqRate, 8000), kIqRate, 24000), kDecim);
        filterMode_ = -1;
        preemphPrev_ = 0.0f;
        fmPhase_ = 0.0;
        filterDirty_ = true;
    }

    void markFilterDirty() { filterDirty_ = true; }

    // nAudio個のマイク音声からnAudio*kDecim組のIQ(int16のI,Q交互)をiqへ書く。
    void process(const float *mic, size_t nAudio, float micGain, int16_t *iq) {
        if (filterDirty_.exchange(false) || filterMode_ != mode) updateFilter();
        cf up[kDecim];
        for (size_t n = 0; n < nAudio; n++) {
            float m = mic[n] * micGain;
            cf s;
            if (filterMode_ == MODE_FM) {
                // プリエンファシス(75us)→制限→300〜3000Hz→±5kHz偏移の周波数変調(analog.nbfm_tx相当)
                float pe = m + 3.6f * (m - preemphPrev_);
                preemphPrev_ = m;
                pe = std::max(-1.0f, std::min(1.0f, pe));
                const float a = fmAudio_.filter(pe);
                fmPhase_ += 2.0 * kPi * 5000.0 / kAudioRate * a;
                if (fmPhase_ > kPi) fmPhase_ -= 2.0 * kPi;
                if (fmPhase_ < -kPi) fmPhase_ += 2.0 * kPi;
                s = cf(static_cast<float>(std::cos(fmPhase_)), static_cast<float>(std::sin(fmPhase_)));
            } else {
                // Langstoneと同じく制限後に複素帯域フィルタで片側波帯を作る(片側分で振幅が半分になるため2倍)
                m = std::max(-0.99f, std::min(0.99f, m));
                s = ssb_.filter(cf(m, 0.0f)) * 2.0f;
            }
            interp_.push(s, up);
            for (int k = 0; k < kDecim; k++) {
                const size_t idx = n * kDecim + k;
                const float re = std::max(-1.0f, std::min(1.0f, up[k].real()));
                const float im = std::max(-1.0f, std::min(1.0f, up[k].imag()));
                iq[2 * idx] = static_cast<int16_t>(re * kTxScale);
                iq[2 * idx + 1] = static_cast<int16_t>(im * kTxScale);
            }
        }
    }

private:
    FirCC ssb_;
    FirRR fmAudio_;
    InterpolatorCR interp_;
    std::atomic<bool> filterDirty_{true};
    int filterMode_ = -1;
    float preemphPrev_ = 0.0f;
    double fmPhase_ = 0.0;

    void updateFilter() {
        filterMode_ = mode;
        if (filterMode_ == MODE_FM) {
            fmAudio_.setTaps(bandpassTaps(tapsForTransition(kAudioRate, 300), kAudioRate, 300, 3000));
        } else {
            const double lo = (filterMode_ == MODE_USB) ? 300 : -3000;
            const double hi = (filterMode_ == MODE_USB) ? 3000 : -300;
            ssb_.setTaps(complexBandpassTaps(tapsForTransition(kAudioRate, 250), kAudioRate, lo, hi));
        }
    }
};

} // namespace ssbfm
