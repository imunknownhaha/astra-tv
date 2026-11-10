package com.astratv.player.core.database

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName="sources")
data class SourceEntity(@PrimaryKey val id: String, val name: String, val type: String, val configCipher: String, val guideCipher: String = "", val updated: Long = 0)
@Entity(tableName="items", indices=[Index("sourceId"), Index(value=["kind","groupName"]), Index("guideId"),Index("parentId"),Index(value=["kind","name","id"])])
data class ItemEntity(@PrimaryKey val id: String, val sourceId: String, val name: String, val kind: String, val groupName: String,
    val streamCipher: String, val logoCipher: String, val guideId: String, val providerId: String, val description: String,
    val catchupDays: Int, val catchupCipher: String, val season: Int, val episode: Int, val parentId: String,
    val agentCipher: String, val referrerCipher: String, val generation: Long)
@Entity(tableName="profiles")
data class ProfileEntity(@PrimaryKey val id: String, val name: String, val pinHash: String = "", val restrictedGroups: String = "[]")
@Entity(tableName="watch", primaryKeys=["profileId","itemId"],indices=[Index("itemId")])
data class WatchEntity(val profileId: String, val itemId: String, val favorite: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val lastPlayed: Long = 0, val watched: Boolean = false)
@Entity(tableName="programs",primaryKeys=["sourceId","channelId","start"],indices=[Index(value=["channelId","start","end"]),Index("sourceId")])
data class ProgramEntity(val sourceId: String, val channelId: String, val title: String, val start: Long, val end: Long, val description: String)
data class MediaRow(@Embedded val item: ItemEntity, val favorite: Boolean = false, val position: Long = 0, val duration: Long = 0, val lastPlayed: Long = 0, val watched: Boolean = false)
@Dao
interface LibraryDao {
    @Query("SELECT * FROM sources ORDER BY name") fun sources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources") suspend fun allSources(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): SourceEntity?
    @Upsert suspend fun putSource(source: SourceEntity)
    @Upsert suspend fun putItems(items: List<ItemEntity>)
    @Query("DELETE FROM items WHERE sourceId=:sourceId AND generation!=:generation AND kind!='EPISODE'") suspend fun prune(sourceId: String,generation: Long)
    @Query("DELETE FROM items WHERE sourceId=:sourceId AND kind='EPISODE' AND parentId!='' AND parentId NOT IN (SELECT id FROM items WHERE kind='SERIES')") suspend fun pruneEpisodes(sourceId: String)
    @Query("DELETE FROM items WHERE sourceId=:id") suspend fun deleteItems(id: String)
    @Query("DELETE FROM sources WHERE id=:id") suspend fun deleteSource(id: String)
    @RawQuery(observedEntities=[ItemEntity::class,WatchEntity::class]) fun browse(query: SupportSQLiteQuery): Flow<List<MediaRow>>
    @RawQuery suspend fun selectItems(query: SupportSQLiteQuery): List<ItemEntity>
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: String): ItemEntity?
    @Query("SELECT DISTINCT groupName FROM items WHERE (:source='' OR sourceId=:source) ORDER BY groupName COLLATE NOCASE") fun groups(source: String): Flow<List<String>>
    @Query("SELECT * FROM items WHERE kind='LIVE' ORDER BY name COLLATE NOCASE LIMIT 100000") suspend fun liveItems(): List<ItemEntity>
    @Query("SELECT * FROM profiles ORDER BY name") fun profiles(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM profiles WHERE id=:id") suspend fun profile(id: String): ProfileEntity?
    @Upsert suspend fun putProfile(profile: ProfileEntity)
    @Query("DELETE FROM profiles WHERE id=:id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM watch WHERE profileId=:id") suspend fun deleteProfileWatch(id: String)
    @Query("SELECT * FROM watch WHERE profileId=:profile AND itemId=:item") suspend fun watch(profile: String,item: String): WatchEntity?
    @Upsert suspend fun putWatch(watch: WatchEntity)
    @Upsert suspend fun putPrograms(programs: List<ProgramEntity>)
    @Query("DELETE FROM programs WHERE sourceId=:source") suspend fun clearGuide(source: String)
    @Query("SELECT * FROM programs WHERE sourceId=:source AND channelId=:channel AND end>:start AND start<:end ORDER BY start LIMIT 120")
    fun guide(source: String,channel: String,start: Long,end: Long): Flow<List<ProgramEntity>>
    @Query("SELECT * FROM programs WHERE end<:now") suspend fun expiredPrograms(now: Long): List<ProgramEntity>
    @Query("DELETE FROM programs WHERE end<:before") suspend fun pruneGuide(before: Long)
}
@Database(entities=[SourceEntity::class,ItemEntity::class,ProfileEntity::class,WatchEntity::class,ProgramEntity::class],version=1,exportSchema=true)
abstract class LibraryDatabase: RoomDatabase() { abstract fun library(): LibraryDao }
