package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.data.model.Artist
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.components.TrackItem
import com.vktracks.player.ui.theme.AppleRed

enum class SearchTab(val title: String) {
    TRACKS("Треки"),
    PLAYLISTS("Плейлисты"),
    ALBUMS("Альбомы"),
    ARTISTS("Исполнители")
}

@Composable
fun SearchScreen(
    searchQuery: String = "",
    searchResults: List<Track>,
    searchPlaylists: List<Playlist> = emptyList(),
    searchArtists: List<Artist> = emptyList(),
    isSearching: Boolean,
    currentTrack: Track?,
    isPlaying: Boolean,
    likedTrackIds: Set<String> = emptySet(),
    onSearch: (String) -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlaylistClick: (Playlist) -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    onLikeClick: (Track) -> Unit = {},
    onDownloadClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember(searchQuery) { mutableStateOf(searchQuery) }
    var selectedTab by remember { mutableStateOf(SearchTab.TRACKS) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            selectedTab = SearchTab.TRACKS
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val popularGenres = listOf(
        "Хип-хоп", "Поп", "Рок", "Электроника", "Танцевальная", "Инди", "Фонк", "R&B"
    )

    val foundPlaylists = remember(searchPlaylists) { searchPlaylists.filter { !it.isAlbum } }
    val foundAlbums = remember(searchPlaylists) { searchPlaylists.filter { it.isAlbum } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item {
            Text(
                text = "Поиск",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // Поисковая строка Apple Music style с авто-фокусом
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearch(it)
                },
                placeholder = { Text("Артисты, треки, альбомы...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Поиск")
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            onSearch("")
                        }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .focusRequester(focusRequester)
            )

            // Табы результатов поиска (Треки, Плейлисты, Альбомы, Исполнители)
            if (query.isNotBlank()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    edgePadding = 20.dp,
                    divider = {},
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    SearchTab.values().forEach { tab ->
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
        }

        if (isSearching) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleRed)
                }
            }
        } else if (query.isNotBlank()) {
            when (selectedTab) {
                SearchTab.TRACKS -> {
                    if (searchResults.isEmpty()) {
                        item { EmptySearchMessage("Треки не найдены") }
                    } else {
                        items(searchResults) { track ->
                            TrackItem(
                                track = track,
                                isPlaying = isPlaying,
                                isCurrent = currentTrack?.fullId == track.fullId,
                                onTrackClick = { onTrackClick(track, searchResults) },
                                isLiked = likedTrackIds.contains(track.fullId) || track.isLiked,
                                onLikeClick = { onLikeClick(track) },
                                onDownloadClick = { onDownloadClick(track) }
                            )
                        }
                    }
                }
                SearchTab.PLAYLISTS -> {
                    if (foundPlaylists.isEmpty()) {
                        item { EmptySearchMessage("Плейлисты не найдены") }
                    } else {
                        items(foundPlaylists) { playlist ->
                            SearchPlaylistItem(
                                playlist = playlist,
                                subtitle = "${playlist.count} треков",
                                onClick = { onPlaylistClick(playlist) }
                            )
                        }
                    }
                }
                SearchTab.ALBUMS -> {
                    if (foundAlbums.isEmpty()) {
                        item { EmptySearchMessage("Альбомы не найдены") }
                    } else {
                        items(foundAlbums) { album ->
                            val desc = buildString {
                                if (!album.mainArtist.isNullOrBlank()) append("${album.mainArtist} • ")
                                if (album.year != null && album.year > 0) append("${album.year} • ")
                                append("${album.count} треков")
                            }
                            SearchPlaylistItem(
                                playlist = album,
                                subtitle = desc,
                                onClick = { onPlaylistClick(album) }
                            )
                        }
                    }
                }
                SearchTab.ARTISTS -> {
                    if (searchArtists.isEmpty()) {
                        item { EmptySearchMessage("Исполнители не найдены") }
                    } else {
                        items(searchArtists) { artist ->
                            ListItem(
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!artist.photoUrl.isNullOrEmpty()) {
                                            AsyncImage(
                                                model = artist.photoUrl,
                                                contentDescription = artist.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                },
                                headlineContent = {
                                    Text(
                                        text = artist.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = { Text("Исполнитель") },
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onArtistClick(artist)
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Подсказки / Популярные жанры
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = "Популярные жанры",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val rows = popularGenres.chunked(2)
                    for (row in rows) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (genre in row) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            query = genre
                                            onSearch(genre)
                                        }
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = genre,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySearchMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Попробуйте изменить поисковый запрос или выбрать другой таб.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SearchPlaylistItem(
    playlist: Playlist,
    subtitle: String,
    onClick: () -> Unit
) {
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
