package com.astratv.player.core.database

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.astratv.player.core.datastore.Settings
import com.astratv.player.core.model.*
import com.astratv.player.core.network.*
import com.astratv.player.core.security.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LibraryRepository(val db: LibraryDatabase,val secrets: SecretStore,val network: NetworkClient,private val context: Context) {
    val dao=db.library()
    suspend fun initialize() { if(dao.profile("default")==null) dao.putProfile(ProfileEntity("default","Home")) }
    fun browse(settings: Settings,section: String,category: String,query: String,limit: Int,profile: ProfileEntity?): Flow<List<MediaRow>> {
        val args=mutableListOf<Any>(settings.profile)
        val clauses=mutableListOf("1=1")
        when(section) {
            "Live TV","Guide","Catch-up" -> clauses+="i.kind='LIVE'"
            "Movies" -> clauses+="i.kind='MOVIE'"
            "Series" -> clauses+="i.kind='SERIES'"
            "Favorites" -> clauses+="w.favorite=1"
            "History" -> clauses+="w.lastPlayed>0"
            "Continue watching" -> clauses+="w.position>0 AND w.watched=0 AND i.kind!='LIVE'"
        }
        if(!settings.merged && settings.source.isNotEmpty()) { clauses+="i.sourceId=?";args+=settings.source }
        if(category.isNotEmpty()) { clauses+="i.groupName=?";args+=category }
        if(query.isNotEmpty()) { clauses+="(i.name LIKE ? ESCAPE '\\' OR i.groupName LIKE ? ESCAPE '\\')";val q="%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args+=q;args+=q }
        if(section=="Catch-up") clauses+="i.catchupDays>0"
        if(profile!=null) {
            val groups=JSONArray(profile.restrictedGroups)
            for(i in 0 until groups.length()) { clauses+="(i.groupName!=? AND NOT EXISTS (SELECT 1 FROM items parent WHERE parent.id=i.parentId AND parent.groupName=?))";args+=groups.getString(i);args+=groups.getString(i) }
        }
        val order=when { section in listOf("History","Continue watching") -> "w.lastPlayed DESC";settings.sort=="Recently refreshed" -> "i.generation DESC,i.name COLLATE NOCASE";else -> "i.name COLLATE NOCASE" }
        args+=limit
        return dao.browse(SimpleSQLiteQuery("SELECT i.*,COALESCE(w.favorite,0) AS favorite,COALESCE(w.position,0) AS position,COALESCE(w.duration,0) AS duration,COALESCE(w.lastPlayed,0) AS lastPlayed,COALESCE(w.watched,0) AS watched FROM items i LEFT JOIN watch w ON w.itemId=i.id AND w.profileId=? WHERE ${clauses.joinToString(" AND ")} ORDER BY $order LIMIT ?",args.toTypedArray()))
    }
    suspend fun neighbor(settings: Settings,profile: ProfileEntity?,current: ItemEntity?,direction: Int): ItemEntity? {
        val base=mutableListOf("kind='LIVE'");val baseArgs=mutableListOf<Any>()
        if(!settings.merged && settings.source.isNotEmpty()) { base+="sourceId=?";baseArgs+=settings.source }
        val hidden=JSONArray(profile?.restrictedGroups ?: "[]")
        for(i in 0 until hidden.length()) { base+="groupName!=?";baseArgs+=hidden.getString(i) }
        val comparison=if(direction>0) ">" else "<";val order=if(direction>0) "ASC" else "DESC"
        suspend fun find(withCursor: Boolean): ItemEntity? {
            val clauses=base.toMutableList();val args=baseArgs.toMutableList()
            if(withCursor && current!=null) { clauses+="(name COLLATE NOCASE $comparison ? OR (name COLLATE NOCASE = ? AND id $comparison ?))";args+=current.name;args+=current.name;args+=current.id }
            return dao.selectItems(SimpleSQLiteQuery("SELECT * FROM items WHERE ${clauses.joinToString(" AND ")} ORDER BY name COLLATE NOCASE $order,id $order LIMIT 1",args.toTypedArray())).firstOrNull()
        }
        return find(true) ?: find(false)
    }
    suspend fun importM3u(name: String,url: String,allowHttp: Boolean,existing: String?=null): Int = withContext(Dispatchers.IO) {
        val address=UrlPolicy.validate(url,allowHttp)
        val parsed=network.read(address,allowHttp) { M3uParser.parse(it.reader(),address,allowHttp) }
        val config=JSONObject().put("url",address)
        persist(SourceEntity(existing ?: UUID.randomUUID().toString(),name.ifBlank { "My playlist" },"M3U",secrets.seal(config.toString()),secrets.seal(parsed.guideUrl)),parsed.items)
    }
    suspend fun importFile(name: String,uri: Uri,allowHttp: Boolean): Int = withContext(Dispatchers.IO) {
        val parsed=context.contentResolver.openInputStream(uri)?.use { M3uParser.parse(it.reader(),allowHttp=allowHttp) } ?: throw ImportFailure("The selected file could not be opened.")
        persist(SourceEntity(UUID.randomUUID().toString(),name.ifBlank { "Local playlist" },"FILE",secrets.seal(JSONObject().put("uri",uri.toString()).toString()),secrets.seal(parsed.guideUrl)),parsed.items)
    }
    suspend fun importXtream(name: String,server: String,user: String,password: String,allowHttp: Boolean,existing: String?=null): Int = withContext(Dispatchers.IO) {
        val base=UrlPolicy.validate(server.trimEnd('/'),allowHttp)
        val u=java.net.URI(base)
        if(u.rawQuery!=null || u.path !in listOf("","/")) throw ImportFailure("Use the provider's server address and port without a path or query.")
        val config=JSONObject().put("base",base).put("user",user).put("password",password)
        fun get(action: String="")=network.text(api(config,action),allowHttp)
        val auth=JSONObject(get()).optJSONObject("user_info")
        if(auth?.optInt("auth")!=1) throw ImportFailure("Provider authentication failed. Check your credentials and subscription status.")
        val all=mutableListOf<ImportedItem>()
        for((kind,action,categories) in listOf(Triple(MediaKind.LIVE,"get_live_streams","get_live_categories"),Triple(MediaKind.MOVIE,"get_vod_streams","get_vod_categories"),Triple(MediaKind.SERIES,"get_series","get_series_categories"))) {
            val groups=XtreamParser.categories(get(categories))
            all+=XtreamParser.items(get(action),kind,base,user,password,groups)
        }
        if(all.isEmpty()) throw ImportFailure("The provider returned an empty library.")
        val epg="$base/xmltv.php?username=${XtreamParser.segment(user)}&password=${XtreamParser.segment(password)}"
        persist(SourceEntity(existing ?: UUID.randomUUID().toString(),name.ifBlank { "My provider" },"XTREAM",secrets.seal(config.toString()),secrets.seal(epg)),all)
    }
    private suspend fun persist(source: SourceEntity,items: List<ImportedItem>): Int {
        val generation=System.currentTimeMillis()
        db.withTransaction {
            dao.putSource(source.copy(updated=generation))
            items.chunked(500).forEach { batch -> dao.putItems(batch.map { entity(source.id,it,generation) }) }
            dao.prune(source.id,generation)
            dao.pruneEpisodes(source.id)
        }
        return items.size
    }
    private fun entity(source: String,item: ImportedItem,generation: Long)=ItemEntity(stableId("$source|${item.key}"),source,item.name,item.kind.name,item.group,
        secrets.seal(item.url),secrets.seal(item.logo),item.guideId,item.providerId,item.description,item.catchupDays,secrets.seal(item.catchupTemplate),item.season,item.episode,
        if(item.parentId.isEmpty()) "" else stableId("$source|${item.parentId}"),secrets.seal(item.userAgent),secrets.seal(item.referrer),generation)
    suspend fun refresh(source: SourceEntity,allowHttp: Boolean): Int {
        val c=JSONObject(secrets.open(source.configCipher))
        return when(source.type) {
            "M3U" -> importM3u(source.name,c.getString("url"),allowHttp,source.id)
            "XTREAM" -> importXtream(source.name,c.getString("base"),c.getString("user"),c.getString("password"),allowHttp,source.id)
            else -> withContext(Dispatchers.IO) {
                val uri=Uri.parse(c.getString("uri"))
                val parsed=context.contentResolver.openInputStream(uri)?.use { M3uParser.parse(it.reader(),allowHttp=allowHttp) } ?: throw ImportFailure("Select the local file again to restore access.")
                persist(source,parsed.items)
            }
        }
    }
    suspend fun refreshGuide(source: SourceEntity,url: String,allowHttp: Boolean): Int = withContext(Dispatchers.IO) {
        val address=UrlPolicy.validate(url,allowHttp)
        val entries=network.read(address,allowHttp,128L*1024*1024) { XmlTvParser.parse(it.reader()) }
        if(entries.isEmpty()) throw ImportFailure("No programs found in the supported guide window (7 days past to 14 days ahead).")
        db.withTransaction {
            dao.clearGuide(source.id)
            entries.chunked(500).forEach { batch -> dao.putPrograms(batch.map { ProgramEntity(source.id,it.channelId,it.title,it.start,it.end,it.description) }) }
            dao.putSource(source.copy(guideCipher=secrets.seal(address)))
        };entries.size
    }
    suspend fun episodes(series: ItemEntity,allowHttp: Boolean): List<ItemEntity> = withContext(Dispatchers.IO) {
        val source=dao.source(series.sourceId) ?: throw ImportFailure("Provider configuration is missing.")
        if(source.type!="XTREAM") throw ImportFailure("Episode browsing requires an Xtream-compatible series provider.")
        val c=JSONObject(secrets.open(source.configCipher))
        val list=XtreamParser.episodes(network.text(api(c,"get_series_info")+"&series_id=${XtreamParser.segment(series.providerId)}",allowHttp),series.providerId,c.getString("base"),c.getString("user"),c.getString("password"))
        val entities=list.map { entity(source.id,it.copy(group=series.groupName),System.currentTimeMillis()) };dao.putItems(entities);entities
    }
    suspend fun catchup(item: ItemEntity,program: ProgramEntity,allowHttp: Boolean): String = withContext(Dispatchers.IO) {
        val now=System.currentTimeMillis()
        if(item.catchupDays<=0 || program.start>=now || program.start < now-item.catchupDays*86400000L) throw ImportFailure("This program is outside the provider's catch-up window.")
        val source=dao.source(item.sourceId) ?: throw ImportFailure("Provider is unavailable.")
        val url=if(source.type=="XTREAM") {
            val c=JSONObject(secrets.open(source.configCipher));val time=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(program.start))
            "${c.getString("base")}/timeshift/${XtreamParser.segment(c.getString("user"))}/${XtreamParser.segment(c.getString("password"))}/${((program.end-program.start)/60000).coerceAtLeast(1)}/$time/${XtreamParser.segment(item.providerId)}.ts"
        } else {
            val template=secrets.open(item.catchupCipher)
            if(template.isEmpty() || (!template.contains("{utc}") && !template.contains("{start}"))) throw ImportFailure("This playlist does not provide a supported catch-up address template.")
            template.replace("{utc}",(program.start/1000).toString()).replace("{start}",(program.start/1000).toString())
                .replace("{duration}",((program.end-program.start)/1000).toString()).replace("{utcend}",(program.end/1000).toString())
        };UrlPolicy.validate(url,allowHttp)
    }
    suspend fun favorite(profile: String,item: String) { db.withTransaction { val w=dao.watch(profile,item) ?: WatchEntity(profile,item);dao.putWatch(w.copy(favorite=!w.favorite)) } }
    suspend fun progress(profile: String,item: String,position: Long,duration: Long) { db.withTransaction {
        val w=dao.watch(profile,item) ?: WatchEntity(profile,item)
        dao.putWatch(w.copy(position=position.coerceAtLeast(0),duration=duration.coerceAtLeast(0),lastPlayed=System.currentTimeMillis(),watched=duration>0 && position.toDouble()/duration>0.95))
    } }
    suspend fun delete(source: SourceEntity) { db.withTransaction { dao.deleteItems(source.id);dao.clearGuide(source.id);dao.deleteSource(source.id) } }
    private fun api(c: JSONObject,action: String)="${c.getString("base")}/player_api.php?username=${XtreamParser.segment(c.getString("user"))}&password=${XtreamParser.segment(c.getString("password"))}"+(if(action.isEmpty()) "" else "&action=$action")
}
