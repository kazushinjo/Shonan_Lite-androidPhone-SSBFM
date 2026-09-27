package com.shinjo.shonanandroid.dvbs2

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.shinjo.shonanandroid.ts.TsMuxerNative
import com.shinjo.shonanandroid.tx.AACEncoder
import com.shinjo.shonanandroid.tx.AudioCapture
import com.shinjo.shonanandroid.tx.CameraCapture
import com.shinjo.shonanandroid.tx.CameraPosition
import com.shinjo.shonanandroid.tx.H264Encoder

/**
 * 機器診断(diag)機能用: カメラ映像+マイク音声を実際にキャプチャ→H.264/AACエンコード→
 * MPEG-TS多重化した上で[Dvbs2TxPipeline.write]に渡し、Plutoへの送出経路が健全か
 * (TsMuxerNative/ts_bridge.cpp/Dvbs2TxPipelineの全区間)を確認する -- iOS版
 * `CameraAudioTxDiagPipeline.swift`のAndroid対応。復調・受信側の検証は行わない
 * (半二重運用のためTX単体診断で十分という判断、iOS版と同じ)。
 */
class CameraAudioTxDiagPipeline(context: Context, lifecycleOwner: LifecycleOwner) {
    private val camera = CameraCapture(context, lifecycleOwner)
    private val videoEncoder = H264Encoder()
    private val audioCapture = AudioCapture()
    private val audioEncoder = AACEncoder()
    private var muxer: TsMuxerNative? = null

    val cameraPreview: Preview get() = camera.preview

    var onTsData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var videoFrameCount = 0
        private set
    var audioFrameCount = 0
        private set

    private var isRunning = false
    private var muxerStarted = false
    private var encoderStarted = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var aacAsc: ByteArray? = null
    private var videoBitrateBps = 0
    private var audioBitrateBps = 0
    private var actualVideoWidth = VIDEO_WIDTH
    private var actualVideoHeight = VIDEO_HEIGHT

    fun start(position: CameraPosition, videoBitrateBps: Int, audioBitrateBps: Int) {
        if (isRunning) return
        isRunning = true
        this.videoBitrateBps = videoBitrateBps
        this.audioBitrateBps = audioBitrateBps

        videoFrameCount = 0
        audioFrameCount = 0
        muxerStarted = false
        encoderStarted = false
        pendingSps = null
        pendingPps = null
        aacAsc = null
        actualVideoWidth = VIDEO_WIDTH
        actualVideoHeight = VIDEO_HEIGHT

        camera.onError = { message -> onError?.invoke("CameraCapture: $message") }
        videoEncoder.onError = { message -> onError?.invoke("H264Encoder: $message") }
        audioCapture.onError = { message -> onError?.invoke("AudioCapture: $message") }
        audioEncoder.onError = { message -> onError?.invoke("AACEncoder: $message") }

        val newMuxer = TsMuxerNative()
        newMuxer.onTsData = { data ->
            if (isRunning) onTsData?.invoke(data)
        }
        muxer = newMuxer

        videoEncoder.onSpsPps = videoSpsPps@{ sps, pps ->
            if (!isRunning) return@videoSpsPps
            pendingSps = sps
            pendingPps = pps
        }
        videoEncoder.onEncodedFrame = videoFrame@{ frame ->
            if (!isRunning) return@videoFrame
            videoFrameCount++
            if (!muxerStarted) {
                // SPS/PPSが揃う最初のキーフレームまでは破棄(本番Tx画面のTxController.ktと同じ方針)。
                val sps = pendingSps
                val pps = pendingPps
                if (!frame.isKeyframe || sps == null || pps == null) return@videoFrame
                val error = newMuxer.start(
                    sps, pps, actualVideoWidth, actualVideoHeight,
                    aacAsc, audioCapture.sampleRate, audioCapture.channelCount,
                )
                if (error != null) {
                    onError?.invoke(error)
                    return@videoFrame
                }
                muxerStarted = true
            }
            newMuxer.writeFrame(frame.annexBData, frame.presentationTimeUsec, frame.isKeyframe)
        }

        audioCapture.onFrame = { data, presentationTimeUsec ->
            if (isRunning) audioEncoder.encode(data, presentationTimeUsec)
        }
        audioEncoder.onExtradata = { asc ->
            if (isRunning) aacAsc = asc
        }
        audioEncoder.onEncodedFrame = audioFrame@{ frame ->
            if (!isRunning) return@audioFrame
            audioFrameCount++
            muxer?.writeAudioFrame(frame.payload, frame.presentationTimeUsec)
        }

        camera.onFrame = cameraFrame@{ data, frameWidth, frameHeight, presentationTimeUs ->
            if (!isRunning) return@cameraFrame
            if (!encoderStarted) {
                encoderStarted = true
                actualVideoWidth = frameWidth
                actualVideoHeight = frameHeight
                videoEncoder.start(frameWidth, frameHeight, VIDEO_FPS, this.videoBitrateBps)
            }
            videoEncoder.encode(data, presentationTimeUs)
        }

        audioEncoder.start(audioCapture.sampleRate, audioCapture.channelCount, this.audioBitrateBps)
        audioCapture.start()
        camera.start(position, VIDEO_WIDTH, VIDEO_HEIGHT)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        camera.onFrame = null
        audioCapture.onFrame = null
        videoEncoder.onEncodedFrame = null
        audioEncoder.onEncodedFrame = null
        muxer?.onTsData = null
        camera.stop()
        videoEncoder.stop()
        audioCapture.stop()
        audioEncoder.stop()
        muxer?.destroy()
        muxer = null
        muxerStarted = false
    }

    companion object {
        // videoEncoder.start()には実測フレームサイズを渡すため、ここは要求値に留まる
        // (端末依存でVIDEO_WIDTH/VIDEO_HEIGHTと異なりうる。TxController.ktと同じ理由)。
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 15
    }
}
