package com.mf.musicfree.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

    val density = LocalDensity.current
    val itemHeightPx = with(density) { 64.dp.toPx() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(queue.size, currentIndex) {
        if (draggingIndex == null && queue.isNotEmpty() && currentIndex in queue.indices) {
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
                        val isDragging = draggingIndex == index

                        SwipeToDismissBox(
                            modifier = Modifier
                                .animateItem()
                                .zIndex(if (isDragging) 2f else 1f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationY = dragDeltaY
                                        shadowElevation = 8.dp.toPx()
                                    }
                                },
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
                                trailingContent = {
                                    if (!isCurrent) {
                                        Icon(
                                            imageVector = Icons.Default.DragHandle,
                                            contentDescription = "Переместить",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .pointerInput(track.id) {
                                                    detectVerticalDragGestures(
                                                        onDragStart = {
                                                            draggingIndex = index
                                                            dragDeltaY = 0f
                                                        },
                                                        onDragEnd = {
                                                            draggingIndex = null
                                                            dragDeltaY = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggingIndex = null
                                                            dragDeltaY = 0f
                                                        },
                                                        onVerticalDrag = { change, dragAmount ->
                                                            change.consume()
                                                            dragDeltaY += dragAmount
                                                            val currentDrag = draggingIndex ?: return@detectVerticalDragGestures
                                                            val targetDown = currentDrag + 1
                                                            val targetUp = currentDrag - 1
                                                            if (dragDeltaY > itemHeightPx * 0.7f && targetDown < queue.size && targetDown != currentIndex) {
                                                                viewModel.moveQueue(currentDrag, targetDown)
                                                                draggingIndex = targetDown
                                                                dragDeltaY -= itemHeightPx
                                                            } else if (dragDeltaY < -itemHeightPx * 0.7f && targetUp >= 0 && targetUp != currentIndex) {
                                                                viewModel.moveQueue(currentDrag, targetUp)
                                                                draggingIndex = targetUp
                                                                dragDeltaY += itemHeightPx
                                                            }
                                                        }
                                                    )
                                                }
                                        )
                                    }
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
