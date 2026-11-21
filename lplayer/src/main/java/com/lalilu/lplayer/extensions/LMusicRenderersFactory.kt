package com.lalilu.lplayer.extensions

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.lalilu.lplayer.audio.AudioSettings
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
    forceFloatOutput = AudioSettings.outputBitDepth == AudioSettings.BIT_DEPTH_FLOAT,
) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
    }

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
        val selector = if (softwareOnly) {
            MediaCodecSelector { _, _, _ -> emptyList() }
        } else {
            mediaCodecSelector
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
            AudioSettings.outputBitDepth == AudioSettings.BIT_DEPTH_FLOAT
        )
        if (softwareOnly || softwareFirst) {
            out.add(0, renderer)
        } else {
            out.add(renderer)
        }
    }
}
