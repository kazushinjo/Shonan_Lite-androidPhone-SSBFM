// gr-dvbs2rx(GNU Radio OOTモジュール)ベースの本番RX復調ブリッジ。
// aff3ct版(dvbs2_bridge.cpp/Dvbs2RxPipeline)が既知のDVB-S2標準信号(gr-dvbs2rx製の
// テスト信号)すら復調できないことがホスト実機検証で判明したため、実績のある
// 独立実装(gr-dvbs2rx、igorauad氏)へ切り替える。ShonanはPlutoへUSBではなく
// ネットワーク経由(ip:)で接続するため、USB Host API統合は不要 --
// gr-iioのfmcomms2_sourceがネットワークURIをそのまま受け付ける。
#include <jni.h>
#include <android/log.h>

#include <gnuradio/top_block.h>
#include <gnuradio/analog/agc_cc.h>
#include <gnuradio/blocks/file_sink.h>
#include <gnuradio/iio/fmcomms2_source.h>
#include <gnuradio/dvbs2rx/rotator_cc.h>
#include <gnuradio/dvbs2rx/symbol_sync_cc.h>
#include <gnuradio/dvbs2rx/plsync_cc.h>
#include <gnuradio/dvbs2rx/xfecframe_demapper_cb.h>
#include <gnuradio/dvbs2rx/ldpc_decoder_bb.h>
#include <gnuradio/dvbs2rx/bch_decoder_bb.h>
#include <gnuradio/dvbs2rx/bbdescrambler_bb.h>
#include <gnuradio/dvbs2rx/bbdeheader_bb.h>

#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <future>
#include <poll.h>
#include <string>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>

#define LOG_TAG "Dvbs2rxBridge"

using namespace gr;
using namespace gr::dvbs2rx;

namespace {

bool parseCodeRate(const std::string &s, dvb_code_rate_t *out) {
    static const std::pair<const char *, dvb_code_rate_t> table[] = {
        {"1/4", C1_4}, {"1/3", C1_3}, {"2/5", C2_5}, {"1/2", C1_2}, {"3/5", C3_5},
        {"2/3", C2_3}, {"3/4", C3_4}, {"4/5", C4_5}, {"5/6", C5_6}, {"7/8", C7_8},
        {"8/9", C8_9}, {"9/10", C9_10},
    };
    for (auto &e : table) if (s == e.first) { *out = e.second; return true; }
    return false;
}

bool parseConstellation(const std::string &s, dvb_constellation_t *out) {
    if (s == "QPSK") { *out = MOD_QPSK; return true; }
    if (s == "8PSK") { *out = MOD_8PSK; return true; }
    if (s == "16APSK") { *out = MOD_16APSK; return true; }
    if (s == "32APSK") { *out = MOD_32APSK; return true; }
    return false;
}

// ★gr-dvbs2rx公式リファレンス(dvbs2rx_rx_hier.grc)のブロック接続順序を、
// ファイルソースの代わりにfmcomms2_source(Plutoネットワーク接続)へ
// 差し替えて再現する。
struct Dvbs2rxSession {
    top_block_sptr tb;
    plsync_cc::sptr plsync;
    bbdeheader_bb::sptr bbdeheader;
    std::string outputFifoPath;
    std::thread outputReadThread;
    std::thread debugThread;
    std::atomic<bool> running{false};
    // ★readOutputLoop()はprepare()内でrunning=trueになる前(beginStreaming()より前)に
    // 起動されるため、ループ条件をrunningにすると即座に抜けてしまう。stop()専用の
    // 別フラグでpoll()タイムアウト経由の定期チェックにより安全に終了させる。
    std::atomic<bool> stopReading{false};

    JavaVM *jvm = nullptr;
    jobject callbackObj = nullptr;
    jmethodID onErrorMethod = nullptr;
    jmethodID onTsDataMethod = nullptr;

    ~Dvbs2rxSession() { stop(); }

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
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", message.c_str());
        bool didAttach = false;
        JNIEnv *env = attachEnv(&didAttach);
        jstring jMsg = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(callbackObj, onErrorMethod, jMsg);
        env->DeleteLocalRef(jMsg);
        if (didAttach) jvm->DetachCurrentThread();
    }

