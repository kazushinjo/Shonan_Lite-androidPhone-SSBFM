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

    /** CSD-0受信時に一度だけ呼ばれる。H.264はSPS/PPS、H.265はVPS/SPS/PPSを
     *  Annex B(スタートコード付き)で並べたバイト列。 */
    var onCodecConfig: ((codecConfig: ByteArray) -> Unit)? = null

    /** trueならH.265(HEVC)で符号化する。start()の前に設定する。 */
    var hevc: Boolean = false
    var onEncodedFrame: ((EncodedFrame) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var inputColorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private set

    private var codec: MediaCodec? = null
    private var didEmitSpsPps = false
    private var codecConfig: ByteArray? = null
    private val drainExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var draining = false
    private var drainFuture: Future<*>? = null

    fun start(width: Int, height: Int, fps: Int, bitrate: Int) {
        val mimeType = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
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
                codecConfig = null
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

        onError?.invoke("MediaCodec初期化に失敗しました: ${lastError ?: if (hevc) "対応H.265エンコーダがありません" else "対応H.264エンコーダがありません"}")
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
                val required = if (hevc) listOf(32, 33, 34) else listOf(7, 8)
                val found = required.map { type -> nals.firstOrNull { nalType(it) == type } }
                if (found.all { it != null }) {
                    didEmitSpsPps = true
                    val config = java.io.ByteArrayOutputStream()
                    for (nal in found) {
                        config.write(START_CODE)
                        config.write(nal!!)
                    }
                    codecConfig = config.toByteArray()
                    onCodecConfig?.invoke(codecConfig!!)
                }
            }
            return
        }

        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        // H.265: 端末のエンコーダはVPS/SPS/PPSを最初のCSDでしか出さないことが多い。RXは
        // 途中から視聴を始めるため、キーフレームの先頭にVPS/SPS/PPSが無ければ付け足す
        // (Pi5版のx265 repeat-headers=1と同じ目的)。
        val config = codecConfig
        val frameBytes = if (hevc && isKeyframe && config != null &&
            splitAnnexB(bytes).firstOrNull()?.let { nalType(it) } != 32
        ) config + bytes else bytes
        onEncodedFrame?.invoke(EncodedFrame(frameBytes, info.presentationTimeUs, isKeyframe))
    }

    /** NALタイプ(H.264は先頭バイトの下位5bit、H.265は先頭バイトのbit1〜6)。 */
    private fun nalType(nal: ByteArray): Int =
        if (nal.isEmpty()) -1 else if (hevc) (nal[0].toInt() shr 1) and 0x3F else nal[0].toInt() and 0x1F

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** この端末にH.265(HEVC)で符号化できるエンコーダがあるか。 */
        fun isHevcEncoderAvailable(): Boolean =
            runCatching { encoderCandidates(MediaFormat.MIMETYPE_VIDEO_HEVC).isNotEmpty() }.getOrDefault(false)

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
