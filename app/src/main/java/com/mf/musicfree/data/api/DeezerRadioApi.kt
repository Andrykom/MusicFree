package com.mf.musicfree.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

// Ответ при поиске артиста
@Serializable
data class DeezerArtistSearchResponse(val data: List<DeezerArtist>? = null)

@Serializable
data class DeezerArtist(val id: Long, val name: String)

// Ответ при запросе радио артиста
@Serializable
data class DeezerRadioResponse(val data: List<DeezerRadioTrack>? = null)

@Serializable
data class DeezerRadioTrack(
    val title: String,
    val artist: DeezerArtist
)

class DeezerRadioApi(private val client: HttpClient) {
    suspend fun getRadioTracks(artistName: String): List<DeezerRadioTrack> {
        return try {
            // 1. Ищем ID артиста в базе Deezer
            val searchRes: DeezerArtistSearchResponse = client.get("https://api.deezer.com/search/artist") {
                parameter("q", artistName)
                parameter("limit", 1)
            }.body()

            val artistId = searchRes.data?.firstOrNull()?.id ?: return emptyList()

            // 2. Получаем умное радио по этому ID (возвращает микс из похожих артистов)
            val radioRes: DeezerRadioResponse = client.get("https://api.deezer.com/artist/$artistId/radio").body()

            radioRes.data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}