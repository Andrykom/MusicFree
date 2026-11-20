package com.mf.musicfree.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mf.musicfree.data.download.TrackDownloadWorker
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import com.mf.musicfree.plugin.SourcePlugin
import com.mf.musicfree.plugin.exception.PluginException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SearchUiState {
    data object Idle : SearchUiState()
    data object Loading : SearchUiState()
    data class Success(val tracks: List<SearchTrack>) : SearchUiState()
    data class Empty(val message: String = "Ничего не найдено") : SearchUiState()
    data class Error(val message: String, val canRetry: Boolean = true) : SearchUiState()
}

class SearchViewModel(
    private val pluginManager: PluginManager,
    private val trackRepository: TrackRepository,
    private val workManager: WorkManager,
    private val playerController: com.mf.musicfree.player.PlayerController? = null,
    private val settingsRepository: com.mf.musicfree.data.preferences.SettingsRepository? = null,
    private val downloadManager: com.mf.musicfree.data.download.DownloadManager? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState

    private val _results = MutableStateFlow<List<SearchTrack>>(emptyList())
    val results: StateFlow<List<SearchTrack>> = _results

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val downloadProgress: StateFlow<Map<String, Int>> = downloadManager?.downloadProgress
        ?: MutableStateFlow(emptyMap())

    val downloadFailed: StateFlow<Set<String>> = downloadManager?.downloadFailed
        ?: MutableStateFlow(emptySet())

    val libraryIds: StateFlow<Set<String>> = trackRepository.getAllTracks()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    val downloadedIds: StateFlow<Set<String>> = trackRepository.getAllTracks()
        .map { list -> list.filter { it.isDownloaded }.map { it.id }.toSet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )
    private val _selectedPlugin = MutableStateFlow<SourcePlugin?>(null)
    val selectedPlugin: StateFlow<SourcePlugin?> = _selectedPlugin

    init {
        _selectedPlugin.value = pluginManager.plugins.firstOrNull()
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.defaultPluginName.collect { defaultName ->
                    if (defaultName != null) {
                        val plugin = pluginManager.getPlugin(defaultName)
                        if (plugin != null) {
                            _selectedPlugin.value = plugin
                        }
                    }
                }
            }
        }
        downloadManager?.let { dm ->
            viewModelScope.launch {
                dm.downloadErrorEvents.collect { errorMsg ->
                    _error.value = errorMsg
                }
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isBlank()) {
            _uiState.value = SearchUiState.Idle
            _results.value = emptyList()
            _error.value = null
        }
    }

    fun selectPlugin(plugin: SourcePlugin?) {
        _selectedPlugin.value = plugin
    }

    fun search() {
        val currentQuery = _query.value.trim()
        if (currentQuery.isEmpty()) return

        val plugin = _selectedPlugin.value ?: pluginManager.plugins.firstOrNull() ?: return

        performSearch(plugin, currentQuery)
    }

    fun retry() {
        search()
    }

    private fun performSearch(plugin: SourcePlugin, currentQuery: String) {
        viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            _uiState.value = SearchUiState.Loading

            try {
                // Сайты могут вернуть один трек дважды — дубли рушат LazyColumn по key=id
                val tracks = plugin.search(currentQuery).distinctBy { it.id }
                _results.value = tracks

                if (tracks.isEmpty()) {
                    _uiState.value = SearchUiState.Empty("По запросу «$currentQuery» ничего не найдено")
                } else {
                    _uiState.value = SearchUiState.Success(tracks)
                }
            } catch (e: PluginException.NoConnection) {
                _error.value = e.message
                _uiState.value = SearchUiState.Error(
                    message = "Нет подключения к интернету.\nПроверьте соединение и попробуйте снова.",
                    canRetry = true
                )
                _results.value = emptyList()
            } catch (e: PluginException.ServerUnavailable) {
                _error.value = e.message
                _uiState.value = SearchUiState.Error(
                    message = "Сервер ${plugin.name} временно недоступен.\nПопробуйте позже.",
                    canRetry = true
                )
                _results.value = emptyList()
            } catch (e: PluginException.ParseError) {
                _error.value = e.message
                _uiState.value = SearchUiState.Error(
                    message = "Не удалось обработать ответ от ${plugin.name}.\nВозможно, сайт изменил структуру.",
                    canRetry = false
                )
                _results.value = emptyList()
            } catch (e: Exception) {
                _error.value = e.message
                _uiState.value = SearchUiState.Error(
                    message = "Произошла ошибка: ${e.message}",
                    canRetry = true
                )
                _results.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun downloadTrack(searchTrack: SearchTrack) {
        val track = Track(
            id = searchTrack.id,
            title = searchTrack.title,
            artist = searchTrack.artist,
            duration = searchTrack.duration,
            coverUrl = searchTrack.coverUrl,
            sourceUrl = searchTrack.audioUrl,
            isDownloaded = false
        )
        if (downloadManager != null) {
            downloadManager.downloadTrack(track)
        } else {
            viewModelScope.launch {
                try {
                    trackRepository.addTrack(track)

                    val inputData = Data.Builder()
                        .putString(TrackDownloadWorker.KEY_TRACK_ID, searchTrack.id)
                        .putString(TrackDownloadWorker.KEY_AUDIO_URL, searchTrack.audioUrl ?: "")
                        .putString(
                            TrackDownloadWorker.KEY_FILE_NAME,
                            "${searchTrack.artist} - ${searchTrack.title}.mp3"
                        )
                        .build()

                    val downloadWork = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                        .setInputData(inputData)
                        .addTag("track_download")
                        .addTag(searchTrack.id)
                        .build()

                    workManager.enqueueUniqueWork(searchTrack.id, androidx.work.ExistingWorkPolicy.KEEP, downloadWork)
                } catch (e: Exception) {
                    _error.value = "Не удалось добавить трек: ${e.message}"
                }
            }
        }
    }

    fun deleteDownloadedFile(searchTrack: SearchTrack) {
        viewModelScope.launch {
            try {
                val existing = trackRepository.getTrackById(searchTrack.id)
                if (downloadManager != null) {
                    downloadManager.cancelOrDeleteDownload(searchTrack.id, existing?.filePath)
                } else {
                    workManager.cancelUniqueWork(searchTrack.id)
                    existing?.filePath?.let { path ->
                        try { java.io.File(path).delete() } catch (_: Exception) {}
                    }
                }
                trackRepository.clearDownloaded(searchTrack.id)
            } catch (e: Exception) {
                _error.value = "Не удалось удалить файл: ${e.message}"
            }
        }
    }

    fun deleteTrack(searchTrack: SearchTrack) {
        viewModelScope.launch {
            try {
                if (downloadManager != null) {
                    val existing = trackRepository.getTrackById(searchTrack.id)
                    downloadManager.cancelOrDeleteDownload(searchTrack.id, existing?.filePath)
                } else {
                    workManager.cancelUniqueWork(searchTrack.id)
                    val existing = trackRepository.getTrackById(searchTrack.id)
                    existing?.filePath?.let { path ->
                        try { java.io.File(path).delete() } catch (_: Exception) {}
                    }
                }
                try { playerController?.removeById(searchTrack.id) } catch (_: Exception) {}
                val existing = trackRepository.getTrackById(searchTrack.id)
                if (existing != null) {
                    downloadManager?.cancelOrDeleteDownload(searchTrack.id, existing.filePath)
                    trackRepository.deleteTrack(existing)
                } else {
                    trackRepository.deleteById(searchTrack.id)
                }
            } catch (e: Exception) {
                _error.value = "Не удалось удалить трек: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
        if (_uiState.value is SearchUiState.Error) {
            _uiState.value = SearchUiState.Idle
        }
    }

    fun addToLibraryOnly(searchTrack: SearchTrack) {
        val track = Track(
            id = searchTrack.id,
            title = searchTrack.title,
            artist = searchTrack.artist,
            duration = searchTrack.duration,
            coverUrl = searchTrack.coverUrl,
            sourceUrl = searchTrack.audioUrl,
            isDownloaded = false
        )
        viewModelScope.launch {
            trackRepository.ensureTrackExists(track)
        }
    }

    fun removeFromLibraryOnly(searchTrack: SearchTrack) {
        viewModelScope.launch {
            try {
                val existing = trackRepository.getTrackById(searchTrack.id)
                if (existing != null) {
                    if (existing.isDownloaded) {
                        downloadManager?.cancelOrDeleteDownload(searchTrack.id, existing.filePath)
                    }
                    trackRepository.deleteTrack(existing)
                } else {
                    trackRepository.deleteById(searchTrack.id)
                }
            } catch (e: Exception) {
                _error.value = "Не удалось удалить из Моей музыки: ${e.message}"
            }
        }
    }

    fun toggleLibrary(searchTrack: SearchTrack, isInLibrary: Boolean) {
        if (isInLibrary) {
            removeFromLibraryOnly(searchTrack)
        } else {
            addToLibraryOnly(searchTrack)
        }
    }
}