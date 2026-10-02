package com.shinjo.shonanandroid.tx

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AudioRecord]でマイク音声をキャプチャする -- iOS版`AudioCapture.swift`の移植。
 *
 * `AudioRecord.read()`は呼び出しごとに任意サイズのサンプルを返すため(バッファサイズの
 * 保証がない)、[AACEncoder]へ渡す1フレーム分([SAMPLES_PER_AAC_FRAME]サンプル)ちょうどの
 * 固定チャンクに整形してから供給する。これを怠るとMediaCodecの入力バッファサイズと
 * 合わずBufferOverflowExceptionになる(iOS版コメント、他プロジェクトでも実際に
 * 踏んだ既知のバグクラス)。
 *
 * 機器試験(カメラ+音声送出診断)専用。本番のTx画面はvideo-onlyのため未使用。
 */
class AudioCapture {
    var onFrame: ((data: ByteArray, presentationTimeUsec: Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** マイク入力レベル(0f〜1f、RMSをフルスケールで正規化)。読み取りチャンクごとに通知。 */
    var onLevel: ((level: Float) -> Unit)? = null

    /** 送信音量の倍率(1fで入力そのまま)。送信中にUIスレッドから変更され、録音スレッドで読む。 */
    @Volatile
    var gain: Float = 1f

    val sampleRate = 44100
    val channelCount = 1

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            onError?.invoke("AudioRecordのバッファサイズ取得に失敗しました")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2,
            )
        } catch (e: SecurityException) {
            onError?.invoke("マイクへのアクセスが許可されていません")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError?.invoke("AudioRecordの初期化に失敗しました")
            record.release()
            return
        }

        audioRecord = record
        running = true
        record.startRecording()

        val thread = Thread { recordLoop(record) }
        thread.name = "AudioCapture"
        thread.start()
        recordThread = thread
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            recordThread?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordThread = null
        audioRecord?.let {
            it.release()
        }
        audioRecord = null
    }

    private fun recordLoop(record: AudioRecord) {
        val pendingSamples = ArrayList<Short>(SAMPLES_PER_AAC_FRAME * 2)
        var totalSamplesEmitted = 0L
        val readBuf = ShortArray(SAMPLES_PER_AAC_FRAME)

        while (running) {
            val n = try {
                record.read(readBuf, 0, readBuf.size)
            } catch (e: IllegalStateException) {
                if (running) onError?.invoke("AudioRecord.readに失敗しました: ${e.message}")
                break
            }
            if (n <= 0) continue

            // 送信音量の倍率を掛ける(飽和はクリップ)。レベル表示も調整後の値にする。
            val currentGain = gain
            if (currentGain != 1f) {
                for (i in 0 until n) {
                    val scaled = readBuf[i] * currentGain
                    readBuf[i] = scaled.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                }
            }

            var sumSquares = 0.0
            for (i in 0 until n) sumSquares += readBuf[i].toDouble() * readBuf[i].toDouble()
            val rms = kotlin.math.sqrt(sumSquares / n)
            onLevel?.invoke((rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f))

            for (i in 0 until n) pendingSamples.add(readBuf[i])

            while (pendingSamples.size >= SAMPLES_PER_AAC_FRAME) {
                val bytes = ByteArray(SAMPLES_PER_AAC_FRAME * 2)
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until SAMPLES_PER_AAC_FRAME) bb.putShort(pendingSamples[i])
                pendingSamples.subList(0, SAMPLES_PER_AAC_FRAME).clear()

                val presentationTimeUsec = totalSamplesEmitted * 1_000_000L / sampleRate
                totalSamplesEmitted += SAMPLES_PER_AAC_FRAME
                onFrame?.invoke(bytes, presentationTimeUsec)
            }
        }
    }

    companion object {
        /** AACの1フレームあたりサンプル数(AAC-LC固定)。 */
        const val SAMPLES_PER_AAC_FRAME = 1024

        /**
         * 送信音量(0〜100%)を倍率に変換する(iPad版AudioCapture.gain(forVolumePercent:)と同じ)。
         * Pi5版の初期値80%を入力そのまま(1倍)とし、80%以下は比例して小さく(0%で無音)、
         * 80%超は100%で2倍(+6dB)まで大きくする。端末内蔵マイクは録音ゲインを変えられない
         * ことが多いため、サンプルに倍率を掛ける。
         */
        fun gainForVolumePercent(percent: Int): Float {
            val p = percent.coerceIn(0, 100).toFloat()
            return if (p <= 80f) p / 80f else 1f + (p - 80f) / 20f
        }
    }
}
