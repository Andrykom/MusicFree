package com.mf.musicfree.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.AddToPlaylistDialog
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
    val context = LocalContext.current

    val isDevicePlaylist = playlistId == "device"

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            if (isDevicePlaylist) {
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted && isDevicePlaylist) {
            viewModel.loadDeviceTracks()
        }
    }

    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    val playlist = playlistWithTracks?.playlist
    val tracks = playlistWithTracks?.tracks ?: emptyList()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // ← не дублируем system insets
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isDevicePlaylist) "Музыка с устройства" else (playlist?.name ?: "Плейлист"),
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
            if (isDevicePlaylist && !hasPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Доступ к аудиофайлам",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Чтобы отображать и проигрывать локальные треки с вашего устройства, приложению требуется разрешение.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(permission) }) {
                        Text("Предоставить доступ")
                    }
                }
            } else if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (isDevicePlaylist) "На устройстве не найдено аудиофайлов" else "В этом плейлисте пока нет треков",
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
                            onDelete = if (isDevicePlaylist) null else { { viewModel.removeTrack(track.id) } },
                            onPlayNext = { playerViewModel.playNext(track) },
                            onAddToQueue = { playerViewModel.addToQueue(track) },
                            onAddToPlaylist = { addToPlaylistTrack = track }
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

    addToPlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            trackId = track.id,
            track = track,
            onDismiss = { addToPlaylistTrack = null }
        )
    }
}