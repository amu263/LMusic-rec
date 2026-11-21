package com.lalilu.lplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Pre-gain for DSD / very high sample-rate material. Salt Player exposes a 0..12 dB DSD
 * conversion gain because DSD-to-PCM conversion usually leaves a lot of headroom.
 */
@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {
    private var channels = 0
    private var encoding = C.ENCODING_PCM_16BIT
    private var highRate = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        highRate = inputAudioFormat.sampleRate > 192_000
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gainDb = if (highRate) AudioSettings.dsdGainDb else 0
        val remaining = inputBuffer.remaining()
        if (remaining <= 0 || gainDb == 0) {
            if (remaining <= 0) {
                inputBuffer.position(inputBuffer.limit())
                return
            }
            val target = replaceOutputBuffer(remaining)
            target.put(inputBuffer)
            target.flip()
            return
        }

        val multiplier = 10.0.pow(gainDb / 20.0).toFloat()
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val samples = remaining / bytesPerSample
        val target = replaceOutputBuffer(remaining)
        target.order(ByteOrder.nativeOrder())

        if (encoding == C.ENCODING_PCM_FLOAT) {
            val view = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val out = target.asFloatBuffer()
            for (i in 0 until samples) {
                out.put((view.get(i) * multiplier).coerceIn(-1f, 1f))
            }
            target.position(remaining)
        } else {
            val view = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val out = target.asShortBuffer()
            for (i in 0 until samples) {
                val value = (view.get(i) / 32768f * multiplier).coerceIn(-1f, 1f)
                out.put((value * 32767f).toInt().toShort())
            }
            target.position(remaining)
        }
        inputBuffer.position(inputBuffer.limit())
        target.flip()
    }
}
