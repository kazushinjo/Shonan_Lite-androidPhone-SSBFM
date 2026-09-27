// MPEG-TS Muxer/Demuxer(libavformat採用)のJNIブリッジ。
// iOS版 ios/Shonan/TS/ffmpeg_ts_bridge.c(datv_ts_muxer_*/datv_ts_demuxer_*)の移植。
// C関数ポインタコールバックの代わりに、JNI経由でKotlin側メソッドを呼び出す。
// 本番導線はvideo-onlyのため音声(AAC)関連の引数は残しつつ未使用。

#include <jni.h>
#include <pthread.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <future>
#include <memory>
#include <thread>
#include <unistd.h>
#include <vector>

#include <android/log.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mathematics.h>
}

#define LOG_TAG "TsBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// MARK: - Muxer

struct Muxer {
    AVFormatContext *fmtCtx = nullptr;
    AVIOContext *avioCtx = nullptr;
    AVStream *videoStream = nullptr;
    AVStream *audioStream = nullptr; // 音声診断機能用: nullptrなら映像のみ(本番Tx画面はこちら)
    bool headerWritten = false;

    // ネイティブ→Kotlinコールバック用。JNI呼び出しは常にnativeStart/nativeWriteFrame/
    // nativeWriteAudioFrameを呼んだスレッド上で同期的に発生するため、その呼び出し中のみ
    // 有効なenvを保持する。カメラ+音声送出診断機能ではH264Encoder/AACEncoderそれぞれの
    // drainスレッドから並行してnativeWriteFrame/nativeWriteAudioFrameが呼ばれうるため、
    // writeMutexでこのフィールドとfmtCtxへのアクセスを直列化する(単一スレッドしか
    // 書き込まない本番Tx画面(video-only)には影響しないが、両方の経路で保護する)。
    JNIEnv *callbackEnv = nullptr;
    jobject callbackObj = nullptr; // TsMuxerNativeのグローバル参照
    jmethodID onTsDataMethod = nullptr;
    pthread_mutex_t writeMutex = PTHREAD_MUTEX_INITIALIZER;
};

int muxerWritePacket(void *opaque, const uint8_t *buf, int bufSize) {
    auto *muxer = static_cast<Muxer *>(opaque);
    if (muxer->callbackEnv && muxer->callbackObj) {
        jbyteArray array = muxer->callbackEnv->NewByteArray(bufSize);
        muxer->callbackEnv->SetByteArrayRegion(array, 0, bufSize, reinterpret_cast<const jbyte *>(buf));
        muxer->callbackEnv->CallVoidMethod(muxer->callbackObj, muxer->onTsDataMethod, array);
        muxer->callbackEnv->DeleteLocalRef(array);
    }
    return bufSize;
}

void destroyMuxer(JNIEnv *env, Muxer *muxer) {
    if (!muxer) return;
    // 呼び出し元(Kotlin側)はvideoEncoder/audioEncoderのdrainスレッド終了を待ってから
    // destroy()を呼ぶ設計だが、念のためnativeWriteFrame/nativeWriteAudioFrame実行中との
    // 競合を防ぐ。
    pthread_mutex_lock(&muxer->writeMutex);
    if (muxer->fmtCtx && muxer->headerWritten) {
        av_write_trailer(muxer->fmtCtx);
    }
    if (muxer->avioCtx) {
        av_freep(&muxer->avioCtx->buffer);
        avio_context_free(&muxer->avioCtx);
    }
    if (muxer->fmtCtx) {
        muxer->fmtCtx->pb = nullptr;
        avformat_free_context(muxer->fmtCtx);
    }
    pthread_mutex_unlock(&muxer->writeMutex);
    if (muxer->callbackObj) {
        env->DeleteGlobalRef(muxer->callbackObj);
    }
    pthread_mutex_destroy(&muxer->writeMutex);
    delete muxer;
}

// MARK: - Demuxer

constexpr int kRingCapacity = 1024 * 1024;

struct Demuxer {
    AVFormatContext *fmtCtx = nullptr;
    AVIOContext *avioCtx = nullptr;
    int videoStreamIndex = -1;
    AVBSFContext *bsfCtx = nullptr;

    // 受信側の音声レベル表示用: 音声(AAC)をデコードしRMSレベルのみKotlin側へ通知する
    // (音声そのものの再生は本番導線では未対応、TxController側のAudioCaptureと対になる
    // 表示専用の最小実装)。
    int audioStreamIndex = -1;
    AVCodecContext *audioDecCtx = nullptr;

