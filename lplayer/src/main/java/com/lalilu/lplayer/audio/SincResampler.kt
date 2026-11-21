package com.lalilu.lplayer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler.
 *
 * A 32-tap Lanczos window is evaluated for every output frame. Weights are normalized so
 * unity gain is preserved independently of the input/output ratio. A cutoff slightly below
 * Nyquist suppresses aliasing when downsampling.
 */
internal class SincResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val channelCount: Int,
) {
    private val taps = 32
    private val half = taps / 2
    private val step = inputRate.toDouble() / outputRate.toDouble()
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate.toDouble()) * 0.96

    private var data = FloatArray(4096 * channelCount)
    private var frames = 0
    private var position = 0.0
    private var endFrame = 0
    private var finished = false

    init {
        reset()
    }

    fun reset() {
        // Prime the filter history with silence so the first output frame can evaluate
        // its full left window without negative indices.
        frames = half
        ensureCapacity(frames)
        java.util.Arrays.fill(data, 0, frames * channelCount, 0f)
        position = half.toDouble()
        endFrame = 0
        finished = false
    }

    fun append(source: FloatArray, frameCount: Int) {
        if (frameCount <= 0) return
        ensureCapacity(frames + frameCount)
        System.arraycopy(source, 0, data, frames * channelCount, frameCount * channelCount)
        frames += frameCount
    }

    fun finish() {
        if (finished) return
        endFrame = frames
        // Enough zero padding for the right half of the last output window.
        ensureCapacity(frames + half + 2)
        java.util.Arrays.fill(
            data,
            frames * channelCount,
            (frames + half + 2) * channelCount,
            0f
        )
        frames += half + 2
        finished = true
    }

    fun process(destination: FloatArray, maxFrames: Int): Int {
        var outFrames = 0
        val limitPosition = if (finished) endFrame.toDouble() else Double.MAX_VALUE

        while (outFrames < maxFrames && position < limitPosition) {
            val base = floor(position).toInt()
            if (base + half >= frames) break

            val fraction = position - base
            var weightSum = 0.0
            val weights = DoubleArray(taps)
            for (t in 0 until taps) {
                val sampleIndex = base - half + 1 + t
                val distance = sampleIndex - position
                val x = distance * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val windowX = distance / half
                val window = if (abs(windowX) < 1e-9) 1.0
                else sin(PI * windowX) / (PI * windowX)
                val weight = sinc * window
                weights[t] = weight
                weightSum += weight
            }
            if (abs(weightSum) < 1e-9) {
                position += step
                continue
            }
            val invSum = 1.0 / weightSum
            val outBase = outFrames * channelCount
            for (ch in 0 until channelCount) {
                var acc = 0.0
                for (t in 0 until taps) {
                    val sampleIndex = base - half + 1 + t
                    acc += data[sampleIndex * channelCount + ch] * weights[t]
                }
                destination[outBase + ch] = (acc * invSum).toFloat()
            }
            outFrames++
            position += step
        }

        compact()
        return outFrames
    }

    private fun compact() {
        if (finished) return
        val keepFrom = (floor(position).toInt() - half).coerceAtLeast(0)
        if (keepFrom <= 0) return
        val keepFrames = frames - keepFrom
        if (keepFrames > 0) {
            System.arraycopy(
                data,
                keepFrom * channelCount,
                data,
                0,
                keepFrames * channelCount
            )
        }
        frames = keepFrames
        position -= keepFrom
    }

    private fun ensureCapacity(requiredFrames: Int) {
        val required = requiredFrames * channelCount
        if (data.size >= required) return
        var newSize = data.size.coerceAtLeast(1)
        while (newSize < required) newSize *= 2
        data = data.copyOf(newSize)
    }

    fun bufferedFrames(): Int = frames

    fun outputFrameEstimate(maxInputFrames: Int): Int {
        return ceil((maxInputFrames + 8) * outputRate.toDouble() / inputRate).toInt() + 64
    }
}
