package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when an FFmpeg audio decoding error occurs. */
@UnstableApi
public final class FfmpegDecoderException extends DecoderException {

    public FfmpegDecoderException(String message) {
        super(message);
    }

    public FfmpegDecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
