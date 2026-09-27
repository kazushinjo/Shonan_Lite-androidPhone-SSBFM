package com.shinjo.shonanandroid.ts

/**
 * `ts_bridge.cpp`(libavformat mpegtsデマクサー)のJNIラッパー。
 * iOS版`FFmpegTSDemuxer.swift`(`ffmpeg_ts_bridge.h`)の移植。
 *
 * 生成時にネイティブ側が読み取り専用スレッドを1本起動し、[ingest]で投入された
 * TSバイト列を逐次パースする(呼び出し元スレッドをブロックしない)。
 */
class TsDemuxerNative {
    /** 1フレーム分のH.264 Annex Bデータを再構成できたときに呼ばれる。 */
    var onFrame: ((annexBData: ByteArray, presentationTimeUsec: Long, isKeyFrame: Boolean) -> Unit)? = null
    /** 相手側音声(AAC)をデコードしたRMSレベル(0f〜1f)。 */
    var onAudioLevel: ((level: Float) -> Unit)? = null
    /** 相手側音声(AAC)をデコードしたS16interleaved PCM(AudioTrack再生用)。 */
    var onAudioPcm: ((pcm: ByteArray, sampleRate: Int, channelCount: Int) -> Unit)? = null

    private val handle: Long = nativeCreate()

    /** UDPで受信したTSペイロード(既定1316byte)を投入する。 */
    fun ingest(tsData: ByteArray) = nativeIngest(handle, tsData)

    fun destroy() = nativeDestroy(handle)

    // ネイティブ側(ts_bridge.cpp、読み取りスレッド上)から呼び戻される。
    @Suppress("unused")
    private fun onFrame(annexBData: ByteArray, presentationTimeUsec: Long, isKeyFrame: Boolean) {
        onFrame?.invoke(annexBData, presentationTimeUsec, isKeyFrame)
    }

    // ネイティブ側(ts_bridge.cpp、読み取りスレッド上)から呼び戻される。
    @Suppress("unused")
    private fun onAudioLevel(level: Float) {
        onAudioLevel?.invoke(level)
    }

    // ネイティブ側(ts_bridge.cpp、読み取りスレッド上)から呼び戻される。
    @Suppress("unused")
    private fun onAudioPcm(pcm: ByteArray, sampleRate: Int, channelCount: Int) {
        onAudioPcm?.invoke(pcm, sampleRate, channelCount)
    }

    private external fun nativeCreate(): Long
    private external fun nativeIngest(handle: Long, tsData: ByteArray)
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("ts_bridge")
        }
    }
}
