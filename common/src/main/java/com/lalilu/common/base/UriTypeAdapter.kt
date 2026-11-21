package com.lalilu.common.base

import android.net.Uri
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/** Gson adapter for [Uri]: store the canonical string and parse it back. */
class UriTypeAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        if (value == null) out.nullValue() else out.value(value.toString())
    }

    override fun read(reader: JsonReader): Uri? {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }

            JsonToken.BEGIN_OBJECT -> {
                var value: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "value" || name == "uri" || name == "path") value = reader.nextString()
                    else reader.skipValue()
                }
                reader.endObject()
                value?.let(Uri::parse)
            }

            else -> reader.nextString().let(Uri::parse)
        }
    }
}
