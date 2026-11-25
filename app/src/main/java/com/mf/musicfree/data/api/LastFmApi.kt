package com.mf.musicfree.data.api

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LastFmApi(
    private val client: HttpClient,
    // Получить бесплатный ключ: https://www.last.fm/api/account/create
    private val apiKey: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    suspend fun getSimilarTracks(artistName: String, maxArtists: Int = 8): List<DeezerRadioTrack> = coroutineScope {
        if (!isConfigured) return@coroutineScope emptyList()
        try {
            val similarArtists = getSimilarArtists(artistName, maxArtists)
            if (similarArtists.isEmpty()) return@coroutineScope emptyList()

            similarArtists.map { similarArtist ->
                async { getTopTracks(similarArtist, limit = 3) }
            }.awaitAll().flatten()
        } catch (e: Exception) {
            Log.w("LastFmApi", "getSimilarTracks failed for $artistName: ${e.message}")
            emptyList()
        }
    }

    private suspend fun getSimilarArtists(artistName: String, limit: Int): List<String> {
        val text = client.get("https://ws.audioscrobbler.com/2.0/") {
            parameter("method", "artist.getsimilar")
            parameter("artist", artistName)
            parameter("limit", limit)
            parameter("autocorrect", 1)
            parameter("api_key", apiKey)
            parameter("format", "json")
        }.bodyAsText()

        val root = json.parseToJsonElement(text).jsonObject
        val artists = root["similarartists"]?.jsonObject?.get("artist")?.jsonArray ?: return emptyList()
        return artists.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { n -> n.isNotBlank() } }
    }

    private suspend fun getTopTracks(artistName: String, limit: Int): List<DeezerRadioTrack> {
        return try {
            val text = client.get("https://ws.audioscrobbler.com/2.0/") {
                parameter("method", "artist.gettoptracks")
                parameter("artist", artistName)
                parameter("limit", limit)
                parameter("autocorrect", 1)
                parameter("api_key", apiKey)
                parameter("format", "json")
            }.bodyAsText()

            val root = json.parseToJsonElement(text).jsonObject
            val tracks = root["toptracks"]?.jsonObject?.get("track")?.jsonArray ?: return emptyList()
            tracks.mapNotNull { el ->
                val title = el.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                DeezerRadioTrack(
                    title = title,
                    artist = DeezerArtist(id = 0L, name = artistName)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}