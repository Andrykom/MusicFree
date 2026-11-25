package com.mf.musicfree.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

private val Context.tasteDataStore by preferencesDataStore(name = "taste_profile")

class TasteProfileRepository(private val context: Context) {
    private val dataStore = context.tasteDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val weightsSerializer = MapSerializer(String.serializer(), Float.serializer())

    companion object {
        private val KEY_ARTIST_WEIGHTS = stringPreferencesKey("artist_weights_json")
        private const val MAX_STORED_ARTISTS = 200
    }

    private fun normalizeArtist(artist: String): String =
        artist.split(",", ";", "&", "feat.", "ft.", ignoreCase = true)
            .first()
            .lowercase()
            .replace('ё', 'е')
            .trim()

    suspend fun getWeights(): Map<String, Float> {
        return try {
            val raw = dataStore.data.first()[KEY_ARTIST_WEIGHTS] ?: return emptyMap()
            json.decodeFromString(weightsSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun recordLike(artist: String) = adjustWeight(artist, +2.5f)
    suspend fun recordCompleteListen(artist: String) = adjustWeight(artist, +1.0f)
    suspend fun recordQuickSkip(artist: String) = adjustWeight(artist, -1.2f)

    private suspend fun adjustWeight(artist: String, delta: Float) {
        val key = normalizeArtist(artist)
        if (key.isBlank() || key == "unknown") return
        try {
            dataStore.edit { prefs ->
                val current = prefs[KEY_ARTIST_WEIGHTS]?.let { raw ->
                    try {
                        json.decodeFromString(weightsSerializer, raw)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                }?.toMutableMap() ?: mutableMapOf()

                val updated = ((current[key] ?: 0f) + delta).coerceIn(-10f, 30f)
                current[key] = updated

                // Держим таблицу компактной
                val trimmed = if (current.size > MAX_STORED_ARTISTS) {
                    current.entries
                        .sortedByDescending { abs(it.value) }
                        .take(MAX_STORED_ARTISTS)
                        .associate { it.key to it.value }
                } else {
                    current
                }
                prefs[KEY_ARTIST_WEIGHTS] = json.encodeToString(weightsSerializer, trimmed)
            }
        } catch (_: Exception) {}
    }
}