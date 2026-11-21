package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

/**
 * Configures and queries the bundled FFmpeg native library.
 *
 * <p>This is a local fork of the NextLib FFmpeg extension, extended with the extra audio codecs
 * used by LMusic (APE, WavPack, WMA, TTA, TAK, DSD and big-endian PCM).
 */
@UnstableApi
public final class FfmpegLibrary {

    private static final String TAG = "FfmpegLibrary";
    private static final String LIBRARY_NAME = "media3ext";

    private static volatile boolean available;
    private static volatile boolean loadAttempted;
    private static volatile String version;
    private static volatile int inputBufferPaddingSize = C.LENGTH_UNSET;

    /** Extended MIME types produced by {@link FfmpegProgressiveMediaExtractor}. */
    public static final String AUDIO_APE = "audio/x-ape";
    public static final String AUDIO_WAVPACK = "audio/x-wavpack";
    public static final String AUDIO_TTA = "audio/x-tta";
    public static final String AUDIO_TAK = "audio/x-tak";
    public static final String AUDIO_SHORTEN = "audio/x-shorten";
    public static final String AUDIO_WMA_V1 = "audio/x-wma-v1";
    public static final String AUDIO_WMA_V2 = "audio/x-wma-v2";
    public static final String AUDIO_WMA_PRO = "audio/x-wma-pro";
    public static final String AUDIO_WMA_LOSSLESS = "audio/x-wma-lossless";
    public static final String AUDIO_DSD_LSBF = "audio/x-dsd-lsbf";
    public static final String AUDIO_DSD_MSBF = "audio/x-dsd-msbf";
    public static final String AUDIO_DSD_LSBF_PLANAR = "audio/x-dsd-lsbf-planar";
    public static final String AUDIO_DSD_MSBF_PLANAR = "audio/x-dsd-msbf-planar";
    public static final String AUDIO_DST = "audio/x-dst";
    public static final String AUDIO_MP2 = "audio/x-mp2";
    public static final String AUDIO_PCM_S16BE = "audio/x-pcm-s16be";
    public static final String AUDIO_PCM_S24BE = "audio/x-pcm-s24be";
    public static final String AUDIO_PCM_S32BE = "audio/x-pcm-s32be";
    public static final String AUDIO_PCM_F32BE = "audio/x-pcm-f32be";
    public static final String AUDIO_PCM_S16LE_PLANAR = "audio/x-pcm-s16le-planar";
    public static final String AUDIO_PCM_S24LE_PLANAR = "audio/x-pcm-s24le-planar";
    public static final String AUDIO_PCM_S32LE_PLANAR = "audio/x-pcm-s32le-planar";
    public static final String AUDIO_PCM_F32LE_PLANAR = "audio/x-pcm-f32le-planar";
    public static final String AUDIO_PCM_F64LE = "audio/x-pcm-f64le";

    private FfmpegLibrary() {
    }

    /** Returns whether the underlying library is available, loading it if necessary. */
    public static boolean isAvailable() {
        if (!loadAttempted) {
            synchronized (FfmpegLibrary.class) {
                if (!loadAttempted) {
                    try {
                        System.loadLibrary(LIBRARY_NAME);
                        available = true;
                    } catch (UnsatisfiedLinkError e) {
                        Log.e(TAG, "Failed to load native library " + LIBRARY_NAME, e);
                        available = false;
                    }
                    loadAttempted = true;
                }
            }
        }
        return available;
    }

    /** Returns the FFmpeg version string, or {@code null} when unavailable. */
    @Nullable
    public static String getVersion() {
        if (!isAvailable()) {
            return null;
        }
        if (version == null) {
            synchronized (FfmpegLibrary.class) {
                if (version == null) {
                    version = ffmpegGetVersion();
                }
            }
        }
        return version;
    }

    /** Returns the required input buffer padding in bytes. */
    public static int getInputBufferPaddingSize() {
        if (!isAvailable()) {
            return C.LENGTH_UNSET;
        }
        if (inputBufferPaddingSize == C.LENGTH_UNSET) {
            inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
        }
        return inputBufferPaddingSize;
    }

