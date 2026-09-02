package com.mf.musicfree.data.api

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import com.mf.musicfree.util.isNetworkError
import kotlinx.serialization.Serializable

@Serializable
data class KugouSearchResponse(val candidates: List<KugouCandidate>?)

@Serializable
data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val duration: Int,
    val song: String,
    val singer: String
)

@Serializable
data class KugouLyricResponse(
    val content: String? = null,
    val fmt: String? = null,
    val status: Int = 0
)

class KugouApi(private val client: HttpClient) {
    suspend fun getLyrics(artist: String, title: String, durationMs: Long? = null): String? {
        return try {
            val keyword = "$artist $title"
            val searchResponse: KugouSearchResponse = client.get("http://lyrics.kugou.com/search") {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("keyword", keyword)
                durationMs?.let { parameter("duration", it) }
            }.body()

            val candidate = searchResponse.candidates?.firstOrNull() ?: return null

            val lyricResponse: KugouLyricResponse = client.get("http://lyrics.kugou.com/download") {
                parameter("ver", 1)
                parameter("client", "pc")
                parameter("id", candidate.id)
                parameter("accesskey", candidate.accesskey)
                parameter("fmt", "lrc")
                parameter("charset", "utf8")
            }.body()

            if (lyricResponse.status != 200) return null

            val content = lyricResponse.content ?: return null
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            null
        }
    }
}
