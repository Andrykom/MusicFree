package com.mf.musicfree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
import com.mf.musicfree.ui.search.SearchViewModel
import com.mf.musicfree.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val playerViewModel = koinViewModel<PlayerViewModel>()
    val searchViewModel = koinViewModel<SearchViewModel>()
    val items = listOf(BottomNavItem.Library, BottomNavItem.Search, BottomNavItem.Settings)

    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    var showPlayer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    BackHandler(enabled = showLyrics) {
        showLyrics = false
        showPlayer = true
    }
    BackHandler(enabled = showPlayer && !showLyrics) {
        showPlayer = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = !showPlayer && !showLyrics,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
                ) {
                    Column {
                        MiniPlayer(
                            viewModel = playerViewModel,
                            onExpand = { showPlayer = true }
                        )
                        NavigationBar {
                            items.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    icon = { Icon(item.icon, contentDescription = item.title) },
                                    label = { Text(item.title) },
                                    selected = pagerState.currentPage == index,
                                    onClick = {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    modifier = Modifier.fillMaxSize(),
                    key = { it }
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

                // NavHost только для детальных экранов (плейлисты) — прозрачный фон, чтобы пейджер был виден
                NavHost(
                    navController = navController,
                    startDestination = "empty",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable("empty") { Box(Modifier.fillMaxSize()) }
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

        // FullPlayer — оверлей поверх всего (включая bottomBar)
        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                FullPlayerScreen(
                    viewModel = playerViewModel,
                    onBack = { showPlayer = false },
                    onLyrics = {
                        showPlayer = false
                        showLyrics = true
                    },
                    onArtistClick = { artist ->
                        showPlayer = false
                        scope.launch {
                            pagerState.animateScrollToPage(1)
                            searchViewModel.onQueryChange(artist)
                            searchViewModel.search()
                        }
                    }
                )
            }
        }

        // Lyrics — поверх плеера
        AnimatedVisibility(
            visible = showLyrics,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                LyricsScreen(
                    viewModel = playerViewModel,
                    onBack = {
                        showLyrics = false
                        showPlayer = true
                    }
                )
            }
        }
    }
}
