package com.mf.musicfree.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun LyricsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val lyricsState by viewModel.lyricsState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Текущий текст доступен только в состоянии Success
    val safeLyrics = (lyricsState as? PlayerViewModel.LyricsUiState.Success)?.lyrics

    val currentLineIndex by remember(safeLyrics, currentPosition) {
        derivedStateOf {
            safeLyrics?.lines?.indexOfLast { it.timeMs <= currentPosition } ?: -1
        }
    }

    LaunchedEffect(currentLineIndex) {
        val targetIndex = if (currentLineIndex >= 0) currentLineIndex else 0

        // 2. Используем локальный снимок вместо !!
        if (safeLyrics != null && safeLyrics.lines.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(index = targetIndex)
            }
        }
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Текст не найден",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                // В этой ветке текст гарантированно есть — берём из state, а не из nullable safeLyrics
                val lyrics = state.lyrics
                if (lyrics.isSynced) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 350.dp,
                            bottom = 350.dp,
                            start = 24.dp,
                            end = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        itemsIndexed(
                            // 3. Безопасное обращение к списку без оператора !!
                            items = lyrics.lines,
                            key = { index, _ -> "line_$index" }
                        ) { index, line ->
                            val activeIndex = if (currentLineIndex >= 0) currentLineIndex else 0
                            val distance = abs(index - activeIndex)
                            val isCurrent = (index == currentLineIndex)

                            val alpha = when {
                                isCurrent -> 1f
                                distance == 1 -> 0.6f
                                distance == 2 -> 0.3f
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
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    // Статичный текст (Genius) — обычный скролл без подсветки
                    LazyColumn(
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