package com.lalilu.common.base

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Gson adapter for [SourceType].
 *
 * `SourceType` is a sealed class, so Gson's reflective adapter cannot instantiate it. Media
 * cache entries written by [com.lalilu.lmedia.scanner.FileSystemScanner] need it to survive
 * process restarts, so store the stable [SourceType.name] instead.
 */
class SourceTypeAdapter : TypeAdapter<SourceType>() {
    override fun write(out: JsonWriter, value: SourceType?) {
        if (value == null) out.nullValue() else out.value(value.name)
    }

    override fun read(reader: JsonReader): SourceType? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }

            JsonToken.BEGIN_OBJECT -> {
                var name = "Unknown"
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "name") name = reader.nextString() else reader.skipValue()
                }
                reader.endObject()
                fromName(name)
            }

            else -> fromName(reader.nextString())
        }
    }

    private fun fromName(name: String): SourceType = when (name) {
        SourceType.Unknown.name -> SourceType.Unknown
        SourceType.MediaStore.name -> SourceType.MediaStore
        SourceType.Local.name -> SourceType.Local
        SourceType.Network.name -> SourceType.Network
        SourceType.WebDAV.name -> SourceType.WebDAV
        else -> SourceType.Extension(name)
    }
}
