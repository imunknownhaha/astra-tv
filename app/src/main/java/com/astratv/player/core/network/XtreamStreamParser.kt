package com.astratv.player.core.network

import android.util.JsonReader
import android.util.JsonToken
import com.astratv.player.core.model.*
import java.io.Reader

/** Catalog JSON is consumed one row at a time; unknown provider metadata is skipped. */
object XtreamStreamParser {
    private val fields = setOf("stream_id", "series_id", "name", "category_id", "stream_icon", "cover",
        "epg_channel_id", "container_extension", "plot", "tv_archive", "tv_archive_duration", "category_name")

    suspend fun items(input: Reader, kind: MediaKind, base: String, user: String, password: String,
        categories: Map<String, String>, sink: suspend (ImportedItem) -> Unit): Int {
        var count = 0
        records(input, 100000) { row ->
            val id = row[if (kind == MediaKind.SERIES) "series_id" else "stream_id"].orEmpty()
            if (id.isNotBlank()) {
                val extension = row["container_extension"]?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
                    ?: if (kind == MediaKind.LIVE) "ts" else "mp4"
                val path = if (kind == MediaKind.LIVE) "live" else "movie"
                val url = if (kind == MediaKind.SERIES) "" else
                    "$base/$path/${XtreamParser.segment(user)}/${XtreamParser.segment(password)}/${XtreamParser.segment(id)}.$extension"
                sink(ImportedItem("${kind.name}:$id", row["name"].orEmpty().ifBlank { "Unnamed item" }, url,
                    categories[row["category_id"]] ?: "Uncategorized", row[if (kind == MediaKind.SERIES) "cover" else "stream_icon"].orEmpty(),
                    row["epg_channel_id"].orEmpty(), kind, id, row["plot"].orEmpty(),
                    if (row["tv_archive"] == "1") row["tv_archive_duration"]?.toIntOrNull()?.coerceIn(0, 365) ?: 0 else 0))
                count++
            }
        }
        return count
    }

    suspend fun categories(input: Reader): Map<String, String> {
        val result = mutableMapOf<String, String>()
        records(input, 10000) { row ->
            val id = row["category_id"].orEmpty()
            if (id.isNotBlank()) result[id] = row["category_name"].orEmpty().ifBlank { "Uncategorized" }
        }
        return result
    }

    private suspend fun records(input: Reader, limit: Int, sink: suspend (Map<String, String>) -> Unit) {
        JsonReader(input).use { reader ->
            // Android JsonReader requires lenient mode to recognize a top-level null.
            // Restore strict parsing immediately after inspecting the first token.
            reader.isLenient = true
            val root = reader.peek()
            reader.isLenient = false
            when (root) {
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BEGIN_OBJECT -> { reader.beginObject(); if (reader.hasNext()) invalid(); reader.endObject() }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    var count = 0
                    while (reader.hasNext()) {
                        if (++count > limit) throw ImportFailure("Provider catalog exceeds the supported entry limit.")
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                        val row = mutableMapOf<String, String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (name !in fields) { reader.skipValue(); continue }
                            when (reader.peek()) {
                                JsonToken.STRING, JsonToken.NUMBER -> {
                                    val value = reader.nextString()
                                    val maximum = when (name) { "plot" -> 12000; "stream_icon", "cover" -> 16384; else -> 512 }
                                    if (value.length > maximum) throw ImportFailure("A provider metadata field is too large. The previous library was kept.")
                                    if (value != "null") row[name] = value
                                }
                                JsonToken.BOOLEAN -> row[name] = if (reader.nextBoolean()) "1" else "0"
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        sink(row)
                    }
                    reader.endArray()
                }
                else -> invalid()
            }
            if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
        }
    }
    private fun invalid(): Nothing = throw ImportFailure("Provider returned an unexpected catalog response. The previous library was kept.")
}
