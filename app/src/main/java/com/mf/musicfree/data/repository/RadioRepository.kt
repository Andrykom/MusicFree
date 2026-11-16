package com.mf.musicfree.data.repository

import android.util.Log
import com.mf.musicfree.data.api.DeezerArtist
import com.mf.musicfree.data.api.DeezerRadioApi
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import com.mf.musicfree.plugin.SourcePlugin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class RadioRepository(
    private val deezerRadioApi: DeezerRadioApi,
    private val pluginManager: PluginManager,
    private val blockedArtistsRepository: BlockedArtistsRepository
) {
    // Приоритет для Моей волны: сначала Zvuch, затем DriveMusic, затем TheMP3
    private val orderedPlugins: List<SourcePlugin>
        get() = pluginManager.plugins.sortedBy { plugin ->
            when {
                plugin.name.equals("Zvuch", ignoreCase = true) -> 0
                plugin.name.equals("DriveMusic", ignoreCase = true) -> 1
                else -> 2
            }
        }

    /** Получить список похожих треков (seeds) от Deezer — с фильтром блэклиста */
    suspend fun getSeeds(artistName: String, excludeTitle: String? = null): List<DeezerRadioTrack> {
        return try {
            var tracks = deezerRadioApi.getRadioTracks(artistName)
            if (tracks.isEmpty()) {
                for (plugin in orderedPlugins) {
                    try {
                        val searchResults = withTimeoutOrNull(6_000) { plugin.search(artistName) }
                        if (!searchResults.isNullOrEmpty()) {
                            tracks = searchResults
                                .filter { it.artist.isNotBlank() && it.artist != "Unknown" }
                                .map {
                                    DeezerRadioTrack(
                                        title = it.title,
                                        artist = DeezerArtist(0L, it.artist)
                                    )
                                }
                            if (tracks.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
            }

            var filtered = if (excludeTitle != null) {
                tracks.filter { it.title.lowercase() != excludeTitle.lowercase() }
            } else tracks

            val blocked = blockedArtistsRepository.blockedArtists.first()
            if (blocked.isNotEmpty()) {
                filtered = filtered.filter { it.artist.name.trim().lowercase() !in blocked }
            }
            filtered.shuffled()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Найти конкретный трек через плагины (приоритет Zvuch) с проверкой совпадения */
    suspend fun findTrack(artist: String, title: String): Track? {
        if (blockedArtistsRepository.isBlocked(artist)) return null
        val query = "$artist $title".trim()
        Log.d("RadioRepository", "Searching radio track: '$query'")

        for (plugin in orderedPlugins) {
            try {
                val results = withTimeoutOrNull(8_000) { plugin.search(query) } ?: continue
                val validResults = results.filter { !it.audioUrl.isNullOrBlank() }
                if (validResults.isEmpty()) continue

                // Ищем результат, который реально совпадает по названию с искомым треком
                val matched = validResults.firstOrNull { candidate ->
                    isTitleMatch(title, candidate.title) &&
                            (candidate.artist.isBlank() ||
                                    candidate.artist.equals("Unknown", ignoreCase = true) ||
                                    isArtistMatch(artist, candidate.artist))
                } ?: continue

                val resolvedArtist = if (matched.artist.isBlank() || matched.artist.equals("Unknown", ignoreCase = true)) {
                    artist // Подставляем точного артиста из Deezer, если сайт не вернул имя
                } else {
                    matched.artist
                }

                if (blockedArtistsRepository.isBlocked(resolvedArtist)) continue

                Log.d("RadioRepository", "Found in ${plugin.name}: $resolvedArtist - ${matched.title}")
                return Track(
                    id = matched.id,
                    title = matched.title,
                    artist = resolvedArtist,
                    duration = matched.duration,
                    coverUrl = matched.coverUrl,
                    sourceUrl = matched.audioUrl,
                    isDownloaded = false
                )
            } catch (e: Exception) {
                Log.w("RadioRepository", "${plugin.name} failed for '$query': ${e.message}")
                continue
            }
        }

        Log.d("RadioRepository", "No matching track found for: '$query'")
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*]"""), "")
            .replace(Regex("""[^a-zа-яё0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isTitleMatch(expectedTitle: String, actualTitle: String): Boolean {
        val exp = normalize(expectedTitle)
        val act = normalize(actualTitle)
        if (exp.isBlank() || act.isBlank()) return false
        // Отсекаем ремиксы/спидапы, если в оригинале их не было
        val isRemixOrSpeedUp = listOf("remix", "speed up", "slowed", "nightcore", "cover", "ковёр")
            .any { tag -> act.contains(tag) && !exp.contains(tag) }
        if (isRemixOrSpeedUp) return false

        return act == exp || act.contains(exp) || exp.contains(act)
    }

    private fun isArtistMatch(expectedArtist: String, actualArtist: String): Boolean {
        val exp = normalize(expectedArtist)
        val act = normalize(actualArtist)
        if (exp.isBlank() || act.isBlank()) return false
        return act == exp || act.contains(exp) || exp.contains(act)
    }
}