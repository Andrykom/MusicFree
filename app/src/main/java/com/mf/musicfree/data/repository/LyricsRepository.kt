package com.mf.musicfree.data.repository

import com.mf.musicfree.data.api.GeniusApi
import com.mf.musicfree.data.api.KugouApi
import com.mf.musicfree.data.api.LrcLibApi
import com.mf.musicfree.data.api.NeteaseApi
import com.mf.musicfree.data.model.LyricsLine
import com.mf.musicfree.data.model.TrackLyrics
import kotlinx.coroutines.withTimeoutOrNull

class LyricsRepository(
    private val neteaseApi: NeteaseApi,
    private val lrcLibApi: LrcLibApi,
    private val kugouApi: KugouApi,
    private val geniusApi: GeniusApi
) {
    private val cache = mutableMapOf<String, TrackLyrics>()

    suspend fun getLyrics(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int? = null
    ): TrackLyrics? {
        val cacheKey = "$artist|$title"
        cache[cacheKey]?.let { return it }

        val result = withTimeoutOrNull(15_000) {
            // В названиях часто висит мусор в скобках — "(Single 2019)", "(Prod. by ...)",
            // он мешает матчингу. Ищем по чистому названию, при неудаче — по оригиналу.
            val cleanTitle = cleanTitle(title)
            fetchLyrics(artist, cleanTitle, album, duration)
                ?: (if (cleanTitle != title) fetchLyrics(artist, title, album, duration) else null)
        }

        result?.let { cache[cacheKey] = it }
        return result
    }

    private suspend fun fetchLyrics(
        artist: String,
        title: String,
        album: String?,
        duration: Int?
    ): TrackLyrics? {
        // 1. Netease
        val neteaseLrc = try {
            neteaseApi.getLyrics(artist, title)
        } catch (_: Exception) { null }

        if (!neteaseLrc.isNullOrBlank()) {
            val lines = parseLrc(neteaseLrc)
            if (lines.isNotEmpty()) {
                return TrackLyrics(lines = lines, isSynced = true)
            }
        }

        // 2. LRCLIB без duration
        val lrcLibNoDur = try {
            lrcLibApi.getLyrics(artist, title, album, null)
        } catch (_: Exception) { null }
        parseLrcLibResponse(lrcLibNoDur)?.let { return it }

        // 3. LRCLIB с duration
        if (duration != null && duration > 0) {
            val lrcLibDur = try {
                lrcLibApi.getLyrics(artist, title, album, duration)
            } catch (_: Exception) { null }
            parseLrcLibResponse(lrcLibDur)?.let { return it }
        }

        // 4. Kugou
        val kugouLrc = try {
            kugouApi.getLyrics(artist, title, duration?.times(1000)?.toLong())
        } catch (_: Exception) { null }

        if (!kugouLrc.isNullOrBlank()) {
            val lines = parseLrc(kugouLrc)
            if (lines.isNotEmpty()) {
                return TrackLyrics(lines = lines, isSynced = true)
            }
        }

        // 5. Genius (обычный текст, без синхронизации)
        val geniusText = try {
            geniusApi.getLyrics(artist, title)
        } catch (_: Exception) { null }

        if (!geniusText.isNullOrBlank()) {
            return createPlainTextLyrics(geniusText)
        }

        return null
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