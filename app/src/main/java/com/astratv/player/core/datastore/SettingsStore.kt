package com.astratv.player.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
private val Context.preferences by preferencesDataStore("astra.settings")
data class Settings(val allowHttp: Boolean=false,val merged: Boolean=true,val sort: String="Name",val profile: String="default",
    val source: String="",val aspect: Int=0,val github: String="",val ownerPin: String="",val guideRefresh: Boolean=true)
class SettingsStore(private val context: Context) {
    val flow=context.preferences.data.map { p -> Settings(p[booleanPreferencesKey("http")] ?: false,p[booleanPreferencesKey("merged")] ?: true,
        p[stringPreferencesKey("sort")] ?: "Name",p[stringPreferencesKey("profile")] ?: "default",p[stringPreferencesKey("source")].orEmpty(),
        p[intPreferencesKey("aspect")] ?: 0,p[stringPreferencesKey("github")].orEmpty(),p[stringPreferencesKey("ownerPin")].orEmpty(),p[booleanPreferencesKey("guideRefresh")] ?: true) }
    suspend fun bool(key: String,value: Boolean) { context.preferences.edit { it[booleanPreferencesKey(key)]=value } }
    suspend fun string(key: String,value: String) { context.preferences.edit { it[stringPreferencesKey(key)]=value } }
    suspend fun int(key: String,value: Int) { context.preferences.edit { it[intPreferencesKey(key)]=value } }
}
