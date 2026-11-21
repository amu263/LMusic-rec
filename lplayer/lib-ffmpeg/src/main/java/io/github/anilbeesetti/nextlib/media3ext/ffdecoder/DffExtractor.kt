package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/**
 * Minimal DSDIFF (`.dff`) extractor. FFmpeg dropped the DSDIFF demuxer, but the container is
 * simple: FRM8/DSD with a PROP/SND header (FS, CHNL, CMPR) followed by a raw DSD chunk.
 *
 * Raw DSD is MSB-first and interleaved; the bundled FFmpeg `dsd_msbf` decoder converts it to
 * PCM for us. Compressed DST chunks are not supported (FFmpeg's DST decoder expects its own
 * framing, which is outside the scope of a music-library importer).
 */
@UnstableApi
class DffExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var dataStart = -1L
    private var dataLength = -1L
    private var fileLength = C.LENGTH_UNSET.toLong()
    private var initialized = false
    private var seekMapSet = false
    private var framesConsumed = 0L
    private var pendingSeekUs = -1L

    private val scratch = ByteArray(MAX_PACKET)

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(12)
        return try {
            input.peekFully(header, 0, 12)
            header.copyOfRange(0, 4).contentEquals("FRM8".toByteArray(Charsets.US_ASCII)) &&
                header.copyOfRange(8, 12).contentEquals("DSD ".toByteArray(Charsets.US_ASCII))
        } catch (e: IOException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun seek(position: Long, seekTimeUs: Long) {
        pendingSeekUs = seekTimeUs.coerceAtLeast(0)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = output ?: return Extractor.RESULT_END_OF_INPUT
        if (!initialized) {
            parseHeader(input)
            initialized = true
        }
        if (dataStart < 0 || sampleRate <= 0 || channelCount <= 0) {
            return Extractor.RESULT_END_OF_INPUT
        }

        if (!seekMapSet) {
            setupTracks(output)
        }

        if (pendingSeekUs >= 0) {
            val targetUs = pendingSeekUs
            pendingSeekUs = -1
            val frame = (targetUs * sampleRate / 1_000_000L)
            val byteOffset = (dataStart + (frame * channelCount)).coerceIn(
                dataStart,
                dataStart + dataLength
            )
            seekPosition.position = byteOffset
            framesConsumed = frame
            return Extractor.RESULT_SEEK
        }

        val remaining = dataLength - (framesConsumed * channelCount)
        if (remaining <= 0) return Extractor.RESULT_END_OF_INPUT
        val bytes = minOf(remaining, MAX_PACKET.toLong()).toInt()
        input.readFully(scratch, 0, bytes)
        val track = track ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(ParsableByteArray(scratch, bytes), bytes)
        val timeUs = framesConsumed * 1_000_000L / sampleRate
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes, 0, null)
        framesConsumed += bytes / channelCount
        return Extractor.RESULT_CONTINUE
    }

    override fun release() = Unit

    private fun parseHeader(input: ExtractorInput) {
        val header = ByteArray(12)
        input.readFully(header, 0, 12)
        if (!header.copyOfRange(0, 4).contentEquals("FRM8".toByteArray(Charsets.US_ASCII))) {
            throw IOException("Not a DSDIFF file")
        }
        val formLength = readLongBe(header, 4)
        fileLength = formLength + 12
        if (!header.copyOfRange(8, 12).contentEquals("DSD ".toByteArray(Charsets.US_ASCII))) {
            throw IOException("Unsupported DSDIFF form")
        }

        while (true) {
            val chunkHeader = ByteArray(12)
            try {
                input.readFully(chunkHeader, 0, 12)
            } catch (e: IOException) {
                break
            }
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = readLongBe(chunkHeader, 4)
            if (size < 0) break
            when (id) {
                "PROP" -> {
                    parseProp(input)
                    if (size % 2L == 1L) input.skipFully(1)
                }
                "DSD " -> {
                    dataStart = input.position
                    dataLength = size
                    return
                }
                "DST " -> throw IOException("Compressed DST DSDIFF is not supported")
                else -> {
                    input.skipFully(minOf(size, fileLength - input.position).toInt())
                    if (size % 2L == 1L) input.skipFully(1)
                }
            }
        }
        throw IOException("DSDIFF has no DSD chunk")
    }

    private fun parseProp(input: ExtractorInput) {
        val header = ByteArray(12)
        input.readFully(header, 0, 12)
        val propType = String(header, 0, 4, Charsets.US_ASCII)
        val propSize = readLongBe(header, 4)
        if (propType != "SND ") {
            input.skipFully(minOf(propSize, Int.MAX_VALUE.toLong()).toInt())
            return
        }
        var consumed = 12L
        while (consumed + 12 <= propSize) {
            val subHeader = ByteArray(12)
            input.readFully(subHeader, 0, 12)
            val id = String(subHeader, 0, 4, Charsets.US_ASCII)
            val size = readLongBe(subHeader, 4)
            when (id) {
                "FS  " -> {
                    val fs = ByteArray(4)
                    input.readFully(fs, 0, 4)
                    sampleRate = (readLongBe(fs, 0) / 8).toInt()
                    if (size > 4) input.skipFully((size - 4).toInt())
                }
                "CHNL" -> {
                    val channels = ByteArray(2)
                    input.readFully(channels, 0, 2)
                    channelCount = ((channels[0].toInt() and 0xFF) shl 8) or
                        (channels[1].toInt() and 0xFF)
                    if (size > 2) input.skipFully((size - 2).toInt())
                }
                "CMPR" -> {
                    val compression = ByteArray(minOf(size, 4L).toInt())
                    input.readFully(compression, 0, compression.size)
                    if (String(compression, 0, compression.size, Charsets.US_ASCII) != "DSD ") {
                        throw IOException("Unsupported DSDIFF compression")
                    }
                    if (size > compression.size) input.skipFully((size - compression.size).toInt())
                }
                else -> input.skipFully(minOf(size, Int.MAX_VALUE.toLong()).toInt())
            }
            consumed += 12 + size
            if (size % 2L == 1L) {
                input.skipFully(1)
                consumed++
            }
        }
    }

    private fun setupTracks(output: ExtractorOutput) {
        val totalFrames = if (dataLength >= 0) dataLength / channelCount else 0
        val durationUs = if (sampleRate > 0) totalFrames * 1_000_000L / sampleRate else C.TIME_UNSET
        val format = Format.Builder()
            .setSampleMimeType(FfmpegLibrary.AUDIO_DSD_MSBF)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .setMaxInputSize(MAX_PACKET)
            .build()
        track = output.track(0, C.TRACK_TYPE_AUDIO).also { it.format(format) }
        output.endTracks()
        output.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint(timeUs, 0))
        })
        seekMapSet = true
    }

    private fun readLongBe(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 56) or
            ((data[offset + 1].toLong() and 0xFF) shl 48) or
            ((data[offset + 2].toLong() and 0xFF) shl 40) or
            ((data[offset + 3].toLong() and 0xFF) shl 32) or
            ((data[offset + 4].toLong() and 0xFF) shl 24) or
            ((data[offset + 5].toLong() and 0xFF) shl 16) or
            ((data[offset + 6].toLong() and 0xFF) shl 8) or
            (data[offset + 7].toLong() and 0xFF)
    }

    private companion object {
        const val MAX_PACKET = 64 * 1024
    }
}
