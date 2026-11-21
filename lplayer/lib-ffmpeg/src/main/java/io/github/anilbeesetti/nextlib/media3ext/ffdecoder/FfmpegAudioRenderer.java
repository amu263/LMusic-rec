package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DecoderAudioRenderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import android.os.Handler;

/** Decodes and renders audio using FFmpeg as a software fallback / forced software engine. */
@UnstableApi
public final class FfmpegAudioRenderer extends DecoderAudioRenderer<FfmpegAudioDecoder> {

    private static final String TAG = "FfmpegAudioRenderer";
    private static final int NUM_BUFFERS = 16;
    private static final int DEFAULT_INPUT_BUFFER_SIZE = 256 * 1024;

    private final boolean preferFloatOutput;

    public FfmpegAudioRenderer() {
        this(null, null);
    }

    public FfmpegAudioRenderer(
            @Nullable Handler eventHandler,
            @Nullable AudioRendererEventListener eventListener) {
        this(eventHandler, eventListener, new DefaultAudioSink.Builder().build());
    }

    public FfmpegAudioRenderer(
            @Nullable Handler eventHandler,
            @Nullable AudioRendererEventListener eventListener,
            AudioSink audioSink) {
        this(eventHandler, eventListener, audioSink, true);
    }

    public FfmpegAudioRenderer(
            @Nullable Handler eventHandler,
            @Nullable AudioRendererEventListener eventListener,
            AudioSink audioSink,
            boolean preferFloatOutput) {
        super(eventHandler, eventListener, audioSink);
        this.preferFloatOutput = preferFloatOutput;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    protected @C.FormatSupport int supportsFormatInternal(Format format) {
        String mimeType = Assertions.checkNotNull(format.sampleMimeType);
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE;
        }
        if (!FfmpegLibrary.supportsFormat(mimeType)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE;
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM;
        }
        // The renderer's AudioSink is fed through LMusic's resampling/EQ processor chain, so
        // the source sample rate (e.g. 352.8 kHz DSD) does not need to be directly playable.
        return C.FORMAT_HANDLED;
    }

    @Override
    public int supportsMixedMimeTypeAdaptation() {
        return ADAPTIVE_NOT_SEAMLESS;
    }

    @Override
    protected FfmpegAudioDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
            throws FfmpegDecoderException {
        TraceUtil.beginSection("createFfmpegAudioDecoder");
        int initialInputBufferSize = format.maxInputSize != Format.NO_VALUE
                ? format.maxInputSize
                : DEFAULT_INPUT_BUFFER_SIZE;
        FfmpegAudioDecoder decoder = new FfmpegAudioDecoder(
                format,
                NUM_BUFFERS,
                NUM_BUFFERS,
                initialInputBufferSize,
                preferFloatOutput);
        TraceUtil.endSection();
        return decoder;
    }

    @Override
    protected Format getOutputFormat(FfmpegAudioDecoder decoder) {
        Assertions.checkNotNull(decoder);
        return new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(decoder.getChannelCount())
                .setSampleRate(decoder.getSampleRate())
                .setPcmEncoding(decoder.getEncoding())
                .build();
    }
}
