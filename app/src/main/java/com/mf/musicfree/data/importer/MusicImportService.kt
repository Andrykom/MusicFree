package com.mf.musicfree.data.importer

import android.util.Log
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class ImportProgress(
    val current: Int,
    val total: Int,
    val currentTrackTitle: String,
    val foundCount: Int
)

data class ImportResult(
    val totalProcessed: Int,
    val successCount: Int,
    val skippedCount: Int
)

class MusicImportService(
    private val playlistWebExtractor: PlaylistWebExtractor,
    private val pluginManager: PluginManager,
    private val trackRepository: TrackRepository
) {

    companion object {
        private const val TAG = "MusicImport"
    }

    suspend fun extractPlaylistTracks(url: String): Result<List<ExtractedTrack>> {
        return playlistWebExtractor.extractTracks(url)
    }

    suspend fun importTracks(
        extractedTracks: List<ExtractedTrack>,
        maxCount: Int,
        onProgress: (ImportProgress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val tracksToImport = extractedTracks.take(maxCount.coerceIn(1, 100))
        val total = tracksToImport.size
        var foundCount = 0

        Log.i(TAG, "Starting import: $total tracks to process (maxCount=$maxCount)")

        // Начинаем добавлять треки снизу списка (reversed), чтобы при сортировке
        // ORDER BY addedAt DESC в Моей музыке первый трек исходного плейлиста оказался на самом верху!
        val reversedTracks = tracksToImport.reversed()
        val baseTimestamp = System.currentTimeMillis()

        for ((index, rawTrack) in reversedTracks.withIndex()) {
            if (!coroutineContext.isActive) {
                Log.w(TAG, "Import cancelled by user after processing $index of $total tracks")
                break
            }

            val stepNumber = index + 1
            val trackDisplayName = "${rawTrack.artist} — ${rawTrack.title}"
            Log.d(TAG, "[$stepNumber/$total] Searching track: $trackDisplayName")

            onProgress(
                ImportProgress(
                    current = stepNumber,
                    total = total,
                    currentTrackTitle = trackDisplayName,
                    foundCount = foundCount
                )
            )

            val foundSearchTrack = findTrackWithFallback(stepNumber, total, rawTrack.artist, rawTrack.title)
            if (foundSearchTrack != null) {
                // Время добавления увеличивается с каждым добавленным треком снизу вверх,
                // поэтому самый верхний трек (последний добавленный) получит наибольший timestamp
                val trackTime = baseTimestamp + (index * 1000L)
                val track = Track(
                    id = foundSearchTrack.id,
                    title = foundSearchTrack.title,
                    artist = foundSearchTrack.artist,
                    duration = foundSearchTrack.duration,
                    coverUrl = foundSearchTrack.coverUrl,
                    sourceUrl = foundSearchTrack.audioUrl,
                    filePath = null,
                    isDownloaded = false,
                    addedAt = trackTime
                )
                trackRepository.addTrack(track)
                foundCount++
                Log.i(TAG, "[$stepNumber/$total] Added to library: ${track.artist} — ${track.title} (audioUrl: ${track.sourceUrl})")
            } else {
                Log.w(TAG, "[$stepNumber/$total] Skipped track (not found): $trackDisplayName")
            }
        }

        val skipped = total - foundCount
        Log.i(TAG, "Import completed! Total: $total, Added: $foundCount, Skipped: $skipped")

        ImportResult(
            totalProcessed = total,
            successCount = foundCount,
            skippedCount = skipped
        )
    }

    private suspend fun findTrackWithFallback(step: Int, total: Int, artist: String, title: String): SearchTrack? {
        val zvuch = pluginManager.getPlugin("Zvuch")
            ?: pluginManager.plugins.firstOrNull { it.name.equals("Zvuch", ignoreCase = true) }
        val theMp3 = pluginManager.getPlugin("TheMP3")
            ?: pluginManager.plugins.firstOrNull { it.name.equals("TheMP3", ignoreCase = true) }
        val driveMusic = pluginManager.getPlugin("DriveMusic")
            ?: pluginManager.plugins.firstOrNull { it.name.equals("DriveMusic", ignoreCase = true) }

        val plugins = listOfNotNull(zvuch, theMp3, driveMusic)

        // Формируем список поисковых запросов: от точного к упрощенным вариантам
        val queries = mutableListOf<String>()
        val primaryQuery = "$artist $title".trim()
        queries.add(primaryQuery)

        // 1. Если несколько артистов ("SALUKI, postoyanno"), пробуем только первого артиста ("SALUKI")
        if (artist.contains(",") || artist.contains(";") || artist.contains(" feat.", ignoreCase = true) || artist.contains(" ft.", ignoreCase = true)) {
            val mainArtist = artist.split(',', ';').first()
                .replace(Regex("""(?i)\s*(feat\.?|ft\.?).*"""), "")
                .trim()
            if (mainArtist.isNotEmpty() && mainArtist != artist) {
                queries.add("$mainArtist $title".trim())
            }
        }

        // 2. Если в названии трека есть "(feat. ...)" или "(ft. ...)", пробуем без скобок фита
        val cleanTitle = title
            .replace(Regex("""\s*[\(\[][fF]eat.*?[\]\)]"""), "")
            .replace(Regex("""\s*[\(\[][fF]t\..*?[\]\)]"""), "")
            .trim()
        if (cleanTitle.isNotEmpty() && cleanTitle != title) {
            queries.add("$artist $cleanTitle".trim())
            val mainArtist = artist.split(',', ';').first().trim()
            if (mainArtist.isNotEmpty() && mainArtist != artist) {
                queries.add("$mainArtist $cleanTitle".trim())
            }
        }

        val distinctQueries = queries.distinct()

        for (q in distinctQueries) {
            for (plugin in plugins) {
                try {
                    Log.d(TAG, "[$step/$total] Trying ${plugin.name} for query: '$q'")
                    val results = withTimeoutOrNull(6_000) { plugin.search(q) }
                    val best = results?.firstOrNull { !it.audioUrl.isNullOrBlank() }
                    if (best != null) {
                        Log.d(TAG, "[$step/$total] Found in ${plugin.name}: ${best.artist} — ${best.title}")
                        return best
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[$step/$total] ${plugin.name} search error for '$q': ${e.message}")
                }
            }
        }

        return null
    }
}
