package com.mf.musicfree.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "user_settings")

class SettingsRepository(private val context: Context) {

    private val dataStore = context.settingsDataStore

    companion object {
        private val KEY_DEFAULT_PLUGIN = stringPreferencesKey("default_search_plugin")
        private val KEY_CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
    }

    val defaultPluginName: Flow<String?> = dataStore.data.map { it[KEY_DEFAULT_PLUGIN] }

    suspend fun setDefaultPluginName(name: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_PLUGIN] = name
        }
    }

    val crossfadeSeconds: Flow<Int> = dataStore.data.map { it[KEY_CROSSFADE_SECONDS] ?: 0 }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CROSSFADE_SECONDS] = seconds
        }
    }
}
