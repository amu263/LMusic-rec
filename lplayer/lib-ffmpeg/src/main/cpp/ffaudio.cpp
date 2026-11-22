
#include <android/log.h>
#include <jni.h>
#include <cstdlib>
#include <android/native_window_jni.h>
#include <algorithm>
#include "ffcommon.h"

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <cstdint>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

# define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;

static jmethodID growOutputBufferMethod;

int transformError(int errorNumber);


/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
struct GrowOutputBufferCallback {
    uint8_t *operator()(int requiredSize) const;

    JNIEnv *env;
    jobject thiz;
    jobject decoderOutputBuffer;
};

uint8_t *GrowOutputBufferCallback::operator()(int requiredSize) const {
    jobject newOutputData = env->CallObjectMethod(thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
    if (env->ExceptionCheck()) {
        LOGE("growOutputBuffer() failed");
        env->ExceptionDescribe();
        return nullptr;
    }
    return static_cast<uint8_t *>(env->GetDirectBufferAddress(newOutputData));
}

AVCodecContext *createContext(JNIEnv *env, AVCodec *codec, jbyteArray extraData,
                              jboolean outputFloat, jint rawSampleRate,
                              jint rawChannelCount, jint bitsPerCodedSample,
                              jint blockAlign, jint bitRate, jint outputBits) {
    AVCodecContext *context = avcodec_alloc_context3(codec);
    if (!context) {
        LOGE("Failed to allocate context.");
        return nullptr;
    }
    context->request_sample_fmt =
            outputBits == 24 ? AV_SAMPLE_FMT_S32 :
            (outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT);
    if (extraData) {
        jsize size = env->GetArrayLength(extraData);
        context->extradata_size = size;
        context->extradata =
                (uint8_t *) av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!context->extradata) {
            LOGE("Failed to allocate extra data.");
            releaseContext(context);
            return nullptr;
        }
        env->GetByteArrayRegion(extraData, 0, size, (jbyte *) context->extradata);
    }
    if (rawSampleRate > 0) context->sample_rate = rawSampleRate;
    if (rawChannelCount > 0) av_channel_layout_default(&context->ch_layout, rawChannelCount);
    if (bitsPerCodedSample > 0) {
        context->bits_per_coded_sample = bitsPerCodedSample;
        context->bits_per_raw_sample = bitsPerCodedSample;
    }
    if (blockAlign > 0) context->block_align = blockAlign;
    if (bitRate > 0) context->bit_rate = bitRate;
    context->err_recognition = AV_EF_IGNORE_ERR;
    int result = avcodec_open2(context, codec, nullptr);
    if (result < 0) {
        logError("avcodec_open2", result);
        releaseContext(context);
        return nullptr;
    }
    return context;
}

