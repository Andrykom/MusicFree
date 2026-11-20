package com.mf.musicfree.ui.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import com.mf.musicfree.data.model.AlbumTrackItem
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.TrackCover
import com.mf.musicfree.util.formatDuration
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumKey: String,
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    viewModel: CatalogViewModel = koinViewModel()
) {
    val state by viewModel.albumState.collectAsState()
    val resolvingIndex by viewModel.resolvingTrackIndex.collectAsState()
    val isResolvingAlbum by viewModel.isResolvingAlbum.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val libraryIds by viewModel.libraryIds.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(albumKey) {
        viewModel.loadAlbum(albumKey)
    }

    LaunchedEffect(Unit) {
        viewModel.messageEvent.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    val topTitle = (state as? AlbumUiState.Success)?.album?.title ?: "Альбом"

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
            is AlbumUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is AlbumUiState.Error -> {
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
                        Button(onClick = { viewModel.loadAlbum(albumKey, force = true) }) {
                            Text("Повторить")
                        }
                    }
                }
            }

            is AlbumUiState.Success -> {
                val album = ui.album
                val tracks = ui.tracks

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Шапка альбома
                    item(key = "album_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            TrackCover(
                                coverUrl = album.coverUrl,
                                title = album.title,
                                modifier = Modifier
                                    .size(200.dp)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = album.title,
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (album.artistName.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = album.artistName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        val key = viewModel.registerArtistByName(album.artistName)
                                        onOpenArtist(key)
                                    }
                                )
                            }
                            val meta = buildList {
                                album.releaseYear?.let { add(it) }
                                if (tracks.isNotEmpty()) add("Треков: ${tracks.size}")
                            }.joinToString(" • ")
                            if (meta.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (tracks.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.playWholeAlbum(shuffle = false) { queue, idx ->
                                                playerViewModel.playSearchResults(queue, idx)
                                            }
                                        },
                                        enabled = !isResolvingAlbum,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isResolvingAlbum) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Слушать")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.playWholeAlbum(shuffle = true) { queue, idx ->
                                                playerViewModel.playSearchResults(queue, idx)
                                            }
                                        },
                                        enabled = !isResolvingAlbum,
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

                    if (tracks.isEmpty()) {
                        item(key = "album_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Треклист альбома недоступен",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        itemsIndexed(tracks, key = { idx, item -> "alb_tr_${idx}_${item.title}" }) { index, item ->
                            val resolved = item.resolvedTrack
                            val isCurrent = resolved != null && resolved.id == currentTrack?.id
                            val isInLibrary = resolved != null && resolved.id in libraryIds
                            val isDownloaded = resolved != null && resolved.id in downloadedIds
                            val progress = resolved?.let { downloadProgress[it.id] }
                            val isResolving = resolvingIndex == index

                            AlbumTrackRow(
                                item = item,
                                isCurrent = isCurrent,
                                isResolving = isResolving,
                                isInLibrary = isInLibrary,
                                isDownloaded = isDownloaded,
                                downloadProgress = progress,
                                onClick = {
                                    if (isCurrent) {
                                        playerViewModel.togglePlayPause()
                                    } else {
                                        viewModel.playAlbumTrack(index) { queue, idx ->
                                            playerViewModel.playSearchResults(queue, idx)
                                        }
                                    }
                                },
                                onToggleLibrary = {
                                    viewModel.resolveAndExecute(index) { viewModel.toggleLibrary(it) }
                                },
                                onDownload = {
                                    viewModel.resolveAndExecute(index) { viewModel.downloadTrack(it) }
                                },
                                onDeleteDownload = {
                                    resolved?.let { viewModel.deleteDownloadedFile(it) }
                                },
                                onPlayNext = {
                                    viewModel.resolveAndExecute(index) { playerViewModel.playNext(it) }
                                },
                                onAddToQueue = {
                                    viewModel.resolveAndExecute(index) { playerViewModel.addToQueue(it) }
                                }
                            )
                            if (index < tracks.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    item: AlbumTrackItem,
    isCurrent: Boolean,
    isResolving: Boolean,
    isInLibrary: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Int?,
    onClick: () -> Unit,
    onToggleLibrary: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val bgColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .background(bgColor)
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = item.trackNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        item.resolvedTrack != null -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                    }
                )
                val meta = buildList {
                    if (item.artist.isNotBlank()) add(item.artist)
                    if (item.durationMs > 0L) add(item.durationMs.formatDuration())
                }.joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleLibrary) {
                    Icon(
                        imageVector = if (isInLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isInLibrary) "В Моей музыке" else "В библиотеку",
                        tint = if (isInLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (downloadProgress != null && !isDownloaded) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (downloadProgress > 0) {
                            CircularProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                } else if (isDownloaded) {
                    IconButton(onClick = onDeleteDownload) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Скачано",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    IconButton(onClick = onDownload) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Скачать",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (isInLibrary) "Удалить из Моей музыки" else "Добавить в Мою музыку") },
                    onClick = { showMenu = false; onToggleLibrary() }
                )
                DropdownMenuItem(
                    text = { Text("Играть следующим") },
                    onClick = { showMenu = false; onPlayNext() }
                )
                DropdownMenuItem(
                    text = { Text("Добавить в очередь") },
                    onClick = { showMenu = false; onAddToQueue() }
                )
            }
        }
    }
}