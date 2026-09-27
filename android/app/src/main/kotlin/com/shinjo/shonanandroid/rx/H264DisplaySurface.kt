package com.shinjo.shonanandroid.rx

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors

/**
 * [MediaCodec]のdecode-to-Surfaceモードで、TS Demuxerが渡すH.264 Annex-Bフレームを
 * 復号しSurfaceへ直接描画する。iOS版`H264DisplayDecoder.swift`
 * (`AVSampleBufferDisplayLayer`直結)に相当するAndroid実装だが、他プロジェクトに
 * 前例のない新規設計部分(あちらはRX映像表示自体が未実装のため)。
 *
 * SPS/PPSはCSDとして明示指定せず、最初のキーフレームのインバンドNALから
 * デコーダ自身に認識させる(標準的なAndroid MediaCodecの挙動)。
 */
class H264DisplaySurface {
    var onError: ((String) -> Unit)? = null

    private var codec: MediaCodec? = null
    private val drainExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var draining = false

    // ★調査目的の一時計装(フリッカー原因調査用): decode()呼び出し頻度とドロップ数を計測。
    private var inFrameCount = 0
    private var inDropCount = 0
    private var inWindowStartMs = SystemClock.elapsedRealtime()

    fun configure(surface: Surface, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.configure(format, surface, null, 0)
            newCodec.start()
            codec = newCodec
            draining = true
            drainExecutor.execute { drainLoop() }
        } catch (e: Exception) {
            onError?.invoke("デコーダの初期化に失敗しました: ${e.message}")
        }
    }

    /** 1フレーム分のH.264 Annex-Bデータ(複数NALユニットを含みうる)を投入する。 */
    fun decode(annexBFrame: ByteArray, presentationTimeUsec: Long) {
        val codec = codec ?: return
        val inputIndex = try {
            codec.dequeueInputBuffer(10_000)
        } catch (e: IllegalStateException) {
            return
        }
        inFrameCount++
        if (inputIndex < 0) inDropCount++
        val now = SystemClock.elapsedRealtime()
        if (now - inWindowStartMs >= 1000) {
            Log.i(TAG, "decodeIn calls=$inFrameCount drops=$inDropCount in ${now - inWindowStartMs}ms")
            inFrameCount = 0
            inDropCount = 0
            inWindowStartMs = now
        }
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(annexBFrame)
        codec.queueInputBuffer(inputIndex, 0, annexBFrame.size, presentationTimeUsec, 0)
    }

    fun stop() {
        draining = false
        val codec = codec ?: return
        try {
            codec.stop()
            codec.release()
        } catch (_: Exception) {
        }
        this.codec = null
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        // ★実測RX表示フレームレート計測用(調査目的の一時計装)。1秒ごとに
        // 実際にSurfaceへ描画したフレーム数をログ出力する。
        var frameCount = 0
        var windowStartMs = SystemClock.elapsedRealtime()
        while (draining) {
            val codec = codec ?: return
            val outputIndex = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (outputIndex < 0) continue
            // trueでSurfaceへ描画。
            codec.releaseOutputBuffer(outputIndex, true)
            frameCount++
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - windowStartMs
            if (elapsedMs >= 1000) {
                val fps = frameCount * 1000.0 / elapsedMs
                Log.i(TAG, "displayFps=%.1f (frames=%d in %dms)".format(fps, frameCount, elapsedMs))
                frameCount = 0
                windowStartMs = now
            }
        }
    }

    companion object {
        private const val TAG = "H264DisplaySurface"
        // TxControllerと同じ固定720p(HD)。
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }
}
