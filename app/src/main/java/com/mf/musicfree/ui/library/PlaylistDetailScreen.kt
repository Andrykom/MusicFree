package com.mf.musicfree.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.AddToPlaylistDialog
import com.mf.musicfree.ui.components.TrackCover
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playerViewModel: PlayerViewModel,
    onNavigateBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = koinViewModel { parametersOf(playlistId) }
) {
    val playlistWithTracks by viewModel.playlist.collectAsState()
    val currentTrack by playerViewModel.currentTrack.collectAsState()

    val playlist = playlistWithTracks?.playlist
    val tracks = playlistWithTracks?.tracks ?: emptyList()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // ← не дублируем system insets
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        playlist?.name ?: "Плейлист",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "В этом плейлисте пока нет треков",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                        val isCurrent = track.id == currentTrack?.id

                        TrackItem(
                            track = track,
                            isCurrent = isCurrent,
                            onClick = {
                                if (isCurrent) {
                                    playerViewModel.togglePlayPause()
                                } else {
                                    playerViewModel.playLibraryTracks(tracks, index)
                                }
                            },
                            onDelete = { viewModel.removeTrack(track.id) }
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