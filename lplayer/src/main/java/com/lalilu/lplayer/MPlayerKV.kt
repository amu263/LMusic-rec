package com.lalilu.lplayer

import com.lalilu.common.kv.BaseKV

object MPlayerKV : BaseKV(prefix = "mplayer") {
    val historyPlaylistIds = obtainList<String>("history_playlist_ids")
    val handleAudioFocus = obtain<Boolean>("handleAudioFocus")
    val handleBecomeNoisy = obtain<Boolean>("handleBecomeNoisy")
    val playMode = obtain<String>("play_mode")
    val decoderMode = obtain<Int>("decoder_mode")
    val outputSampleRate = obtain<Int>("output_sample_rate")
    val outputBitDepth = obtain<Int>("output_bit_depth")
    val dsdGainDb = obtain<Int>("dsd_gain_db")
    val eqEnabled = obtain<Boolean>("eq_enabled")
    val eqBands = obtain<String>("eq_bands")
    val usbExclusive = obtain<Boolean>("usb_exclusive")
    val usbBitPerfect = obtain<Boolean>("usb_bit_perfect")
    val usbDeviceId = obtain<String>("usb_device_id")
    val usbOutputSampleRate = obtain<Int>("usb_output_sample_rate")
    val usbOutputBitDepth = obtain<Int>("usb_output_bit_depth")
    val usbVolumePercent = obtain<Int>("usb_volume_percent")
    val usbVolumeMode = obtain<Int>("usb_volume_mode")
    val usbSmoothHandoff = obtain<Boolean>("usb_smooth_handoff")
    val usbMarginDb = obtain<Int>("usb_margin_db")
    val usbTpdf = obtain<Boolean>("usb_tpdf")
    val usbFrontBufferMs = obtain<Int>("usb_front_buffer_ms")
    val usbBackBufferMs = obtain<Int>("usb_back_buffer_ms")
    val usbBitDepthCompat = obtain<Boolean>("usb_bit_depth_compat")
    val usbChannelCompat = obtain<Boolean>("usb_channel_compat")
    val usbDelayedOpen = obtain<Boolean>("usb_delayed_open")
    val usbBackgroundPermission = obtain<Boolean>("usb_background_permission")
    val gaplessEnabled = obtain<Boolean>("gapless_enabled")
}