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
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.plugin.SearchTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { NONE, ALL, ONE }

class PlayerViewModel(
    private val playerController: PlayerController,
    private val trackRepository: TrackRepository,
    private val workManager: WorkManager,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val radioRepository: RadioRepository,
    private val blockedArtistsRepository: BlockedArtistsRepository
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
    private var originalQueue: List<Track>? = null

    private val _isCurrentTrackSaved = MutableStateFlow(false)
    val isCurrentTrackSaved: StateFlow<Boolean> = _isCurrentTrackSaved

    private val _currentLyrics = MutableStateFlow<TrackLyrics?>(null)
    val currentLyrics: StateFlow<TrackLyrics?> = _currentLyrics

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading

    sealed class LyricsUiState {
        data object Idle : LyricsUiState()
        data object Loading : LyricsUiState()
        data class Success(val lyrics: TrackLyrics) : LyricsUiState()
        data object Empty : LyricsUiState()
        data class Error(val message: String) : LyricsUiState()
    }

    private val _lyricsState = MutableStateFlow<LyricsUiState>(LyricsUiState.Idle)
    val lyricsState: StateFlow<LyricsUiState> = _lyricsState

    private var lyricsJob: Job? = null

    private val _isRadioMode = MutableStateFlow(false)
    val isRadioMode: StateFlow<Boolean> = _isRadioMode

    // ─── Блэклист артистов для волны ───
    val blockedArtists: StateFlow<Set<String>> = blockedArtistsRepository.blockedArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _isCurrentArtistBlocked = MutableStateFlow(false)
    val isCurrentArtistBlocked: StateFlow<Boolean> = _isCurrentArtistBlocked

    // Сколько радио-треков должно стоять впереди текущего — по 1, чтобы не плодить вечно-висящие
    private val radioBufferSize = 1

    private var radioSeeds: List<DeezerRadioTrack> = emptyList()
    private var radioSeedIndex = 0
    private var radioLoadJob: Job? = null
    private val radioQueuedIds = mutableSetOf<String>()

    init {
        playerController.setOnTrackChangeListener { index ->
            _currentIndex.value = index
            // Берём трек из очереди ViewModel, а если она пуста (пересоздание Activity)
            // — из очереди контроллера, которая живёт в синглтоне
            val track = _queue.value.getOrNull(index)
                ?: playerController.getQueue().getOrNull(index)
            _currentTrack.value = track
            track?.let {
                checkIfSaved(it.id)
                loadLyrics(it)
            }
            // Если очереди рассинхронизировались — подтягиваем полную из контроллера
            if (playerController.getQueue().isNotEmpty() && _queue.value != playerController.getQueue()) {
                _queue.value = playerController.getQueue()
            }
            // Режим радио: поддерживаем буфер волны впереди текущего трека
            if (_isRadioMode.value) {
                ensureRadioBuffer()
            }
        }
        playerController.setOnIsPlayingChangeListener { isPlaying ->
            _isPlaying.value = isPlaying
        }

        // Восстанавливаем состояние, если плеер уже играет (переоткрытие приложения
        // при живом PlaybackService). Контроллер-синглтон хранит ExoPlayer и очередь.
        syncFromController()

        // Следим за блэклистом — обновляем флаг для текущего трека
        viewModelScope.launch {
            combine(_currentTrack, blockedArtistsRepository.blockedArtists) { track, blocked ->
                isArtistBlocked(track?.artist, blocked)
            }.collect { _isCurrentArtistBlocked.value = it }
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

    private fun syncFromController() {
        if (!playerController.isInitialized()) return
        val q = playerController.getQueue()
        if (q.isEmpty()) return
        val idx = playerController.getCurrentIndex().coerceIn(0, q.lastIndex)
        _queue.value = q
        _currentIndex.value = idx
        val track = q.getOrNull(idx)
        _currentTrack.value = track
        _isPlaying.value = playerController.isPlaying()
        track?.let {
            checkIfSaved(it.id)
            loadLyrics(it)
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

    fun toggleBlockCurrentArtist() {
        val raw = _currentTrack.value?.artist ?: return
        val artists = splitArtists(raw)
        if (artists.isEmpty()) return
        viewModelScope.launch {
            val blocked = blockedArtistsRepository.blockedArtists.first()
            val anyBlocked = artists.any { it in blocked }
            if (anyBlocked) {
                artists.forEach { blockedArtistsRepository.unblock(it) }
                _isCurrentArtistBlocked.value = false
            } else {
                artists.forEach { blockedArtistsRepository.block(it) }
                _isCurrentArtistBlocked.value = true
                // Убираем из очереди все предстоящие треки заблокированных артистов
                val curIdx = _currentIndex.value
                val fullBlocked = blockedArtistsRepository.blockedArtists.first()
                val indicesToRemove = _queue.value.mapIndexedNotNull { idx, tr ->
                    if (idx > curIdx && isArtistBlocked(tr.artist, fullBlocked)) idx else null
                }
                if (indicesToRemove.isNotEmpty()) {
                    for (idx in indicesToRemove.sortedDescending()) {
                        val newQ = _queue.value.toMutableList().apply { removeAt(idx) }
                        _queue.value = newQ
                        playerController.removeAt(idx)
                    }
                }
                // Если текущий трек сам в блэклисте — скипаем
                if (isArtistBlocked(_currentTrack.value?.artist, fullBlocked)) {
                    next()
                }
            }
        }
    }

    fun unblockArtist(artist: String) {
        viewModelScope.launch { blockedArtistsRepository.unblock(artist) }
    }

    fun clearBlockedArtists() {
        viewModelScope.launch { blockedArtistsRepository.clearAll() }
    }

    private fun splitArtists(raw: String): List<String> =
        raw.split(",", ";")
            .flatMap { it.split(" & ", " feat. ", " ft. ", ignoreCase = true) }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    private fun isArtistBlocked(artist: String?, blocked: Set<String>): Boolean {
        if (artist.isNullOrBlank()) return false
        return splitArtists(artist).any { it in blocked }
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
        // Вставляем в конец радио-блока, а не сразу после текущего — иначе второй трек вечно отодвигается
        val insertPos = (_currentIndex.value + radioTracksAhead() + 1).coerceIn(0, _queue.value.size)
        val newQueue = _queue.value.toMutableList().apply { add(insertPos, track) }
        _queue.value = newQueue
        playerController.insertAt(insertPos, listOf(track))
        originalQueue?.let { orig ->
            val posOrig = insertPos.coerceIn(0, orig.size)
            originalQueue = orig.toMutableList().apply { add(posOrig, track) }
        }
    }

    private fun insertAfterCurrent(track: Track) {
        val currentIdx = _currentIndex.value.coerceAtLeast(0)
        val newQueue = _queue.value.toMutableList()
        newQueue.add(currentIdx + 1, track)
        _queue.value = newQueue
        playerController.insertAfterCurrent(listOf(track))
        originalQueue?.let { orig ->
            val origIdx = orig.indexOfFirst { it.id == _currentTrack.value?.id }.coerceAtLeast(0)
            originalQueue = orig.toMutableList().apply { add(origIdx + 1, track) }
        }
    }

    // ─── Очередь: управление ───
    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return
        val removedId = _queue.value[index].id
        radioQueuedIds.remove(removedId)
        originalQueue?.let { orig ->
            val oi = orig.indexOfFirst { it.id == removedId }
            if (oi != -1) originalQueue = orig.toMutableList().apply { removeAt(oi) }
        }
        val wasCurrent = index == _currentIndex.value
        val newQueue = _queue.value.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        playerController.removeAt(index)
        when {
            newQueue.isEmpty() -> {
                _currentTrack.value = null
                _currentIndex.value = -1
            }
            wasCurrent -> {
                val newIdx = index.coerceAtMost(newQueue.lastIndex)
                _currentIndex.value = newIdx
                _currentTrack.value = newQueue.getOrNull(newIdx)
            }
            index < _currentIndex.value -> _currentIndex.value = _currentIndex.value - 1
        }
    }

    fun removeFromQueue(track: Track) {
        val idx = _queue.value.indexOfFirst { it.id == track.id }
        if (idx != -1) removeFromQueue(idx)
    }

    fun playNext(track: Track) = insertAfterCurrent(track)
    fun playNext(searchTrack: SearchTrack) = playNext(searchTrack.toTrack())

    fun addToQueue(track: Track) {
        val newQueue = _queue.value + track
        _queue.value = newQueue
        playerController.addToEnd(track)
        originalQueue?.let { orig -> originalQueue = orig + track }
    }
    fun addToQueue(searchTrack: SearchTrack) = addToQueue(searchTrack.toTrack())

    fun moveQueue(from: Int, to: Int) {
        if (from !in _queue.value.indices || to !in _queue.value.indices || from == to) return
        val newQueue = _queue.value.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = newQueue
        playerController.move(from, to)
        originalQueue?.let { orig ->
            val movedId = newQueue[to].id
            val origFrom = orig.indexOfFirst { it.id == movedId }
            val ot = to.coerceIn(orig.indices)
            if (origFrom != -1 && ot in orig.indices && origFrom != ot) {
                originalQueue = orig.toMutableList().apply { add(ot, removeAt(origFrom)) }
            }
        }
        val cur = _currentIndex.value
        _currentIndex.value = when {
            cur == from -> to
            cur in (minOf(from, to) + 1)..maxOf(from, to) -> if (from < to) cur - 1 else cur + 1
            else -> cur
        }
    }

    // ─── Воспроизведение ───

    fun playFromQueue(tracks: List<Track>, index: Int) {
        // Новая очередь — сбрасываем сохранённый оригинал шафла
        if (_isShuffled.value) {
            _isShuffled.value = false
            originalQueue = null
            playerController.setShuffleEnabled(false)
        }
        _queue.value = tracks
        _currentIndex.value = index
        _currentTrack.value = tracks.getOrNull(index)
        playerController.setQueue(tracks, index)
        playerController.play()

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
        if (playerController.isPlaying()) playerController.pause() else playerController.play()
    }

    private var lastNavMs = 0L
    fun next() {
        val now = System.currentTimeMillis()
        if (now - lastNavMs < 200) return
        lastNavMs = now
        playerController.next()
    }

    fun previous() {
        val now = System.currentTimeMillis()
        if (now - lastNavMs < 200) return
        lastNavMs = now
        playerController.previous()
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
        // Отключаем встроенный шафл ExoPlayer — делаем свой, чтобы очередь совпадала с отображением
        playerController.setShuffleEnabled(false)
        val currentIdx = _currentIndex.value
        val queue = _queue.value
        if (queue.isEmpty()) return
        val wasPlaying = playerController.isPlaying()
        val pos = playerController.getCurrentPosition()
        if (newValue) {
            // Включаем: сохраняем оригинал, шафлим только хвост после текущего
            originalQueue = queue.toList()
            if (currentIdx in queue.indices && currentIdx + 1 < queue.size) {
                val head = queue.subList(0, currentIdx + 1)
                val tailShuffled = queue.subList(currentIdx + 1, queue.size).shuffled()
                val newQueue = head + tailShuffled
                _queue.value = newQueue
                playerController.setQueue(newQueue, currentIdx)
                playerController.seekToPosition(pos)
                if (wasPlaying) playerController.play()
            }
        } else {
            // Выключаем: восстанавливаем оригинал
            val orig = originalQueue ?: return
            val currentId = _currentTrack.value?.id
            val newIdx = currentId?.let { id -> orig.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: currentIdx.coerceIn(orig.indices)
            _queue.value = orig
            playerController.setQueue(orig, newIdx)
            playerController.seekToPosition(pos)
            if (wasPlaying) playerController.play()
            originalQueue = null
        }
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
                .addTag("track_download")
                .addTag(track.id)
                .build()
            workManager.enqueueUniqueWork(track.id, androidx.work.ExistingWorkPolicy.KEEP, downloadWork)
        }
    }

    fun deleteCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            try { workManager.cancelUniqueWork(track.id) } catch (_: Exception) {}
            track.filePath?.let { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
            // Удаляем из очереди плеера
            val idx = _queue.value.indexOfFirst { it.id == track.id }
            if (idx != -1) {
                val newQ = _queue.value.toMutableList().apply { removeAt(idx) }
                _queue.value = newQ
                try { playerController.removeAt(idx) } catch (_: Exception) {}
                // Корректируем currentIndex
                if (idx == _currentIndex.value) {
                    if (newQ.isEmpty()) {
                        _currentTrack.value = null
                        _currentIndex.value = -1
                        playerController.pause()
                    } else {
                        val newIdx = idx.coerceAtMost(newQ.lastIndex)
                        _currentIndex.value = newIdx
                        _currentTrack.value = newQ.getOrNull(newIdx)
                    }
                } else if (idx < _currentIndex.value) {
                    _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
                }
            }
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
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _isLyricsLoading.value = true
            _lyricsState.value = LyricsUiState.Loading
            _currentLyrics.value = null
            try {
                val result = lyricsRepository.getLyrics(
                    artist = track.artist,
                    title = track.title,
                    album = track.album,
                    duration = (track.duration / 1000).toInt()
                )
                if (result != null) {
                    _currentLyrics.value = result
                    _lyricsState.value = LyricsUiState.Success(result)
                } else {
                    _lyricsState.value = LyricsUiState.Empty
                }
            } catch (e: java.io.IOException) {
                val msg = when {
                    e.message?.contains("Timeout", ignoreCase = true) == true ->
                        "Превышено время ожидания"
                    else -> "Нет подключения к интернету"
                }
                _lyricsState.value = LyricsUiState.Error(msg)
            } catch (e: Exception) {
                _lyricsState.value = LyricsUiState.Error("Ошибка загрузки текста")
            } finally {
                _isLyricsLoading.value = false
            }
        }
    }

    fun retryLyrics() {
        val track = _currentTrack.value ?: return
        loadLyrics(track)
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