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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist

class GeniusApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    suspend fun getLyrics(artist: String, title: String): String? {
        return try {
            val cleanArtist = artist.split(",", ";", "&", "feat.", "ft.", ignoreCase = true).first().trim()
            val cleanTitle = title.replace(Regex("""\([^)]*\)"""), "").trim()

            // Ищем сначала по /api/search/song (чище и быстрее, чем multi)
            val lyricsUrl = searchSongUrl("$cleanArtist$cleanTitle", cleanArtist, cleanTitle)
                ?: searchSongUrl(cleanTitle, cleanArtist, cleanTitle)
                ?: run {
                    Log.d("GeniusApi", "No matching song URL for $artist -$title")
                    return null
                }

            Log.d("GeniusApi", "Fetching lyrics page: $lyricsUrl")

            val html = client.get(lyricsUrl) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                header(HttpHeaders.Referrer, "https://genius.com/")
            }.bodyAsText()

            if (html.contains("cf-challenge") || html.contains("Attention Required")) {
                Log.w("GeniusApi", "Lyrics page blocked by Cloudflare: $lyricsUrl")
                return null
            }

            val document = Jsoup.parse(html)
            val containers = document.select("div[data-lyrics-container=true]")
            if (containers.isEmpty()) {
                Log.d("GeniusApi", "No lyrics container for $lyricsUrl (len=${html.length})")
                return null
            }

            // Удаляем встроенные заголовки ("Contributors", "Translations", "XXX Lyrics", кнопки)
            containers.select("[data-exclude-from-selection=true], button, script, style").remove()

            var lyricsHtml = containers.joinToString("\n") { it.html() }
            lyricsHtml = lyricsHtml.replace(Regex("(?i)<br\\s*/?>"), "\n")

            val rawText = Jsoup.clean(
                lyricsHtml,
                "",
                Safelist.none(),
                Document.OutputSettings().prettyPrint(false)
            ).trim()

            val plainText = cleanPlainText(rawText)
            if (plainText.isNotBlank()) plainText else null
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            Log.w("GeniusApi", "getLyrics failed for $artist - $title:${e.message}")
            null
        }
    }

    private suspend fun searchSongUrl(query: String, expectedArtist: String, expectedTitle: String): String? {
        val response = client.get("https://genius.com/api/search/song") {
            parameter("q", query)
            parameter("per_page", 5)
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/json, text/plain, */*")
            header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en;q=0.8")
            header(HttpHeaders.Referrer, "https://genius.com/")
        }.bodyAsText()

        if (response.trimStart().startsWith("<!DOCTYPE") || response.contains("cf-challenge")) {
            Log.w("GeniusApi", "Search blocked by Cloudflare for '$query'")
            return null
        }

        val root = try {
            json.parseToJsonElement(response).jsonObject
        } catch (_: Exception) {
            return null
        }

        val sections = root["response"]?.jsonObject?.get("sections")?.jsonArray ?: return null
        val expArtistNorm = normalize(expectedArtist)
        val expTitleNorm = normalize(expectedTitle)

        var fallbackUrl: String? = null

        for (section in sections) {
            val hits = section.jsonObject["hits"]?.jsonArray ?: continue
            for (hit in hits) {
                val res = hit.jsonObject["result"]?.jsonObject ?: continue
                val url = res["url"]?.jsonPrimitive?.content ?: continue
                if (!url.startsWith("https://genius.com/")) continue

                val hitTitle = normalize(res["title"]?.jsonPrimitive?.content ?: "")
                val hitArtist = normalize(
                    res["primary_artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                        ?: res["artist_names"]?.jsonPrimitive?.content
                        ?: ""
                )

                val artistMatch = expArtistNorm.isNotBlank() &&
                        (hitArtist.contains(expArtistNorm) || expArtistNorm.contains(hitArtist))
                val titleMatch = expTitleNorm.isNotBlank() &&
                        (hitTitle == expTitleNorm || hitTitle.contains(expTitleNorm) || expTitleNorm.contains(hitTitle))

                if (artistMatch && titleMatch) return url
                if (fallbackUrl == null && artistMatch) fallbackUrl = url
            }
        }
        return fallbackUrl
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cleanPlainText(text: String): String {
        var t = text.replace(Regex("""\[[^\]]*]"""), " ")
        val lines = t.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // Убираем случайные шапки вида "12 Contributors ... Lyrics"
        while (lines.isNotEmpty() && (
                    lines.first().contains("Contributors", ignoreCase = true) ||
                            lines.first().contains("Translations", ignoreCase = true) ||
                            lines.first().endsWith("Lyrics", ignoreCase = true)
                    )
        ) {
            lines.removeAt(0)
        }

        return lines.joinToString("\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}