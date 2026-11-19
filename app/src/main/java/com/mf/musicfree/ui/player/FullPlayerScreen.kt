package com.mf.musicfree.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.player.RepeatMode
import com.mf.musicfree.ui.components.AddToPlaylistDialog
import com.mf.musicfree.ui.components.TrackCover
import com.mf.musicfree.util.formatDuration
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onLyrics: () -> Unit,
    onArtistClick: (String) -> Unit = {}
) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffled by viewModel.isShuffled.collectAsState()
    val isInLibrary by viewModel.isCurrentTrackInLibrary.collectAsState()
    val isDownloaded by viewModel.isCurrentTrackDownloaded.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    val isRadioMode by viewModel.isRadioMode.collectAsState()
    val isRadioBuffering by viewModel.isRadioBuffering.collectAsState()
    val isArtistBlocked by viewModel.isCurrentArtistBlocked.collectAsState()

    var showAddToPlaylist by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    val track = currentTrack
    if (track == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .offset { IntOffset(0, offsetY.value.toInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value > 300f) {
                                    onBack()
                                } else {
                                    offsetY.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                            scope.launch { offsetY.snapTo(newOffset) }
                            change.consume()
                        }
                    )
                }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Сейчас играет") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showQueue = true }) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Очередь")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    var coverDragOffsetX by remember { mutableFloatStateOf(0f) }
                    val animatedOffsetX by animateFloatAsState(
                        targetValue = coverDragOffsetX,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "coverOffset"
                    )

                    TrackCover(
                        coverUrl = track.coverUrl,
                        title = track.title,
                        modifier = Modifier
                            .size(280.dp)
                            .offset { IntOffset(animatedOffsetX.toInt(), 0) }
                            .pointerInput(track.id) {
                                detectHorizontalDragGestures(
                                    onDragStart = { coverDragOffsetX = 0f },
                                    onDragEnd = {
                                        if (coverDragOffsetX < -120f) {
                                            viewModel.next()
                                        } else if (coverDragOffsetX > 120f) {
                                            viewModel.previous()
                                        }
                                        coverDragOffsetX = 0f
                                    },
                                    onDragCancel = { coverDragOffsetX = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        coverDragOffsetX = (coverDragOffsetX + dragAmount).coerceIn(-250f, 250f)
                                    }
                                )
                            }
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onArtistClick(track.artist) }
                        )
                    }

                    // Изолированный прогресс-бар — перерисовывается только он сам
                    PlayerProgressSection(viewModel = viewModel)

                    // Ряд основных контроллеров
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Перемешать",
                                tint = if (isShuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel.previous() }) {
                            Icon(Icons.Default.SkipPrevious, "Назад", modifier = Modifier.size(36.dp))
                        }
                        FilledIconButton(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "Пауза" else "Играть",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.next() },
                            enabled = !isRadioBuffering
                        ) {
                            if (isRadioBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(Icons.Default.SkipNext, "Вперёд", modifier = Modifier.size(36.dp))
                            }
                        }
                        IconButton(onClick = { viewModel.toggleRepeat() }) {
                            Icon(
                                when (repeatMode) {
                                    RepeatMode.ONE -> Icons.Default.RepeatOne
                                    else -> Icons.Default.Repeat
                                },
                                contentDescription = "Повтор",
                                tint = if (repeatMode != RepeatMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Ряд дополнительных действий
                    val currentDownloadProgress = track.let { downloadProgress[it.id] }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // В Мою музыку
                        IconButton(onClick = { viewModel.toggleCurrentTrackInLibrary() }) {
                            Icon(
                                imageVector = if (isInLibrary) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isInLibrary) "В Моей музыке" else "В библиотеку",
                                tint = if (isInLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Скачать офлайн
                        if (currentDownloadProgress != null) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentDownloadProgress >= 0) {
                                    CircularProgressIndicator(
                                        progress = { currentDownloadProgress / 100f },
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                }
                            }
                        } else if (isDownloaded) {
                            IconButton(onClick = { viewModel.deleteDownloadedFileForCurrentTrack() }) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Скачано (нажмите, чтобы удалить файл)",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        } else {
                            IconButton(onClick = { viewModel.downloadCurrentTrack() }) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Скачать",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // В плейлист
                        IconButton(onClick = { showAddToPlaylist = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "В плейлист",
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Моя Волна
                        IconButton(onClick = { viewModel.toggleRadioMode() }) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Моя Волна",
                                tint = if (isRadioMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Заблокировать артиста
                        IconButton(onClick = { viewModel.toggleBlockCurrentArtist() }) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = if (isArtistBlocked) "Разблокировать" else "Заблокировать артиста",
                                tint = if (isArtistBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Текст песни
                        IconButton(onClick = onLyrics) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Subject,
                                contentDescription = "Текст песни",
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }

        if (showQueue) {
            QueueBottomSheet(viewModel = viewModel, onDismiss = { showQueue = false })
        }

        if (showAddToPlaylist) {
            AddToPlaylistDialog(
                trackId = track.id,
                track = track,
                onDismiss = { showAddToPlaylist = false }
            )
        }
    }
}

@Composable
private fun PlayerProgressSection(viewModel: PlayerViewModel) {
    val progress by viewModel.progress.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val displayProgress = if (isDragging) dragProgress else progress
    val displayPosition = if (isDragging) (dragProgress * duration).toLong() else currentPosition

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayProgress.coerceIn(0f, 1f),
            onValueChange = {
                isDragging = true
                dragProgress = it
            },
            onValueChangeFinished = {
                viewModel.seekTo(dragProgress)
                isDragging = false
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(displayPosition.formatDuration(), style = MaterialTheme.typography.labelMedium)
            Text(duration.formatDuration(), style = MaterialTheme.typography.labelMedium)
        }
    }
}