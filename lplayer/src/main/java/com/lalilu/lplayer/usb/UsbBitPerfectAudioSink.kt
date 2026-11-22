package com.lalilu.lplayer.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.lalilu.usbuac.UsbAudioSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.roundToLong

object UsbOutputDiagnostics {
    private const val TAG = "UsbAudioOutput"
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()

    internal fun route(message: String) {
        Log.i(TAG, message)
        mutableStatus.value = message
    }

    internal fun update(status: UsbAudioSession.Status, quantized: Boolean) {
        val floatInfo = if (quantized) " · float → PCM32" else ""
        mutableStatus.value = "${status.configuration}$floatInfo\n" +
            "queued=${status.framesWritten} transferred=${status.framesTransferred} " +
            "underruns=${status.underrunPackets} errors=${status.transferErrors} " +
            "feedback=${status.feedbackPackets} libusb=${status.error} volume=${status.hardwareVolumeResult}"
        Log.d(TAG, mutableStatus.value)
    }
}

/** Uses unprocessed decoder PCM for UAC and delegates normal output to the existing sink. */
@UnstableApi
class UsbBitPerfectAudioSink(
    context: Context,
    fallback: AudioSink,
    private val requested: () -> Boolean,
    private val device: () -> UsbDevice?,
) : ForwardingAudioSink(fallback) {
    private data class Configuration(val format: Format, val bufferSize: Int, val channels: IntArray?) {
        val quantized = format.pcmEncoding == C.ENCODING_PCM_FLOAT
    }

    private data class Timestamp(val frame: Long, val timeUs: Long)

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var configuration: Configuration? = null
    private var pending: Configuration? = null
    private var session: UsbAudioSession? = null
    private var listener: AudioSink.Listener? = null
    private var configured = false
    private var platformConfigured = false
    private var playing = false
    private var ended = false
    private var volume = 1f
    private var parameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var lastStatusMs = 0L
    private var written = 0L
    private var lastPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var lastBuffer: ByteBuffer? = null
    private var lastBufferStart = 0
    private var fallbackBuffer: ByteBuffer? = null
    private var fallbackTimeUs = 0L
    private var discontinuity = false
    private var positionAdvancing = false
    private val timestamps = ArrayDeque<Timestamp>()
    private var conversion: ByteBuffer? = null

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if (requested() && device() != null) {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
            if (pcm(format) != null && format.pcmEncoding != C.ENCODING_PCM_FLOAT) {
                return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            }
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (requested() && device() != null) AudioOffloadSupport.DEFAULT_UNSUPPORTED
        else super.getFormatOffloadSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val next = Configuration(inputFormat, specifiedBufferSize, outputChannels?.clone())
        ended = false
        if (!requested() && session == null) {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            configuration = next
            pending = null
            platformConfigured = true
            configured = true
        } else {
            pending = next
        }
    }

    private fun pcm(format: Format): UsbAudioSession.Pcm? {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW ||
            format.sampleRate !in 8_000..768_000 || format.channelCount !in 1..32
        ) return null
        val bits = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> return null
        }
        return UsbAudioSession.Pcm(format.sampleRate, format.channelCount, bits)
    }

    private fun activate(config: Configuration) {
        configuration = config
        configured = true
        val format = pcm(config.format)
        val selected = device()
        if (requested()) {
            val problem = when {
                selected == null -> "No selected USB DAC connected"
                !manager.hasPermission(selected) -> "USB permission required"
                format == null -> "Unsupported decoder PCM format"
                config.channels?.withIndex()?.any { it.index != it.value } == true ||
                    (config.channels != null && config.channels.size != format.channels) -> "Channel mapping required"
                config.format.encoderDelay != 0 || config.format.encoderPadding != 0 -> "Encoder trimming required"
                else -> null
            }
            if (problem == null && selected != null && format != null) {
                var opened: UsbAudioSession? = null
                try {
                    opened = UsbAudioSession.open(manager, selected, format) {
                        UsbOutputDiagnostics.update(it, config.quantized)
                    }
                    super.flush()
                    platformConfigured = false
                    session = opened
                    UsbOutputDiagnostics.route(opened.configuration + if (config.quantized) " · float → PCM32" else "")
                    if (volume != 1f) opened.setVolume(volume)
                    if (playing) opened.start()
                    return
                } catch (failure: Exception) {
                    opened?.close()
                    session = null
                    UsbOutputDiagnostics.route("AudioTrack fallback · ${failure.message}")
                } catch (failure: LinkageError) {
                    opened?.close()
                    session = null
                    UsbOutputDiagnostics.route("AudioTrack fallback · ${failure.message}")
                }
            } else {
                UsbOutputDiagnostics.route("AudioTrack fallback · $problem")
            }
        }
        configureFallback(config)
    }

    private fun configureFallback(config: Configuration) {
        super.configure(config.format, config.bufferSize, config.channels)
        platformConfigured = true
        super.setPlaybackParameters(parameters)
        super.setSkipSilenceEnabled(skipSilence)
        if (playing) super.play()
    }

    private fun abandon(reason: IOException) {
        val config = checkNotNull(configuration)
        val counters = session?.status()?.let {
            " · transferred=${it.framesTransferred}/${it.framesWritten}, underruns=${it.underrunPackets}, " +
                "errors=${it.transferErrors}, feedback=${it.feedbackPackets}"
        }.orEmpty()
        closeUsb()
        super.setVolume(volume)
        UsbOutputDiagnostics.route("AudioTrack fallback · ${reason.message}$counters")
        listener?.onAudioSinkError(reason)
        configureFallback(config)
        listener?.onPositionDiscontinuity()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val next = pending ?: if (!configured) configuration else null
        if (next != null) {
            session?.let { previous ->
                previous.drain()
                val status = previous.status()
                if (status.error == 0 && status.framesWritten > status.framesTransferred) return false
                closeUsb()
            }
            pending = null
            activate(next)
        }
        val output = session ?: return handleFallbackBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (!buffer.hasRemaining()) return true
        if (lastBuffer !== buffer) {
            lastBuffer = buffer
            lastBufferStart = buffer.position()
        }
        val start = buffer.position()
        val frames = buffer.remaining() / output.format.frameBytes
        val config = checkNotNull(configuration)
        try {
            if (!requested() || device() == null) {
                throw IOException("USB output is no longer available")
            }
            if ((!config.quantized && !buffer.isDirect) ||
                frames == 0 || buffer.remaining() % output.format.frameBytes != 0) {
                throw IOException("Decoder returned a partial PCM frame")
            }
            val accepted = if (config.quantized) writeFloat(output, buffer, frames)
            else output.write(buffer, frames)
            if (accepted != 0) {
                val time = presentationTimeUs + durationUs(
                    (start - lastBufferStart).toLong() / output.format.frameBytes, output.format.sampleRate
                )
                recordTimestamp(time, output.format.sampleRate)
                written += accepted
            }
            return if (buffer.hasRemaining()) false else {
                lastBuffer = null
                true
            }
        } catch (failure: IOException) {
            val resumeFrames = (buffer.position() - lastBufferStart).toLong() / output.format.frameBytes
            abandon(failure)
            fallbackBuffer = buffer
            fallbackTimeUs = presentationTimeUs + durationUs(resumeFrames, output.format.sampleRate)
            return handleFallbackBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    private fun handleFallbackBuffer(buffer: ByteBuffer, timeUs: Long, accessUnits: Int): Boolean {
        val handled = super.handleBuffer(buffer, if (buffer === fallbackBuffer) fallbackTimeUs else timeUs, accessUnits)
        if (handled) fallbackBuffer = null
        return handled
    }

    private fun recordTimestamp(timeUs: Long, rate: Int) {
        val last = timestamps.peekLast()
        if (last == null || discontinuity ||
            kotlin.math.abs(last.timeUs + durationUs(written - last.frame, rate) - timeUs) > 100_000
        ) {
            timestamps.add(Timestamp(written, timeUs.coerceAtLeast(0)))
        }
        discontinuity = false
    }

    private fun writeFloat(output: UsbAudioSession, input: ByteBuffer, frames: Int): Int {
        val count = minOf(frames, 2048)
        val bytes = count * output.format.frameBytes
        val pcm = conversion?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(bytes)
            .order(ByteOrder.LITTLE_ENDIAN).also { conversion = it }
        pcm.clear()
        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        repeat(count * output.format.channels) {
            val sample = samples.float
            pcm.putInt(when {
                sample.isNaN() -> 0
                sample >= 1f -> Int.MAX_VALUE
                sample <= -1f -> Int.MIN_VALUE
                else -> (sample.toDouble() * 2147483648.0).roundToLong().toInt()
            })
        }
        pcm.flip()
        val accepted = output.write(pcm, count)
        input.position(input.position() + accepted * output.format.frameBytes)
        return accepted
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = session ?: return super.getCurrentPositionUs(sourceEnded)
        val now = SystemClock.elapsedRealtime()
        val status = if (now - lastStatusMs >= 1000) {
            lastStatusMs = now
            output.publishStatus()
        } else output.status()
        val frames = status.framesTransferred
        var anchor = timestamps.peekFirst() ?: return lastPositionUs
        while (timestamps.size > 1) {
            val next = timestamps.elementAt(1)
            if (frames < next.frame) break
            timestamps.remove()
            anchor = next
            listener?.onPositionDiscontinuity()
        }
        if (playing && !positionAdvancing && frames > 0) {
            positionAdvancing = true
            listener?.onPositionAdvancing(System.currentTimeMillis())
        }
        lastPositionUs = anchor.timeUs + durationUs((frames - anchor.frame).coerceAtLeast(0), output.format.sampleRate)
        return lastPositionUs
    }

    private fun durationUs(frames: Long, rate: Int) = frames * 1_000_000 / rate

    override fun play() {
        playing = true
        session?.start() ?: super.play()
    }

    override fun pause() {
        playing = false
        positionAdvancing = false
        session?.stop() ?: super.pause()
    }

    override fun handleDiscontinuity() {
        if (session != null) discontinuity = true else super.handleDiscontinuity()
    }

    override fun playToEndOfStream() {
        val output = session ?: return super.playToEndOfStream()
        output.drain()
        val status = output.status()
        if (status.error != 0) {
            abandon(IOException("USB stream failed while draining (libusb ${status.error})"))
            super.playToEndOfStream()
        } else {
            ended = status.framesWritten == status.framesTransferred
        }
    }

    override fun isEnded(): Boolean = if (session == null) super.isEnded() else ended && !hasPendingData()

    override fun hasPendingData(): Boolean = session?.status()?.let {
        it.error == 0 && it.framesWritten > it.framesTransferred
    } ?: super.hasPendingData()

    override fun setVolume(volume: Float) {
        this.volume = volume
        val output = session
        if (output == null) super.setVolume(volume) else output.setVolume(volume)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        parameters = playbackParameters
        if (session == null) super.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters() = if (session == null) super.getPlaybackParameters() else PlaybackParameters.DEFAULT

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        if (session == null) super.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled() = session == null && super.getSkipSilenceEnabled()

    override fun flush() {
        closeUsb()
        configured = platformConfigured && !requested()
        ended = false
        lastPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        super.flush()
    }

    override fun reset() {
        flush()
        pending = null
        configuration = null
        platformConfigured = false
        configured = false
        playing = false
        conversion = null
        super.reset()
    }

    override fun release() {
        closeUsb()
        super.release()
    }

    private fun closeUsb() {
        session?.close()
        session = null
        written = 0
        timestamps.clear()
        lastBuffer = null
        fallbackBuffer = null
        lastStatusMs = 0
        discontinuity = false
        positionAdvancing = false
    }
}
