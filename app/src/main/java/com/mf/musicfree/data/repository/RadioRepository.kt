package com.mf.musicfree.data.repository

import android.util.Log
import com.mf.musicfree.data.api.DeezerArtist
import com.mf.musicfree.data.api.DeezerRadioApi
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.api.LastFmApi
import com.mf.musicfree.data.api.ListenBrainzApi
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.data.preferences.TasteProfileRepository
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
    private val lastFmApi: LastFmApi,
    private val listenBrainzApi: ListenBrainzApi,
    private val tasteProfileRepository: TasteProfileRepository,
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

    suspend fun getSeeds(artistName: String, excludeTitle: String? = null): List<DeezerRadioTrack> = coroutineScope {
        try {
            val lastFmDef = async { withTimeoutOrNull(5_000) { lastFmApi.getSimilarTracks(artistName) } ?: emptyList() }
            val lbDef = async { withTimeoutOrNull(5_500) { listenBrainzApi.getSimilarTracks(artistName) } ?: emptyList() }
            val deezerDef = async { withTimeoutOrNull(4_500) { deezerRadioApi.getRadioTracks(artistName) } ?: emptyList() }

            var tracks = (lastFmDef.await() + lbDef.await() + deezerDef.await())
                .distinctBy { normalize("${it.artist.name} ${it.title}") }

            if (tracks.isEmpty()) {
                for (plugin in orderedPlugins) {
                    try {
                        val searchResults = withTimeoutOrNull(6_000) { plugin.search(artistName) }
                        if (!searchResults.isNullOrEmpty()) {
                            tracks = searchResults
                                .filter { it.artist.isNotBlank() && it.artist != "Unknown" }
                                .map { DeezerRadioTrack(title = it.title, artist = DeezerArtist(0L, it.artist)) }
                            if (tracks.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
            }

            var filtered = if (excludeTitle != null) {
                tracks.filter { !it.title.equals(excludeTitle, ignoreCase = true) }
            } else tracks

            val blocked = blockedArtistsRepository.blockedArtists.first()
            if (blocked.isNotEmpty()) {
                filtered = filtered.filter { it.artist.name.trim().lowercase() !in blocked }
            }

            // Применяем локальные веса вкуса: штрафуем часто скипаемых, поднимаем любимых + сохраняем элемент случайности
            val weights = tasteProfileRepository.getWeights()
            filtered
                .filter { (weights[normalize(it.artist.name)] ?: 0f) > -4f }
                .sortedByDescending { seed ->
                    val w = weights[normalize(seed.artist.name)] ?: 0f
                    w * 0.4f + kotlin.random.Random.nextFloat() * 3.5f
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getTasteSeeds(libraryTracks: List<Track>): List<DeezerRadioTrack> = coroutineScope {
        if (libraryTracks.isEmpty()) return@coroutineScope emptyList()

        val blocked = blockedArtistsRepository.blockedArtists.first()
        val weights = tasteProfileRepository.getWeights()

        // Считаем скор каждого артиста библиотеки: количество треков + локальный вес прослушиваний/лайков
        val artistScores = libraryTracks
            .map { it.artist.split(",", ";", "&", "feat.", "ft.", ignoreCase = true).first().trim() }
            .filter { it.isNotBlank() && it.lowercase() !in blocked }
            .groupingBy { it }
            .eachCount()
            .mapValues { (artist, count) ->
                count.toFloat() + (weights[normalize(artist)] ?: 0f)
            }
            .filterValues { it > -2f }

        if (artistScores.isEmpty()) return@coroutineScope emptyList()

        val topArtists = artistScores.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        val randomArtists = artistScores.keys
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
            .sortedByDescending { seed ->
                val w = weights[normalize(seed.artist.name)] ?: 0f
                w * 0.4f + kotlin.random.Random.nextFloat() * 4.0f
            }
    }

    /** Найти конкретный трек через плагины (строгий отбор для Волны — сомнительные пропускаем) */
    suspend fun findTrack(artist: String, title: String): Track? {
        if (blockedArtistsRepository.isBlocked(artist)) return null
        val query = "$artist $title".trim()
        Log.d("RadioRepository", "Searching radio track: '$query'")

        for (plugin in orderedPlugins) {
            try {
                val results = withTimeoutOrNull(8_000) { plugin.search(query) } ?: continue
                val validResults = results.filter {
                    !it.audioUrl.isNullOrBlank() &&
                            !it.artist.equals("Популярные песни", ignoreCase = true) &&
                            it.artist.isNotBlank() &&
                            !it.artist.equals("Unknown", ignoreCase = true) &&
                            (it.duration == 0L || it.duration >= 30_000L) // отсекаем превью/рингтоны
                }
                if (validResults.isEmpty()) continue

                val matched = validResults.firstOrNull { candidate ->
                    isStrictTitleMatch(title, candidate.title) &&
                            isStrictArtistMatch(artist, candidate.artist)
                } ?: continue

                if (blockedArtistsRepository.isBlocked(matched.artist)) continue

                Log.d("RadioRepository", "Found in ${plugin.name}: ${matched.artist} - ${matched.title}")
                return Track(
                    id = matched.id,
                    title = title,
                    artist = artist,
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

        Log.d("RadioRepository", "No strict match found for: '$query' — skipping")
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*]"""), "")
            .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isStrictTitleMatch(expectedTitle: String, actualTitle: String): Boolean {
        val exp = normalize(expectedTitle)
        val act = normalize(actualTitle)
        if (exp.isBlank() || act.isBlank()) return false

        val rawAct = actualTitle.lowercase().replace('ё', 'е')
        val rawExp = expectedTitle.lowercase().replace('ё', 'е')
        val unwantedTags = listOf(
            "remix", "ремикс", "speed up", "sped up", "slowed", "nightcore",
            "cover", "кавер", "ковёр", "karaoke", "караоке", "minus", "минус",
            "instrumental", "инструментал", "live", "acoustic", "акустика",
            "phonk", "фонк", "bass boosted", "8d", "ai ", "нейро"
        )
        if (unwantedTags.any { tag -> rawAct.contains(tag) && !rawExp.contains(tag) }) {
            return false
        }

        // Точное совпадение (без учёта скобок и регистра)
        if (act == exp) return true

        // Допускаем только точное совпадение по всем словам (например, добавлен "feat ...")
        val expWords = exp.split(" ").filter { it.isNotBlank() }
        val actWords = act.split(" ").filter { it.isNotBlank() }
        return expWords.size >= 2 && actWords.take(expWords.size) == expWords &&
                (actWords.size == expWords.size || actWords[expWords.size] in setOf("feat", "ft", "prod"))
    }

    private fun isStrictArtistMatch(expectedArtist: String, actualArtist: String): Boolean {
        val expPrimary = normalize(
            expectedArtist.split(",", ";", "&", "feat.", "ft.", " x ", ignoreCase = true).first()
        )
        val actPrimary = normalize(
            actualArtist.split(",", ";", "&", "feat.", "ft.", " x ", ignoreCase = true).first()
        )
        val actFull = normalize(actualArtist)
        if (expPrimary.isBlank() || actPrimary.isBlank()) return false

        if (actPrimary == expPrimary) return true
        // Если у кандидата несколько артистов через пробел/запятую — проверяем совпадение целыми словами
        val expWords = expPrimary.split(" ").filter { it.isNotBlank() }
        val actWords = actFull.split(" ").filter { it.isNotBlank() }
        return expWords.isNotEmpty() && actWords.containsAll(expWords) &&
                expPrimary.length >= 3
    }
}