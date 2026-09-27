package com.shinjo.shonanandroid.net

import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * UDPデータグラム送出。iOS版`UDPSender.swift`のAndroid対応。
 *
 * ★[send]は[com.shinjo.shonanandroid.ts.TsPacketPacker.flush]経由でTxController.stop()の
 * 呼び出しスレッドから直接呼ばれることがあり、それがCompose UIのクリックハンドラ(メイン
 * スレッド)だとブロッキングI/O呼び出しでNetworkOnMainThreadExceptionになる(「送信を停止
 * すると『UDP送出に失敗しました』が出る」という実機不具合の原因)。呼び出し元スレッドに
 * 依存しないよう、専用スレッドを持ちsend()はそこへポストするだけにする。
 */
class UdpSender {
    var onError: ((String) -> Unit)? = null
    var onPacketSent: ((Int) -> Unit)? = null
    private val sentPackets = AtomicLong(0)

    private var socket: DatagramSocket? = null
    private var destination: InetSocketAddress? = null
    private val ioThread = HandlerThread("UdpSender").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    fun connect(host: String, port: Int) {
        try {
            socket = DatagramSocket()
            destination = InetSocketAddress(host, port)
            sentPackets.set(0)
            Log.i(TAG, "UDP送信開始 destination=$host:$port")
        } catch (e: Exception) {
            onError?.invoke("UDP送出先への接続に失敗しました: ${e.message}")
        }
    }

    fun send(datagram: ByteArray) {
        val socket = socket ?: return
        val destination = destination ?: return
        ioHandler.post {
            try {
                socket.send(DatagramPacket(datagram, datagram.size, destination))
                val count = sentPackets.incrementAndGet()
                if (count <= 5 || count % 100 == 0L) {
                    Log.i(TAG, "UDP送信成功 packet=$count bytes=${datagram.size} destination=${destination.hostString}:${destination.port}")
                }
                onPacketSent?.invoke(datagram.size)
            } catch (e: Exception) {
                onError?.invoke("UDP送出に失敗しました: ${e.message}")
            }
        }
    }

    /** ★保留中のsend()タスクを送出し切ってからsocketを閉じるため、close自体もioHandler上で
     *  行う(disconnect()呼び出し元スレッドに関わらず、送出順序とクローズ順序を保証する)。 */
    fun disconnect() {
        val socketToClose = socket
        val destination = destination
        socket = null
        this.destination = null
        if (socketToClose == null || destination == null) return
        ioHandler.post { socketToClose.close() }
        Log.i(TAG, "UDP送信停止 sentPackets=${sentPackets.get()} destination=${destination.hostString}:${destination.port}")
    }

    companion object {
        private const val TAG = "UdpSender"
    }
}
