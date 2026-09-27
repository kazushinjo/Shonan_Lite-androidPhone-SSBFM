package com.shinjo.shonanandroid.ts

/**
 * `ts_bridge.cpp`(libavformat mpegtsマクサー、Android NDK arm64-v8a向けクロスビルド --
 * third_party/build_ffmpeg_mpegts.sh参照)のJNIラッパー。
 * iOS版`FFmpegTSMuxer.swift`(`ffmpeg_ts_bridge.h`)の移植。
 *
 * ハンドルはインスタンスごとに1つ、ネイティブ側からのコールバック(`onTsData`)は
 * このオブジェクト自身のメソッドとして呼び戻される(JNI `CallVoidMethod`)。
 */
class TsMuxerNative {
    /** 生成されたTSデータ(任意長)。188byte境界への整形は呼び出し側(TsPacketPacker)の責務。 */
    var onTsData: ((data: ByteArray) -> Unit)? = null

    private val handle: Long = nativeCreate()

    /** sps/ppsはH264Encoderが最初のCSD-0から取得したAnnex B NALペイロード(スタートコードなし)。
     *  aacAscを渡すと音声トラックも登録される(機器試験のカメラ+音声診断専用、本番Tx画面はnull=video-only)。
     *  成功時null、失敗時エラーメッセージを返す。 */
    fun start(
        sps: ByteArray,
        pps: ByteArray,
        width: Int,
        height: Int,
        aacAsc: ByteArray? = null,
        aacSampleRate: Int = 0,
        aacChannels: Int = 0,
    ): String? = nativeStart(handle, sps, pps, width, height, aacAsc, aacSampleRate, aacChannels)

    /** H.264 Annex Bフレーム(1フレーム分、複数NALユニットを含みうる)を1つ書き込む。 */
    fun writeFrame(annexBFrame: ByteArray, presentationTimeUsec: Long, isKeyFrame: Boolean): Int =
        nativeWriteFrame(handle, annexBFrame, presentationTimeUsec, isKeyFrame)

    /** AACフレーム(raw、ADTSヘッダなし)を1つ書き込む。start()にaacAscを渡した場合のみ有効。 */
    fun writeAudioFrame(aacFrame: ByteArray, presentationTimeUsec: Long): Int =
        nativeWriteAudioFrame(handle, aacFrame, presentationTimeUsec)

    fun destroy() = nativeDestroy(handle)

    // ネイティブ側(ts_bridge.cpp)から呼び戻される。
    @Suppress("unused")
    private fun onTsData(data: ByteArray) {
        onTsData?.invoke(data)
    }

    private external fun nativeCreate(): Long
    private external fun nativeStart(
        handle: Long,
        sps: ByteArray,
        pps: ByteArray,
        width: Int,
        height: Int,
        aacAsc: ByteArray?,
        aacSampleRate: Int,
        aacChannels: Int,
    ): String?
    private external fun nativeWriteFrame(handle: Long, annexBFrame: ByteArray, presentationTimeUsec: Long, isKeyFrame: Boolean): Int
    private external fun nativeWriteAudioFrame(handle: Long, aacFrame: ByteArray, presentationTimeUsec: Long): Int
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("ts_bridge")
        }
    }
}
