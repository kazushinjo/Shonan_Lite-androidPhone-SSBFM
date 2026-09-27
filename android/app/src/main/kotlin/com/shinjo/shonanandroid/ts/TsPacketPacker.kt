package com.shinjo.shonanandroid.ts

/**
 * 「188byte TSパケットを7個束ねた1316byteのUDPペイロード」に整形する。
 * TsMuxerNativeの出力(188byte境界とは限らない任意長データ)を受け取り、
 * 188byte境界に沿って7パケット=1316byteずつUDP送出用データグラムに束ね直す。
 * iOS版`TSPacketPacker.swift`の移植。
 */
class TsPacketPacker {
    var onDatagram: ((ByteArray) -> Unit)? = null

    private var buffer = ByteArray(0)

    fun ingest(tsData: ByteArray) {
        buffer += tsData
        while (buffer.size >= DATAGRAM_SIZE) {
            onDatagram?.invoke(buffer.copyOfRange(0, DATAGRAM_SIZE))
            buffer = buffer.copyOfRange(DATAGRAM_SIZE, buffer.size)
        }
    }

    /** セッション終了時、188byte×7に満たない端数が残っていればMPEG-TS null packet
     *  (sync byte 0x47, PID=0x1FFF)でパディングして送出する。 */
    fun flush() {
        if (buffer.isEmpty()) return

        var padded = buffer
        while (padded.size < DATAGRAM_SIZE) {
            padded += nullPacket
        }
        onDatagram?.invoke(padded.copyOfRange(0, DATAGRAM_SIZE))
        buffer = ByteArray(0)
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    companion object {
        const val TS_PACKET_SIZE = 188
        const val PACKETS_PER_DATAGRAM = 7
        const val DATAGRAM_SIZE = TS_PACKET_SIZE * PACKETS_PER_DATAGRAM // 1316

        private val nullPacket: ByteArray = ByteArray(TS_PACKET_SIZE).also { packet ->
            packet[0] = 0x47
            packet[1] = 0x1F // transport_error=0, payload_unit_start=0, PID上位5bit=0b11111
            packet[2] = 0xFF.toByte() // PID下位8bit(0x1FFF)
            packet[3] = 0x10 // scrambling=0, adaptation_field_control=01(payloadのみ), CC=0
        }
    }
}
