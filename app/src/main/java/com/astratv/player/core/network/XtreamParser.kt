package com.astratv.player.core.network

import com.astratv.player.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object XtreamParser {
    fun segment(value: String): String = URLEncoder.encode(value,"UTF-8").replace("+","%20")
    fun categories(json: String): Map<String,String> = JSONArray(json).let { list ->
        (0 until list.length()).associate { val o=list.getJSONObject(it); o.optString("category_id") to o.optString("category_name","Uncategorized") }
    }
    fun items(json: String, kind: MediaKind, base: String, user: String, password: String, categories: Map<String,String>): List<ImportedItem> {
        val list = JSONArray(json)
        if (list.length() > 100000) throw ImportFailure("Provider returned more than 100,000 entries.")
        return (0 until list.length()).mapNotNull { index ->
            val o = list.optJSONObject(index) ?: return@mapNotNull null
            val id = o.optString(if(kind == MediaKind.SERIES) "series_id" else "stream_id")
            if (id.isBlank() || id == "null") return@mapNotNull null
            val ext = o.optString("container_extension", if(kind==MediaKind.LIVE) "ts" else "mp4").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "mp4"
            val path = if(kind==MediaKind.LIVE) "live" else "movie"
            ImportedItem("${kind.name}:$id", o.optString("name","Unnamed item"),
                if(kind==MediaKind.SERIES) "" else "$base/$path/${segment(user)}/${segment(password)}/${segment(id)}.$ext",
                categories[o.optString("category_id")] ?: "Uncategorized",
                o.optString(if(kind==MediaKind.SERIES) "cover" else "stream_icon").nullClean(),
                o.optString("epg_channel_id").nullClean(),kind,id,
                o.optString("plot").nullClean(),
                if(o.optInt("tv_archive")==1) o.optInt("tv_archive_duration").coerceIn(0,365) else 0)
        }
    }
    fun episodes(json: String, seriesId: String, base: String, user: String, password: String): List<ImportedItem> {
        val root = JSONObject(json); val seasons = root.optJSONObject("episodes") ?: return emptyList()
        return seasons.keys().asSequence().toList().flatMap { season ->
            val list = seasons.optJSONArray(season) ?: return@flatMap emptyList()
            (0 until list.length()).mapNotNull { i ->
                val e = list.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id"); if(id.isBlank()) return@mapNotNull null
                val ext = e.optString("container_extension","mp4").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "mp4"
                val info = e.optJSONObject("info")
                ImportedItem("EPISODE:$id",e.optString("title","Episode ${i+1}"),"$base/series/${segment(user)}/${segment(password)}/${segment(id)}.$ext",
                    "Season $season",info?.optString("movie_image").orEmpty(),kind=MediaKind.EPISODE,providerId=id,
                    description=info?.optString("plot").orEmpty(),season=season.toIntOrNull() ?: 0,episode=e.optInt("episode_num",i+1),parentId="SERIES:$seriesId")
            }
        }.sortedWith(compareBy({it.season},{it.episode}))
    }
    private fun String.nullClean() = if(this == "null") "" else this
}
