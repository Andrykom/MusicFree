package com.mf.musicfree.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomNavItem(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Library : BottomNavItem("library", "Моя музыка", Icons.Default.Home)
    data object Search : BottomNavItem("search", "Поиск", Icons.Default.Search)
    data object Settings : BottomNavItem("settings", "Настройки", Icons.Default.Settings)
}