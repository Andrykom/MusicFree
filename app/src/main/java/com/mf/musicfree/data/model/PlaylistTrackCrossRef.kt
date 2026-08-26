package com.mf.musicfree.data.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("trackId")]
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val orderIndex: Int = 0
)