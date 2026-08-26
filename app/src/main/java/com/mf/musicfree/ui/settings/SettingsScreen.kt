package com.mf.musicfree.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        ListItem(
            headlineContent = { Text("Версия") },
            supportingContent = { Text("MF v1.0") }
        )

        HorizontalDivider()

        ListItem(
            headlineContent = { Text("О приложении") },
            supportingContent = { Text("Music Free — open source плеер") }
        )
    }
}