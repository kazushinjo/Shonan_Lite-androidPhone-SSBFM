package com.shinjo.shonanandroid.rx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsPacketAlignerTest {
    @Test
    fun alignsChunksAndRecoversAfterMidStreamCorruption() {
        val packets = (0 until 15).map { index ->
            ByteArray(TsPacketAligner.TS_PACKET_SIZE) { offset ->
                if (offset == 0) 0x47 else ((index + offset) and 0xFF).toByte()
            }
        }.toMutableList()
        packets[7][0] = 0x00 // 1 byte欠損相当の同期ずれ
        val stream = packets.fold(ByteArray(0)) { acc, packet -> acc + packet }
        val received = mutableListOf<ByteArray>()
        val aligner = TsPacketAligner { received += it }

        // GNU Radio/FIFOのread境界がTS境界と一致しないことを再現する。
        aligner.ingest(byteArrayOf(0x12, 0x34, 0x56))
        stream.asList().chunked(97).forEach { aligner.ingest(it.toByteArray()) }

        assertTrue("mid-stream corruption must trigger resync", aligner.resyncCount > 0)
        assertTrue(aligner.droppedBytes > 0)
        assertTrue(received.isNotEmpty())
        received.forEach { chunk ->
            assertEquals(0, chunk.size % TsPacketAligner.TS_PACKET_SIZE)
            for (offset in 0 until chunk.size step TsPacketAligner.TS_PACKET_SIZE) {
                assertEquals(0x47, chunk[offset].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun waitsUntilThreeSyncPacketsAreAvailable() {
        val received = mutableListOf<ByteArray>()
        val aligner = TsPacketAligner { received += it }
        val packet = ByteArray(TsPacketAligner.TS_PACKET_SIZE) { if (it == 0) 0x47 else 0x00 }

        aligner.ingest(packet)
        aligner.ingest(packet)
        assertTrue(received.isEmpty())
        aligner.ingest(packet)
        assertTrue(received.isEmpty()) // 7 packets単位が揃うまでdemuxerへ渡さない
    }
}
