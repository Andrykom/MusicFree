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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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

    /** Подборка рекомендаций по общему вкусу из библиотеки пользователя */
    suspend fun getTasteSeeds(libraryTracks: List<Track>): List<DeezerRadioTrack> = coroutineScope {
        if (libraryTracks.isEmpty()) return@coroutineScope emptyList()

        val blocked = blockedArtistsRepository.blockedArtists.first()
        val artistCounts = libraryTracks
            .map { it.artist.split(",", ";", "&", "feat.", "ft.", ignoreCase = true).first().trim() }
            .filter { it.isNotBlank() && it.lowercase() !in blocked }
            .groupingBy { it }
            .eachCount()

        if (artistCounts.isEmpty()) return@coroutineScope emptyList()

        val topArtists = artistCounts.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        val randomArtists = artistCounts.keys
            .filter { it !in topArtists }
            .shuffled()
            .take(2)

        val seedArtists = (topArtists + randomArtists).distinct()
        val libraryKeys = libraryTracks.map { normalize("${it.artist} ${it.title}") }.toSet()

        val allSeeds = seedArtists.map { artist ->
            async { getSeeds(artist) }
        }.awaitAll().flatten()

        allSeeds
            .distinctBy { normalize("${it.artist.name} ${it.title}") }
            .filter { normalize("${it.artist.name} ${it.title}") !in libraryKeys }
            .shuffled()
    }

    /** Найти конкретный трек через плагины (приоритет Zvuch) с проверкой совпадения */
    suspend fun findTrack(artist: String, title: String): Track? {
        if (blockedArtistsRepository.isBlocked(artist)) return null
        val query = "$artist $title".trim()
        Log.d("RadioRepository", "Searching radio track: '$query'")

        for (plugin in orderedPlugins) {
            try {
                val results = withTimeoutOrNull(8_000) { plugin.search(query) } ?: continue
                val validResults = results.filter { !it.audioUrl.isNullOrBlank() && !it.artist.equals("Популярные песни", ignoreCase = true)}
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