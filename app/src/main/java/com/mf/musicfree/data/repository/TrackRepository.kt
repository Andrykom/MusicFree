package com.mf.musicfree.data.repository

import com.mf.musicfree.data.db.dao.TrackDao
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackRepository(private val trackDao: TrackDao) {
    private val writeMutex = Mutex()

    fun getAllTracks(): Flow<List<Track>> = trackDao.getAll()
    fun getDownloadedTracks(): Flow<List<Track>> = trackDao.getDownloaded()

    suspend fun addTrack(track: Track) = writeMutex.withLock {
        val nextAddedAt = maxOf(System.currentTimeMillis(), trackDao.getMaxAddedAt() + 1L)
        trackDao.insert(track.copy(addedAt = nextAddedAt))
    }

    suspend fun ensureTrackExists(track: Track, bumpToTop: Boolean = true) = writeMutex.withLock {
        val nextAddedAt = maxOf(System.currentTimeMillis(), trackDao.getMaxAddedAt() + 1L)
        val insertedRowId = trackDao.insertIfAbsent(track.copy(addedAt = nextAddedAt))
        if (insertedRowId == -1L && bumpToTop) {
            trackDao.updateAddedAt(track.id, nextAddedAt)
        }
    }

    suspend fun deleteTrack(track: Track) = trackDao.delete(track)
    suspend fun updateFilePath(id: String, path: String) = trackDao.updateFilePath(id, path)
    suspend fun clearDownloaded(id: String) = trackDao.clearDownloaded(id)
    suspend fun updateCover(id: String, coverUrl: String) = trackDao.updateCover(id, coverUrl)
    suspend fun updateSourceUrl(id: String, sourceUrl: String) = trackDao.updateSourceUrl(id, sourceUrl)

    suspend fun exists(id: String): Boolean = trackDao.exists(id)
    suspend fun getTrackById(id: String): Track? = trackDao.getById(id)
    suspend fun deleteById(id: String) = trackDao.deleteById(id)
}