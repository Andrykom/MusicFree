package com.mf.musicfree.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.blockedArtistsDataStore by preferencesDataStore(name = "blocked_artists")

class BlockedArtistsRepository(private val context: Context) {

    private val dataStore = context.blockedArtistsDataStore
    private val KEY_BLOCKED = stringSetPreferencesKey("blocked_artists")

    val blockedArtists: Flow<Set<String>> = dataStore.data.map { it[KEY_BLOCKED] ?: emptySet() }

    suspend fun isBlocked(artist: String): Boolean {
        val set = blockedArtists.first()
        return normalize(artist) in set
    }

    suspend fun block(artist: String) {
        val n = normalize(artist)
        if (n.isBlank()) return
        dataStore.edit { prefs ->
            val cur = prefs[KEY_BLOCKED] ?: emptySet()
            prefs[KEY_BLOCKED] = cur + n
        }
    }

    suspend fun unblock(artist: String) {
        val n = normalize(artist)
        dataStore.edit { prefs ->
            val cur = prefs[KEY_BLOCKED] ?: emptySet()
            prefs[KEY_BLOCKED] = cur - n
        }
    }

    suspend fun toggle(artist: String): Boolean {
        val n = normalize(artist)
        var nowBlocked = false
        dataStore.edit { prefs ->
            val cur = prefs[KEY_BLOCKED] ?: emptySet()
            nowBlocked = n !in cur
            prefs[KEY_BLOCKED] = if (nowBlocked) cur + n else cur - n
        }
        return nowBlocked
    }

    suspend fun clearAll() {
        dataStore.edit { it.remove(KEY_BLOCKED) }
    }

    private fun normalize(artist: String): String = artist.trim().lowercase()
}
