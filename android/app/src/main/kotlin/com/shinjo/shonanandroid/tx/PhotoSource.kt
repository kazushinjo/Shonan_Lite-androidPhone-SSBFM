package com.shinjo.shonanandroid.tx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写真フォルダーから選択した写真へ運用情報を重ね、映像フレームとして送出するソース。 */
class PhotoSource(private val context: Context) {
    var onFrame: ((data: ByteArray, width: Int, height: Int, presentationTimeUs: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var handlerThread: HandlerThread? = null
    @Volatile private var running = false

    fun start(uriString: String?, callsign: String, note: String, width: Int, height: Int, fps: Int) {
        if (running) return
        if (uriString.isNullOrBlank()) {
            onError?.invoke("送信する写真が選択されていません")
            return
        }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString)).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
        if (bitmap == null) {
            onError?.invoke("選択した写真を読み込めません")
            return
        }

        val prepared = createOverlayBitmap(bitmap, callsign, note, width, height)
        if (prepared !== bitmap) bitmap.recycle()
        val frame = toNv12(prepared)
        prepared.recycle()

        running = true
        val thread = HandlerThread("PhotoSource").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        val intervalMs = (1000L / fps.coerceAtLeast(1))
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                onFrame?.invoke(frame, width, height, SystemClock.elapsedRealtimeNanos() / 1000)
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable)
    }

    fun stop() {
        if (!running) return
        running = false
        handlerThread?.let {
            it.quitSafely()
            it.join()
        }
        handlerThread = null
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** 送信画面のプレビュー用。呼び出し側で返却Bitmapをrecycleする。 */
        fun loadOverlayBitmap(
            context: Context,
            uriString: String?,
            callsign: String,
            note: String,
            width: Int = 1280,
            height: Int = 720,
        ): Bitmap? {
            if (uriString.isNullOrBlank()) return null
            val source = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString)).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
            return createOverlayBitmap(source, callsign, note, width, height).also {
                if (it !== source) source.recycle()
            }
        }

        private fun createOverlayBitmap(source: Bitmap, callsign: String, note: String, width: Int, height: Int): Bitmap {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val sourceRatio = source.width.toFloat() / source.height
            val targetRatio = width.toFloat() / height
            val src = if (sourceRatio > targetRatio) {
                val cropWidth = (source.height * targetRatio).toInt()
                android.graphics.Rect((source.width - cropWidth) / 2, 0, (source.width + cropWidth) / 2, source.height)
            } else {
                val cropHeight = (source.width / targetRatio).toInt()
                android.graphics.Rect(0, (source.height - cropHeight) / 2, source.width, (source.height + cropHeight) / 2)
            }
            canvas.drawBitmap(source, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), null)

            // 60pt相当(エンコード画像上の60px)で、コールサインは左上へ表示する。
            val callsignTextSize = 128f
            val detailTextSize = 24f
            val padding = 24f
            val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = callsignTextSize
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = detailTextSize
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }

            callsign.trim().takeIf { it.isNotEmpty() }?.let { value ->
                callsignPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(value, padding, padding + callsignTextSize, callsignPaint)
            }

            val bottomLines = listOfNotNull(
                dateFormat.format(Date()),
                note.trim().takeIf { it.isNotEmpty() }?.let { "NOTE: $it" },
            )
            detailPaint.textAlign = Paint.Align.RIGHT
            val lineHeight = detailTextSize * 1.25f
            val boxTop = height - padding * 2 - lineHeight * bottomLines.size
            bottomLines.forEachIndexed { index, line ->
                canvas.drawText(line, width - padding, boxTop + padding + detailTextSize + index * lineHeight, detailPaint)
            }
            return output
        }

        private fun toNv12(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val out = ByteArray(width * height * 3 / 2)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val p = pixels[row * width + col]
                    out[row * width + col] = y((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                }
            }
            val chromaOffset = width * height
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val p = pixels[(row * 2) * width + col * 2]
                    val idx = chromaOffset + row * width + col * 2
                    out[idx] = u((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                    out[idx + 1] = v((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                }
            }
            return out
        }

        private fun y(r: Int, g: Int, b: Int) = (16 + (66 * r + 129 * g + 25 * b) / 256).coerceIn(0, 255).toByte()
        private fun u(r: Int, g: Int, b: Int) = (128 + (-38 * r - 74 * g + 112 * b) / 256).coerceIn(0, 255).toByte()
        private fun v(r: Int, g: Int, b: Int) = (128 + (112 * r - 94 * g - 18 * b) / 256).coerceIn(0, 255).toByte()
    }
}
