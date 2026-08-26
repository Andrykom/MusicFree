package com.mf.musicfree.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import io.ktor.client.request.parameter

class GeniusApi(private val client: HttpClient) {
    suspend fun getLyrics(artist: String, title: String): String? {
        return try {
            // Ищем трек через публичный API поиска Genius
            val response = client.get("https://genius.com/api/search/multi") {
                parameter("q", "$artist $title")
                parameter("per_page", 1)
            }.bodyAsText()

            // Парсим URL страницы с текстом (упрощенный вариант поиска)
            val json = Json { ignoreUnknownKeys = true }
            // Для надежности лучше использовать регулярку, чтобы вытащить URL без сложных Data-классов
            val urlRegex = """"url":"(https://genius\.com/[^"]+lyrics)"""".toRegex()
            val match = urlRegex.find(response)
            val path = match?.groupValues?.get(1) ?: return null

            // Парсим HTML через JSoup
            val html = client.get(path).bodyAsText()
            val document = Jsoup.parse(html)

            // Genius хранит текст в контейнерах с data-lyrics-container
            // <br> меняем на переносы ДО очистки, иначе clean() их выкинет
            val lyricsHtml = document.select("div[data-lyrics-container=true]")
                .joinToString("\n") { it.html() }
                .replace(Regex("(?i)<br\\s*/?>"), "\n")

            // Очищаем от HTML тегов
            val plainText = Jsoup.clean(
                lyricsHtml,
                "",
                org.jsoup.safety.Safelist.none(),
                org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)
            ).trim()

            if (plainText.isNotBlank()) plainText else null
        } catch (e: Exception) {
            null
        }
    }
}