package com.mf.musicfree.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.components.TrackCover
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
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
    val handleZoneWidthPx = with(density) { 68.dp.toPx() }

    var draggingTrackKey by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grabOffsetInItem by remember { mutableFloatStateOf(0f) }

    // Стабильные ключи без привязки к index (чтобы элемент не пересоздавался при обмене местами)
    val itemKeys = remember(queue) {
        val counts = HashMap<String, Int>(queue.size)
        queue.map { t ->
            val c = counts.getOrDefault(t.id, 0)
            counts[t.id] = c + 1
            if (c == 0) t.id else "${t.id}#dup$c"
        }
    }

    val currentQueue by rememberUpdatedState(queue)
    val currentKeys by rememberUpdatedState(itemKeys)
    val currentPlayingIndex by rememberUpdatedState(currentIndex)

    fun trySwapDraggedItem(currentFingerY: Float) {
        val activeKey = draggingTrackKey ?: return
        val keys = currentKeys
        val q = currentQueue
        val fromIndex = keys.indexOf(activeKey).takeIf { it >= 0 }
            ?: draggingIndex
            ?: return
        if (fromIndex !in q.indices) return

        val draggedTopY = currentFingerY - grabOffsetInItem
        val draggedCenterY = draggedTopY + (itemHeightPx / 2f)

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val targetItem = visibleItems.firstOrNull { item ->
            item.index != fromIndex &&
                    item.index in q.indices &&
                    item.index != currentPlayingIndex &&
                    draggedCenterY.toInt() in item.offset..(item.offset + item.size)
        } ?: return

        val toIndex = targetItem.index
        val anchorIdx = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset

        viewModel.moveQueue(fromIndex, toIndex)
        draggingIndex = toIndex
        // Фиксируем якорь скролла, чтобы LazyColumn не прыгал при замене верхнего элемента
        listState.requestScrollToItem(anchorIdx, anchorOffset)
    }

    // Прокручиваем к играющему треку только один раз при открытии шторки
    LaunchedEffect(Unit) {
        if (queue.isNotEmpty() && currentIndex in queue.indices) {
            listState.scrollToItem(currentIndex)
        }
    }

    // Плавный автоскролл списка при удержании перетаскиваемого трека у верхнего или нижнего края
    LaunchedEffect(draggingTrackKey != null) {
        if (draggingTrackKey == null) return@LaunchedEffect
        val edgeZonePx = with(density) { 64.dp.toPx() }
        val maxScrollSpeedPx = with(density) { 12.dp.toPx() }

        while (isActive && draggingTrackKey != null) {
            val layoutInfo = listState.layoutInfo
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
            if (viewportHeight > 0f) {
                val y = fingerY
                val scrollStep = when {
                    y < edgeZonePx && listState.canScrollBackward -> {
                        val ratio = ((edgeZonePx - y) / edgeZonePx).coerceIn(0.15f, 1f)
                        -maxScrollSpeedPx * ratio
                    }
                    y > viewportHeight - edgeZonePx && listState.canScrollForward -> {
                        val ratio = ((y - (viewportHeight - edgeZonePx)) / edgeZonePx).coerceIn(0.15f, 1f)
                        maxScrollSpeedPx * ratio
                    }
                    else -> 0f
                }

                if (scrollStep != 0f) {
                    val scrolled = listState.scrollBy(scrollStep)
                    if (scrolled != 0f) {
                        trySwapDraggedItem(fingerY)
                    }
                }
            }
            withFrameNanos { }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Очередь пуста", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = draggingTrackKey == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                // Реагируем только если палец лег на правую зону иконки DragHandle
                                if (down.position.x < size.width - handleZoneWidthPx) {
                                    return@awaitEachGesture
                                }

                                val hitItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    down.position.y.toInt() in item.offset..(item.offset + item.size)
                                } ?: return@awaitEachGesture

                                val startIndex = hitItem.index
                                val q = currentQueue
                                val keys = currentKeys
                                if (startIndex !in q.indices || startIndex !in keys.indices || startIndex == currentPlayingIndex) {
                                    return@awaitEachGesture
                                }

                                down.consume()
                                draggingTrackKey = keys[startIndex]
                                draggingIndex = startIndex
                                fingerY = down.position.y
                                grabOffsetInItem = (down.position.y - hitItem.offset)
                                    .coerceIn(0f, hitItem.size.toFloat())

                                try {
                                    val pointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        change.consume()
                                        fingerY = change.position.y.coerceIn(0f, size.height.toFloat())
                                        trySwapDraggedItem(fingerY)
                                    }
                                } finally {
                                    draggingTrackKey = null
                                    draggingIndex = null
                                }
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = queue,
                        key = { index, t -> itemKeys.getOrElse(index) { t.id } }
                    ) { index, track ->
                        val itemKey = itemKeys.getOrElse(index) { track.id }
                        val isCurrent = index == currentIndex
                        val isDragging = draggingTrackKey == itemKey

                        SwipeToDismissBox(
                            modifier = Modifier
                                .zIndex(if (isDragging) 2f else 1f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == index }
                                        translationY = if (info != null) {
                                            (fingerY - grabOffsetInItem) - info.offset.toFloat()
                                        } else {
                                            0f
                                        }
                                        shadowElevation = 8.dp.toPx()
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                },
                            enableDismissFromStartToEnd = false,
                            enableDismissFromEndToStart = !isCurrent && draggingTrackKey == null,
                            state = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (!isCurrent && draggingTrackKey == null && value == SwipeToDismissBoxValue.EndToStart) {
                                        viewModel.removeFromQueue(index)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            ),
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Удалить из очереди",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        ) {
                            ListItem(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = draggingTrackKey == null) {
                                        viewModel.playFromQueue(queue, index)
                                    },
                                headlineContent = {
                                    Text(
                                        track.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        track.artist,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
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
                                            tint = if (isDragging) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = when {
                                        isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}