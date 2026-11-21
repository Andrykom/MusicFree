package com.mf.musicfree.ui.player

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.model.LyricsLine
import com.mf.musicfree.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private fun findActiveLineIndex(lines: List<LyricsLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val adjustedPosition = positionMs + 350L
    if (adjustedPosition < lines.first().timeMs) return -1

    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= adjustedPosition) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun LyricsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()

    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }
    var lastTrackId by remember { mutableStateOf(currentTrack?.id) }

    val density = LocalDensity.current
    val defaultHalfItemPx = with(density) { 24.dp.roundToPx() }

    // Реагируем ТОЛЬКО на реальное перетаскивание пальцем (не на программный animateScrollToItem)
    LaunchedEffect(isDragged) {
        if (isDragged) {
            userScrolling = true
        }
    }

    // Когда палец отпущен и инерция скролла остановилась — ждём 3 секунды и возвращаем автопрокрутку
    LaunchedEffect(isDragged, listState.isScrollInProgress, userScrolling) {
        if (userScrolling && !isDragged && !listState.isScrollInProgress) {
            delay(3000L)
            userScrolling = false
        }
    }

    // При смене трека сразу снимаем паузу ручного скролла
    LaunchedEffect(currentTrack?.id) {
        userScrolling = false
    }

    val safeLyrics = (lyricsState as? PlayerViewModel.LyricsUiState.Success)?.lyrics

    val currentLineIndex by remember(safeLyrics, currentPosition) {
        derivedStateOf {
            safeLyrics?.lines?.let { findActiveLineIndex(it, currentPosition) } ?: -1
        }
    }

    // Автопрокрутка к активной строке + быстрая плавная прокрутка наверх при старте нового трека
    LaunchedEffect(safeLyrics, currentLineIndex, userScrolling) {
        if (userScrolling || safeLyrics == null || !safeLyrics.isSynced || safeLyrics.lines.isEmpty()) {
            return@LaunchedEffect
        }
        val targetIndex = if (currentLineIndex >= 0) currentLineIndex else 0
        val isNewTrack = currentTrack?.id != lastTrackId

        if (isNewTrack) {
            lastTrackId = currentTrack?.id
            // Если предыдущая песня закончилась далеко внизу, сокращаем дистанцию до 15 строк,
            // чтобы анимация отката наверх была быстрой и эффектной
            if (listState.firstVisibleItemIndex > 15 && targetIndex == 0) {
                listState.scrollToItem(15)
            }
        }

        val visibleTarget = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val itemHalfHeightPx = visibleTarget?.size?.div(2) ?: defaultHalfItemPx

        listState.animateScrollToItem(
            index = targetIndex,
            scrollOffset = itemHalfHeightPx
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (drag > 120) onBack()
                        drag = 0f
                    },
                    onHorizontalDrag = { _, amount -> drag += amount }
                )
            }
    ) {
        when (val state = lyricsState) {
            is PlayerViewModel.LyricsUiState.Loading,
            is PlayerViewModel.LyricsUiState.Idle -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is PlayerViewModel.LyricsUiState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Текст не найден",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is PlayerViewModel.LyricsUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.retryLyrics() }) {
                            Text("Повторить")
                        }
                    }
                }
            }
            is PlayerViewModel.LyricsUiState.Success -> {
                val lyrics = state.lyrics
                if (lyrics.isSynced) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val halfHeight = maxHeight / 2
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = halfHeight,
                                bottom = halfHeight,
                                start = 24.dp,
                                end = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            itemsIndexed(
                                items = lyrics.lines,
                                key = { index, _ -> "line_$index" }
                            ) { index, line ->
                                val activeIndex = if (currentLineIndex >= 0) currentLineIndex else 0
                                val distance = abs(index - activeIndex)
                                val isCurrent = (index == currentLineIndex)

                                val alpha = when {
                                    isCurrent -> 1f
                                    distance == 1 -> 0.65f
                                    distance == 2 -> 0.35f
                                    else -> 0.15f
                                }

                                val style = when {
                                    isCurrent -> MaterialTheme.typography.headlineMedium
                                    distance <= 1 -> MaterialTheme.typography.titleLarge
                                    else -> MaterialTheme.typography.titleMedium
                                }

                                Text(
                                    text = line.text,
                                    style = style,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            userScrolling = false
                                            viewModel.seekToPosition(line.timeMs)
                                        }
                                        .padding(vertical = 6.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                } else {
                    val plainListState = rememberLazyListState()
                    LaunchedEffect(currentTrack?.id) {
                        plainListState.animateScrollToItem(0)
                    }
                    LazyColumn(
                        state = plainListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 80.dp,
                            bottom = 80.dp,
                            start = 24.dp,
                            end = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(
                            items = lyrics.lines,
                            key = { index, _ -> "plain_$index" }
                        ) { _, line ->
                            if (line.text.isNotBlank()) {
                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 12.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = CircleShape
                )
                .align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}