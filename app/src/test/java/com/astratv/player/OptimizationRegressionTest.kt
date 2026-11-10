package com.astratv.player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.astratv.player.core.database.*
import com.astratv.player.core.datastore.Settings
import com.astratv.player.core.network.NetworkClient
import com.astratv.player.core.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class OptimizationRegressionTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    @Test fun migrationPreservesExistingSourcesCiphertextFavoritesAndProgress()=runBlocking {
        val name="migration-${System.nanoTime()}.db"
        val path=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schemaFile=File("schemas/com.astratv.player.core.database.LibraryDatabase/1.json")
        val schema=JSONObject(schemaFile.readText()).getJSONObject("database").getJSONArray("entities")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            for(i in 0 until schema.length()) {
                val table=schema.getJSONObject(i)
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",table.getString("tableName")))
                val indices=table.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table.getString("tableName")))
            }
            old.execSQL("INSERT INTO sources VALUES ('source','Saved provider','XTREAM','encrypted-config','encrypted-guide',1)")
            old.execSQL("INSERT INTO profiles VALUES ('default','Home','','[]')")
            old.execSQL("INSERT INTO items VALUES ('channel','source','ZEBRA News','LIVE','News','encrypted-stream','encrypted-logo','news','1','',0,'',0,0,'','','',1)")
            old.execSQL("INSERT INTO watch VALUES ('default','channel',1,12345,60000,42,0)")
            old.version=1
        }
        val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val row=db.library().item("channel")!!
            assertEquals("zebra news",row.sortName)
            assertEquals("encrypted-stream",row.streamCipher)
            assertEquals("encrypted-config",db.library().source("source")!!.configCipher)
            assertTrue(db.library().watch("default","channel")!!.favorite)
            assertEquals(12345,db.library().watch("default","channel")!!.position)
            assertEquals(2,db.openHelper.readableDatabase.version)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun artworkCacheAvoidsRepeatedDecryptionAndEvictsWithinItsBudget()=runBlocking {
        val decrypts=AtomicInteger()
        val codec=object: SecretCodec {
            override fun seal(value: String)=value
            override fun open(value: String): String { decrypts.incrementAndGet();return "https://example.test/$value" }
        }
        val cache=ArtworkResolver(codec,256)
        repeat(20) { assertEquals("https://example.test/first",cache.resolve("first")) }
        assertEquals(1,decrypts.get())
        repeat(10) { cache.resolve("image-$it") }
        val before=decrypts.get();cache.resolve("first")
        assertEquals(before+1,decrypts.get())
        assertEquals("",cache.resolve(""))
    }
    @Test fun indexedBrowsePreservesCaseInsensitiveOrderAndChannelWraparound()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val codec=object: SecretCodec { override fun seal(value: String)=value;override fun open(value: String)=value }
            val repo=LibraryRepository(db,codec,NetworkClient(),context)
            repo.dao.putSource(SourceEntity("source","Test","M3U",""))
            val names=listOf("zebra","Beta","ALPHA","apple")
            repo.dao.putItems(names.mapIndexed { i,n -> ItemEntity("$i","source",n,"LIVE","News","","","","","",0,"",0,0,"","","",1) })
            val rows=repo.browse(Settings(),"Live TV","","",200,null).first()
            assertEquals(listOf("ALPHA","apple","Beta","zebra"),rows.map { it.item.name })
            assertEquals("ALPHA",repo.neighbor(Settings(),null,rows.last().item,1)!!.name)
            assertEquals("zebra",repo.neighbor(Settings(),null,rows.first().item,-1)!!.name)
        } finally { db.close() }
    }
}
