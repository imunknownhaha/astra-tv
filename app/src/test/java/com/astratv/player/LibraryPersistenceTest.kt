package com.astratv.player

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.astratv.player.core.database.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class LibraryPersistenceTest {
    private lateinit var db: LibraryDatabase
    private lateinit var context: Context
    @Before fun setup() { context=ApplicationProvider.getApplicationContext();db=Room.databaseBuilder(context,LibraryDatabase::class.java,"test-${System.nanoTime()}.db").allowMainThreadQueries().build() }
    @After fun cleanup() { val name=db.openHelper.databaseName;db.close();if(name!=null) context.deleteDatabase(name) }
    private fun item(generation: Long=1)=ItemEntity("channel","source","News","LIVE","News","cipher","","news","","",0,"",0,0,"","","",generation)
    @Test fun sourceRefreshPreservesFavoritesAndProgressAcrossReopen()=runTest {
        val dao=db.library();dao.putSource(SourceEntity("source","Playlist","M3U","encrypted"));dao.putItems(listOf(item()))
        dao.putWatch(WatchEntity("child","channel",true,120000,300000,100,false))
        dao.putItems(listOf(item(2).copy(name="Updated News")));dao.prune("source",2)
        assertTrue(dao.watch("child","channel")!!.favorite);assertEquals(120000,dao.watch("child","channel")!!.position)
        assertNull(dao.watch("other","channel"));assertEquals("Updated News",dao.item("channel")!!.name)
        val name=db.openHelper.databaseName!!;db.close();db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).allowMainThreadQueries().build()
        assertEquals(120000,db.library().watch("child","channel")!!.position)
    }
    @Test fun pruneRemovesStaleItemsAndGuideQueriesAreScopedBySourceAndWindow()=runTest {
        val dao=db.library();dao.putItems(listOf(item(1),item(2).copy(id="new")));dao.prune("source",2)
        assertNull(dao.item("channel"));assertNotNull(dao.item("new"))
        dao.putPrograms(listOf(ProgramEntity("source","news","Current",100,200,""),ProgramEntity("other","news","Other",100,200,"")))
        assertEquals("Current",dao.guide("source","news",150,300).first().single().title)
        assertTrue(dao.guide("source","news",200,300).first().isEmpty())
    }
    @Test fun favoriteAndHistoryQueriesRemainProfileSpecific()=runTest {
        val dao=db.library();dao.putItems(listOf(item()));dao.putWatch(WatchEntity("parent","channel",true,0,0,100))
        fun query(profile: String)=SimpleSQLiteQuery("SELECT i.*,COALESCE(w.favorite,0) AS favorite,COALESCE(w.position,0) AS position,COALESCE(w.duration,0) AS duration,COALESCE(w.lastPlayed,0) AS lastPlayed,COALESCE(w.watched,0) AS watched FROM items i LEFT JOIN watch w ON i.id=w.itemId AND w.profileId=?",arrayOf(profile))
        assertTrue(dao.browse(query("parent")).first().single().favorite)
        assertFalse(dao.browse(query("child")).first().single().favorite)
    }
    @Test fun restrictedParentCategoryAlsoHidesPreviouslyCachedEpisodes()=runTest {
        val dao=db.library()
        dao.putSource(SourceEntity("source","Test","M3U","cipher"))
        val parent=item().copy(id="series",kind="SERIES",groupName="Restricted")
        val child=item().copy(id="episode",kind="EPISODE",groupName="Season 1",parentId="series")
        dao.putItems(listOf(parent,child))
        val repo=LibraryRepository(db,com.astratv.player.core.security.SecretStore(),com.astratv.player.core.network.NetworkClient(),context)
        val profile=ProfileEntity("child","Child",restrictedGroups="[\"Restricted\"]")
        assertTrue(repo.browse(com.astratv.player.core.datastore.Settings(profile="child"),"Search","","",200,profile).first().isEmpty())
    }

}