    bool prepare(const std::string &uri, const std::string &constellationStr,
                 const std::string &codeRateStr, int64_t sampleRateHz, int64_t loHz,
                 double gainDb, bool agcEnabled, const std::string &fifoPath) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare() entered uri=%s sr=%lld lo=%lld",
                             uri.c_str(), (long long)sampleRateHz, (long long)loHz);
        dvb_constellation_t constellation;
        dvb_code_rate_t codeRate;
        if (!parseConstellation(constellationStr, &constellation)) {
            reportError("未対応の変調方式です(" + constellationStr + ")");
            return false;
        }
        if (!parseCodeRate(codeRateStr, &codeRate)) {
            reportError("未対応の符号化率です(" + codeRateStr + ")");
            return false;
        }

        outputFifoPath = fifoPath;
        ::unlink(outputFifoPath.c_str());
        if (mkfifo(outputFifoPath.c_str(), 0600) != 0) {
            reportError("出力FIFOの作成に失敗しました(errno=" + std::to_string(errno) + ")");
            return false;
        }

        try {
            // 666 ksym/sはPluto/Androidの実効IQ転送上限を避けるためSPS=2。
            const double sps = 2.0;
            const float rolloff = 0.35f;
            const int rrc_delay = 5;
            const int rrc_nfilts = 128;

            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare: make_top_block");
            tb = make_top_block("dvbs2rx_shonan");

            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare: fmcomms2_source::make uri=%s", uri.c_str());
            // voltage0/1=RX1のI/Q、voltage2/3=RX2のI/Q。gr_complex出力にはI/Q両方必要なため
            // {true,false}(voltage0のみ)ではchannel_listのサイズがoutput_items.size()(2)より
            // 小さくなり、device_source_impl::work()内channel_list[1]が範囲外アクセスしてSIGSEGVした。
            auto src = gr::iio::fmcomms2_source_fc32::make(uri, std::vector<bool>{true, true, false, false}, 0x8000);
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare: fmcomms2_source made, setting params");
            src->set_frequency(static_cast<double>(loHz));
            src->set_samplerate(static_cast<unsigned long>(sampleRateHz));
            // ★手動ゲイン(設定値71dB)を試したところRFループバック環境では信号が強すぎて
            // 飽和しSOF検出すら不成立になった(sof=0固定)ことを実機で確認したことがあるが、
            // これは71dBという値そのものの問題であり、手動ゲイン制御自体を禁止する理由には
            // ならない。設定画面(AGCトグル/RX Gainスライダー)の値をそのまま反映する。
            if (agcEnabled) {
                src->set_gain_mode(0, "slow_attack");
            } else {
                src->set_gain_mode(0, "manual");
                src->set_gain(0, gainDb);
            }
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                 "prepare: fmcomms2_source configured agcEnabled=%d gainDb=%.1f",
                                 agcEnabled ? 1 : 0, gainDb);

            auto agc = analog::agc_cc::make(1e-5f, 1.0f, 1.0f);
            agc->set_max_gain(65536);

            auto rotator = rotator_cc::make(0.0, true);
            auto symbol_sync = symbol_sync_cc::make(sps, 0.001f, 1.0f, rolloff, rrc_delay, rrc_nfilts, 0);
            // ★acm_vcm=falseだとPLSフィルタが単一MODCODしか許容しない(コンストラクタが
            // 例外を投げる)。単一PLS値を厳密に計算する代わりに、reference実装
            // (test_dvbs2rx_rx.cc)と同じacm_vcm=true(全PLS許容)にして回避する。
            auto plsyncBlock = plsync_cc::make(0, 30, sps, 0, true, true,
                                                0xFFFFFFFFFFFFFFFFULL, 0xFFFFFFFFFFFFFFFFULL);
            plsync = plsyncBlock;

            auto xfecframe_demapper = xfecframe_demapper_cb::make(FECFRAME_NORMAL, codeRate, constellation);
            auto ldpc_decoder = ldpc_decoder_bb::make(STANDARD_DVBS2, FECFRAME_NORMAL, codeRate,
                                                       constellation, OM_MESSAGE, INFO_OFF, 25, 0);
            auto bch_decoder = bch_decoder_bb::make(STANDARD_DVBS2, FECFRAME_NORMAL, codeRate, OM_MESSAGE, 0);
            auto bbdescrambler = bbdescrambler_bb::make(STANDARD_DVBS2, FECFRAME_NORMAL, codeRate);
            auto bbdeheaderBlock = bbdeheader_bb::make(STANDARD_DVBS2, FECFRAME_NORMAL, codeRate, 0);
            bbdeheader = bbdeheaderBlock;
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare: all blocks constructed, connecting");

            // ★blocks::file_sink::make()はFIFOを書き込み用にopen(2)するため、読み手が
            // 先にいないとブロックする(mkfifoしたFIFOの一般的な性質。dvbs2_bridge.cppの
            // readOutputLoopRxと同じ理由で先に読み取りスレッドを起動しておく必要がある。
            // 実機でこれを忘れてprepare()自体が無期限にハングする事象を確認済み)。
            outputReadThread = std::thread([this] { readOutputLoop(); });

            auto sink = blocks::file_sink::make(sizeof(uint8_t), outputFifoPath.c_str(), false);
            sink->set_unbuffered(true);
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "prepare: file_sink opened");

            tb->connect(src, 0, agc, 0);
            tb->connect(agc, 0, rotator, 0);
            tb->connect(rotator, 0, symbol_sync, 0);
            tb->connect(symbol_sync, 0, plsyncBlock, 0);
            tb->connect(plsyncBlock, 0, xfecframe_demapper, 0);
            tb->connect(xfecframe_demapper, 0, ldpc_decoder, 0);
            tb->connect(ldpc_decoder, 0, bch_decoder, 0);
            tb->connect(bch_decoder, 0, bbdescrambler, 0);
            tb->connect(bbdescrambler, 0, bbdeheaderBlock, 0);
            tb->connect(bbdeheaderBlock, 0, sink, 0);

            tb->msg_connect(plsyncBlock, "rotator_phase_inc", rotator, "cmd");
            tb->msg_connect(ldpc_decoder, "llr_pdu", xfecframe_demapper, "llr_pdu");
        } catch (const std::exception &e) {
            reportError(std::string("フローグラフ構築に失敗しました: ") + e.what());
            return false;
        }
        return true;
    }

    void beginStreaming() {
        if (running.exchange(true)) return;
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "beginStreaming: tb->start()");
        tb->start();
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "beginStreaming: started");
        debugThread = std::thread([this] { debugLoop(); });
    }

    // ★実機でロックしない事象の切り分け用。plsync_cc/bbdeheaderの内部カウンタを
    // 1秒おきにログへ出す(test_dvbs2rx_rx.ccの診断ポーリングループと同じ発想)。
    void debugLoop() {
        while (running) {
            std::this_thread::sleep_for(std::chrono::seconds(1));
            if (!running || !plsync) break;
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                "locked=%d sof=%llu frame=%llu rejected=%llu freq_off=%.1f coarse_corr=%d bbdeheader packets=%llu errors=%llu",
                (int)plsync->get_locked(),
                (unsigned long long)plsync->get_sof_count(),
                (unsigned long long)plsync->get_frame_count(),
                (unsigned long long)plsync->get_rejected_count(),
                plsync->get_freq_offset(), (int)plsync->get_coarse_freq_corr_state(),
                bbdeheader ? (unsigned long long)bbdeheader->get_packet_count() : 0ULL,
                bbdeheader ? (unsigned long long)bbdeheader->get_error_count() : 0ULL);
        }
    }

    bool isLocked() {
        return plsync && plsync->get_locked();
    }

    void stop() {
        if (!running.exchange(false)) return;
        stopReading = true;
        if (tb) {
            tb->stop();
            // ★tb->wait()がgr-iio(fmcomms2_source)のブロッキングネットワークI/O
            // (iio_buffer_refill)待ちで無期限にハングすることを実機で確認済み。
            // tb->stop()によるiio_buffer_cancel()だけでは即座に解除されない場合がある。
            // 別スレッドでtb->wait()を実行しタイムアウト付きで待つことで、@Synchronized
            // 経由の後続start()呼び出しが永久にデッドロックする事態を回避する。
            // waiter threadはtbのshared_ptrをコピーで保持するため、タイムアウトしても
            // (thisのtb.reset()後も)tbオブジェクト自体はwaiter完了までダングリングしない。
            auto tbForWait = tb;
            auto donePromise = std::make_shared<std::promise<void>>();
            std::future<void> doneFuture = donePromise->get_future();
            std::thread([tbForWait, donePromise] {
                tbForWait->wait();
                donePromise->set_value();
            }).detach();
            if (doneFuture.wait_for(std::chrono::seconds(3)) == std::future_status::timeout) {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                    "stop: tb->wait() timed out after 3s (gr-iio blocking I/O?), proceeding anyway");
            }
        }
        // readOutputLoop()はFIFOのread()がEOFを返すまで待つ設計(dvbs2_bridge.cppの
        // readOutputLoopRxと同じ理由)。file_sinkがクローズされ次第自然に終了する。
        if (outputReadThread.joinable()) outputReadThread.join();
        if (debugThread.joinable()) debugThread.join();
        if (!outputFifoPath.empty()) ::unlink(outputFifoPath.c_str());
        tb.reset();
        plsync.reset();
        bbdeheader.reset();
    }

