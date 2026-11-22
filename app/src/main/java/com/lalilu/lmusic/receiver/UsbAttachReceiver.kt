package com.lalilu.lmusic.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.lalilu.lplayer.usb.UsbAudioManager

/** Optional manifest entry point so a DAC can be authorized while LMusic is closed. */
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return
        if (!UsbAudioManager.hasAudioOutput(device)) return
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (manager.hasPermission(device)) return
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val result = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(UsbAudioManager.ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        runCatching { manager.requestPermission(device, result) }
    }
}
