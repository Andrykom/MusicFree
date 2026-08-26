package com.mf.musicfree.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mf.musicfree.data.db.dao.PlaylistDao
import com.mf.musicfree.data.db.dao.TrackDao
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.PlaylistTrackCrossRef
import com.mf.musicfree.data.model.Track

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrackCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
}