package com.mf.musicfree.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import com.mf.musicfree.ui.components.AddToPlaylistDialog
import com.mf.musicfree.ui.components.TrackCover
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.clip
import com.mf.musicfree.ui.catalog.AlbumCard

@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    onNavigate: (String) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
    pluginManager: PluginManager = koinInject()
) {
    val catalogResult by viewModel.catalogResult.collectAsState()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val downloadedIds by viewModel.downloadedIds.collectAsState()
    val selectedPlugin by viewModel.selectedPlugin.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val libraryIds by viewModel.libraryIds.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val error by viewModel.error.collectAsState()

    val context = LocalContext.current
    val plugins = remember { pluginManager.plugins }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        if (uiState !is SearchUiState.Error) {
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
        ) {
            // ← Поисковой бар — первый item, уходит при скролле естественно
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChange(it) },
                    placeholder = { Text("Поиск треков...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            viewModel.search()
                        }
                    )
                )

                if (plugins.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(plugins) { plugin ->
                            val selected = selectedPlugin?.name == plugin.name
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val nextPlugin = if (selected) null else plugin
                                    viewModel.selectPlugin(nextPlugin)
                                    if (nextPlugin != null && query.isNotBlank()) {
                                        viewModel.search()
                                    }
                                },
                                label = { Text(plugin.name) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // ── Контент ──
            when (val state = uiState) {
                is SearchUiState.Idle -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "Введите название трека или исполнителя",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                is SearchUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                is SearchUiState.Empty -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SentimentDissatisfied,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }

                is SearchUiState.Error -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state.canRetry) {
                                    Button(onClick = { viewModel.retry() }) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Повторить")
                                    }
                                }
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text("Закрыть")
                                }
                            }
                        }
                    }
                }

                is SearchUiState.Success -> {
                    itemsIndexed(results, key = { _, item -> item.id }) { index, track ->
                        val isCurrent = track.id == currentTrack?.id
                        val prog = downloadProgress[track.id]

                        SearchResultItem(
                            track = track,
                            isDownloaded = track.id in downloadedIds,
                            downloadProgress = prog,
                            isInLibrary = track.id in libraryIds,
                            isCurrent = isCurrent,
                            onClick = {
                                if (isCurrent) {
                                    playerViewModel.togglePlayPause()
                                } else {
                                    playerViewModel.playSearchResults(results, index)
                                }
                            },
                            onDownload = {
                                viewModel.downloadTrack(track)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Скачивание «${track.title}»...",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onToggleLibrary = {
                                val inLib = track.id in libraryIds
                                viewModel.toggleLibrary(track, inLib)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (inLib) "«${track.title}» удален из Моей музыки" else "«${track.title}» добавлен в Мою музыку",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onDeleteDownloadedFile = {
                                viewModel.deleteDownloadedFile(track)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Скачанный файл «${track.title}» удален",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onDelete = {
                                viewModel.deleteTrack(track)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "«${track.title}» удален",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onPlayNext = { playerViewModel.playNext(track) },
                            onAddToQueue = { playerViewModel.addToQueue(track) }
                        )
                        if (index < results.lastIndex) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultItem(
    track: SearchTrack,
    isDownloaded: Boolean,
    downloadProgress: Int? = null,
    isInLibrary: Boolean = false,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onToggleLibrary: () -> Unit = {},
    onDeleteDownloadedFile: () -> Unit = {},
    onDelete: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {}
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
                .height(64.dp)
                .background(bgColor)
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    track.quality?.let {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
                    track.durationText?.let {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1
                        )
                    }
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
                    IconButton(onClick = onDeleteDownloadedFile) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Скачано (нажмите для удаления файла)",
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
                if (!isInLibrary) {
                    DropdownMenuItem(
                        text = { Text("Добавить в Мою музыку") },
                        onClick = { showMenu = false; onToggleLibrary() }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Удалить из Моей музыки") },
                        onClick = { showMenu = false; onToggleLibrary() }
                    )
                }
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