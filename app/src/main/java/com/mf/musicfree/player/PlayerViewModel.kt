package com.mf.musicfree.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.download.TrackDownloadWorker
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.model.TrackLyrics
import com.mf.musicfree.data.repository.LyricsRepository
import com.mf.musicfree.data.repository.PlaylistRepository
import com.mf.musicfree.data.repository.RadioRepository
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.plugin.SearchTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { NONE, ALL, ONE }

class PlayerViewModel(
    private val playerController: PlayerController,
    private val trackRepository: TrackRepository,
    private val workManager: WorkManager,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val radioRepository: RadioRepository
) : ViewModel() {

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private val _isShuffled = MutableStateFlow(false)
    val isShuffled: StateFlow<Boolean> = _isShuffled

    private val _isCurrentTrackSaved = MutableStateFlow(false)
    val isCurrentTrackSaved: StateFlow<Boolean> = _isCurrentTrackSaved

    private val _currentLyrics = MutableStateFlow<TrackLyrics?>(null)
    val currentLyrics: StateFlow<TrackLyrics?> = _currentLyrics

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading

    private val _isRadioMode = MutableStateFlow(false)
    val isRadioMode: StateFlow<Boolean> = _isRadioMode

    // Сколько радио-треков должно стоять впереди текущего
    private val radioBufferSize = 2

    private var radioSeeds: List<DeezerRadioTrack> = emptyList()
    private var radioSeedIndex = 0
    private var radioLoadJob: Job? = null
    private val radioQueuedIds = mutableSetOf<String>()

    init {
        playerController.setOnTrackChangeListener { index ->
            _currentIndex.value = index
            val track = _queue.value.getOrNull(index)
            _currentTrack.value = track
            track?.let {
                checkIfSaved(it.id)
                loadLyrics(it)
            }
            // Режим радио: поддерживаем буфер волны впереди текущего трека
            if (_isRadioMode.value) {
                ensureRadioBuffer()
            }
        }
        playerController.setOnIsPlayingChangeListener { isPlaying ->
            _isPlaying.value = isPlaying
        }
        viewModelScope.launch {
            while (isActive) {
                if (_currentTrack.value != null) {
                    _progress.value = playerController.getCurrentProgress()
                    _duration.value = playerController.getDuration()
                    _currentPosition.value = playerController.getCurrentPosition()
                }
                delay(500)
            }
        }
    }

    // ─── Радио ───

    fun toggleRadioMode() {
        val newValue = !_isRadioMode.value
        _isRadioMode.value = newValue
        if (newValue) {
            startRadioForCurrent()
        } else {
            stopRadio()
        }
    }

    private fun stopRadio() {
        radioLoadJob?.cancel()
        radioLoadJob = null
        radioSeeds = emptyList()
        radioSeedIndex = 0
        radioQueuedIds.clear()
    }

    private fun startRadioForCurrent() {
        val track = _currentTrack.value ?: return
        stopRadio()
        radioLoadJob = viewModelScope.launch {
            radioSeeds = radioRepository.getSeeds(track.artist, track.title)
            radioSeedIndex = 0
            if (radioSeeds.isEmpty()) {
                _isRadioMode.value = false
                return@launch
            }
            runRadioLoader()
        }
    }

    /** Сколько радио-треков стоит впереди текущего в очереди */
    private fun radioTracksAhead(): Int =
        _queue.value.drop(_currentIndex.value + 1).count { it.id in radioQueuedIds }

    /**
     * Держит буфер: пока впереди текущего меньше radioBufferSize радио-треков —
     * ищет и вставляет следующие. Вызывается при каждой смене трека.
     */
    private fun ensureRadioBuffer() {
        if (!_isRadioMode.value) return
        if (radioTracksAhead() >= radioBufferSize) return
        if (radioLoadJob?.isActive == true) return

        radioLoadJob = viewModelScope.launch {
            runRadioLoader()
        }
    }

    private suspend fun runRadioLoader() {
        var addedSinceRefill = 0
        while (_isRadioMode.value && radioTracksAhead() < radioBufferSize) {
            // Seeds кончились — продолжаем волну от текущего артиста
            if (radioSeedIndex >= radioSeeds.size) {
                if (!refillSeeds(addedSinceRefill)) break
                addedSinceRefill = 0
            }

            val seed = radioSeeds[radioSeedIndex++]
            val track = try {
                radioRepository.findTrack(seed.artist.name, seed.title)
            } catch (_: Exception) { null }

            val duplicate = track == null ||
                    track.id in radioQueuedIds ||
                    track.id == _currentTrack.value?.id
            if (track != null && !duplicate) {
                addRadioTrack(track)
                addedSinceRefill++
            }
        }
    }

    /**
     * Новая порция сидов от артиста текущего трека.
     * Если прошлая порция не дала ни одного трека — глушим радио, чтобы не крутиться впустую.
     */
    private suspend fun refillSeeds(addedSinceRefill: Int): Boolean {
        if (addedSinceRefill == 0) {
            _isRadioMode.value = false
            return false
        }
        val sourceArtist = _currentTrack.value?.artist ?: run {
            _isRadioMode.value = false
            return false
        }
        val newSeeds = radioRepository.getSeeds(sourceArtist)
        radioSeeds = newSeeds
        radioSeedIndex = 0
        if (newSeeds.isEmpty()) {
            _isRadioMode.value = false
            return false
        }
        return true
    }

    private fun addRadioTrack(track: Track) {
        radioQueuedIds.add(track.id)
        insertAfterCurrent(track)
    }

    private fun insertAfterCurrent(track: Track) {
        val currentIdx = _currentIndex.value.coerceAtLeast(0)
        val newQueue = _queue.value.toMutableList()
        newQueue.add(currentIdx + 1, track)
        _queue.value = newQueue
        playerController.insertAfterCurrent(listOf(track))
    }

    // ─── Воспроизведение ───

    fun playFromQueue(tracks: List<Track>, index: Int) {
        _queue.value = tracks
        _currentIndex.value = index
        _currentTrack.value = tracks.getOrNull(index)
        playerController.setQueue(tracks, index)
        playerController.play()
        _isPlaying.value = true

        // Если радио включено — запускаем для нового трека
        if (_isRadioMode.value) {
            startRadioForCurrent()
        }
    }

    fun playSearchResults(results: List<SearchTrack>, index: Int) {
        val tracks = results.map { it.toTrack() }
        playFromQueue(tracks, index)
    }

    fun playLibraryTracks(tracks: List<Track>, index: Int) {
        playFromQueue(tracks, index)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) playerController.pause() else playerController.play()
    }

    fun next() {
        playerController.next()
        _isPlaying.value = true
    }

    fun previous() {
        playerController.previous()
        _isPlaying.value = true
    }

    fun seekTo(position: Float) {
        playerController.seekTo(position)
        _progress.value = position
    }

    fun toggleRepeat() {
        val newMode = when (_repeatMode.value) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        _repeatMode.value = newMode
        val exoRepeat = when (newMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        playerController.setRepeatMode(exoRepeat)
    }

    fun toggleShuffle() {
        val newValue = !_isShuffled.value
        _isShuffled.value = newValue
        playerController.setShuffleEnabled(newValue)
    }

    // ─── Сохранение ───

    fun saveCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            trackRepository.addTrack(track)
            _isCurrentTrackSaved.value = true
            val inputData = Data.Builder()
                .putString(TrackDownloadWorker.KEY_TRACK_ID, track.id)
                .putString(TrackDownloadWorker.KEY_AUDIO_URL, track.sourceUrl ?: "")
                .putString(TrackDownloadWorker.KEY_FILE_NAME, "${track.artist} - ${track.title}.mp3")
                .build()
            val downloadWork = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                .setInputData(inputData)
                .build()
            workManager.enqueue(downloadWork)
        }
    }

    fun deleteCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            trackRepository.deleteTrack(track)
            _isCurrentTrackSaved.value = false
        }
    }

    fun addCurrentTrackToPlaylist(playlistId: String) {
        val trackId = _currentTrack.value?.id ?: return
        viewModelScope.launch {
            playlistRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    // ─── Тексты ───

    private fun checkIfSaved(trackId: String) {
        viewModelScope.launch {
            _isCurrentTrackSaved.value = trackRepository.exists(trackId)
        }
    }

    private fun loadLyrics(track: Track) {
        viewModelScope.launch {
            _isLyricsLoading.value = true
            _currentLyrics.value = null
            try {
                _currentLyrics.value = lyricsRepository.getLyrics(
                    artist = track.artist,
                    title = track.title,
                    album = track.album,
                    duration = (track.duration / 1000).toInt()
                )
            } finally {
                _isLyricsLoading.value = false
            }
        }
    }

    private fun SearchTrack.toTrack() = Track(
        id = id,
        title = title,
        artist = artist,
        duration = duration,
        coverUrl = coverUrl,
        sourceUrl = audioUrl,
        isDownloaded = false
    )
}