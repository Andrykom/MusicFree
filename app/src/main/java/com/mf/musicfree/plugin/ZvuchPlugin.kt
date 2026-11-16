package com.mf.musicfree.plugin

import android.util.Log
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
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ZvuchPlugin(private val client: HttpClient) : SourcePlugin {
    override val name = "Zvuch"
    private val baseUrl = "https://wwv.zvuch.com"

    override suspend fun search(query: String): List<SearchTrack> = withContext(Dispatchers.IO) {
        // /search?q= редиректит на канонический /tracks/<query>?search=1 — идём с редиректами
        val url = "$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
        Log.d("ZvuchPlugin", "Search: $url")

        val html = try {
            val response: HttpResponse = client.get("$baseUrl/search") {
                parameter("q", query)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.Referrer, baseUrl)
            }

            Log.d("ZvuchPlugin", "Status: ${response.status}")
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
        Log.d("ZvuchPlugin", "Parsed tracks: ${tracks.size}")
        tracks
    }

    private fun parseTracks(doc: Document): List<SearchTrack> {
        // Только блок результатов поиска — на странице есть виджеты с плеерами,
        // которые не должны попадать в выдачу
        val items = doc.select("ul.mainSongs[data-type=tracks] li.item")

        val tracks = if (items.isNotEmpty()) {
            items.mapNotNull { parseTrack(it) }
        } else {
            // Fallback: любые элементы с кнопкой воспроизведения
            Log.w("ZvuchPlugin", "mainSongs not found, using fallback selector")
            doc.select("li.item")
                .filter { it.selectFirst("li.play[data-url]") != null }
                .mapNotNull { parseTrack(it) }
        }

        // Один трек может лежать на странице дважды с одним data-id
        return tracks.distinctBy { it.id }
    }

    private fun parseTrack(element: Element): SearchTrack? {
        val playButton = element.selectFirst("li.play[data-url]") ?: return null
        val audioUrl = playButton.attr("data-url")
        if (!audioUrl.startsWith("http")) return null

        // Приоритет — data-атрибуты элемента, fallback на текст ссылок
        val artist = element.attr("data-artist").trim().ifBlank {
            element.selectFirst("span.artist")?.text()?.trim() ?: "Unknown"
        }

        val title = element.attr("data-title").trim().ifBlank {
            element.selectFirst("span.track")?.text()?.trim() ?: return null
        }
        val id = element.attr("data-id").ifBlank { "${artist}_${title}".hashCode().toString() }

        // Обложка приходит прямо с сайта (относительный путь)
        val coverPath = element.attr("data-img").trim()
        val coverUrl = when {
            coverPath.startsWith("http") -> coverPath
            coverPath.startsWith("/") -> "$baseUrl$coverPath"
            else -> null
        }

        // data-duration указана в секундах
        val durationMs = (element.attr("data-duration").toLongOrNull() ?: 0L) * 1000L

        return SearchTrack(
            id = "zv_$id",
            title = title,
            artist = artist,
            duration = durationMs,
            quality = "MP3",
            coverUrl = coverUrl,
            audioUrl = audioUrl
        )
    }
}
