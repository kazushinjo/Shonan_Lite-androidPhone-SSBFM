// ssbfm_dsp.h(SSB/FM送受信DSP)の実機検証用プログラム。アプリには組み込まない。
// 送信DSPの出力を、Pluto経由の受信を模した信号(受信LOからのオフセット+12bit量子化+雑音)として
// 受信DSPへ折り返し、側波帯抑圧・復調音の純度・スペクトル位置・処理時間を測る。
//
// ビルド(Windows、NDK r27):
//   %NDK%/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++ --target=aarch64-linux-android26 ^
//     -std=c++17 -O3 -ffast-math -static-libstdc++ ssbfm_dsp_test.cpp -o ssbfm_dsp_test
//   adb push ssbfm_dsp_test /data/local/tmp/ && adb shell /data/local/tmp/ssbfm_dsp_test
#include "../ssbfm_dsp.h"

#include <chrono>
#include <cstdio>
#include <random>

using namespace ssbfm;

namespace {

double tonePowerReal(const std::vector<float> &x, size_t from, double f, double fs) {
    std::complex<double> acc = 0;
    for (size_t i = from; i < x.size(); i++) acc += static_cast<double>(x[i]) * std::polar(1.0, -2 * kPi * f * i / fs);
    const double n = static_cast<double>(x.size() - from);
    return std::norm(acc / n) * 2.0; // 実正弦波の片側成分から電力(振幅^2/2)へ
}

double totalPower(const std::vector<float> &x, size_t from) {
    double p = 0;
    for (size_t i = from; i < x.size(); i++) p += static_cast<double>(x[i]) * x[i];
    return p / static_cast<double>(x.size() - from);
}

double tonePowerComplex(const std::vector<cf> &x, size_t from, double f, double fs) {
    std::complex<double> acc = 0;
    for (size_t i = from; i < x.size(); i++) acc += std::complex<double>(x[i]) * std::polar(1.0, -2 * kPi * f * i / fs);
    return std::norm(acc / static_cast<double>(x.size() - from));
}

double db(double p) { return 10.0 * std::log10(p + 1e-30); }

struct Run {
    std::vector<cf> txIq;        // 送信DSP出力(528ksps、±1正規化)
    std::vector<float> audio;    // 受信DSP出力(48kHz)
    float spectrum[kFftSize];
    bool spectrumOk = false;
    float signalDb = -200;       // Sメーター相当(AGC前のチャンネル内電力)
    double txSecondsPerSecond = 0, rxSecondsPerSecond = 0;
};

Run run(Mode txMode, Mode rxMode, double toneHz, float micAmp, int offsetHz, double seconds, float rfLevel,
        float noiseRms) {
    TxDsp tx;
    tx.mode = txMode;
    RxDsp rx;
    rx.mode = rxMode;
    rx.afGain = 0.5f; // 音声利得1倍
    rx.ncoOffsetHz = offsetHz;

    Run r;
    std::mt19937 rng(1);
    std::normal_distribution<float> gauss(0.0f, noiseRms);
    const int blocks = static_cast<int>(seconds * kAudioRate / kAudioPerBlock);
    std::vector<float> mic(kAudioPerBlock);
    std::vector<int16_t> txOut(2 * kIqPerBlock), rxIn(2 * kIqPerBlock);
    std::vector<float> audio(kAudioPerBlock + 1);
    size_t micIdx = 0, iqIdx = 0;
    double txTime = 0, rxTime = 0;
    for (int b = 0; b < blocks; b++) {
        for (int n = 0; n < kAudioPerBlock; n++, micIdx++) {
            mic[n] = micAmp * static_cast<float>(std::sin(2 * kPi * toneHz * micIdx / kAudioRate));
        }
        auto t0 = std::chrono::steady_clock::now();
        tx.process(mic.data(), kAudioPerBlock, 1.0f, txOut.data());
        auto t1 = std::chrono::steady_clock::now();
        txTime += std::chrono::duration<double>(t1 - t0).count();

        // 疑似RF: 送信ベースバンドを受信LOから+offsetHzの位置へ移し、受信側の12bit値へ量子化する。
        for (int i = 0; i < kIqPerBlock; i++, iqIdx++) {
            const cf s(txOut[2 * i] / kTxScale, txOut[2 * i + 1] / kTxScale);
            r.txIq.push_back(s);
            const cf moved = s * std::polar(1.0f, static_cast<float>(2 * kPi * std::fmod(static_cast<double>(offsetHz) * iqIdx / kIqRate, 1.0)));
            const float re = moved.real() * rfLevel * 2048.0f + gauss(rng);
            const float im = moved.imag() * rfLevel * 2048.0f + gauss(rng);
            rxIn[2 * i] = static_cast<int16_t>(std::lround(std::max(-2048.0f, std::min(2047.0f, re))));
            rxIn[2 * i + 1] = static_cast<int16_t>(std::lround(std::max(-2048.0f, std::min(2047.0f, im))));
        }
        auto t2 = std::chrono::steady_clock::now();
        const int n = rx.process(rxIn.data(), kIqPerBlock, audio.data());
        auto t3 = std::chrono::steady_clock::now();
        rxTime += std::chrono::duration<double>(t3 - t2).count();
        r.audio.insert(r.audio.end(), audio.begin(), audio.begin() + n);
    }
    r.spectrumOk = rx.copySpectrum(r.spectrum, kFftSize);
    r.signalDb = rx.signalDb;
    r.txSecondsPerSecond = txTime / seconds;
    r.rxSecondsPerSecond = rxTime / seconds;
    return r;
}

const char *name(Mode m) { return m == MODE_USB ? "USB" : m == MODE_LSB ? "LSB" : "FM"; }

int failures = 0;
void check(bool ok, const char *what) {
    std::printf("  [%s] %s\n", ok ? "OK" : "NG", what);
    if (!ok) failures++;
}

} // namespace

