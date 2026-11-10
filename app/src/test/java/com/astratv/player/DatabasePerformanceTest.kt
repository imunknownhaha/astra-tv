package com.astratv.player

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.astratv.player.core.database.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Host SQL measurements, not TV frame-rate benchmarks. Fixed synthetic 20k-channel workload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class DatabasePerformanceTest {
    @Test fun recordLargeLibraryBrowsePlanAndTimings()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.library();dao.putSource(SourceEntity("source","Synthetic","M3U",""))
            repeat(20) { batch -> dao.putItems((0 until 1000).map { n ->
                val i=20000-(batch*1000+n)
                ItemEntity("item-$i","source","Channel ${i.toString().padStart(6,'0')}","LIVE","Group ${i%20}","cipher","","","","",0,"",0,0,"","","",1)
            }) }
            val sql=System.getProperty("astra.perfSort", "i.sortName,i.id")
            val query="SELECT i.*,COALESCE(w.favorite,0) AS favorite,COALESCE(w.position,0) AS position,COALESCE(w.duration,0) AS duration,COALESCE(w.lastPlayed,0) AS lastPlayed,COALESCE(w.watched,0) AS watched FROM items i LEFT JOIN watch w ON w.itemId=i.id AND w.profileId='default' WHERE i.sourceId IN (SELECT id FROM sources) AND i.kind='LIVE' ORDER BY $sql LIMIT 200"
            val cleanupPlan=mutableListOf<String>()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN DELETE FROM items WHERE sourceId GLOB 'pending:*'").use { c -> while(c.moveToNext()) cleanupPlan+=c.getString(3) }
            org.junit.Assert.assertTrue("Startup cleanup must use the source index",cleanupPlan.any { it.contains("index_items_sourceId") && it.contains("SEARCH") })
            val plans=mutableListOf<String>()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $query").use { c -> while(c.moveToNext()) plans+=c.getString(3) }
            fun sample(): Double {
                val start=System.nanoTime()
                db.openHelper.readableDatabase.query(query).use { c -> while(c.moveToNext()) { c.getString(c.getColumnIndexOrThrow("name")) } }
                return (System.nanoTime()-start)/1_000_000.0
            }
            repeat(3) { sample() }
            org.junit.Assert.assertFalse("Browsing must not sort the entire catalog",plans.any { it.contains("TEMP B-TREE") })
            val times=List(9) { sample() }.sorted()
            val result=JSONObject().put("channels",20000).put("pageSize",200).put("medianHostQueryMs",times[4]).put("plan",plans).put("sort",sql).put("startupCleanupPlan",cleanupPlan)
            val report=File("build/reports/performance/database.json");report.parentFile!!.mkdirs();report.writeText(result.toString(2))
            println("ASTRA_SQL_PERFORMANCE: $result")
        } finally { db.close() }
    }
}
