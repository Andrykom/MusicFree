package com.mf.musicfree.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.repository.PlaylistRepository
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.TrackRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AddToPlaylistDialog(
    trackId: String,
    track: Track? = null,
    onDismiss: () -> Unit
) {
    val playlistRepository: PlaylistRepository = koinInject()
    val trackRepository: TrackRepository = koinInject()
    val playlists by playlistRepository.getAllPlaylists()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showCreateField by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            Column {
                if (playlists.isEmpty() && !showCreateField) {
                    Text("Нет плейлистов. Создайте первый!")
                } else if (!showCreateField) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        if (track != null) {
                                            trackRepository.ensureTrackExists(track)
                                        }
                                        playlistRepository.addTrackToPlaylist(playlist.id, trackId)
                                        onDismiss()
                                    }
                                },
                                headlineContent = { Text(playlist.name) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }

                if (showCreateField) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Название плейлиста") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                if (newPlaylistName.isNotBlank()) {
                                    playlistRepository.createPlaylist(newPlaylistName.trim())
                                    delay(200)
                                    val all = playlistRepository.getAllPlaylists().first()
                                    val created = all.firstOrNull { it.name == newPlaylistName.trim() }
                                    created?.let {
                                        if (track != null) {
                                            trackRepository.ensureTrackExists(track)
                                        }
                                        playlistRepository.addTrackToPlaylist(it.id, trackId)
                                    }
                                    onDismiss()
                                }
                            }
                        },
                        enabled = newPlaylistName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Создать и добавить")
                    }
                }
            }
        },
        confirmButton = {
            if (!showCreateField) {
                TextButton(onClick = { showCreateField = true }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Создать плейлист")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}