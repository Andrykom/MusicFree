package com.mf.musicfree.data.model

import com.mf.musicfree.plugin.SearchTrack

data class ArtistInfo(
    val id: String,
    val name: String,
    val pictureUrl: String? = null,
    val deezerId: Long? = null,
    val itunesId: Long? = null,
    val fansCount: Int? = null
)

data class AlbumInfo(
    val id: String,
    val title: String,
    val artistName: String,
    val coverUrl: String? = null,
    val releaseYear: String? = null,
    val recordType: String? = null,
    val deezerId: Long? = null,
    val itunesId: Long? = null,
    val trackCount: Int = 0,
    val prematchedTracks: List<SearchTrack> = emptyList()
)

data class AlbumTrackItem(
    val trackNumber: Int,
    val title: String,
    val artist: String,
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val resolvedTrack: SearchTrack? = null
)

data class CatalogSearchResult(
    val artists: List<ArtistInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList()
)

data class ArtistPageData(
    val artist: ArtistInfo,
    val albums: List<AlbumInfo>,
    val singles: List<SearchTrack>,
    val allZvuchTracks: List<SearchTrack>
)