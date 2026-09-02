package com.mf.musicfree.ui.library

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mf.musicfree.data.download.TrackDownloadWorker
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.PlaylistRepository
import com.mf.musicfree.data.repository.TrackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository,
    private val workManager: WorkManager
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = trackRepository.getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val playlists = playlistRepository.getAllPlaylistsWithTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress

    private val _downloadFailed = MutableStateFlow<Set<String>>(emptySet())
    val downloadFailed: StateFlow<Set<String>> = _downloadFailed

    private val workLiveData = workManager.getWorkInfosByTagLiveData("track_download")
    private val workObserver = Observer<List<WorkInfo>> { infos ->
        val prog = mutableMapOf<String, Int>()
        val failed = mutableSetOf<String>()
        infos.forEach { info ->
            val trackId = info.tags.firstOrNull { it != "track_download" } ?: return@forEach
            when (info.state) {
                WorkInfo.State.RUNNING -> prog[trackId] = info.progress.getInt(TrackDownloadWorker.KEY_PROGRESS, 0)
                WorkInfo.State.ENQUEUED -> prog[trackId] = 0
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> failed.add(trackId)
                else -> {}
            }
        }
        _downloadProgress.value = prog
        _downloadFailed.value = failed
    }

    init {
        workLiveData.observeForever(workObserver)
    }

    override fun onCleared() {
        workLiveData.removeObserver(workObserver)
        super.onCleared()
    }

    fun retryDownload(track: Track) {
        val url = track.sourceUrl ?: return
        val inputData = androidx.work.Data.Builder()
            .putString(TrackDownloadWorker.KEY_TRACK_ID, track.id)
            .putString(TrackDownloadWorker.KEY_AUDIO_URL, url)
            .putString(TrackDownloadWorker.KEY_FILE_NAME, "${track.artist} - ${track.title}.mp3")
            .build()
        val work = androidx.work.OneTimeWorkRequestBuilder<TrackDownloadWorker>()
            .setInputData(inputData)
            .addTag("track_download")
            .addTag(track.id)
            .build()
        workManager.enqueueUniqueWork(track.id, androidx.work.ExistingWorkPolicy.REPLACE, work)
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            trackRepository.deleteTrack(track)
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlist)
        }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch {
            playlistRepository.renamePlaylist(id, name)
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            playlistRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }
}