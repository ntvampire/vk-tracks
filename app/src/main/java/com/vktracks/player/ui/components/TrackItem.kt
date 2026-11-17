package com.vktracks.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    track: Track,
    isCurrent: Boolean,
    onTrackClick: () -> Unit,
    isLiked: Boolean = false,
    isDownloaded: Boolean = false,
    onLikeClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    coverUrl: String? = track.coverUrl,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onToggleSelect?.invoke(!isSelected)
                    } else if (track.isRestricted) {
                        android.widget.Toast.makeText(
                            context,
                            "Воспроизведение недоступно: ${track.displayRestriction}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        onTrackClick()
                    }
                },
                onLongClick = {
                    onLongClick?.invoke()
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Чекбокс режима множественного выбора (пакетные операции)
        AnimatedVisibility(visible = isSelectionMode) {
            Row {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect?.invoke(it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AppleRed,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // Миниатюра обложки (выделяет ~80 КБ в RAM вместо 4 МБ)
        TrackCoverThumbnail(
            coverUrl = coverUrl,
            sizeDp = 48.dp,
            iconSizeDp = 24.dp
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Название и артист
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = if (isCurrent) AppleRed else if (track.isRestricted) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.isExplicit) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.Gray.copy(alpha = 0.3f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "E",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
                if (track.isRestricted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = track.displayRestriction,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = track.artist,
                fontSize = 13.sp,
                color = if (track.isRestricted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (!isSelectionMode) {
            // Кнопка добавления в медиатеку (сердечко)
            if (onLikeClick != null) {
                IconButton(
                    onClick = onLikeClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "В медиатеке" else "Добавить в медиатеку",
                        tint = if (isLiked) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Кнопка скачивания
            if (onDownloadClick != null) {
                val isTrackSaved = isDownloaded || track.isLocal
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isTrackSaved) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Скачано (нажмите для удаления)",
                            tint = AppleRed,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Скачать",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Кнопка контекстного меню (3 точки)
            if (onMoreClick != null) {
                IconButton(
                    onClick = onMoreClick,
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
