package com.shinjo.shonanandroid.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * UDP受信。指定ポートを待ち受け、受信したペイロードをハンドラへ渡す。
 * iOS版`UDPReceiver.swift`のAndroid対応。
 */
class UdpReceiver {
    var onPayload: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    fun start(port: Int) {
        stop()
        try {
            val newSocket = DatagramSocket(port)
            socket = newSocket
            running = true
            executor.execute { receiveLoop(newSocket) }
        } catch (e: Exception) {
            onError?.invoke("UDP受信の開始に失敗しました(port=$port): ${e.message}")
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun receiveLoop(socket: DatagramSocket) {
        // UDPの理論最大ペイロードに合わせて余裕を持ったバッファ長。
        val buffer = ByteArray(65536)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                break // stop()によるclose()経由の正常終了
            } catch (e: Exception) {
                if (running) onError?.invoke("UDP受信エラー: ${e.message}")
                continue
            }
            if (packet.length > 0) {
                onPayload?.invoke(packet.data.copyOfRange(0, packet.length))
            }
        }
    }
}
