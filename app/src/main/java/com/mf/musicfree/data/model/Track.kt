package com.mf.musicfree.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long = 0,
    val filePath: String? = null,
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val isDownloaded: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)