    /** Returns whether the FFmpeg build contains a decoder for the given MIME type. */
    public static boolean supportsFormat(@Nullable String mimeType) {
        if (!isAvailable() || mimeType == null) {
            return false;
        }
        String codecName = getCodecName(mimeType);
        if (codecName == null) {
            return false;
        }
        if (!ffmpegHasDecoder(codecName)) {
            Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
            return false;
        }
        return true;
    }

    /** Maps a Media3 MIME type (including the LMusic extension types) to an FFmpeg decoder name. */
    @Nullable
    public static String getCodecName(String mimeType) {
        switch (mimeType) {
            // Codecs that also have a Media3 MIME constant.
            case MimeTypes.AUDIO_AAC:
                return "aac";
            case MimeTypes.AUDIO_MPEG:
            case MimeTypes.AUDIO_MPEG_L1:
            case MimeTypes.AUDIO_MPEG_L2:
                return "mp3";
            case MimeTypes.AUDIO_AC3:
                return "ac3";
            case MimeTypes.AUDIO_E_AC3:
            case MimeTypes.AUDIO_E_AC3_JOC:
                return "eac3";
            case MimeTypes.AUDIO_TRUEHD:
                return "truehd";
            case MimeTypes.AUDIO_DTS:
            case MimeTypes.AUDIO_DTS_HD:
            case MimeTypes.AUDIO_DTS_EXPRESS:
                return "dca";
            case MimeTypes.AUDIO_VORBIS:
                return "vorbis";
            case MimeTypes.AUDIO_OPUS:
                return "opus";
            case MimeTypes.AUDIO_AMR_NB:
                return "amrnb";
            case MimeTypes.AUDIO_AMR_WB:
                return "amrwb";
            case MimeTypes.AUDIO_FLAC:
                return "flac";
            case MimeTypes.AUDIO_ALAC:
                return "alac";
            case MimeTypes.AUDIO_MLAW:
                return "pcm_mulaw";
            case MimeTypes.AUDIO_ALAW:
                return "pcm_alaw";

            // LMusic extended audio formats.
            case AUDIO_APE:
                return "ape";
            case AUDIO_WAVPACK:
                return "wavpack";
            case AUDIO_TTA:
                return "tta";
            case AUDIO_TAK:
                return "tak";
            case AUDIO_SHORTEN:
                return "shorten";
            case AUDIO_WMA_V1:
                return "wmav1";
            case AUDIO_WMA_V2:
                return "wmav2";
            case AUDIO_WMA_PRO:
                return "wmapro";
            case AUDIO_WMA_LOSSLESS:
                return "wmalossless";
            case AUDIO_DSD_LSBF:
                return "dsd_lsbf";
            case AUDIO_DSD_MSBF:
                return "dsd_msbf";
            case AUDIO_DSD_LSBF_PLANAR:
                return "dsd_lsbf_planar";
            case AUDIO_DSD_MSBF_PLANAR:
                return "dsd_msbf_planar";
            case AUDIO_DST:
                return "dst";
            case AUDIO_MP2:
                return "mp2";
            case AUDIO_PCM_S16BE:
                return "pcm_s16be";
            case AUDIO_PCM_S24BE:
                return "pcm_s24be";
            case AUDIO_PCM_S32BE:
                return "pcm_s32be";
            case AUDIO_PCM_F32BE:
                return "pcm_f32be";
            case AUDIO_PCM_S16LE_PLANAR:
                return "pcm_s16le_planar";
            case AUDIO_PCM_S24LE_PLANAR:
                return "pcm_s24le_planar";
            case AUDIO_PCM_S32LE_PLANAR:
                return "pcm_s32le_planar";
            case AUDIO_PCM_F32LE_PLANAR:
                return "pcm_f32le_planar";
            case AUDIO_PCM_F64LE:
                return "pcm_f64le";
            default:
                return null;
        }
    }

    private static native String ffmpegGetVersion();

    private static native int ffmpegGetInputBufferPaddingSize();

    private static native boolean ffmpegHasDecoder(String codecName);
}
