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

@Serializable
data class DeezerCatalogArtistSearchResponse(
    val data: List<DeezerCatalogArtistDto> = emptyList()
)

@Serializable
data class DeezerCatalogArtistDto(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("picture_xl") val pictureXl: String? = null,
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
    @SerialName("nb_fan") val nbFan: Int? = null
)

@Serializable
data class DeezerCatalogAlbumSearchResponse(
    val data: List<DeezerCatalogAlbumDto> = emptyList()
)

@Serializable
data class DeezerCatalogAlbumDto(
    val id: Long = 0L,
    val title: String = "",
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("cover_medium") val coverMedium: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int = 0,
    val artist: DeezerCatalogArtistShortDto? = null
)

@Serializable
data class DeezerCatalogArtistShortDto(
    val id: Long = 0L,
    val name: String = ""
)

@Serializable
data class DeezerCatalogAlbumTracksResponse(
    val data: List<DeezerCatalogAlbumTrackDto> = emptyList()
)

@Serializable
data class DeezerCatalogAlbumTrackDto(
    val id: Long = 0L,
    val title: String = "",
    val duration: Long = 0L,
    @SerialName("track_position") val trackPosition: Int = 0,
    @SerialName("disk_number") val diskNumber: Int = 1,
    val artist: DeezerCatalogArtistShortDto? = null
)

class DeezerApi(private val client: HttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun searchCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("artist:\"$artist\" track:\"$title\"", "UTF-8")
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

    suspend fun searchArtists(query: String, limit: Int = 3): List<ArtistInfo> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val response = client.get("https://api.deezer.com/search/artist?q=$encoded&limit=$limit")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<DeezerCatalogArtistSearchResponse>(response.bodyAsText())
            parsed.data
                .filter { it.id > 0L && it.name.isNotBlank() }
                .map { dto ->
                    ArtistInfo(
                        id = "dz_${dto.id}",
                        name = dto.name,
                        pictureUrl = dto.pictureBig ?: dto.pictureMedium ?: dto.pictureXl,
                        deezerId = dto.id,
                        fansCount = dto.nbFan
                    )
                }
        } catch (e: Exception) {
            Log.w("DeezerApi", "searchArtists failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchAlbums(query: String, limit: Int = 8): List<AlbumInfo> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val response = client.get("https://api.deezer.com/search/album?q=$encoded&limit=$limit")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<DeezerCatalogAlbumSearchResponse>(response.bodyAsText())
            parsed.data
                .filter { it.id > 0L && it.title.isNotBlank() }
                .map { dto ->
                    AlbumInfo(
                        id = "dz_alb_${dto.id}",
                        title = dto.title,
                        artistName = dto.artist?.name.orEmpty(),
                        coverUrl = dto.coverBig ?: dto.coverMedium ?: dto.coverXl,
                        releaseYear = dto.releaseDate?.take(4)?.takeIf { it.length == 4 },
                        recordType = dto.recordType,
                        deezerId = dto.id,
                        trackCount = dto.nbTracks
                    )
                }
        } catch (e: Exception) {
            Log.w("DeezerApi", "searchAlbums failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getArtistAlbums(artistId: Long, artistName: String): List<AlbumInfo> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("https://api.deezer.com/artist/$artistId/albums?limit=50")
            if (response.status.value !in 200..299) return@withContext emptyList()
            val parsed = json.decodeFromString<DeezerCatalogAlbumSearchResponse>(response.bodyAsText())
            parsed.data
                .filter { it.id > 0L && it.title.isNotBlank() }
                .sortedByDescending { it.releaseDate.orEmpty() }
                .map { dto ->
                    AlbumInfo(
                        id = "dz_alb_${dto.id}",
                        title = dto.title,
                        artistName = dto.artist?.name?.takeIf { it.isNotBlank() } ?: artistName,
                        coverUrl = dto.coverBig ?: dto.coverMedium ?: dto.coverXl,
                        releaseYear = dto.releaseDate?.take(4)?.takeIf { it.length == 4 },
                        recordType = dto.recordType,
                        deezerId = dto.id,
                        trackCount = dto.nbTracks
                    )
                }
        } catch (e: Exception) {
            Log.w("DeezerApi", "getArtistAlbums failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getAlbumTracks(albumId: Long, fallbackArtist: String, coverUrl: String?): List<AlbumTrackItem> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.get("https://api.deezer.com/album/$albumId/tracks?limit=100")
                if (response.status.value !in 200..299) return@withContext emptyList()
                val parsed = json.decodeFromString<DeezerCatalogAlbumTracksResponse>(response.bodyAsText())
                parsed.data
                    .filter { it.title.isNotBlank() }
                    .sortedWith(compareBy({ it.diskNumber }, { it.trackPosition }))
                    .mapIndexed { idx, dto ->
                        AlbumTrackItem(
                            trackNumber = if (dto.trackPosition > 0) dto.trackPosition else idx + 1,
                            title = dto.title,
                            artist = dto.artist?.name?.takeIf { it.isNotBlank() } ?: fallbackArtist,
                            durationMs = dto.duration * 1000L,
                            coverUrl = coverUrl
                        )
                    }
            } catch (e: Exception) {
                Log.w("DeezerApi", "getAlbumTracks failed: ${e.message}")
                emptyList()
            }
        }
}