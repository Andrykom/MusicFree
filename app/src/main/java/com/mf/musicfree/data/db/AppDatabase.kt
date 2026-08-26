package com.mf.musicfree.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mf.musicfree.data.db.dao.PlaylistDao
import com.mf.musicfree.data.db.dao.TrackDao
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.PlaylistTrackCrossRef
import com.mf.musicfree.data.model.Track

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrackCrossRef::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        // v3: индекс на trackId в junction-таблице (устраняет full table scan)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` " +
                            "ON `playlist_tracks` (`trackId`)"
                )
            }
        }
    }
}