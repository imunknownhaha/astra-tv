package com.astratv.player.core.network

import com.astratv.player.core.model.*
import java.io.Reader

/** Single-pass parser. No stream URLs or credentials enter error messages. */
object M3uParser {
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
    fun parse(reader: Reader, base: String? = null, allowHttp: Boolean = false): ParsedPlaylist {
        val items = linkedMapOf<String, ImportedItem>()
        var metadata: Map<String, String> = emptyMap()
        var name = ""; var group = ""; var guide = ""; var agent = ""; var referer = ""; var skipped = 0
        var header = false; var count = 0; var characters = 0L
        reader.buffered().useLines { lines -> lines.forEach { untrimmed ->
            characters += untrimmed.length
            if (characters > 64L*1024*1024 || ++count > 1000000 || untrimmed.length > 65536) throw ImportFailure("Playlist exceeds the supported size.")
            val line = untrimmed.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", true) -> { header = true; val a = attrs(line); guide = a["x-tvg-url"] ?: a["url-tvg"] ?: "" }
                line.startsWith("#EXT-X-TARGETDURATION") -> throw ImportFailure("This is an HLS stream. Import a channel M3U playlist containing its address.")
                line.startsWith("#EXTINF:", true) -> {
                    metadata = attrs(line.substringBeforeLastComma())
                    name = line.substringAfterLastComma().trim()
                    group = metadata["group-title"].orEmpty()
                    agent = ""; referer = ""
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> agent = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=", true) -> referer = line.substringAfter('=')
                line.startsWith('#') -> Unit
                else -> {
                    if (!header) throw ImportFailure("The file is not an extended M3U playlist (#EXTM3U is missing).")
                    val url = try { UrlPolicy.resolve(base, line, allowHttp) } catch (_: ImportFailure) { skipped++; "" }
                    if (url.isNotEmpty()) {
                        val kind = when (metadata["media-type"]?.lowercase()) { "movie", "vod" -> MediaKind.MOVIE; "series" -> MediaKind.EPISODE; else -> MediaKind.LIVE }
                        val id = metadata["tvg-id"].orEmpty()
                        // URL identity avoids incorrectly collapsing distinct channels with reused guide IDs.
                        val key = stableId("${kind.name}|$url")
                        items.putIfAbsent(key, ImportedItem(key, name.ifBlank { metadata["tvg-name"].orEmpty().ifBlank { "Unnamed channel" } }.take(512), url,
                            group.ifBlank { "Uncategorized" }.take(256), metadata["tvg-logo"].orEmpty().take(16384), id.take(512), kind,
                            catchupDays = metadata["catchup-days"]?.toIntOrNull()?.coerceIn(0, 365) ?: 0,
                            catchupTemplate = metadata["catchup-source"].orEmpty(), userAgent = agent, referrer = referer))
                    }
                    metadata = emptyMap(); name = ""; group = ""; agent = ""; referer = ""
                    if (items.size > 100000) throw ImportFailure("Playlist has more than 100,000 entries.")
                }
            }
        } }
        if (!header || items.isEmpty()) throw ImportFailure(if (skipped > 0) "No supported channels found. Check the source addresses and HTTP setting." else "No channels found in this playlist.")
        return ParsedPlaylist(items.values.toList(), guide, skipped)
    }
    private fun attrs(text: String): Map<String,String> = attributes.findAll(text).associate { m ->
        m.groupValues[1].lowercase() to m.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
    }
    private fun String.commaIndex(): Int { var quote = '\u0000'; forEachIndexed { i,c ->
        if (c == '"' || c == '\'') { if (quote == c) quote = '\u0000' else if (quote == '\u0000') quote = c }
        if (c == ',' && quote == '\u0000') return i
    }; return -1 }
    private fun String.substringBeforeLastComma(): String = commaIndex().let { if(it < 0) this else substring(0,it) }
    private fun String.substringAfterLastComma(): String = commaIndex().let { if(it < 0) "" else substring(it+1) }
}
