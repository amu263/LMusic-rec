package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A DataSource that can be reopened at an absolute byte offset by the native FFmpeg seek
 * callback while preserving the original URI permission and request bounds.
 */
@UnstableApi
class ReopeningDataSource(
    private val delegate: DataSource
) : DataSource {

    private lateinit var request: DataSpec
    private var end = C.LENGTH_UNSET.toLong()
    private var opened = false

    var position = 0L
        private set
    var length = C.LENGTH_UNSET.toLong()
        private set

    override fun open(dataSpec: DataSpec): Long {
        request = dataSpec
        position = dataSpec.position
        opened = true
        val remaining = delegate.open(dataSpec)
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else Math.addExact(dataSpec.position, dataSpec.length)
        length = if (remaining == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
        else Math.addExact(position, remaining)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end != C.LENGTH_UNSET.toLong() && position == end) return C.RESULT_END_OF_INPUT
        return delegate.read(buffer, offset, length).also { if (it > 0) position += it }
    }

    fun seek(offset: Long): Long {
        if (offset < request.position || (end != C.LENGTH_UNSET.toLong() && offset > end)) {
            throw IOException("FFmpeg seek outside request: $offset")
        }
        if (offset == position) return position
        close()
        position = offset
        if (end != C.LENGTH_UNSET.toLong() && offset == end) return position
        val spec = request.buildUpon()
            .setPosition(offset)
            .setLength(
                if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - offset
            )
            .build()
        opened = true
        delegate.open(spec)
        return position
    }

    override fun close() {
        if (opened) {
            opened = false
            delegate.close()
        }
    }

    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)
}
