package com.mf.musicfree.data.api

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ListenBrainzApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mbidCache = mutableMapOf<String, String>()
    private val userAgent = "MusicFree/1.1 (https://github.com/musicfree)"

    suspend fun getSimilarTracks(artistName: String, maxArtists: Int = 6): List<DeezerRadioTrack> = coroutineScope {
        try {
            val mbid = resolveArtistMbid(artistName) ?: return@coroutineScope emptyList()
            val similar = getSimilarArtists(mbid).take(maxArtists)
            if (similar.isEmpty()) return@coroutineScope emptyList()

            similar.map { (simMbid, simName) ->
                async { getTopRecordings(simMbid, simName, limit = 3) }
            }.awaitAll().flatten()
        } catch (e: Exception) {
            Log.w("ListenBrainzApi", "getSimilarTracks failed for $artistName: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveArtistMbid(artistName: String): String? {
        val key = artistName.trim().lowercase()
        mbidCache[key]?.let { return it }

        val text = client.get("https://musicbrainz.org/ws/2/artist/") {
            parameter("query", "artist:\"$artistName\"")
            parameter("limit", 1)
            parameter("fmt", "json")
            header(HttpHeaders.UserAgent, userAgent)
        }.bodyAsText()

        val root = json.parseToJsonElement(text).jsonObject
        val mbid = root["artists"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.content
        if (!mbid.isNullOrBlank()) {
            mbidCache[key] = mbid
        }
        return mbid
    }

    private suspend fun getSimilarArtists(artistMbid: String): List<Pair<String, String>> {
        val text = client.get("https://labs.api.listenbrainz.org/similar-artists/json") {
            parameter("artist_mbids", artistMbid)
            parameter(
                "algorithm",
                "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
            )
            header(HttpHeaders.UserAgent, userAgent)
        }.bodyAsText()

        val arr = json.parseToJsonElement(text).jsonArray
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["artist_mbid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (id == artistMbid || name.isBlank()) null else (id to name)
        }
    }

    private suspend fun getTopRecordings(
        artistMbid: String,
        artistName: String,
        limit: Int
    ): List<DeezerRadioTrack> {
        return try {
            val text = client.get("https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/$artistMbid") {
                header(HttpHeaders.UserAgent, userAgent)
            }.bodyAsText()

            val arr = json.parseToJsonElement(text).jsonArray
            arr.take(limit).mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["recording_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val resolvedArtist = obj["artist_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: artistName
                DeezerRadioTrack(
                    title = title,
                    artist = DeezerArtist(id = 0L, name = resolvedArtist)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}