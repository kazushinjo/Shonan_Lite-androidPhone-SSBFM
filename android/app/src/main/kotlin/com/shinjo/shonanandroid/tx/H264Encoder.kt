package com.shinjo.shonanandroid.tx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * [MediaCodec](バッファモード、ソフトウェア/ハードウェアAVCエンコーダ)をラップし、
 * カメラの生フレームをH.264へエンコードする -- iOS版`H264Encoder.swift`
 * (VideoToolboxの`VTCompressionSession`)のAndroid対応。
 *
 * MediaCodecのAVCエンコーダはAnnex-B(スタートコード付き)NALユニットを出力する。
 * 下流の[com.shinjo.shonanandroid.ts.TsMuxerNative](libavformat mpegtsマクサー)も
 * Annex-B入力を期待するため、RTMP/FLV向けにAVCC変換する他プロジェクトのH264Encoderと異なり
 * **AVCCへの変換は不要**(Shonanの本番導線はMPEG-TS向けでAnnex-Bのまま渡す)。
 */
class H264Encoder {
    data class EncodedFrame(val annexBData: ByteArray, val presentationTimeUsec: Long, val isKeyframe: Boolean)

    /** CSD-0(SPS/PPS)受信時に一度だけ呼ばれる。スタートコードなしの生NALペイロード。 */
    var onSpsPps: ((sps: ByteArray, pps: ByteArray) -> Unit)? = null
    var onEncodedFrame: ((EncodedFrame) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var inputColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private set

    private var codec: MediaCodec? = null
    private var didEmitSpsPps = false
    private val drainExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var draining = false
    private var drainFuture: Future<*>? = null

    fun start(width: Int, height: Int, fps: Int, bitrate: Int) {
        val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
        var lastError: String? = null

        for (candidate in encoderCandidates(mimeType)) {
            inputColorFormat = candidate.colorFormat
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, candidate.colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            val newCodec = try {
                MediaCodec.createByCodecName(candidate.codecName)
            } catch (e: Exception) {
                lastError = "${candidate.codecName}: create失敗(${e.message})"
                continue
            }

            try {
                newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                newCodec.start()
                codec = newCodec
                didEmitSpsPps = false
                draining = true
                drainFuture = drainExecutor.submit { drainLoop() }
                return
            } catch (e: Exception) {
                lastError = "${candidate.codecName}: ${width}x${height}@${fps}, color=${candidate.colorFormat}, bitrate=$bitrate (${e.message})"
                try {
                    newCodec.release()
                } catch (_: Exception) {
                }
            }
        }

        onError?.invoke("MediaCodec初期化に失敗しました: ${lastError ?: "対応H.264エンコーダがありません"}")
    }

    /** [inputColorFormat]で1フレーム分の生データを投入する。入力バッファ待ちで短時間ブロックしうる。 */
    fun encode(data: ByteArray, presentationTimeUs: Long) {
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
        codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
    }

    fun stop() {
        draining = false
        // ★drainLoop()は別スレッドでcodecの出力を読み続けており、drainingをfalseに
        // しただけでは即座には止まらない(dequeueOutputBufferの最大10msタイムアウト分の
        // ラグがある)。このスレッドが完全に終了する前にcodec.stop()/release()すると、
        // 別スレッドが解放済みcodecへアクセスして例外になりうるほか、drainLoop側で
        // まだ発火中のonEncodedFrameコールバックが呼び出し元(TxController)のUDP切断処理と
        // 競合し、「UDP送出に失敗しました」という誤ったエラーが送信停止時に出る原因になっていた。
        // drainLoopの自然終了を待ってから解放することで、stop()が返った時点で以後
        // onEncodedFrameが発火しないことを呼び出し元に保証する。
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
            // バッファモードのエンコーダはsignalEndOfInputStream()未対応(Surfaceモード限定)。
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
            if (!didEmitSpsPps) {
                val nals = splitAnnexB(bytes)
                val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 7 }
                val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == 8 }
                if (sps != null && pps != null) {
                    didEmitSpsPps = true
                    onSpsPps?.invoke(sps, pps)
                }
            }
            return
        }

        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        onEncodedFrame?.invoke(EncodedFrame(bytes, info.presentationTimeUs, isKeyframe))
    }

    companion object {
        private data class EncoderCandidate(val codecName: String, val colorFormat: Int)

        private fun encoderCandidates(mimeType: String): List<EncoderCandidate> {
            val preferredColorFormats = listOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            )
            val candidates = mutableListOf<EncoderCandidate>()
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (mimeType !in info.supportedTypes) continue
                val formats = info.getCapabilitiesForType(mimeType).colorFormats.toSet()
                for (format in preferredColorFormats) {
                    if (format in formats) candidates.add(EncoderCandidate(info.name, format))
                }
            }
            return candidates
        }

        /** Annex-Bバイト列(00 00 00 01/00 00 01スタートコード)を個々のNALユニットへ分割する
         *  (スタートコードは取り除く)。 */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>()
            var i = 0
            while (i < data.size - 3) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    ((data[i + 2] == 1.toByte()) || (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()))
                ) {
                    starts.add(i)
                    i += if (data[i + 2] == 1.toByte()) 3 else 4
                } else {
                    i++
                }
            }
            val nals = mutableListOf<ByteArray>()
            for ((idx, start) in starts.withIndex()) {
                val nalStart = start + if (data[start + 2] == 1.toByte()) 3 else 4
                val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
                if (nalStart < end) {
                    nals.add(data.copyOfRange(nalStart, end))
                }
            }
            return nals
        }
    }
}
