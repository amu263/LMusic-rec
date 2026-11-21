package com.lalilu.lplayer.extensions

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DffExtractor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegMediaSourceFactory

/**
 * Selects the FFmpeg demuxer for containers/codecs that Media3's built-in extractors do not
 * understand, while leaving common formats on Media3's mature extractors.
 */
@UnstableApi
class UniversalMediaSourceFactory(
    context: Context,
) : MediaSource.Factory {

    private val dataSources = DefaultDataSource.Factory(context)
    private val fallback = DefaultMediaSourceFactory(context)
    private val ffmpeg = FfmpegMediaSourceFactory(dataSources)
    private val dff = ProgressiveMediaSource.Factory(
        dataSources,
        ExtractorsFactory { arrayOf(DffExtractor()) }
    )

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        fallback.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        fallback.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val extension = mediaItem.localConfiguration?.uri?.path
            ?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension == "dff" || extension == "dsdiff") {
            return dff.createMediaSource(mediaItem)
        }
        if (FfmpegLibrary.isAvailable() && needsFfmpeg(mediaItem)) {
            return ffmpeg.createMediaSource(mediaItem)
        }
        return fallback.createMediaSource(mediaItem)
    }

    private fun needsFfmpeg(mediaItem: MediaItem): Boolean {
        val configuration = mediaItem.localConfiguration ?: return false
        val uri = configuration.uri
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme != "content" && scheme != "file" && scheme != "android.resource") {
            return false
        }
        val mime = configuration.mimeType?.lowercase().orEmpty()
        if (mime.startsWith("audio/")) {
            if (FFMPEG_MIMES.any { mime == it }) return true
        }
        // MediaStore often reports ALAC/M4A as audio/mpeg. The scanner stores the real file
        // path in RequestMetadata extras, so prefer its extension for format detection.
        val dataPath = mediaItem.requestMetadata.extras?.getString("DATA")
            ?.lowercase()
            .orEmpty()
        val path = dataPath.ifBlank { uri.path?.lowercase().orEmpty() }
        val extension = path.substringAfterLast('.', "")
        if (extension in FFMPEG_EXTENSIONS) return true
        // Unknown audio extensions should still get a chance through FFmpeg's probe.
        return extension.isNotEmpty() && extension !in MEDIA3_EXTENSIONS
    }

    private companion object {
        val FFMPEG_EXTENSIONS = setOf(
            "ape", "wv", "wma", "asf", "aif", "aiff", "aifc", "tta", "tak", "dst",
            "alac", "m4a", "mp4", "m4b", "caf", "au", "snd", "w64", "mka", "mkv",
            "dsf", "dff", "dsdiff", "mpc", "shn"
        )
        val MEDIA3_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "ogg", "oga", "opus", "aac", "m4a", "amr", "3gp",
            "ac3", "eac3", "ts", "m4b"
        )
        val FFMPEG_MIMES = setOf(
            "audio/x-ape", "audio/ape", "audio/x-wavpack", "audio/wavpack",
            "audio/x-wma", "audio/x-ms-wma", "audio/x-tta", "audio/x-tak",
            "audio/x-dsf", "audio/x-dff", "audio/x-dsd", "audio/x-aiff",
            "audio/aiff", "audio/x-alac", "audio/alac", "audio/x-m4a",
            "audio/mp4"
        )
    }
}
