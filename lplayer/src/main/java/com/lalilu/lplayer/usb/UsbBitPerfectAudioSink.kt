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
import com.lalilu.lplayer.audio.SincResampler
import com.lalilu.usbuac.UsbAudioSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToLong

data class UsbRuntime(
    val deviceName: String = "未识别 USB 设备",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val serial: String = "",
    val connected: Boolean = false,
    val active: Boolean = false,
    val fallback: Boolean = false,
    val status: String = "等待连接",
    val sourceRate: Int = 0,
    val sourceBits: Int = 0,
    val streamRate: Int = 0,
    val streamBits: Int = 0,
    val endpoint: String = "待协商",
    val feedback: Boolean = false,
    val bufferMs: Int = 0,
    val isoTransfers: Int = 0,
    val underruns: Long = 0,
    val errors: Long = 0,
    val feedbackPackets: Long = 0,
    val volumePercent: Int = UsbAudioSettings.DEFAULT_VOLUME,
    val hardwareVolumeDb: Float? = null,
    val hardwareVolumeAvailable: Boolean = false,
    val volumeMode: Int = UsbAudioSettings.VOLUME_MODE_AUTO,
    val peakDb: Float? = null,
    val clippedSamples: Long = 0,
    val anomalySamples: Long = 0,
    val playing: Boolean = false,
)

object UsbOutputDiagnostics {
    private const val TAG = "UsbAudioOutput"
    private val mutableRuntime = MutableStateFlow(UsbRuntime())
    val runtime = mutableRuntime.asStateFlow()
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()

    internal fun update(transform: (UsbRuntime) -> UsbRuntime) {
        mutableRuntime.value = transform(mutableRuntime.value)
    }

    internal fun route(message: String) {
        Log.i(TAG, message)
        mutableStatus.value = message
        mutableRuntime.value = mutableRuntime.value.copy(
            status = message,
            fallback = message.contains("fallback", ignoreCase = true),
        )
    }

    internal fun update(status: UsbAudioSession.Status, sourceFormat: Format?, streamBits: Int, streamRate: Int) {
        val runtime = mutableRuntime.value
        val sourceRate = sourceFormat?.sampleRate ?: runtime.sourceRate
        val sourceBits = sourceFormat?.let { pcmBits(it.pcmEncoding) } ?: runtime.sourceBits
        val peak = status.peakSample
        val max = when (streamBits) {
            16 -> 32767.0
            24 -> 8388607.0
            32 -> 2147483647.0
            else -> 0.0
        }
        val peakDb = if (max > 0 && peak > 0) {
            (20.0 * kotlin.math.log10(peak / max)).toFloat().coerceIn(-120f, 0f)
        } else null
        val bufferMs = if (streamRate > 0) (status.queuedFrames * 1000 / streamRate).toInt() else 0
        val packetFrames = if (streamRate > 0) maxOf(1, streamRate / 1000) else 1
        update {
            it.copy(
                sourceRate = sourceRate,
                sourceBits = sourceBits,
                streamRate = streamRate,
                streamBits = streamBits,
                bufferMs = bufferMs,
                isoTransfers = ((status.framesWritten - status.framesTransferred) / packetFrames)
                    .coerceIn(0, 64).toInt(),
                underruns = status.underrunPackets,
                errors = status.transferErrors,
                feedbackPackets = status.feedbackPackets,
                hardwareVolumeAvailable = status.hardwareVolumeAvailable,
                hardwareVolumeDb = if (status.hardwareVolumeAvailable) status.hardwareVolumeDb else null,
                volumePercent = status.volumePercent,
                peakDb = peakDb,
                clippedSamples = status.clippedSamples,
                anomalySamples = status.anomalySamples,
                playing = runtime.playing,
            )
        }
        val text = "${status.configuration}\n" +
            "queued=${status.framesWritten} transferred=${status.framesTransferred} " +
            "underruns=${status.underrunPackets} errors=${status.transferErrors} " +
            "feedback=${status.feedbackPackets} libusb=${status.error}"
        mutableStatus.value = text
        Log.d(TAG, text)
    }

    internal fun markPlaying(playing: Boolean) = update { it.copy(playing = playing) }
    internal fun markFallback(reason: String) = update { it.copy(active = false, fallback = true, status = reason) }

