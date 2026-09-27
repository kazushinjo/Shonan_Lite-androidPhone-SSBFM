package com.shinjo.shonanandroid.tx

import android.content.Context
import android.media.MediaCodecInfo
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraXでカメラの生フレームをキャプチャする。フレームごとにNV12
 * ([MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar])の
 * バイト配列を[H264Encoder]へ渡す(自身ではエンコード/muxしない) --
 * iOS版`CameraCaptureManager.swift`(AVFoundation)のAndroid対応。
 *
 * CameraXの`ImageAnalysis`はYUV_420_888(端末依存の任意プレーンストライド)で
 * 配信するため、MediaCodecが期待するNV12レイアウトへの変換が必要
 * (iOS版はBGRA CVPixelBufferをVideoToolboxへそのまま渡すだけで済んでいた点と異なる)。
 */
class CameraCapture(private val context: Context, private val lifecycleOwner: LifecycleOwner) {
    /** [width]/[height]はフレームの実サイズ -- CameraXの解像度選択は[start]で
     *  要求したサイズへのベストエフォートマッチ(端末依存)のため、呼び出し側は
     *  要求値ではなくこちらの値でエンコーダをサイズ合わせすること。 */
    var onFrame: ((data: ByteArray, width: Int, height: Int, presentationTimeUs: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val preview: Preview = Preview.Builder().build()

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var targetWidth = 1920
    private var targetHeight = 1080

    fun start(position: CameraPosition, width: Int = 1920, height: Int = 1080) {
        targetWidth = width
        targetHeight = height
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bind(position)
            } catch (e: Exception) {
                onError?.invoke("カメラの初期化に失敗しました: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchPosition(position: CameraPosition) {
        bind(position)
    }

    fun stop() {
        ContextCompat.getMainExecutor(context).execute {
            cameraProvider?.unbindAll()
        }
    }

    private fun bind(position: CameraPosition) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = if (position == CameraPosition.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // imageInfo.timestampのクロックドメインは端末/HAL依存
        // (CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCEがTIMESTAMP_SOURCE_UNKNOWNの
        // ことが多い)。最初のフレームでオフセットを測定しSystemClock.elapsedRealtimeNanos()
        // 系へ再アンカーする。
        var timestampOffsetNs: Long? = null
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(targetWidth, targetHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        // ★CONTROL_AE_TARGET_FPS_RANGEを[fps,fps]に固定してfps低下(暗所でAEが露光時間を
        // 伸ばす)を防ぐ案を試したが、この端末のフロントカメラ(MediaTek HAL)ではキャプチャ
        // セッション構成そのものが失敗し映像が一切出なくなる回帰を実機で確認したため撤回。
        // fpsは可変のまま(暗所での露出安定を優先)。
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
        // ★フロントカメラのセンサーは実世界に対しては反転しない生データを出すため、
        // 素のまま送信すると受信側では(TX側プレビューがCameraXにより自動で鏡像表示
        // されるのに対し)「実際の向き」になり、ユーザーには反転して見える。
        // プレビューと同じ鏡像を送るため、フロントカメラ選択時のみ水平反転する。
        val mirror = position == CameraPosition.FRONT
        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val nv12 = toNV12(imageProxy, mirror)
                val rawTimestampNs = imageProxy.imageInfo.timestamp
                val offset = timestampOffsetNs ?: (SystemClock.elapsedRealtimeNanos() - rawTimestampNs).also {
                    timestampOffsetNs = it
                }
                onFrame?.invoke(nv12, imageProxy.width, imageProxy.height, (rawTimestampNs + offset) / 1000)
            } catch (e: Exception) {
                onError?.invoke("フレーム変換に失敗しました: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: Exception) {
            onError?.invoke("カメラの初期化に失敗しました(${position.displayName}): ${e.message}")
        }
    }

    companion object {
        /** YUV_420_888の[ImageProxy]をNV12(Yプレーン→U/V交互配置)へ変換する。
         *  平面/半平面どちらのソースレイアウトもピクセル単位インデックスで汎用的に扱う。 */
        private fun toNV12(image: ImageProxy, mirror: Boolean): ByteArray {
            val width = image.width
            val height = image.height
            val out = ByteArray(width * height * 3 / 2)

            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            var offset = 0
            val yRow = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yRow, 0, minOf(yRowStride, yBuffer.remaining()))
                if (mirror) {
                    for (col in 0 until width) {
                        out[offset + col] = yRow[width - 1 - col]
                    }
                } else {
                    System.arraycopy(yRow, 0, out, offset, width)
                }
                offset += width
            }

            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val srcCol = if (mirror) chromaWidth - 1 - col else col
                    val uIndex = row * uPlane.rowStride + srcCol * uPlane.pixelStride
                    val vIndex = row * vPlane.rowStride + srcCol * vPlane.pixelStride
                    out[offset++] = uBuffer.get(uIndex)
                    out[offset++] = vBuffer.get(vIndex)
                }
            }
            return out
        }
    }
}
