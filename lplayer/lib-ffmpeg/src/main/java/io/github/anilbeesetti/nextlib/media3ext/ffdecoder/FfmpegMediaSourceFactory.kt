package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * Creates progressive MediaSources which demux every container through FFmpeg.
 *
 * <p>The factory is intentionally stateless. Media3 creates the DataSource immediately before
 * asking for the extractor on the same loader thread; a ThreadLocal hands that already-open
 * source to [FfmpegProgressiveMediaExtractor] so FFmpeg can perform absolute byte seeks.</p>
 */
@UnstableApi
class FfmpegMediaSourceFactory(
    private val dataSources: DataSource.Factory
) {
    fun createMediaSource(item: MediaItem): MediaSource {
        require(item.localConfiguration?.drmConfiguration == null) {
            "FFmpeg extraction does not support DRM"
        }
        val pending = ThreadLocal<ReopeningDataSource>()
        val factory = DataSource.Factory {
            check(pending.get() == null) { "Unconsumed FFmpeg source reader" }
            ReopeningDataSource(dataSources.createDataSource()).also(pending::set)
        }
        val extractorFactory = ProgressiveMediaExtractor.Factory {
            val source = checkNotNull(pending.get()) { "FFmpeg extractor requires its loader's reader" }
            pending.remove()
            FfmpegProgressiveMediaExtractor(source)
        }
        return ProgressiveMediaSource
            .Factory(factory, extractorFactory)
            .createMediaSource(item)
    }
}
