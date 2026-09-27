package com.shinjo.shonanandroid.tx

import android.util.Log
import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.net.UdpSender
import com.shinjo.shonanandroid.ts.TsMuxerNative
import com.shinjo.shonanandroid.ts.TsPacketPacker

/**
 * [CameraCapture]または[ColorBarSource] -> [H264Encoder]、[AudioCapture] -> [AACEncoder] ->
 * [TsMuxerNative] -> [TsPacketPacker] -> [UdpSender] を結線する送信側パイプライン -- iOS版
 * `TxSessionController.swift`の移植。[AppSettings.useColorBarSource]がtrueの場合、
 * 実機カメラの代わりに固定カラーバーパターンをエンコーダへ供給する
 * (実機カメラなしで送信パイプラインの動作確認をするための機能)。
 */
class TxController(context: Context, lifecycleOwner: LifecycleOwner) {
    var onError: ((String) -> Unit)? = null

    val preview: Preview get() = camera.preview
    /** 送信中の統計情報(ビットレート/パケット数等) -- iOS版`NetworkStats`表示に対応。 */
    val stats = TxNetworkStats()

    private val camera = CameraCapture(context, lifecycleOwner)
    private val colorBarSource = ColorBarSource(context)
    private val photoSource = PhotoSource(context)
    private val encoder = H264Encoder()
    private val audioCapture = AudioCapture()
    private val audioEncoder = AACEncoder()
    private var muxer: TsMuxerNative? = null
    private val packer = TsPacketPacker()
    private val sender = UdpSender()

    private var isRunning = false
    private var muxerStarted = false
    private var usingColorBar = false
    private var usingPhoto = false

