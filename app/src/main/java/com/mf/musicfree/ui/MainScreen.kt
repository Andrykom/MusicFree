package com.mf.musicfree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mf.musicfree.data.repository.CatalogRepository
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.ui.catalog.AlbumDetailScreen
import com.mf.musicfree.ui.catalog.ArtistDetailScreen
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
import org.koin.compose.koinInject
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.mf.musicfree.ui.wave.WaveScreen

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val playerViewModel = koinViewModel<PlayerViewModel>()
    val catalogRepository = koinInject<CatalogRepository>()
    val items = listOf(BottomNavItem.Library, BottomNavItem.Wave, BottomNavItem.Search)

    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    var showPlayer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        playerViewModel.playbackErrorEvent.collect { errorMessage ->
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Short
            )
        }
    }

    BackHandler(enabled = showLyrics) {
        showLyrics = false
        showPlayer = true
    }
    BackHandler(enabled = showPlayer && !showLyrics) {
        showPlayer = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        CompactBottomBar(
                            items = items,
                            selectedIndex = pagerState.currentPage,
                            onSelect = { index ->
                                navController.popBackStack("empty", inclusive = false)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            }
                        )
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
                        1 -> WaveScreen(playerViewModel = playerViewModel)
                        2 -> SearchScreen(
                            playerViewModel = playerViewModel,
                            onNavigate = { navController.navigate(it) }
                        )
                    }
                }

                // NavHost только для детальных экранов (плейлисты, исполнители, альбомы) — прозрачный фон, чтобы пейджер был виден
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
                    composable(
                        route = "artist_detail/{artistKey}",
                        arguments = listOf(navArgument("artistKey") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val artistKey = backStackEntry.arguments?.getString("artistKey") ?: return@composable
                        ArtistDetailScreen(
                            artistKey = artistKey,
                            playerViewModel = playerViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenAlbum = { albumKey -> navController.navigate("album_detail/$albumKey") }
                        )
                    }

                    composable("settings") {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                    title = { Text("Настройки") },
                                    navigationIcon = {
                                        IconButton(onClick = { navController.popBackStack() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                                        }
                                    }
                                )
                            }
                        ) { settingsPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(settingsPadding)
                            ) {
                                SettingsScreen()
                            }
                        }
                    }

                    composable(
                        route = "album_detail/{albumKey}",
                        arguments = listOf(navArgument("albumKey") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val albumKey = backStackEntry.arguments?.getString("albumKey") ?: return@composable
                        AlbumDetailScreen(
                            albumKey = albumKey,
                            playerViewModel = playerViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenArtist = { artistKey -> navController.navigate("artist_detail/$artistKey") }
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
            Box(modifier = Modifier.fillMaxSize()) {
                FullPlayerScreen(
                    viewModel = playerViewModel,
                    onBack = { showPlayer = false },
                    onLyrics = {
                        showPlayer = false
                        showLyrics = true
                    },
                    onArtistClick = { artist ->
                        showPlayer = false
                        val artistKey = catalogRepository.registerArtistByName(artist.trim())
                        navController.navigate("artist_detail/$artistKey")
                    },
                    onAlbumClick = { artist, albumTitle ->
                        showPlayer = false
                        val albumKey = catalogRepository.registerAlbumByName(artist, albumTitle)
                        navController.navigate("album_detail/$albumKey")
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

@Composable
private fun CompactBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        color = NavigationBarDefaults.containerColor,
        tonalElevation = 3.dp
    ) {
        Column {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val selected = selectedIndex == index
                    val indicatorColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        animationSpec = tween(180),
                        label = "navIndicator"
                    )
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(180),
                        label = "navIconTint"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected,
                                onClick = { onSelect(index) },
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .width(56.dp)
                                .clip(CircleShape)
                                .background(indicatorColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                tint = iconTint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}