    uint8_t ringBuffer[kRingCapacity];
    int ringReadPos = 0;
    int ringWritePos = 0;
    int ringAvailable = 0;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    bool shouldStop = false;

    pthread_t readThread{};
    bool threadStarted = false;

    JavaVM *jvm = nullptr;
    jobject callbackObj = nullptr; // TsDemuxerNativeのグローバル参照
    jmethodID onFrameMethod = nullptr;
    jmethodID onAudioLevelMethod = nullptr;
    jmethodID onAudioPcmMethod = nullptr;
};

// AACデコード結果(PCM)からRMSレベル(0f〜1f)を計算する。フォーマット非対応時はNANを返す。
float computeAudioLevel(const AVFrame *frame) {
    double sumSquares = 0.0;
    int64_t sampleCount = 0;
    switch (frame->format) {
        case AV_SAMPLE_FMT_FLTP: {
            const auto *samples = reinterpret_cast<const float *>(frame->extended_data[0]);
            for (int i = 0; i < frame->nb_samples; i++) sumSquares += static_cast<double>(samples[i]) * samples[i];
            sampleCount = frame->nb_samples;
            break;
        }
        case AV_SAMPLE_FMT_FLT: {
            const auto *samples = reinterpret_cast<const float *>(frame->data[0]);
            int total = frame->nb_samples * frame->ch_layout.nb_channels;
            for (int i = 0; i < total; i++) sumSquares += static_cast<double>(samples[i]) * samples[i];
            sampleCount = total;
            break;
        }
        case AV_SAMPLE_FMT_S16P: {
            const auto *samples = reinterpret_cast<const int16_t *>(frame->extended_data[0]);
            for (int i = 0; i < frame->nb_samples; i++) {
                double v = samples[i] / 32768.0;
                sumSquares += v * v;
            }
            sampleCount = frame->nb_samples;
            break;
        }
        case AV_SAMPLE_FMT_S16: {
            const auto *samples = reinterpret_cast<const int16_t *>(frame->data[0]);
            int total = frame->nb_samples * frame->ch_layout.nb_channels;
            for (int i = 0; i < total; i++) {
                double v = samples[i] / 32768.0;
                sumSquares += v * v;
            }
            sampleCount = total;
            break;
        }
        default:
            return NAN;
    }
    if (sampleCount <= 0) return NAN;
    return static_cast<float>(sqrt(sumSquares / static_cast<double>(sampleCount)));
}

// AACデコード結果(PCM)をAudioTrack再生用のS16interleavedへ変換する。非対応フォーマットは空配列。
std::vector<int16_t> convertToS16Interleaved(const AVFrame *frame) {
    int channels = frame->ch_layout.nb_channels;
    std::vector<int16_t> out(static_cast<size_t>(frame->nb_samples) * channels);
    auto clampToS16 = [](float v) -> int16_t {
        if (v > 1.0f) v = 1.0f;
        if (v < -1.0f) v = -1.0f;
        return static_cast<int16_t>(v * 32767.0f);
    };
    switch (frame->format) {
        case AV_SAMPLE_FMT_FLTP: {
            for (int ch = 0; ch < channels; ch++) {
                const auto *samples = reinterpret_cast<const float *>(frame->extended_data[ch]);
                for (int i = 0; i < frame->nb_samples; i++) out[i * channels + ch] = clampToS16(samples[i]);
            }
            break;
        }
        case AV_SAMPLE_FMT_FLT: {
            const auto *samples = reinterpret_cast<const float *>(frame->data[0]);
            for (size_t i = 0; i < out.size(); i++) out[i] = clampToS16(samples[i]);
            break;
        }
        case AV_SAMPLE_FMT_S16P: {
            for (int ch = 0; ch < channels; ch++) {
                const auto *samples = reinterpret_cast<const int16_t *>(frame->extended_data[ch]);
                for (int i = 0; i < frame->nb_samples; i++) out[i * channels + ch] = samples[i];
            }
            break;
        }
        case AV_SAMPLE_FMT_S16: {
            const auto *samples = reinterpret_cast<const int16_t *>(frame->data[0]);
            std::memcpy(out.data(), samples, out.size() * sizeof(int16_t));
            break;
        }
        default:
            out.clear();
    }
    return out;
}

