package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    likedTrackIds: Set<String> = emptySet(),
    downloadedTrackIds: Set<String> = emptySet(),
    isLoadingMoreTracks: Boolean = false,
    onLoadMoreTracks: () -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onLikeClick: (Track) -> Unit = {},
    onDownloadClick: (Track, String?) -> Unit,
    onLoginClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isAuthorized: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.FAVORITES) }

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

    LaunchedEffect(shouldLoadMore, selectedTab) {
        if (shouldLoadMore && selectedTab == LibraryTab.FAVORITES) {
            onLoadMoreTracks()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
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
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Переключатель разделов (Избранное, Плейлисты, Альбомы, Скачанное)
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 20.dp,
                divider = {},
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                LibraryTab.values().forEach { tab ->
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
                        items(myTracks, key = { it.fullId }) { track ->
                            TrackItem(
                                track = track,
                                isCurrent = currentTrack?.fullId == track.fullId,
                                onTrackClick = { onTrackClick(track, myTracks) },
                                isLiked = likedTrackIds.contains(track.fullId) || track.isLiked,
                                isDownloaded = downloadedTrackIds.contains(track.fullId) || track.isLocal,
                                onLikeClick = { onLikeClick(track) },
                                onDownloadClick = { onDownloadClick(track, "Favorites") }
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
                        items(downloadedTracks, key = { it.fullId }) { track ->
                            TrackItem(
                                track = track,
                                isCurrent = currentTrack?.fullId == track.fullId,
                                onTrackClick = { onTrackClick(track, downloadedTracks) },
                                isLiked = likedTrackIds.contains(track.fullId) || track.isLiked,
                                isDownloaded = true,
                                onLikeClick = { onLikeClick(track) },
                                onDownloadClick = { onDownloadClick(track, null) }
                            )
                        }
                    }
                }
            }
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
