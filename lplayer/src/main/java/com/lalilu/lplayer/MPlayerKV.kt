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
    val gaplessEnabled = obtain<Boolean>("gapless_enabled")
}