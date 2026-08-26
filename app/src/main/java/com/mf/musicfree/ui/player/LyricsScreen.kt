package com.mf.musicfree.ui.player

import androidx.compose.foundation.background
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
    val lyrics by viewModel.currentLyrics.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isLoading by viewModel.isLyricsLoading.collectAsState()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 1. Делаем локальный снимок стейта, чтобы он не поменялся прямо во время отрисовки
    val safeLyrics = lyrics

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

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            safeLyrics == null -> { // Проверяем локальную переменную
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
            else -> {
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
                        items = safeLyrics.lines,
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
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
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