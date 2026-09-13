package com.mf.musicfree.data.importer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.regex.Pattern

data class ExtractedTrack(
    val artist: String,
    val title: String
)

enum class PlaylistPlatform {
    YANDEX,
    UNKNOWN
}

class PlaylistWebExtractor {

    companion object {
        private const val TAG = "MusicImport"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    fun detectPlatform(url: String): PlaylistPlatform {
        val lower = url.lowercase()
        return when {
            lower.contains("music.yandex") || lower.contains("yandex.ru/music") || lower.contains("yandex.com/music") -> PlaylistPlatform.YANDEX
            else -> PlaylistPlatform.UNKNOWN
        }
    }

    suspend fun extractTracks(rawUrl: String): Result<List<ExtractedTrack>> = withContext(Dispatchers.IO) {
        val cleanUrl = cleanUrl(rawUrl)
        val platform = detectPlatform(cleanUrl)

        Log.i(TAG, "extractTracks() started for URL: $rawUrl (clean: $cleanUrl, platform: $platform)")

        if (platform == PlaylistPlatform.UNKNOWN) {
            val msg = "Неподдерживаемая ссылка. Поддерживаются ссылки на плейлисты Яндекс Музыки (music.yandex.ru)."
            Log.w(TAG, msg)
            return@withContext Result.failure(IllegalArgumentException(msg))
        }

        try {
            val tracks = extractYandexTracks(cleanUrl)
            if (tracks.isEmpty()) {
                val msg = "Не удалось извлечь треки из плейлиста Яндекс Музыки (найдено 0 треков). Убедитесь, что плейлист открыт для всех (не приватный), или попробуйте другую ссылку."
                Log.w(TAG, msg)
                Result.failure(IllegalStateException(msg))
            } else {
                Log.i(TAG, "Successfully extracted ${tracks.size} tracks from Yandex Music")
                Result.success(tracks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $cleanUrl: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun cleanUrl(url: String): String {
        return url.trim()
            .substringBefore("?utm_")
            .substringBefore("&utm_")
    }

    private fun extractYandexIdentifiers(url: String): Pair<String?, Pair<String, String>?> {
        // Проверка формата /playlists/{uuid}
        val uuidMatch = Regex("""/playlists/([^/?#]+)""").find(url)
        val uuid = uuidMatch?.groupValues?.get(1)

        // Проверка формата /users/{owner}/playlists/{kind}
        val userMatch = Regex("""/users/([^/?#]+)/playlists/([^/?#]+)""").find(url)
        val ownerKind = if (userMatch != null) {
            Pair(userMatch.groupValues[1], userMatch.groupValues[2])
        } else null

        return Pair(uuid, ownerKind)
    }

    private fun extractYandexTracks(url: String): List<ExtractedTrack> {
        val playlist = mutableListOf<ExtractedTrack>()
        val (uuid, ownerKind) = extractYandexIdentifiers(url)
        Log.i(TAG, "extractYandexTracks: extracted identifiers: uuid=$uuid, ownerKind=$ownerKind")

        // 1. Стратегия через прямые API эндпоинты Яндекс Музыки
        if (uuid != null) {
            val apiCandidates = listOf(
                "https://api.music.yandex.net/playlist/$uuid",
                "https://api.music.yandex.net/playlists?uuid=$uuid",
                "https://music.yandex.ru/api/v2.1/handlers/playlist/$uuid",
                "https://music.yandex.ru/handlers/playlist.jsx?playlistUuid=$uuid",
                "https://music.yandex.ru/handlers/playlist.jsx?playlist=$uuid"
            )
            for (apiUrl in apiCandidates) {
                Log.d(TAG, "Attempting Yandex API endpoint: $apiUrl")
                try {
                    val resp = Jsoup.connect(apiUrl)
                        .userAgent(USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "ru-RU,ru;q=0.9")
                        .header("X-Retpath-Y", url)
                        .referrer(url)
                        .ignoreContentType(true)
                        .maxBodySize(0)
                        .timeout(30000)
                        .execute()

                    Log.d(TAG, "API candidate response: code=${resp.statusCode()}, contentType=${resp.contentType()}, len=${resp.body().length}")
                    if (resp.statusCode() in 200..299 && resp.body().isNotBlank()) {
                        parseTracksFromJson(resp.body(), playlist)
                        if (playlist.isNotEmpty()) {
                            Log.i(TAG, "Successfully extracted ${playlist.size} tracks from Yandex API: $apiUrl")
                            return playlist
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "API candidate request failed ($apiUrl): ${e.message}")
                }
            }
        }

        if (ownerKind != null) {
            val (owner, kind) = ownerKind
            val userApiCandidates = listOf(
                "https://music.yandex.ru/handlers/playlist.jsx?owner=$owner&kinds=$kind",
                "https://api.music.yandex.net/users/$owner/playlists/$kind"
            )
            for (apiUrl in userApiCandidates) {
                Log.d(TAG, "Attempting Yandex User API endpoint: $apiUrl")
                try {
                    val resp = Jsoup.connect(apiUrl)
                        .userAgent(USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "ru-RU,ru;q=0.9")
                        .header("X-Retpath-Y", url)
                        .referrer(url)
                        .ignoreContentType(true)
                        .maxBodySize(0)
                        .timeout(30000)
                        .execute()

                    Log.d(TAG, "User API response: code=${resp.statusCode()}, len=${resp.body().length}")
                    if (resp.statusCode() in 200..299 && resp.body().isNotBlank()) {
                        parseTracksFromJson(resp.body(), playlist)
                        if (playlist.isNotEmpty()) {
                            Log.i(TAG, "Successfully extracted ${playlist.size} tracks from User API: $apiUrl")
                            return playlist
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "User API candidate request failed ($apiUrl): ${e.message}")
                }
            }
        }

        // 2. Стратегия через загрузку и парсинг веб-страницы
        Log.d(TAG, "Fetching Yandex Music web page: $url")
        val response = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .maxBodySize(0)
            .timeout(30000)
            .followRedirects(true)
            .execute()

        val finalUrl = response.url().toString()
        val doc = response.parse()
        val title = doc.title()
        val bodyText = doc.body()?.text() ?: ""
        val rawHtml = response.body()

        Log.d(TAG, "Yandex Music response: status=${response.statusCode()}, url=$finalUrl, title='$title', bodyTextLen=${bodyText.length}, htmlLen=${rawHtml.length}")

        // Проверка на гео-блокировку
        if (bodyText.contains("недоступна в вашем регионе") || title.contains("недоступна в вашем регионе")) {
            Log.w(TAG, "Yandex Music returned geo-block message: 'недоступна в вашем регионе'")
            throw IllegalStateException(
                "Яндекс Музыка недоступна в регионе вашего подключения (сервис заблокирован за пределами РФ/СНГ без VPN). Включите российский VPN и попробуйте снова."
            )
        }

        // 2.1. Schema.org JSON-LD (<script type="application/ld+json">)
        val ldScripts = doc.select("script[type=application/ld+json]")
        Log.d(TAG, "Found ${ldScripts.size} application/ld+json scripts")
        for (ldScript in ldScripts) {
            val ldContent = ldScript.html()
            parseTracksFromJson(ldContent, playlist)
            if (playlist.isNotEmpty()) {
                Log.i(TAG, "Extracted ${playlist.size} tracks from application/ld+json")
                return playlist
            }
        }

        // 2.2. script#noscript-data
        val dataScript = doc.select("script#noscript-data").first()
        if (dataScript != null) {
            val jsonContent = dataScript.html()
            Log.d(TAG, "Found script#noscript-data (length: ${jsonContent.length})")
            parseTracksFromJson(jsonContent, playlist)
            if (playlist.isNotEmpty()) {
                Log.i(TAG, "Extracted ${playlist.size} tracks from noscript-data")
                return playlist
            }
        } else {
            Log.d(TAG, "script#noscript-data not found in page")
        }

        // 2.3. Сканирование всех <script> тегов
        Log.d(TAG, "Scanning all script tags for tracks data...")
        for (script in doc.select("script")) {
            val scriptContent = script.html()
            if (scriptContent.contains("\"tracks\"") || scriptContent.contains("\"track\"") || scriptContent.contains("\"playlist\"")) {
                val jsonStart = scriptContent.indexOf('{')
                val jsonEnd = scriptContent.lastIndexOf('}')
                if (jsonStart != -1 && jsonEnd > jsonStart) {
                    parseTracksFromJson(scriptContent.substring(jsonStart, jsonEnd + 1), playlist)
                } else {
                    parseTracksFromJson(scriptContent, playlist)
                }
                if (playlist.isNotEmpty()) {
                    Log.i(TAG, "Extracted ${playlist.size} tracks from embedded script tag")
                    return playlist
                }
            }
        }

        // 2.4. Поиск в HTML-элементах страницы
        val trackElements = doc.select(".light-track, [data-testid*='track'], [class*='Track_root'], [class*='PlaylistItem'], div[role='row']")
        Log.d(TAG, "Checking HTML track elements: found ${trackElements.size}")
        for (element in trackElements) {
            val trackTitle = element.select(".light-track__title, [data-testid*='track-title'], [data-testid*='title'], [class*='title']").text().trim()
            val trackArtist = element.select(".light-track__artists, [data-testid*='track-artist'], [data-testid*='artist'], [class*='artist']").text().trim()
            if (trackTitle.isNotEmpty()) {
                playlist.add(ExtractedTrack(trackArtist.ifEmpty { "Неизвестный исполнитель" }, trackTitle))
            }
        }
        if (playlist.isNotEmpty()) {
            Log.i(TAG, "Extracted ${playlist.size} tracks from HTML elements")
            return playlist
        }

        // 2.5. Проверка ссылок на треки в документе (a[href*="/track/"])
        val trackLinks = doc.select("a[href*='/track/']")
        Log.d(TAG, "Checking track links a[href*='/track/']: found ${trackLinks.size}")
        for (link in trackLinks) {
            val trackTitle = link.text().trim()
            if (trackTitle.isNotEmpty() && !trackTitle.equals("Слушать", ignoreCase = true)) {
                val parent = link.parent()?.parent()
                val artistLink = parent?.select("a[href*='/artist/']")?.firstOrNull()
                val artist = artistLink?.text()?.trim() ?: "Неизвестный исполнитель"
                playlist.add(ExtractedTrack(artist, trackTitle))
            }
        }
        if (playlist.isNotEmpty()) {
            Log.i(TAG, "Extracted ${playlist.size} tracks from track links")
            return playlist
        }

        if (playlist.isEmpty()) {
            val snippet = bodyText.take(500)
            Log.w(TAG, "Yandex Music: 0 tracks found. Title: '$title', Body preview: $snippet")
            throw IllegalStateException(
                "Не удалось найти треки в плейлисте Яндекс Музыки (0 треков). Убедитесь, что плейлист открыт для всех (не приватный), или попробуйте другую ссылку."
            )
        }

        return playlist
    }

    internal fun parseTracksFromJson(jsonContent: String, output: MutableList<ExtractedTrack>) {
        val trimmed = jsonContent.trim()
        val jsonString = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            trimmed
        } else {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start != -1 && end > start) trimmed.substring(start, end + 1) else trimmed
        }

        // 1. Парсинг через org.json (поддерживает любые валидные ответы API и Schema.org ld+json)
        try {
            if (jsonString.startsWith("[")) {
                val array = org.json.JSONArray(jsonString)
                parseTrackArray(array, output)
                if (output.isNotEmpty()) {
                    Log.d(TAG, "Successfully parsed ${output.size} tracks from top-level JSONArray")
                    return
                }
            } else {
                val root = JSONObject(jsonString)
                val result = root.optJSONObject("result") ?: root
                val playlistObj = result.optJSONObject("playlist") ?: result
                val tracksArray = playlistObj.optJSONArray("tracks")
                    ?: playlistObj.optJSONArray("track")
                    ?: playlistObj.optJSONArray("trackIds")
                    ?: result.optJSONArray("tracks")
                    ?: root.optJSONArray("tracks")

                if (tracksArray != null) {
                    parseTrackArray(tracksArray, output)
                    if (output.isNotEmpty()) {
                        Log.d(TAG, "Successfully parsed ${output.size} tracks via JSONObject")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "JSONObject parse attempt not applicable or failed: ${e.message}")
        }

        // 2. Безопасный Fallback: построчное/поблочное выделение треков по чанкам "track": { ... }
        // В отличие от обычных регулярных выражений, этот метод изолирует каждый трек в отдельный контекст
        // и принципиально не может сопоставить имя владельца плейлиста или лейбла с названием трека.
        if (output.isEmpty() && jsonString.contains("\"track\"")) {
            val trackChunks = jsonString.split(Regex(""""track"\s*:\s*\{"""))
            for (i in 1 until trackChunks.size) {
                val chunk = trackChunks[i]
                val titleMatch = Regex(""""title"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(chunk) ?: continue
                val title = unescapeUnicode(titleMatch.groupValues[1]).trim()
                if (title.isEmpty()) continue

                val artistsBlockMatch = Regex(""""artists"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(chunk)
                val artist = if (artistsBlockMatch != null) {
                    val names = Regex(""""name"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
                        .findAll(artistsBlockMatch.groupValues[1])
                        .map { unescapeUnicode(it.groupValues[1]).trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                    names.joinToString(", ")
                } else ""

                if (artist.isEmpty()) continue

                output.add(ExtractedTrack(artist, title))
            }
            if (output.isNotEmpty()) {
                Log.d(TAG, "Successfully parsed ${output.size} tracks via safe chunk fallback")
            }
        }
    }

    private fun parseTrackArray(tracksArray: org.json.JSONArray, output: MutableList<ExtractedTrack>) {
        for (i in 0 until tracksArray.length()) {
            val item = tracksArray.optJSONObject(i) ?: continue
            val trackObj = item.optJSONObject("track") ?: item
            val title = trackObj.optString("title").ifEmpty { trackObj.optString("name") }.trim()

            val artistsArray = trackObj.optJSONArray("artists")
            val artist = if (artistsArray != null && artistsArray.length() > 0) {
                val names = mutableListOf<String>()
                for (j in 0 until artistsArray.length()) {
                    val a = artistsArray.optJSONObject(j)
                    val name = a?.optString("name")?.trim()
                    if (!name.isNullOrEmpty()) names.add(name)
                }
                names.joinToString(", ")
            } else {
                val byArtist = trackObj.optJSONObject("byArtist")
                if (byArtist != null) {
                    byArtist.optString("name").trim()
                } else {
                    trackObj.optString("artist").trim()
                }
            }

            if (title.isNotEmpty()) {
                output.add(ExtractedTrack(artist.ifEmpty { "Неизвестный исполнитель" }, title))
            }
        }
    }

    private fun unescapeUnicode(input: String): String {
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(input) { matchResult ->
            matchResult.groupValues[1].toInt(16).toChar().toString()
        }.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
