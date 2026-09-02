package com.mf.musicfree.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import com.mf.musicfree.util.isNetworkError
import kotlinx.serialization.Serializable

@Serializable
data class NeteaseSearchResponse(val result: NeteaseResult?)
@Serializable
data class NeteaseResult(val songs: List<NeteaseSong>?)
@Serializable
data class NeteaseSong(val id: Long)

@Serializable
data class NeteaseLyricResponse(val lrc: NeteaseLrc?)
@Serializable
data class NeteaseLrc(val lyric: String?)

class NeteaseApi(private val client: HttpClient) {

    private val neteaseHeaders = io.ktor.http.headers {
        append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        append("Referer", "https://music.163.com")
    }

    suspend fun getLyrics(artist: String, title: String): String? {
        return try {
            val searchResponse: NeteaseSearchResponse = client.get(
                "https://music.163.com/api/search/get/web"
            ) {
                parameter("s", "$artist $title")
                parameter("type", 1)
                parameter("limit", 1)
                headers { appendAll(neteaseHeaders) }
            }.body()

            val songId = searchResponse.result?.songs?.firstOrNull()?.id ?: return null

            val lyricResponse: NeteaseLyricResponse = client.get(
                "https://music.163.com/api/song/lyric"
            ) {
                parameter("id", songId)
                parameter("lv", 1)
                parameter("kv", 1)
                parameter("tv", -1)
                headers { appendAll(neteaseHeaders) }
            }.body()

            lyricResponse.lrc?.lyric?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            null
        }
    }
}
