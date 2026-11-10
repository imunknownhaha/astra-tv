package com.astratv.player.feature.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.FileProvider
import com.astratv.player.BuildConfig
import com.astratv.player.core.model.ImportFailure
import com.astratv.player.core.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI

data class Release(val version: String,val notes: String,val asset: String,val newer: Boolean)
class UpdateRepository(private val context: Context,private val network: NetworkClient) {
    suspend fun check(repository: String): Release = withContext(Dispatchers.IO) {
        if(!repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) throw ImportFailure("Enter a GitHub repository as owner/repository.")
        val root=JSONObject(network.text("https://api.github.com/repos/$repository/releases/latest",false))
        val tag=root.getString("tag_name").removePrefix("v")
        val assets=root.getJSONArray("assets")
        val expected=if(BuildConfig.IS_PHONE) "astra-phone-$tag.apk" else "astra-tv-$tag.apk"
        val asset=(0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name")==expected }?.optString("browser_download_url").orEmpty()
        if(asset.isNotEmpty()) {
            val u=URI(asset)
            if(u.scheme!="https" || u.host!="github.com" || !u.path.startsWith("/$repository/releases/download/")) throw ImportFailure("The APK is not hosted by the configured GitHub repository.")
        }
        Release(tag,root.optString("body","No release notes supplied."),asset,newer(tag,BuildConfig.VERSION_NAME))
    }
    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        if(!release.newer || release.asset.isBlank()) throw ImportFailure("This release has no compatible APK asset.")
        val dir=File(context.cacheDir,"updates").apply { mkdirs() };val file=File(dir,"astra-update.apk")
        try {
            network.read(release.asset,false,150L*1024*1024) { input -> file.outputStream().use { input.copyTo(it) } }
            val pm=context.packageManager
            @Suppress("DEPRECATION")
            val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive=pm.getPackageArchiveInfo(file.absolutePath,flags) ?: throw ImportFailure("Downloaded file is not a valid APK.")
            if(archive.packageName!=BuildConfig.APPLICATION_ID || PackageInfoCompat.getLongVersionCode(archive)<=BuildConfig.VERSION_CODE) throw ImportFailure("The APK package or version does not match this app.")
            @Suppress("DEPRECATION")
            val installed=pm.getPackageInfo(context.packageName,flags)
            val trusted=signers(installed)
            val candidate=signers(archive)
            if(trusted.isEmpty() || candidate.isEmpty() || !candidate.all { it in trusted }) throw ImportFailure("The APK signing certificate does not match this installation.")
            file
        } catch(e: Exception) { file.delete();throw e }
    }
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): List<List<Byte>> =
        (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toByteArray().toList() }.orEmpty()
    fun install(file: File): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(
        FileProvider.getUriForFile(context,"${context.packageName}.files",file),"application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    companion object {
        fun newer(remote: String,local: String): Boolean {
            fun parse(v: String)=v.removePrefix("v").takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }?.split('.')?.map { it.toIntOrNull() ?: 0 }
            val a=parse(remote) ?: return false;val b=parse(local) ?: return false
            for(i in 0..2) if(a[i]!=b[i]) return a[i]>b[i]
            return false
        }
    }
}
