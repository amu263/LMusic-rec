package com.lalilu.usbuac

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer

/** Direct UAC PCM session. Call and close from a single owner thread. */
class UsbAudioSession private constructor(
    private val connection: UsbDeviceConnection,
    private var handle: Long,
    val format: Pcm,
    private val onStatus: (Status) -> Unit,
) : AutoCloseable {
    data class Pcm(val sampleRate: Int, val channels: Int, val bits: Int) {
        init {
            require(sampleRate in 8_000..768_000 && channels in 1..32 && bits in listOf(16, 24, 32))
        }
        val frameBytes = channels * (bits / 8)
    }

    data class Status(
        val configuration: String,
        val framesWritten: Long,
        val framesTransferred: Long,
        val underrunPackets: Long,
        val transferErrors: Long,
        val feedbackPackets: Long,
        val error: Int,
        val hardwareVolumeResult: Int, // 0: untouched, 1: applied, -1: rejected by DAC
        val queuedFrames: Long = 0,
        val peakSample: Long = 0,
        val clippedSamples: Long = 0,
        val anomalySamples: Long = 0,
        val volumeRaw: Int = 0,
        val volumeMinRaw: Int = 0,
        val volumeMaxRaw: Int = 0,
        val volumeStepRaw: Int = 0,
        val targetFrames: Long = 0,
    ) {
        val hardwareVolumeAvailable: Boolean get() = hardwareVolumeResult > 0
        val hardwareVolumeDb: Float get() = volumeRaw / 256f
        val volumePercent: Int
            get() = if (volumeMaxRaw > volumeMinRaw) {
                ((volumeRaw - volumeMinRaw) * 100 / (volumeMaxRaw - volumeMinRaw)).coerceIn(0, 100)
            } else 0
    }

    val configuration: String = UsbNative.configuration(handle)

    /** Queues whole little-endian frames and advances the direct buffer by accepted bytes. */
    fun write(buffer: ByteBuffer, frames: Int): Int {
        require(buffer.isDirect && frames >= 0 && frames <= buffer.remaining() / format.frameBytes)
        val count = UsbNative.write(openHandle(), buffer, buffer.position(), frames)
        if (count < 0) {
            publishStatus()
            throw IOException("USB isochronous write failed (libusb $count)")
        }
        buffer.position(buffer.position() + count * format.frameBytes)
        return count
    }

    fun status(): Status {
        val stats = UsbNative.stats(openHandle())
        return Status(
            configuration = configuration,
            framesWritten = stats[0],
            framesTransferred = stats[1],
            underrunPackets = stats[2],
            transferErrors = stats[3],
            feedbackPackets = stats[4],
            error = stats[5].toInt(),
            hardwareVolumeResult = stats[6].toInt(),
            queuedFrames = stats[7],
            peakSample = stats[8],
            clippedSamples = stats[9],
            anomalySamples = stats[10],
            volumeRaw = stats[11].toInt(),
            volumeMinRaw = stats[12].toInt(),
            volumeMaxRaw = stats[13].toInt(),
            volumeStepRaw = stats[14].toInt(),
            targetFrames = stats[15],
        )
    }

    /** Delivers a snapshot on the owning thread. */
    fun publishStatus() = status().also(onStatus)

    /** Sets the DAC master Feature Unit volume, 0..100% of its hardware range. */
    fun setVolumePercent(percent: Int, smooth: Boolean = true): Boolean =
        UsbNative.setVolumePercent(openHandle(), percent.coerceIn(0, 100).toFloat(), smooth)

    fun resetStats() = UsbNative.resetStats(openHandle())
    fun start() = UsbNative.run(openHandle(), true)
    fun stop() = UsbNative.run(openHandle(), false)
    fun drain() = UsbNative.drain(openHandle())

    /** Drops queued audio, cancels in-flight transfers and releases the claimed interfaces. */
    fun flush() = close()

    override fun close() {
        if (handle == 0L) return
        try {
            UsbNative.close(handle)
        } finally {
            handle = 0
            connection.close()
        }
    }

    private fun openHandle(): Long = handle.also { check(it != 0L) { "USB audio session is closed" } }

    companion object {
        /** The caller owns the package-scoped permission result receiver. */
        fun requestPermission(manager: UsbManager, device: UsbDevice, result: PendingIntent) {
            if (!manager.hasPermission(device)) manager.requestPermission(device, result)
        }

        /** Lists Type I PCM alternate settings from the active USB configuration. */
        fun describe(manager: UsbManager, device: UsbDevice): List<String> {
            val connection = connect(manager, device)
            try {
                return UsbNative.probe(connection.fileDescriptor).lineSequence().filter { it.isNotBlank() }.toList()
            } finally {
                connection.close()
            }
        }

        fun open(
            manager: UsbManager,
            device: UsbDevice,
            format: Pcm,
            bufferMs: Int = 600,
            channelCompatibility: Boolean = true,
            bitDepthCompatibility: Boolean = true,
            onStatus: (Status) -> Unit = {},
        ): UsbAudioSession {
            val connection = connect(manager, device)
            var handle = 0L
            try {
                handle = UsbNative.open(
                    connection.fileDescriptor,
                    format.sampleRate,
                    format.channels,
                    format.bits,
                    bufferMs,
                    channelCompatibility,
                    bitDepthCompatibility,
                )
                return UsbAudioSession(connection, handle, format, onStatus).also { it.publishStatus() }
            } catch (failure: Throwable) {
                if (handle != 0L) UsbNative.close(handle)
                connection.close()
                throw failure
            }
        }

        private fun connect(manager: UsbManager, device: UsbDevice): UsbDeviceConnection {
            if (!manager.hasPermission(device)) throw IOException("USB permission required")
            return manager.openDevice(device) ?: throw IOException("Could not open USB device")
        }
    }
}

internal object UsbNative {
    init { System.loadLibrary("usbuac") }

    external fun probe(fd: Int): String
    external fun open(
        fd: Int,
        rate: Int,
        channels: Int,
        bits: Int,
        bufferMs: Int,
        channelCompatibility: Boolean,
        bitDepthCompatibility: Boolean,
    ): Long
    external fun configuration(handle: Long): String
    external fun write(handle: Long, buffer: ByteBuffer, offset: Int, frames: Int): Int
    external fun stats(handle: Long): LongArray
    external fun run(handle: Long, play: Boolean)
    external fun drain(handle: Long)
    external fun setVolumePercent(handle: Long, percent: Float, smooth: Boolean): Boolean
    external fun resetStats(handle: Long): Boolean
    external fun close(handle: Long)
}
