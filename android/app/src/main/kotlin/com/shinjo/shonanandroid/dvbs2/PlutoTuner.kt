package com.shinjo.shonanandroid.dvbs2

/** TX/RX開始時にPlutoのLO周波数だけを設定する一時セッション(IQストリーミングはしない)。 */
class PlutoTuner(plutoIp: String) : AutoCloseable {
    private var handle = nativeOpen("ip:$plutoIp")

    val isOpen: Boolean get() = handle != 0L

    /** 指定周波数へLOを設定する。isTx=trueならTX LO、falseならRX LOを操作する。 */
    fun tune(isTx: Boolean, frequencyHz: Long): Boolean =
        if (handle == 0L) false else nativeTune(handle, isTx, frequencyHz)

    /**
     * TX LO(altvoltage1)を止める(down=true)/入れる(down=false)。Pluto+ の FPGA 版などは
     * 送信を止めても Pluto の pluto_dvb が動き続け電波が出たままになるため、送信停止で止め、
     * 送信開始で入れ直す(2026-11-18、SDR 版の実機で確認)。
     */
    fun setTxLoPowerdown(down: Boolean): Boolean =
        if (handle == 0L) false else nativeSetTxLoPowerdown(handle, down)

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeOpen(uri: String): Long
    private external fun nativeTune(handle: Long, isTx: Boolean, frequencyHz: Long): Boolean
    private external fun nativeSetTxLoPowerdown(handle: Long, down: Boolean): Boolean
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("dvbs2_bridge") }
    }
}