int demuxerReadPacket(void *opaque, uint8_t *buf, int bufSize) {
    auto *demuxer = static_cast<Demuxer *>(opaque);
    static std::atomic<int> callCount{0};
    int myCount = callCount.fetch_add(1);
    if (myCount < 10) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "demuxerReadPacket: call#%d bufSize=%d", myCount, bufSize);
    }
    pthread_mutex_lock(&demuxer->mutex);
    while (demuxer->ringAvailable == 0 && !demuxer->shouldStop) {
        pthread_cond_wait(&demuxer->cond, &demuxer->mutex);
    }
    if (demuxer->shouldStop && demuxer->ringAvailable == 0) {
        pthread_mutex_unlock(&demuxer->mutex);
        return AVERROR_EOF;
    }
    int toCopy = bufSize < demuxer->ringAvailable ? bufSize : demuxer->ringAvailable;
    for (int i = 0; i < toCopy; i++) {
        buf[i] = demuxer->ringBuffer[(demuxer->ringReadPos + i) % kRingCapacity];
    }
    demuxer->ringReadPos = (demuxer->ringReadPos + toCopy) % kRingCapacity;
    demuxer->ringAvailable -= toCopy;
    pthread_mutex_unlock(&demuxer->mutex);
    if (myCount < 10) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "demuxerReadPacket: call#%d returned toCopy=%d", myCount, toCopy);
    }
    return toCopy;
}

