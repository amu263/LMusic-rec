package com.lalilu.lplayer.extensions

import android.content.Context
import androidx.annotation.OptIn
import androidx.dynamicanimation.animation.SpringForce
import androidx.dynamicanimation.animation.springAnimationOf
import androidx.dynamicanimation.animation.withSpringForceProperties
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.lalilu.lplayer.audio.AudioSettings
import com.lalilu.lplayer.audio.Pcm24AudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class FadeTransitionAudioSink(
    sink: AudioSink,
    val scope: CoroutineScope,
) : ForwardingAudioSink(sink) {
    private var volumeOverride = 0f
        set(value) {
            field = value
            super.setVolume((value / 100f).coerceIn(0f..1f))
        }

    private var onFinished: (() -> Unit)? = null
    private val animation = springAnimationOf(
        getter = { volumeOverride },
        setter = { volumeOverride = it },
    ).withSpringForceProperties {
        stiffness = SpringForce.STIFFNESS_LOW
        dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
    }.apply {
        setStartValue(0f)
        setStartVelocity(0f)
        addEndListener { _, canceled, _, _ ->
            if (!canceled) onFinished?.invoke()
        }
    }

    override fun setVolume(volume: Float) {
        volumeOverride = volume
    }

    override fun play() {
        scope.launch(Dispatchers.Main) { animation.animateToFinalPosition(100f) }
        onFinished = null
        super.play()
    }

    override fun pause() {
        scope.launch(Dispatchers.Main) { animation.animateToFinalPosition(0f) }
        onFinished = { super.pause() }
    }
}

@OptIn(UnstableApi::class)
open class FadeTransitionRenderersFactory(
    context: Context,
    val scope: CoroutineScope,
    teeBufferListener: TeeAudioProcessor.AudioBufferSink? = null,
    private val extraAudioProcessors: Array<AudioProcessor> = emptyArray(),
    private val outputBitDepth: Int = AudioSettings.BIT_DEPTH_16,
) : DefaultRenderersFactory(context), AudioProcessorChain {

    protected val preferFloatOutput = outputBitDepth != AudioSettings.BIT_DEPTH_16

    private val teeAudioProcessor = teeBufferListener
        ?.let { TeeAudioProcessor(it) }

    @Volatile
    var lastAudioSink: AudioSink? = null
        private set

    @Volatile
    private var preferredDevice: android.media.AudioDeviceInfo? = null

    fun setPreferredDevice(device: android.media.AudioDeviceInfo?) {
        preferredDevice = device
        lastAudioSink?.setPreferredDevice(device)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val usePackedOutput = outputBitDepth == AudioSettings.BIT_DEPTH_24 && Pcm24AudioSink.isSupported(context)
        val defaultAudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(!usePackedOutput && (enableFloatOutput || preferFloatOutput))
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(this)
            .build()

        val sink = if (usePackedOutput) {
            Pcm24AudioSink(defaultAudioSink, getAudioProcessors())
        } else {
            defaultAudioSink
        }
        return FadeTransitionAudioSink(sink, scope).also {
            it.setPreferredDevice(preferredDevice)
            lastAudioSink = it
        }
    }

    override fun getAudioProcessors(): Array<AudioProcessor> {
        val processors = extraAudioProcessors.toMutableList()
        teeAudioProcessor?.let(processors::add)
        return processors.toTypedArray()
    }

    override fun getMediaDuration(playoutDuration: Long): Long = playoutDuration
    override fun getSkippedOutputFrameCount(): Long = 0
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = skipSilenceEnabled
    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        playbackParameters
}