package com.astratv.player.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.astratv.player.core.ui.*
import com.astratv.player.feature.*
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val player by vm.engine.player.collectAsStateWithLifecycle();val item by vm.playing.collectAsStateWithLifecycle()
    val error by vm.engine.error.collectAsStateWithLifecycle();val buffering by vm.engine.buffering.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(true) };var trackType by remember { mutableStateOf<Int?>(null) }
    var interaction by remember { mutableIntStateOf(0) };var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0) };var duration by remember { mutableLongStateOf(0) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    val rootFocus=remember { FocusRequester() };val playFocus=remember { FocusRequester() }
    BackHandler { if(trackType!=null) trackType=null else vm.stopPlayback() }
    LaunchedEffect(controls) { if(controls) runCatching { playFocus.requestFocus() } else runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(controls,interaction,error,trackType) { if(controls && trackType==null && error==null) { delay(8000);controls=false } }
    DisposableEffect(player) {
        val listener=object: Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing=value }
            override fun onTracksChanged(value: Tracks) { tracks=value }
        }
        player?.addListener(listener);tracks=player?.currentTracks ?: Tracks.EMPTY
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { while(true) { position=player?.currentPosition ?: 0;duration=(player?.duration ?: 0).coerceAtLeast(0);delay(1000) } }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFocus).onPreviewKeyEvent { e ->
        if(e.type!=KeyEventType.KeyDown) false else {
            interaction++
            when(e.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { if(it.isPlaying) it.pause() else it.play() };true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play();true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause();true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { vm.stopPlayback();true }
                KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_MEDIA_NEXT -> { if(item?.kind=="LIVE") vm.switchChannel(1) else vm.nextEpisode();true }
                KeyEvent.KEYCODE_CHANNEL_DOWN,KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if(item?.kind=="LIVE") vm.switchChannel(-1);true }
                KeyEvent.KEYCODE_LAST_CHANNEL -> { vm.recall();true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if(player?.isCurrentMediaItemSeekable==true) player?.seekTo((position+30000).coerceAtMost(duration));true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if(player?.isCurrentMediaItemSeekable==true) player?.seekTo((position-10000).coerceAtLeast(0));true }
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> if(!controls) { controls=true;true } else false
                else -> false
            }
        }
    }.focusable()) {
        AndroidView(factory={ context -> PlayerView(context).apply { useController=false;isFocusable=false;keepScreenOn=true } },
            update={ view -> view.player=player;view.resizeMode=when(settings.aspect) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL;2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else -> AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            onRelease={ it.player=null },modifier=Modifier.fillMaxSize())
        if(message!=null) Row(Modifier.align(Alignment.TopCenter).padding(32.dp).fillMaxWidth().background(Background).padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { Text(message!!,Modifier.weight(1f),color=Ink);TvAction("Dismiss",{ vm.message.value=null }) }
        if(buffering) CircularProgressIndicator(Modifier.align(Alignment.Center),color=Accent)
        if(error!=null) Column(Modifier.align(Alignment.Center).widthIn(max=560.dp).background(Background).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(error!!,color=Ink,style=MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { TvAction("Retry",{ vm.engine.retry() });TvAction("Back to library",{ vm.stopPlayback() }) }
        }
        if(controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Background.copy(alpha=0.96f)).padding(32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(item?.name.orEmpty(),color=Ink,style=MaterialTheme.typography.headlineSmall)
            if(duration>0) { LinearProgressIndicator(progress={ (position.toFloat()/duration).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth(),color=Accent,trackColor=Outline);Text("${time(position)} / ${time(duration)}",color=Muted) }
            LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(4.dp)) {
                item { TvAction(if(playing) "Pause" else "Play",{ player?.let { if(it.isPlaying) it.pause() else it.play() } },Modifier.focusRequester(playFocus)) }
                if(item?.kind=="LIVE") {
                    item { TvAction("Previous channel",{ vm.switchChannel(-1) }) };item { TvAction("Next channel",{ vm.switchChannel(1) }) }
                    item { TvAction("Last channel",{ vm.recall() },enabled=vm.previousChannel!=null) }
                } else {
                    item { TvAction("Back 10 sec",{ player?.seekTo((position-10000).coerceAtLeast(0)) },enabled=player?.isCurrentMediaItemSeekable==true) }
                    item { TvAction("Forward 30 sec",{ player?.seekTo((position+30000).coerceAtMost(duration)) },enabled=player?.isCurrentMediaItemSeekable==true) }
                    if(item?.kind=="EPISODE") item { TvAction("Next episode",{ vm.nextEpisode() },enabled=episodes.indexOfFirst { it.id==item?.id }.let { it>=0 && it+1<episodes.size }) }
                }
                item { TvAction("Audio",{ trackType=C.TRACK_TYPE_AUDIO }) };item { TvAction("Subtitles",{ trackType=C.TRACK_TYPE_TEXT }) }
                item { TvAction("Aspect: ${listOf("Fit","Fill","Zoom")[settings.aspect.coerceIn(0,2)]}",{ vm.aspect((settings.aspect+1)%3) }) }
                item { TvAction("Save favorite",{ item?.let { vm.favorite(it) } }) }
                item { TvAction("Hide controls",{ controls=false }) };item { TvAction("Close player",{ vm.stopPlayback() }) }
            }
        }
    }
    if(trackType!=null) PanelDialog(if(trackType==C.TRACK_TYPE_AUDIO) "Audio tracks" else "Subtitle tracks",{ trackType=null }) {
        val type=trackType!!
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { TvAction(if(type==C.TRACK_TYPE_TEXT) "Off" else "Automatic",{
                player?.let { it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,type==C.TRACK_TYPE_TEXT).build() };trackType=null
            },Modifier.fillMaxWidth()) }
            val available=tracks.groups.filter { it.type==type }
            if(available.isEmpty()) item { Text("No selectable tracks supplied by this stream.",color=Muted) }
            available.forEach { group ->
                items((0 until group.length).toList()) { index ->
                    val format=group.getTrackFormat(index)
                    val label=listOfNotNull(format.label,format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }).distinct().joinToString(" · ").ifBlank { "Track ${index+1}" }
                    TvAction(label+(if(group.isTrackSelected(index)) " · Selected" else ""),{
                        player?.let { it.trackSelectionParameters=it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type,false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index)).build() };trackType=null
                    },Modifier.fillMaxWidth(),enabled=group.isTrackSupported(index))
                }
            }
            item { TvAction("Close",{ trackType=null }) }
        }
    }
}
private fun time(value: Long): String { val s=value/1000;return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60) }