int decodePacket(AVCodecContext *context, AVPacket *packet,
                 uint8_t *outputBuffer, int outputSize, GrowOutputBufferCallback growBuffer) {
    int result = avcodec_send_packet(context, packet);
    if (result) {
        logError("avcodec_send_packet", result);
        return transformError(result);
    }

    int outSize = 0;
    while (true) {
        AVFrame *frame = av_frame_alloc();
        if (!frame) {
            LOGE("Failed to allocate output frame.");
            return AUDIO_DECODER_ERROR_INVALID_DATA;
        }
        result = avcodec_receive_frame(context, frame);
        if (result) {
            av_frame_free(&frame);
            if (result == AVERROR(EAGAIN)) break;
            logError("avcodec_receive_frame", result);
            return transformError(result);
        }

        AVSampleFormat sampleFormat = context->sample_fmt;
        int channelCount = context->ch_layout.nb_channels;
        AVChannelLayout channelLayout = context->ch_layout;
        int sampleRate = context->sample_rate;
        int sampleCount = frame->nb_samples;
        if (channelCount <= 0 || sampleCount <= 0) {
            av_frame_free(&frame);
            continue;
        }

        auto *resampler = (AudioResampler *) context->opaque;
        if (!resampler) {
            resampler = (AudioResampler *) av_mallocz(sizeof(AudioResampler));
            if (!resampler) {
                av_frame_free(&frame);
                return AUDIO_DECODER_ERROR_OTHER;
            }
            resampler->swr = swr_alloc();
            resampler->outputBits = context->request_sample_fmt == AV_SAMPLE_FMT_S32 ? 24 : 0;
            context->opaque = resampler;
            av_opt_set_chlayout(resampler->swr, "in_chlayout", &channelLayout, 0);
            av_opt_set_chlayout(resampler->swr, "out_chlayout", &channelLayout, 0);
            av_opt_set_int(resampler->swr, "in_sample_rate", sampleRate, 0);
            av_opt_set_int(resampler->swr, "out_sample_rate", sampleRate, 0);
            av_opt_set_int(resampler->swr, "in_sample_fmt", sampleFormat, 0);
            av_opt_set_int(resampler->swr, "out_sample_fmt", context->request_sample_fmt, 0);
            result = swr_init(resampler->swr);
            if (result < 0) {
                logError("swr_init", result);
                av_frame_free(&frame);
                return transformError(result);
            }
        }

        int outSamples = swr_get_out_samples(resampler->swr, sampleCount);
        if (outSamples <= 0) {
            av_frame_free(&frame);
            continue;
        }
        bool packed24 = resampler->outputBits == 24;
        int outBytesPerSample = packed24 ? 3
                : av_get_bytes_per_sample(context->request_sample_fmt);
        int packedBytes = outBytesPerSample * channelCount * outSamples;

        if (outSize + packedBytes > outputSize) {
            LOGD("Output buffer size (%d) too small for output data (%d), reallocating buffer.",
                 outputSize, outSize + packedBytes);
            outputSize = outSize + packedBytes;
            outputBuffer = growBuffer(outputSize);
            if (!outputBuffer) {
                LOGE("Failed to reallocate output buffer.");
                av_frame_free(&frame);
                return AUDIO_DECODER_ERROR_OTHER;
            }
        }

        int converted;
        if (packed24) {
            // swresample writes 32-bit samples; pack the high 24 bits into little-endian
            // three-byte slots expected by ENCODING_PCM_24BIT_PACKED.
            int tempSize = 4 * channelCount * outSamples;
            auto *temp = (uint8_t *) av_malloc(tempSize);
            if (!temp) {
                av_frame_free(&frame);
                return AUDIO_DECODER_ERROR_OTHER;
            }
            uint8_t *tempPtr = temp;
            converted = swr_convert(resampler->swr, &tempPtr, outSamples,
                                    (const uint8_t **) frame->data, frame->nb_samples);
            if (converted < 0) {
                logError("swr_convert", converted);
                av_free(temp);
                av_frame_free(&frame);
                return transformError(converted);
            }
            uint8_t *dst = outputBuffer + outSize;
            auto *src = (const int32_t *) temp;
            for (int i = 0; i < converted * channelCount; i++) {
                int32_t value = src[i] >> 8;
                dst[i * 3] = (uint8_t) (value & 0xff);
                dst[i * 3 + 1] = (uint8_t) ((value >> 8) & 0xff);
                dst[i * 3 + 2] = (uint8_t) ((value >> 16) & 0xff);
            }
            av_free(temp);
        } else {
            uint8_t *dst = outputBuffer + outSize;
            converted = swr_convert(resampler->swr, &dst, outSamples,
                                    (const uint8_t **) frame->data, frame->nb_samples);
            if (converted < 0) {
                logError("swr_convert", converted);
                av_frame_free(&frame);
                return transformError(converted);
            }
        }
        av_frame_free(&frame);

        int available = swr_get_out_samples(resampler->swr, 0);
        if (available != 0) {
            LOGE("Expected no samples remaining after resampling, but found %d.", available);
            return AUDIO_DECODER_ERROR_INVALID_DATA;
        }
        outSize += outBytesPerSample * channelCount * converted;
    }
    return outSize;
}

