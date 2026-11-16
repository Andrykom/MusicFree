package com.mf.musicfree.data.db.dao

import androidx.room.*
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun getAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 ORDER BY title")
    fun getDownloaded(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(track: Track): Long

    @Delete
    suspend fun delete(track: Track)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE tracks SET filePath = :path, isDownloaded = 1 WHERE id = :id")
    suspend fun updateFilePath(id: String, path: String)

    @Query("UPDATE tracks SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun updateCover(id: String, coverUrl: String)

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE id = :id)")
    suspend fun exists(id: String): Boolean
}