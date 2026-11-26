package com.mf.musicfree.data.api

import android.util.Log
import com.mf.musicfree.util.isNetworkError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GeniusApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Ищет текст через публичный JSON API Shazam (без Cloudflare и API-ключей).
     * Имя класса сохранено для совместимости с LyricsRepository.
     */
    suspend fun getLyrics(artist: String, title: String): String? {
        return try {
            val cleanArtist = artist.split(",", ";", "&", "feat.", "ft.", ignoreCase = true).first().trim()
            val cleanTitle = title.replace(Regex("""\([^)]*\)"""), "").trim()

            val trackKey = searchShazamTrackKey("$cleanArtist $cleanTitle", cleanArtist, cleanTitle)
                ?: searchShazamTrackKey(cleanTitle, cleanArtist, cleanTitle)
                ?: run {
                    Log.d("ShazamLyrics", "No Shazam match for $artist - $title")
                    return null
                }

            fetchShazamLyrics(trackKey)
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            Log.w("ShazamLyrics", "getLyrics failed for $artist - $title: ${e.message}")
            null
        }
    }

    private suspend fun searchShazamTrackKey(
        query: String,
        expectedArtist: String,
        expectedTitle: String
    ): String? {
        val response = client.get("https://www.shazam.com/services/search/v3/ru/RU/web/search") {
            parameter("query", query)
            parameter("numResults", 5)
            parameter("offset", 0)
            parameter("types", "songs")
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en;q=0.8")
        }.bodyAsText()

        val root = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return null
        }

        val hits = root["tracks"]?.jsonObject?.get("hits")?.jsonArray ?: return null
        val expArtist = normalize(expectedArtist)
        val expTitle = normalize(expectedTitle)

        var fallbackKey: String? = null
        for (hit in hits) {
            val trackObj = hit.jsonObject["track"]?.jsonObject ?: continue
            val key = trackObj["key"]?.jsonPrimitive?.content ?: continue
            val hitTitle = normalize(trackObj["title"]?.jsonPrimitive?.content ?: "")
            val hitArtist = normalize(trackObj["subtitle"]?.jsonPrimitive?.content ?: "")

            val artistMatch = expArtist.isNotBlank() &&
                    (hitArtist.contains(expArtist) || expArtist.contains(hitArtist))
            val titleMatch = expTitle.isNotBlank() &&
                    (hitTitle == expTitle || hitTitle.contains(expTitle) || expTitle.contains(hitTitle))

            if (artistMatch && titleMatch) return key
            if (fallbackKey == null && artistMatch) fallbackKey = key
        }
        return fallbackKey
    }

    private suspend fun fetchShazamLyrics(trackKey: String): String? {
        val response = client.get("https://www.shazam.com/discovery/v5/ru/RU/android/-/track/$trackKey") {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json")
        }.bodyAsText()

        val root = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return null
        }

        val sections = root["sections"]?.jsonArray ?: return null
        val lyricsSection = sections.firstOrNull {
            it.jsonObject["type"]?.jsonPrimitive?.content.equals("LYRICS", ignoreCase = true)
        }?.jsonObject ?: return null

        val lines = lyricsSection["text"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?: return null

        val text = lines.joinToString("\n").trim()
        return text.ifBlank { null }
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}