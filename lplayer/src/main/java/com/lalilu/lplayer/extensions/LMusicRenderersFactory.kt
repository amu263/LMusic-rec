package com.lalilu.lplayer.extensions

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lalilu.lplayer.audio.AudioSettings
import com.lalilu.lplayer.usb.UsbBitPerfectAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import kotlinx.coroutines.CoroutineScope

/**
 * Renderers factory that keeps Media3's normal hardware-first path but makes the bundled
 * FFmpeg audio renderer available for every format. In software-only mode the platform
 * MediaCodec selector is emptied so the FFmpeg renderer is the only audio decoder.
 */
@UnstableApi
class LMusicRenderersFactory(
    context: Context,
    scope: CoroutineScope,
    private val softwareOnly: Boolean,
    private val softwareFirst: Boolean,
    providers: Array<androidx.media3.common.audio.AudioProcessor>,
) : FadeTransitionRenderersFactory(
    context = context,
    scope = scope,
    extraAudioProcessors = providers,
    outputBitDepth = AudioSettings.outputBitDepth,
) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

    @Volatile
    var usbDevice: UsbDevice? = null

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = UsbBitPerfectAudioSink(
        context,
        super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams),
        requested = { AudioSettings.usbBitPerfect },
        device = { usbDevice },
    )

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val selector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            if (softwareOnly || AudioSettings.usbBitPerfect) {
                emptyList()
            } else {
                mediaCodecSelector.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
            }
        }

        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            selector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )

        if (!FfmpegLibrary.isAvailable()) return
        val renderer = FfmpegAudioRenderer(
            eventHandler,
            eventListener,
            audioSink,
            preferFloatOutput,
        ) {
            val bits = when {
                AudioSettings.outputBitDepth == AudioSettings.BIT_DEPTH_24 -> 24
                AudioSettings.outputBitDepth == AudioSettings.BIT_DEPTH_FLOAT -> 32
                else -> 16
            }
            bits
        }
        if (softwareOnly || softwareFirst) {
            out.add(0, renderer)
        } else {
            out.add(renderer)
        }
    }
}
