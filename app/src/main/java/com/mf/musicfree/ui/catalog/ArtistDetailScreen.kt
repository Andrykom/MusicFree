package com.mf.musicfree.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.model.AlbumInfo
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.TrackCover
import com.mf.musicfree.ui.search.SearchResultItem
import org.koin.androidx.compose.koinViewModel
import com.mf.musicfree.ui.components.FullScreenImageDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistKey: String,
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    viewModel: CatalogViewModel = koinViewModel()
) {
    val state by viewModel.artistState.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val libraryIds by viewModel.libraryIds.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFullPhoto by remember { mutableStateOf(false) }

    LaunchedEffect(artistKey) {
        viewModel.loadArtist(artistKey)
    }

    LaunchedEffect(Unit) {
        viewModel.messageEvent.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    val topTitle = (state as? ArtistUiState.Success)?.data?.artist?.name ?: "Исполнитель"

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = topTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        when (val ui = state) {
            is ArtistUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ArtistUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = ui.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.loadArtist(artistKey, force = true) }) {
                            Text("Повторить")
                        }
                    }
                }
            }

            is ArtistUiState.Success -> {
                val data = ui.data
                val playableTracks = data.allZvuchTracks.ifEmpty { data.singles }
                val displayedSingles = data.singles.ifEmpty { data.allZvuchTracks }

                if (showFullPhoto && !data.artist.pictureUrl.isNullOrBlank()) {
                    FullScreenImageDialog(
                        imageUrl = data.artist.pictureUrl,
                        title = data.artist.name,
                        onDismiss = { showFullPhoto = false }
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Шапка исполнителя
                    item(key = "artist_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            TrackCover(
                                coverUrl = data.artist.pictureUrl,
                                title = data.artist.name,
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !data.artist.pictureUrl.isNullOrBlank()) {
                                        showFullPhoto = true
                                    }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = data.artist.name,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitleParts = buildList {
                                if (data.albums.isNotEmpty()) add("Альбомов: ${data.albums.size}")
                                if (displayedSingles.isNotEmpty()) add("Треков: ${displayedSingles.size}")
                            }
                            if (subtitleParts.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = subtitleParts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (playableTracks.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { playerViewModel.playSearchResults(playableTracks, 0) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Слушать")
                                    }
                                    OutlinedButton(
                                        onClick = { playerViewModel.playSearchResults(playableTracks.shuffled(), 0) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Вперемешку")
                                    }
                                }
                            }
                        }
                    }

                    // Блок альбомов
                    if (data.albums.isNotEmpty()) {
                        item(key = "albums_section") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Альбомы",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(data.albums, key = { it.id }) { album ->
                                        AlbumCard(
                                            album = album,
                                            onClick = { onOpenAlbum(album.id) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    // Синглы и треки без привязки к альбомам
                    if (displayedSingles.isNotEmpty()) {
                        item(key = "singles_header") {
                            Text(
                                text = if (data.singles.isNotEmpty()) "Синглы и фиты" else "Треки",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        itemsIndexed(displayedSingles, key = { _, item -> item.id }) { index, track ->
                            val isCurrent = track.id == currentTrack?.id
                            SearchResultItem(
                                track = track,
                                isDownloaded = track.id in downloadedIds,
                                downloadProgress = downloadProgress[track.id],
                                isInLibrary = track.id in libraryIds,
                                isCurrent = isCurrent,
                                onClick = {
                                    if (isCurrent) {
                                        playerViewModel.togglePlayPause()
                                    } else {
                                        playerViewModel.playSearchResults(displayedSingles, index)
                                    }
                                },
                                onDownload = { viewModel.downloadTrack(track) },
                                onToggleLibrary = { viewModel.toggleLibrary(track) },
                                onDeleteDownloadedFile = { viewModel.deleteDownloadedFile(track) },
                                onPlayNext = { playerViewModel.playNext(track) },
                                onAddToQueue = { playerViewModel.addToQueue(track) }
                            )
                            if (index < displayedSingles.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    } else if (data.albums.isEmpty()) {
                        item(key = "empty_artist") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Треки исполнителя не найдены",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCard(
    album: AlbumInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArtist: Boolean = false
) {
    Column(
        modifier = modifier
            .width(136.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        TrackCover(
            coverUrl = album.coverUrl,
            title = album.title,
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val meta = buildList {
            if (showArtist && album.artistName.isNotBlank()) add(album.artistName)
            album.releaseYear?.let { add(it) }
            if (!showArtist && album.trackCount > 0) add("${album.trackCount} тр.")
        }.joinToString(" • ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}