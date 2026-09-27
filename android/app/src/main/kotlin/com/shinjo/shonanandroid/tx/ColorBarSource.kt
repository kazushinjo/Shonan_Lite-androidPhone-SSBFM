package com.shinjo.shonanandroid.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.shinjo.shonanandroid.R

/**
 * `res/drawable-nodpi/test_pattern`のテストパターン画像をNV12
 * ([android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar])に変換して
 * 送出し続ける映像ソース。[CameraCapture]と同じ`onFrame`インターフェースを持たせることで、
 * [TxController]から実機カメラと透過的に差し替えられるようにしている。
 *
 * 実機カメラなしで送信パイプライン(エンコード〜多重化〜UDP送信)の動作確認をするための機能。
 */
class ColorBarSource(private val context: Context) {
    var onFrame: ((data: ByteArray, width: Int, height: Int, presentationTimeUs: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false

    fun start(width: Int = VIDEO_WIDTH, height: Int = VIDEO_HEIGHT, fps: Int = VIDEO_FPS) {
        if (running) return
        running = true

        val frame = buildTestPatternNV12(context, width, height)
        val thread = HandlerThread("ColorBarSource").apply { start() }
        handlerThread = thread
        val threadHandler = Handler(thread.looper)
        handler = threadHandler

        val intervalMs = 1000L / fps
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                onFrame?.invoke(frame, width, height, SystemClock.elapsedRealtimeNanos() / 1000)
                threadHandler.postDelayed(this, intervalMs)
            }
        }
        threadHandler.post(runnable)
    }

    fun stop() {
        if (!running) return
        running = false
        // ★quitSafely()はキュー済みのRunnableを吐き出してから終了するだけで、スレッドの
        // 実終了を待たない。呼び出し元(TxController.stop())はこの直後にencoder.stop()/
        // muxer.destroy()/sender.disconnect()を呼ぶため、join()せずに戻ると実行中の
        // onFrameコールバックがencoder.encode()を停止処理と競合させ、「UDP送出に
        // 失敗しました」という誤ったエラーの原因になる(H264Encoder.stop()で修正した
        // drainLoopの競合と同種の問題)。stop()が返った時点でonFrameが発火しないことを
        // 呼び出し元に保証するため、スレッド終了まで待つ。
        handlerThread?.let { thread ->
            thread.quitSafely()
            thread.join()
        }
        handlerThread = null
        handler = null
    }

    companion object {
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        /** RGBのBT.601 studio-swing変換(フルレンジRGB -> スタジオレンジYUV)。 */
        private fun rgbToY(r: Int, g: Int, b: Int): Byte =
            (16 + (66 * r + 129 * g + 25 * b) / 256).coerceIn(0, 255).toByte()
        private fun rgbToU(r: Int, g: Int, b: Int): Byte =
            (128 + (-38 * r - 74 * g + 112 * b) / 256).coerceIn(0, 255).toByte()
        private fun rgbToV(r: Int, g: Int, b: Int): Byte =
            (128 + (112 * r - 94 * g - 18 * b) / 256).coerceIn(0, 255).toByte()

        private fun buildTestPatternNV12(context: Context, width: Int, height: Int): ByteArray {
            val options = BitmapFactory.Options().apply { inScaled = false }
            val source = BitmapFactory.decodeResource(context.resources, R.drawable.test_pattern, options)
            val bitmap = if (source.width == width && source.height == height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true).also { source.recycle() }
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            if (bitmap !== source) bitmap.recycle()

            val out = ByteArray(width * height * 3 / 2)

            // Yプレーン(フル解像度)
            for (row in 0 until height) {
                val rowOffset = row * width
                for (col in 0 until width) {
                    val p = pixels[rowOffset + col]
                    out[rowOffset + col] = rgbToY((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                }
            }

            // UVプレーン(NV12、U/V交互配置、クロマは水平・垂直とも1/2解像度、最近傍サンプリング)
            val chromaOffset = width * height
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            for (row in 0 until chromaHeight) {
                val rowOffset = chromaOffset + row * chromaWidth * 2
                val srcRow = (row * 2).coerceAtMost(height - 1) * width
                for (col in 0 until chromaWidth) {
                    val srcCol = (col * 2).coerceAtMost(width - 1)
                    val p = pixels[srcRow + srcCol]
                    val idx = rowOffset + col * 2
                    out[idx] = rgbToU((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    out[idx + 1] = rgbToV((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                }
            }
            return out
        }
    }
}
