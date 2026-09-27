package com.shinjo.shonanandroid.dvbs2

/** PlutoのRSSIを測定するRSSI測定専用のlibiioセッション。 */
class RssiNativeSession(plutoIp: String) : AutoCloseable {
    private var handle = nativeOpen("ip:$plutoIp")

    val isOpen: Boolean get() = handle != 0L

    /** RXゲインを設定する(AGCならslow_attack、手動ならmanual+[gainDb])。 */
    fun setRxGain(agcEnabled: Boolean, gainDb: Int): Boolean =
        handle != 0L && nativeSetRxGain(handle, agcEnabled, gainDb)

    /** 指定周波数へLOを設定し、AD9361のRSSI(dB、値が小さいほど強い)を返す。失敗時はNaN。 */
    fun measure(frequencyHz: Long): Double =
        if (handle == 0L) Double.NaN else nativeMeasure(handle, frequencyHz)

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeOpen(uri: String): Long
    private external fun nativeMeasure(handle: Long, frequencyHz: Long): Double
    private external fun nativeSetRxGain(handle: Long, agcEnabled: Boolean, gainDb: Int): Boolean
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("dvbs2_bridge") }
    }
}