    fun start(settings: AppSettings) {
        if (isRunning) return
        usingColorBar = settings.useColorBarSource
        usingPhoto = settings.usePhotoSource
        val transmitAudio = settings.transmitAudio

        camera.onError = { message -> onError?.invoke(message) }
        colorBarSource.onError = { message -> onError?.invoke(message) }
        photoSource.onError = { message -> onError?.invoke(message) }
        encoder.onError = { message -> onError?.invoke(message) }
        audioCapture.onError = { message -> onError?.invoke(message) }
        audioEncoder.onError = { message -> onError?.invoke(message) }
        sender.onError = { message -> onError?.invoke(message) }
        sender.onPacketSent = { byteCount -> stats.recordPacket(byteCount) }

        stats.start()
        val (host, port) = settings.effectiveTxDestination()
        Log.i("TxController", "UDP TS送信開始 destination=$host:$port colorBar=$usingColorBar photo=$usingPhoto audio=$transmitAudio")
        sender.connect(host, port)

        muxerStarted = false
        var pendingSps: ByteArray? = null
        var pendingPps: ByteArray? = null
        var aacAsc: ByteArray? = null
        var videoWidth = VIDEO_WIDTH
        var videoHeight = VIDEO_HEIGHT
        packer.reset()

        val newMuxer = TsMuxerNative()
        newMuxer.onTsData = { data -> packer.ingest(data) }
        muxer = newMuxer

        packer.onDatagram = { datagram ->
            sender.send(datagram)
        }

        encoder.onSpsPps = { sps, pps ->
            pendingSps = sps
            pendingPps = pps
        }
        encoder.onEncodedFrame = onEncodedFrame@{ frame ->
            if (!muxerStarted) {
                // SPS/PPSが揃う最初のキーフレームまでは破棄(iOS版と同じ方針)。
                val sps = pendingSps
                val pps = pendingPps
                if (!frame.isKeyframe || sps == null || pps == null) return@onEncodedFrame
                val error = newMuxer.start(
                    sps, pps, videoWidth, videoHeight,
                    aacAsc, audioCapture.sampleRate, audioCapture.channelCount,
                )
                if (error != null) {
                    onError?.invoke(error)
                    return@onEncodedFrame
                }
                muxerStarted = true
            }
            newMuxer.writeFrame(frame.annexBData, frame.presentationTimeUsec, frame.isKeyframe)
        }

        if (transmitAudio) {
            audioCapture.onFrame = { data, presentationTimeUsec -> audioEncoder.encode(data, presentationTimeUsec) }
            audioCapture.onLevel = { level -> stats.recordAudioLevel(level) }
            audioEncoder.onExtradata = { asc -> aacAsc = asc }
            audioEncoder.onEncodedFrame = { frame -> muxer?.writeAudioFrame(frame.payload, frame.presentationTimeUsec) }
            audioEncoder.start(audioCapture.sampleRate, audioCapture.channelCount, AUDIO_BITRATE_BPS)
            audioCapture.start()
        }

        var encoderStarted = false
        if (usingPhoto) {
            photoSource.onFrame = { data, frameWidth, frameHeight, presentationTimeUs ->
                if (!encoderStarted) {
                    encoderStarted = true
                    videoWidth = frameWidth
                    videoHeight = frameHeight
                    encoder.start(videoWidth, videoHeight, VIDEO_FPS, settings.effectiveVideoBitrateBps)
                }
                encoder.encode(data, presentationTimeUs)
            }
            photoSource.start(settings.selectedPhotoUri, settings.photoCallsign, settings.photoNote, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        } else if (usingColorBar) {
            colorBarSource.onFrame = { data, frameWidth, frameHeight, presentationTimeUs ->
                if (!encoderStarted) {
                    encoderStarted = true
                    videoWidth = frameWidth
                    videoHeight = frameHeight
                    encoder.start(videoWidth, videoHeight, VIDEO_FPS, settings.effectiveVideoBitrateBps)
                }
                encoder.encode(data, presentationTimeUs)
            }
            colorBarSource.start(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        } else {
            // CameraXの解像度選択は要求サイズへのベストエフォートマッチ(端末依存)のため、
            // エンコーダは実際にカメラが配信するフレームサイズが判明してから起動する
            // (CameraCapture.startの要求値をそのまま使うと端末によってサイズが食い違い、
            // MediaCodec入力バッファへの書き込みでBufferOverflowExceptionになる)。
            camera.onFrame = { data, frameWidth, frameHeight, presentationTimeUs ->
                if (!encoderStarted) {
                    encoderStarted = true
                    videoWidth = frameWidth
                    videoHeight = frameHeight
                    encoder.start(videoWidth, videoHeight, VIDEO_FPS, settings.effectiveVideoBitrateBps)
                }
                encoder.encode(data, presentationTimeUs)
            }
            camera.start(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK, VIDEO_WIDTH, VIDEO_HEIGHT)
        }

        isRunning = true
    }

    /**
     * 送信を開始していない間もカメラ映像を確認できるよう、エンコード/送出パイプラインを
     * 起動せずカメラ入力とプレビューだけを開始する -- iOS版`TxSessionController.startCameraPreview`
     * の移植。実送信中(isRunning)は既に[start]側でカメラを管理しているため何もしない。
     */
    fun startCameraPreview(position: CameraPosition) {
        if (isRunning) return
        camera.onError = { message -> onError?.invoke(message) }
        camera.start(position, VIDEO_WIDTH, VIDEO_HEIGHT)
    }

    /**
     * [startCameraPreview]で開始したプレビュー専用のカメラを停止する -- iOS版
     * `stopCameraPreviewIfIdle`の移植。実送信中は[stop]側がカメラを管理しているため何もしない。
     */
    fun stopCameraPreviewIfIdle() {
        if (isRunning) return
        camera.stop()
    }

    fun stop() {
        if (!isRunning) return
        camera.stop()
        colorBarSource.stop()
        photoSource.stop()
        encoder.stop()
        audioCapture.stop()
        audioEncoder.stop()
        packer.flush()
        muxer?.destroy()
        muxer = null
        sender.disconnect()
        stats.stop()
        isRunning = false
    }

    companion object {
        // CameraCapture.startへの要求値。DVB-S2の実運用シンボルレートで安定して
        // 送受信できるようHD(720p)に落としている(1080pは低シンボルレートでは
        // ビットレートに対して負荷が高すぎる)。実際にエンコーダ/TS muxerへ渡すサイズは、
        // カメラが配信する実フレームサイズ(端末依存でこの要求値と異なりうる)を使う。
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        // 機器診断パイプライン(CameraAudioTxDiagPipeline)と同じ既定値。
        private const val AUDIO_BITRATE_BPS = 64_000
    }
}
