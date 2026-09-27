package com.shinjo.shonanandroid.ssbfm

/**
 * `ssbfm_bridge.cpp`(Langstone-V2のPluto送受信処理をC++へ移植したもの)のJNIラッパー。
 * 呼び出しはネットワーク越しにPlutoを操作するため、メインスレッドから呼ばないこと
 * ([SsbFmController]は専用の直列ディスパッチャーから呼ぶ)。
 */
class SsbFmNative {
    var onError: ((String) -> Unit)? = null

    private val handle: Long = nativeCreate()

    fun start(
        plutoUri: String, frequencyHz: Long, mode: SsbFmMode, rxAgc: Boolean, rxGainDb: Double, txAttenuationDb: Double,
    ): Boolean = nativeStart(handle, plutoUri, frequencyHz, mode.nativeId, rxAgc, rxGainDb, txAttenuationDb)

    fun stop() = nativeStop(handle)
    fun setFrequency(hz: Long): Boolean = nativeSetFrequency(handle, hz)
    fun setMode(mode: SsbFmMode) = nativeSetMode(handle, mode.nativeId)
    fun setPtt(on: Boolean): Boolean = nativeSetPtt(handle, on)
    fun setAfGain(value: Float) = nativeSetAfGain(handle, value)
    fun setMicGain(value: Float) = nativeSetMicGain(handle, value)
    fun setSquelch(db: Float) = nativeSetSquelch(handle, db)
    fun setRxGain(agc: Boolean, db: Double) = nativeSetRxGain(handle, agc, db)
    fun setTxAttenuation(db: Double) = nativeSetTxAttenuation(handle, db)

    /** fftshift済み(先頭が-24kHz)の電力dBを[out](512点)へ書く。まだ無ければfalse。 */
    fun readSpectrum(out: FloatArray): Boolean = nativeGetSpectrum(handle, out)
    val signalDb: Float get() = nativeGetSignalDb(handle)
    val rxGainDb: Float get() = nativeGetRxGainDb(handle)
    val micLevel: Float get() = nativeGetMicLevel(handle)
    val isSquelchOpen: Boolean get() = nativeIsSquelchOpen(handle)

    // ネイティブ側から呼び戻される。
    @Suppress("unused")
    private fun onNativeError(message: String) {
        onError?.invoke(message)
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(
        handle: Long, uri: String, freqHz: Long, mode: Int, rxAgc: Boolean, rxGainDb: Double, txAttenDb: Double,
    ): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetFrequency(handle: Long, hz: Long): Boolean
    private external fun nativeSetMode(handle: Long, mode: Int)
    private external fun nativeSetPtt(handle: Long, on: Boolean): Boolean
    private external fun nativeSetAfGain(handle: Long, value: Float)
    private external fun nativeSetMicGain(handle: Long, value: Float)
    private external fun nativeSetSquelch(handle: Long, db: Float)
    private external fun nativeSetRxGain(handle: Long, agc: Boolean, db: Double)
    private external fun nativeSetTxAttenuation(handle: Long, db: Double)
    private external fun nativeGetSpectrum(handle: Long, out: FloatArray): Boolean
    private external fun nativeGetSignalDb(handle: Long): Float
    private external fun nativeGetRxGainDb(handle: Long): Float
    private external fun nativeGetMicLevel(handle: Long): Float
    private external fun nativeIsSquelchOpen(handle: Long): Boolean

    companion object {
        const val SPECTRUM_SIZE = 512
        const val SPECTRUM_SPAN_HZ = 48_000

        init {
            System.loadLibrary("ssbfm_bridge")
        }
    }
}
