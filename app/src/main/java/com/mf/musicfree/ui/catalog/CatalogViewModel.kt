package com.mf.musicfree.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mf.musicfree.data.download.DownloadManager
import com.mf.musicfree.data.model.AlbumInfo
import com.mf.musicfree.data.model.AlbumTrackItem
import com.mf.musicfree.data.model.ArtistPageData
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.CatalogRepository
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.plugin.SearchTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ArtistUiState {
    data object Loading : ArtistUiState()
    data class Success(val data: ArtistPageData) : ArtistUiState()
    data class Error(val message: String) : ArtistUiState()
}

sealed class AlbumUiState {
    data object Loading : AlbumUiState()
    data class Success(val album: AlbumInfo, val tracks: List<AlbumTrackItem>) : AlbumUiState()
    data class Error(val message: String) : AlbumUiState()
}

class CatalogViewModel(
    private val catalogRepository: CatalogRepository,
    private val trackRepository: TrackRepository,
    private val downloadManager: DownloadManager? = null
) : ViewModel() {

    private val _artistState = MutableStateFlow<ArtistUiState>(ArtistUiState.Loading)
    val artistState: StateFlow<ArtistUiState> = _artistState

    private val _albumState = MutableStateFlow<AlbumUiState>(AlbumUiState.Loading)
    val albumState: StateFlow<AlbumUiState> = _albumState

    private val _resolvingTrackIndex = MutableStateFlow<Int?>(null)
    val resolvingTrackIndex: StateFlow<Int?> = _resolvingTrackIndex

    private val _isResolvingAlbum = MutableStateFlow(false)
    val isResolvingAlbum: StateFlow<Boolean> = _isResolvingAlbum

    private val _messageEvent = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val messageEvent: SharedFlow<String> = _messageEvent.asSharedFlow()

    val downloadProgress = downloadManager?.downloadProgress
        ?: MutableStateFlow(emptyMap<String, Int>())

    val libraryIds: StateFlow<Set<String>> = trackRepository.getAllTracks()
        .map { list -> list.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val libraryTitleKeys: StateFlow<Set<String>> = trackRepository.getAllTracks()
        .map { list -> list.map { trackKey(it.artist, it.title) }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun trackKey(artist: String, title: String): String =
        "${artist.lowercase().replace('ё', 'е').trim()}|${title.lowercase().replace('ё', 'е').trim()}"

    val downloadedIds: StateFlow<Set<String>> = trackRepository.getAllTracks()
        .map { list -> list.filter { it.isDownloaded }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private var loadedArtistKey: String? = null
    private var loadedAlbumKey: String? = null

    fun loadArtist(artistKey: String, force: Boolean = false) {
        if (!force && loadedArtistKey == artistKey && _artistState.value is ArtistUiState.Success) return
        loadedArtistKey = artistKey
        viewModelScope.launch {
            _artistState.value = ArtistUiState.Loading
            try {
                val page = catalogRepository.loadArtistPage(artistKey)
                if (page != null) {
                    _artistState.value = ArtistUiState.Success(page)
                } else {
                    _artistState.value = ArtistUiState.Error("Исполнитель не найден")
                }
            } catch (e: Exception) {
                _artistState.value = ArtistUiState.Error("Ошибка загрузки: ${e.message ?: "нет сети"}")
            }
        }
    }

    fun loadAlbum(albumKey: String, force: Boolean = false) {
        if (!force && loadedAlbumKey == albumKey && _albumState.value is AlbumUiState.Success) return
        loadedAlbumKey = albumKey
        viewModelScope.launch {
            _albumState.value = AlbumUiState.Loading
            try {
                val pair = catalogRepository.loadAlbumTracks(albumKey)
                if (pair != null) {
                    _albumState.value = AlbumUiState.Success(pair.first, pair.second)
                } else {
                    _albumState.value = AlbumUiState.Error("Альбом не найден")
                }
            } catch (e: Exception) {
                _albumState.value = AlbumUiState.Error("Ошибка загрузки альбома: ${e.message ?: "нет сети"}")
            }
        }
    }

    fun registerArtistByName(name: String): String = catalogRepository.registerArtistByName(name)

    /**
     * Запускает выбранный трек альбома. Если аудиопоток ещё не сопоставлен,
     * лениво находит его через плагины и кэширует в состоянии экрана.
     */
    private fun buildFullAlbumTracks(album: AlbumInfo, items: List<AlbumTrackItem>): List<Track> {
        return items.mapIndexed { idx, item ->
            val resolved = item.resolvedTrack
            if (resolved != null && !resolved.audioUrl.isNullOrBlank()) {
                resolved.toTrack().copy(
                    album = album.title,
                    coverUrl = album.coverUrl ?: resolved.coverUrl
                )
            } else {
                Track(
                    id = "alb_${album.id}_${idx}_${item.title.hashCode().toUInt()}",
                    title = item.title,
                    artist = item.artist.ifBlank { album.artistName },
                    album = album.title,
                    duration = item.durationMs,
                    coverUrl = album.coverUrl ?: item.coverUrl,
                    sourceUrl = null,
                    isDownloaded = false
                )
            }
        }
    }

    /**
     * Запускает выбранный трек альбома и сразу кладёт ВЕСЬ альбом в очередь.
     * Недогруженные треки догружаются в фоне внутри PlayerViewModel.
     */
    fun playAlbumTrack(index: Int, onPlayQueue: (List<Track>, Int) -> Unit) {
        val current = _albumState.value as? AlbumUiState.Success ?: return
        val item = current.tracks.getOrNull(index) ?: return

        if (item.resolvedTrack != null) {
            val fullQueue = buildFullAlbumTracks(current.album, current.tracks)
            onPlayQueue(fullQueue, index)
            return
        }

        if (_resolvingTrackIndex.value != null) return
        viewModelScope.launch {
            _resolvingTrackIndex.value = index
            try {
                val found = catalogRepository.resolveTrackStream(
                    artist = item.artist.ifBlank { current.album.artistName },
                    title = item.title,
                    coverUrl = item.coverUrl ?: current.album.coverUrl
                )
                if (found == null) {
                    _messageEvent.tryEmit("Не удалось найти «${item.title}» на источниках")
                    return@launch
                }
                val updatedTracks = current.tracks.toMutableList().apply {
                    set(index, item.copy(resolvedTrack = found))
                }
                _albumState.value = current.copy(tracks = updatedTracks)

                val fullQueue = buildFullAlbumTracks(current.album, updatedTracks)
                onPlayQueue(fullQueue, index)
            } finally {
                _resolvingTrackIndex.value = null
            }
        }
    }

    /**
     * Запускает весь альбом (по порядку или вперемешку).
     * Резолвит только первый трек для мгновенного старта, остальные догружаются в фоне.
     */
    fun playWholeAlbum(shuffle: Boolean, onPlayQueue: (List<Track>, Int) -> Unit) {
        val current = _albumState.value as? AlbumUiState.Success ?: return
        if (current.tracks.isEmpty()) return

        val orderedIndices = current.tracks.indices.toList().let { if (shuffle) it.shuffled() else it }
        val firstIdx = orderedIndices.first()
        val firstItem = current.tracks[firstIdx]

        if (firstItem.resolvedTrack != null) {
            val reorderedItems = orderedIndices.map { current.tracks[it] }
            onPlayQueue(buildFullAlbumTracks(current.album, reorderedItems), 0)
            return
        }

        if (_isResolvingAlbum.value) return
        viewModelScope.launch {
            _isResolvingAlbum.value = true
            try {
                val found = catalogRepository.resolveTrackStream(
                    artist = firstItem.artist.ifBlank { current.album.artistName },
                    title = firstItem.title,
                    coverUrl = firstItem.coverUrl ?: current.album.coverUrl
                )
                if (found == null) {
                    _messageEvent.tryEmit("Не удалось найти «${firstItem.title}» на источниках")
                    return@launch
                }
                val updatedTracks = current.tracks.toMutableList().apply {
                    set(firstIdx, firstItem.copy(resolvedTrack = found))
                }
                _albumState.value = current.copy(tracks = updatedTracks)

                val reorderedItems = orderedIndices.map { updatedTracks[it] }
                onPlayQueue(buildFullAlbumTracks(current.album, reorderedItems), 0)
            } finally {
                _isResolvingAlbum.value = false
            }
        }
    }

    fun resolveAndExecute(
        index: Int,
        action: (SearchTrack) -> Unit
    ) {
        val current = _albumState.value as? AlbumUiState.Success ?: return
        val item = current.tracks.getOrNull(index) ?: return
        item.resolvedTrack?.let {
            action(it)
            return
        }
        if (_resolvingTrackIndex.value != null) return
        viewModelScope.launch {
            _resolvingTrackIndex.value = index
            try {
                val found = catalogRepository.resolveTrackStream(
                    artist = item.artist.ifBlank { current.album.artistName },
                    title = item.title,
                    coverUrl = item.coverUrl ?: current.album.coverUrl
                )
                if (found == null) {
                    _messageEvent.tryEmit("Трек «${item.title}» не найден на источниках")
                    return@launch
                }
                val updatedTracks = current.tracks.toMutableList().apply {
                    set(index, item.copy(resolvedTrack = found))
                }
                _albumState.value = current.copy(tracks = updatedTracks)
                action(found)
            } finally {
                _resolvingTrackIndex.value = null
            }
        }
    }

    fun toggleLibrary(searchTrack: SearchTrack, albumTitle: String? = null) {
        viewModelScope.launch {
            val inLib = searchTrack.id in libraryIds.value
            if (inLib) {
                val existing = trackRepository.getTrackById(searchTrack.id)
                if (existing != null) {
                    downloadManager?.cancelOrDeleteDownload(searchTrack.id, existing.filePath)
                    trackRepository.deleteTrack(existing)
                } else {
                    trackRepository.deleteById(searchTrack.id)
                }
                _messageEvent.tryEmit("«${searchTrack.title}» удалён из Моей музыки")
            } else {
                trackRepository.ensureTrackExists(searchTrack.toTrack().copy(album = albumTitle))
                _messageEvent.tryEmit("«${searchTrack.title}» добавлен в Мою музыку")
            }
        }
    }

    fun toggleAlbumTrackLibrary(index: Int, albumTitle: String) {
        val current = _albumState.value as? AlbumUiState.Success ?: return
        val item = current.tracks.getOrNull(index) ?: return
        val artist = item.artist.ifBlank { current.album.artistName }
        val key = trackKey(artist, item.title)

        // Если трек уже в библиотеке (даже если resolvedTrack == null) — удаляем сразу без поиска
        if (item.resolvedTrack?.id in libraryIds.value || key in libraryTitleKeys.value) {
            viewModelScope.launch {
                val all = trackRepository.getAllTracks().first()
                val existing = item.resolvedTrack?.let { r -> all.firstOrNull { it.id == r.id } }
                    ?: all.firstOrNull { trackKey(it.artist, it.title) == key }
                if (existing != null) {
                    downloadManager?.cancelOrDeleteDownload(existing.id, existing.filePath)
                    trackRepository.deleteTrack(existing)
                    _messageEvent.tryEmit("«${item.title}» удалён из Моей музыки")
                }
            }
            return
        }

        // Если трека ещё нет в библиотеке — догружаем и сохраняем с привязкой к альбому
        resolveAndExecute(index) { found ->
            toggleLibrary(found, albumTitle)
        }
    }

    fun downloadTrack(searchTrack: SearchTrack) {
        val track = searchTrack.toTrack()
        downloadManager?.downloadTrack(track)
        _messageEvent.tryEmit("Скачивание «${searchTrack.title}»...")
    }

    fun deleteDownloadedFile(searchTrack: SearchTrack) {
        viewModelScope.launch {
            val existing = trackRepository.getTrackById(searchTrack.id)
            downloadManager?.cancelOrDeleteDownload(searchTrack.id, existing?.filePath)
            trackRepository.clearDownloaded(searchTrack.id)
            _messageEvent.tryEmit("Скачанный файл «${searchTrack.title}» удалён")
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