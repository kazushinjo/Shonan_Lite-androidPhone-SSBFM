package com.shinjo.shonanandroid.rx

import java.io.ByteArrayOutputStream

/**
 * DVB-S2復調器から届くMPEG-TSバイト列を188バイト境界へ再同期する。
 *
 * Raspberry Pi版のudp_relay.pyと同じく、初回だけでなくストリーム途中でも
 * 0x47を188バイト間隔で検証する。GNU Radio/file_sink/FIFOのread境界はTS境界を
 * 保証しないため、受信側でこの境界を保証してからTS demuxerへ渡す。
 */
class TsPacketAligner(
    private val onAlignedData: (ByteArray) -> Unit,
) {
    companion object {
        const val TS_PACKET_SIZE = 188
        const val PACKETS_PER_CHUNK = 7
        const val CHUNK_SIZE = TS_PACKET_SIZE * PACKETS_PER_CHUNK
        private const val SYNC_BYTE = 0x47
        private const val LOOKAHEAD_PACKETS = 3
    }

    private val pending = ByteArrayOutputStream(CHUNK_SIZE * 2)
    var resyncCount: Long = 0
        private set
    var droppedBytes: Long = 0
        private set

    fun ingest(data: ByteArray) {
        if (data.isEmpty()) return
        pending.write(data)
        drain()
    }

    fun reset() {
        pending.reset()
        resyncCount = 0
        droppedBytes = 0
    }

    private fun drain() {
        while (true) {
            val bytes = pending.toByteArray()
            if (bytes.size < TS_PACKET_SIZE * LOOKAHEAD_PACKETS) return

            val start = findSync(bytes)
            if (start < 0) {
                // 次の入力で3パケット検証を完成できる末尾だけを残す。
                val keep = TS_PACKET_SIZE * (LOOKAHEAD_PACKETS - 1) + 1
                dropPrefix(bytes.size - keep)
                return
            }
            if (start > 0) dropPrefix(start)

            val aligned = pending.toByteArray()
            if (aligned.size < CHUNK_SIZE) return

            // 途中の同期ずれを見つけたら、その位置までの完全なTSだけを送る。
            var validPackets = 0
            while ((validPackets + 1) * TS_PACKET_SIZE <= aligned.size &&
                aligned[validPackets * TS_PACKET_SIZE].toInt() and 0xFF == SYNC_BYTE
            ) {
                validPackets++
            }
            if (validPackets < PACKETS_PER_CHUNK) {
                if (validPackets > 0) {
                    emit(validPackets * TS_PACKET_SIZE)
                } else {
                    dropPrefix(1)
                }
                continue
            }

            emit(CHUNK_SIZE)
        }
    }

    private fun findSync(bytes: ByteArray): Int {
        val max = bytes.size - TS_PACKET_SIZE * LOOKAHEAD_PACKETS
        for (i in 0..max) {
            if ((bytes[i].toInt() and 0xFF) == SYNC_BYTE &&
                (bytes[i + TS_PACKET_SIZE].toInt() and 0xFF) == SYNC_BYTE &&
                (bytes[i + TS_PACKET_SIZE * 2].toInt() and 0xFF) == SYNC_BYTE
            ) {
                return i
            }
        }
        return -1
    }

    private fun emit(size: Int) {
        val bytes = pending.toByteArray()
        onAlignedData(bytes.copyOf(size))
        pending.reset()
        if (bytes.size > size) pending.write(bytes, size, bytes.size - size)
    }

    private fun dropPrefix(size: Int) {
        if (size <= 0) return
        val bytes = pending.toByteArray()
        val actual = minOf(size, bytes.size)
        pending.reset()
        if (bytes.size > actual) pending.write(bytes, actual, bytes.size - actual)
        droppedBytes += actual.toLong()
        resyncCount++
    }
}