void *demuxerReadThreadMain(void *arg) {
    auto *demuxer = static_cast<Demuxer *>(arg);

    JNIEnv *env = nullptr;
    bool attached = false;
    if (demuxer->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (demuxer->jvm->AttachCurrentThread(&env, nullptr) != 0) {
            return nullptr;
        }
        attached = true;
    }

    // ★gr-dvbs2rx経由のRXでは、DVB-S2フレーム同期ロック直後の数百msに大量のBCH/LDPC
    // 復号エラーを含んだデータが混入し、avformat_open_input/find_stream_infoが失敗する
    // ことがある(実機で確認済み)。UDP版(常にクリーンなTS)では起きなかった問題。失敗時に
    // スレッドごと終了すると以後そのRXセッションは永久に映像が出なくなるため、リソースを
    // 解放してリングバッファをクリアし、再同期を試みるリトライループにする。
retry_open:
    if (demuxer->shouldStop) {
        if (attached) demuxer->jvm->DetachCurrentThread();
        return nullptr;
    }

    AVFormatContext *fmtCtx = avformat_alloc_context();
    if (!fmtCtx) {
        if (attached) demuxer->jvm->DetachCurrentThread();
        return nullptr;
    }

    const int avioBufferSize = 4096;
    auto *avioBuf = static_cast<uint8_t *>(av_malloc(avioBufferSize));
    AVIOContext *avioCtx = avio_alloc_context(avioBuf, avioBufferSize, 0, demuxer, demuxerReadPacket, nullptr, nullptr);
    if (!avioCtx) {
        avformat_free_context(fmtCtx);
        if (attached) demuxer->jvm->DetachCurrentThread();
        return nullptr;
    }
    demuxer->avioCtx = avioCtx;
    fmtCtx->pb = avioCtx;
    fmtCtx->flags |= AVFMT_FLAG_CUSTOM_IO;
    // ★gr-dvbs2rx経由のRXではBCH/LDPCエラーで壊れたTSパケットが混在するため、
    // avformat_find_stream_info()がデフォルトのprobesize(5MB)/max_analyze_duration
    // (5秒相当のPTS)を使い切ってもPAT/PMTを確定できず極めて長時間(実機で2分以上)
    // 粘り続ける事象を確認した。明示的に小さめの上限を設定し、早期に(不完全でも)
    // 決着させてリトライループに委ねる。
    fmtCtx->probesize = 500000;
    fmtCtx->max_analyze_duration = 2 * AV_TIME_BASE;

    const AVInputFormat *inputFormat = av_find_input_format("mpegts");
    int openRet = avformat_open_input(&fmtCtx, nullptr, inputFormat, nullptr);
    if (openRet < 0) {
        LOGE("avformat_open_input failed: %d, retrying", openRet);
        av_freep(&avioCtx->buffer);
        avio_context_free(&avioCtx);
        demuxer->avioCtx = nullptr;
        avformat_free_context(fmtCtx);
        pthread_mutex_lock(&demuxer->mutex);
        demuxer->ringReadPos = demuxer->ringWritePos = demuxer->ringAvailable = 0;
        pthread_mutex_unlock(&demuxer->mutex);
        usleep(200000);
        goto retry_open;
    }
    demuxer->fmtCtx = fmtCtx;
    // ★avformat_find_stream_info()を別スレッド+タイムアウトで打ち切る方式は、
    // detachしたスレッドがタイムアウト後もfmtCtxへアクセスし続け、その後この関数側で
    // avformat_close_input()した場合にUse-After-Freeを起こす危険があるため撤回した
    // (実機で2回目のfind_stream_info呼び出しが異常に長時間応答不能になる現象を確認、
    // 前回のdetachスレッドがまだ古いfmtCtxを触り続けていたためと推測される)。
    // 代わりにprobesize/max_analyze_durationを極小値に制限し、同期呼び出しのまま
    // 早期に(不完全な情報でも)打ち切らせる、より安全な方式に戻す。
    fmtCtx->probesize = 65536;
    fmtCtx->max_analyze_duration = static_cast<int64_t>(0.3 * AV_TIME_BASE);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "before find_stream_info: probesize=%lld max_analyze_duration=%lld",
        (long long)fmtCtx->probesize, (long long)fmtCtx->max_analyze_duration);

    int findRet = avformat_find_stream_info(fmtCtx, nullptr);
    if (findRet < 0) {
        LOGE("avformat_find_stream_info failed: %d, retrying", findRet);
        avformat_close_input(&fmtCtx);
        av_freep(&avioCtx->buffer);
        avio_context_free(&avioCtx);
        demuxer->avioCtx = nullptr;
        demuxer->fmtCtx = nullptr;
        pthread_mutex_lock(&demuxer->mutex);
        demuxer->ringReadPos = demuxer->ringWritePos = demuxer->ringAvailable = 0;
        pthread_mutex_unlock(&demuxer->mutex);
        usleep(200000);
        goto retry_open;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "find_stream_info ok, nb_streams=%u", fmtCtx->nb_streams);

    demuxer->videoStreamIndex = -1;
    for (unsigned i = 0; i < fmtCtx->nb_streams; i++) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "stream[%u] codec_type=%d codec_id=%d",
            i, fmtCtx->streams[i]->codecpar->codec_type, fmtCtx->streams[i]->codecpar->codec_id);
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            demuxer->videoStreamIndex = static_cast<int>(i);
            break;
        }
    }
    if (demuxer->videoStreamIndex < 0) {
        LOGE("no video stream found, retrying");
        avformat_close_input(&fmtCtx);
        av_freep(&avioCtx->buffer);
        avio_context_free(&avioCtx);
        demuxer->avioCtx = nullptr;
        demuxer->fmtCtx = nullptr;
        // ★ここではリングバッファをクリアしない(エラー時の"find_stream_info failed"とは
        // 異なり、audio streamの検出自体には成功しているケース)。probesize=64KBの探索窓を
        // 毎回ゼロからやり直すと同じ範囲を見ることになりやすいため、蓄積済みの新しいデータ
        // をそのまま次のavformat_open_inputに引き継ぎ、video streamのキーフレームが
        // 含まれる範囲に当たるまでリトライを重ねられるようにする。
        usleep(200000);
        goto retry_open;
    }

    for (unsigned i = 0; i < fmtCtx->nb_streams; i++) {
        if (fmtCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            demuxer->audioStreamIndex = static_cast<int>(i);
            break;
        }
    }
    if (demuxer->audioStreamIndex >= 0) {
        const AVCodec *audioCodec = avcodec_find_decoder(fmtCtx->streams[demuxer->audioStreamIndex]->codecpar->codec_id);
        if (audioCodec) {
            demuxer->audioDecCtx = avcodec_alloc_context3(audioCodec);
            if (demuxer->audioDecCtx) {
                avcodec_parameters_to_context(demuxer->audioDecCtx, fmtCtx->streams[demuxer->audioStreamIndex]->codecpar);
                if (avcodec_open2(demuxer->audioDecCtx, audioCodec, nullptr) < 0) {
                    avcodec_free_context(&demuxer->audioDecCtx);
                }
            }
        }
    }

    const AVBitStreamFilter *bsfFilter = av_bsf_get_by_name("h264_mp4toannexb");
    if (bsfFilter) {
        if (av_bsf_alloc(bsfFilter, &demuxer->bsfCtx) == 0) {
            avcodec_parameters_copy(demuxer->bsfCtx->par_in, fmtCtx->streams[demuxer->videoStreamIndex]->codecpar);
            av_bsf_init(demuxer->bsfCtx);
        }
    }

    AVPacket *pkt = av_packet_alloc();
    AVPacket *filtered = av_packet_alloc();
    AVFrame *audioFrame = av_frame_alloc();

    while (!demuxer->shouldStop && av_read_frame(fmtCtx, pkt) >= 0) {
        if (pkt->stream_index == demuxer->videoStreamIndex) {
            AVRational tb = fmtCtx->streams[demuxer->videoStreamIndex]->time_base;
            int64_t ptsUsec = (pkt->pts != AV_NOPTS_VALUE)
                ? av_rescale_q(pkt->pts, tb, AVRational{1, 1000000})
                : 0;
            bool isKey = (pkt->flags & AV_PKT_FLAG_KEY) != 0;

            if (demuxer->bsfCtx && av_bsf_send_packet(demuxer->bsfCtx, pkt) == 0) {
                while (av_bsf_receive_packet(demuxer->bsfCtx, filtered) == 0) {
                    if (demuxer->callbackObj) {
                        jbyteArray array = env->NewByteArray(filtered->size);
                        env->SetByteArrayRegion(array, 0, filtered->size, reinterpret_cast<const jbyte *>(filtered->data));
                        env->CallVoidMethod(demuxer->callbackObj, demuxer->onFrameMethod, array,
                                             static_cast<jlong>(ptsUsec), static_cast<jboolean>(isKey));
                        env->DeleteLocalRef(array);
                    }
                    av_packet_unref(filtered);
                }
            } else if (demuxer->callbackObj) {
                jbyteArray array = env->NewByteArray(pkt->size);
                env->SetByteArrayRegion(array, 0, pkt->size, reinterpret_cast<const jbyte *>(pkt->data));
                env->CallVoidMethod(demuxer->callbackObj, demuxer->onFrameMethod, array,
                                     static_cast<jlong>(ptsUsec), static_cast<jboolean>(isKey));
                env->DeleteLocalRef(array);
            }
        } else if (pkt->stream_index == demuxer->audioStreamIndex && demuxer->audioDecCtx) {
            if (avcodec_send_packet(demuxer->audioDecCtx, pkt) == 0) {
                while (avcodec_receive_frame(demuxer->audioDecCtx, audioFrame) == 0) {
                    float level = computeAudioLevel(audioFrame);
                    if (!std::isnan(level) && demuxer->callbackObj && demuxer->onAudioLevelMethod) {
                        env->CallVoidMethod(demuxer->callbackObj, demuxer->onAudioLevelMethod,
                                             static_cast<jfloat>(level));
                    }
                    auto pcm = convertToS16Interleaved(audioFrame);
                    if (!pcm.empty() && demuxer->callbackObj && demuxer->onAudioPcmMethod) {
                        auto byteLen = static_cast<jsize>(pcm.size() * sizeof(int16_t));
                        jbyteArray array = env->NewByteArray(byteLen);
                        env->SetByteArrayRegion(array, 0, byteLen, reinterpret_cast<const jbyte *>(pcm.data()));
                        env->CallVoidMethod(demuxer->callbackObj, demuxer->onAudioPcmMethod, array,
                                             static_cast<jint>(demuxer->audioDecCtx->sample_rate),
                                             static_cast<jint>(audioFrame->ch_layout.nb_channels));
                        env->DeleteLocalRef(array);
                    }
                    av_frame_unref(audioFrame);
                }
            }
        }
        av_packet_unref(pkt);
    }

    av_packet_free(&pkt);
    av_packet_free(&filtered);
    av_frame_free(&audioFrame);
    if (demuxer->audioDecCtx) avcodec_free_context(&demuxer->audioDecCtx);
    demuxer->audioDecCtx = nullptr;

    if (!demuxer->shouldStop) {
        // ★av_read_frameが負値を返してこのループを抜けた場合(gr-dvbs2rx側の一時的な
        // BCH/LDPCエラー混入でffmpeg内部のパース状態が壊れた場合など)、そのままこの
        // スレッドを終了させるとTSパケット自体は届き続けているのに以後そのRXセッションは
        // 永久に映像が出なくなる(実機で確認済み: ロック継続・パケット数増加中でも
        // H.264フレーム抽出が完全に止まる)。find_stream_info失敗時と同様に
        // AVFormatContextを破棄し、リングバッファはクリアせずにretry_openへ戻って
        // ストリームを再検出する。
        LOGE("read loop exited unexpectedly (not shouldStop), retrying");
        if (demuxer->bsfCtx) av_bsf_free(&demuxer->bsfCtx);
        avformat_close_input(&fmtCtx);
        av_freep(&avioCtx->buffer);
        avio_context_free(&avioCtx);
        demuxer->avioCtx = nullptr;
        demuxer->fmtCtx = nullptr;
        demuxer->audioStreamIndex = -1;
        usleep(200000);
        goto retry_open;
    }

    if (attached) demuxer->jvm->DetachCurrentThread();
    return nullptr;
}

} // namespace