private:
    void readOutputLoop() {
        // ★O_NONBLOCKでopenし、poll()のタイムアウト(200ms)ごとにstopReadingを
        // チェックすることで、file_sink側(writer)がクローズされずEOFが来ない場合でも
        // stop()から確実に(200ms程度で)抜けられるようにする。実機でtb->wait()が
        // gr-iioのブロッキングI/Oでハングし、その結果file_sinkが最後までcloseされず
        // readOutputLoopがread()で無期限にブロックし続ける事態を確認したための対策。
        int fd = ::open(outputFifoPath.c_str(), O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            reportError("出力FIFOのopenに失敗しました");
            return;
        }
        bool didAttach = false;
        JNIEnv *env = attachEnv(&didAttach);

        std::vector<uint8_t> buf(65536);
        uint64_t totalBytes = 0;
        int logCount = 0;
        while (!stopReading) {
            struct pollfd pfd{fd, POLLIN, 0};
            int pret = ::poll(&pfd, 1, 200);
            if (pret <= 0) continue;
            ssize_t n = ::read(fd, buf.data(), buf.size());
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                break;
            }
            if (n == 0) break;
            if (!running) continue;
            totalBytes += static_cast<uint64_t>(n);
            if (logCount < 5) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                    "readOutputLoop: read n=%zd total=%llu first_byte=0x%02x", n,
                    (unsigned long long)totalBytes, buf[0]);
                logCount++;
            }
            jbyteArray array = env->NewByteArray(static_cast<jsize>(n));
            env->SetByteArrayRegion(array, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte *>(buf.data()));
            env->CallVoidMethod(callbackObj, onTsDataMethod, array);
            env->DeleteLocalRef(array);
        }
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "readOutputLoop: exiting, total=%llu",
            (unsigned long long)totalBytes);
        ::close(fd);
        if (didAttach) jvm->DetachCurrentThread();
    }
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *session = new Dvbs2rxSession();
    env->GetJavaVM(&session->jvm);
    session->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    session->onErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
    session->onTsDataMethod = env->GetMethodID(cls, "onNativeTsData", "([B)V");
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativePrepare(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jUri, jstring jConstellation,
        jstring jCodeRate, jlong sampleRateHz, jlong loHz, jdouble gainDb, jboolean agcEnabled,
        jstring jFifoPath, jstring jTmpDir) {
    auto *session = reinterpret_cast<Dvbs2rxSession *>(handle);
    // ★TMP/GR_PREFIX環境変数はDvbs2rxPipeline.ktのensureEnvironment()で
    // System.loadLibrary()より前に設定済み(ここで設定しても静的初期化には間に合わない)。
    const char *tmpDir = env->GetStringUTFChars(jTmpDir, nullptr);
    (void)tmpDir;
    env->ReleaseStringUTFChars(jTmpDir, tmpDir);

    const char *uri = env->GetStringUTFChars(jUri, nullptr);
    const char *constellation = env->GetStringUTFChars(jConstellation, nullptr);
    const char *codeRate = env->GetStringUTFChars(jCodeRate, nullptr);
    const char *fifoPath = env->GetStringUTFChars(jFifoPath, nullptr);
    bool ok = session->prepare(uri, constellation, codeRate, sampleRateHz, loHz, gainDb,
                                agcEnabled == JNI_TRUE, fifoPath);
    env->ReleaseStringUTFChars(jUri, uri);
    env->ReleaseStringUTFChars(jConstellation, constellation);
    env->ReleaseStringUTFChars(jCodeRate, codeRate);
    env->ReleaseStringUTFChars(jFifoPath, fifoPath);
    return static_cast<jboolean>(ok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativeBeginStreaming(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    reinterpret_cast<Dvbs2rxSession *>(handle)->beginStreaming();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativeIsLocked(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    return static_cast<jboolean>(reinterpret_cast<Dvbs2rxSession *>(handle)->isLocked());
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativeStop(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    reinterpret_cast<Dvbs2rxSession *>(handle)->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_dvbs2rx_Dvbs2rxPipeline_nativeDestroy(
        JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *session = reinterpret_cast<Dvbs2rxSession *>(handle);
    env->DeleteGlobalRef(session->callbackObj);
    delete session;
}
