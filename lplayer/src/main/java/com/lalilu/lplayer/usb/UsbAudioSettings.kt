package com.lalilu.lplayer.usb

import com.lalilu.lplayer.MPlayerKV
import kotlin.math.pow

/**
 * USB-exclusive preferences. They are intentionally independent from the normal
 * AudioTrack output settings so a DAC can keep its own safe volume and buffer policy.
 */
object UsbAudioSettings {
    const val VOLUME_MODE_HARDWARE = 0
    const val VOLUME_MODE_DIGITAL = 1
    const val VOLUME_MODE_AUTO = 2

    const val BUFFER_MIN_MS = 100
    const val BUFFER_MAX_MS = 1500

    @Volatile
    var foreground: Boolean = true

    val sampleRate: Int get() = (MPlayerKV.usbOutputSampleRate.value ?: 0).coerceIn(0, 768_000)
    val bitDepth: Int get() = (MPlayerKV.usbOutputBitDepth.value ?: 0).let {
        if (it == 16 || it == 24) it else 0
    }
    val volumePercent: Int get() = (MPlayerKV.usbVolumePercent.value ?: DEFAULT_VOLUME).coerceIn(0, 100)
    val volumeMode: Int get() = (MPlayerKV.usbVolumeMode.value ?: VOLUME_MODE_AUTO)
        .coerceIn(VOLUME_MODE_HARDWARE, VOLUME_MODE_AUTO)
    val smoothHandoff: Boolean get() = MPlayerKV.usbSmoothHandoff.value != false
    val marginDb: Int get() = (MPlayerKV.usbMarginDb.value ?: 0).coerceIn(-6, 0)
    val tpdf: Boolean get() = MPlayerKV.usbTpdf.value == true
    val frontBufferMs: Int get() = (MPlayerKV.usbFrontBufferMs.value ?: 300)
        .coerceIn(BUFFER_MIN_MS, BUFFER_MAX_MS)
    val backBufferMs: Int get() = (MPlayerKV.usbBackBufferMs.value ?: 600)
        .coerceIn(BUFFER_MIN_MS, BUFFER_MAX_MS)
    val bitDepthCompatibility: Boolean get() = MPlayerKV.usbBitDepthCompat.value != false
    val channelCompatibility: Boolean get() = MPlayerKV.usbChannelCompat.value != false
    val delayedOpen: Boolean get() = MPlayerKV.usbDelayedOpen.value == true
    val backgroundPermission: Boolean get() = MPlayerKV.usbBackgroundPermission.value == true

    val bufferMs: Int get() = maxOf(frontBufferMs, backBufferMs)
    val targetBufferMs: Int get() = if (foreground) frontBufferMs else backBufferMs

    /** Digital fallback amplitude. Quadratic taper keeps low settings quiet on sensitive IEMs. */
    fun digitalGain(percent: Int = volumePercent): Float =
        (percent.coerceIn(0, 100) / 100f).pow(2f)

    fun formatRate(rate: Int): String = when {
        rate <= 0 -> "自适应"
        rate % 1000 == 0 -> "${rate / 1000} kHz"
        else -> String.format("%.1f kHz", rate / 1000f)
    }

    fun formatDepth(bits: Int): String = if (bits <= 0) "自适应" else "$bits-bit"

    fun formatVolume(percent: Int, hardwareDb: Float?): String =
        if (hardwareDb != null) {
            val gain = digitalGain(percent).coerceAtLeast(0.0001f)
            "$percent%  ${String.format("%.1f dB", 20 * kotlin.math.log10(gain))}"
        } else {
            "$percent%  ${String.format("%.1f dB", 20 * kotlin.math.log10(digitalGain(percent).coerceAtLeast(0.0001f)))}"
        }

    const val DEFAULT_VOLUME = 12
}
