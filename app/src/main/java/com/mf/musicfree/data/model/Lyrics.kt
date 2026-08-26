package com.mf.musicfree.data.model

data class LyricsLine(
    val timeMs: Long,
    val text: String
)

data class TrackLyrics(
    val lines: List<LyricsLine>,
    val isSynced: Boolean,
    val plainText: String? = null
)