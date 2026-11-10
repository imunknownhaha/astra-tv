package com.astratv.player.core.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import com.astratv.player.core.network.NetworkClient
import com.astratv.player.core.network.UrlPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
class PlaybackEngine(private val context: Context,private val network: NetworkClient) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var session: MediaSession?=null
    private var retry: Job?=null
    private var attempts=0
    private var generation=0
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private val _buffering=MutableStateFlow(false);val buffering=_buffering.asStateFlow()
    private val _player=MutableStateFlow<ExoPlayer?>(null);val player=_player.asStateFlow()
    private var allowHttp: Boolean?=null
    fun play(url: String,allow: Boolean,position: Long=0,agent: String="",referrer: String="") {
        UrlPolicy.validate(url,allow);retry?.cancel();attempts=0;generation++;_error.value=null
        if(_player.value==null || allowHttp!=allow) {
            releasePlayer()
            val http=OkHttpDataSource.Factory(network.streamHttp(allow)).setUserAgent(agent.ifBlank { "AstraTV/0.1" })
                .setDefaultRequestProperties(if(referrer.isBlank()) emptyMap() else mapOf("Referer" to referrer))
            val factory=DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http)).setLoadErrorHandlingPolicy(NoAutomaticRetry())
            val p=ExoPlayer.Builder(context).setMediaSourceFactory(factory).setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(5000,20000,500,1500).setTargetBufferBytes(24*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()).build()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            p.setHandleAudioBecomingNoisy(true)
            p.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { _buffering.value=state==Player.STATE_BUFFERING }
                override fun onPlayerError(error: PlaybackException) {
                    val transient=error.errorCode in 2000..2009 && error.errorCode !in listOf(2004,2005)
                    val delay=if(transient) RetryPolicy.delayMs(attempts++) else null
                    if(delay==null) { _buffering.value=false;_error.value=when(error.errorCode) {
                        in 4000..4999 -> "This device cannot decode this stream. Try another format or channel."
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused this stream. Check the source or choose another channel."
                        else -> "Playback stopped. Check your connection and retry, or choose another stream."
                    } } else {
                        val token=generation;_error.value="Connection lost. Reconnecting ($attempts/${RetryPolicy.MAX_RETRIES})…"
                        retry=scope.launch { delay(delay);if(token==generation) { _error.value=null;p.prepare();p.play() } }
                    }
                }
            })
            _player.value=p;allowHttp=allow;session=MediaSession.Builder(context,p).build()
        }
        val p=_player.value!!
        // Source-specific headers are attached to the MediaItem request, including when reusing the player.
        val http=OkHttpDataSource.Factory(network.streamHttp(allow)).setUserAgent(agent.ifBlank { "AstraTV/0.1" })
            .setDefaultRequestProperties(if(referrer.isBlank()) emptyMap() else mapOf("Referer" to referrer))
        val source=DefaultMediaSourceFactory(DefaultDataSource.Factory(context,http)).setLoadErrorHandlingPolicy(NoAutomaticRetry()).createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source,position.coerceAtLeast(0));p.prepare();p.play()
    }
    fun retry() { attempts=0;_error.value=null;_player.value?.apply { prepare();play() } }
    fun stop() { generation++;retry?.cancel();_player.value?.stop();_error.value=null }
    fun releasePlayer() { generation++;retry?.cancel();session?.release();session=null;_player.value?.release();_player.value=null;allowHttp=null;_buffering.value=false }
    fun close() { releasePlayer();scope.cancel() }
}

@UnstableApi
private class NoAutomaticRetry: DefaultLoadErrorHandlingPolicy(0) {
    override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
}
