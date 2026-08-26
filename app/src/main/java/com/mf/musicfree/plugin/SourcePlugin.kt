package com.mf.musicfree.plugin

data class SearchTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val durationText: String? = null,
    val quality: String? = null,
    val coverUrl: String?,
    val audioUrl: String?
)

interface SourcePlugin {
    val name: String
    suspend fun search(query: String): List<SearchTrack>
}