package com.astratv.player.feature

import android.app.Application
import android.net.Uri
import androidx.room.withTransaction
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.astratv.player.AstraApplication
import com.astratv.player.core.database.*
import com.astratv.player.core.datastore.Settings
import com.astratv.player.core.model.ImportFailure
import com.astratv.player.core.player.PlaybackEngine
import com.astratv.player.core.security.PinHasher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.util.UUID

@UnstableApi
class MainViewModel(application: Application): AndroidViewModel(application) {
    val app=application as AstraApplication
    val repo=app.library
    val settings=app.settings.flow.stateIn(viewModelScope,SharingStarted.Eagerly,Settings())
    val sources=repo.dao.sources().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val profiles=repo.dao.profiles().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val section=MutableStateFlow("Live TV");val category=MutableStateFlow("");val query=MutableStateFlow("");val limit=MutableStateFlow(200)
    val ready=MutableStateFlow(false)
    val busy=MutableStateFlow(false);val message=MutableStateFlow<String?>(null)
    val activeProfile=combine(profiles,settings) { p,s -> p.find { it.id==s.profile } }.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val groups=settings.flatMapLatest { repo.dao.groups(if(it.merged) "" else it.source) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    @OptIn(ExperimentalCoroutinesApi::class,FlowPreview::class)
    val rows=combine(combine(settings,section,category,query.debounce(180),limit) { s,sec,cat,q,l -> Browse(s,sec,cat,q,l) },activeProfile) { b,p -> b to p }
        .flatMapLatest { (b,p) -> if(p?.id!=b.settings.profile) flowOf(emptyList()) else repo.browse(b.settings,b.section,b.category,b.query,b.limit,p) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val engine=PlaybackEngine(app,app.network)
    val playing=MutableStateFlow<ItemEntity?>(null)
    val focusedItemId=MutableStateFlow("")
    val detail=MutableStateFlow<MediaRow?>(null)
    val episodes=MutableStateFlow<List<ItemEntity>>(emptyList())
    private var currentUrl: String?=null
    private var playbackProfile="default"
    var previousChannel: ItemEntity?=null;private set
    private var lastChannel: ItemEntity?=null
    private var pinFailures=0;private var pinBlockedUntil=0L
    init { viewModelScope.launch { repo.initialize();var saved=app.settings.flow.first();if(repo.dao.profile(saved.profile)==null) { app.settings.string("profile","default");saved=app.settings.flow.first() };settings.first { it==saved };profiles.first { list -> list.any { it.id==saved.profile } };activeProfile.first { it?.id==saved.profile };ready.value=true };viewModelScope.launch {
        while(isActive) { delay(10000);saveProgress() }
    } }
    private data class Browse(val settings: Settings,val section: String,val category: String,val query: String,val limit: Int)
    fun navigate(value: String) { section.value=value;category.value="";query.value="";limit.value=200 }
    fun task(block: suspend ()->Unit) { if(busy.value) return;viewModelScope.launch {
        busy.value=true
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) {
            message.value=if(e is ImportFailure || e is IllegalArgumentException) e.message else "The operation failed. Check your connection, source and file access, then retry."
        } finally { busy.value=false }
    } }
    fun importUrl(name: String,url: String) = task { val n=repo.importM3u(name,url,settings.value.allowHttp);message.value="Imported $n channels." }
    fun importLocal(name: String,uri: Uri) = task { val n=repo.importFile(name,uri,settings.value.allowHttp);message.value="Imported $n channels." }
    fun importProvider(name: String,server: String,user: String,password: String)=task { val n=repo.importXtream(name,server,user,password,settings.value.allowHttp);message.value="Imported $n items." }
    fun refresh(source: SourceEntity)=task { val n=repo.refresh(source,settings.value.allowHttp);message.value="Refreshed $n items." }
    fun guide(source: SourceEntity,url: String)=task { val n=repo.refreshGuide(source,url,settings.value.allowHttp);message.value="Imported $n programs." }
    fun delete(source: SourceEntity)=task { if(playing.value?.sourceId==source.id) stopPlayback();repo.delete(source) }
    fun rename(source: SourceEntity,name: String)=task { if(name.isBlank()) throw ImportFailure("Enter a playlist name.");repo.dao.putSource(source.copy(name=name.trim())) }
    fun favorite(item: ItemEntity) { viewModelScope.launch { repo.favorite(settings.value.profile,item.id) } }
    fun play(item: ItemEntity,url: String?=null,startOver: Boolean=false)=task { playNow(item,url,startOver) }
    private suspend fun playNow(item: ItemEntity,url: String?=null,startOver: Boolean=false) {
        val profile=activeProfile.value
        if(profile?.id!=settings.value.profile) throw ImportFailure("Profile is still loading. Try again.")
        val restricted=profile?.restrictedGroups?.let { JSONArray(it) }
        val parentGroup=if(item.parentId.isNotEmpty()) repo.dao.item(item.parentId)?.groupName else null
        if(restricted!=null && (0 until restricted.length()).any { restricted.getString(it)==item.groupName || restricted.getString(it)==parentGroup }) throw ImportFailure("This category is restricted in this profile.")
        if(item.kind=="SERIES") { showDetail(MediaRow(item));return }
        saveProgress()
        val stream=withContext(Dispatchers.IO) { url ?: repo.secrets.open(item.streamCipher) }
        val watch=repo.dao.watch(settings.value.profile,item.id)
        val resume=if(startOver || item.kind=="LIVE" || watch?.watched==true) 0 else watch?.position ?: 0
        val agent=withContext(Dispatchers.IO) { repo.secrets.open(item.agentCipher) };val ref=withContext(Dispatchers.IO) { repo.secrets.open(item.referrerCipher) }
        engine.play(stream,settings.value.allowHttp,resume,agent,ref)
        if(item.kind=="LIVE" && lastChannel?.id!=item.id) { previousChannel=lastChannel;lastChannel=item }
        currentUrl=stream;message.value=null;playing.value=item;playbackProfile=settings.value.profile
        repo.progress(playbackProfile,item.id,resume,0)
    }
    fun playCatchup(item: ItemEntity,program: ProgramEntity)=task { playNow(item,repo.catchup(item,program,settings.value.allowHttp)) }
    fun recall() { previousChannel?.let { play(it) } }
    fun switchChannel(direction: Int)=task {
        val next=repo.neighbor(settings.value,activeProfile.value,playing.value,direction) ?: return@task
        playNow(next)
    }

    fun nextEpisode() { val list=episodes.value;val index=list.indexOfFirst { it.id==playing.value?.id };if(index>=0 && index+1<list.size) play(list[index+1]) else message.value="No next episode is loaded." }
    suspend fun saveProgress() {
        val item=playing.value ?: return;val p=engine.player.value ?: return
        repo.progress(playbackProfile,item.id,if(item.kind=="LIVE") 0 else p.currentPosition,p.duration.coerceAtLeast(0))
    }
    fun stopPlayback() { viewModelScope.launch { saveProgress();engine.stop();playing.value=null;currentUrl=null } }
    fun background() { viewModelScope.launch { saveProgress();engine.releasePlayer();playing.value=null;currentUrl=null } }
    fun showDetail(row: MediaRow) { detail.value=row;episodes.value=emptyList();if(row.item.kind=="SERIES") task { episodes.value=repo.episodes(row.item,settings.value.allowHttp) } }
    fun bool(key: String,value: Boolean) { viewModelScope.launch { app.settings.bool(key,value) } }
    fun string(key: String,value: String) { viewModelScope.launch { app.settings.string(key,value) } }
    fun aspect(value: Int) { viewModelScope.launch { app.settings.int("aspect",value) } }
    fun saveProfile(existing: ProfileEntity?,name: String,pin: String,restricted: Set<String>)=task {
        if(name.isBlank()) throw ImportFailure("Enter a profile name.")
        if(restricted.isNotEmpty() && settings.value.ownerPin.isEmpty()) throw ImportFailure("Set a management PIN in Settings before restricting categories.")
        val hash=if(pin.isBlank()) existing?.pinHash.orEmpty() else withContext(Dispatchers.Default) { PinHasher.create(pin) }
        repo.dao.putProfile(ProfileEntity(existing?.id ?: UUID.randomUUID().toString(),name.trim(),hash,JSONArray(restricted.toList()).toString()))
    }
    fun removeProfile(profile: ProfileEntity)=task { if(profile.id=="default") throw ImportFailure("The Home profile cannot be removed.");repo.db.withTransaction { repo.dao.deleteProfileWatch(profile.id);repo.dao.deleteProfile(profile.id) };if(settings.value.profile==profile.id) app.settings.string("profile","default") }
    fun verifyPin(pin: String,hash: String,onSuccess: ()->Unit) {
        viewModelScope.launch {
            val now=android.os.SystemClock.elapsedRealtime()
            if(now<pinBlockedUntil) { message.value="Too many PIN attempts. Try again in a minute.";return@launch }
            val valid=withContext(Dispatchers.Default) { PinHasher.verify(pin,hash) }
            if(valid) { pinFailures=0;onSuccess() } else { pinFailures++;if(pinFailures>=5) { pinBlockedUntil=now+60000;pinFailures=0 };message.value="Incorrect PIN." }
        }
    }
    fun setOwnerPin(pin: String)=task { val hash=withContext(Dispatchers.Default) { PinHasher.create(pin) };app.settings.string("ownerPin",hash);message.value="Management PIN saved." }
    override fun onCleared() { engine.close();super.onCleared() }
}