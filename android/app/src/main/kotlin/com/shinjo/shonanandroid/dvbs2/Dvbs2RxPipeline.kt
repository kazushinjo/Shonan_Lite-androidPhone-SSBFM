package com.shinjo.shonanandroid.dvbs2

import android.content.Context

/**
 * `dvbs2_bridge.cpp`のJNIラッパー(受信側)。iOS版`DVBS2Decoder`+`LibiioSession`+
 * `DVBS2RxPipeline`を1クラスに統合したネイティブ実装に対応する。[Dvbs2TxPipeline]参照。
 */
class Dvbs2RxPipeline {
    var onError: ((String) -> Unit)? = null
    var onTsData: ((ByteArray) -> Unit)? = null

    private val handle: Long = nativeCreate()
    private var isRunning = false

    fun start(
        context: Context, plutoUri: String, modCod: String,
        bandwidthHz: Long, sampleRateHz: Long, loHz: Long, rfPort: String = "A_BALANCED",
        // ★aff3ct既定は0.20。Plutoのオンボード変調(pluto_dvb/libdvbmod)はロールオフ0.35
        // 固定・変更不可なため、その信号を復調する場合は呼び出し側で0.35を渡すこと
        // (実機で0.20のままだと同期が一切成立せずBER=0.5相当のノイズになる不具合を確認済み)。
        rolloff: Double = 0.2,
    ): Boolean {
        if (isRunning) return false
        // ★Dvbs2Constants参照: ネイティブ層を呼ぶ前に未対応MODCODを弾く(SIGSEGV防止)。
        if (!Dvbs2Constants.isSupported(modCod)) {
            onError?.invoke(
                "未対応のMODCODです($modCod)。サポート済み: ${Dvbs2Constants.VALID_MOD_CODS.sorted().joinToString(", ")}"
            )
            return false
        }
        Dvbs2Native.ensureWorkingDirectory(context)
        val tmpDir = Dvbs2Native.tmpDirPath(context)
        val ok = nativePrepare(handle, plutoUri, modCod, bandwidthHz, sampleRateHz, loHz, rfPort, tmpDir, rolloff)
        if (!ok) return false
        nativeBeginStreaming(handle, context.filesDir.path)
        isRunning = true
        return true
    }

    fun stop() {
        if (!isRunning) return
        nativeStop(handle)
        isRunning = false
    }

    /** DVB-S2フレーム同期の現在のロック状態(sync_frame->get_packet_flag())。
     *  TS出力(onTsData)はBCH/LDPC復号成否・フレーム同期の有無に関わらず毎フレーム
     *  無条件で発生する(dvbs2_rx_lib.cpp参照)ため、TS到達の有無ではロック判定できない。
     *  呼び出し側が適当な間隔でポーリングすること。 */
    fun isLocked(): Boolean = if (isRunning) nativeIsLocked(handle) else false

    fun destroy() {
        stop()
        nativeDestroy(handle)
    }

    // ネイティブ側(dvbs2_bridge.cpp)から呼び戻される。
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
        handle: Long, plutoUri: String, modCod: String,
        bandwidthHz: Long, sampleRateHz: Long, loHz: Long, rfPort: String, tmpDir: String,
        rolloff: Double,
    ): Boolean
    private external fun nativeBeginStreaming(handle: Long, filesDir: String)
    private external fun nativeStop(handle: Long)
    private external fun nativeIsLocked(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("dvbs2_bridge")
        }
    }
}
