package com.mf.musicfree.data.repository

import com.mf.musicfree.data.db.dao.TrackDao
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.flow.Flow

class TrackRepository(private val trackDao: TrackDao) {
    fun getAllTracks(): Flow<List<Track>> = trackDao.getAll()
    fun getDownloadedTracks(): Flow<List<Track>> = trackDao.getDownloaded()
    suspend fun addTrack(track: Track) = trackDao.insert(track)
    suspend fun ensureTrackExists(track: Track) = trackDao.insertIfAbsent(track)
    suspend fun deleteTrack(track: Track) = trackDao.delete(track)
    suspend fun updateFilePath(id: String, path: String) = trackDao.updateFilePath(id, path)
    suspend fun clearDownloaded(id: String) = trackDao.clearDownloaded(id)
    suspend fun updateCover(id: String, coverUrl: String) = trackDao.updateCover(id, coverUrl)

    suspend fun exists(id: String): Boolean = trackDao.exists(id)
    suspend fun getTrackById(id: String): Track? = trackDao.getById(id)
    suspend fun deleteById(id: String) = trackDao.deleteById(id)
}