package com.mf.musicfree.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.model.Playlist
import com.mf.musicfree.data.model.PlaylistWithTracks
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.AddToPlaylistDialog
import com.mf.musicfree.ui.components.TrackCover
import org.koin.androidx.compose.koinViewModel

@Composable
fun LibraryScreen(
    playerViewModel: PlayerViewModel,
    onNavigate: (String) -> Unit,
    viewModel: LibraryViewModel = koinViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Треки", "Плейлисты")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> TracksTab(playerViewModel, viewModel)
            1 -> PlaylistsTab(viewModel, onNavigate)
        }
    }
}

@Composable
private fun TracksTab(
    playerViewModel: PlayerViewModel,
    viewModel: LibraryViewModel
) {
    val tracks by viewModel.tracks.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadFailed by viewModel.downloadFailed.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(tracks.firstOrNull()?.id) {
        if (tracks.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Пока нет треков.\nСкачай что-нибудь во вкладке Поиск!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(state = listState) {
                itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                    val isCurrent = track.id == currentTrack?.id
                    val progress = downloadProgress[track.id]
                    val isFailed = track.id in downloadFailed
                    val isDownloading = progress != null && !track.isDownloaded

                    TrackItem(
                        track = track,
                        isCurrent = isCurrent,
                        downloadProgress = progress,
                        isFailed = isFailed,
                        isDownloading = isDownloading,
                        onClick = {
                            if (isCurrent) {
                                playerViewModel.togglePlayPause()
                            } else {
                                playerViewModel.playLibraryTracks(tracks, index)
                            }
                        },
                        onDelete = { viewModel.deleteTrack(track) },
                        onPlayNext = { playerViewModel.playNext(track) },
                        onAddToQueue = { playerViewModel.addToQueue(track) },
                        onRetry = { viewModel.retryDownload(track) }
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

@Composable
private fun PlaylistsTab(
    viewModel: LibraryViewModel,
    onNavigate: (String) -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val deviceTracksCount by viewModel.deviceTracksCount.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceTracksCount()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Закрепленный специальный плейлист "Музыка с устройства"
            item(key = "device_tracks_pinned") {
                ListItem(
                    modifier = Modifier.clickable { onNavigate("playlist_detail/device") },
                    headlineContent = {
                        Text(
                            "Музыка с устройства",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        val countText = if (deviceTracksCount > 0) {
                            "$deviceTracksCount ${trackCountString(deviceTracksCount)}"
                        } else {
                            "Локальные треки"
                        }
                        Text(
                            countText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.medium
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    thickness = 0.5.dp
                )
            }

            if (playlists.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Нет созданных плейлистов",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(playlists, key = { it.playlist.id }) { playlistWithTracks ->
                    PlaylistItem(
                        playlistWithTracks = playlistWithTracks,
                        onClick = { onNavigate("playlist_detail/${playlistWithTracks.playlist.id}") },
                        onDelete = { viewModel.deletePlaylist(playlistWithTracks.playlist) },
                        onRename = { newName -> viewModel.renamePlaylist(playlistWithTracks.playlist.id, newName) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, "Создать плейлист")
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PlaylistItem(
    playlistWithTracks: PlaylistWithTracks,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = {
            Text(
                playlistWithTracks.playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val count = playlistWithTracks.tracks.size
            Text(
                "$count ${trackCountString(count)}",
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        onClick = {
                            showMenu = false
                            showRenameDialog = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
        }
    )

    if (showRenameDialog) {
        CreatePlaylistDialog(
            title = "Переименовать плейлист",
            initialName = playlistWithTracks.playlist.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                onRename(newName)
                showRenameDialog = false
            }
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    title: String = "Новый плейлист",
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    track: Track,
    isCurrent: Boolean,
    downloadProgress: Int? = null,
    isFailed: Boolean = false,
    isDownloading: Boolean = false,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onRetry: () -> Unit = {},
    onAddToPlaylist: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    val bgColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(modifier = Modifier.fillMaxWidth().background(bgColor)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .combinedClickable(onClick = onClick, onLongClick = { showMenu = true })
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TrackCover(
                coverUrl = track.coverUrl,
                title = track.title,
                modifier = Modifier.size(48.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            when {
                isDownloading -> {
                    if (downloadProgress != null && downloadProgress >= 0) {
                        Text(
                            "${downloadProgress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                isFailed -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.MusicNote, "Повторить", tint = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    } else {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Меню")
                        }
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
                        text = { Text("Играть следующим") },
                        onClick = { showMenu = false; onPlayNext() }
                    )
                    DropdownMenuItem(
                        text = { Text("Добавить в очередь") },
                        onClick = { showMenu = false; onAddToQueue() }
                    )
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Добавить в плейлист") },
                            onClick = { showMenu = false; onAddToPlaylist() }
                        )
                    }
                }
            }
        }
        if (isDownloading) {
            if (downloadProgress != null && downloadProgress >= 0) {
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

private fun trackCountString(count: Int): String {
    return when {
        count % 10 == 1 && count % 100 != 11 -> "трек"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "трека"
        else -> "треков"
    }
}