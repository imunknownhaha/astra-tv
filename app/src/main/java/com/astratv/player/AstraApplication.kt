package com.astratv.player

import android.app.Application
import androidx.room.Room
import androidx.work.*
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.astratv.player.core.database.*
import com.astratv.player.core.datastore.SettingsStore
import com.astratv.player.core.network.NetworkClient
import com.astratv.player.core.security.SecretStore
import com.astratv.player.feature.epg.GuideRefreshWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AstraApplication: Application(),ImageLoaderFactory, Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
    val database by lazy { Room.databaseBuilder(this,LibraryDatabase::class.java,"astra.db").build() }
    val network by lazy { NetworkClient() }
    val settings by lazy { SettingsStore(this) }
    val library by lazy { LibraryRepository(database,SecretStore(),network,this) }
    override fun onCreate() {
        super.onCreate()
        com.astratv.player.core.security.CrashDiagnostics.install(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("astra-guide",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<GuideRefreshWorker>(12,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    // Logos are HTTPS-only; HTTP providers can still play after explicit opt-in.
    override fun newImageLoader()=ImageLoader.Builder(this).okHttpClient(network.http(false)).crossfade(false).build()
}
