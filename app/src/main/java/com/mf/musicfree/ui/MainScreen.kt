package com.mf.musicfree.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.library.LibraryScreen
import com.mf.musicfree.ui.library.PlaylistDetailScreen
import com.mf.musicfree.ui.navigation.BottomNavItem
import com.mf.musicfree.ui.player.FullPlayerScreen
import com.mf.musicfree.ui.player.LyricsScreen
import com.mf.musicfree.ui.player.MiniPlayer
import com.mf.musicfree.ui.search.SearchScreen
import com.mf.musicfree.ui.settings.SettingsScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val playerViewModel = koinViewModel<PlayerViewModel>()
    val items = listOf(BottomNavItem.Library, BottomNavItem.Search, BottomNavItem.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val hideBottomBarRoutes = setOf("player", "lyrics")
    val showBottomBar = currentRoute != null
            && currentRoute !in hideBottomBarRoutes
            && !currentRoute.startsWith("playlist_detail")

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    MiniPlayer(
                        viewModel = playerViewModel,
                        onExpand = { navController.navigate("player") }
                    )
                    NavigationBar {
                        items.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.title) },
                                label = { Text(item.title) },
                                selected = currentRoute == item.route,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Library.route) {
                LibraryScreen(
                    playerViewModel = playerViewModel,
                    onNavigate = { navController.navigate(it) }
                )
            }
            composable(BottomNavItem.Search.route) {
                SearchScreen(
                    playerViewModel = playerViewModel,
                    onNavigate = { navController.navigate(it) }
                )
            }
            composable(BottomNavItem.Settings.route) { SettingsScreen() }
            composable("player") {
                FullPlayerScreen(
                    viewModel = playerViewModel,
                    onBack = { navController.popBackStack() },
                    onLyrics = { navController.navigate("lyrics") }
                )
            }
            composable("lyrics") {
                LyricsScreen(
                    viewModel = playerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "playlist_detail/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId") ?: return@composable
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    playerViewModel = playerViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}