// MARK: - JNI: TsMuxerNative

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_ts_TsMuxerNative_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *muxer = new Muxer();
    muxer->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    muxer->onTsDataMethod = env->GetMethodID(cls, "onTsData", "([B)V");
    return reinterpret_cast<jlong>(muxer);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shinjo_shonanandroid_ts_TsMuxerNative_nativeStart(
        JNIEnv *env, jobject /* thiz */, jlong handle,
        jbyteArray jSps, jbyteArray jPps, jint width, jint height,
        jbyteArray jAacAsc, jint aacSampleRate, jint aacChannels) {
    auto *muxer = reinterpret_cast<Muxer *>(handle);
    if (!muxer) return env->NewStringUTF("無効なハンドルです");

    avformat_alloc_output_context2(&muxer->fmtCtx, nullptr, "mpegts", nullptr);
    if (!muxer->fmtCtx) {
        return env->NewStringUTF("avformat_alloc_output_context2に失敗しました");
    }

    const int avioBufferSize = 4096;
    auto *avioBuf = static_cast<uint8_t *>(av_malloc(avioBufferSize));
    muxer->avioCtx = avio_alloc_context(avioBuf, avioBufferSize, 1, muxer, nullptr, muxerWritePacket, nullptr);
    if (!muxer->avioCtx) {
        avformat_free_context(muxer->fmtCtx);
        muxer->fmtCtx = nullptr;
        return env->NewStringUTF("avio_alloc_contextに失敗しました");
    }
    muxer->fmtCtx->pb = muxer->avioCtx;
    muxer->fmtCtx->flags |= AVFMT_FLAG_CUSTOM_IO;

    muxer->videoStream = avformat_new_stream(muxer->fmtCtx, nullptr);
    if (!muxer->videoStream) {
        return env->NewStringUTF("avformat_new_streamに失敗しました");
    }
    muxer->videoStream->id = 0;
    // MPEG-TSの標準的な90kHzクロックをtime_baseとして使う(iOS版と同一)。
    muxer->videoStream->time_base = AVRational{1, 90000};

    AVCodecParameters *params = muxer->videoStream->codecpar;
    params->codec_type = AVMEDIA_TYPE_VIDEO;
    params->codec_id = AV_CODEC_ID_H264;
    params->width = width;
    params->height = height;

    // SPS/PPSをAnnex B(スタートコード付き)のままextradataへ格納する(iOS版と同一)。
    static const uint8_t startCode[4] = {0, 0, 0, 1};
    jsize spsLen = env->GetArrayLength(jSps);
    jsize ppsLen = env->GetArrayLength(jPps);
    int extradataLen = 4 + spsLen + 4 + ppsLen;
    auto *extradata = static_cast<uint8_t *>(av_mallocz(extradataLen + AV_INPUT_BUFFER_PADDING_SIZE));
    if (extradata) {
        int offset = 0;
        memcpy(extradata + offset, startCode, 4); offset += 4;
        env->GetByteArrayRegion(jSps, 0, spsLen, reinterpret_cast<jbyte *>(extradata + offset)); offset += spsLen;
        memcpy(extradata + offset, startCode, 4); offset += 4;
        env->GetByteArrayRegion(jPps, 0, ppsLen, reinterpret_cast<jbyte *>(extradata + offset)); offset += ppsLen;
        params->extradata = extradata;
        params->extradata_size = offset;
    }

    // 音声診断機能用: aac_ascが渡されていれば音声ストリームも登録する。
    // MPEG-TSは全ストリームをavformat_write_header呼び出し前に登録しておく必要があるため、
    // 映像ストリームと同様ここで追加する(iOS版ffmpeg_ts_bridge.cと同一方針)。
    if (jAacAsc != nullptr) {
        jsize aacAscLen = env->GetArrayLength(jAacAsc);
        if (aacAscLen > 0) {
            muxer->audioStream = avformat_new_stream(muxer->fmtCtx, nullptr);
            if (muxer->audioStream) {
                muxer->audioStream->id = 1;
                muxer->audioStream->time_base = AVRational{1, 90000};

                AVCodecParameters *aparams = muxer->audioStream->codecpar;
                aparams->codec_type = AVMEDIA_TYPE_AUDIO;
                aparams->codec_id = AV_CODEC_ID_AAC;
                aparams->sample_rate = aacSampleRate;
                av_channel_layout_default(&aparams->ch_layout, aacChannels);

                auto *ascCopy = static_cast<uint8_t *>(av_mallocz(aacAscLen + AV_INPUT_BUFFER_PADDING_SIZE));
                if (ascCopy) {
                    env->GetByteArrayRegion(jAacAsc, 0, aacAscLen, reinterpret_cast<jbyte *>(ascCopy));
                    aparams->extradata = ascCopy;
                    aparams->extradata_size = aacAscLen;
                }
            }
        }
    }

    muxer->callbackEnv = env;
    int ret = avformat_write_header(muxer->fmtCtx, nullptr);
    muxer->callbackEnv = nullptr;
    muxer->headerWritten = (ret >= 0);
    if (!muxer->headerWritten) {
        return env->NewStringUTF("avformat_write_headerに失敗しました");
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shinjo_shonanandroid_ts_TsMuxerNative_nativeWriteFrame(
        JNIEnv *env, jobject /* thiz */, jlong handle,
        jbyteArray jAnnexBFrame, jlong ptsUsec, jboolean isKeyFrame) {
    auto *muxer = reinterpret_cast<Muxer *>(handle);
    if (!muxer || !muxer->fmtCtx || !muxer->headerWritten) return -1;

    jsize len = env->GetArrayLength(jAnnexBFrame);
    auto *buf = static_cast<uint8_t *>(av_malloc(len + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!buf) return -1;
    env->GetByteArrayRegion(jAnnexBFrame, 0, len, reinterpret_cast<jbyte *>(buf));

    AVPacket *pkt = av_packet_alloc();
    if (!pkt) {
        av_free(buf);
        return -1;
    }
    if (av_packet_from_data(pkt, buf, len) < 0) {
        av_free(buf);
        av_packet_free(&pkt);
        return -1;
    }

    pkt->stream_index = muxer->videoStream->index;
    pkt->pts = pkt->dts = av_rescale_q(ptsUsec, AVRational{1, 1000000}, muxer->videoStream->time_base);
    if (isKeyFrame) {
        pkt->flags |= AV_PKT_FLAG_KEY;
    }

    pthread_mutex_lock(&muxer->writeMutex);
    muxer->callbackEnv = env;
    int ret = av_interleaved_write_frame(muxer->fmtCtx, pkt);
    muxer->callbackEnv = nullptr;
    pthread_mutex_unlock(&muxer->writeMutex);
    av_packet_free(&pkt);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_shinjo_shonanandroid_ts_TsMuxerNative_nativeWriteAudioFrame(
        JNIEnv *env, jobject /* thiz */, jlong handle,
        jbyteArray jAacFrame, jlong ptsUsec) {
    auto *muxer = reinterpret_cast<Muxer *>(handle);
    if (!muxer || !muxer->fmtCtx || !muxer->headerWritten || !muxer->audioStream) return -1;

    jsize len = env->GetArrayLength(jAacFrame);
    auto *buf = static_cast<uint8_t *>(av_malloc(len + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!buf) return -1;
    env->GetByteArrayRegion(jAacFrame, 0, len, reinterpret_cast<jbyte *>(buf));

    AVPacket *pkt = av_packet_alloc();
    if (!pkt) {
        av_free(buf);
        return -1;
    }
    if (av_packet_from_data(pkt, buf, len) < 0) {
        av_free(buf);
        av_packet_free(&pkt);
        return -1;
    }

    pkt->stream_index = muxer->audioStream->index;
    pkt->pts = pkt->dts = av_rescale_q(ptsUsec, AVRational{1, 1000000}, muxer->audioStream->time_base);
    pkt->flags |= AV_PKT_FLAG_KEY; // AACフレームは常に独立して復号可能

    pthread_mutex_lock(&muxer->writeMutex);
    muxer->callbackEnv = env;
    int ret = av_interleaved_write_frame(muxer->fmtCtx, pkt);
    muxer->callbackEnv = nullptr;
    pthread_mutex_unlock(&muxer->writeMutex);
    av_packet_free(&pkt);
    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ts_TsMuxerNative_nativeDestroy(JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *muxer = reinterpret_cast<Muxer *>(handle);
    if (!muxer) return;
    muxer->callbackEnv = env;
    destroyMuxer(env, muxer);
}

// MARK: - JNI: TsDemuxerNative

extern "C" JNIEXPORT jlong JNICALL
Java_com_shinjo_shonanandroid_ts_TsDemuxerNative_nativeCreate(JNIEnv *env, jobject thiz) {
    auto *demuxer = new Demuxer();
    env->GetJavaVM(&demuxer->jvm);
    demuxer->callbackObj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    demuxer->onFrameMethod = env->GetMethodID(cls, "onFrame", "([BJZ)V"); // (data, ptsUsec, isKeyFrame)
    demuxer->onAudioLevelMethod = env->GetMethodID(cls, "onAudioLevel", "(F)V");
    demuxer->onAudioPcmMethod = env->GetMethodID(cls, "onAudioPcm", "([BII)V");

    pthread_mutex_init(&demuxer->mutex, nullptr);
    pthread_cond_init(&demuxer->cond, nullptr);

    if (pthread_create(&demuxer->readThread, nullptr, demuxerReadThreadMain, demuxer) != 0) {
        pthread_mutex_destroy(&demuxer->mutex);
        pthread_cond_destroy(&demuxer->cond);
        env->DeleteGlobalRef(demuxer->callbackObj);
        delete demuxer;
        return 0;
    }
    demuxer->threadStarted = true;
    return reinterpret_cast<jlong>(demuxer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ts_TsDemuxerNative_nativeIngest(
        JNIEnv *env, jobject /* thiz */, jlong handle, jbyteArray jTsData) {
    auto *demuxer = reinterpret_cast<Demuxer *>(handle);
    if (!demuxer) return;

    jsize len = env->GetArrayLength(jTsData);
    jbyte *data = env->GetByteArrayElements(jTsData, nullptr);

    pthread_mutex_lock(&demuxer->mutex);
    int freeSpace = kRingCapacity - demuxer->ringAvailable;
    int toWrite = len < freeSpace ? len : freeSpace;
    for (int i = 0; i < toWrite; i++) {
        demuxer->ringBuffer[(demuxer->ringWritePos + i) % kRingCapacity] = static_cast<uint8_t>(data[i]);
    }
    demuxer->ringWritePos = (demuxer->ringWritePos + toWrite) % kRingCapacity;
    demuxer->ringAvailable += toWrite;
    pthread_cond_signal(&demuxer->cond);
    pthread_mutex_unlock(&demuxer->mutex);

    env->ReleaseByteArrayElements(jTsData, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_shinjo_shonanandroid_ts_TsDemuxerNative_nativeDestroy(JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *demuxer = reinterpret_cast<Demuxer *>(handle);
    if (!demuxer) return;

    pthread_mutex_lock(&demuxer->mutex);
    demuxer->shouldStop = true;
    pthread_cond_signal(&demuxer->cond);
    pthread_mutex_unlock(&demuxer->mutex);

    if (demuxer->threadStarted) {
        pthread_join(demuxer->readThread, nullptr);
    }

    if (demuxer->bsfCtx) {
        av_bsf_free(&demuxer->bsfCtx);
    }
    if (demuxer->fmtCtx) {
        avformat_close_input(&demuxer->fmtCtx);
    }
    if (demuxer->avioCtx) {
        av_freep(&demuxer->avioCtx->buffer);
        avio_context_free(&demuxer->avioCtx);
    }

    pthread_mutex_destroy(&demuxer->mutex);
    pthread_cond_destroy(&demuxer->cond);
    if (demuxer->callbackObj) {
        env->DeleteGlobalRef(demuxer->callbackObj);
    }
    delete demuxer;
}
