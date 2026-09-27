package com.shinjo.shonanandroid.dvbs2

import android.content.Context

/**
 * `dvbs2_bridge.cpp`(aff3ct/dvbs2 + libiio、Android NDK arm64-v8a向けクロスビルド --
 * third_party/build_dvbs2_deps.sh参照)のJNIラッパー。iOS版
 * `DVBS2Encoder`+`LibiioSession`+`DVBS2TxPipeline`(3クラス)を1クラスに統合したネイティブ
 * 実装に対応する薄いKotlinラッパー。
 *
 * 本番のTx画面(UDP TS方式、[com.shinjo.shonanandroid.tx.TxController])とは完全に別経路で、
 * Plutoへlibiio経由で直接IQサンプルを送出しaff3ct/dvbs2でDVB-S2変調をオンデバイスで行う。
 * 機器試験(Diagnostic)画面専用。
 */
class Dvbs2TxPipeline {
    var onError: ((String) -> Unit)? = null

    /** 機器診断用: 送信データの供給が枯渇していないか(starved率、直近ブロック)。 */
    val starvedPercent: Double get() = nativeGetStarvedPercent(handle)

    /** 機器診断用: DAC書き込みバッファの振幅(rms、直近ブロック)。 */
    val dmaRms: Double get() = nativeGetDmaRms(handle)

    private val handle: Long = nativeCreate()
    private var isRunning = false

    /**
     * connect+setup(RF設定)までを行い、成功すればストリーミングを開始する。
     * @param plutoUri 例 "ip:192.168.0.20"
     */
    fun start(context: Context, plutoUri: String, modCod: String, bandwidthHz: Long, sampleRateHz: Long, loHz: Long): Boolean {
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
        val ok = nativePrepare(handle, plutoUri, modCod, bandwidthHz, sampleRateHz, loHz, tmpDir)
        if (!ok) return false
        nativeBeginStreaming(handle, context.filesDir.path)
        isRunning = true
        return true
    }

    /** カメラ映像等から生成したTSデータを渡す(ダミーデータでも可、機器試験の診断用途)。 */
    fun write(data: ByteArray) {
        if (isRunning) nativeWrite(handle, data)
    }

    fun stop() {
        if (!isRunning) return
        nativeStop(handle)
        isRunning = false
    }

    fun requestStop() {
        if (!isRunning) return
        nativeRequestStop(handle)
        isRunning = false
    }

    fun destroy() {
        stop()
        nativeDestroy(handle)
    }

    // ネイティブ側(dvbs2_bridge.cpp)から呼び戻される。
    @Suppress("unused")
    private fun onNativeError(message: String) {
        onError?.invoke(message)
    }

    private external fun nativeCreate(): Long
    private external fun nativePrepare(
        handle: Long, plutoUri: String, modCod: String,
        bandwidthHz: Long, sampleRateHz: Long, loHz: Long, tmpDir: String,
    ): Boolean
    private external fun nativeBeginStreaming(handle: Long, filesDir: String)
    private external fun nativeWrite(handle: Long, data: ByteArray)
    private external fun nativeGetStarvedPercent(handle: Long): Double
    private external fun nativeGetDmaRms(handle: Long): Double
    private external fun nativeStop(handle: Long)
    private external fun nativeRequestStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("dvbs2_bridge")
        }
    }
}
