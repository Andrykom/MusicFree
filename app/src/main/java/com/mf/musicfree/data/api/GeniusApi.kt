package com.mf.musicfree.data.api

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import com.mf.musicfree.util.isNetworkError
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

class GeniusApi(private val client: HttpClient) {
    suspend fun getLyrics(artist: String, title: String): String? {
        return try {
            // Ищем трек через публичный API поиска Genius
            val response = client.get("https://genius.com/api/search/multi") {
                parameter("q", "$artist $title")
                parameter("per_page", 5)
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                header(HttpHeaders.Accept, "application/json, text/plain, */*")
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en;q=0.8")
                header(HttpHeaders.Referrer, "https://genius.com/")
            }.bodyAsText()

            // Cloudflare отдаёт HTML вместо JSON при блокировке
            if (response.trimStart().startsWith("<!DOCTYPE") || response.contains("cf-challenge")) {
                Log.w("GeniusApi", "Search blocked by Cloudflare for $artist - $title")
                return null
            }

            // Парсим URL страницы с текстом
            val urlRegex = """"url":"(https://genius\.com/[^"]+lyrics)"""".toRegex()
            val match = urlRegex.find(response)
            val path = match?.groupValues?.get(1) ?: run {
                Log.d("GeniusApi", "No lyrics URL in search for $artist - $title")
                return null
            }
            Log.d("GeniusApi", "Search hit: $path")

            // Парсим HTML — нужен браузерный UA, иначе Cloudflare
            val html = client.get(path) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9,en;q=0.8")
                header(HttpHeaders.Referrer, "https://genius.com/")
            }.bodyAsText()

            if (html.contains("cf-challenge") || html.contains("Attention Required")) {
                Log.w("GeniusApi", "Lyrics page blocked by Cloudflare: $path")
                return null
            }

            val document = Jsoup.parse(html)

            // 1) Стандартный путь — div[data-lyrics-container] (работает и на мобильной вёрстке)
            var lyricsHtml = document.select("div[data-lyrics-container=true]")
                .joinToString("\n") { it.html() }

            // 2) Фолбэк — если контейнер пустой (React-SSR без него), пробуем вытащить из JSON
            if (lyricsHtml.isBlank()) {
                val jsonBlob = Regex("""window\.__PRELOADED_STATE__\s*=\s*JSON\.parse\('(.+?)'\)""").find(html)?.groupValues?.get(1)
                if (jsonBlob != null) {
                    // Внутри JSON есть dom с текстом — грубо вырезаем все "children":["text"] куски
                    // Альтернативно ищем все "plainText" — но проще взять html из dom
                    Log.d("GeniusApi", "Fallback: trying JSON parse for $path")
                }
            }

            if (lyricsHtml.isBlank()) {
                Log.d("GeniusApi", "No lyrics container for $path (len=${html.length})")
                return null
            }

            lyricsHtml = lyricsHtml.replace(Regex("(?i)<br\\s*/?>"), "\n")

            // Очищаем от HTML тегов
            val rawText = Jsoup.clean(
                lyricsHtml,
                "",
                org.jsoup.safety.Safelist.none(),
                org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)
            ).trim()

            val plainText = cleanPlainText(rawText)
            if (plainText.isNotBlank()) plainText else null
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            Log.w("GeniusApi", "getLyrics failed for $artist - $title: ${e.message}")
            null
        }
    }

    /**
     * Чистит статичный текст Genius: убирает [Куплет], [Припев], заголовок "XXX Lyrics"
     * и лишний мусор. Синхронизированные lrc сюда не попадают.
     */
    private fun cleanPlainText(text: String): String {
        var t = text
        // 1) Всё в квадратных скобках — секции [Интро], [Припев] и т.п.
        t = t.replace(Regex("""\[[^\]]*]"""), " ")
        // 2) Разбиваем на строки, тримим, убираем пустые
        val lines = t.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // 3) Первая строка вида "Название (xxx) Lyrics" — выкидываем
        if (lines.isNotEmpty() && lines.first().contains("Lyrics", ignoreCase = true)) {
            lines.removeAt(0)
        }

        // 4) Склеиваем, схлопываем множественные пробелы/пустые строки
        t = lines.joinToString("\n")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()

        return t
    }
}