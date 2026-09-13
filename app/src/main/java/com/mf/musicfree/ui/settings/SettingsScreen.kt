package com.mf.musicfree.ui.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mf.musicfree.data.importer.ImportProgress
import com.mf.musicfree.data.importer.ImportResult
import com.mf.musicfree.data.importer.MusicImportService
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.data.preferences.SettingsRepository
import com.mf.musicfree.plugin.PluginManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class CryptoWallet(
    val coin: String,
    val network: String,
    val address: String,
    val badgeColor: Color
)

sealed class ImportDialogState {
    object Input : ImportDialogState()
    object Extracting : ImportDialogState()
    data class Transferring(val progress: ImportProgress) : ImportDialogState()
    data class Finished(val result: ImportResult) : ImportDialogState()
}

@Composable
fun SettingsScreen(
    blockedRepo: BlockedArtistsRepository = koinInject(),
    settingsRepo: SettingsRepository = koinInject(),
    pluginManager: PluginManager = koinInject(),
    musicImportService: MusicImportService = koinInject()
) {
    val blocked by blockedRepo.blockedArtists.collectAsState(initial = emptySet())
    val defaultPlugin by settingsRepo.defaultPluginName.collectAsState(initial = null)
    val crossfadeSeconds by settingsRepo.crossfadeSeconds.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var importDialogState by remember { mutableStateOf<ImportDialogState>(ImportDialogState.Input) }
    var importUrl by remember { mutableStateOf("") }
    var trackCountText by remember { mutableStateOf("100") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }

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

        // Поддержать автора
        item {
            ListItem(
                headlineContent = { Text("Поддержать автора") },
                supportingContent = { Text("Помочь развитию проекта криптовалютой") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFE91E63)
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = { showDonateDialog = true }) {
                        Text("Донат")
                    }
                }
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // Импорт из Яндекс Музыки
        item {
            ListItem(
                headlineContent = { Text("Импорт из Яндекс Музыки") },
                supportingContent = { Text("Перенос треков по ссылке прямо в Мою музыку") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = {
                        importUrl = ""
                        trackCountText = "100"
                        errorMessage = null
                        importDialogState = ImportDialogState.Input
                        showImportDialog = true
                    }) {
                        Text("Импорт")
                    }
                }
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            ListItem(
                headlineContent = { Text("Поиск по умолчанию") },
                supportingContent = { Text("Источник музыки, выбираемый при открытии поиска") }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pluginManager.plugins.forEach { plugin ->
                    val isSelected = (defaultPlugin == plugin.name) || (defaultPlugin == null && plugin == pluginManager.plugins.firstOrNull())
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            scope.launch { settingsRepo.setDefaultPluginName(plugin.name) }
                        },
                        label = { Text(plugin.name) }
                    )
                }
            }
        }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            ListItem(
                headlineContent = { Text("Кроссфейд") },
                supportingContent = { Text("Плавное затухание громкости при переключении треков") }
            )
            val crossfadeOptions = listOf(0 to "Выкл", 2 to "2 сек", 4 to "4 сек", 8 to "8 сек")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                crossfadeOptions.forEach { (sec, label) ->
                    FilterChip(
                        selected = crossfadeSeconds == sec,
                        onClick = {
                            scope.launch { settingsRepo.setCrossfadeSeconds(sec) }
                        },
                        label = { Text(label) }
                    )
                }
            }
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

    if (showImportDialog) {
        when (val state = importDialogState) {
            is ImportDialogState.Input -> {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("Импорт в Мою музыку") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "Инструкция:",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "1. Скопируйте ссылку на плейлист в Яндекс Музыке.\n" +
                                        "2. Убедитесь, что плейлист открыт для всех (не приватный).\n" +
                                        "3. Вставьте ссылку и укажите количество треков для переноса.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = importUrl,
                                onValueChange = {
                                    importUrl = it
                                    errorMessage = null
                                },
                                label = { Text("Ссылка на плейлист") },
                                placeholder = { Text("https://music.yandex.ru/playlists/...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = trackCountText,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                        val num = it.toIntOrNull()
                                        if (num == null || num <= 100) {
                                            trackCountText = it
                                            errorMessage = null
                                        }
                                    }
                                },
                                label = { Text("Количество треков (макс. 100)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (!errorMessage.isNullOrBlank()) {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val trimmedUrl = importUrl.trim()
                                if (trimmedUrl.isBlank()) {
                                    errorMessage = "Введите ссылку на плейлист"
                                    return@Button
                                }
                                val count = trackCountText.toIntOrNull()?.coerceIn(1, 100) ?: 100
                                Log.i("MusicImport", "User initiated import: url='$trimmedUrl', maxCount=$count")

                                importJob = scope.launch {
                                    importDialogState = ImportDialogState.Extracting
                                    val extractResult = musicImportService.extractPlaylistTracks(trimmedUrl)
                                    extractResult.fold(
                                        onSuccess = { extracted ->
                                            if (extracted.isEmpty()) {
                                                val msg = "Не удалось получить треки. Убедитесь, что плейлист открыт для всех, или попробуйте другую ссылку."
                                                Log.w("MusicImport", msg)
                                                errorMessage = msg
                                                importDialogState = ImportDialogState.Input
                                            } else {
                                                Log.i("MusicImport", "Proceeding to import ${extracted.size} tracks (limit $count)")
                                                val result = musicImportService.importTracks(
                                                    extractedTracks = extracted,
                                                    maxCount = count,
                                                    onProgress = { progress ->
                                                        importDialogState = ImportDialogState.Transferring(progress)
                                                    }
                                                )
                                                importDialogState = ImportDialogState.Finished(result)
                                            }
                                        },
                                        onFailure = { error ->
                                            Log.e("MusicImport", "Extraction failed: ${error.message}", error)
                                            errorMessage = error.message ?: "Не удалось получить треки. Убедитесь, что плейлист открыт для всех, или попробуйте другую ссылку."
                                            importDialogState = ImportDialogState.Input
                                        }
                                    )
                                }
                            }
                        ) {
                            Text("Начать перенос")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showImportDialog = false }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            is ImportDialogState.Extracting -> {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Чтение плейлиста") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                "Подключение и извлечение треков...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {
                            importJob?.cancel()
                            importDialogState = ImportDialogState.Input
                        }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            is ImportDialogState.Transferring -> {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Перенос в Мою музыку") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = {
                                    if (state.progress.total > 0) {
                                        state.progress.current.toFloat() / state.progress.total.toFloat()
                                    } else 0f
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Обработано: ${state.progress.current} из ${state.progress.total}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Найдено: ${state.progress.foundCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Поиск: ${state.progress.currentTrackTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = {
                            importJob?.cancel()
                            showImportDialog = false
                        }) {
                            Text("Отмена")
                        }
                    }
                )
            }

            is ImportDialogState.Finished -> {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = { Text("Перенос завершен") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Успешно добавлено ${state.result.successCount} из ${state.result.totalProcessed} треков прямо в Мою музыку.")
                            if (state.result.skippedCount > 0) {
                                Text(
                                    "Пропущено: ${state.result.skippedCount} (треки не найдены в доступных источниках Zvuch / TheMP3).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showImportDialog = false }) {
                            Text("Готово")
                        }
                    }
                )
            }
        }
    }

    if (showDonateDialog) {
        DonateDialog(onDismiss = { showDonateDialog = false })
    }
}

