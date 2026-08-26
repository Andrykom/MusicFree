package com.mf.musicfree.data.repository

import com.mf.musicfree.data.db.dao.PlaylistDao
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.PlaylistTrackCrossRef

class PlaylistRepository(private val playlistDao: PlaylistDao) {
    fun getAllPlaylists() = playlistDao.getAll()
    fun getAllPlaylistsWithTracks() = playlistDao.getAllPlaylistsWithTracks()

    suspend fun createPlaylist(name: String) {
        playlistDao.insert(Playlist(name = name))
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        playlistDao.delete(playlist)
    }

    suspend fun renamePlaylist(id: String, name: String) {
        playlistDao.updateName(id, name)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        playlistDao.addTrack(PlaylistTrackCrossRef(playlistId, trackId))
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrack(playlistId, trackId)
    }

    suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean {
        return playlistDao.hasTrack(playlistId, trackId)
    }

    fun getPlaylistWithTracks(playlistId: String) =
        playlistDao.getPlaylistWithTracks(playlistId)
}