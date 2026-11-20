package com.mf.musicfree.data.api

import android.util.Log
import com.mf.musicfree.data.model.AlbumInfo
import com.mf.musicfree.data.model.AlbumTrackItem
import com.mf.musicfree.data.model.ArtistInfo
import com.mf.musicfree.util.isNetworkError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable
data class ITunesResponse(
    val resultCount: Int = 0,
    val results: List<ITunesResult> = emptyList()
)

@Serializable
data class ITunesResult(
    @SerialName("artworkUrl100") val artworkUrl100: String? = null
)

@Serializable
data class ITunesCatalogResponse(
    val resultCount: Int = 0,
    val results: List<ITunesCatalogItem> = emptyList()
)

@Serializable
data class ITunesCatalogItem(
    val wrapperType: String? = null,
    val kind: String? = null,
    val artistId: Long? = null,
    val artistName: String? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val trackName: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val trackCount: Int? = null,
    val trackTimeMillis: Long? = null,
    val releaseDate: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null
)

class ITunesApi(private val client: HttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun String.upscaleITunesArtwork(): String =
        replace("100x100bb.jpg", "600x600bb.jpg")
            .replace("100x100bb", "600x600bb")
            .replace("100x100", "600x600")

    suspend fun searchCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=1"
            Log.d("ITunesApi", "Request: $url")

            val response: HttpResponse = client.get(url)
            val bodyText = response.bodyAsText()
            val parsed = json.decodeFromString<ITunesResponse>(bodyText)

            Log.d("ITunesApi", "Results: ${parsed.resultCount}")
            val rawUrl = parsed.results.firstOrNull()?.artworkUrl100
            Log.d("ITunesApi", "Raw artworkUrl100: $rawUrl")

            val cover = rawUrl?.upscaleITunesArtwork()?.also {
                Log.d("ITunesApi", "Converted to: $it")
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

    suspend fun searchArtists(query: String, limit: Int = 3): List<ArtistInfo> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val response = client.get("https://itunes.apple.com/search?term=$encoded&entity=musicArtist&limit=$limit")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<ITunesCatalogResponse>(response.bodyAsText())
            parsed.results
                .filter { it.artistId != null && !it.artistName.isNullOrBlank() }
                .map { dto ->
                    ArtistInfo(
                        id = "it_${dto.artistId}",
                        name = dto.artistName!!,
                        itunesId = dto.artistId
                    )
                }
        } catch (e: Exception) {
            Log.w("ITunesApi", "searchArtists failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchAlbums(query: String, limit: Int = 8): List<AlbumInfo> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val response = client.get("https://itunes.apple.com/search?term=$encoded&entity=album&limit=$limit")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<ITunesCatalogResponse>(response.bodyAsText())
            parsed.results
                .filter { it.collectionId != null && !it.collectionName.isNullOrBlank() }
                .map { dto ->
                    AlbumInfo(
                        id = "it_alb_${dto.collectionId}",
                        title = dto.collectionName!!,
                        artistName = dto.artistName.orEmpty(),
                        coverUrl = dto.artworkUrl100?.upscaleITunesArtwork(),
                        releaseYear = dto.releaseDate?.take(4)?.takeIf { it.length == 4 },
                        recordType = "album",
                        itunesId = dto.collectionId,
                        trackCount = dto.trackCount ?: 0
                    )
                }
        } catch (e: Exception) {
            Log.w("ITunesApi", "searchAlbums failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getArtistAlbums(artistId: Long, artistName: String): List<AlbumInfo> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("https://itunes.apple.com/lookup?id=$artistId&entity=album&limit=50")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<ITunesCatalogResponse>(response.bodyAsText())
            parsed.results
                .filter { it.wrapperType == "collection" && it.collectionId != null && !it.collectionName.isNullOrBlank() }
                .sortedByDescending { it.releaseDate.orEmpty() }
                .map { dto ->
                    AlbumInfo(
                        id = "it_alb_${dto.collectionId}",
                        title = dto.collectionName!!,
                        artistName = dto.artistName?.takeIf { it.isNotBlank() } ?: artistName,
                        coverUrl = dto.artworkUrl100?.upscaleITunesArtwork(),
                        releaseYear = dto.releaseDate?.take(4)?.takeIf { it.length == 4 },
                        recordType = if ((dto.trackCount ?: 0) <= 2) "single" else "album",
                        itunesId = dto.collectionId,
                        trackCount = dto.trackCount ?: 0
                    )
                }
        } catch (e: Exception) {
            Log.w("ITunesApi", "getArtistAlbums failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAlbumTracks(collectionId: Long, fallbackArtist: String, coverUrl: String?): List<AlbumTrackItem> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.get("https://itunes.apple.com/lookup?id=$collectionId&entity=song&limit=100")
                if (response.status.value !in 200..299) return@withContext emptyList()
                val parsed = json.decodeFromString<ITunesCatalogResponse>(response.bodyAsText())
                parsed.results
                    .filter { it.wrapperType == "track" && !it.trackName.isNullOrBlank() }
                    .sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 999 }))
                    .mapIndexed { idx, dto ->
                        AlbumTrackItem(
                            trackNumber = dto.trackNumber ?: (idx + 1),
                            title = dto.trackName!!,
                            artist = dto.artistName?.takeIf { it.isNotBlank() } ?: fallbackArtist,
                            durationMs = dto.trackTimeMillis ?: 0L,
                            coverUrl = coverUrl ?: dto.artworkUrl100?.upscaleITunesArtwork()
                        )
                    }
            } catch (e: Exception) {
                Log.w("ITunesApi", "getAlbumTracks failed: ${e.message}")
                emptyList()
            }
        }
}