package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/** FFmpeg audio decoder supporting the extended LMusic format set. */
@SuppressLint("UnsafeOptInUsageError")
final class FfmpegAudioDecoder extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

    private static final int INITIAL_OUTPUT_BUFFER_SIZE_16BIT = 65535;
    private static final int INITIAL_OUTPUT_BUFFER_SIZE_32BIT = INITIAL_OUTPUT_BUFFER_SIZE_16BIT * 2;

    private static final int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int AUDIO_DECODER_ERROR_OTHER = -2;

    private final String codecName;
    @Nullable
    private final byte[] extraData;
    private final @C.PcmEncoding int encoding;
    private int outputBufferSize;

    private long nativeContext;
    private boolean hasOutputFormat;
    private volatile int channelCount;
    private volatile int sampleRate;

    FfmpegAudioDecoder(
            Format format,
            int numInputBuffers,
            int numOutputBuffers,
            int initialInputBufferSize,
            boolean outputFloat) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        String mimeType = checkNotNull(format.sampleMimeType);
        codecName = checkNotNull(FfmpegLibrary.getCodecName(mimeType));
        extraData = getExtraData(mimeType, format.initializationData);
        encoding = outputFloat ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
        outputBufferSize = outputFloat
                ? INITIAL_OUTPUT_BUFFER_SIZE_32BIT
                : INITIAL_OUTPUT_BUFFER_SIZE_16BIT;
        int bitsPerCodedSample = 0;
        int blockAlign = 0;
        int bitRate = 0;
        String codecs = format.codecs;
        if (codecs != null) {
            for (String part : codecs.split(";")) {
                try {
                    if (part.startsWith("lmusic-bits=")) {
                        bitsPerCodedSample = Integer.parseInt(part.substring("lmusic-bits=".length()));
                    } else if (part.startsWith("block_align=")) {
                        blockAlign = Integer.parseInt(part.substring("block_align=".length()));
                    } else if (part.startsWith("bit_rate=")) {
                        bitRate = Integer.parseInt(part.substring("bit_rate=".length()));
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed optional hints.
                }
            }
        }
        nativeContext = ffmpegInitialize(
                codecName,
                extraData,
                outputFloat,
                format.sampleRate,
                format.channelCount,
                bitsPerCodedSample,
                blockAlign,
                bitRate);
        if (nativeContext == 0) {
            throw new FfmpegDecoderException("Initialization failed for " + codecName + ".");
        }
        setInitialInputBufferSize(initialInputBufferSize);
    }

    @Override
    public String getName() {
        return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(
                DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
                FfmpegLibrary.getInputBufferPaddingSize());
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

    @Override
    @Nullable
    protected FfmpegDecoderException decode(
            DecoderInputBuffer inputBuffer,
            SimpleDecoderOutputBuffer outputBuffer,
            boolean reset) {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext, extraData);
            if (nativeContext == 0) {
                return new FfmpegDecoderException("Error resetting FFmpeg decoder.");
            }
        }
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputSize = inputData.limit();
        ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
        int result = ffmpegDecode(
                nativeContext,
                inputData,
                inputSize,
                outputBuffer,
                outputData,
                outputBufferSize);
        if (result == AUDIO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("Error decoding audio (see logcat).");
        } else if (result == AUDIO_DECODER_ERROR_INVALID_DATA) {
            // Match MediaCodec: a bad packet produces no output, but does not abort playback.
            outputBuffer.shouldBeSkipped = true;
            return null;
        } else if (result == 0) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        }
        if (!hasOutputFormat) {
            channelCount = ffmpegGetChannelCount(nativeContext);
            sampleRate = ffmpegGetSampleRate(nativeContext);
            if (sampleRate == 0 && "alac".equals(codecName)) {
                checkNotNull(extraData);
                ParsableByteArray parsableExtraData = new ParsableByteArray(extraData);
                parsableExtraData.setPosition(extraData.length - 4);
                sampleRate = parsableExtraData.readUnsignedIntToInt();
            }
            hasOutputFormat = true;
        }
        outputData = checkNotNull(outputBuffer.data);
        outputData.position(0);
        outputData.limit(result);
        return null;
    }

    // Called from native code when a decoded frame is larger than the initial buffer.
    @Keep
    @SuppressWarnings("unused")
    private ByteBuffer growOutputBuffer(SimpleDecoderOutputBuffer outputBuffer, int requiredSize) {
        outputBufferSize = requiredSize;
        return outputBuffer.grow(requiredSize);
    }

    @Override
    public void release() {
        super.release();
        if (nativeContext != 0) {
            ffmpegRelease(nativeContext);
            nativeContext = 0;
        }
    }

    int getChannelCount() {
        return channelCount;
    }

    int getSampleRate() {
        return sampleRate;
    }

    @C.PcmEncoding
    int getEncoding() {
        return encoding;
    }

    @Nullable
    private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
        if (initializationData.isEmpty()) {
            return null;
        }
        byte[] first = initializationData.get(0);
        if (first.length == 0) {
            return null;
        }
        switch (mimeType) {
            case MimeTypes.AUDIO_ALAC:
                return getAlacExtraData(first);
            case MimeTypes.AUDIO_AAC:
            case MimeTypes.AUDIO_OPUS:
            case MimeTypes.AUDIO_VORBIS:
            case FfmpegLibrary.AUDIO_APE:
            case FfmpegLibrary.AUDIO_WAVPACK:
            case FfmpegLibrary.AUDIO_WMA_V1:
            case FfmpegLibrary.AUDIO_WMA_V2:
            case FfmpegLibrary.AUDIO_WMA_PRO:
            case FfmpegLibrary.AUDIO_WMA_LOSSLESS:
            case FfmpegLibrary.AUDIO_TTA:
            case FfmpegLibrary.AUDIO_TAK:
                return first;
            default:
                // Most lossless codecs tolerate (and some require) the container extradata.
                return first;
        }
    }

    private static byte[] getAlacExtraData(byte[] magicCookie) {
        // FFmpeg's ALAC decoder expects a complete ALAC atom. The FFmpeg MP4 demuxer only
        // provides the magic cookie, while Media3 provides the same cookie in our extractor.
        if (magicCookie.length >= 12
                && magicCookie[4] == 0x61
                && magicCookie[5] == 0x6C
                && magicCookie[6] == 0x61
                && magicCookie[7] == 0x63) {
            return magicCookie;
        }
        int alacAtomLength = 12 + magicCookie.length;
        ByteBuffer alacAtom = ByteBuffer.allocate(alacAtomLength);
        alacAtom.putInt(alacAtomLength);
        alacAtom.putInt(0x616C6163); // 'alac'
        alacAtom.putInt(0); // version=0, flags=0
        alacAtom.put(magicCookie, 0, magicCookie.length);
        return alacAtom.array();
    }

    private native long ffmpegInitialize(
            String codecName,
            @Nullable byte[] extraData,
            boolean outputFloat,
            int rawSampleRate,
            int rawChannelCount,
            int bitsPerCodedSample,
            int blockAlign,
            int bitRate);

    private native int ffmpegDecode(
            long context,
            ByteBuffer inputData,
            int inputSize,
            SimpleDecoderOutputBuffer decoderOutputBuffer,
            ByteBuffer outputData,
            int outputSize);

    private native int ffmpegGetChannelCount(long context);

    private native int ffmpegGetSampleRate(long context);

    private native long ffmpegReset(long context, @Nullable byte[] extraData);

    private native void ffmpegRelease(long context);
}