    private fun pcmBits(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
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
    init { active = this }

    companion object {
        @Volatile
        var active: UsbBitPerfectAudioSink? = null
            private set
    }
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
    private var processed: ByteBuffer? = null
    private var resampler: SincResampler? = null
    private var resampledRate = 0
    private var digitalGainCurrent = 1f
    private var digitalGainTarget = 1f
    private var lastAppliedVolume = -1
    private val volumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var volumeJob: Job? = null
    private var delayedPending = false

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
        } else if (UsbAudioSettings.delayedOpen && !playing) {
            delayedPending = true
            pending = next
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
                    val targetRate = UsbAudioSettings.sampleRate.takeIf { it > 0 } ?: format.sampleRate
                    val sessionFormat = format.copy(sampleRate = targetRate)
                    opened = UsbAudioSession.open(
                        manager = manager,
                        device = selected,
                        format = sessionFormat,
                        bufferMs = UsbAudioSettings.bufferMs,
                        channelCompatibility = UsbAudioSettings.channelCompatibility,
                        bitDepthCompatibility = UsbAudioSettings.bitDepthCompatibility,
                    ) { status ->
                        UsbOutputDiagnostics.update(
                            status,
                            config.format,
                            format.bits,
                            resampledRate.takeIf { it > 0 } ?: format.sampleRate,
                        )
                    }
                    super.flush()
                    platformConfigured = false
                    session = opened
                    resampledRate = targetRate
                    resampler = if (targetRate != format.sampleRate) {
                        SincResampler(format.sampleRate, targetRate, format.channels)
                    } else null
                    val digitalMode = UsbAudioSettings.volumeMode == UsbAudioSettings.VOLUME_MODE_DIGITAL
                    opened.setVolumePercent(
                        if (digitalMode) 100 else UsbAudioSettings.volumePercent,
                        UsbAudioSettings.smoothHandoff,
                    )
                    val afterVolume = opened.status()
                    val hw = afterVolume.hardwareVolumeAvailable
                    lastAppliedVolume = UsbAudioSettings.volumePercent
                    val useDigital = !hw || digitalMode
                    digitalGainTarget = if (useDigital) UsbAudioSettings.digitalGain() else 1f
                    // Short software fade-in: even with hardware volume at its safe low
                    // starting point, this removes the first-packet click on many DACs.
                    digitalGainCurrent = 0f
                    UsbOutputDiagnostics.update {
                        it.copy(
                            deviceName = selected.productName ?: "USB DAC",
                            vendorId = selected.vendorId,
                            productId = selected.productId,
                            serial = selected.serialNumber.orEmpty(),
                            connected = true,
                            active = true,
                            fallback = false,
                            status = "已接管",
                            sourceRate = format.sampleRate,
                            sourceBits = format.bits,
                            streamRate = resampledRate.takeIf { it > 0 } ?: format.sampleRate,
                            streamBits = format.bits,
                            endpoint = run {
                                val cfg = opened.configuration
                                val alt = Regex("alt=(\\d+)").find(cfg)?.groupValues?.getOrNull(1)
                                val out = Regex("OUT=(0x[0-9a-fA-F]+)").find(cfg)?.groupValues?.getOrNull(1)
                                buildString {
                                    if (alt != null) append("alt=").append(alt).append(" · ")
                                    append("ISO OUT ").append(out ?: "0x1")
                                    if (cfg.contains("feedback=0x")) append(" · feedback")
                                }
                            },
                            feedback = opened.configuration.contains("feedback=0x"),
                            hardwareVolumeAvailable = hw,
                            hardwareVolumeDb = if (hw) afterVolume.hardwareVolumeDb else null,
                            volumePercent = UsbAudioSettings.volumePercent,
                            volumeMode = UsbAudioSettings.volumeMode,
                            peakDb = null,
                            clippedSamples = 0,
                            anomalySamples = 0,
                            playing = playing,
                        )
                    }
                    if (playing) opened.start()
                    return
                } catch (failure: Exception) {
                    opened?.close()
                    session = null
                    UsbOutputDiagnostics.route("AudioTrack fallback · ${failure.message}")
                UsbOutputDiagnostics.markFallback("AudioTrack fallback · ${failure.message}")
                } catch (failure: LinkageError) {
                    opened?.close()
                    session = null
                    UsbOutputDiagnostics.route("AudioTrack fallback · ${failure.message}")
                UsbOutputDiagnostics.markFallback("AudioTrack fallback · ${failure.message}")
                }
            } else {
                UsbOutputDiagnostics.route("AudioTrack fallback · $problem")
                UsbOutputDiagnostics.markFallback("AudioTrack fallback · $problem")
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
        if (delayedPending && !playing) return false
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
            else if (resampler != null) writeResampled(output, buffer, frames)
            else writePcm(output, buffer, frames)
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

    private fun writeResampled(
        output: UsbAudioSession,
        input: ByteBuffer,
        frames: Int,
    ): Int {
        val config = checkNotNull(configuration)
        val format = config.format
        val channels = format.channelCount
        val bits = output.format.bits
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames * channels)
        for (i in samples.indices) {
            samples[i] = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> src.short / 32768f
                C.ENCODING_PCM_24BIT -> {
                    val v = (src.get().toInt() and 0xff) or
                        ((src.get().toInt() and 0xff) shl 8) or
                        (src.get().toInt() shl 16)
                    v / 8388608f
                }
                C.ENCODING_PCM_32BIT -> src.int / 2147483648f
                else -> return output.write(input, frames)
            }
        }
        input.position(input.limit())
        val rs = checkNotNull(resampler)
        rs.append(samples, frames)
        var acceptedTotal = 0
        val maxFrames = 2048
        val tmp = FloatArray(maxFrames * channels)
        while (true) {
            val produced = rs.process(tmp, maxFrames)
            if (produced <= 0) break
            val bytes = produced * channels * (bits / 8)
            val pcm = processed?.takeIf { it.capacity() >= bytes }
                ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN).also { processed = it }
            pcm.clear()
            for (i in 0 until produced * channels) {
                val v = tmp[i].coerceIn(-1f, 1f)
                when (bits) {
                    16 -> pcm.putShort((v * 32767f).roundToLong().toInt().toShort())
                    24 -> {
                        val n = (v * 8388607f).roundToLong().toInt()
                        pcm.put((n and 0xff).toByte())
                        pcm.put(((n shr 8) and 0xff).toByte())
                        pcm.put(((n shr 16) and 0xff).toByte())
                    }
                    else -> pcm.putInt((v * 2147483647f).roundToLong().toInt())
                }
            }
            pcm.flip()
            val accepted = output.write(pcm, produced)
            acceptedTotal += accepted
            if (accepted < produced) break
        }
        return acceptedTotal
    }

    private fun needsProcessing(): Boolean =
        digitalGainTarget < 0.9999f || digitalGainCurrent < 0.9999f ||
            UsbAudioSettings.marginDb != 0 || UsbAudioSettings.tpdf

    private fun writePcm(output: UsbAudioSession, input: ByteBuffer, frames: Int): Int {
        if (!needsProcessing()) return output.write(input, frames)
        val config = checkNotNull(configuration)
        val bits = output.format.bits
        val channels = output.format.channels
        val frameBytes = output.format.frameBytes
        val count = minOf(frames, 4096)
        val bytes = count * frameBytes
        val pcm = processed?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .also { processed = it }
        pcm.clear()
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val margin = 10.0.pow(UsbAudioSettings.marginDb / 20.0).toFloat()
        val step = 0.004f
        var gain = digitalGainCurrent
        val target = digitalGainTarget
        val total = count * channels
        for (i in 0 until total) {
            if (gain < target) gain = minOf(target, gain + step)
            else if (gain > target) gain = maxOf(target, gain - step)
            val sample = when (bits) {
                16 -> src.short.toFloat() / 32768f
                24 -> {
                    val value = (src.get().toInt() and 0xff) or
                        ((src.get().toInt() and 0xff) shl 8) or
                        (src.get().toInt() shl 16)
                    value / 8388608f
                }
                else -> src.int.toFloat() / 2147483648f
            }
            var scaled = sample * gain * margin
            val dither = if (UsbAudioSettings.tpdf) {
                (Math.random() - Math.random()) / when (bits) {
                    16 -> 32768.0
                    24 -> 8388608.0
                    else -> 2147483648.0
                }
            } else 0.0
            scaled = (scaled + dither.toFloat()).coerceIn(-1f, 1f)
            when (bits) {
                16 -> pcm.putShort((scaled * 32767f).roundToLong().toInt().toShort())
                24 -> {
                    val value = (scaled * 8388607f).roundToLong().toInt()
                    pcm.put((value and 0xff).toByte())
                    pcm.put(((value shr 8) and 0xff).toByte())
                    pcm.put(((value shr 16) and 0xff).toByte())
                }
                else -> pcm.putInt((scaled * 2147483647f).roundToLong().toInt())
            }
        }
        digitalGainCurrent = gain
        pcm.flip()
        val accepted = output.write(pcm, count)
        input.position(input.position() + accepted * frameBytes)
        return accepted
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
        if (lastAppliedVolume != UsbAudioSettings.volumePercent) {
            lastAppliedVolume = UsbAudioSettings.volumePercent
            output.setVolumePercent(lastAppliedVolume, UsbAudioSettings.smoothHandoff)
            val status = output.status()
            digitalGainTarget = if (!status.hardwareVolumeAvailable ||
                UsbAudioSettings.volumeMode == UsbAudioSettings.VOLUME_MODE_DIGITAL
            ) UsbAudioSettings.digitalGain() else 1f
        }
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
        if (session == null && delayedPending && pending != null) {
            delayedPending = false
            val next = pending
            pending = null
            if (next != null) activate(next)
        }
        session?.start()
        if (session == null) super.play()
        UsbOutputDiagnostics.markPlaying(true)
    }

    override fun pause() {
        playing = false
        positionAdvancing = false
        // Keep the isochronous stream alive with digital silence. Stopping at every pause
        // produces an audible click/电流声 on many DACs.
        UsbOutputDiagnostics.markPlaying(false)
        if (session == null) super.pause()
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
        val output = session ?: return super.setVolume(volume)
        lastAppliedVolume = UsbAudioSettings.volumePercent
        output.setVolumePercent(lastAppliedVolume, UsbAudioSettings.smoothHandoff)
        val status = output.status()
        digitalGainTarget = if (!status.hardwareVolumeAvailable ||
            UsbAudioSettings.volumeMode == UsbAudioSettings.VOLUME_MODE_DIGITAL
        ) UsbAudioSettings.digitalGain() else 1f
        UsbOutputDiagnostics.update { it.copy(volumePercent = UsbAudioSettings.volumePercent) }
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
        volumeJob?.cancel()
        closeUsb()
        if (active === this) active = null
        super.release()
    }

    fun resetStats() {
        session?.resetStats()
        UsbOutputDiagnostics.update {
            it.copy(peakDb = null, clippedSamples = 0, anomalySamples = 0)
        }
    }

    fun rebuild() {
        val config = configuration ?: return
        closeUsb()
        pending = config
        configured = false
    }

    fun refreshSettings() {
        val output = session ?: return
        val target = UsbAudioSettings.volumePercent
        lastAppliedVolume = target
        val status = output.status()
        val digitalMode = UsbAudioSettings.volumeMode == UsbAudioSettings.VOLUME_MODE_DIGITAL
        val useDigital = !status.hardwareVolumeAvailable || digitalMode
        digitalGainTarget = if (useDigital) UsbAudioSettings.digitalGain(target) else 1f
        volumeJob?.cancel()
        if (digitalMode) {
            // Mute the software stage first, then open the DAC hardware stage; this avoids
            // a full-scale transient when switching from hardware to digital control.
            digitalGainCurrent = 0f
            volumeJob = volumeScope.launch {
                delay(80)
                runCatching { output.setVolumePercent(100, false) }
            }
            return
        }
        digitalGainCurrent = if (useDigital) 0f else 1f
        if (!UsbAudioSettings.smoothHandoff || status.hardwareVolumeDb == null ||
            !status.hardwareVolumeAvailable
        ) {
            output.setVolumePercent(target, false)
            return
        }
        volumeJob = volumeScope.launch {
            val from = status.volumePercent
            if (from == target) return@launch
            val step = if (target > from) 1 else -1
            var current = from
            while (current != target) {
                current += step
                runCatching { output.setVolumePercent(current, false) }
                delay(8)
            }
        }
    }

    private fun closeUsb() {
        session?.close()
        session = null
        resampler = null
        resampledRate = 0
        UsbOutputDiagnostics.update {
            it.copy(active = false, playing = false, bufferMs = 0, isoTransfers = 0)
        }
        written = 0
        timestamps.clear()
        lastBuffer = null
        fallbackBuffer = null
        lastStatusMs = 0
        discontinuity = false
        positionAdvancing = false
    }
}
