package com.mf.musicfree.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.TrackCover

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun QueueBottomSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val queue by viewModel.queue.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    LaunchedEffect(queue.size, currentIndex) {
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Очередь — ${queue.size} треков",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Очередь пуста", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
                        val isCurrent = index == currentIndex
                        SwipeToDismissBox(
                            modifier = Modifier.animateItem(),
                            state = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart ||
                                        value == SwipeToDismissBoxValue.StartToEnd
                                    ) {
                                        viewModel.removeFromQueue(track)
                                        true
                                    } else false
                                }
                            ),
                            backgroundContent = {
                                Box(modifier = Modifier.fillMaxSize())
                            }
                        ) {
                            ListItem(
                                modifier = Modifier.clickable {
                                    viewModel.playFromQueue(queue, index)
                                },
                                headlineContent = {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                supportingContent = { Text(track.artist, maxLines = 1) },
                                leadingContent = {
                                    TrackCover(
                                        coverUrl = track.coverUrl,
                                        title = track.title,
                                        modifier = Modifier.size(40.dp)
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
