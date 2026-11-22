package com.lalilu.lplayer.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat as PlatformFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs

/** Packed PCM output with the standard Media3 sink available for stream and route failures. */
@UnstableApi
class Pcm24AudioSink(
    private val fallback: AudioSink,
    processors: Array<AudioProcessor>,
) : ForwardingAudioSink(fallback) {
    private data class Configuration(val format: Format, val bufferSize: Int, val channels: IntArray?)

    private val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))
    private var requested: Configuration? = null
    private var active: Configuration? = null
    private var useFallback = false
    private var packedFailed = false
    private var tunneling = false
    private var listener: AudioSink.Listener? = null
    private var attributes = AudioAttributes.DEFAULT
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffect = AuxEffectInfo(0, 0f)
    @Volatile private var preferredDevice: AudioDeviceInfo? = null
    @Volatile private var volume = 1f
    @Volatile private var playing = false
    @Volatile private var blockedSinceMs = 0L
    @Volatile private var track: AudioTrack? = null
    private var trackConfig: AudioSink.AudioTrackConfig? = null
    private var requestedParameters = PlaybackParameters.DEFAULT
    private var appliedParameters = PlaybackParameters.DEFAULT

    private var outputRate = 0
    private var outputChannels = 0
    private var inputFrameSize = 0
    private var mappedChannels = IntArray(0)
    private var trimStartFrames = 0
    private var tail = FloatArray(0)
    private var tailSamples = 0
    private var normalized = AudioProcessor.EMPTY_BUFFER
    private var packed = AudioProcessor.EMPTY_BUFFER
    private var pendingInput: ByteBuffer? = null
    private var draining = false
    private var ended = false
    private var mediaStartUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var inputFrames = 0L
    private var writtenBytes = 0L
    private var lastRawHead = 0L
    private var headWraps = 0L
    private var discontinuity = false
    private var underrunCount = 0
    private var lastWriteMs = 0L
    private var positionAdvancing = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        when {
            !packedFailed && !tunneling && canPack(format) -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            format.sampleMimeType == MimeTypes.AUDIO_RAW || tunneling -> fallback.getFormatSupport(format)
            else -> AudioSink.SINK_FORMAT_UNSUPPORTED
        }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        requested = Configuration(format, specifiedBufferSize, outputChannels?.clone())
    }

    private fun configurePending(): Boolean {
        val config = requested ?: return true
        val nextUsesFallback = packedFailed || tunneling || !canPack(config.format)
        if (active != null) {
            if (!useFallback) {
                if (!drainPacked() || hasPendingData()) return false
                releaseTrack()
            } else if (!nextUsesFallback) {
                fallback.playToEndOfStream()
                if (fallback.hasPendingData()) return false
            }
        }
        requested = null
        active = config
        useFallback = nextUsesFallback
        if (useFallback) {
            fallback.configure(config.format, config.bufferSize, config.channels)
            return true
        }
        // The fallback can still own a track after an unsupported stream.
        fallback.flush()
        clearStream()
        val format = config.format
        mappedChannels = config.channels ?: IntArray(format.channelCount) { it }
        inputFrameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
        trimStartFrames = format.encoderDelay
        tail = FloatArray(format.encoderPadding * mappedChannels.size)
        try {
            val output = pipeline.configure(
                AudioProcessor.AudioFormat(format.sampleRate, mappedChannels.size, C.ENCODING_PCM_FLOAT)
            )
            pipeline.flush()
            outputRate = output.sampleRate
            outputChannels = output.channelCount
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            fallBack(e)
        }
        return true
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        try {
            if (!configurePending()) return false
            if (!useFallback) {
                if (!buffer.hasRemaining()) return true
                if (track == null) initializeTrack()
                if (pendingInput == null) {
                    val format = checkNotNull(active).format
                    updateMediaTime(presentationTimeUs, format.sampleRate)
                    inputFrames += buffer.remaining() / inputFrameSize
                    // Keep the original decoder buffer available until its packed output is written.
                    normalize(buffer.duplicate(), format)
                    pendingInput = buffer
                }
                if (!writeProcessed(normalized)) return false
                buffer.position(buffer.limit())
                pendingInput = null
                return true
            }
        } catch (e: TrackFailure) {
            fallBack(e)
            if (!configurePending()) return false
        }
        return fallback.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun updateMediaTime(timeUs: Long, sampleRate: Int) {
        if (mediaStartUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            mediaStartUs = timeUs.coerceAtLeast(0)
        } else {
            val expectedUs = mediaStartUs + framesToUs(inputFrames, sampleRate)
            if (discontinuity || abs(timeUs - expectedUs) > 200_000L) {
                mediaStartUs += timeUs - expectedUs
                listener?.onPositionDiscontinuity()
            }
        }
        discontinuity = false
    }

    /** Channel selection and gapless trimming happen before the sample rate changes. */
    private fun normalize(input: ByteBuffer, format: Format) {
        val frames = input.remaining() / inputFrameSize
        val skip = minOf(frames, trimStartFrames)
        trimStartFrames -= skip
        input.position(input.position() + skip * inputFrameSize)
        input.order(ByteOrder.LITTLE_ENDIAN)
        val sampleBytes = inputFrameSize / format.channelCount
        val samples = (frames - skip) * mappedChannels.size
        val outputSamples = (tailSamples + samples - tail.size).coerceAtLeast(0)
        normalized = prepare(normalized, outputSamples * Float.SIZE_BYTES)
        val fromTail = minOf(tailSamples, outputSamples)
        for (index in 0 until fromTail) normalized.putFloat(tail[index])
        tail.copyInto(tail, 0, fromTail, tailSamples)
        tailSamples -= fromTail
        while (input.remaining() >= inputFrameSize) {
            for (channel in mappedChannels) {
                val offset = input.position() + channel * sampleBytes
                val sample = when (format.pcmEncoding) {
                    C.ENCODING_PCM_FLOAT -> input.getFloat(offset)
                    C.ENCODING_PCM_16BIT -> input.getShort(offset) / 32768f
                    C.ENCODING_PCM_24BIT -> (
                        (input.get(offset).toInt() and 0xff) or
                            ((input.get(offset + 1).toInt() and 0xff) shl 8) or
                            (input.get(offset + 2).toInt() shl 16)
                        ) / 8388608f
                    else -> input.getInt(offset) / 2147483648f
                }
                if (normalized.hasRemaining()) normalized.putFloat(sample)
                else tail[tailSamples++] = sample
            }
            input.position(input.position() + inputFrameSize)
        }
        normalized.flip()
    }

    private fun writeProcessed(input: ByteBuffer): Boolean {
        if (!writePacked()) return false
        if (!pipeline.isOperational) return packAndWrite(input)
        while (true) {
            val output = pipeline.output
            if (output.hasRemaining()) {
                if (!packAndWrite(output)) return false
            } else if (input.hasRemaining()) {
                val position = input.position()
                pipeline.queueInput(input)
                if (input.position() == position && !pipeline.output.hasRemaining()) return false
            } else {
                return true
            }
        }
    }

    private fun packAndWrite(input: ByteBuffer): Boolean {
        packed = prepare(packed, input.remaining() / Float.SIZE_BYTES * 3)
        Pcm24Packer.pack(input, packed)
        packed.flip()
        return writePacked()
    }

    private fun writePacked(): Boolean {
        if (!packed.hasRemaining()) return true
        val audioTrack = track ?: throw TrackFailure("AudioTrack disappeared")
        val written = try {
            audioTrack.write(packed, packed.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: IllegalStateException) {
            throw TrackFailure("AudioTrack write failed", e)
        }
        if (written < 0) throw TrackFailure("AudioTrack write returned $written")
        val now = SystemClock.elapsedRealtime()
        val underruns = audioTrack.underrunCount
        if (playing && underruns > underrunCount && lastWriteMs != 0L) {
            val config = checkNotNull(trackConfig)
            listener?.onUnderrun(config.bufferSize, framesToUs(
                (config.bufferSize / (outputChannels * 3)).toLong(), outputRate
            ) / 1000, now - lastWriteMs)
        }
        underrunCount = underruns
        if (written > 0) {
            writtenBytes += written
            lastWriteMs = now
            blockedSinceMs = 0
        } else if (playing) {
            val blockedSince = blockedSinceMs
            if (blockedSince == 0L) {
                blockedSinceMs = now
            } else if (now - blockedSince >= WRITE_STALL_TIMEOUT_MS) {
                throw TrackFailure("AudioTrack stopped accepting data")
            }
        }
        return !packed.hasRemaining()
    }

    private fun initializeTrack() {
        val channelMask = Util.getAudioTrackChannelConfig(outputChannels)
        val minSize = AudioTrack.getMinBufferSize(outputRate, channelMask, PACKED)
        if (minSize <= 0) throw TrackFailure("Unsupported output rate=$outputRate channels=$outputChannels")
        val bytesPerFrame = outputChannels * 3
        val requestedSize = checkNotNull(active).bufferSize
        val size = maxOf(minSize, if (requestedSize > 0) requestedSize else outputRate * bytesPerFrame / 4)
        val bufferSize = ((size + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
        val audioTrack = try {
            buildTrack(outputRate, channelMask, bufferSize, attributes, sessionId)
        } catch (e: RuntimeException) {
            throw TrackFailure("AudioTrack initialization failed at rate=$outputRate", e)
        }
        try {
            audioTrack.setPreferredDevice(preferredDevice)
            audioTrack.setVolume(volume)
            applyAuxEffect(audioTrack)
            applyParameters(audioTrack)
            if (playing) audioTrack.play()
        } catch (e: RuntimeException) {
            releaseExecutor.execute { runCatching { audioTrack.release() } }
            throw TrackFailure("AudioTrack setup failed", e)
        }
        track = audioTrack
        val config = AudioSink.AudioTrackConfig(PACKED, outputRate, channelMask, false, false, bufferSize)
        trackConfig = config
        Log.i(TAG, "initialized rate=$outputRate depth=24 channels=$outputChannels")
        listener?.onAudioTrackInitialized(config)
    }

    private fun drainPacked(): Boolean {
        if (track == null) return true
        if (!draining) {
            if (!writeProcessed(normalized)) return false
            if (pipeline.isOperational) pipeline.queueEndOfStream()
            draining = true
        }
        return writeProcessed(AudioProcessor.EMPTY_BUFFER) && (!pipeline.isOperational || pipeline.isEnded)
    }

    override fun playToEndOfStream() {
        if (useFallback) {
            fallback.playToEndOfStream()
            return
        }
        try {
            ended = drainPacked()
        } catch (e: TrackFailure) {
            fallBack(e)
            fallback.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean =
        if (useFallback) fallback.isEnded else track == null || ended && !hasPendingData()

    override fun hasPendingData(): Boolean =
        if (useFallback) fallback.hasPendingData()
        else packed.hasRemaining() || playbackHeadFrames() < writtenFrames()

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (useFallback) return fallback.getCurrentPositionUs(sourceEnded)
        if (track == null || mediaStartUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val frames = minOf(playbackHeadFrames(), writtenFrames())
        if (frames > 0 && !positionAdvancing) {
            positionAdvancing = true
            listener?.onPositionAdvancing(System.currentTimeMillis())
        }
        return mediaStartUs + framesToUs(frames, outputRate)
    }

    private fun writtenFrames(): Long = if (outputChannels == 0) 0 else writtenBytes / (outputChannels * 3)

    private fun playbackHeadFrames(): Long {
        val raw = (track?.playbackHeadPosition ?: return 0).toLong() and 0xffffffffL
        if (raw < lastRawHead && lastRawHead - raw > 0x80000000L) headWraps++
        lastRawHead = raw
        return (headWraps shl 32) + raw
    }

    private fun fallBack(error: Exception) {
        val config = checkNotNull(active)
        Log.w(TAG, "Packed PCM failed; falling back to 16-bit DefaultAudioSink", error)
        releaseTrack()
        pipeline.reset()
        clearStream()
        packedFailed = true
        useFallback = true
        fallback.flush()
        fallback.configure(config.format, config.bufferSize, config.channels)
        listener?.onPositionDiscontinuity()
    }

    override fun play() {
        blockedSinceMs = 0
        playing = true
        fallback.play()
        track?.let { runCatching { it.play() } }
    }

    override fun pause() {
        playing = false
        fallback.pause()
        track?.let { runCatching { it.pause() } }
    }

    override fun handleDiscontinuity() {
        discontinuity = true
        if (useFallback) fallback.handleDiscontinuity()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        fallback.setVolume(volume)
        track?.let { runCatching { it.setVolume(volume) } }
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        fallback.setPreferredDevice(audioDeviceInfo)
        track?.let { runCatching { it.setPreferredDevice(audioDeviceInfo) } }
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        if (attributes == audioAttributes) return
        attributes = audioAttributes
        fallback.setAudioAttributes(audioAttributes)
        if (!useFallback && track != null) flush()
    }

    override fun getAudioAttributes(): AudioAttributes = attributes

    override fun setAudioSessionId(audioSessionId: Int) {
        if (sessionId == audioSessionId) return
        sessionId = audioSessionId
        fallback.setAudioSessionId(audioSessionId)
        if (!useFallback && track != null) flush()
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        auxEffect = auxEffectInfo
        fallback.setAuxEffectInfo(auxEffectInfo)
        track?.let(::applyAuxEffect)
    }

    private fun applyAuxEffect(audioTrack: AudioTrack) {
        audioTrack.attachAuxEffect(auxEffect.effectId)
        if (auxEffect.effectId != 0) audioTrack.setAuxEffectSendLevel(auxEffect.sendLevel)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        requestedParameters = playbackParameters
        fallback.setPlaybackParameters(playbackParameters)
        track?.let(::applyParameters)
    }

    private fun applyParameters(audioTrack: AudioTrack) {
        appliedParameters = try {
            audioTrack.playbackParams = PlaybackParams().allowDefaults()
                .setSpeed(requestedParameters.speed).setPitch(requestedParameters.pitch)
            // Setting playback parameters can start an AudioTrack.
            if (!playing && audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.pause()
            val actual = audioTrack.playbackParams
            PlaybackParameters(actual.speed, actual.pitch)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioTrack rejected playback parameters", e)
            appliedParameters
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters = when {
        useFallback -> fallback.playbackParameters
        track == null -> requestedParameters
        else -> appliedParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (useFallback) fallback.setSkipSilenceEnabled(skipSilenceEnabled)
        else listener?.onSkipSilenceEnabledChanged(false)
    }

    override fun getSkipSilenceEnabled(): Boolean = useFallback && fallback.skipSilenceEnabled

    override fun enableTunnelingV21() {
        if (tunneling) return
        flush()
        tunneling = true
        requested = requested ?: active
        fallback.enableTunnelingV21()
    }

    override fun disableTunneling() {
        if (!tunneling) return
        flush()
        tunneling = false
        requested = requested ?: active
        fallback.disableTunneling()
    }

    override fun flush() {
        releaseTrack()
        fallback.flush()
        clearStream()
        // The renderer need not call configure again after a seek.
        if (!useFallback && requested == null) {
            pipeline.flush()
            // Encoder delay only applies at the beginning of the stream.
            trimStartFrames = 0
        }
    }

    private fun clearStream() {
        normalized.position(normalized.limit())
        packed.position(packed.limit())
        pendingInput = null
        tailSamples = 0
        mediaStartUs = AudioSink.CURRENT_POSITION_NOT_SET
        inputFrames = 0
        writtenBytes = 0
        headWraps = 0
        lastRawHead = 0
        underrunCount = 0
        lastWriteMs = 0
        blockedSinceMs = 0
        positionAdvancing = false
        discontinuity = false
        draining = false
        ended = false
    }

    private fun releaseTrack() {
        val audioTrack = track ?: return
        track = null
        val config = trackConfig
        trackConfig = null
        runCatching { audioTrack.pause() }
        releaseExecutor.execute { runCatching { audioTrack.release() } }
        config?.let { listener?.onAudioTrackReleased(it) }
    }

    override fun reset() {
        flush()
        pipeline.reset()
        fallback.reset()
        requested = null
        active = null
        useFallback = false
        packedFailed = false
        playing = false
    }

    override fun release() {
        reset()
        fallback.release()
    }

    private class TrackFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "Pcm24AudioSink"
        private const val WRITE_STALL_TIMEOUT_MS = 2_000L
        private const val PACKED = PlatformFormat.ENCODING_PCM_24BIT_PACKED
        private val releaseExecutor = Executors.newSingleThreadExecutor()
        @Volatile private var probeResult: Boolean? = null

        @JvmStatic
        @Synchronized
        fun isSupported(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return false
            probeResult?.let { return it }
            val supported = try {
                val rate = 48_000
                val mask = PlatformFormat.CHANNEL_OUT_STEREO
                val size = AudioTrack.getMinBufferSize(rate, mask, PACKED)
                if (size <= 0 || context.getSystemService(Context.AUDIO_SERVICE) == null) {
                    false
                } else {
                    val track = buildTrack(rate, mask, ((size + 5) / 6) * 6, AudioAttributes.DEFAULT, 0)
                    releaseExecutor.execute { runCatching { track.release() } }
                    true
                }
            } catch (e: RuntimeException) {
                Log.i(TAG, "Packed PCM AudioTrack probe failed", e)
                false
            }
            probeResult = supported
            return supported
        }

        private fun canPack(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.AUDIO_RAW && when (format.pcmEncoding) {
                C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_32BIT -> true
                else -> false
            }

        private fun buildTrack(
            rate: Int, mask: Int, size: Int, attributes: AudioAttributes, sessionId: Int,
        ): AudioTrack {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes.audioAttributesV21.audioAttributes)
                .setAudioFormat(PlatformFormat.Builder().setEncoding(PACKED)
                    .setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(sessionId)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED || track.audioFormat != PACKED) {
                releaseExecutor.execute { runCatching { track.release() } }
                throw IllegalStateException("Packed AudioTrack is unavailable")
            }
            return track
        }

        private fun prepare(buffer: ByteBuffer, bytes: Int): ByteBuffer {
            val target = if (buffer.capacity() < bytes) {
                ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            } else buffer
            target.clear()
            target.limit(bytes)
            return target
        }

        private fun framesToUs(frames: Long, rate: Int): Long = frames * 1_000_000L / rate
    }
}
