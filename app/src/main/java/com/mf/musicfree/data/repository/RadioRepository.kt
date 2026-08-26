package com.mf.musicfree.data.repository

import com.mf.musicfree.data.api.DeezerRadioApi
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.plugin.PluginManager
import kotlinx.coroutines.withTimeoutOrNull

class RadioRepository(
    private val deezerRadioApi: DeezerRadioApi,
    private val pluginManager: PluginManager
) {
    /** Получить список похожих треков (seeds) от Deezer */
    suspend fun getSeeds(artistName: String, excludeTitle: String? = null): List<DeezerRadioTrack> {
        return try {
            val tracks = deezerRadioApi.getRadioTracks(artistName)
            val filtered = if (excludeTitle != null) {
                tracks.filter { it.title.lowercase() != excludeTitle.lowercase() }
            } else tracks
            filtered.shuffled()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Найти один конкретный трек через плагины */
    suspend fun findTrack(artist: String, title: String): Track? {
        val query = "$artist $title"
        for (plugin in pluginManager.plugins) {
            try {
                val results = withTimeoutOrNull(8_000) { plugin.search(query) } ?: continue
                val best = results.firstOrNull() ?: continue
                return Track(
                    id = best.id,
                    title = best.title,
                    artist = best.artist,
                    duration = best.duration,
                    coverUrl = best.coverUrl,
                    sourceUrl = best.audioUrl,
                    isDownloaded = false
                )
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}