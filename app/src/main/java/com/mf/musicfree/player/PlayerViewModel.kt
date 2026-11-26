package com.mf.musicfree.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mf.musicfree.data.api.CoverProvider
import com.mf.musicfree.data.api.DeezerRadioTrack
import com.mf.musicfree.data.download.TrackDownloadWorker
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.model.TrackLyrics
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.data.preferences.SettingsRepository
import com.mf.musicfree.data.repository.LyricsRepository
import com.mf.musicfree.data.repository.PlaylistRepository
import com.mf.musicfree.data.repository.RadioRepository
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mf.musicfree.data.preferences.TasteProfileRepository

enum class RepeatMode { NONE, ALL, ONE }

class PlayerViewModel(
    private val playerController: PlayerController,
    private val trackRepository: TrackRepository,
    private val workManager: WorkManager,
    private val playlistRepository: PlaylistRepository,
    private val lyricsRepository: LyricsRepository,
    private val radioRepository: RadioRepository,
    private val blockedArtistsRepository: BlockedArtistsRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val coverProvider: CoverProvider? = null,
    private val pluginManager: PluginManager? = null,
    private val downloadManager: com.mf.musicfree.data.download.DownloadManager? = null,
    private val tasteProfileRepository: TasteProfileRepository? = null
) : ViewModel() {

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

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

    fun getAudioSpectrum(): AudioSpectrum = playerController.spectrumMonitor.snapshot()

    val downloadProgress: StateFlow<Map<String, Int>> = downloadManager?.downloadProgress
        ?: MutableStateFlow(emptyMap())

    val isCurrentTrackInLibrary: StateFlow<Boolean> = combine(
        _currentTrack,
        trackRepository.getAllTracks()
    ) { current, allTracks ->
        if (current == null) false else allTracks.any { it.id == current.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isCurrentTrackDownloaded: StateFlow<Boolean> = combine(
        _currentTrack,
        trackRepository.getAllTracks()
    ) { current, allTracks ->
        if (current == null) false else allTracks.any { it.id == current.id && it.isDownloaded }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isCurrentTrackSaved: StateFlow<Boolean> = isCurrentTrackDownloaded

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

    private val _playbackErrorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackErrorEvent: SharedFlow<String> = _playbackErrorEvent.asSharedFlow()

    private var lyricsJob: Job? = null

    private var albumResolveJob: Job? = null
    private var loadedLyricsTrackId: String? = null
    private var lastRetriedUrlTrackId: String? = null
    private var lastPersistedPosMs: Long = -1L

    private val _isRadioMode = MutableStateFlow(false)

    private val _isTasteWaveActive = MutableStateFlow(false)
    val isTasteWaveActive: StateFlow<Boolean> = _isTasteWaveActive
    val isRadioMode: StateFlow<Boolean> = _isRadioMode

    private val _isRadioBuffering = MutableStateFlow(false)
    val isRadioBuffering: StateFlow<Boolean> = _isRadioBuffering

    val blockedArtists: StateFlow<Set<String>> = blockedArtistsRepository.blockedArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _isCurrentArtistBlocked = MutableStateFlow(false)
    val isCurrentArtistBlocked: StateFlow<Boolean> = _isCurrentArtistBlocked

    private val radioBufferSize = 2
    private var tasteRefillRetries = 0

    private var radioSeeds: List<DeezerRadioTrack> = emptyList()
    private var radioSeedIndex = 0
    private var radioLoadJob: Job? = null
    private val radioQueuedIds = mutableSetOf<String>()


    init {
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.crossfadeSeconds.collect { sec ->
                    playerController.setCrossfadeSeconds(sec)
                }
            }
        }

        downloadManager?.let { dm ->
            viewModelScope.launch {
                dm.downloadErrorEvents.collect { err ->
                    _playbackErrorEvent.tryEmit(err)
                }
            }
        }

        playerController.setOnTrackChangeListener { index ->
            val queue = if (playerController.getQueue().isNotEmpty()) playerController.getQueue() else _queue.value
            val safeIdx = index.coerceIn(-1, queue.lastIndex)
            _currentIndex.value = safeIdx
            val track = queue.getOrNull(safeIdx)
            _currentTrack.value = track
            if (track != null && track.id != loadedLyricsTrackId) {
                loadLyrics(track)
                loadCoverIfMissing(track)
            }
            if (queue.isNotEmpty() && _queue.value != queue) {
                _queue.value = queue
            }
            persistSnapshotAsync(safeIdx, 0L)
            if (_isRadioMode.value) {
                ensureRadioBuffer()
            }
            val prevTrack = _currentTrack.value
            val prevPos = _currentPosition.value
            val prevDur = _duration.value
            if (prevTrack != null && prevDur > 0 && (prevPos >= prevDur * 0.65 || prevPos >= 60_000L)) {
                viewModelScope.launch {
                    tasteProfileRepository?.recordCompleteListen(prevTrack.artist)
                }
            }
        }

        playerController.setOnIsPlayingChangeListener { isPlaying ->
            _isPlaying.value = isPlaying
            if (!isPlaying) {
                val pos = playerController.getCurrentPosition()
                _currentPosition.value = pos
                persistPositionAsync(pos)
            }
        }

        playerController.setOnBufferingChangeListener { buffering ->
            _isBuffering.value = buffering
        }

        playerController.setOnPlayerErrorListener { _ ->
            val failedTrack = _currentTrack.value
            val failedIdx = _currentIndex.value
            val savedPos = _currentPosition.value

            // Если это онлайн-стрим и мы ещё не пробовали обновить ссылку — ищем свежий audioUrl
            if (failedTrack != null &&
                !failedTrack.isDownloaded &&
                failedTrack.filePath == null &&
                lastRetriedUrlTrackId != failedTrack.id &&
                pluginManager != null
            ) {
                lastRetriedUrlTrackId = failedTrack.id
                viewModelScope.launch {
                    val freshUrl = refreshStreamUrl(failedTrack)
                    if (!freshUrl.isNullOrBlank() && failedIdx in _queue.value.indices) {
                        val updated = failedTrack.copy(sourceUrl = freshUrl)
                        _queue.value = _queue.value.toMutableList().apply { set(failedIdx, updated) }
                        if (_currentTrack.value?.id == updated.id) {
                            _currentTrack.value = updated
                        }
                        if (trackRepository.exists(updated.id)) {
                            trackRepository.updateSourceUrl(updated.id, freshUrl)
                        }
                        playerController.replaceTrackAt(failedIdx, updated, savedPos, autoPlay = true)
                    } else {
                        handleUnrecoverableTrackError(failedTrack.title)
                    }
                }
            } else {
                handleUnrecoverableTrackError(failedTrack?.title ?: "Трек")
            }
        }

        playerController.setOnQueueChangeListener { updatedQueue ->
            _queue.value = updatedQueue
            val curId = _currentTrack.value?.id
            val curIdx = if (curId != null) {
                updatedQueue.indexOfFirst { it.id == curId }.takeIf { it >= 0 }
                    ?: playerController.getCurrentIndex().coerceIn(-1, updatedQueue.lastIndex)
            } else {
                playerController.getCurrentIndex().coerceIn(-1, updatedQueue.lastIndex)
            }
            _currentIndex.value = curIdx
            val track = updatedQueue.getOrNull(curIdx)
            _currentTrack.value = track
            if (track == null && updatedQueue.isEmpty()) {
                _isPlaying.value = false
            }
        }

        syncFromController()

        viewModelScope.launch {
            combine(_currentTrack, blockedArtistsRepository.blockedArtists) { track, blocked ->
                isArtistBlocked(track?.artist, blocked)
            }.collect { _isCurrentArtistBlocked.value = it }
        }

        viewModelScope.launch {
            var tickCounter = 0
            while (isActive) {
                if (_currentTrack.value != null && _isPlaying.value) {
                    _progress.value = playerController.getCurrentProgress()
                    val dur = playerController.getDuration()
                    _duration.value = dur
                    val pos = playerController.getCurrentPosition()
                    _currentPosition.value = pos

                    // Сохраняем позицию в DataStore примерно раз в 4.5 секунды
                    tickCounter++
                    if (tickCounter >= 30) {
                        tickCounter = 0
                        persistPositionAsync(pos)
                    }

                    val xSec = playerController.getCrossfadeSeconds()
                    if (xSec > 0 && dur > 0 && !playerController.isCrossfading() && _repeatMode.value != RepeatMode.ONE) {
                        val remainingMs = dur - pos
                        val crossfadeMs = xSec * 1000L
                        val curIdx = _currentIndex.value
                        val nextIdx = when {
                            curIdx + 1 < _queue.value.size -> curIdx + 1
                            _repeatMode.value == RepeatMode.ALL && _queue.value.isNotEmpty() -> 0
                            else -> -1
                        }
                        if (nextIdx in _queue.value.indices) {
                            if (remainingMs in (crossfadeMs + 1)..(crossfadeMs + 6000L)) {
                                playerController.preloadNextTrack(_queue.value[nextIdx])
                            } else if (remainingMs in 1..crossfadeMs) {
                                playerController.performCrossfadeToNext(remainingMs)
                            }
                        }
                    }

                    delay(150)
                } else {
                    if (_currentTrack.value != null) {
                        _progress.value = playerController.getCurrentProgress()
                        _duration.value = playerController.getDuration()
                        _currentPosition.value = playerController.getCurrentPosition()
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun handleUnrecoverableTrackError(title: String) {
        _playbackErrorEvent.tryEmit("Не удалось воспроизвести: «$title»")
        if (_queue.value.size > 1 && _currentIndex.value < _queue.value.lastIndex) {
            next()
        } else {
            _isPlaying.value = false
        }
    }

    private suspend fun refreshStreamUrl(track: Track): String? {
        val manager = pluginManager ?: return null
        val query = "${track.artist} ${track.title}".trim()
        val preferredPrefix = track.id.substringBefore("_", "")
        val sortedPlugins = manager.plugins.sortedBy { plugin ->
            when {
                preferredPrefix == "zv" && plugin.name.equals("Zvuch", true) -> 0
                preferredPrefix == "tmp3" && plugin.name.equals("TheMP3", true) -> 0
                preferredPrefix == "dm" && plugin.name.equals("DriveMusic", true) -> 0
                plugin.name.equals("Zvuch", true) -> 1
                plugin.name.equals("TheMP3", true) -> 2
                else -> 3
            }
        }
        for (plugin in sortedPlugins) {
            try {
                val results = withTimeoutOrNull(7_000) { plugin.search(query) } ?: continue
                val valid = results.filter {
                    !it.audioUrl.isNullOrBlank() &&
                            !it.artist.equals("Популярные песни", ignoreCase = true)
                }
                if (valid.isEmpty()) continue

                val normTitle = track.title.lowercase().replace('ё', 'е')
                    .replace(Regex("""\([^)]*\)?"""), "").trim()

                val match = valid.firstOrNull { candidate ->
                    val candTitle = candidate.title.lowercase().replace('ё', 'е')
                        .replace(Regex("""\([^)]*\)?"""), "").trim()
                    candTitle == normTitle ||
                            (normTitle.length >= 4 && candTitle.contains(normTitle))
                }
                if (match?.audioUrl != null) {
                    return match.audioUrl
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun persistSnapshotAsync(index: Int, positionMs: Long) {
        val repo = settingsRepository ?: return
        val q = _queue.value
        viewModelScope.launch {
            try {
                repo.savePlaybackSnapshot(
                    queue = q,
                    currentIndex = index,
                    positionMs = positionMs,
                    isTasteWave = _isTasteWaveActive.value
                )
                lastPersistedPosMs = positionMs
            } catch (_: Exception) {}
        }
    }

    private fun persistPositionAsync(positionMs: Long) {
        val repo = settingsRepository ?: return
        if (kotlin.math.abs(positionMs - lastPersistedPosMs) < 1500L) return
        lastPersistedPosMs = positionMs
        viewModelScope.launch {
            try {
                repo.updateSavedPosition(positionMs)
            } catch (_: Exception) {}
        }
    }

    private fun syncFromController() {
        _isBuffering.value = playerController.isBuffering()
        _repeatMode.value = playerController.getSavedRepeatMode()
        _isShuffled.value = playerController.getSavedIsShuffled()
        originalQueue = playerController.getOriginalQueue()
        _isRadioMode.value = playerController.getSavedIsRadioMode()

        // Случай 1: контроллер жив в памяти и имеет очередь
        if (playerController.getQueue().isNotEmpty()) {
            val q = playerController.getQueue()
            val idx = playerController.getCurrentIndex().coerceIn(0, q.lastIndex)
            _queue.value = q
            _currentIndex.value = idx
            val track = q.getOrNull(idx)
            _currentTrack.value = track
            _isPlaying.value = playerController.isPlaying()
            _currentPosition.value = playerController.getCurrentPosition()
            _duration.value = playerController.getDuration()
            _progress.value = playerController.getCurrentProgress()
            track?.let {
                loadLyrics(it)
                loadCoverIfMissing(it)
            }
            if (_isRadioMode.value) {
                ensureRadioBuffer()
            }
            return
        }

        // Случай 2: холодный старт процесса — восстанавливаем последний трек и позицию из DataStore
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            val snapshot = repo.loadPlaybackSnapshot() ?: return@launch
            if (snapshot.isTasteWave) {
                _isTasteWaveActive.value = true
                _isRadioMode.value = true
                playerController.setSavedIsRadioMode(true)
                radioQueuedIds.clear()
                radioQueuedIds.addAll(snapshot.queue.map { it.id })
            }
            if (_queue.value.isNotEmpty()) return@launch
            val track = snapshot.queue.getOrNull(snapshot.currentIndex) ?: return@launch
            _queue.value = snapshot.queue
            _currentIndex.value = snapshot.currentIndex
            _currentTrack.value = track
            _isPlaying.value = false
            _currentPosition.value = snapshot.positionMs
            _duration.value = track.duration
            _progress.value = if (track.duration > 0) {
                (snapshot.positionMs.toFloat() / track.duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            playerController.restorePausedState(snapshot.queue, snapshot.currentIndex, snapshot.positionMs)
            loadLyrics(track)
            loadCoverIfMissing(track)
        }
    }

    // ─── Радио ───

    fun toggleRadioMode() {
        val newValue = !_isRadioMode.value
        _isRadioMode.value = newValue
        playerController.setSavedIsRadioMode(newValue)
        if (newValue) {
            startRadioForCurrent()
        } else {
            stopRadio()
        }
    }

    fun startTasteWave() {
        if (_isTasteWaveActive.value && _isRadioMode.value) {
            val atQueueEnd = !playerController.isPlaying() &&
                    _queue.value.isNotEmpty() &&
                    _currentIndex.value >= _queue.value.lastIndex &&
                    _progress.value >= 0.95f
            if (atQueueEnd) {
                radioLoadJob?.cancel()
                radioLoadJob = viewModelScope.launch {
                    runRadioLoader()
                    if (_currentIndex.value < _queue.value.lastIndex) {
                        playerController.next()
                        playerController.play()
                    }
                }
                return
            }
            togglePlayPause()
            ensureRadioBuffer()
            return
        }
        stopRadio()
        tasteRefillRetries = 0
        _isRadioBuffering.value = true
        radioLoadJob = viewModelScope.launch {
            val libraryTracks = trackRepository.getAllTracks().first()
            if (libraryTracks.isEmpty()) {
                _isRadioBuffering.value = false
                _playbackErrorEvent.tryEmit("Добавьте пару треков в Мою музыку, чтобы Волна узнала ваш вкус")
                return@launch
            }

            radioSeeds = radioRepository.getTasteSeeds(libraryTracks)
            radioSeedIndex = 0
            if (radioSeeds.isEmpty()) {
                _isRadioBuffering.value = false
                _playbackErrorEvent.tryEmit("Не удалось подобрать треки для Волны")
                return@launch
            }

            // Ищем первый рабочий трек для старта
            var firstTrack: Track? = null
            while (radioSeedIndex < radioSeeds.size && firstTrack == null) {
                val seed = radioSeeds[radioSeedIndex++]
                firstTrack = try {
                    radioRepository.findTrack(seed.artist.name, seed.title)
                } catch (_: Exception) { null }
            }

            if (firstTrack == null) {
                _isRadioBuffering.value = false
                _playbackErrorEvent.tryEmit("Не удалось загрузить треки для Волны")
                return@launch
            }

            // Запускаем первый трек (при _isRadioMode == false, чтобы playFromQueue не перезапустил обычное радио)
            playFromQueue(listOf(firstTrack), 0, keepTasteWave = true)
            radioQueuedIds.add(firstTrack.id)
            _isTasteWaveActive.value = true
            _isRadioMode.value = true
            playerController.setSavedIsRadioMode(true)
            _isRadioBuffering.value = false

            // Догружаем буфер вперёд
            runRadioLoader()
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
        _isTasteWaveActive.value = false
        radioLoadJob?.cancel()
        radioLoadJob = null
        radioSeeds = emptyList()
        radioSeedIndex = 0
        radioQueuedIds.clear()
        _isRadioBuffering.value = false
        playerController.setSavedIsRadioMode(false)
    }

    private fun startRadioForCurrent() {
        _isTasteWaveActive.value = false
        val track = _currentTrack.value ?: return
        stopRadio()
        _isRadioMode.value = true
        playerController.setSavedIsRadioMode(true)
        radioLoadJob = viewModelScope.launch {
            radioSeeds = radioRepository.getSeeds(track.artist, track.title)
            radioSeedIndex = 0
            if (radioSeeds.isEmpty()) {
                _isRadioMode.value = false
                playerController.setSavedIsRadioMode(false)
                _playbackErrorEvent.tryEmit("Не удалось найти треки для волны")
                return@launch
            }
            runRadioLoader()
            if (radioTracksAhead() == 0 && _isRadioMode.value) {
                _isRadioMode.value = false
                playerController.setSavedIsRadioMode(false)
                _playbackErrorEvent.tryEmit("Не удалось загрузить треки для волны")
            }
        }
    }

    private fun radioTracksAhead(): Int =
        _queue.value.drop(_currentIndex.value + 1).count { it.id in radioQueuedIds }

    private fun ensureRadioBuffer() {
        if (!_isRadioMode.value) return
        if (radioTracksAhead() >= radioBufferSize) return
        if (radioLoadJob?.isActive == true) return

        radioLoadJob = viewModelScope.launch {
            runRadioLoader()
        }
    }

    private fun primaryArtistKey(artist: String): String =
        artist.split(",", ";", "&", "feat.", "ft.", " x ", ignoreCase = true)
            .first()
            .lowercase()
            .replace('ё', 'е')
            .trim()

    private fun isArtistOnCooldown(artist: String, windowSize: Int = 5): Boolean {
        val key = primaryArtistKey(artist)
        if (key.isBlank()) return false
        val recent = _queue.value.takeLast(windowSize)
        return recent.any { primaryArtistKey(it.artist) == key }
    }

    private suspend fun runRadioLoader() {
        if (radioTracksAhead() == 0) {
            _isRadioBuffering.value = true
        }
        var addedSinceRefill = 0
        try {
            while (_isRadioMode.value && radioTracksAhead() < radioBufferSize) {
                if (radioSeedIndex >= radioSeeds.size) {
                    if (!refillSeeds(addedSinceRefill)) break
                    addedSinceRefill = 0
                }

                val seed = radioSeeds[radioSeedIndex++]
                // Кулдаун до сетевого запроса: экономит время и не даёт артисту повторяться чаще раза в 5 треков
                if (isArtistOnCooldown(seed.artist.name, windowSize = 5)) continue

                val track = try {
                    radioRepository.findTrack(seed.artist.name, seed.title)
                } catch (_: Exception) { null }

                val duplicate = track == null ||
                        track.id in radioQueuedIds ||
                        track.id == _currentTrack.value?.id ||
                        isArtistOnCooldown(track.artist, windowSize = 5)

                if (track != null && !duplicate) {
                    addRadioTrack(track)
                    addedSinceRefill++
                    tasteRefillRetries = 0
                    _isRadioBuffering.value = false
                }
            }
        } finally {
            _isRadioBuffering.value = false
        }
    }

    private suspend fun refillSeeds(addedSinceRefill: Int): Boolean {
        if (addedSinceRefill == 0) {
            // Для Волны по вкусу делаем до 2 повторных попыток с новой случайной выборкой артистов
            if (_isTasteWaveActive.value && tasteRefillRetries < 2) {
                tasteRefillRetries++
            } else {
                _isRadioMode.value = false
                _isTasteWaveActive.value = false
                playerController.setSavedIsRadioMode(false)
                _playbackErrorEvent.tryEmit("Волна завершена: новые треки не найдены")
                return false
            }
        }
        val sourceArtist = _currentTrack.value?.artist ?: run {
            _isRadioMode.value = false
            _isTasteWaveActive.value = false
            playerController.setSavedIsRadioMode(false)
            return false
        }
        val newSeeds = if (_isTasteWaveActive.value) {
            radioRepository.getTasteSeeds(trackRepository.getAllTracks().first())
        } else {
            radioRepository.getSeeds(sourceArtist)
        }
        radioSeeds = newSeeds
        radioSeedIndex = 0
        if (newSeeds.isEmpty()) {
            _isRadioMode.value = false
            _isTasteWaveActive.value = false
            playerController.setSavedIsRadioMode(false)
            _playbackErrorEvent.tryEmit("Волна завершена: новые треки не найдены")
            return false
        }
        return true
    }

    private fun addRadioTrack(track: Track) {
        radioQueuedIds.add(track.id)
        val insertPos = (_currentIndex.value + radioTracksAhead() + 1).coerceIn(0, _queue.value.size)
        val newQueue = _queue.value.toMutableList().apply { add(insertPos, track) }
        _queue.value = newQueue
        playerController.insertAt(insertPos, listOf(track))
        originalQueue?.let { orig ->
            val posOrig = insertPos.coerceIn(0, orig.size)
            originalQueue = orig.toMutableList().apply { add(posOrig, track) }
            playerController.setOriginalQueue(originalQueue)
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
            playerController.setOriginalQueue(originalQueue)
        }
    }

    // ─── Очередь: управление ───
    fun removeFromQueue(index: Int) {
        if (index !in _queue.value.indices) return
        val removedId = _queue.value[index].id
        radioQueuedIds.remove(removedId)
        originalQueue?.let { orig ->
            val oi = orig.indexOfFirst { it.id == removedId }
            if (oi != -1) {
                originalQueue = orig.toMutableList().apply { removeAt(oi) }
                playerController.setOriginalQueue(originalQueue)
            }
        }
        val wasCurrent = index == _currentIndex.value
        val newQueue = _queue.value.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        playerController.removeAt(index)
        when {
            newQueue.isEmpty() -> {
                _currentTrack.value = null
                _currentIndex.value = -1
                persistSnapshotAsync(-1, 0L)
            }
            wasCurrent -> {
                val newIdx = index.coerceAtMost(newQueue.lastIndex)
                _currentIndex.value = newIdx
                _currentTrack.value = newQueue.getOrNull(newIdx)
                persistSnapshotAsync(newIdx, 0L)
            }
            index < _currentIndex.value -> {
                _currentIndex.value = _currentIndex.value - 1
                persistSnapshotAsync(_currentIndex.value, _currentPosition.value)
            }
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
        originalQueue?.let { orig ->
            originalQueue = orig + track
            playerController.setOriginalQueue(originalQueue)
        }
    }
    fun addToQueue(searchTrack: SearchTrack) = addToQueue(searchTrack.toTrack())

    fun moveQueue(from: Int, to: Int) {
        if (from !in _queue.value.indices || to !in _queue.value.indices || from == to) return
        if (from == _currentIndex.value || to == _currentIndex.value) return
        val curTrack = _currentTrack.value
        val newQueue = _queue.value.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = newQueue
        playerController.move(from, to)
        originalQueue?.let { orig ->
            val movedId = newQueue[to].id
            val origFrom = orig.indexOfFirst { it.id == movedId }
            val ot = to.coerceIn(orig.indices)
            if (origFrom != -1 && ot in orig.indices && origFrom != ot) {
                originalQueue = orig.toMutableList().apply { add(ot, removeAt(origFrom)) }
                playerController.setOriginalQueue(originalQueue)
            }
        }
        val curId = curTrack?.id
        val newIdx = if (curId != null) {
            newQueue.indexOfFirst { it.id == curId }.takeIf { it >= 0 }
        } else null

        val cur = _currentIndex.value
        val finalIdx = newIdx ?: when {
            cur == from -> to
            cur in (minOf(from, to) + 1)..maxOf(from, to) -> if (from < to) cur - 1 else cur + 1
            else -> cur
        }
        _currentIndex.value = finalIdx
    }

    // ─── Воспроизведение ───

    fun playFromQueue(tracks: List<Track>, index: Int, keepTasteWave: Boolean = false) {
        albumResolveJob?.cancel()
        if (!keepTasteWave && _isTasteWaveActive.value) {
            // Тихо останавливаем Мою волну при запуске новой очереди (библиотека / альбом / артист)
            stopRadio()
        }
        if (tracks.isEmpty()) return
        val safeIdx = index.coerceIn(0, tracks.lastIndex)
        lastRetriedUrlTrackId = null

        if (_isShuffled.value) {
            _isShuffled.value = false
            originalQueue = null
            playerController.setSavedIsShuffled(false)
            playerController.setOriginalQueue(null)
            playerController.setShuffleEnabled(false)
        }
        _queue.value = tracks
        _currentIndex.value = safeIdx
        _currentTrack.value = tracks[safeIdx]
        _currentPosition.value = 0L
        _progress.value = 0f
        _queue.value = tracks
        _currentIndex.value = safeIdx
        _currentTrack.value = tracks[safeIdx]
        _currentPosition.value = 0L
        _progress.value = 0f

        loadLyrics(tracks[safeIdx])
        loadCoverIfMissing(tracks[safeIdx])

        playerController.setQueue(tracks, safeIdx, 0L)
        playerController.play()
        persistSnapshotAsync(safeIdx, 0L)

        if (_isRadioMode.value) {
            startRadioForCurrent()
        }
    }

    fun playAlbumQueue(tracks: List<Track>, startIndex: Int) {
        playFromQueue(tracks, startIndex)

        // Запускаем фоновую догрузку всех треков альбома без audioUrl
        // Сначала догружаем следующие после текущего, потом те, что были до него
        val pendingIds = buildList {
            for (i in (startIndex + 1) until tracks.size) {
                if (tracks[i].sourceUrl.isNullOrBlank() && !tracks[i].isDownloaded) add(tracks[i].id)
            }
            for (i in 0 until startIndex) {
                if (tracks[i].sourceUrl.isNullOrBlank() && !tracks[i].isDownloaded) add(tracks[i].id)
            }
        }
        if (pendingIds.isEmpty()) return

        albumResolveJob = viewModelScope.launch {
            for (trackId in pendingIds) {
                if (!isActive) break
                val currentIdx = _queue.value.indexOfFirst { it.id == trackId }
                if (currentIdx == -1) continue
                val track = _queue.value[currentIdx]
                if (!track.sourceUrl.isNullOrBlank()) continue

                val freshUrl = refreshStreamUrl(track)
                if (!freshUrl.isNullOrBlank()) {
                    val latestIdx = _queue.value.indexOfFirst { it.id == trackId }
                    if (latestIdx != -1) {
                        val updated = _queue.value[latestIdx].copy(sourceUrl = freshUrl)
                        _queue.value = _queue.value.toMutableList().apply { set(latestIdx, updated) }
                        if (_currentTrack.value?.id == trackId) {
                            _currentTrack.value = updated
                        }
                        playerController.updateTrackInQueue(latestIdx, updated)
                    }
                }
            }
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
        if (playerController.isPlaying()) {
            playerController.pause()
        } else {
            playerController.play()
        }
    }

    private var lastNavMs = 0L
    fun next() {
        if (_isRadioBuffering.value) return
        val now = System.currentTimeMillis()
        if (now - lastNavMs < 200) return
        lastNavMs = now
        val cur = _currentTrack.value
        if (cur != null && _isRadioMode.value && _currentPosition.value < 20_000L) {
            viewModelScope.launch {
                tasteProfileRepository?.recordQuickSkip(cur.artist)
            }
        }
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
        val dur = _duration.value
        if (dur > 0) {
            val pos = (position * dur).toLong()
            _currentPosition.value = pos
            persistPositionAsync(pos)
        }
    }

    fun seekToPosition(positionMs: Long) {
        playerController.seekToPosition(positionMs)
        _currentPosition.value = positionMs
        persistPositionAsync(positionMs)
        val dur = _duration.value
        if (dur > 0) {
            _progress.value = (positionMs.toFloat() / dur).coerceIn(0f, 1f)
        }
    }

    fun toggleRepeat() {
        val newMode = when (_repeatMode.value) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        _repeatMode.value = newMode
        playerController.setSavedRepeatMode(newMode)
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
        playerController.setSavedIsShuffled(newValue)
        playerController.setShuffleEnabled(false)
        val currentIdx = _currentIndex.value
        val queue = _queue.value
        if (queue.isEmpty()) return

        if (newValue) {
            originalQueue = queue.toList()
            playerController.setOriginalQueue(originalQueue)
            val current = _currentTrack.value ?: queue.getOrNull(currentIdx) ?: queue.first()
            val others = queue.filterIndexed { idx, _ -> idx != currentIdx }.shuffled()
            val newQueue = listOf(current) + others
            _queue.value = newQueue
            _currentIndex.value = 0
            _currentTrack.value = current
            playerController.replaceQueueKeepingCurrent(newQueue, 0)
            persistSnapshotAsync(0, _currentPosition.value)
        } else {
            val orig = originalQueue ?: return
            val currentId = _currentTrack.value?.id
            val newIdx = currentId?.let { id -> orig.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                ?: currentIdx.coerceIn(orig.indices)
            _queue.value = orig
            _currentIndex.value = newIdx
            _currentTrack.value = orig.getOrNull(newIdx)
            playerController.replaceQueueKeepingCurrent(orig, newIdx)
            originalQueue = null
            playerController.setOriginalQueue(null)
            persistSnapshotAsync(newIdx, _currentPosition.value)
        }
    }

    // ─── Сохранение ───

    fun toggleCurrentTrackInLibrary() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            if (isCurrentTrackInLibrary.value) {
                val path = track.filePath ?: trackRepository.getTrackById(track.id)?.filePath
                downloadManager?.cancelOrDeleteDownload(track.id, path)
                trackRepository.deleteTrack(track)
                _playbackErrorEvent.tryEmit("«${track.title}» удален из Моей музыки")
            } else {
                trackRepository.ensureTrackExists(track)
                tasteProfileRepository?.recordLike(track.artist)
                _playbackErrorEvent.tryEmit("«${track.title}» добавлен в Мою музыку")
            }
        }
    }

    fun downloadCurrentTrack() {
        val track = _currentTrack.value ?: return
        if (downloadManager != null) {
            downloadManager.downloadTrack(track)
        } else {
            saveCurrentTrack()
        }
    }

    fun deleteDownloadedFileForCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            val path = track.filePath ?: trackRepository.getTrackById(track.id)?.filePath
            downloadManager?.cancelOrDeleteDownload(track.id, path)
            trackRepository.clearDownloaded(track.id)
            if (_currentTrack.value?.id == track.id) {
                _currentTrack.value = _currentTrack.value?.copy(isDownloaded = false, filePath = null)
            }
            _playbackErrorEvent.tryEmit("Скачанный файл удален")
        }
    }

    fun saveCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            trackRepository.ensureTrackExists(track)
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
            workManager.enqueueUniqueWork(track.id, ExistingWorkPolicy.KEEP, downloadWork)
        }
    }

    fun deleteCurrentTrack() {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            try { workManager.cancelUniqueWork(track.id) } catch (_: Exception) {}
            track.filePath?.let { path ->
                try { java.io.File(path).delete() } catch (_: Exception) {}
            }
            val idx = _queue.value.indexOfFirst { it.id == track.id }
            if (idx != -1) {
                val newQ = _queue.value.toMutableList().apply { removeAt(idx) }
                _queue.value = newQ
                try { playerController.removeAt(idx) } catch (_: Exception) {}
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
        }
    }

    fun addCurrentTrackToPlaylist(playlistId: String) {
        val track = _currentTrack.value ?: return
        viewModelScope.launch {
            trackRepository.ensureTrackExists(track)
            playlistRepository.addTrackToPlaylist(playlistId, track.id)
        }
    }

    private var coverJob: Job? = null

    private fun loadCoverIfMissing(track: Track) {
        coverJob?.cancel()
        if (!track.coverUrl.isNullOrBlank() && track.coverUrl.startsWith("http")) {
            return
        }
        val provider = coverProvider ?: return
        coverJob = viewModelScope.launch {
            try {
                val foundCover = provider.searchCover(track.artist, track.title)
                if (!foundCover.isNullOrBlank()) {
                    if (_currentTrack.value?.id == track.id) {
                        _currentTrack.value = _currentTrack.value?.copy(coverUrl = foundCover)
                    }
                    _queue.value = _queue.value.map {
                        if (it.id == track.id) it.copy(coverUrl = foundCover) else it
                    }
                    if (trackRepository.exists(track.id)) {
                        trackRepository.updateCover(track.id, foundCover)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ─── Тексты ───

    // ─── Тексты ───

    private fun loadLyrics(track: Track, force: Boolean = false) {
        if (!force && track.id == loadedLyricsTrackId &&
            (_lyricsState.value is LyricsUiState.Success || _lyricsState.value is LyricsUiState.Loading)
        ) {
            return
        }
        loadedLyricsTrackId = track.id
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
                if (_currentTrack.value?.id != track.id) return@launch
                if (result != null) {
                    _currentLyrics.value = result
                    _lyricsState.value = LyricsUiState.Success(result)
                } else {
                    _lyricsState.value = LyricsUiState.Empty
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                if (_currentTrack.value?.id != track.id) return@launch
                val msg = when {
                    e.message?.contains("Timeout", ignoreCase = true) == true ->
                        "Превышено время ожидания"
                    else -> "Нет подключения к интернету"
                }
                _lyricsState.value = LyricsUiState.Error(msg)
            } catch (e: Exception) {
                if (_currentTrack.value?.id != track.id) return@launch
                _lyricsState.value = LyricsUiState.Error("Ошибка загрузки текста")
            } finally {
                if (_currentTrack.value?.id == track.id) {
                    _isLyricsLoading.value = false
                }
            }
        }
    }

    fun retryLyrics() {
        val track = _currentTrack.value ?: return
        loadLyrics(track, force = true)
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