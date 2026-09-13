package com.mf.musicfree.plugin

import android.util.Log
import com.mf.musicfree.data.api.CoverProvider
import com.mf.musicfree.plugin.exception.PluginException
import com.mf.musicfree.util.isNetworkError
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DriveMusicPlugin(
    private val coverProvider: CoverProvider
) : SourcePlugin {
    override val name = "DriveMusic"

    private val client = HttpClient(Android) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
        }
        engine {
            connectTimeout = 10000
            socketTimeout = 15000
        }
    }
    private val baseUrl = "https://ru.drivemusic.me"

    override suspend fun search(query: String): List<SearchTrack> = withContext(Dispatchers.IO) {
        val html = try {
            val response: HttpResponse = client.get(baseUrl) {
                parameter("do", "search")
                parameter("subaction", "search")
                parameter("story", query)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.Referrer, baseUrl)
            }

            if (response.status.value !in 200..299) {
                throw PluginException.ServerUnavailable(baseUrl)
            }
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is PluginException) throw e
            if (e.isNetworkError()) {
                throw PluginException.NoConnection()
            }
            throw PluginException.ServerUnavailable(baseUrl)
        }

        val doc = try {
            Jsoup.parse(html, baseUrl)
        } catch (e: Exception) {
            throw PluginException.ParseError()
        }

        val tracks = parseTracks(doc)

        if (tracks.isEmpty()) {
            return@withContext emptyList()
        }

        coroutineScope {
            tracks.mapIndexed { index, track ->
                async {
                    if (index < 6) {
                        try {
                            val cover = kotlinx.coroutines.withTimeoutOrNull(1500) {
                                coverProvider.searchCover(track.artist, track.title)
                            }
                            track.copy(coverUrl = cover)
                        } catch (e: Exception) {
                            track
                        }
                    } else {
                        track
                    }
                }
            }.awaitAll()
        }
    }

    private fun parseTracks(doc: Document): List<SearchTrack> {
        return doc.select("div.music-popular-wrapper").mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(element: Element): SearchTrack? {
        val nameElem = element.selectFirst("a.popular-play-author") ?: return null
        val title = nameElem.text().trim()
        val pageUrl = nameElem.attr("abs:href")
        val trackId = extractId(pageUrl)

        val artistElem = element.selectFirst("div.popular-play-composition")
        val artists = artistElem?.select("a")?.map { it.text().trim() } ?: emptyList()
        val artist = artists.joinToString(", ").ifEmpty { "Unknown" }

        val playButton = element.selectFirst("button.popular-play__item")
        val downloadUrl = playButton?.attr("data-url") ?: return null
        if (downloadUrl.isBlank()) return null

        val qualityElem = element.selectFirst("div.popular-download-date")
        val quality = qualityElem?.text()?.trim() ?: "320 kbps"

        val durationElem = element.selectFirst("div.popular-download-number")
        val durationText = durationElem?.text()?.trim() ?: "00:00"

        return SearchTrack(
            id = "dm_$trackId",
            title = title,
            artist = artist,
            duration = parseDuration(durationText),
            durationText = durationText,
            quality = quality,
            coverUrl = null,
            audioUrl = downloadUrl
        )
    }

    private fun extractId(url: String): Int {
        return Regex("/(\\d+)-").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun parseDuration(durationStr: String): Long {
        val parts = durationStr.split(":")
        return if (parts.size == 2) {
            (parts[0].toIntOrNull() ?: 0) * 60_000L +
                    (parts[1].toIntOrNull() ?: 0) * 1000L
        } else 0L
    }
}