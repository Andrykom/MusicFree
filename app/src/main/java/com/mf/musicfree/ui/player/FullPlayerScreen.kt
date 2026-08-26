package com.mf.musicfree.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome // <-- Новая иконка волны/магии
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.player.RepeatMode
import com.mf.musicfree.ui.components.AddToPlaylistDialog
import com.mf.musicfree.ui.components.TrackCover
import com.mf.musicfree.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onLyrics: () -> Unit
) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val isShuffled by viewModel.isShuffled.collectAsState()
    val isSaved by viewModel.isCurrentTrackSaved.collectAsState()

    // Подписываемся на стейт радио
    val isRadioMode by viewModel.isRadioMode.collectAsState()

    var showAddToPlaylist by remember { mutableStateOf(false) }

    if (currentTrack == null) {
        onBack()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сейчас играет") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            TrackCover(
                coverUrl = currentTrack?.coverUrl,
                title = currentTrack?.title ?: "",
                modifier = Modifier.size(280.dp)
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = currentTrack!!.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Text(
                    text = currentTrack!!.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = progress,
                    onValueChange = { viewModel.seekTo(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(currentPosition.formatDuration(), style = MaterialTheme.typography.labelMedium)
                    Text(duration.formatDuration(), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Ряд основных контроллеров
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle, "Shuffle",
                        tint = if (isShuffled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { viewModel.previous() }) {
                    Icon(Icons.Default.SkipPrevious, "Prev", modifier = Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { viewModel.toggleRepeat() }) {
                    Icon(
                        when (repeatMode) {
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        "Repeat",
                        tint = if (repeatMode != RepeatMode.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Ряд дополнительных действий (теперь 4 кнопки)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isSaved) viewModel.deleteCurrentTrack() else viewModel.saveCurrentTrack()
                }) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Delete else Icons.Default.Download,
                        contentDescription = if (isSaved) "Удалить" else "Сохранить",
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { showAddToPlaylist = true }) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "В плейлист",
                        modifier = Modifier.size(28.dp)
                    )
                }

                // НОВАЯ КНОПКА: Моя Волна
                IconButton(onClick = { viewModel.toggleRadioMode() }) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Моя Волна",
                        tint = if (isRadioMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onLyrics) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Subject,
                        contentDescription = "Текст песни",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }

    if (showAddToPlaylist) {
        currentTrack?.id?.let { id ->
            AddToPlaylistDialog(trackId = id, onDismiss = { showAddToPlaylist = false })
        }
    }
}