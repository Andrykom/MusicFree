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
data class ITunesResponse(
    val resultCount: Int,
    val results: List<ITunesResult>
)

@Serializable
data class ITunesResult(
    @SerialName("artworkUrl100") val artworkUrl100: String? = null
)

class ITunesApi {
    private val client = HttpClient(Android) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
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
            val query = java.net.URLEncoder.encode("$artist $title", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
            Log.d("ITunesApi", "Request: $url")

            val response: HttpResponse = client.get(url)
            val bodyText = response.bodyAsText()
            val parsed = json.decodeFromString<ITunesResponse>(bodyText)

            Log.d("ITunesApi", "Results: ${parsed.resultCount}")
            val rawUrl = parsed.results.firstOrNull()?.artworkUrl100
            Log.d("ITunesApi", "Raw artworkUrl100: $rawUrl")

            val cover = rawUrl?.let { url100 ->
                url100.replace("100x100bb.jpg", "600x600bb.jpg")
                    .replace("100x100bb", "600x600bb")
                    .replace("100x100", "600x600")
                    .also { Log.d("ITunesApi", "Converted to: $it") }
            }
            if (cover == null) Log.d("ITunesApi", "No cover found")
            cover
        } catch (e: Exception) {
            if (e.isNetworkError()) {
                Log.w("ITunesApi", "Network error, returning null: ${e.message}")
            } else {
                Log.e("ITunesApi", "Error: ${e.message}", e)
            }
            null
        }
    }
}