int main() {
    const double tone = 1000.0;
    const size_t settle48k = kAudioRate / 4;  // AGC・フィルタの立ち上がり0.25秒は除外
    const size_t settle528k = kIqRate / 4;

    // 1) SSB: 送信側波帯の抑圧、同じ側波帯での受信、逆側波帯での受信
    for (Mode m : {MODE_USB, MODE_LSB}) {
        const Mode other = (m == MODE_USB) ? MODE_LSB : MODE_USB;
        std::printf("== %s 送信 1kHz ==\n", name(m));
        Run r = run(m, m, tone, 0.5f, 123450, 2.0, 0.3f, 1.0f);
        const double want = tonePowerComplex(r.txIq, settle528k, m == MODE_USB ? tone : -tone, kIqRate);
        const double unwanted = tonePowerComplex(r.txIq, settle528k, m == MODE_USB ? -tone : tone, kIqRate);
        std::printf("  送信: 希望側 %.1f dBFS / 逆側 %.1f dBFS → 抑圧 %.1f dB\n", db(want), db(unwanted), db(want) - db(unwanted));
        check(db(want) - db(unwanted) > 40, "送信の逆側波帯抑圧 > 40dB");

        const double p1k = tonePowerReal(r.audio, settle48k, tone, kAudioRate);
        const double pAll = totalPower(r.audio, settle48k);
        std::printf("  受信(%s): 1kHz %.1f dBFS / 全体 %.1f dBFS → SINAD %.1f dB\n", name(m), db(p1k), db(pAll),
                    db(p1k) - db(pAll - p1k));
        check(db(p1k) - db(pAll - p1k) > 30, "同じ側波帯で受信した復調音のSINAD > 30dB");
        check(db(p1k) > -20, "復調音の大きさ(AGC後) > -20dBFS");

        if (r.spectrumOk) {
            int peak = 0;
            for (int i = 1; i < kFftSize; i++) if (r.spectrum[i] > r.spectrum[peak]) peak = i;
            const double peakHz = (peak - kFftSize / 2) * static_cast<double>(kAudioRate) / kFftSize;
            std::printf("  スペクトルのピーク: %+.0f Hz (%.1f dB)\n", peakHz, r.spectrum[peak]);
            check(std::fabs(peakHz - (m == MODE_USB ? tone : -tone)) < 150, "スペクトルのピーク位置が±1kHz(正しい側)");
        } else {
            check(false, "スペクトルが得られた");
        }

        // 逆側波帯で受信: 信号は帯域外なので、チャンネル内電力は大きく下がるはず。
        // AGCは帯域外の漏れも持ち上げるため、AGC前のチャンネル内電力(Sメーター値)で比べる。
        Run same = run(m, m, tone, 0.5f, 123450, 1.0, 0.3f, 0.0f);
        Run opp = run(m, other, tone, 0.5f, 123450, 1.0, 0.3f, 0.0f);
        std::printf("  Sメーター: 同じ側波帯 %.1f dB / 逆側波帯(%s) %.1f dB → 差 %.1f dB\n", same.signalDb, name(other),
                    opp.signalDb, same.signalDb - opp.signalDb);
        check(same.signalDb - opp.signalDb > 40, "逆側波帯の受信でチャンネル内電力が40dB以上下がる");
        std::printf("  処理時間: 送信 %.1f%% / 受信 %.1f%%(実時間比、1コア)\n", r.txSecondsPerSecond * 100,
                    r.rxSecondsPerSecond * 100);
    }

    // 2) FM: ±5kHz偏移前提で1kHzを送受信
    {
        std::printf("== FM 送信 1kHz ==\n");
        Run r = run(MODE_FM, MODE_FM, tone, 0.3f, 60000, 2.0, 0.3f, 1.0f);
        const double p1k = tonePowerReal(r.audio, settle48k, tone, kAudioRate);
        const double pAll = totalPower(r.audio, settle48k);
        std::printf("  受信: 1kHz %.1f dBFS / 全体 %.1f dBFS → SINAD %.1f dB\n", db(p1k), db(pAll), db(p1k) - db(pAll - p1k));
        check(db(p1k) - db(pAll - p1k) > 25, "FM復調音のSINAD > 25dB");

        // 占有帯域: 搬送波±5kHz内の成分と±20kHzの成分を比べる
        double p20k = tonePowerComplex(r.txIq, settle528k, 20000, kIqRate) +
                      tonePowerComplex(r.txIq, settle528k, -20000, kIqRate);
        double pCarrierArea = 0;
        for (int k = -5; k <= 5; k++) pCarrierArea += tonePowerComplex(r.txIq, settle528k, k * 1000.0, kIqRate);
        std::printf("  送信: ±5kHz内の成分合計 %.1f dB / ±20kHzの成分 %.1f dB\n", db(pCarrierArea), db(p20k));
        check(db(pCarrierArea) - db(p20k) > 40, "FM送信の±20kHz成分が主成分より40dB以上低い");
        std::printf("  処理時間: 送信 %.1f%% / 受信 %.1f%%(実時間比、1コア)\n", r.txSecondsPerSecond * 100,
                    r.rxSecondsPerSecond * 100);
    }

    // 3) 受信周波数のずれ: オフセット端(50kHz/149.99kHz)でも1kHzで復調されること
    for (int offset : {50000, 149990}) {
        Run r = run(MODE_USB, MODE_USB, tone, 0.5f, offset, 1.0, 0.3f, 1.0f);
        const double p1k = tonePowerReal(r.audio, settle48k, tone, kAudioRate);
        const double pAll = totalPower(r.audio, settle48k);
        std::printf("== オフセット %d Hz: SINAD %.1f dB ==\n", offset, db(p1k) - db(pAll - p1k));
        check(db(p1k) - db(pAll - p1k) > 30, "オフセット端でも1kHzで復調される");
    }

    std::printf("\n結果: %s(NG %d件)\n", failures == 0 ? "すべてOK" : "NGあり", failures);
    return failures == 0 ? 0 : 1;
}
