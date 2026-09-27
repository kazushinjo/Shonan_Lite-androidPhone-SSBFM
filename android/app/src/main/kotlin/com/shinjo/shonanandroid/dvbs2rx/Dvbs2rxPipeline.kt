package com.shinjo.shonanandroid.dvbs2rx

import android.content.Context
import android.system.Os
import java.io.File

/**
 * `dvbs2rx_bridge.cpp`(GNU Radio + gr-dvbs2rx、igorauad氏)のJNIラッパー。
 * aff3ct版([[com.shinjo.shonanandroid.dvbs2.Dvbs2RxPipeline]])が既知のDVB-S2標準信号
 * (gr-dvbs2rx自身が生成したテスト信号)すら復調できないことがホスト実機検証で判明した
 * ため、実績のある独立実装へ切り替える。Shonanはネットワーク経由でPlutoに接続するため
 * (USB非経由)、gr-iioのfmcomms2_sourceにそのまま`ip:<addr>`のURIを渡せる。
 *
 * ★[Context]をコンストラクタで要求するのは、`System.loadLibrary`より前に環境変数
 * (GR_PREFIX/TMP)を設定する必要があるため([ensureEnvironment]参照)。
 */
class Dvbs2rxPipeline(context: Context) {
    var onError: ((String) -> Unit)? = null
    var onTsData: ((ByteArray) -> Unit)? = null

    init {
        ensureEnvironment(context)
    }

    // ★同一プロセス内でhandle(ネイティブDvbs2rxSession)を使い回して2回目のprepare()を
    // 呼ぶと、tb->connect()内(hier_block2_detail::connect()のd_debug_logger->debug()
    // 呼び出し)でSIGSEGVすることを実機で確認済み。GNU Radio側のグローバル状態
    // (sptr_magicのstashマップ、loggerシングルトン等)が前回セッションのメモリ位置と
    // 絡んで壊れている可能性が高いため、start()のたびにhandle自体を作り直す
    // (nativeDestroy→nativeCreate)ことで回避する。
    private var handle: Long = nativeCreate()
    private var isRunning = false

    // ★RxController.stop()は呼び出し元スレッド(メインスレッド)をブロックしないよう
    // 別スレッドで非同期にonDeviceRx.stop()を実行する設計のため、その直後にユーザーが
    // 「受信を開始」を押すと次のstart()呼び出しと競合しうる。start()内のnativeDestroy()が
    // まだjoinableなネイティブスレッド(outputReadThread/debugThread)を残したまま
    // deleteしてしまいstd::thread::~thread()がstd::terminate()するSIGABRTクラッシュを
    // 実機で確認済み。start()/stop()をsynchronizedで直列化し、確実に前回のstop()完了後に
    // 次のstart()が走るようにする。
    @Synchronized
    fun start(
        context: Context, plutoUri: String, constellation: String, codeRate: String,
        sampleRateHz: Long, loHz: Long, gainDb: Double = 40.0, agcEnabled: Boolean = true,
    ): Boolean {
        if (isRunning) return false
        // ★nativeStop()はC++側でrunning.exchange(false)により冪等(既に停止済みでも
        // 安全に呼べる)。isRunning=falseでもhandleのネイティブセッションがまだ
        // prepare済み・スレッド起動済みの可能性があるため、destroyの前に必ず呼ぶ。
        nativeStop(handle)
        nativeDestroy(handle)
        handle = nativeCreate()
        val fifoPath = File(context.cacheDir, "dvbs2rx_out_${System.nanoTime()}.fifo").absolutePath
        val tmpDir = context.cacheDir.absolutePath
        val ok = nativePrepare(
            handle, plutoUri, constellation, codeRate, sampleRateHz, loHz, gainDb, agcEnabled, fifoPath, tmpDir,
        )
        if (!ok) return false
        nativeBeginStreaming(handle)
        isRunning = true
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        nativeStop(handle)
        isRunning = false
    }

    /** DVB-S2フレーム同期の現在のロック状態(gr-dvbs2rxのplsync_cc::get_locked())。 */
    fun isLocked(): Boolean = if (isRunning) nativeIsLocked(handle) else false

    fun destroy() {
        stop()
        nativeDestroy(handle)
    }

    // ネイティブ側(dvbs2rx_bridge.cpp)から呼び戻される。
    @Suppress("unused")
    private fun onNativeError(message: String) {
        onError?.invoke(message)
    }

    @Suppress("unused")
    private fun onNativeTsData(data: ByteArray) {
        onTsData?.invoke(data)
    }

    private external fun nativeCreate(): Long
    private external fun nativePrepare(
        handle: Long, plutoUri: String, constellation: String, codeRate: String,
        sampleRateHz: Long, loHz: Long, gainDb: Double, agcEnabled: Boolean, fifoPath: String, tmpDir: String,
    ): Boolean
    private external fun nativeBeginStreaming(handle: Long)
    private external fun nativeIsLocked(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        @Volatile private var envReady = false

        /**
         * ★gr::prefs::singleton()がgr::prefix()/prefsdir()経由でビルド時に焼き込まれた
         * ホストPCのパス(Android実機に存在しない)をstd::filesystem::canonical()にかけ、
         * filesystem_errorが未捕捉のままSIGABRTするクラッシュを実機で確認済み
         * (`System.loadLibrary("gnuradio-runtime")`の静的初期化中に発生するため、
         * ネイティブ側の`nativePrepare`で環境変数を設定しても手遅れ -- ここで
         * `System.loadLibrary`より前に設定する必要がある)。GR_PREFIX環境変数で
         * 上書きできる(gnuradio-runtime/lib/constants.cc.in参照)。同じくgr::vmcircbuf系が
         * 前提にする書き込み可能な/tmpが無い問題(Android実機での既知事項)には
         * TMP環境変数で対応する。
         */
        @Synchronized
        private fun ensureEnvironment(context: Context) {
            if (envReady) return
            val grPrefix = File(context.cacheDir, "gr_prefix")
            File(grPrefix, "etc/gnuradio/conf.d").mkdirs()
            Os.setenv("GR_PREFIX", grPrefix.absolutePath, true)
            Os.setenv("TMP", context.cacheDir.absolutePath, true)

            // ★依存順(NEEDEDの逆トポロジカル順)にロードする: pmt/volk → runtime →
            // blocks → fft → filter → analog/iio/dvbs2rx → bridge。
            System.loadLibrary("gnuradio-pmt")
            System.loadLibrary("volk")
            System.loadLibrary("gnuradio-runtime")
            System.loadLibrary("gnuradio-blocks")
            System.loadLibrary("gnuradio-fft")
            System.loadLibrary("gnuradio-filter")
            System.loadLibrary("gnuradio-analog")
            System.loadLibrary("gnuradio-iio")
            System.loadLibrary("gnuradio-dvbs2rx")
            System.loadLibrary("dvbs2rx_bridge")
            envReady = true
        }
    }
}
