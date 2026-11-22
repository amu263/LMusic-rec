package com.lalilu.lplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * Audio output preferences shared between the settings UI and the playback service.
 *
 * Values are stored in [com.lalilu.lplayer.MPlayerKV] so they survive process death. The
 * processor chain reads the current values whenever a new audio format is configured, so
 * changes apply at the next track (or after the service rebuilds the player).
 */
@UnstableApi
object AudioSettings {
    const val DECODER_AUTO = 0
    const val DECODER_HARDWARE_FIRST = 0
    const val DECODER_SOFTWARE_FIRST = 1
    const val DECODER_SOFTWARE_ONLY = 2

    const val SAMPLE_RATE_AUTO = 0

    const val BIT_DEPTH_16 = 16
    const val BIT_DEPTH_24 = 24
    const val BIT_DEPTH_FLOAT = 32

    @Volatile
    var decoderMode: Int = DECODER_AUTO

    @Volatile
    var outputSampleRate: Int = SAMPLE_RATE_AUTO

    @Volatile
    var outputBitDepth: Int = BIT_DEPTH_16

    fun resolveOutputBitDepth(context: Context, value: Int?): Int = when (value) {
        BIT_DEPTH_FLOAT -> BIT_DEPTH_FLOAT
        BIT_DEPTH_24 -> if (Pcm24AudioSink.isSupported(context)) BIT_DEPTH_24 else BIT_DEPTH_FLOAT
        else -> BIT_DEPTH_16
    }

    /** DSD / high-resolution pre-gain in dB, 0..12. */
    @Volatile
    var dsdGainDb: Int = 0

    @Volatile
    var eqEnabled: Boolean = false
        set(value) {
            field = value
            eqVersion++
        }

    /** Values in dB for 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k and 16k Hz. */
    @Volatile
    var eqBands: FloatArray = FloatArray(10)

    @Volatile
    var eqVersion: Int = 0

    @Volatile
    var usbExclusive: Boolean = false

    @Volatile
    var usbBitPerfect: Boolean = false

    @Volatile
    var gaplessEnabled: Boolean = true

    fun setEqBand(index: Int, gainDb: Float) {
        if (index !in eqBands.indices) return
        val copy = eqBands.copyOf()
        copy[index] = gainDb.coerceIn(-12f, 12f)
        eqBands = copy
        eqVersion++
    }

    fun updateEqBands(gains: FloatArray) {
        eqBands = FloatArray(10) { gains.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }
        eqVersion++
    }

    fun presetGains(preset: Int): FloatArray = when (preset) {
        0 -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // 平直
        1 -> floatArrayOf(4f, 3f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 2f) // 流行
        2 -> floatArrayOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 3f) // 摇滚
        3 -> floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f) // 爵士
        4 -> floatArrayOf(4f, 3f, 2f, 1f, 0f, 0f, -1f, -1f, -1f, -2f) // 古典
        5 -> floatArrayOf(6f, 5f, 3f, 1f, 0f, -1f, -1f, 0f, 0f, 0f) // 低音增强
        6 -> floatArrayOf(-2f, -1f, 0f, 1f, 2f, 3f, 3f, 2f, 1f, 0f) // 人声
        7 -> floatArrayOf(2f, 1f, 0f, 1f, 2f, 2f, 1f, 0f, -1f, -2f) // 轻音乐
        else -> FloatArray(10)
    }
}
