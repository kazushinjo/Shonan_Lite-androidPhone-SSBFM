package com.shinjo.shonanandroid.tx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * [MediaCodec]のAAC-LCエンコーダをラップし、[AudioCapture]が供給する16bit PCMを
 * AACへエンコードする -- iOS版`AACEncoder.swift`(AVAudioConverter)のAndroid対応。
 *
 * [H264Encoder]と同じdrainLoop方式。MediaCodecのAACエンコーダはCSD-0として
 * AudioSpecificConfig(ASC)を1度だけ出力し、以降はADTSヘッダなしのrawなAACフレームを
 * 出力する(MPEG-TSへの多重化にはこのraw形式で十分)。
 *
 * 機器試験(カメラ+音声送出診断)専用。本番のTx画面はvideo-onlyのため未使用。
 */
class AACEncoder {
    data class EncodedFrame(val payload: ByteArray, val presentationTimeUsec: Long)

    /** CSD-0(AudioSpecificConfig)受信時に一度だけ呼ばれる。 */
    var onExtradata: ((asc: ByteArray) -> Unit)? = null
    var onEncodedFrame: ((EncodedFrame) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var didEmitExtradata = false
    private val drainExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var draining = false
    private var drainFuture: Future<*>? = null

    fun start(sampleRate: Int, channelCount: Int, bitrate: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }

        try {
            val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newCodec.start()
            codec = newCodec
        } catch (e: Exception) {
            onError?.invoke("MediaCodec(AAC)初期化に失敗しました: ${e.message}")
            return
        }

        didEmitExtradata = false
        draining = true
        drainFuture = drainExecutor.submit { drainLoop() }
    }

    /** [AudioCapture.SAMPLES_PER_AAC_FRAME]サンプル分の16bit PCMを投入する。 */
    fun encode(data: ByteArray, presentationTimeUsec: Long) {
        val codec = codec ?: return
        val inputIndex = try {
            codec.dequeueInputBuffer(10_000)
        } catch (e: IllegalStateException) {
            return
        }
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUsec, 0)
    }

    fun stop() {
        draining = false
        // ★H264Encoder.stop()と同じ理由(drainLoopの自然終了を待たずcodecをrelease()すると、
        // 別スレッドでの解放済みcodecアクセスや、呼び出し元の後処理とのコールバック競合を招く)。
        try {
            drainFuture?.get(500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            drainFuture?.cancel(true)
        }
        drainFuture = null

        val codec = codec ?: return
        try {
            codec.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            codec.stop()
            codec.release()
        } catch (_: Exception) {
        }
        this.codec = null
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            val codec = codec ?: return
            val outputIndex = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (outputIndex < 0) continue

            val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
            handleOutput(outputBuffer, info)
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun handleOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            if (!didEmitExtradata) {
                didEmitExtradata = true
                onExtradata?.invoke(bytes)
            }
            return
        }

        onEncodedFrame?.invoke(EncodedFrame(bytes, info.presentationTimeUs))
    }
}