int transformError(int errorNumber) {
    return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                              : AUDIO_DECODER_ERROR_OTHER;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegInitialize(JNIEnv *env,
                                                                        jobject thiz,
                                                                        jstring codec_name,
                                                                        jbyteArray extra_data,
                                                                        jboolean output_float,
                                                                        jint raw_sample_rate,
                                                                        jint raw_channel_count,
                                                                        jint bits_per_coded_sample,
                                                                        jint block_align,
                                                                        jint bit_rate,
                                                                        jint output_bits) {
    AVCodec *codec = getCodecByName(env, codec_name);
    if (!codec) {
        LOGE("Codec not found.");
        return 0L;
    }
    jclass clazz = env->FindClass("io/github/anilbeesetti/nextlib/media3ext/ffdecoder/FfmpegAudioDecoder");
    growOutputBufferMethod = env->GetMethodID(clazz, "growOutputBuffer","(Landroidx/media3/decoder/SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
    return (jlong) createContext(env, codec, extra_data, output_float, raw_sample_rate,
                                 raw_channel_count, bits_per_coded_sample, block_align, bit_rate,
                                 output_bits);
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegDecode(JNIEnv *env,
                                                                    jobject thiz,
                                                                    jlong context,
                                                                    jobject input_data,
                                                                    jint input_size,
                                                                    jobject decoderOutputBuffer,
                                                                    jobject output_data,
                                                                    jint output_size) {
    if (!context) {
        LOGE("Context must be non-NULL.");
        return -1;
    }
    if (!input_data || !decoderOutputBuffer || !output_data) {
        LOGE("Input and output buffers must be non-NULL.");
        return -1;
    }
    if (input_size < 0) {
        LOGE("Invalid input buffer size: %d.", input_size);
        return -1;
    }
    if (output_size < 0) {
        LOGE("Invalid output buffer length: %d", output_size);
        return -1;
    }
    auto *inputBuffer = (uint8_t *) env->GetDirectBufferAddress(input_data);
    auto *outputBuffer = (uint8_t *) env->GetDirectBufferAddress(output_data);
    AVPacket *packet;
    packet = av_packet_alloc();

    if (packet == nullptr) {
        LOGE("audio_decoder_decode_frame: av_packet_alloc failed");
        return -1;
    }

    packet->data = inputBuffer;
    packet->size = input_size;
    int decodedPacket = decodePacket((AVCodecContext *) context, packet, outputBuffer,
                                     output_size, GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
    av_packet_free(&packet);
    return decodedPacket;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegGetChannelCount(
        JNIEnv *env, jobject thiz, jlong context) {
    if (!context) {
        LOGE("Context must be non-NULL.");
        return -1;
    }
    return ((AVCodecContext *) context)->ch_layout.nb_channels;
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegGetSampleRate(JNIEnv *env,
                                                                           jobject thiz,
                                                                           jlong context) {
    if (!context) {
        LOGE("Context must be non-NULL.");
        return -1;
    }
    return ((AVCodecContext *) context)->sample_rate;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegReset(JNIEnv *env,
                                                                   jobject thiz,
                                                                   jlong jContext,
                                                                   jbyteArray extra_data) {
    auto *context = (AVCodecContext *) jContext;
    if (!context) {
        LOGE("Tried to reset without a context.");
        return 0L;
    }

    AVCodecID codecId = context->codec_id;
    if (codecId == AV_CODEC_ID_TRUEHD) {
        // Release and recreate the context if the codec is TrueHD.
        // TODO: Figure out why flushing doesn't work for this codec.
        auto outputFloat =
                (jboolean) (context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
        int outputBits = 0;
        if (auto *resampler = (AudioResampler *) context->opaque) {
            outputBits = resampler->outputBits;
        } else if (context->request_sample_fmt == AV_SAMPLE_FMT_S32) {
            outputBits = 24;
        }
        releaseContext(context);
        auto *codec = const_cast<AVCodec *>(avcodec_find_decoder(codecId));
        if (!codec) {
            LOGE("Unexpected error finding codec %d.", codecId);
            return 0L;
        }
        return (jlong) createContext(env, codec, extra_data, outputFloat,
                /* rawSampleRate= */ -1,
                /* rawChannelCount= */ -1,
                /* bitsPerCodedSample= */ context->bits_per_coded_sample,
                /* blockAlign= */ context->block_align,
                /* bitRate= */ context->bit_rate,
                /* outputBits= */ outputBits);
    }

    avcodec_flush_buffers(context);
    return (jlong) context;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_anilbeesetti_nextlib_media3ext_ffdecoder_FfmpegAudioDecoder_ffmpegRelease(JNIEnv *env,
                                                                     jobject thiz,
                                                                     jlong context) {
    if (context) {
        releaseContext((AVCodecContext *) context);
    }
}