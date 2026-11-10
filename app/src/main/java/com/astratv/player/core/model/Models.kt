package com.astratv.player.core.model

import java.security.MessageDigest

enum class MediaKind { LIVE, MOVIE, SERIES, EPISODE }
data class ImportedItem(
    val key: String, val name: String, val url: String = "", val group: String = "Uncategorized",
    val logo: String = "", val guideId: String = "", val kind: MediaKind = MediaKind.LIVE,
    val providerId: String = "", val description: String = "", val catchupDays: Int = 0,
    val catchupTemplate: String = "", val season: Int = 0, val episode: Int = 0,
    val parentId: String = "", val userAgent: String = "", val referrer: String = ""
)
data class ParsedPlaylist(val items: List<ImportedItem>, val guideUrl: String = "", val skipped: Int = 0)
data class GuideEntry(val channelId: String, val title: String, val start: Long, val end: Long, val description: String = "")
fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
class ImportFailure(message: String) : Exception(message)
