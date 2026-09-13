package com.mf.musicfree.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.PlaylistWithTracks
import com.mf.musicfree.data.repository.DeviceTracksRepository
import com.mf.musicfree.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val playlistRepository: PlaylistRepository,
    private val deviceTracksRepository: DeviceTracksRepository
) : ViewModel() {

    private val _devicePlaylist = MutableStateFlow<PlaylistWithTracks?>(null)

    val playlist: StateFlow<PlaylistWithTracks?> = if (playlistId == "device") {
        _devicePlaylist
    } else {
        playlistRepository
            .getPlaylistWithTracks(playlistId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )
    }

    init {
        if (playlistId == "device") {
            loadDeviceTracks()
        }
    }

    fun loadDeviceTracks() {
        viewModelScope.launch {
            val tracks = deviceTracksRepository.getDeviceTracks()
            _devicePlaylist.value = PlaylistWithTracks(
                playlist = Playlist(
                    id = "device",
                    name = "Музыка с устройства",
                    createdAt = 0L
                ),
                tracks = tracks
            )
        }
    }

    fun removeTrack(trackId: String) {
        if (playlistId == "device") return
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}