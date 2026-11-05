package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.vktracks.player.ui.components.TrackItem

enum class LibraryTab(val title: String) {
    MY_TRACKS("Мои аудиозаписи"),
    DOWNLOADED("Скачанные"),
    HISTORY("История"),
    PLAYLISTS("Плейлисты")
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
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onLikeClick: (Track) -> Unit = {},
    onDownloadClick: (Track, String?) -> Unit,
    onLoginClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isAuthorized: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(LibraryTab.MY_TRACKS) }

    LazyColumn(
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

            // Переключатель разделов
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

        if (!isAuthorized && selectedTab == LibraryTab.MY_TRACKS) {
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
            val currentList = when (selectedTab) {
                LibraryTab.MY_TRACKS -> myTracks
                LibraryTab.DOWNLOADED -> downloadedTracks
                LibraryTab.HISTORY -> historyTracks
                LibraryTab.PLAYLISTS -> emptyList()
            }

            if (selectedTab == LibraryTab.PLAYLISTS) {
                if (playlists.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Нет сохранённых плейлистов",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(playlists) { playlist ->
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!playlist.coverUrl.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = playlist.coverUrl,
                                            contentDescription = playlist.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Album,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            },
                            headlineContent = {
                                Text(
                                    text = playlist.title,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = { Text("${playlist.count} треков") },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            },
                            modifier = Modifier.clickable { onPlaylistClick(playlist) }
                        )
                    }
                }
            } else {
                if (currentList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (selectedTab == LibraryTab.DOWNLOADED) "Нет скачанных треков" else "Список пуст",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(currentList) { track ->
                        val downloadSubfolder = if (selectedTab == LibraryTab.MY_TRACKS) "Favorites" else null
                        TrackItem(
                            track = track,
                            isPlaying = isPlaying,
                            isCurrent = currentTrack?.fullId == track.fullId,
                            onTrackClick = { onTrackClick(track, currentList) },
                            isLiked = likedTrackIds.contains(track.fullId) || track.isLiked,
                            onLikeClick = { onLikeClick(track) },
                            onDownloadClick = { onDownloadClick(track, downloadSubfolder) }
                        )
                    }
                }
            }
        }
    }
}
