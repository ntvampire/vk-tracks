package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.components.TrackCoverThumbnail
import com.vktracks.player.ui.components.TrackItem
import com.vktracks.player.ui.theme.AppleRed

enum class LibraryTab(val title: String) {
    FAVORITES("Избранное"),
    PLAYLISTS("Плейлисты"),
    ALBUMS("Альбомы"),
    DOWNLOADED("Скачанное")
}

@Composable
fun LibraryScreen(
    myTracks: List<Track>,
    downloadedTracks: List<Track>,
    historyTracks: List<Track>,
    playlists: List<Playlist>,
    currentTrack: Track?,
    isPlaying: Boolean,
    resolvedCovers: Map<String, String> = emptyMap(),
    likedTrackIds: Set<String> = emptySet(),
    downloadedTrackIds: Set<String> = emptySet(),
    isLoadingMoreTracks: Boolean = false,
    onLoadMoreTracks: () -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onLikeClick: (Track) -> Unit = {},
    onDownloadClick: (Track, String?) -> Unit,
    onBatchDownloadClick: ((List<Track>) -> Unit)? = null,
    onBatchDeleteDownloadedClick: ((List<Track>) -> Unit)? = null,
    onBatchRemoveFavoritesClick: ((List<Track>) -> Unit)? = null,
    onBatchAddToPlaylistClick: ((Playlist, List<Track>) -> Unit)? = null,
    onLoginClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isAuthorized: Boolean,
    onMoreTrackClick: ((Track) -> Unit)? = null,
    onCreatePlaylistClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.FAVORITES) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(emptySet<String>()) }
    var showPlaylistChooserDialog by remember { mutableStateOf(false) }

    val userPlaylists = remember(playlists) { playlists.filter { !it.isAlbum } }
    val userAlbums = remember(playlists) { playlists.filter { it.isAlbum } }

    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalCount = listState.layoutInfo.totalItemsCount
            totalCount > 10 && lastVisibleIndex >= totalCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore, selectedTab, isLoadingMoreTracks) {
        if (shouldLoadMore && selectedTab == LibraryTab.FAVORITES && !isLoadingMoreTracks) {
            onLoadMoreTracks()
        }
    }

    LaunchedEffect(selectedTab) {
        // При смене вкладки сбрасываем режим множественного выбора
        isSelectionMode = false
        selectedTrackIds = emptySet()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Медиатека",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedTab == LibraryTab.FAVORITES || selectedTab == LibraryTab.DOWNLOADED) {
                            TextButton(
                                onClick = {
                                    isSelectionMode = !isSelectionMode
                                    if (!isSelectionMode) selectedTrackIds = emptySet()
                                }
                            ) {
                                Text(
                                    text = if (isSelectionMode) "Готово" else "Выбрать",
                                    color = AppleRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Настройки",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }

                // Переключатель разделов (Избранное, Плейлисты, Альбомы, Скачанное)
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 20.dp,
                    divider = {},
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    LibraryTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    text = tab.title,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }
            }

        if (!isAuthorized && selectedTab != LibraryTab.DOWNLOADED) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = onLoginClick) {
                        Text(text = "Войти через ВКонтакте")
                    }
                }
            }
        } else {
            when (selectedTab) {
                LibraryTab.FAVORITES -> {
                    if (myTracks.isEmpty()) {
                        item {
                            EmptyLibraryMessage("В избранном пока нет треков")
                        }
                    } else {
                        val currentFullId = currentTrack?.fullId
                        val currentPlayingCover = currentTrack?.coverUrl

                        items(myTracks, key = { it.fullId }) { track ->
                            val isCurrent = currentFullId == track.fullId
                            val itemCover = resolvedCovers[track.fullId] ?: if (isCurrent) currentPlayingCover ?: track.coverUrl else track.coverUrl
                            val isLiked = remember(track.fullId, likedTrackIds, track.isLiked) { likedTrackIds.contains(track.fullId) || track.isLiked }
                            val isDownloaded = remember(track.fullId, downloadedTrackIds, track.isLocal) { downloadedTrackIds.contains(track.fullId) || track.isLocal }

                            TrackItem(
                                track = track,
                                isCurrent = isCurrent,
                                coverUrl = itemCover,
                                onTrackClick = remember(track.fullId) { { onTrackClick(track, myTracks) } },
                                isLiked = isLiked,
                                isDownloaded = isDownloaded,
                                onLikeClick = remember(track.fullId) { { onLikeClick(track) } },
                                onDownloadClick = remember(track.fullId) { { onDownloadClick(track, "Favorites") } },
                                onMoreClick = { onMoreTrackClick?.invoke(track) },
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedTrackIds.contains(track.fullId),
                                onToggleSelect = { selected ->
                                    selectedTrackIds = if (selected) selectedTrackIds + track.fullId else selectedTrackIds - track.fullId
                                },
                                onLongClick = {
                                    isSelectionMode = true
                                    selectedTrackIds = selectedTrackIds + track.fullId
                                }
                            )
                        }
                        if (isLoadingMoreTracks) {
                            item(key = "loading_more_favorites") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = AppleRed,
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
                        }
                    }
                }
                LibraryTab.PLAYLISTS -> {
                    item {
                        Surface(
                            onClick = onCreatePlaylistClick,
                            shape = RoundedCornerShape(12.dp),
                            color = AppleRed.copy(alpha = 0.12f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = AppleRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Создать новый плейлист",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppleRed
                                )
                            }
                        }
                    }
                    if (userPlaylists.isEmpty()) {
                        item {
                            EmptyLibraryMessage("Нет сохранённых плейлистов")
                        }
                    } else {
                        items(userPlaylists, key = { "${it.ownerId}_${it.id}" }) { playlist ->
                            PlaylistListItem(
                                playlist = playlist,
                                subtitle = "${playlist.count} треков",
                                onClick = { onPlaylistClick(playlist) }
                            )
                        }
                    }
                }
                LibraryTab.ALBUMS -> {
                    if (userAlbums.isEmpty()) {
                        item {
                            EmptyLibraryMessage("Нет сохранённых альбомов")
                        }
                    } else {
                        items(userAlbums, key = { "${it.ownerId}_${it.id}" }) { album ->
                            val desc = buildString {
                                if (!album.mainArtist.isNullOrBlank()) append("${album.mainArtist} • ")
                                if (album.year != null && album.year > 0) append("${album.year} • ")
                                append("${album.count} треков")
                            }
                            PlaylistListItem(
                                playlist = album,
                                subtitle = desc,
                                onClick = { onPlaylistClick(album) }
                            )
                        }
                    }
                }
                LibraryTab.DOWNLOADED -> {
                    if (downloadedTracks.isEmpty()) {
                        item {
                            EmptyLibraryMessage("Нет скачанных треков")
                        }
                    } else {
                        val currentFullId = currentTrack?.fullId
                        val currentPlayingCover = currentTrack?.coverUrl

                        items(downloadedTracks, key = { it.fullId }) { track ->
                            val isCurrent = currentFullId == track.fullId
                            val itemCover = resolvedCovers[track.fullId] ?: if (isCurrent) currentPlayingCover ?: track.coverUrl else track.coverUrl
                            val isLiked = remember(track.fullId, likedTrackIds, track.isLiked) { likedTrackIds.contains(track.fullId) || track.isLiked }

                            TrackItem(
                                track = track,
                                isCurrent = isCurrent,
                                coverUrl = itemCover,
                                onTrackClick = remember(track.fullId) { { onTrackClick(track, downloadedTracks) } },
                                isLiked = isLiked,
                                isDownloaded = true,
                                onLikeClick = remember(track.fullId) { { onLikeClick(track) } },
                                onDownloadClick = remember(track.fullId) { { onDownloadClick(track, null) } },
                                onMoreClick = { onMoreTrackClick?.invoke(track) },
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedTrackIds.contains(track.fullId),
                                onToggleSelect = { selected ->
                                    selectedTrackIds = if (selected) selectedTrackIds + track.fullId else selectedTrackIds - track.fullId
                                },
                                onLongClick = {
                                    isSelectionMode = true
                                    selectedTrackIds = selectedTrackIds + track.fullId
                                }
                            )
                        }
                    }
                }
            }
        }
    }

        // Плавающая панель пакетных действий
        if (isSelectionMode) {
            val currentTracksList = if (selectedTab == LibraryTab.FAVORITES) myTracks else downloadedTracks
            val selectedTracks = currentTracksList.filter { selectedTrackIds.contains(it.fullId) }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 76.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Выбрано: ${selectedTrackIds.size}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                selectedTrackIds = if (selectedTrackIds.size == currentTracksList.size) {
                                    emptySet()
                                } else {
                                    currentTracksList.map { it.fullId }.toSet()
                                }
                            }
                        ) {
                            Text(
                                text = if (selectedTrackIds.size == currentTracksList.size) "Снять" else "Все",
                                color = AppleRed,
                                fontSize = 13.sp
                            )
                        }

                        if (selectedTab == LibraryTab.FAVORITES) {
                            IconButton(
                                onClick = {
                                    onBatchDownloadClick?.invoke(selectedTracks)
                                    isSelectionMode = false
                                    selectedTrackIds = emptySet()
                                },
                                enabled = selectedTracks.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Скачать выбранные", tint = AppleRed)
                            }
                        }

                        IconButton(
                            onClick = {
                                showPlaylistChooserDialog = true
                            },
                            enabled = selectedTracks.isNotEmpty() && userPlaylists.isNotEmpty()
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = "В плейлист", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        IconButton(
                            onClick = {
                                if (selectedTab == LibraryTab.FAVORITES) {
                                    onBatchRemoveFavoritesClick?.invoke(selectedTracks)
                                } else {
                                    onBatchDeleteDownloadedClick?.invoke(selectedTracks)
                                }
                                isSelectionMode = false
                                selectedTrackIds = emptySet()
                            },
                            enabled = selectedTracks.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить выбранные", tint = Color(0xFFFF3B30))
                        }
                    }
                }
            }
        }

        // Диалог выбора плейлиста для пакетного добавления
        if (showPlaylistChooserDialog) {
            val currentTracksList = if (selectedTab == LibraryTab.FAVORITES) myTracks else downloadedTracks
            val selectedTracks = currentTracksList.filter { selectedTrackIds.contains(it.fullId) }

            AlertDialog(
                onDismissRequest = { showPlaylistChooserDialog = false },
                title = { Text("Добавить в плейлист", fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(userPlaylists, key = { it.fullId }) { pl ->
                            ListItem(
                                headlineContent = { Text(pl.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.clickable {
                                    onBatchAddToPlaylistClick?.invoke(pl, selectedTracks)
                                    showPlaylistChooserDialog = false
                                    isSelectionMode = false
                                    selectedTrackIds = emptySet()
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPlaylistChooserDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyLibraryMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlaylistListItem(
    playlist: Playlist,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        leadingContent = {
            TrackCoverThumbnail(
                coverUrl = playlist.coverUrl,
                sizeDp = 54.dp,
                iconSizeDp = 28.dp
            )
        },
        headlineContent = {
            Text(
                text = playlist.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
