package com.shinjo.shonanandroid.rx

/**
 * MPEG-TSパケット(188byte、先頭バイト0x47)のPIDとcontinuity_counter(4bit)を見て、
 * 同一PID内でCCが連番から外れた回数を「推定ロス」としてカウントする簡易実装。
 * iOS版`TSContinuityTracker.swift`の移植。
 *
 * ★簡易実装。CC折り返しや、187byteアライメント崩れ等の厳密なエッジケースは未検証。
 */
class TSContinuityTracker {
    private val lastCC = HashMap<Int, Int>()
    var totalPackets: Int = 0
        private set
    var estimatedLostPackets: Int = 0
        private set

    fun reset() {
        lastCC.clear()
        totalPackets = 0
        estimatedLostPackets = 0
    }

    /** UDPペイロード(1316byte = 188byte×7、またはそれ以外のサイズ)を受け取り、
     *  含まれるTSパケットを走査して統計を更新する。 */
    fun ingest(payload: ByteArray) {
        var offset = 0
        while (offset + PACKET_SIZE <= payload.size) {
            if ((payload[offset].toInt() and 0xFF) == SYNC_BYTE) {
                val b1 = payload[offset + 1].toInt() and 0xFF
                val b2 = payload[offset + 2].toInt() and 0xFF
                val pid = ((b1 and 0x1F) shl 8) or b2

                val b3 = payload[offset + 3].toInt() and 0xFF
                val adaptationFieldControl = (b3 shr 4) and 0x03
                val cc = b3 and 0x0F

                totalPackets += 1

                // PID 0x1FFF(null packet)はCC連続性の対象外。
                // adaptation_field_controlがpayload無し(0b10)の場合、CCは対象外。
                if (pid != 0x1FFF && adaptationFieldControl != 0b10) {
                    val prev = lastCC[pid]
                    if (prev != null) {
                        val expected = (prev + 1) and 0x0F
                        if (cc != expected) {
                            estimatedLostPackets += 1
                        }
                    }
                    lastCC[pid] = cc
                }
            }
            offset += PACKET_SIZE
        }
    }

    companion object {
        private const val SYNC_BYTE = 0x47
        private const val PACKET_SIZE = 188
    }
}
