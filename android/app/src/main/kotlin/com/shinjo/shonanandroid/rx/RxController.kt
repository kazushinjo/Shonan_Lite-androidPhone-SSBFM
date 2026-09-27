package com.shinjo.shonanandroid.rx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.net.UdpReceiver
import com.shinjo.shonanandroid.ts.TsDemuxerNative
import com.shinjo.shonanandroid.dvbs2rx.Dvbs2rxPipeline
import java.util.concurrent.atomic.AtomicLong

/**
 * [UdpReceiver] -> [TsDemuxerNative] -> [H264DisplaySurface]/[AudioTrack] を結線する受信側
 * パイプライン -- iOS版`RxSessionController.swift`の移植。
 *
 * [displayDecoder]はUI層(RxScreen)がSurfaceView/TextureView取得時に個別に
 * `configure()`する(iOS版で`displayDecoder`がpublicなのと同じ理由)。
 *
 * 通常受信では外部復調機器からUDP-TSを受け取る。
 */
class RxController(private val context: Context) {
    var onError: ((String) -> Unit)? = null

    val displayDecoder = H264DisplaySurface()
    val continuityTracker = TSContinuityTracker()
    /** 相手側音声のRMSレベル(0f〜1f)。 */
    var audioLevel by mutableStateOf(0f)
        private set
    /** 本番RXは復調機器(Pluto等)から届くUDP-TSをそのまま表示するだけで、DVB-S2の
     *  同期ロック状態そのものは分からない(それを担うaff3ct復調は「機器試験」機能専用の
     *  別経路)。復調機器からのステータスプロトコル(rxStatusPort)は仕様未確定のため、
     *  直近[LOCK_TIMEOUT_MS]以内にTSペイロードが届いているかどうかを「ロック中」の
     *  代用指標として扱う(ロック外れ→BCH/LDPC失敗→有効なTSが出ない、という関係を
     *  逆手に取った近似)。 */
    var isLocked by mutableStateOf(false)
        private set

    private val receiver = UdpReceiver()
    /** ステータスポート(既定4002)。★フォーマット未確定のため受信のみ(iOS版と同じ扱い)。 */
    private val statusReceiver = UdpReceiver()
    private var demuxer: TsDemuxerNative? = null
    private var tsAligner: TsPacketAligner? = null
    private var isRunning = false
    private var onDeviceRx: Dvbs2rxPipeline? = null

    private val lastPacketAtMs = AtomicLong(0)
    private var lockWatchdogThread: HandlerThread? = null

    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0
    private var audioTrackChannelCount = 0
    private var pendingVolume = 1f

    /** タブレットの受信音声出力音量(0f〜1f)を設定する。 */
    fun setVolume(volume: Float) {
        pendingVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(pendingVolume)
    }

    fun start(settings: AppSettings) {
        if (isRunning) return

        continuityTracker.reset()
        audioLevel = 0f
        isLocked = false
        lastPacketAtMs.set(0)
        pendingVolume = settings.rxVolume

        val newDemuxer = TsDemuxerNative()
        newDemuxer.onFrame = { data, ptsUsec, _ -> displayDecoder.decode(data, ptsUsec) }
        newDemuxer.onAudioLevel = { level -> audioLevel = level }
        newDemuxer.onAudioPcm = { pcm, sampleRate, channelCount -> playPcm(pcm, sampleRate, channelCount) }
        demuxer = newDemuxer

        val aligner = TsPacketAligner { data ->
            lastPacketAtMs.set(SystemClock.elapsedRealtime())
            continuityTracker.ingest(data)
            newDemuxer.ingest(data)
        }
        tsAligner = aligner

        if (settings.useOnDeviceGRDVBS2Rx) {
            val pipeline = runCatching { Dvbs2rxPipeline(context) }.getOrElse {
                onError?.invoke("オンデバイス復調の初期化に失敗しました: ${it.message}")
                newDemuxer.destroy()
                demuxer = null
                return
            }
            pipeline.onError = { message -> onError?.invoke(message) }
            pipeline.onTsData = { data -> aligner.ingest(data) }
            val started = pipeline.start(
                context = context,
                plutoUri = "ip:${settings.txDestinationIP}",
                constellation = settings.modulationScheme.label,
                codeRate = settings.fecRate.label,
                sampleRateHz = (settings.effectiveOperationalSymbolRateMsps * 2_000_000.0).toLong(),
                loHz = settings.effectiveLoHz,
                gainDb = settings.rxGainDb.toDouble(),
                agcEnabled = settings.rxAgcEnabled,
            )
            if (!started) {
                pipeline.destroy()
                newDemuxer.destroy()
                demuxer = null
                onError?.invoke("オンデバイス復調を開始できませんでした")
                return
            }
            onDeviceRx = pipeline
        } else {
            receiver.onError = { message -> onError?.invoke(message) }
            receiver.onPayload = { data -> aligner.ingest(data) }
            receiver.start(settings.rxListenPort)
            statusReceiver.start(settings.rxStatusPort)
        }

        val thread = HandlerThread("RxLockWatchdog").apply { start() }
        lockWatchdogThread = thread
        val handler = Handler(thread.looper)
        val watchdog = object : Runnable {
            override fun run() {
                val last = lastPacketAtMs.get()
                isLocked = if (settings.useOnDeviceGRDVBS2Rx) {
                    onDeviceRx?.isLocked() == true
                } else {
                    last != 0L && SystemClock.elapsedRealtime() - last < LOCK_TIMEOUT_MS
                }
                handler.postDelayed(this, LOCK_POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchdog, LOCK_POLL_INTERVAL_MS)

        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        if (onDeviceRx != null) {
            onDeviceRx?.destroy()
            onDeviceRx = null
        } else {
            receiver.stop()
            statusReceiver.stop()
        }
        demuxer?.destroy()
        demuxer = null
        tsAligner?.let { aligner ->
            android.util.Log.i(
                "RxController",
                "TS aligner: resyncs=${aligner.resyncCount} droppedBytes=${aligner.droppedBytes}",
            )
            aligner.reset()
        }
        tsAligner = null
        audioLevel = 0f
        isLocked = false
        lockWatchdogThread?.let { thread ->
            thread.quitSafely()
            thread.join()
        }
        lockWatchdogThread = null
        releaseAudioTrack()
        isRunning = false
    }

    // TsDemuxerNativeの読み取りスレッドから直接呼ばれる(AudioTrack.write()はそのスレッドで
    // ブロックしてよい、ストリーミング再生として意図した挙動)。
    private fun playPcm(pcm: ByteArray, sampleRate: Int, channelCount: Int) {
        if (audioTrack == null || sampleRate != audioTrackSampleRate || channelCount != audioTrackChannelCount) {
            releaseAudioTrack()
            val channelMask = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, pcm.size) * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setVolume(pendingVolume)
            track.play()
            audioTrack = track
            audioTrackSampleRate = sampleRate
            audioTrackChannelCount = channelCount
        }
        audioTrack?.write(pcm, 0, pcm.size)
    }

    private fun releaseAudioTrack() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        audioTrackSampleRate = 0
        audioTrackChannelCount = 0
    }

    companion object {
        private const val LOCK_TIMEOUT_MS = 2_000L
        private const val LOCK_POLL_INTERVAL_MS = 500L
    }
}
