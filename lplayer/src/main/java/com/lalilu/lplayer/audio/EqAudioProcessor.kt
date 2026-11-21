package com.lalilu.lplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 10-band peaking equalizer implemented as a cascade of biquad filters.
 *
 * The processor is kept active for the whole session so enabling/disabling or moving a band
 * takes effect immediately without rebuilding the player.
 */
@UnstableApi
class EqAudioProcessor : BaseAudioProcessor() {

    private val frequencies = floatArrayOf(
        31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f
    )
    private val q = 1.1f

    private var channels = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_PCM_16BIT
    private var appliedVersion = -1
    private var bypass = true

    private var b0 = FloatArray(0)
    private var b1 = FloatArray(0)
    private var b2 = FloatArray(0)
    private var a1 = FloatArray(0)
    private var a2 = FloatArray(0)

    // [band][channel] direct form II transposed state.
    private var z1 = Array(0) { FloatArray(0) }
    private var z2 = Array(0) { FloatArray(0) }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        if (channels <= 0 || sampleRate <= 0) return AudioFormat.NOT_SET

        b0 = FloatArray(frequencies.size)
        b1 = FloatArray(frequencies.size)
        b2 = FloatArray(frequencies.size)
        a1 = FloatArray(frequencies.size)
        a2 = FloatArray(frequencies.size)
        z1 = Array(frequencies.size) { FloatArray(channels) }
        z2 = Array(frequencies.size) { FloatArray(channels) }
        appliedVersion = -1
        updateCoefficients()
        return inputAudioFormat
    }

    private fun updateCoefficients() {
        val version = AudioSettings.eqVersion
        if (version == appliedVersion) return
        appliedVersion = version
        val gains = AudioSettings.eqBands
        bypass = !AudioSettings.eqEnabled || gains.all { abs(it) < 0.01f }

        for (band in frequencies.indices) {
            val gain = if (AudioSettings.eqEnabled) gains.getOrElse(band) { 0f } else 0f
            val a = 10.0.pow(gain / 40.0)
            val w0 = 2.0 * PI * frequencies[band] / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[band] = ((1.0 + alpha * a) / a0).toFloat()
            b1[band] = (-2.0 * cosW0 / a0).toFloat()
            b2[band] = ((1.0 - alpha * a) / a0).toFloat()
            a1[band] = (-2.0 * cosW0 / a0).toFloat()
            a2[band] = ((1.0 - alpha / a) / a0).toFloat()
        }
        for (band in frequencies.indices) {
            java.util.Arrays.fill(z1[band], 0f)
            java.util.Arrays.fill(z2[band], 0f)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        updateCoefficients()
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameCount = remaining / (bytesPerSample * channels)
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val bytes = ByteArray(remaining)
        inputBuffer.get(bytes)
        val out = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())

        if (bypass) {
            out.put(bytes)
        } else {
            val floats = FloatArray(frameCount * channels)
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                view.get(floats)
            } else {
                val view = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
                for (i in floats.indices) floats[i] = view.get(i) / 32768f
            }

            for (frame in 0 until frameCount) {
                for (ch in 0 until channels) {
                    var sample = floats[frame * channels + ch]
                    for (band in frequencies.indices) {
                        val y = b0[band] * sample + z1[band][ch]
                        z1[band][ch] = b1[band] * sample - a1[band] * y + z2[band][ch]
                        z2[band][ch] = b2[band] * sample - a2[band] * y
                        sample = y
                    }
                    floats[frame * channels + ch] = sample.coerceIn(-1f, 1f)
                }
            }

            if (encoding == C.ENCODING_PCM_FLOAT) {
                val view = out.asFloatBuffer()
                view.put(floats)
                out.position(remaining)
            } else {
                val view = out.asShortBuffer()
                for (i in floats.indices) {
                    view.put((floats[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                out.position(remaining)
            }
        }

        out.flip()
        val target = replaceOutputBuffer(out.remaining())
        target.put(out)
        target.flip()
    }

    override fun onFlush() {
        for (band in z1.indices) {
            java.util.Arrays.fill(z1[band], 0f)
            java.util.Arrays.fill(z2[band], 0f)
        }
    }

    override fun onReset() {
        appliedVersion = -1
        b0 = FloatArray(0)
        b1 = FloatArray(0)
        b2 = FloatArray(0)
        a1 = FloatArray(0)
        a2 = FloatArray(0)
        z1 = Array(0) { FloatArray(0) }
        z2 = Array(0) { FloatArray(0) }
    }
}
