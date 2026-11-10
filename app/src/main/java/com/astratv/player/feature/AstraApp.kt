@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.astratv.player.feature

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.astratv.player.BuildConfig
import com.astratv.player.core.database.*
import com.astratv.player.core.ui.*
import com.astratv.player.feature.epg.GuideScreen
import com.astratv.player.feature.player.PlayerScreen
import com.astratv.player.feature.updates.UpdatesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val destinations=listOf("Live TV","Guide","Movies","Series","Catch-up","Search","Favorites","History","Continue watching","Playlists","Settings","Profiles","About & updates")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AstraApp(vm: MainViewModel) {
    val ready by vm.ready.collectAsStateWithLifecycle()
    if(!ready) { Box(Modifier.fillMaxSize().background(Background),contentAlignment=Alignment.Center) { CircularProgressIndicator(color=Accent) };return }

    val section by vm.section.collectAsStateWithLifecycle();val sources by vm.sources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle();val rows by vm.rows.collectAsStateWithLifecycle()
    val profile by vm.activeProfile.collectAsStateWithLifecycle();val groups by vm.groups.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle();val detail by vm.detail.collectAsStateWithLifecycle()
    var import by remember { mutableStateOf(false) };var management by remember { mutableStateOf(false) }
    LaunchedEffect(settings.profile,section) { if(section !in listOf("Playlists","Settings","Profiles","About & updates")) management=false }
    LaunchedEffect(settings.profile) { management=false }
    var gateHash by remember { mutableStateOf<String?>(null) };var gateAction by remember { mutableStateOf<()->Unit>({}) }
    val limit by vm.limit.collectAsStateWithLifecycle()
    val listState=rememberLazyListState();val gridState=rememberLazyGridState()
    val navigationState=rememberLazyListState()
    fun protected(action: ()->Unit) { if(settings.ownerPin.isNotEmpty() && !management) { gateHash=settings.ownerPin;gateAction={ management=true;action() } } else action() }
    BackHandler(enabled=playing==null && detail!=null) { vm.detail.value=null }
    BackHandler(enabled=playing==null && detail==null && section!="Live TV") { vm.navigate("Live TV");management=false }
    if(playing!=null) { PlayerScreen(vm);return }
    Row(Modifier.fillMaxSize().background(Background).padding(32.dp),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(158.dp).fillMaxHeight()) {
            Text("Astra TV",style=MaterialTheme.typography.headlineSmall,color=Ink)
            Text(profile?.name ?: "Home",style=MaterialTheme.typography.bodyMedium,color=Muted,modifier=Modifier.padding(top=6.dp,bottom=24.dp))
            LazyColumn(state=navigationState,modifier=Modifier.fillMaxSize().focusRestorer().focusGroup(),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                items(destinations,key={it}) { destination ->
                    TvAction(destination,{
                        if(destination in listOf("Playlists","Settings","Profiles","About & updates")) protected { vm.navigate(destination) }
                        else { vm.navigate(destination);management=false }
                    },Modifier.fillMaxWidth(),section==destination)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(bottom=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if(sources.isEmpty() && section=="Live TV") "Your television, together." else section,style=MaterialTheme.typography.headlineMedium,color=Ink)
                if(busy) CircularProgressIndicator(Modifier.size(28.dp),color=Accent,strokeWidth=3.dp)
            }
            if(message!=null) {
                Row(Modifier.fillMaxWidth().background(Elevated,RoundedCornerShape(12.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(message!!,Modifier.weight(1f),color=Ink,style=MaterialTheme.typography.bodyMedium)
                    TvAction("Dismiss",{ vm.message.value=null })
                };Spacer(Modifier.height(14.dp))
            }
            when(section) {
                "Playlists" -> PlaylistScreen(vm,{ import=true })
                "Settings" -> SettingsScreen(vm)
                "Profiles" -> ProfilesScreen(vm) { hash,action -> gateHash=hash;gateAction=action }
                "About & updates" -> UpdatesScreen(vm)
                else -> if(sources.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.Center) {
                        Text("Bring your own channels.",style=MaterialTheme.typography.headlineLarge,color=Ink)
                        Text("Connect an M3U playlist or Xtream-compatible provider to begin. Astra TV supplies no channels or subscriptions.",style=MaterialTheme.typography.bodyLarge,color=Muted,modifier=Modifier.widthIn(max=540.dp).padding(vertical=20.dp))
                        TvAction("Add a playlist",{ protected { import=true } })
                    }
                } else {
                    if(!settings.merged) {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(bottom=12.dp)) { items(sources,key={it.id}) { source ->
                            TvAction(source.name,{ vm.string("source",source.id) },selected=settings.source==source.id)
                        } }
                    }
                    if(section=="Search") Field("Search channels, titles and categories",vm.query.collectAsStateWithLifecycle().value,{ vm.query.value=it },modifier=Modifier.fillMaxWidth().padding(bottom=14.dp))
                    if(section in listOf("Guide","Catch-up")) GuideScreen(vm,section=="Catch-up")
                    else Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                        if(section in listOf("Live TV","Movies","Series")) CategoryList(groups,profile,vm)
                        Column(Modifier.weight(1f)) {
                            if(rows.isEmpty()) EmptyLibrary(section)
                            else if(section in listOf("Movies","Series")) {
                                LazyVerticalGrid(columns=GridCells.Adaptive(155.dp),state=gridState,modifier=Modifier.weight(1f).focusRestorer().focusGroup(),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(6.dp)) {
                                    items(rows,key={it.item.id}) { row -> Poster(vm,row) }
                                    if(rows.size>=limit) item { TvAction("Load more",{ vm.limit.value+=200 }) }
                                }
                            } else LazyColumn(state=listState,modifier=Modifier.weight(1f).focusRestorer().focusGroup(),verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(5.dp)) {
                                items(rows,key={it.item.id}) { row -> MediaListRow(vm,row) }
                                if(rows.size>=vm.limit.value) item { TvAction("Load more",{ vm.limit.value+=200 },Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                }
            }
        }
    }
    if(import) ImportDialog(vm) { import=false }
    if(detail!=null) DetailDialog(vm,detail!!) { vm.detail.value=null }
    if(gateHash!=null) PinDialog("Enter PIN",{ pin -> vm.verifyPin(pin,gateHash!!) { gateHash=null;gateAction() } },{ gateHash=null })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun CategoryList(groups: List<String>,profile: ProfileEntity?,vm: MainViewModel) {
    val current by vm.category.collectAsStateWithLifecycle()
    val hidden=remember(profile) { val a=JSONArray(profile?.restrictedGroups ?: "[]");(0 until a.length()).map { a.getString(it) }.toSet() }
    LazyColumn(Modifier.width(154.dp).fillMaxHeight().focusRestorer().focusGroup(),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(4.dp)) {
        item { TvAction("All categories",{ vm.category.value="";vm.limit.value=200 },Modifier.fillMaxWidth(),current.isEmpty()) }
        items(groups.filterNot { it in hidden },key={it}) { group -> TvAction(group,{ vm.category.value=group;vm.limit.value=200 },Modifier.fillMaxWidth(),current==group) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun EmptyLibrary(section: String) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center) {
        Text(when(section) { "Favorites" -> "Keep your favorites close.";"History" -> "Nothing watched yet.";"Continue watching" -> "You’re all caught up.";else -> "No matching items." },style=MaterialTheme.typography.headlineSmall,color=Ink)
        Text(when(section) { "Favorites" -> "Choose Save beside a channel, movie or series.";"Search" -> "Try a different title or category.";else -> "Your configured sources and profile determine what appears here." },color=Muted,modifier=Modifier.padding(top=12.dp))
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun Artwork(vm: MainViewModel,item: ItemEntity,modifier: Modifier) {
    val logo by produceState("",item.logoCipher) { value=withContext(Dispatchers.IO) { runCatching { vm.repo.secrets.open(item.logoCipher) }.getOrDefault("") } }
    Box(modifier.background(Elevated,RoundedCornerShape(8.dp)),contentAlignment=Alignment.Center) {
        if(logo.startsWith("https://")) AsyncImage(model=logo,contentDescription=null,modifier=Modifier.fillMaxSize().padding(6.dp))
        else Text(item.name.take(2).uppercase(),color=Muted,style=MaterialTheme.typography.titleLarge)
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MediaListRow(vm: MainViewModel,row: MediaRow) {
    val restore=remember(row.item.id) { FocusRequester() }
    LaunchedEffect(row.item.id) { if(vm.focusedItemId.value==row.item.id) runCatching { restore.requestFocus() } }

    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        TvAction(row.item.name,{ if(row.item.kind=="LIVE") vm.play(row.item) else vm.showDetail(row) },Modifier.weight(1f).focusRequester(restore).onFocusChanged { if(it.hasFocus) vm.focusedItemId.value=row.item.id }) {
            Artwork(vm,row.item,Modifier.size(48.dp));Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(row.item.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                Text(row.item.groupName+(if(row.watched) " · Watched" else if(row.position>0) " · Resume ${(row.position/60000)} min" else ""),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
            }
        }
        TvAction(if(row.favorite) "Saved" else "Save",{ vm.favorite(row.item) },Modifier.width(82.dp),row.favorite)
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Poster(vm: MainViewModel,row: MediaRow) {
    val restore=remember(row.item.id) { FocusRequester() }
    LaunchedEffect(row.item.id) { if(vm.focusedItemId.value==row.item.id) runCatching { restore.requestFocus() } }
    TvAction(row.item.name,{ vm.showDetail(row) },Modifier.fillMaxWidth().focusRequester(restore).onFocusChanged { if(it.hasFocus) vm.focusedItemId.value=row.item.id }) {
        Column {
            Artwork(vm,row.item,Modifier.fillMaxWidth().aspectRatio(2f/3f))
            Text(row.item.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=10.dp))
            if(row.position>0) Text(if(row.watched) "Watched" else "Resume ${row.position/60000} min",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun Field(label: String,value: String,onChange: (String)->Unit,modifier: Modifier=Modifier,password: Boolean=false,numeric: Boolean=false) {
    OutlinedTextField(value,onChange,modifier=modifier,label={ Text(label) },singleLine=true,
        visualTransformation=if(password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.NumberPassword else if(password) KeyboardType.Password else KeyboardType.Text),
        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Accent,unfocusedBorderColor=Outline,focusedTextColor=Ink,unfocusedTextColor=Ink))
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PanelDialog(title: String,onDismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss) {
        Column(Modifier.widthIn(max=680.dp).fillMaxWidth().heightIn(max=480.dp).background(Background,RoundedCornerShape(16.dp)).border(1.dp,Outline,RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text(title,style=MaterialTheme.typography.headlineSmall,color=Ink,modifier=Modifier.padding(bottom=16.dp))
            content()
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PinDialog(title: String,onSubmit: (String)->Unit,onDismiss: ()->Unit) {
    var pin by remember { mutableStateOf("") }
    PanelDialog(title,onDismiss) {
        Field("PIN",pin,{ pin=it.take(8) },Modifier.fillMaxWidth(),password=true,numeric=true)
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Continue",{ onSubmit(pin);pin="" },enabled=pin.length>=4);TvAction("Cancel",onDismiss) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ImportDialog(vm: MainViewModel,onDismiss: ()->Unit) {
    var mode by remember { mutableStateOf("M3U URL") };var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") };var user by remember { mutableStateOf("") };var password by remember { mutableStateOf("") }
    val context=LocalContext.current
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        vm.importLocal(name,uri);onDismiss()
    } }
    PanelDialog("Add playlist",onDismiss) {
        Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("M3U URL","Local file","Xtream").forEach { choice -> TvAction(choice,{ mode=choice },selected=mode==choice) } }
            Field("Playlist name",name,{ name=it },Modifier.fillMaxWidth())
            if(mode!="Local file") Field(if(mode=="Xtream") "Server (https://host:port)" else "Playlist URL",url,{ url=it },Modifier.fillMaxWidth(),password=mode=="M3U URL")
            if(mode=="Xtream") { Field("Username",user,{ user=it },Modifier.fillMaxWidth());Field("Password",password,{ password=it },Modifier.fillMaxWidth(),password=true) }
            Text("Use a source you are authorized to access. Provider addresses and credentials are encrypted on this device.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TvAction(if(mode=="Local file") "Choose M3U file" else "Import",{
                    when(mode) { "Local file" -> runCatching { file.launch(arrayOf("*/*")) }.onFailure { vm.message.value="No file picker is available. Install a TV file manager or import an M3U URL.";onDismiss() };"Xtream" -> { vm.importProvider(name,url,user,password);onDismiss() };else -> { vm.importUrl(name,url);onDismiss() } }
                },enabled=mode=="Local file" || (url.isNotBlank() && (mode!="Xtream" || (user.isNotBlank() && password.isNotBlank()))))
                TvAction("Cancel",onDismiss)
            }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlaylistScreen(vm: MainViewModel,add: ()->Unit) {
    val sources by vm.sources.collectAsStateWithLifecycle();val settings by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SourceEntity?>(null) };var remove by remember { mutableStateOf<SourceEntity?>(null) }
    var guide by remember { mutableStateOf<SourceEntity?>(null) };var rename by remember { mutableStateOf("") };var guideUrl by remember { mutableStateOf("") }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(4.dp)) {
        item { TvAction("Add playlist",add) }
        items(sources,key={it.id}) { source ->
            Column(Modifier.fillMaxWidth().background(Surface,RoundedCornerShape(12.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(source.name,style=MaterialTheme.typography.titleLarge,color=Ink)
                Text("${source.type} · Refreshed ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(source.updated))}",color=Muted,style=MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    item { TvAction("Refresh",{ vm.refresh(source) }) };item { TvAction("Rename",{ rename=source.name;editing=source }) }
                    item { TvAction("XMLTV guide",{ guideUrl="";guide=source }) };item { TvAction("Remove",{ remove=source }) }
                    item { TvAction("Use separately",{ vm.bool("merged",false);vm.string("source",source.id);vm.navigate("Live TV") }) }
                }
            }
        }
        item { TvAction(if(settings.merged) "Merged library: on" else "Merged library: off",{ vm.bool("merged",!settings.merged) }) }
    }
    if(editing!=null) PanelDialog("Rename playlist",{ editing=null }) {
        Field("Name",rename,{ rename=it },Modifier.fillMaxWidth());Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Save",{ vm.rename(editing!!,rename);editing=null });TvAction("Cancel",{ editing=null }) }
    }
    if(guide!=null) PanelDialog("Import XMLTV guide",{ guide=null }) {
        Field("XMLTV URL (blank uses provider guide)",guideUrl,{ guideUrl=it },Modifier.fillMaxWidth(),password=true)
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Import",{
            val source=guide!!;val entered=guideUrl;guide=null;vm.task {
                val address=entered.ifBlank { withContext(Dispatchers.IO) { vm.repo.secrets.open(source.guideCipher) } }
                val n=vm.repo.refreshGuide(source,address,settings.allowHttp);vm.message.value="Imported $n programs."
            }
        });TvAction("Cancel",{ guide=null }) }
    }
    if(remove!=null) PanelDialog("Remove ${remove!!.name}?",{ remove=null }) {
        Text("Its channels and programs will be removed from this library.",color=Muted)
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Remove playlist",{ vm.delete(remove!!);remove=null });TvAction("Cancel",{ remove=null }) }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DetailDialog(vm: MainViewModel,row: MediaRow,onDismiss: ()->Unit) {
    val episodes by vm.episodes.collectAsStateWithLifecycle();var season by remember(row.item.id) { mutableStateOf(0) }
    PanelDialog(row.item.name,onDismiss) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(row.item.description.ifBlank { "No description supplied by this provider." },color=Muted);Text(row.item.groupName,color=Muted,modifier=Modifier.padding(top=8.dp)) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(row.item.kind!="SERIES") { TvAction(if(row.position>0 && !row.watched) "Resume" else "Play",{ vm.play(row.item);onDismiss() });if(row.position>0) TvAction("Start over",{ vm.play(row.item,startOver=true);onDismiss() }) }
                TvAction("Toggle favorite",{ vm.favorite(row.item) });TvAction("Close",onDismiss)
            } }
            if(row.item.kind=="SERIES") {
                item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(episodes.map { it.season }.distinct()) { s -> TvAction("Season $s",{ season=s },selected=season==s) } } }
                if(episodes.isEmpty()) item { Text("No episodes loaded. Provider errors appear in the library message area.",color=Muted) }
                items(episodes.filter { season==0 || it.season==season },key={it.id}) { episode ->
                    val watch by produceState<WatchEntity?>(null,episode.id) { value=vm.repo.dao.watch(vm.settings.value.profile,episode.id) }
                    TvAction("${episode.season} · ${episode.episode}  ${episode.name}"+(if(watch?.watched==true) " · Watched" else if((watch?.position ?: 0)>0) " · Resume" else ""),{ vm.play(episode);onDismiss() },Modifier.fillMaxWidth())
                }
            }
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle();var pin by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(4.dp)) {
        item { Text("Playback & sources",style=MaterialTheme.typography.titleLarge,color=Ink) }
        item { TvAction("HTTP sources: ${if(s.allowHttp) "allowed" else "blocked"}",{ vm.bool("http",!s.allowHttp) },Modifier.fillMaxWidth()) }
        item { Text("Enable only if your provider requires unencrypted HTTP. Logos remain HTTPS-only.",color=Muted,style=MaterialTheme.typography.bodySmall) }
        item { TvAction("Aspect ratio: ${listOf("Fit","Fill","Zoom")[s.aspect.coerceIn(0,2)]}",{ vm.aspect((s.aspect+1)%3) },Modifier.fillMaxWidth()) }
        item { TvAction("Guide refresh every 12 hours: ${if(s.guideRefresh) "on" else "off"}",{ vm.bool("guideRefresh",!s.guideRefresh) },Modifier.fillMaxWidth()) }
        item { Text("Library & interface",style=MaterialTheme.typography.titleLarge,color=Ink,modifier=Modifier.padding(top=12.dp)) }
        item { TvAction("Merged library: ${if(s.merged) "on" else "off"}",{ vm.bool("merged",!s.merged) },Modifier.fillMaxWidth()) }
        item { TvAction("Sort: ${s.sort}",{ vm.string("sort",if(s.sort=="Name") "Recently refreshed" else "Name") },Modifier.fillMaxWidth()) }
        item { TvAction(if(s.ownerPin.isEmpty()) "Set management PIN" else "Change management PIN",{ pin=true },Modifier.fillMaxWidth()) }
        if(s.ownerPin.isNotEmpty()) item { TvAction("Remove management PIN",{ vm.string("ownerPin","") },Modifier.fillMaxWidth()) }
        item { Text("The management PIN protects playlist changes, settings, profiles and update configuration. Use a restricted profile for children; set this PIN before handing over the remote.",color=Muted) }
    }
    if(pin) PinDialog("Set management PIN (4–8 digits)",{ vm.setOwnerPin(it);pin=false },{ pin=false })
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ProfilesScreen(vm: MainViewModel,gate: (String,()->Unit)->Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle();val settings by vm.settings.collectAsStateWithLifecycle();val groups by vm.groups.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf(false) };var editing by remember { mutableStateOf<ProfileEntity?>(null) };var name by remember { mutableStateOf("") };var pin by remember { mutableStateOf("") };var restricted by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    fun edit(profile: ProfileEntity?) { editing=profile;name=profile?.name.orEmpty();pin="";val a=JSONArray(profile?.restrictedGroups ?: "[]");restricted=(0 until a.length()).map { a.getString(it) }.toSet();editor=true }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(4.dp)) {
        item { TvAction("Add local profile",{ edit(null) }) }
        items(profiles,key={it.id}) { profile ->
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                TvAction(profile.name+(if(settings.profile==profile.id) " · Active" else ""),{
                    val switch={ vm.string("profile",profile.id);vm.navigate("Live TV") }
                    if(profile.pinHash.isNotEmpty()) gate(profile.pinHash,switch) else switch()
                },Modifier.weight(1f))
                TvAction("Edit",{ edit(profile) })
                if(profile.id!="default") TvAction("Delete",{ confirmDelete=profile })
            }
        }
    }
    if(editor) PanelDialog("${if(editing==null) "New" else "Edit"} profile",{ editor=false }) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { Field("Profile name",name,{ name=it },Modifier.fillMaxWidth()) }
            item { Field("New entry PIN (blank preserves existing)",pin,{ pin=it.take(8) },Modifier.fillMaxWidth(),password=true,numeric=true) }
            item { Text("Hide categories in this profile",color=Muted) }
            items(groups,key={it}) { group -> TvAction((if(group in restricted) "Hidden: " else "Visible: ")+group,{ restricted=if(group in restricted) restricted-group else restricted+group },Modifier.fillMaxWidth(),group in restricted) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Save profile",{ vm.saveProfile(editing,name,pin,restricted);editor=false });TvAction("Cancel",{ editor=false }) } }
        }
    }
    if(confirmDelete!=null) PanelDialog("Delete ${confirmDelete!!.name}?",{ confirmDelete=null }) {
        Text("This removes its favorites and history.",color=Muted)
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Delete profile",{ vm.removeProfile(confirmDelete!!);confirmDelete=null });TvAction("Cancel",{ confirmDelete=null }) }
    }
}
