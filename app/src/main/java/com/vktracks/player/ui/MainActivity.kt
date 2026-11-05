package com.vktracks.player.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vktracks.player.BuildConfig
import com.vktracks.player.data.model.AppUpdateInfo
import com.vktracks.player.service.PlaybackService
import com.vktracks.player.ui.components.AuthDialog
import com.vktracks.player.ui.components.FullPlayerSheet
import com.vktracks.player.ui.components.MiniPlayer
import com.vktracks.player.ui.components.PlaylistDetailSheet
import com.vktracks.player.ui.screens.BrowseScreen
import com.vktracks.player.ui.screens.LibraryScreen
import com.vktracks.player.ui.screens.ListenNowScreen
import com.vktracks.player.ui.screens.SearchScreen
import com.vktracks.player.ui.screens.SettingsScreen
import com.vktracks.player.ui.theme.VKTracksTheme

enum class MainTab(val title: String, val icon: ImageVector) {
    LISTEN_NOW("Слушать", Icons.Default.PlayCircleOutline),
    BROWSE("Обзор", Icons.Default.Dashboard),
    LIBRARY("Медиатека", Icons.Default.LibraryMusic),
    SEARCH("Поиск", Icons.Default.Search)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Запуск сервиса воспроизведения
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)

        setContent {
            VKTracksTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(MainTab.LISTEN_NOW) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosMs by viewModel.currentPosMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isShuffle by viewModel.isShuffle.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val lyrics by viewModel.currentLyrics.collectAsState()
    val isAuthorized by viewModel.isAuthorized.collectAsState()

    val radioTracks by viewModel.radioTracks.collectAsState()
    val myTracks by viewModel.myTracks.collectAsState()
    val historyTracks by viewModel.historyTracks.collectAsState()
    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val curatedPlaylists by viewModel.curatedPlaylists.collectAsState()
    val chartTracks by viewModel.chartTracks.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searchPlaylists by viewModel.searchPlaylists.collectAsState()
    val searchArtists by viewModel.searchArtists.collectAsState()
    val likedTrackIds by viewModel.likedTrackIds.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val selectedPlaylistTracks by viewModel.selectedPlaylistTracks.collectAsState()
    val isLoadingPlaylistTracks by viewModel.isLoadingPlaylistTracks.collectAsState()

    val availableUpdate by viewModel.availableUpdate.collectAsState()
    val updateProgress by viewModel.updateProgress.collectAsState()

    // Обработка кнопки назад (сворачивание плеера, настроек и открытого плейлиста)
    BackHandler(enabled = showFullPlayer || showSettings || selectedPlaylist != null) {
        if (showFullPlayer) {
            showFullPlayer = false
        } else if (showSettings) {
            showSettings = false
        } else if (selectedPlaylist != null) {
            viewModel.closePlaylist()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Мини-плеер над таб-баром
                    if (currentTrack != null && !showFullPlayer) {
                        MiniPlayer(
                            track = currentTrack,
                            isPlaying = isPlaying,
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onNextClick = { viewModel.playNext() },
                            onClick = { showFullPlayer = true }
                        )
                    }

                    // Таб-бар Apple Music style
                    NavigationBar(
                        tonalElevation = 8.dp,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        MainTab.values().forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                                label = { Text(tab.title) }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    MainTab.LISTEN_NOW -> {
                        ListenNowScreen(
                            radioTracks = radioTracks,
                            historyTracks = historyTracks,
                            recommendations = recommendations,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            likedTrackIds = likedTrackIds,
                            onTrackClick = { track, list -> viewModel.playTrack(track, list) },
                            onRadioClick = {
                                if (radioTracks.isNotEmpty()) {
                                    viewModel.playTrack(radioTracks.first(), radioTracks)
                                } else {
                                    viewModel.loadPersonalRadio()
                                }
                            },
                            onLikeClick = { viewModel.toggleTrackLike(it) },
                            onDownloadClick = { viewModel.downloadTrack(it) },
                            onSettingsClick = { showSettings = true }
                        )
                    }
                    MainTab.BROWSE -> {
                        BrowseScreen(
                            curatedPlaylists = curatedPlaylists,
                            userPlaylists = playlists,
                            chartTracks = chartTracks,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            likedTrackIds = likedTrackIds,
                            onTrackClick = { track, list -> viewModel.playTrack(track, list) },
                            onPlaylistClick = { playlist -> viewModel.openPlaylist(playlist) },
                            onLikeClick = { viewModel.toggleTrackLike(it) },
                            onDownloadClick = { viewModel.downloadTrack(it) }
                        )
                    }
                    MainTab.LIBRARY -> {
                        LibraryScreen(
                            myTracks = myTracks,
                            downloadedTracks = downloadedTracks,
                            historyTracks = historyTracks,
                            playlists = playlists,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            likedTrackIds = likedTrackIds,
                            onTrackClick = { track, list -> viewModel.playTrack(track, list) },
                            onPlaylistClick = { playlist -> viewModel.openPlaylist(playlist) },
                            onLikeClick = { viewModel.toggleTrackLike(it) },
                            onDownloadClick = { track, subfolder -> viewModel.downloadTrack(track, subfolder) },
                            onLoginClick = { showAuthDialog = true },
                            onSettingsClick = { showSettings = true },
                            isAuthorized = isAuthorized
                        )
                    }
                    MainTab.SEARCH -> {
                        SearchScreen(
                            searchResults = searchResults,
                            searchPlaylists = searchPlaylists,
                            searchArtists = searchArtists,
                            isSearching = isSearching,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            likedTrackIds = likedTrackIds,
                            onSearch = { viewModel.search(it) },
                            onTrackClick = { track, list -> viewModel.playTrack(track, list) },
                            onPlaylistClick = { playlist -> viewModel.openPlaylist(playlist) },
                            onLikeClick = { viewModel.toggleTrackLike(it) },
                            onDownloadClick = { viewModel.downloadTrack(it) }
                        )
                    }
                }
            }
        }

        // Полноэкранный плеер в стиле Apple Music
        AnimatedVisibility(
            visible = showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val isCurrentLiked = currentTrack?.let {
                likedTrackIds.contains(it.fullId) || it.isLiked
            } ?: false

            FullPlayerSheet(
                track = currentTrack,
                isPlaying = isPlaying,
                currentPosMs = currentPosMs,
                durationMs = durationMs,
                isShuffle = isShuffle,
                repeatMode = repeatMode,
                lyrics = lyrics,
                isLiked = isCurrentLiked,
                onLikeClick = { currentTrack?.let { viewModel.toggleTrackLike(it) } },
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onNextClick = { viewModel.playNext() },
                onPrevClick = { viewModel.playPrevious() },
                onSeekTo = { viewModel.seekTo(it) },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onToggleRepeat = { viewModel.toggleRepeat() },
                onDownloadClick = { currentTrack?.let { viewModel.downloadTrack(it) } },
                onDismiss = { showFullPlayer = false }
            )
        }

        // Модальный экран плейлиста в стиле Apple Music
        selectedPlaylist?.let { playlist ->
            PlaylistDetailSheet(
                playlist = playlist,
                tracks = selectedPlaylistTracks,
                isLoading = isLoadingPlaylistTracks,
                isFollowed = false,
                likedTrackIds = likedTrackIds,
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                onTrackClick = { track, list -> viewModel.playTrack(track, list) },
                onPlayAllClick = { list -> if (list.isNotEmpty()) viewModel.playTrack(list.first(), list) },
                onShuffleClick = { list ->
                    if (list.isNotEmpty()) {
                        val shuffled = list.shuffled()
                        viewModel.playTrack(shuffled.first(), shuffled)
                    }
                },
                onDownloadAllClick = { list -> viewModel.downloadAllInPlaylist(list, playlist.title) },
                onDownloadTrackClick = { viewModel.downloadTrack(it, playlist.title) },
                onToggleFollow = { viewModel.togglePlaylistFollow(it) },
                onToggleLike = { viewModel.toggleTrackLike(it) },
                onMiniPlayerPlayPause = { viewModel.togglePlayPause() },
                onMiniPlayerNext = { viewModel.playNext() },
                onMiniPlayerClick = { showFullPlayer = true },
                onDismiss = { viewModel.closePlaylist() }
            )
        }

        // Диалог авторизации ВКонтакте
        if (showAuthDialog) {
            AuthDialog(
                authManager = viewModel.authManager,
                apiClient = viewModel.apiClient,
                onDismiss = { showAuthDialog = false },
                onSuccess = {
                    showAuthDialog = false
                    viewModel.onAuthSuccess()
                }
            )
        }

        // Экран настроек
        val context = LocalContext.current
        if (showSettings) {
            SettingsScreen(
                authManager = viewModel.authManager,
                downloader = viewModel.downloader,
                onLoginClick = {
                    showSettings = false
                    showAuthDialog = true
                },
                onCheckUpdateClick = {
                    viewModel.checkForUpdates(isManual = true) {
                        Toast.makeText(
                            context,
                            "У вас установлена последняя версия (v${BuildConfig.VERSION_NAME})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onDismiss = { showSettings = false }
            )
        }

        // Диалог автообновления приложения
        availableUpdate?.let { update ->
            UpdateDialog(
                update = update,
                progress = updateProgress,
                onInstall = { viewModel.installUpdate(update) },
                onDismiss = { viewModel.dismissUpdate() }
            )
        }
    }
}

@Composable
fun UpdateDialog(
    update: AppUpdateInfo,
    progress: Int,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доступно обновление v${update.versionName}") },
        text = {
            Column {
                Text(update.changelog)
                if (progress >= 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Загрузка: $progress%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onInstall,
                enabled = progress < 0
            ) {
                Text(if (progress >= 0) "Скачивание..." else "Обновить")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = progress < 0
            ) {
                Text("Позже")
            }
        }
    )
}
