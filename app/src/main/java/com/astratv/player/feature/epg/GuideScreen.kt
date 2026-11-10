package com.astratv.player.feature.epg

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astratv.player.core.database.*
import com.astratv.player.core.ui.*
import com.astratv.player.feature.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun GuideScreen(vm: MainViewModel,catchup: Boolean) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val limit by vm.limit.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    var anchor by remember(catchup) { mutableLongStateOf((System.currentTimeMillis()/3600000)*3600000-(if(catchup) 3 else 1)*3600000) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selected by remember { mutableStateOf<Pair<ItemEntity,ProgramEntity>?>(null) }
    val scroll=rememberScrollState();val end=anchor+3*3600000
    LaunchedEffect(Unit) { while(true) { delay(60000);now=System.currentTimeMillis() } }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(4.dp)) {
            item { TvAction("Earlier",{ anchor-=3*3600000 }) };item { TvAction("Now",{ anchor=(System.currentTimeMillis()/3600000)*3600000-3600000 }) }
            item { TvAction("Later",{ anchor+=3*3600000 }) };item { Text(DateTimeFormatter.ofPattern("EEE d MMM · z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(anchor)),color=Muted,modifier=Modifier.padding(12.dp)) }
        }
        if(rows.isEmpty()) {
            Text(if(catchup) "No catch-up channels advertised by your provider." else "No live channels available.",color=Muted)
            if(catchup) Text("Astra TV only offers catch-up when the provider supplies an archive window. Import its XMLTV guide to select past programs.",color=Muted)
        } else Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Column(Modifier.width(1260.dp)) {
                Row(Modifier.height(40.dp)) {
                    Text("Channel",Modifier.width(180.dp).padding(8.dp),color=Muted)
                    repeat(6) { i -> Text(clock(anchor+i*1800000),Modifier.width(180.dp).padding(8.dp),color=Muted) }
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(5.dp)) {
                        items(rows,key={it.item.id}) { row ->
                            val item=row.item
                            val programs by remember(item.id,anchor) { vm.repo.dao.guide(item.sourceId,item.guideId,anchor,end) }.collectAsStateWithLifecycle(initialValue=emptyList())
                            Row(Modifier.height(72.dp),verticalAlignment=Alignment.CenterVertically) {
                                TvAction(item.name,{ vm.play(item) },Modifier.width(174.dp).fillMaxHeight())
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.width(1080.dp).fillMaxHeight().background(Surface)) {
                                    if(programs.isEmpty()) Text(if(item.guideId.isEmpty()) "No guide ID · Select channel to watch live" else "No guide data · Import XMLTV from Playlists",color=Muted,modifier=Modifier.padding(16.dp))
                                    programs.forEach { program ->
                                        val start=maxOf(program.start,anchor);val stop=minOf(program.end,end)
                                        val left=(start-anchor)/60000f*6f;val width=(stop-start)/60000f*6f
                                        TvAction(program.title,{ selected=item to program },Modifier.offset(x=left.dp).width(width.coerceAtLeast(12f).dp).fillMaxHeight(),selected=now in program.start until program.end)
                                    }
                                }
                            }
                        }
                        if(rows.size>=limit) item { TvAction("Load more channels",{ vm.limit.value+=200 }) }
                    }
                    if(now in anchor until end) Box(Modifier.offset(x=(180+(now-anchor)/60000f*6).dp).width(1.dp).fillMaxHeight().background(Accent))
                }
            }
        }
    }
    selected?.let { (item,program) ->
        PanelDialog(program.title,{ selected=null }) {
            Text("${clock(program.start)} – ${clock(program.end)} · ${item.name}",color=Accent)
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()).padding(vertical=16.dp)) { Text(program.description.ifBlank { "No description supplied by this provider." },color=Muted) }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TvAction("Watch live",{ vm.play(item);selected=null })
                val source=sources.find { it.id==item.sourceId }
                val templateSupported=source?.type=="XTREAM" || item.catchupCipher.isNotEmpty()
                if(program.start<now && item.catchupDays>0 && program.start>=now-item.catchupDays*86400000L && templateSupported)
                    TvAction("Watch catch-up",{ vm.playCatchup(item,program);selected=null })
                TvAction("Close",{ selected=null })
            }
        }
    }
}
private fun clock(value: Long)=DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