@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val wallets = remember {
        listOf(
            CryptoWallet(
                coin = "USDT / TRON",
                network = "TRC-20",
                address = "TGX1C5cGid2QAVxJbeiEbRpn9WafnkAbM3",
                badgeColor = Color(0xFFE53935)
            ),
            CryptoWallet(
                coin = "Solana",
                network = "SOL / SPL",
                address = "2YJRXvBqzy1iuGJ5Da3EUFYvfNTg6AwCKhLPsTxz3mFA",
                badgeColor = Color(0xFF9C27B0)
            ),
            CryptoWallet(
                coin = "BNB Chain",
                network = "BEP-20",
                address = "0xC61f27D05c35BB0d91C590b83FeD09a4448767ba",
                badgeColor = Color(0xFFF57C00)
            ),
            CryptoWallet(
                coin = "Bitcoin",
                network = "BTC",
                address = "bc1qut8n7q9wfadqftetk79qdd2947ez0n6afs7azr",
                badgeColor = Color(0xFFFB8C00)
            ),
            CryptoWallet(
                coin = "Ethereum",
                network = "ERC-20",
                address = "0xC61f27D05c35BB0d91C590b83FeD09a4448767ba",
                badgeColor = Color(0xFF3F51B5)
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = Color(0xFFE91E63),
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "Поддержать автора",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Спасибо за интерес к Music Free! Ваши донаты помогают развивать плеер, поддерживать серверы и выпускать обновления.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Нажмите на кошелек, чтобы скопировать адрес:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )

                wallets.forEach { wallet ->
                    OutlinedCard(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(wallet.address))
                            Toast.makeText(context, "${wallet.coin}: адрес скопирован", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = wallet.coin,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        color = wallet.badgeColor.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = wallet.network,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = wallet.badgeColor,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = wallet.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(wallet.address))
                                    Toast.makeText(context, "${wallet.coin}: адрес скопирован", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Скопировать",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}