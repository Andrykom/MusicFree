package com.mf.musicfree.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import com.mf.musicfree.util.isNetworkError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LrcLibResponse(
    val id: Int,
    @SerialName("trackName") val trackName: String,
    @SerialName("artistName") val artistName: String,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null
)

class LrcLibApi(private val client: HttpClient) {
    suspend fun getLyrics(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int? = null
    ): LrcLibResponse? {
        return try {
            client.get("https://lrclib.net/api/get") {
                parameter("artist_name", artist)
                parameter("track_name", title)
                album?.let { parameter("album_name", it) }
                duration?.let { parameter("duration", it) }
            }.body()
        } catch (e: Exception) {
            if (e.isNetworkError()) throw e
            null
        }
    }
}