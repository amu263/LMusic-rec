package com.lalilu.lplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resamples the decoded PCM stream to the user-selected output rate.
 *
 * "Auto" keeps the source rate unless it exceeds 192 kHz (some DSD sources are 352.8 kHz),
 * in which case it downsamples to 192 kHz so Android's AudioTrack can accept it.
 */
@UnstableApi
class ResampleAudioProcessor(
    private val targetRateProvider: () -> Int,
) : BaseAudioProcessor() {

    private var resampler: SincResampler? = null
    private var targetRate = 0
    private var bytesPerSample = 2

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val channels = inputAudioFormat.channelCount
        val inputRate = inputAudioFormat.sampleRate
        if (channels <= 0 || inputRate <= 0) return AudioFormat.NOT_SET
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }

        val requested = targetRateProvider()
        val target = when {
            requested > 0 -> requested
            inputRate > 192_000 -> 192_000
            else -> inputRate
        }.coerceIn(8_000, 384_000)

        if (target == inputRate) {
            resampler = null
            targetRate = inputRate
            return AudioFormat.NOT_SET
        }

        targetRate = target
        bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        resampler = SincResampler(inputRate, target, channels)
        return AudioFormat(target, channels, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val resampler = resampler ?: run {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val channels = inputAudioFormat.channelCount
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val inputFrames = remainingBytes / (bytesPerSample * channels)
        if (inputFrames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val samples = FloatArray(inputFrames * channels)
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val floats = inputBuffer.asFloatBuffer()
            floats.get(samples, 0, samples.size)
        } else {
            val shorts = inputBuffer.asShortBuffer()
            for (i in samples.indices) {
                samples[i] = shorts.get(i) / 32768f
            }
        }
        inputBuffer.position(inputBuffer.limit())

        resampler.append(samples, inputFrames)
        emitOutput(resampler, channels)
    }

    override fun onQueueEndOfStream() {
        val resampler = resampler ?: return
        resampler.finish()
        emitOutput(resampler, inputAudioFormat.channelCount)
    }

    private fun emitOutput(resampler: SincResampler, channels: Int) {
        val maxFrames = resampler.outputFrameEstimate(resampler.bufferedFrames())
        val tmp = FloatArray(maxFrames * channels)
        val produced = resampler.process(tmp, maxFrames)
        if (produced <= 0) return

        val byteCount = produced * channels * bytesPerSample
        val buffer = replaceOutputBuffer(byteCount)
        buffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            floats.put(tmp, 0, produced * channels)
            buffer.position(byteCount)
        } else {
            val shorts = buffer.asShortBuffer()
            for (i in 0 until produced * channels) {
                shorts.put((tmp[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            buffer.position(byteCount)
        }
        buffer.flip()
    }

    override fun onFlush() {
        resampler?.reset()
    }

    override fun onReset() {
        resampler = null
        targetRate = 0
    }
}
