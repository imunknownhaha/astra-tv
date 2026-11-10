package com.astratv.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.astratv.player.core.database.*
import com.astratv.player.core.datastore.Settings
import com.astratv.player.core.model.*
import com.astratv.player.core.network.*
import com.astratv.player.core.security.SecretCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=android.app.Application::class)
class XtreamImportTest {
    private lateinit var server: MockWebServer
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private var live = "[{\"stream_id\":1,\"name\":\"Synthetic channel\",\"category_id\":\"10\"}]"
    private var movies = "[]"
    private var series = "[]"
    private var failMovies = false
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).allowMainThreadQueries().build()
        // Deterministic TEST-ONLY codec. Device builds always use Android Keystore SecretStore.
        val codec=object: SecretCodec {
            override fun seal(value: String) = if(value.isEmpty()) "" else "test-sealed:$value"
            override fun open(value: String) = value.removePrefix("test-sealed:")
        }
        repo=LibraryRepository(db,codec,NetworkClient(),context)
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action=request.requestUrl?.queryParameter("action").orEmpty()
                return when(action) {
                    "" -> MockResponse().setBody("{\"user_info\":{\"auth\":1}}")
                    "get_live_categories","get_vod_categories","get_series_categories" -> MockResponse().setBody("[{\"category_id\":\"10\",\"category_name\":\"Synthetic\"}]")
                    "get_live_streams" -> MockResponse().setBody(live)
                    "get_vod_streams" -> if(failMovies) MockResponse().setResponseCode(503) else MockResponse().setBody(movies)
                    "get_series" -> MockResponse().setBody(series)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun cleanup() { server.shutdown();db.close() }
    private suspend fun connect(existing: String?=null)=repo.importXtream("Test",server.url("/").toString(),"synthetic","not-a-real-password",true,existing)

    @Test fun completeProviderImportPublishesBrowsableLiveMovieAndSeries()=runBlocking {
        movies="[{\"stream_id\":2,\"name\":\"Synthetic movie\",\"container_extension\":\"mp4\"}]"
        series="[{\"series_id\":3,\"name\":\"Synthetic series\"}]"
        val result=connect()
        assertEquals(3,result.count)
        val source=repo.dao.allSources().single()
        val rows=repo.browse(Settings(),"Search","","",200,null).first()
        assertEquals(setOf("LIVE","MOVIE","SERIES"),rows.map { it.item.kind }.toSet())
        assertTrue(rows.all { it.item.sourceId==source.id && !it.item.id.contains(':') })
        assertTrue(repo.secrets.open(rows.first { it.item.kind=="LIVE" }.item.streamCipher).endsWith("/live/synthetic/not-a-real-password/1.ts"))
    }
    @Test fun malformedRefreshRollsBackPartialBatchesAndPreservesFavorites()=runBlocking {
        connect();val source=repo.dao.allSources().single()
        val old=repo.browse(Settings(),"Live TV","","",200,null).first().single().item
        repo.favorite("default",old.id)
        live=(1..260).joinToString(",","[",",") { "{\"stream_id\":$it,\"name\":\"Changed $it\"}" } // truncated JSON after >2 batches
        try { connect(source.id);fail("Truncated provider JSON must fail") } catch (_: Exception) { }
        assertEquals("Synthetic channel",repo.dao.item(old.id)!!.name)
        assertTrue(repo.dao.watch("default",old.id)!!.favorite)
        assertEquals(1,db.openHelper.readableDatabase.query("SELECT count(*) FROM items").use { it.moveToFirst();it.getInt(0) })
    }
    @Test fun optionalVodFailureKeepsExistingVodWhileUpdatingLive()=runBlocking {
        movies="[{\"stream_id\":2,\"name\":\"Keep this movie\"}]";connect()
        val source=repo.dao.allSources().single();failMovies=true
        live="[{\"stream_id\":1,\"name\":\"Updated channel\"}]"
        val result=connect(source.id)
        assertTrue(result.warnings.any { it.contains("Movies could not") })
        assertEquals("Keep this movie",repo.browse(Settings(),"Movies","","",200,null).first().single().item.name)
        assertEquals("Updated channel",repo.browse(Settings(),"Live TV","","",200,null).first().single().item.name)
    }
    @Test fun pendingRowsStayInvisibleAndAreRemovedAfterInterruptedImport()=runBlocking {
        val source=SourceEntity("pending:test","", "", "")
        repo.dao.putItems(listOf(ItemEntity("pending-id",source.id,"Hidden","LIVE","Hidden category","","","","","",0,"",0,0,"","","",1)))
        assertTrue(repo.browse(Settings(),"Search","","",200,null).first().isEmpty())
        assertTrue(repo.dao.groups("").first().isEmpty())
        repo.initialize()
        assertNull(repo.dao.item("pending-id"))
    }
    @Test fun successfulRefreshKeepsStableIdsAndSavedProgress()=runBlocking {
        connect();val source=repo.dao.allSources().single()
        val old=repo.browse(Settings(),"Live TV","","",200,null).first().single().item
        repo.progress("default",old.id,12345,30000);repo.favorite("default",old.id)
        live="[{\"stream_id\":1,\"name\":\"Renamed channel\"}]"
        connect(source.id)
        assertEquals("Renamed channel",repo.dao.item(old.id)!!.name)
        assertEquals(12345,repo.dao.watch("default",old.id)!!.position)
        assertTrue(repo.dao.watch("default",old.id)!!.favorite)
    }
    @Test fun parserConsumesLargeGeneratedCatalogIncrementally()=runBlocking {
        var seen=0
        val input=GeneratedCatalogReader(20000,2048)
        val count=XtreamStreamParser.items(input,MediaKind.LIVE,"https://example.test","u","p",emptyMap()) {
            seen++
            if(seen==1) assertTrue("Must emit before reading the complete response",input.emitted<10000)
        }
        assertEquals(20000,count);assertEquals(20000,seen)
        assertTrue(input.emitted>40_000_000)
    }
    @Test fun parserAcceptsEmptyOptionalCatalogShapesAndSkipsBadRows()=runBlocking {
        for(json in listOf("[]","{}","null")) assertEquals(0,XtreamStreamParser.items(json.reader(),MediaKind.MOVIE,"https://example.test","u","p",emptyMap()) { fail() })
        val items=mutableListOf<ImportedItem>()
        XtreamStreamParser.items("[null,4,{}, {\"stream_id\":7,\"name\":\"ok\",\"stream_icon\":null,\"unexpected\":{\"nested\":[1,2]}}]".reader(),MediaKind.LIVE,"https://example.test","u","p",emptyMap()) { items+=it }
        assertEquals("ok",items.single().name);assertEquals("",items.single().logo)
    }
    @Test fun stableHashEncodingMatchesPreviousIdentifiers() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",stableId("abc"))
    }
}

/** Synthesizes >40MB without retaining the complete input or result list in the test. */
class GeneratedCatalogReader(private val total: Int,private val padding: Int): Reader() {
    var emitted=0L;private set
    private var entry=0;private var chunk="[";private var offset=0;private var ended=false
    override fun read(target: CharArray,start: Int,length: Int): Int {
        if(length==0) return 0
        if(offset==chunk.length) {
            if(ended) return -1
            if(entry==total) { chunk="]";ended=true } else {
                chunk=(if(entry++==0) "" else ",")+"{\"stream_id\":$entry,\"name\":\"Channel $entry\",\"ignored_metadata\":\""+"x".repeat(padding)+"\"}"
            };offset=0
        }
        val size=minOf(length,chunk.length-offset)
        chunk.toCharArray(target,start,offset,offset+size);offset+=size;emitted+=size;return size
    }
    override fun close() { }
}
