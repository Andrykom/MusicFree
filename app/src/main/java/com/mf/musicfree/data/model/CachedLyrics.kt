package com.mf.musicfree.data.model

import androidx.room.Entity

@Entity(
    tableName = "cached_lyrics",
    primaryKeys = ["artist", "title"]
)
data class CachedLyrics(
    val artist: String,
    val title: String,
    val isSynced: Boolean,
    val plainText: String? = null,
    // Сериализованный JSON списка LyricsLine
    val linesJson: String,
    val cachedAt: Long = System.currentTimeMillis()
)
