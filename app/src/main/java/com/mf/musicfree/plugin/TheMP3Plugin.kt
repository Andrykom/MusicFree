package com.mf.musicfree.plugin

import android.util.Log
import com.mf.musicfree.data.api.CoverProvider
import com.mf.musicfree.plugin.exception.PluginException
import com.mf.musicfree.util.isNetworkError
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.IDN
import java.net.URLEncoder

class TheMP3Plugin(
    private val coverProvider: CoverProvider
) : SourcePlugin {
    override val name = "TheMP3"

    private val client = HttpClient(Android) {
        followRedirects = true
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }
        engine {
            connectTimeout = 10000
            socketTimeout = 15000
        }
    }
    private val baseUrl = "https://themp3.info"

    override suspend fun search(query: String): List<SearchTrack> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        try {
            val result = searchBySubdomain(query)
            if (result.isNotEmpty()) {
                Log.d("TheMP3Plugin", "Subdomain search succeeded: ${result.size} tracks")
                return@withContext enrichWithCovers(result)
            }
        } catch (e: PluginException) {
            lastException = e
            Log.w("TheMP3Plugin", "Subdomain failed: ${e.message}")
        }

        try {
            val result = searchByForm(query)
            if (result.isNotEmpty()) {
                Log.d("TheMP3Plugin", "Form search succeeded: ${result.size} tracks")
                return@withContext enrichWithCovers(result)
            }
        } catch (e: PluginException) {
            lastException = e
            Log.w("TheMP3Plugin", "Form GET failed: ${e.message}")
        }

        try {
            val result = searchByPost(query)
            Log.d("TheMP3Plugin", "POST search: ${result.size} tracks")
            return@withContext enrichWithCovers(result)
        } catch (e: PluginException) {
            lastException = e
            Log.w("TheMP3Plugin", "POST failed: ${e.message}")
        }

        // Сюда доходим, только если все три стратегии кинули исключение
        throw lastException!!
    }

    private suspend fun enrichWithCovers(tracks: List<SearchTrack>): List<SearchTrack> {
        if (tracks.isEmpty()) return tracks
        return coroutineScope {
            tracks.map { track ->
                async {
                    try {
                        val cover = coverProvider.searchCover(track.artist, track.title)
                        track.copy(coverUrl = cover)
                    } catch (e: Exception) {
                        Log.e("TheMP3Plugin", "Cover fetch failed for ${track.title}", e)
                        track
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun searchBySubdomain(query: String): List<SearchTrack> {
        val clean = query.lowercase()
            .replace(Regex("[^a-zа-яё0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        if (clean.isBlank()) return emptyList()

        val punycode = try { IDN.toASCII(clean) } catch (_: Exception) { clean }
        val url = "https://$punycode.themp3.info/"

        Log.d("TheMP3Plugin", "Trying subdomain: $url")

        val html = try {
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "text/html")
                header(HttpHeaders.Referrer, baseUrl)
            }

            Log.d("TheMP3Plugin", "Subdomain status: ${response.status}")
            if (response.status == HttpStatusCode.NotFound) return emptyList()
            if (response.status.value !in 200..299) {
                throw PluginException.ServerUnavailable(url)
            }
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is PluginException) throw e
            if (e.isNetworkError()) throw PluginException.NoConnection()
            throw PluginException.ServerUnavailable(url)
        }

        return parseHtml(html, url)
    }

    private suspend fun searchByForm(query: String): List<SearchTrack> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search/?song=$encoded"

        Log.d("TheMP3Plugin", "Trying form GET: $url")

        val html = try {
            val response: HttpResponse = client.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0")
                header(HttpHeaders.Accept, "text/html")
            }

            Log.d("TheMP3Plugin", "Form GET status: ${response.status}")
            if (response.status.value !in 200..299) {
                throw PluginException.ServerUnavailable(url)
            }
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is PluginException) throw e
            if (e.isNetworkError()) throw PluginException.NoConnection()
            throw PluginException.ServerUnavailable(url)
        }

        return parseHtml(html, baseUrl)
    }

    private suspend fun searchByPost(query: String): List<SearchTrack> {
        Log.d("TheMP3Plugin", "Trying POST search")

        val html = try {
            val response: HttpResponse = client.post("$baseUrl/search/") {
                header(HttpHeaders.UserAgent, "Mozilla/5.0")
                header(HttpHeaders.Referrer, baseUrl)
                setBody(FormDataContent(Parameters.build {
                    append("song", query)
                }))
            }

            Log.d("TheMP3Plugin", "POST status: ${response.status}")
            if (response.status.value !in 200..299) {
                throw PluginException.ServerUnavailable(baseUrl)
            }
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is PluginException) throw e
            if (e.isNetworkError()) throw PluginException.NoConnection()
            throw PluginException.ServerUnavailable(baseUrl)
        }

        return parseHtml(html, baseUrl)
    }

    private fun parseHtml(html: String, baseUri: String): List<SearchTrack> {
        Log.d("TheMP3Plugin", "HTML length: ${html.length}")
        val doc = Jsoup.parse(html, baseUri)
        Log.d("TheMP3Plugin", "Body snippet: ${doc.body().html().take(2000)}")

        var elements: Elements = doc.select("li.__adv_list_track")
        Log.d("TheMP3Plugin", "Selector '__adv_list_track' found: ${elements.size}")

        if (elements.isEmpty()) {
            elements = doc.select(".playlist-item, .track-item, .adv_list_item")
            Log.d("TheMP3Plugin", "Fallback selectors found: ${elements.size}")
        }

        if (elements.isEmpty()) {
            val trackLinks = doc.select("a[href*=\"/t/\"]")
            Log.d("TheMP3Plugin", "Track links found: ${trackLinks.size}")
            val parents = trackLinks.mapNotNull { link ->
                link.parents().find { it.tagName() in listOf("li", "div") }
            }.distinct()
            elements = Elements(parents)
            Log.d("TheMP3Plugin", "Parent elements found: ${elements.size}")
        }

        val tracks = elements.mapNotNull { parseTrack(it) }
        Log.d("TheMP3Plugin", "Parsed tracks: ${tracks.size}")
        return tracks
    }

    private fun parseTrack(element: Element): SearchTrack? {
        val titleElem = element.selectFirst(".playlist-name-title em, .track-title, a[href*=\"/t/\"] em")
        val title = titleElem?.text()?.trim() ?: return null

        val artistElem = element.selectFirst(".playlist-name-artist a, .track-artist, a[href*=\"/artist/\"]")
        val artist = artistElem?.text()?.trim() ?: "Unknown"

        val playElem = element.selectFirst("a.playlist-play[data-url], a[data-url]")
        val audioUrl = playElem?.attr("data-url") ?: ""

        val finalAudioUrl = if (audioUrl.isNotBlank() && audioUrl.startsWith("http")) {
            audioUrl
        } else {
            val mp3Link = element.selectFirst("a[href*=\".mp3\"]")
            mp3Link?.attr("abs:href") ?: ""
        }

        if (finalAudioUrl.isBlank()) return null

        val durationElem = element.selectFirst(".playlist-duration, .duration")
        val durationText = durationElem?.text()?.trim() ?: "00:00"

        val pageLink = element.selectFirst("a[href*=\"/t/\"]")
        val pageUrl = pageLink?.attr("abs:href") ?: ""
        val trackId = Regex("/t/(\\d+)-").find(pageUrl)?.groupValues?.get(1)
            ?: title.hashCode().toString()

        Log.d("TheMP3Plugin", "🎵 $artist - $title | URL: $finalAudioUrl")

        return SearchTrack(
            id = "tmp3_$trackId",
            title = title,
            artist = artist,
            duration = parseDuration(durationText),
            durationText = durationText,
            quality = "MP3",
            coverUrl = null,
            audioUrl = finalAudioUrl
        )
    }

    private fun parseDuration(durationStr: String): Long {
        val parts = durationStr.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60_000L + (parts[1].toIntOrNull() ?: 0) * 1000L
            3 -> (parts[0].toIntOrNull() ?: 0) * 3_600_000L + (parts[1].toIntOrNull() ?: 0) * 60_000L + (parts[2].toIntOrNull() ?: 0) * 1000L
            else -> 0L
        }
    }
}