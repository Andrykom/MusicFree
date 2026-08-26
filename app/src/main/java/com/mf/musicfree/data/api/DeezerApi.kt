package com.mf.musicfree.data.api

import android.util.Log
import com.mf.musicfree.util.isNetworkError
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeezerSearchResponse(
    val data: List<DeezerTrackResult> = emptyList(),
    val total: Int = 0
)

@Serializable
data class DeezerTrackResult(
    val album: DeezerAlbum? = null
)

@Serializable
data class DeezerAlbum(
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("cover_small") val coverSmall: String? = null
)

class DeezerApi {
    private val client = HttpClient(Android) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            exponentialDelay()
        }
        engine {
            connectTimeout = 8000
            socketTimeout = 10000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = java.net.URLEncoder.encode("artist:\"$artist\" track:\"$title\"", "UTF-8")
            val url = "https://api.deezer.com/search?q=$query&limit=1"
            Log.d("DeezerApi", "Request: $url")

            val response: HttpResponse = client.get(url)

            if (response.status.value !in 200..299) {
                Log.w("DeezerApi", "Non-success status: ${response.status}")
                return@withContext null
            }

            val bodyText = response.bodyAsText()
            val parsed = json.decodeFromString<DeezerSearchResponse>(bodyText)
            Log.d("DeezerApi", "Results: ${parsed.total}")

            // Приоритет: cover_xl (1000x1000) → cover_big (500x500) → cover (250x250)
            val cover = parsed.data.firstOrNull()?.album?.coverXl
                ?: parsed.data.firstOrNull()?.album?.coverBig
                ?: parsed.data.firstOrNull()?.album?.cover

            Log.d("DeezerApi", "Cover found: $cover")
            cover
        } catch (e: Exception) {
            if (e.isNetworkError()) {
                Log.w("DeezerApi", "Network error: ${e.message}")
            } else {
                Log.e("DeezerApi", "Error: ${e.message}", e)
            }
            null
        }
    }
}