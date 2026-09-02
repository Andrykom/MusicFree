package com.mf.musicfree.data.repository

import com.mf.musicfree.data.api.DeezerRadioApi
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.plugin.PluginManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class RadioRepository(
    private val deezerRadioApi: DeezerRadioApi,
    private val pluginManager: PluginManager,
    private val blockedArtistsRepository: BlockedArtistsRepository
) {
    /** Получить список похожих треков (seeds) от Deezer — с фильтром блэклиста */
    suspend fun getSeeds(artistName: String, excludeTitle: String? = null): List<DeezerRadioTrack> {
        return try {
            val tracks = deezerRadioApi.getRadioTracks(artistName)
            var filtered = if (excludeTitle != null) {
                tracks.filter { it.title.lowercase() != excludeTitle.lowercase() }
            } else tracks
            // Фильтруем заблокированных исполнителей
            val blocked = blockedArtistsRepository.blockedArtists.first()
            if (blocked.isNotEmpty()) {
                filtered = filtered.filter { it.artist.name.trim().lowercase() !in blocked }
            }
            filtered.shuffled()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Найти один конкретный трек через плагины — пропускает заблокированных */
    suspend fun findTrack(artist: String, title: String): Track? {
        if (blockedArtistsRepository.isBlocked(artist)) return null
        val query = "$artist $title"
        for (plugin in pluginManager.plugins) {
            try {
                val results = withTimeoutOrNull(8_000) { plugin.search(query) } ?: continue
                val best = results.firstOrNull() ?: continue
                // На всякий случай проверяем и найденного артиста (плагин мог вернуть кавер)
                if (blockedArtistsRepository.isBlocked(best.artist)) continue
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