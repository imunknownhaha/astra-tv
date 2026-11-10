package com.astratv.player

import com.astratv.player.core.model.*
import com.astratv.player.core.network.*
import com.astratv.player.core.player.RetryPolicy
import com.astratv.player.core.security.PinHasher
import com.astratv.player.feature.updates.UpdateRepository
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ParserTest {
    @Test fun m3uReadsQuotedCommaMetadataRelativeStreamsAndDeduplicates() {
        val parsed=M3uParser.parse("""#EXTM3U x-tvg-url="https://example.test/guide.xml"
#EXTINF:-1 tvg-id="news.1" tvg-logo="https://example.test/logo.png" group-title="News, World",News, International
#EXTVLCOPT:http-user-agent=Astra Test
streams/news.m3u8
#EXTINF:-1 tvg-id="news.1" group-title="Another group",Duplicate
streams/news.m3u8
#EXTINF:-1 tvg-id="news.1" group-title="News",Alternate
streams/alternate.m3u8
""".reader(),"https://example.test/list.m3u")
        assertEquals(2,parsed.items.size);assertEquals("News, International",parsed.items.first().name)
        assertEquals("News, World",parsed.items.first().group);assertEquals("Astra Test",parsed.items.first().userAgent)
        assertEquals("https://example.test/streams/news.m3u8",parsed.items.first().url)
        assertEquals("https://example.test/guide.xml",parsed.guideUrl)
    }
    @Test fun refreshIdentitySurvivesMetadataChange() {
        val a=M3uParser.parse("#EXTM3U\n#EXTINF:-1,Old\nhttps://example.test/a".reader()).items.single()
        val b=M3uParser.parse("#EXTM3U\n#EXTINF:-1,New\nhttps://example.test/a".reader()).items.single()
        assertEquals(a.key,b.key)
    }
    @Test fun m3uHandlesBomAndMalformedRowsWithoutDroppingValidStreams() {
        val p=M3uParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1,Bad\nfile:///etc/passwd\n#EXTINF:-1,Good\nhttps://example.test/good".reader())
        assertEquals(1,p.skipped);assertEquals("Good",p.items.single().name)
    }
    @Test fun m3uRejectsEmptyInvalidAndHlsMediaPlaylists() {
        listOf("", "https://example.test/a", "#EXTM3U\n#EXT-X-TARGETDURATION:10\nsegment.ts", "#EXTM3U\n#EXTINF:-1,Test\nhttp://example.test/a").forEach {
            assertThrows(ImportFailure::class.java) { M3uParser.parse(it.reader()) }
        }
    }
    @Test fun httpRequiresExplicitOptInAndUnsupportedSchemesAlwaysFail() {
        assertThrows(ImportFailure::class.java) { UrlPolicy.validate("http://example.test/a") }
        assertEquals("http://example.test/a",UrlPolicy.validate("http://example.test/a",true))
        listOf("javascript:alert(1)","file:///tmp/a","https://user:secret@example.test/a","https://", "https://example.test/\n").take(4).forEach {
            assertThrows(ImportFailure::class.java) { UrlPolicy.validate(it,true) }
        }
    }
    @Test fun xmltvUsesOffsetsAndKeepsProgramDescriptions() {
        val now=Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        val data="""<?xml version="1.0"?><tv><programme channel="news.1" start="20260101120000 +0000" stop="20260101140000 +0100"><title>News &amp; Weather</title><desc>Local bulletin</desc></programme><programme channel="x" start="bad" stop="bad"><title>Invalid</title></programme></tv>"""
        val result=XmlTvParser.parse(data.reader(),now)
        assertEquals(1,result.size);assertEquals(now,result.single().start);assertEquals(now+3600000,result.single().end)
        assertEquals("News & Weather",result.single().title);assertEquals("Local bulletin",result.single().description)
        assertEquals(now,XmlTvParser.timestamp("20260101173000 +0530"))
    }
    @Test fun xmltvRejectsDtdAndExternalEntityDeclarations() {
        val data="""<!DOCTYPE tv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><tv><programme channel="x" start="20260101120000 +0000" stop="20260101130000 +0000"><title>&xxe;</title></programme></tv>"""
        assertThrows(Exception::class.java) { XmlTvParser.parse(data.reader(),0) }
    }
    @Test fun xtreamHandlesCategoryIdsNullsAndArchiveCapabilities() {
        val cats=XtreamParser.categories("""[{"category_id":"3","category_name":"News"}]""")
        val rows=XtreamParser.items("""[{"stream_id":12,"name":"News HD","category_id":"3","stream_icon":null,"epg_channel_id":"news","tv_archive":1,"tv_archive_duration":3},{"name":"Missing ID"}]""",MediaKind.LIVE,"https://provider.test","a b","p&x",cats)
        assertEquals(1,rows.size);assertEquals("News",rows[0].group);assertEquals(3,rows[0].catchupDays)
        assertEquals("",rows[0].logo);assertEquals("https://provider.test/live/a%20b/p%26x/12.ts",rows[0].url)
    }
    @Test fun xtreamSeriesReturnsOrderedPlayableEpisodesAndOnlyProviderMetadata() {
        val json="""{"episodes":{"2":[{"id":"44","title":"Episode B","episode_num":2,"container_extension":"mkv","info":{"plot":"Supplied plot"}}],"1":[{"id":"33","title":"Episode A","episode_num":1}]}}"""
        val e=XtreamParser.episodes(json,"22","https://provider.test","user","pass")
        assertEquals(listOf(1,2),e.map { it.season });assertEquals("",e[0].description);assertEquals("Supplied plot",e[1].description)
        assertEquals("SERIES:22",e[0].parentId);assertTrue(e[1].url.endsWith("44.mkv"))
    }
    @Test fun retryPolicyIsBoundedAndExponential() { assertEquals(listOf(1000L,2000L,4000L,null), (0..3).map { RetryPolicy.delayMs(it) }) }
    @Test fun pinHashUsesRandomSaltAndConstantVerification() {
        val a=PinHasher.create("1234");val b=PinHasher.create("1234")
        assertNotEquals(a,b);assertFalse(a.contains("1234"));assertTrue(PinHasher.verify("1234",a));assertFalse(PinHasher.verify("9999",a))
        assertFalse(PinHasher.verify("1234","broken"));assertThrows(IllegalArgumentException::class.java) { PinHasher.create("abc") }
    }
    @Test fun updatesCompareNumericSemverAndRejectPreviewTags() {
        assertTrue(UpdateRepository.newer("0.10.0","0.9.9"));assertFalse(UpdateRepository.newer("0.1.0","0.1.0"))
        assertFalse(UpdateRepository.newer("0.0.9","0.1.0"));assertFalse(UpdateRepository.newer("1.0.0-rc1","0.1.0"))
    }
}
