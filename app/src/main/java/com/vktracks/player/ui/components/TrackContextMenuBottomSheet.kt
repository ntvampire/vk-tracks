package com.vktracks.player.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenuBottomSheet(
    track: Track,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueueEnd: () -> Unit,
    onSearchArtist: ((String) -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreateNewPlaylist: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showTrackInfoDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    if (showTrackInfoDialog) {
        TrackInfoDialog(
            track = track,
            onDismiss = { showTrackInfoDialog = false },
            onSearchArtist = { artistName ->
                showTrackInfoDialog = false
                onDismiss()
                onSearchArtist?.invoke(artistName)
            }
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { title, desc ->
                showCreatePlaylistDialog = false
                onCreateNewPlaylist(title, desc)
                onDismiss()
            }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists.filter { !it.isAlbum },
            onDismiss = { showAddToPlaylistDialog = false },
            onSelectPlaylist = { pl ->
                showAddToPlaylistDialog = false
                onAddToPlaylist(pl)
                onDismiss()
            },
            onCreateNewClick = {
                showAddToPlaylistDialog = false
                showCreatePlaylistDialog = true
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Заголовок с миниатюрой трека
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackCoverThumbnail(
                    coverUrl = track.coverUrl,
                    sizeDp = 52.dp,
                    iconSizeDp = 26.dp
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // 1. Информация о треке (первым в меню)
            ContextMenuActionItem(
                icon = Icons.Default.Info,
                title = "Информация о треке",
                onClick = {
                    showTrackInfoDialog = true
                }
            )

            // 2. Поделиться (вторым в меню)
            ContextMenuActionItem(
                icon = Icons.Default.Share,
                title = "Поделиться",
                onClick = {
                    shareTrack(context, track)
                    onDismiss()
                }
            )

            // 3. Воспроизвести следующим
            ContextMenuActionItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Воспроизвести следующим",
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )

            // 4. Добавить в конец очереди
            ContextMenuActionItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Добавить в конец очереди",
                onClick = {
                    onAddToQueueEnd()
                    onDismiss()
                }
            )

            // 5. Добавить в плейлист
            ContextMenuActionItem(
                icon = Icons.Default.LibraryAdd,
                title = "Добавить в плейлист...",
                onClick = {
                    showAddToPlaylistDialog = true
                }
            )

            // 6. Поиск по исполнителю (переход в поиск)
            if (onSearchArtist != null && track.artist.isNotBlank()) {
                ContextMenuActionItem(
                    icon = Icons.Default.Search,
                    title = "Поиск по исполнителю: ${track.artist}",
                    onClick = {
                        onDismiss()
                        onSearchArtist(track.artist)
                    }
                )
            }

            // 7. Перейти к альбому (если доступен)
            if (onOpenAlbum != null && !track.albumTitle.isNullOrBlank()) {
                ContextMenuActionItem(
                    icon = Icons.Default.Album,
                    title = "Перейти к альбому: ${track.albumTitle}",
                    onClick = {
                        onOpenAlbum()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ContextMenuActionItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TrackInfoDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSearchArtist: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var bitrateInfo by remember(track.fullId) { mutableStateOf<String?>("Определение...") }

    LaunchedEffect(track.fullId) {
        bitrateInfo = com.vktracks.player.data.utils.TrackBitrateResolver.resolveBitrate(context, track)
    }

    val durationFormatted = if (track.duration > 0) {
        val min = track.duration / 60
        val sec = track.duration % 60
        String.format("%d:%02d", min, sec)
    } else "Прямой эфир"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Информация о треке", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow(label = "Название", value = track.title)

                // Кликабельный исполнитель (открывает поиск по исполнителю)
                Column {
                    Text(
                        text = "Исполнитель",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        fontSize = 14.sp,
                        color = if (onSearchArtist != null) AppleRed else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (onSearchArtist != null) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (onSearchArtist != null) {
                            Modifier.clickable {
                                onSearchArtist(track.artist)
                            }
                        } else Modifier
                    )
                }

                if (!track.albumTitle.isNullOrBlank()) {
                    InfoRow(label = "Альбом", value = track.albumTitle)
                }
                InfoRow(label = "Длительность", value = durationFormatted)
                InfoRow(label = "Формат и битрейт", value = bitrateInfo ?: "Определение...")
                if (track.isLocal && !track.localFilePath.isNullOrBlank()) {
                    InfoRow(label = "Файл на диске", value = track.localFilePath)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreateNewClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Кнопка создания нового плейлиста
                Surface(
                    onClick = onCreateNewClick,
                    shape = RoundedCornerShape(12.dp),
                    color = AppleRed.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
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

                if (playlists.isEmpty()) {
                    Text(
                        text = "У вас пока нет созданных плейлистов",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(playlists, key = { it.fullId }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPlaylist(pl) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = pl.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${pl.count} треков",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый плейлист", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название плейлиста") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание (необязательно)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim(), description.trim())
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

fun shareTrack(context: Context, track: Track) {
    try {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, "Послушай трек «${track.artist} — ${track.title}» в VK: https://vk.com/audio${track.fullId}")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Поделиться треком")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    } catch (_: Exception) {}
}
