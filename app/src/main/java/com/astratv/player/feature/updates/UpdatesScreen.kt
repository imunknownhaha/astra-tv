package com.astratv.player.feature.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astratv.player.BuildConfig
import com.astratv.player.core.ui.*
import com.astratv.player.feature.*
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun UpdatesScreen(vm: MainViewModel) {
    val context=LocalContext.current;val settings by vm.settings.collectAsStateWithLifecycle()
    var repository by remember(settings.github) { mutableStateOf(settings.github) }
    var release by remember { mutableStateOf<Release?>(null) };var downloaded by remember { mutableStateOf<File?>(null) }
    val updates=remember { UpdateRepository(context.applicationContext,vm.app.network) }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(4.dp)) {
        item { Text("Astra TV ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleLarge,color=Ink) }
        item { Text("A player for your authorized sources. Astra TV supplies no channels, subscriptions or media. Updates are checked only when you choose; installation always requires your approval.",color=Muted) }
        item { Field("GitHub release repository (owner/repository)",repository,{ repository=it },Modifier.fillMaxWidth()) }
        item { TvAction("Save & check for updates",{ vm.task {
            vm.app.settings.string("github",repository.trim());release=updates.check(repository.trim());downloaded=null
            vm.message.value=if(release!!.newer) "Update ${release!!.version} is available." else "You are up to date with this repository."
        } },enabled=repository.isNotBlank()) }
        release?.let { r ->
            item { Text("Release ${r.version}",style=MaterialTheme.typography.titleLarge,color=Ink);Text(r.notes,color=Muted,modifier=Modifier.padding(top=10.dp)) }
            if(r.newer && r.asset.isNotBlank()) item { TvAction("Download APK",{ vm.task { downloaded=updates.download(r);vm.message.value="Verified update downloaded. Select Install to continue." } }) }
            if(r.newer && r.asset.isBlank()) item { Text("This release has no compatible ${if(BuildConfig.IS_PHONE) "phone" else "TV"} APK asset.",color=Muted) }
        }
        if(downloaded!=null) item { TvAction("Install downloaded update",{
            if(!context.packageManager.canRequestPackageInstalls()) runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
            }.onFailure { vm.message.value="Allow Astra TV in Settings → Apps → Special app access → Install unknown apps, then select Install again." }
            else runCatching { context.startActivity(updates.install(downloaded!!)) }.onFailure { vm.message.value="No package installer is available on this device. Use ADB or Downloader." }
        }) }
        item { Text("Provider capabilities and supported codecs vary by device. Recording and DRM-protected playback are not included in this version.",color=Muted) }
    }
}
