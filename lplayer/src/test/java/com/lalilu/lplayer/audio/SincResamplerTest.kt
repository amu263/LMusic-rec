package com.lalilu.lplayer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SincResamplerTest {
    @Test
    fun upsamplesOneSecondToExpectedFrameCount() {
        val inRate = 44_100
        val outRate = 48_000
        val channels = 2
        val resampler = SincResampler(inRate, outRate, channels)

        val inputFrames = inRate
        val input = FloatArray(inputFrames * channels)
        for (frame in 0 until inputFrames) {
            val value = (sin(2.0 * PI * 440.0 * frame / inRate) * 0.5).toFloat()
            input[frame * 2] = value
            input[frame * 2 + 1] = value
        }

        var produced = 0
        var offset = 0
        val chunkFrames = 1024
        val temp = FloatArray((chunkFrames * 2.0 * outRate / inRate).toInt() + 256)
        while (offset < inputFrames) {
            val frames = minOf(chunkFrames, inputFrames - offset)
            val chunk = FloatArray(frames * channels)
            System.arraycopy(input, offset * channels, chunk, 0, chunk.size)
            resampler.append(chunk, frames)
            produced += resampler.process(temp, temp.size / channels)
            offset += frames
        }
        resampler.finish()
        produced += resampler.process(temp, temp.size / channels)

        // Allow a couple of frames of filter delay at the tail.
        assertTrue("produced=$produced", produced in (outRate - 64)..(outRate + 64))
    }

    @Test
    fun preservesUnitAmplitude() {
        val resampler = SincResampler(48_000, 44_100, 2)
        val frames = 4096
        val input = FloatArray(frames * 2) { if (it % 2 == 0) 0.25f else 0.25f }
        resampler.append(input, frames)
        resampler.finish()
        val out = FloatArray(8192 * 2)
        val produced = resampler.process(out, 8192)
        assertTrue(produced > 1000)
        // Ignore filter ringing at the artificial zero->DC step edges.
        for (i in 200 until (produced - 200) * 2) {
            assertEquals(0.25f, out[i], 0.02f)
        }
    }
}
