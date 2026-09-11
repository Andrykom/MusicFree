package com.mf.musicfree.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(
    blockedRepo: BlockedArtistsRepository = koinInject()
) {
    val blocked by blockedRepo.blockedArtists.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Версия") },
                supportingContent = { Text("MF v1.1") }
            )
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text("О приложении") },
                supportingContent = { Text("Music Free — open source плеер") }
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            ListItem(
                headlineContent = { Text("Моя волна — блэклист") },
                supportingContent = { Text("Заблокировано артистов: ${blocked.size}") }
            )
        }

        if (blocked.isEmpty()) {
            item {
                Text(
                    "Нет заблокированных артистов",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            items(blocked.toList().sorted(), key = { it }) { artist ->
                ListItem(
                    headlineContent = { Text(artist) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { blockedRepo.unblock(artist) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Разблокировать")
                        }
                    }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { scope.launch { blockedRepo.clearAll() } },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Очистить всё")
                    }
                }
            }
        }
    }
}