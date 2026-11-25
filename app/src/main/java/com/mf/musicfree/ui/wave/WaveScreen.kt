package com.mf.musicfree.ui.wave

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.player.PlayerViewModel


@Composable
fun WaveScreen(
    playerViewModel: PlayerViewModel
) {
    val isTasteWaveActive by playerViewModel.isTasteWaveActive.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val queue by playerViewModel.queue.collectAsState()

    val isRadioBuffering by playerViewModel.isRadioBuffering.collectAsState()
    val isWavePlaying = isTasteWaveActive && isPlaying
    val currentIndex = queue.indexOfFirst { it.id == currentTrack?.id }
    val canGoPrevious = currentIndex > 0

    val isPlayerBuffering by playerViewModel.isBuffering.collectAsState()
    val isBuffering = isRadioBuffering || (isTasteWaveActive && isPlayerBuffering)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        WavePlasmaBackground(
            isWavePlaying = isWavePlaying,
            isWaveActive = isTasteWaveActive,
            spectrumProvider = { playerViewModel.getAudioSpectrum() }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Text(
                text = "Моя волна",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    isBuffering -> "Подбираем треки по вашему вкусу..."
                    isTasteWaveActive && currentTrack != null ->
                        "${currentTrack!!.title} — ${currentTrack!!.artist}"
                    else -> "Бесконечный поток музыки на основе вашей библиотеки"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (isTasteWaveActive) {
                    FilledTonalIconButton(
                        onClick = { playerViewModel.previous() },
                        enabled = canGoPrevious && !isBuffering,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Предыдущий трек"
                        )
                    }
                }

                FilledIconButton(
                    onClick = { playerViewModel.startTasteWave() },
                    enabled = !isBuffering,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isWavePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isWavePlaying) "Пауза" else "Запустить волну",
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }

                if (isTasteWaveActive) {
                    FilledTonalIconButton(
                        onClick = { playerViewModel.next() },
                        enabled = !isBuffering,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Следующий трек"
                        )
                    }
                }
            }
        }
    }
}