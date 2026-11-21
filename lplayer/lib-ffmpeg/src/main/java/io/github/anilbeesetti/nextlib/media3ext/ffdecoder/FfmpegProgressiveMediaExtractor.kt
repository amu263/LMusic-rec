package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Media3 progressive extractor backed by FFmpeg's demuxers.
 *
 * <p>FFmpeg reports every audio stream (including APE, WMA, DSD, WavPack, TTA and TAK) through
 * the callbacks below. Media3's renderer then either uses a hardware MediaCodec or the FFmpeg
 * software decoder, depending on the configured decoder mode.</p>
 */
@Keep
@UnstableApi
class FfmpegProgressiveMediaExtractor(
    private val source: ReopeningDataSource
) : ProgressiveMediaExtractor {

    private lateinit var reader: DataReader
    private lateinit var output: ExtractorOutput
    private val tracks = mutableMapOf<Int, TrackOutput>()
    private var handle = 0L
    private var started = false
    private var pendingSeekUs: Long? = null

    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput
    ) {
        reader = dataReader
        this.output = output
        if (handle == 0L) {
            if (!FfmpegLibrary.isAvailable()) {
                throw IOException("FFmpeg extraction library is unavailable")
            }
            handle = nativeOpen()
            if (handle == 0L) {
                throw IOException("FFmpeg could not open the media container")
            }
        }
    }

    override fun read(positionHolder: PositionHolder): Int {
        started = true
        pendingSeekUs?.let {
            nativeSeek(handle, it)
            pendingSeekUs = null
        }
        return if (nativeRead(handle)) Extractor.RESULT_CONTINUE else Extractor.RESULT_END_OF_INPUT
    }

    override fun seek(position: Long, seekTimeUs: Long) {
        // The native demuxer owns the byte stream, so only the time seek is forwarded.
        pendingSeekUs = if (started || seekTimeUs != 0L) seekTimeUs else null
    }

    override fun getCurrentInputPosition(): Long = source.position

    override fun disableSeekingOnMp3Streams() = Unit

    override fun release() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
        tracks.clear()
        started = false
        pendingSeekUs = null
    }

    // ---- called from native (ffextractor.cpp) ----

    @Keep
    private fun readInput(bytes: ByteArray, size: Int): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return reader.read(bytes, 0, size)
    }

    @Keep
    private fun seekInput(position: Long): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return if (position == -1L) source.length else source.seek(position)
    }

    @Keep
    private fun onTrack(
        index: Int,
        mime: String,
        width: Int,
        height: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerCodedSample: Int,
        blockAlign: Int,
        bitRate: Int,
        pcmEncoding: Int,
        selectionFlags: Int,
        extra: ByteArray,
        language: String?
    ) {
        if (!MimeTypes.isAudio(mime)) return
        val format = Format.Builder()
            .setId(index.toString())
            .setSampleMimeType(mime)
            .setLanguage(language)
            .setSelectionFlags(selectionFlags)
            .setChannelCount(channels.coerceAtLeast(1))
            .setSampleRate(sampleRate.coerceAtLeast(1))
            .setMaxInputSize(MAX_PACKET_SIZE)
            .setCodecs(
                buildString {
                    if (bitsPerCodedSample > 0) append("lmusic-bits=").append(bitsPerCodedSample)
                    if (blockAlign > 0) {
                        if (isNotEmpty()) append(';')
                        append("block_align=").append(blockAlign)
                    }
                    if (bitRate > 0) {
                        if (isNotEmpty()) append(';')
                        append("bit_rate=").append(bitRate)
                    }
                }.takeIf { it.isNotEmpty() }
            )
            .apply {
                if (extra.isNotEmpty()) setInitializationData(listOf(extra))
                if (pcmEncoding != -1) setPcmEncoding(pcmEncoding)
            }
            .build()

        tracks[index] = output.track(index, C.TRACK_TYPE_AUDIO).also { it.format(format) }
    }

    @Keep
    private fun onTracksEnded(durationUs: Long, seekable: Boolean) {
        if (tracks.isEmpty()) {
            throw IllegalStateException("No supported FFmpeg audio track")
        }
        output.endTracks()
        val duration = if (durationUs == NO_DURATION || durationUs < 0) C.TIME_UNSET else durationUs
        output.seekMap(
            if (seekable && duration != C.TIME_UNSET) {
                object : SeekMap {
                    override fun isSeekable(): Boolean = true
                    override fun getDurationUs(): Long = duration
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                        SeekMap.SeekPoints(SeekPoint(timeUs, 0))
                }
            } else {
                object : SeekMap {
                    override fun isSeekable(): Boolean = false
                    override fun getDurationUs(): Long = duration
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                        SeekMap.SeekPoints(SeekPoint(timeUs, 0))
                }
            }
        )
    }

    @Keep
    private fun onSample(
        index: Int,
        timeUs: Long,
        durationUs: Long,
        flags: Int,
        bytes: ByteArray
    ) {
        val track = tracks[index] ?: return
        if (bytes.isEmpty()) return
        track.sampleData(ParsableByteArray(bytes), bytes.size)
        track.sampleMetadata(timeUs, flags, bytes.size, 0, null)
    }

    private external fun nativeOpen(): Long
    private external fun nativeRead(handle: Long): Boolean
    private external fun nativeSeek(handle: Long, timeUs: Long)
    private external fun nativeRelease(handle: Long)

    private companion object {
        const val MAX_PACKET_SIZE = 4 * 1024 * 1024
        const val NO_DURATION = Long.MIN_VALUE + 1
    }
}
