package com.mf.musicfree.data.repository

import android.util.Log
import com.mf.musicfree.data.api.GeniusApi
import com.mf.musicfree.data.api.KugouApi
import com.mf.musicfree.data.api.LrcLibApi
import com.mf.musicfree.data.api.NeteaseApi
import com.mf.musicfree.data.db.dao.LyricsDao
import com.mf.musicfree.data.model.CachedLyrics
import com.mf.musicfree.data.model.LyricsLine
import com.mf.musicfree.data.model.TrackLyrics
import com.mf.musicfree.util.isNetworkError
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LyricsRepository(
    private val neteaseApi: NeteaseApi,
    private val lrcLibApi: LrcLibApi,
    private val kugouApi: KugouApi,
    private val geniusApi: GeniusApi,
    private val lyricsDao: LyricsDao
) {
    private val memoryCache = mutableMapOf<String, TrackLyrics>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int? = null
    ): TrackLyrics? {
        val cacheKey = "$artist|$title"
        memoryCache[cacheKey]?.let {
            Log.d("LyricsRepository", "Memory hit: $cacheKey")
            return it
        }

        // Диск-кэш — переживает перезапуск и офлайн
        try {
            lyricsDao.get(artist, title)?.let { cached ->
                val trackLyrics = cached.toTrackLyrics()
                // Старый кэш Genius хранил мусор [Куплет]/"XXX Lyrics" — инвалидируем
                if (!trackLyrics.isSynced && isDirtyPlainCache(trackLyrics)) {
                    Log.d("LyricsRepository", "Dirty disk cache ignored: $cacheKey")
                    try { lyricsDao.delete(artist, title) } catch (_: Exception) {}
                } else {
                    memoryCache[cacheKey] = trackLyrics
                    Log.d("LyricsRepository", "Disk hit: $cacheKey")
                    return trackLyrics
                }
            }
            // Пробуем по чистому названию, если в кэше лежит под ним
            val clean = cleanTitle(title)
            if (clean != title) {
                lyricsDao.get(artist, clean)?.let { cached ->
                    val trackLyrics = cached.toTrackLyrics()
                    if (!trackLyrics.isSynced && isDirtyPlainCache(trackLyrics)) {
                        Log.d("LyricsRepository", "Dirty disk cache (clean) ignored: $artist|$clean")
                        try { lyricsDao.delete(artist, clean) } catch (_: Exception) {}
                    } else {
                        memoryCache[cacheKey] = trackLyrics
                        Log.d("LyricsRepository", "Disk hit (clean): $artist|$clean")
                        return trackLyrics
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("LyricsRepository", "Disk read failed: ${e.message}")
        }

        // Сеть — с таймаутом на всю цепочку
        val result: TrackLyrics? = try {
            withTimeout(15_000) {
                val cleanTitle = cleanTitle(title)
                var hadNetworkError = false
                fun markNetwork(e: Throwable) {
                    if (e.isNetworkError()) hadNetworkError = true
                }

                suspend fun fetch(a: String, t: String): TrackLyrics? {
                    // 1. Netease
                    try {
                        Log.d("LyricsRepository", "Try Netease: $a - $t")
                        val lrc = neteaseApi.getLyrics(a, t)
                        if (!lrc.isNullOrBlank()) {
                            val lines = parseLrc(lrc)
                            if (lines.isNotEmpty()) {
                                Log.d("LyricsRepository", "Netease hit: $a - $t (${lines.size} lines)")
                                return TrackLyrics(lines = lines, isSynced = true)
                            }
                        }
                        Log.d("LyricsRepository", "Netease miss: $a - $t")
                    } catch (e: Exception) {
                        Log.w("LyricsRepository", "Netease failed for $a - $t: ${e.message}")
                        markNetwork(e)
                    }

                    // 2. LRCLIB без duration
                    try {
                        Log.d("LyricsRepository", "Try LrcLib(noDur): $a - $t")
                        val resp = lrcLibApi.getLyrics(a, t, album, null)
                        parseLrcLibResponse(resp)?.let {
                            Log.d("LyricsRepository", "LrcLib(noDur) hit: $a - $t")
                            return it
                        }
                        Log.d("LyricsRepository", "LrcLib(noDur) miss: $a - $t")
                    } catch (e: Exception) {
                        Log.w("LyricsRepository", "LrcLib(noDur) failed for $a - $t: ${e.message}")
                        markNetwork(e)
                    }

                    // 3. LRCLIB с duration
                    if (duration != null && duration > 0) {
                        try {
                            Log.d("LyricsRepository", "Try LrcLib(dur=$duration): $a - $t")
                            val resp = lrcLibApi.getLyrics(a, t, album, duration)
                            parseLrcLibResponse(resp)?.let {
                                Log.d("LyricsRepository", "LrcLib(dur) hit: $a - $t")
                                return it
                            }
                            Log.d("LyricsRepository", "LrcLib(dur) miss: $a - $t")
                        } catch (e: Exception) {
                            Log.w("LyricsRepository", "LrcLib(dur) failed for $a - $t: ${e.message}")
                            markNetwork(e)
                        }
                    }

                    // 4. Kugou
                    try {
                        Log.d("LyricsRepository", "Try Kugou: $a - $t")
                        val lrc = kugouApi.getLyrics(a, t, duration?.times(1000)?.toLong())
                        if (!lrc.isNullOrBlank()) {
                            val lines = parseLrc(lrc)
                            if (lines.isNotEmpty()) {
                                Log.d("LyricsRepository", "Kugou hit: $a - $t (${lines.size} lines)")
                                return TrackLyrics(lines = lines, isSynced = true)
                            }
                        }
                        Log.d("LyricsRepository", "Kugou miss: $a - $t")
                    } catch (e: Exception) {
                        Log.w("LyricsRepository", "Kugou failed for $a - $t: ${e.message}")
                        markNetwork(e)
                    }

                    // 5. Genius (plain)
                    try {
                        Log.d("LyricsRepository", "Try Genius: $a - $t")
                        val text = geniusApi.getLyrics(a, t)
                        if (!text.isNullOrBlank()) {
                            Log.d("LyricsRepository", "Genius hit: $a - $t (${text.length} chars)")
                            return createPlainTextLyrics(text)
                        }
                        Log.d("LyricsRepository", "Genius miss: $a - $t")
                    } catch (e: Exception) {
                        Log.w("LyricsRepository", "Genius failed for $a - $t: ${e.message}")
                        markNetwork(e)
                    }

                    if (hadNetworkError) throw IOException("No connection")
                    return null
                }

                var res = fetch(artist, cleanTitle)
                if (res == null && cleanTitle != title) {
                    Log.d("LyricsRepository", "Clean title miss, retry original: $title")
                    // Сбрасываем флаг, вторая попытка отдельно
                    res = fetch(artist, title)
                }
                res
            }
        } catch (e: TimeoutCancellationException) {
            Log.w("LyricsRepository", "Lyrics fetch timeout for $artist - $title")
            throw IOException("Timeout", e)
        }

        if (result != null) {
            memoryCache[cacheKey] = result
            try {
                lyricsDao.put(result.toCached(artist, title))
                Log.d("LyricsRepository", "Cached to disk: $cacheKey")
            } catch (e: Exception) {
                Log.w("LyricsRepository", "Disk write failed: ${e.message}")
            }
        } else {
            Log.d("LyricsRepository", "Lyrics not found: $cacheKey")
        }

        return result
    }

    private fun CachedLyrics.toTrackLyrics(): TrackLyrics {
        val lines: List<LyricsLine> = try {
            json.decodeFromString(linesJson)
        } catch (_: Exception) {
            emptyList()
        }
        return TrackLyrics(lines = lines, isSynced = isSynced, plainText = plainText)
    }

    private fun TrackLyrics.toCached(artist: String, title: String): CachedLyrics {
        val linesJson = try {
            json.encodeToString(lines)
        } catch (_: Exception) {
            "[]"
        }
        return CachedLyrics(
            artist = artist,
            title = title,
            isSynced = isSynced,
            plainText = plainText,
            linesJson = linesJson
        )
    }

    /**
     * Убирает из названия всё в круглых скобках вместе со скобками:
     * "ЧСВ (Single 2019)" -> "ЧСВ", "Track (Prod. by X) (2024)" -> "Track"
     */
    private fun cleanTitle(title: String): String {
        var s = title
        val parens = Regex("\\s*\\([^()]*\\)")
        while (parens.containsMatchIn(s)) {
            s = s.replace(parens, "")
        }
        s = s.trim()
            .replace(Regex("\\s+-\\s*$"), "")
            .trim()
        return s.ifBlank { title }
    }

    private fun parseLrcLibResponse(response: com.mf.musicfree.data.api.LrcLibResponse?): TrackLyrics? {
        response ?: return null
        val synced = response.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return TrackLyrics(lines = lines, isSynced = true)
        }
        val plain = response.plainLyrics
        if (!plain.isNullOrBlank()) return createPlainTextLyrics(plain)
        return null
    }

    private fun createPlainTextLyrics(text: String): TrackLyrics {
        return TrackLyrics(
            lines = text.lines().mapIndexed { i, line ->
                LyricsLine(timeMs = i * 5000L, text = line)
            },
            isSynced = false,
            plainText = text
        )
    }

    private fun isDirtyPlainCache(lyrics: TrackLyrics): Boolean {
        val t = lyrics.plainText ?: lyrics.lines.joinToString("\n") { it.text }
        return t.contains("[") && t.contains("]") || t.contains("Lyrics", ignoreCase = true)
    }

    private fun parseLrc(lrc: String): List<LyricsLine> {
        val regex = "\\[(\\d{2,}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)".toRegex()
        return lrc.lines().mapNotNull { line ->
            try {
                val match = regex.find(line) ?: return@mapNotNull null
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msPart = match.groupValues[3]
                val ms = when (msPart.length) {
                    0 -> 0L
                    1 -> msPart.toLong() * 100
                    2 -> msPart.toLong() * 10
                    else -> msPart.toLong()
                }
                val timeMs = min * 60_000 + sec * 1000 + ms
                val text = match.groupValues[4].trim()
                if (text.isBlank() && timeMs == 0L) return@mapNotNull null
                LyricsLine(timeMs = timeMs, text = text)
            } catch (_: Exception) { null }
        }.sortedBy { it.timeMs }
    }
}
