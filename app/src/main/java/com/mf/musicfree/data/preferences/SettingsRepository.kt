package com.mf.musicfree.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.settingsDataStore by preferencesDataStore(name = "user_settings")

@Serializable
data class SavedTrackDto(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long = 0,
    val filePath: String? = null,
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val isDownloaded: Boolean = false,
    val addedAt: Long = 0
) {
    fun toTrack() = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        filePath = filePath,
        coverUrl = coverUrl,
        sourceUrl = sourceUrl,
        isDownloaded = isDownloaded,
        addedAt = addedAt
    )

    companion object {
        fun fromTrack(t: Track) = SavedTrackDto(
            id = t.id,
            title = t.title,
            artist = t.artist,
            album = t.album,
            duration = t.duration,
            filePath = t.filePath,
            coverUrl = t.coverUrl,
            sourceUrl = t.sourceUrl,
            isDownloaded = t.isDownloaded,
            addedAt = t.addedAt
        )
    }
}

data class SavedPlaybackSnapshot(
    val queue: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val isTasteWave: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private val dataStore = context.settingsDataStore
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_DEFAULT_PLUGIN = stringPreferencesKey("default_search_plugin")
        private val KEY_CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        private val KEY_SAVED_QUEUE = stringPreferencesKey("saved_playback_queue")
        private val KEY_SAVED_INDEX = intPreferencesKey("saved_playback_index")
        private val KEY_SAVED_POSITION = longPreferencesKey("saved_playback_position_ms")

        private val KEY_SAVED_IS_TASTE_WAVE = booleanPreferencesKey("saved_is_taste_wave")
    }

    val defaultPluginName: Flow<String?> = dataStore.data
        .map { it[KEY_DEFAULT_PLUGIN] }
        .distinctUntilChanged()

    val crossfadeSeconds: Flow<Int> = dataStore.data
        .map { it[KEY_CROSSFADE_SECONDS] ?: 0 }
        .distinctUntilChanged()

    suspend fun setDefaultPluginName(name: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_PLUGIN] = name
        }
    }


    suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CROSSFADE_SECONDS] = seconds
        }
    }

    suspend fun savePlaybackSnapshot(
        queue: List<Track>,
        currentIndex: Int,
        positionMs: Long,
        isTasteWave: Boolean = false
    ) {
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            clearPlaybackSnapshot()
            return
        }
        val start = (currentIndex - 20).coerceAtLeast(0)
        val end = (currentIndex + 60).coerceAtMost(queue.size)
        val subQueue = queue.subList(start, end)
        val adjustedIndex = currentIndex - start

        val encoded = try {
            json.encodeToString(subQueue.map { SavedTrackDto.fromTrack(it) })
        } catch (_: Exception) {
            return
        }

        dataStore.edit { prefs ->
            prefs[KEY_SAVED_QUEUE] = encoded
            prefs[KEY_SAVED_INDEX] = adjustedIndex
            prefs[KEY_SAVED_POSITION] = positionMs.coerceAtLeast(0L)
            prefs[KEY_SAVED_IS_TASTE_WAVE] = isTasteWave
        }
    }

    suspend fun updateSavedPosition(positionMs: Long) {
        dataStore.edit { prefs ->
            if (prefs.contains(KEY_SAVED_QUEUE)) {
                prefs[KEY_SAVED_POSITION] = positionMs.coerceAtLeast(0L)
            }
        }
    }

    suspend fun loadPlaybackSnapshot(): SavedPlaybackSnapshot? {
        return try {
            val prefs = dataStore.data.first()
            val encoded = prefs[KEY_SAVED_QUEUE] ?: return null
            val idx = prefs[KEY_SAVED_INDEX] ?: return null
            val pos = prefs[KEY_SAVED_POSITION] ?: 0L
            val tracks = json.decodeFromString<List<SavedTrackDto>>(encoded).map { it.toTrack() }
            if (tracks.isEmpty() || idx !in tracks.indices) return null
            SavedPlaybackSnapshot(queue = tracks, currentIndex = idx, positionMs = pos)

            val isTasteWave = prefs[KEY_SAVED_IS_TASTE_WAVE] ?: false
            SavedPlaybackSnapshot(
                queue = tracks,
                currentIndex = idx,
                positionMs = pos,
                isTasteWave = isTasteWave
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clearPlaybackSnapshot() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SAVED_IS_TASTE_WAVE)
            prefs.remove(KEY_SAVED_QUEUE)
            prefs.remove(KEY_SAVED_INDEX)
            prefs.remove(KEY_SAVED_POSITION)
        }
    }
}