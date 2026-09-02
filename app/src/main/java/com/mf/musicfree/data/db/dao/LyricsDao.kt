package com.mf.musicfree.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mf.musicfree.data.model.CachedLyrics

@Dao
interface LyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE artist = :artist AND title = :title LIMIT 1")
    suspend fun get(artist: String, title: String): CachedLyrics?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CachedLyrics)

    @Query("DELETE FROM cached_lyrics WHERE artist = :artist AND title = :title")
    suspend fun delete(artist: String, title: String)

    @Query("DELETE FROM cached_lyrics WHERE cachedAt < :before")
    suspend fun evictOlderThan(before: Long)
}
