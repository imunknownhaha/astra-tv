package com.astratv.player.core.network

import com.astratv.player.core.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.time.*
import java.time.format.DateTimeFormatter

object XmlTvParser {
    private val date = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    fun timestamp(value: String): Long {
        val parts = value.trim().split(Regex("\\s+"))
        val local = LocalDateTime.parse(parts[0].take(14).padEnd(14,'0'), date)
        val offset = parts.getOrNull(1)?.let { ZoneOffset.of(it) } ?: ZoneOffset.UTC
        return local.toInstant(offset).toEpochMilli()
    }
    /** Streaming XML; DTD/entity declarations are rejected, not expanded. Retain a bounded time window. */
    fun parse(reader: Reader, now: Long = System.currentTimeMillis()): List<GuideEntry> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)
        val result = ArrayList<GuideEntry>()
        var channel = ""; var start = 0L; var end = 0L; var title = ""; var description = ""
        var events = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (++events > 12000000) throw ImportFailure("The guide is too large.")
            when(parser.eventType) {
                XmlPullParser.DOCDECL -> throw ImportFailure("Guide files with document type declarations are not supported.")
                XmlPullParser.START_TAG -> when(parser.name) {
                    "programme" -> { channel = parser.getAttributeValue(null,"channel").orEmpty(); title = ""; description = ""
                        start = runCatching { timestamp(parser.getAttributeValue(null,"start").orEmpty()) }.getOrDefault(0)
                        end = runCatching { timestamp(parser.getAttributeValue(null,"stop").orEmpty()) }.getOrDefault(0) }
                    "title" -> title = parser.nextText().take(2048)
                    "desc" -> description = parser.nextText().take(12000)
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme" && channel.isNotBlank() && title.isNotBlank() && end > start &&
                    end >= now - 7 * 86400000L && start <= now + 14 * 86400000L) {
                    result += GuideEntry(channel,title,start,end,description)
                    if (result.size > 500000) throw ImportFailure("Guide exceeds 500,000 programs in the supported window.")
                }
            }
            parser.nextToken()
        }
        return result
    }
}
