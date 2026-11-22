package com.lalilu.lplayer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.lalilu.usbuac.UsbAudioSession

/** USB discovery, permission and AudioTrack fallback routing. */
object UsbAudioManager {
    const val ACTION_USB_PERMISSION = "com.lalilu.lmusic.USB_PERMISSION"

    data class Device(
        val usbDevice: UsbDevice,
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val sampleRates: List<Int>,
        val bitDepths: List<Int>,
        val channels: Int,
    ) {
        val id: String get() = "${usbDevice.deviceId}:$vendorId:$productId:$name"
        val summary: String
            get() = buildString {
                append(name)
                if (sampleRates.isNotEmpty()) {
                    append(" · ")
                    append(sampleRates.joinToString("/") { "${it / 1000}kHz" })
                }
                if (bitDepths.isNotEmpty() && bitDepths.any { it > 16 }) {
                    append(" · ")
                    append(bitDepths.maxOrNull()).append("bit")
                }
                if (channels > 0) append(" · ").append(channels).append("ch")
            }
    }

    fun findDeviceById(context: Context, id: String?): Device? {
        if (id.isNullOrBlank()) return null
        return listDevices(context).firstOrNull { it.id == id }
    }

    fun selectExclusiveDevice(devices: List<Device>, id: String?): Device? {
        if (id.isNullOrBlank()) return devices.singleOrNull()
        return devices.firstOrNull { it.id == id }
            ?: devices.singleOrNull { it.id.substringAfter(':') == id.substringAfter(':') }
    }

    fun listDevices(context: Context): List<Device> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return manager.deviceList.values
            .filter { hasAudioOutput(it) }
            .map { describe(context, it) }
    }

    fun describe(device: UsbDevice): Device = describe(null, device)

    fun describe(context: Context?, device: UsbDevice): Device {
        val rates = mutableSetOf<Int>()
        val depths = mutableSetOf<Int>()
        var channels = 0
        if (context != null) {
            val info = findAudioDeviceInfo(context, device)
            if (info != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                rates += info.sampleRates.toList()
                depths += info.encodings.toList().mapNotNull { encodingToBits(it) }
                channels = info.channelCounts.maxOrNull() ?: 0
            }
        }
        return Device(
            usbDevice = device,
            name = device.productName?.takeIf { it.isNotBlank() }
                ?: device.manufacturerName?.takeIf { it.isNotBlank() }
                ?: "USB Audio (${device.vendorId.toString(16)}:${device.productId.toString(16)})",
            vendorId = device.vendorId,
            productId = device.productId,
            sampleRates = rates.filter { it in 8_000..768_000 }.sorted(),
            bitDepths = depths.filter { it in 8..64 }.sorted(),
            channels = channels,
        )
    }

    fun hasAudioOutput(device: UsbDevice): Boolean {
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            for (endpointIndex in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(endpointIndex)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    return true
                }
            }
        }
        return false
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return manager.hasPermission(device)
    }

    fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        if (manager.hasPermission(device)) return true
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val existing = PendingIntent.getBroadcast(context, device.deviceId, intent, flags or PendingIntent.FLAG_NO_CREATE)
        if (existing != null) return false
        val pendingIntent = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        try {
            UsbAudioSession.requestPermission(manager, device, pendingIntent)
        } catch (failure: RuntimeException) {
            pendingIntent.cancel()
            throw failure
        }
        return false
    }

    fun permissionReceiver(onResult: (UsbDevice?, Boolean) -> Unit): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(device, granted)
            }
        }

    fun registerPermissionReceiver(context: Context, receiver: BroadcastReceiver) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceiver(context: Context, receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun findAudioDeviceInfo(context: Context, device: UsbDevice? = null): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbOutputs = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usbOutputs.isEmpty()) return null
        if (device == null) return usbOutputs.first()
        val name = device.productName.orEmpty()
        if (name.isNotBlank()) {
            usbOutputs.firstOrNull { it.productName.toString().contains(name, ignoreCase = true) }
                ?.let { return it }
        }
        return usbOutputs.first()
    }

    private fun encodingToBits(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT -> 32
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
}
