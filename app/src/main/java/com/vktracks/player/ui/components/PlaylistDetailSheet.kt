package com.vktracks.player.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.toTrackCountString
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailSheet(
    playlist: Playlist,
    tracks: List<Track>,
    isLoading: Boolean,
    isFollowed: Boolean,
    likedTrackIds: Set<String>,
    downloadedTrackIds: Set<String> = emptySet(),
    currentTrack: Track?,
    isPlaying: Boolean,
    resolvedCovers: Map<String, String> = emptyMap(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onPlayAllClick: (List<Track>) -> Unit,
    onShuffleClick: (List<Track>) -> Unit,
    onDownloadAllClick: (List<Track>) -> Unit,
    onDownloadTrackClick: (Track) -> Unit,
    onToggleFollow: (Playlist) -> Unit,
    onToggleLike: (Track) -> Unit,
    onMoreTrackClick: ((Track) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isUserPlaylist: Boolean = false,
    userPlaylists: List<Playlist> = emptyList(),
    playlistError: String? = null,
    onRetryClick: (() -> Unit)? = null,
    onCreateNewPlaylistClick: (() -> Unit)? = null,
    onEditPlaylistClick: ((Playlist, String, String, Uri?) -> Unit)? = null,
    onBatchDownloadClick: ((List<Track>) -> Unit)? = null,
    onBatchAddToPlaylistClick: ((Playlist, List<Track>) -> Unit)? = null,
    onBatchRemoveFromPlaylistClick: ((List<Track>) -> Unit)? = null
) {
    val context = LocalContext.current
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedTrackIds by remember { mutableStateOf(setOf<String>()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showPlaylistChooserDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (showPlaylistChooserDialog) {
            showPlaylistChooserDialog = false
        } else if (showEditDialog) {
            showEditDialog = false
        } else if (isSelectionMode) {
            isSelectionMode = false
            selectedTrackIds = emptySet()
        } else {
            onDismiss()
        }
    }

    if (showEditDialog) {
        EditPlaylistDialog(
            playlist = playlist,
            onDismiss = { showEditDialog = false },
            onSave = { newTitle, newDesc, newCoverUri ->
                showEditDialog = false
                onEditPlaylistClick?.invoke(playlist, newTitle, newDesc, newCoverUri)
            }
        )
    }

    if (showPlaylistChooserDialog) {
        val selectedTracks = tracks.filter { selectedTrackIds.contains(it.fullId) }
        AddToPlaylistDialog(
            playlists = userPlaylists.filter { it.id != playlist.id },
            onDismiss = { showPlaylistChooserDialog = false },
            onSelectPlaylist = { targetPl ->
                showPlaylistChooserDialog = false
                isSelectionMode = false
                selectedTrackIds = emptySet()
                onBatchAddToPlaylistClick?.invoke(targetPl, selectedTracks)
            },
            onCreateNewClick = {
                showPlaylistChooserDialog = false
                onCreateNewPlaylistClick?.invoke()
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Верхняя навигационная панель
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    if (tracks.isNotEmpty() && !playlist.isAlbum) {
                        TextButton(
                            onClick = {
                                isSelectionMode = !isSelectionMode
                                if (!isSelectionMode) selectedTrackIds = emptySet()
                            }
                        ) {
                            Text(
                                text = if (isSelectionMode) "Готово" else "Выбрать",
                                color = AppleRed,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Иконка редактирования плейлиста (название, описание, обложка)
                    if (isUserPlaylist) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Редактировать плейлист",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    // Сердечко плейлиста (добавить/удалить из библиотеки)
                    IconButton(onClick = { onToggleFollow(playlist) }) {
                        Icon(
                            imageVector = if (isFollowed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFollowed) "В медиатеке" else "Добавить в медиатеку",
                            tint = if (isFollowed) AppleRed else MaterialTheme.colorScheme.onBackground
                        )
                    }

                    if (tracks.isNotEmpty()) {
                        IconButton(onClick = { onDownloadAllClick(tracks) }) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Скачать плейлист",
                                tint = AppleRed
                            )
                        }
                    }
                }

            val tracksWithCovers = androidx.compose.runtime.remember(tracks, playlist.coverUrl, playlist.isAlbum) {
                if (playlist.isAlbum && !playlist.coverUrl.isNullOrEmpty()) {
                    tracks.map { if (it.coverUrl.isNullOrEmpty()) it.copy(coverUrl = playlist.coverUrl) else it }
                } else {
                    tracks
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (isSelectionMode) 100.dp else 24.dp)
            ) {
                    // Заголовок плейлиста в стиле Apple Music
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Обложка плейлиста
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .shadow(16.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    modifier = Modifier.size(64.dp)
                                )
                                if (!playlist.coverUrl.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = playlist.coverUrl,
                                        contentDescription = playlist.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Название плейлиста
                            Text(
                                text = playlist.title,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!playlist.description.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = playlist.description,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = (tracks.size.takeIf { it > 0 } ?: playlist.count).toTrackCountString(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppleRed
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Кнопки "Слушать" и "Перемешать"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { if (tracks.isNotEmpty()) onPlayAllClick(tracksWithCovers) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                                    enabled = tracks.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Слушать", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }

                                FilledTonalButton(
                                    onClick = { if (tracks.isNotEmpty()) onShuffleClick(tracksWithCovers) },
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    enabled = tracks.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "Перемешать",
                                        tint = AppleRed,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    if (isLoading) {
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
                    } else if (tracks.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = playlistError ?: "В плейлисте нет треков",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                if (playlistError != null && onRetryClick != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(onClick = onRetryClick) {
                                        Text("Повторить")
                                    }
                                }
                            }
                        }
                    } else {
                        itemsIndexed(tracks, key = { index, track -> "${track.fullId}_$index" }) { index, track ->
                            val isCurrent = currentTrack?.fullId == track.fullId
                            val isLiked = likedTrackIds.contains(track.fullId) || track.isLiked

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedTrackIds = if (selectedTrackIds.contains(track.fullId)) {
                                                    selectedTrackIds - track.fullId
                                                } else {
                                                    selectedTrackIds + track.fullId
                                                }
                                            } else if (track.isRestricted) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Воспроизведение недоступно: ${track.displayRestriction}",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                val trackWithCover = if (playlist.isAlbum && track.coverUrl.isNullOrEmpty() && !playlist.coverUrl.isNullOrEmpty()) {
                                                    track.copy(coverUrl = playlist.coverUrl)
                                                } else {
                                                    track
                                                }
                                                onTrackClick(trackWithCover, tracksWithCovers)
                                            }
                                        },
                                        onLongClick = {
                                            if (!playlist.isAlbum) {
                                                isSelectionMode = true
                                                selectedTrackIds = setOf(track.fullId)
                                            }
                                        }
                                    )
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Чекбокс режима множественного выбора (пакетные операции)
                                AnimatedVisibility(visible = isSelectionMode) {
                                    Checkbox(
                                        checked = selectedTrackIds.contains(track.fullId),
                                        onCheckedChange = { checked ->
                                            selectedTrackIds = if (checked) {
                                                selectedTrackIds + track.fullId
                                            } else {
                                                selectedTrackIds - track.fullId
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AppleRed,
                                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }

                                if (!isSelectionMode) {
                                    // Порядковый номер трека
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isCurrent) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(28.dp)
                                    )
                                }

                                // Обложка трека: для альбома (1 артист) подходит обложка альбома; для плейлиста (сборка разных артистов) - только собственная обложка трека
                                val itemCoverUrl = if (playlist.isAlbum) {
                                    track.coverUrl ?: playlist.coverUrl
                                } else {
                                    resolvedCovers[track.fullId] ?: if (isCurrent) currentTrack?.coverUrl ?: track.coverUrl else track.coverUrl
                                }

                                TrackCoverThumbnail(
                                    coverUrl = itemCoverUrl,
                                    sizeDp = 44.dp,
                                    iconSizeDp = 20.dp
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // Название и артист
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        fontSize = 15.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrent) {
                                            AppleRed
                                        } else if (track.isRestricted) {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.onBackground
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        fontSize = 13.sp,
                                        color = if (track.isRestricted) {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (!isSelectionMode) {
                                    // Кнопка "Сердечко"
                                    IconButton(
                                        onClick = { onToggleLike(track) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = if (isLiked) "В избранном" else "Нравится",
                                            tint = if (isLiked) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    if (onMoreTrackClick != null) {
                                        IconButton(
                                            onClick = { onMoreTrackClick(track) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Действия с треком",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Плавающая панель пакетных действий
            if (isSelectionMode) {
                val selectedTracks = tracks.filter { selectedTrackIds.contains(it.fullId) }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
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
                                    selectedTrackIds = if (selectedTrackIds.size == tracks.size) {
                                        emptySet()
                                    } else {
                                        tracks.map { it.fullId }.toSet()
                                    }
                                }
                            ) {
                                Text(
                                    text = if (selectedTrackIds.size == tracks.size) "Снять" else "Все",
                                    color = AppleRed,
                                    fontSize = 13.sp
                                )
                            }

                            // Скачать выбранные
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

                            // В другой плейлист
                            val otherPlaylists = userPlaylists.filter { it.id != playlist.id }
                            if (otherPlaylists.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        showPlaylistChooserDialog = true
                                    },
                                    enabled = selectedTracks.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "В плейлист", tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            // Удалить из этого плейлиста
                            if (isUserPlaylist) {
                                IconButton(
                                    onClick = {
                                        onBatchRemoveFromPlaylistClick?.invoke(selectedTracks)
                                        isSelectionMode = false
                                        selectedTrackIds = emptySet()
                                    },
                                    enabled = selectedTracks.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить из плейлиста", tint = Color(0xFFFF3B30))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
