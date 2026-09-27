package com.shinjo.shonanandroid.tx

import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 送信中の統計情報(ビットレート/パケット数等) -- iOS版`NetworkStats.swift`の移植。
 *  [recordPacket]は送信スレッド(UdpSender.send()と同じスレッド)から呼ばれる。 */
class TxNetworkStats {
    var isConnected by mutableStateOf(false)
        private set
    var packetsPerSecond by mutableStateOf(0)
        private set
    var bitsPerSecond by mutableStateOf(0.0)
        private set
    var totalPackets by mutableStateOf(0)
        private set
    /** マイク入力レベル(0f〜1f)。[recordAudioLevel]はAudioCaptureの録音スレッドから呼ばれる。 */
    var audioLevel by mutableStateOf(0f)
        private set

    private val bytesSinceLastTick = AtomicLong(0)
    private val packetsSinceLastTick = AtomicInteger(0)
    private val totalPacketsCounter = AtomicInteger(0)
    private var handlerThread: HandlerThread? = null

    fun start() {
        stop()
        isConnected = true
        totalPackets = 0
        packetsPerSecond = 0
        bitsPerSecond = 0.0
        audioLevel = 0f
        totalPacketsCounter.set(0)
        bytesSinceLastTick.set(0)
        packetsSinceLastTick.set(0)

        val thread = HandlerThread("TxNetworkStats").apply { start() }
        handlerThread = thread
        val h = Handler(thread.looper)
        val runnable = object : Runnable {
            override fun run() {
                tick()
                h.postDelayed(this, 1_000)
            }
        }
        h.postDelayed(runnable, 1_000)
    }

    fun stop() {
        isConnected = false
        audioLevel = 0f
        handlerThread?.let { thread ->
            thread.quitSafely()
            thread.join()
        }
        handlerThread = null
    }

    fun recordAudioLevel(level: Float) {
        audioLevel = level
    }

    fun recordPacket(byteCount: Int) {
        bytesSinceLastTick.addAndGet(byteCount.toLong())
        packetsSinceLastTick.incrementAndGet()
        totalPacketsCounter.incrementAndGet()
    }

    private fun tick() {
        val bytes = bytesSinceLastTick.getAndSet(0)
        val packets = packetsSinceLastTick.getAndSet(0)
        packetsPerSecond = packets
        bitsPerSecond = bytes * 8.0
        totalPackets = totalPacketsCounter.get()
    }
}
