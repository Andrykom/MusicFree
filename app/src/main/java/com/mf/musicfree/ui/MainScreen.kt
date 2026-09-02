package com.mf.musicfree.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val playerViewModel = koinViewModel<PlayerViewModel>()
    val items = listOf(BottomNavItem.Library, BottomNavItem.Search, BottomNavItem.Settings)

    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // На вкладках — показываем pager ("pager"), на остальных — нет
    val isOnPager = currentRoute == "pager"
    val showBottomBar = currentRoute == "pager"

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    MiniPlayer(
                        viewModel = playerViewModel,
                        onExpand = { navController.navigate("player") }
                    )
                    NavigationBar {
                        items.forEachIndexed { index, item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.title) },
                                label = { Text(item.title) },
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    if (isOnPager) {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    } else {
                                        navController.navigate("pager") {
                                            popUpTo("pager") { inclusive = false }
                                        }
                                        scope.launch { pagerState.animateScrollToPage(index) }
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
            startDestination = "pager",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("pager") {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> LibraryScreen(
                            playerViewModel = playerViewModel,
                            onNavigate = { navController.navigate(it) }
                        )
                        1 -> SearchScreen(
                            playerViewModel = playerViewModel,
                            onNavigate = { navController.navigate(it) }
                        )
                        2 -> SettingsScreen()
                    }
                }
            }
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
