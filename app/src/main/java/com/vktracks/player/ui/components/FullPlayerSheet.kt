package com.vktracks.player.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Track
import com.vktracks.player.service.PlaybackService
import com.vktracks.player.ui.theme.AppleRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    currentPosMs: Long,
    durationMs: Long,
    isShuffle: Boolean,
    repeatMode: Int,
    lyrics: Lyrics?,
    isLiked: Boolean = false,
    onLikeClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDownloadClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null) return
    val context = LocalContext.current
    var isLyricsMode by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхняя плашка управления (закрытие и режим текста)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Свернуть",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )

                IconButton(onClick = { isLyricsMode = !isLyricsMode }) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = "Текст песни",
                        tint = if (isLyricsMode) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Основная область: либо большая обложка, либо синхронизированный текст
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (!isLyricsMode) {
                    // Большая обложка альбома (Apple Music стиль)
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .shadow(24.dp, shape = RoundedCornerShape(20.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!track.coverUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = track.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(96.dp)
                            )
                        }
                    }
                } else {
                    // Караоке-текст (синхронизированные строки с автоскроллом)
                    LyricsView(
                        lyrics = lyrics,
                        currentPosMs = currentPosMs,
                        onLineClick = onSeekTo
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Название трека, артист и кнопка сердечка (Apple Music style)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (track.isExplicit) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Gray.copy(alpha = 0.3f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "E",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onLikeClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "В медиатеке" else "Добавить в медиатеку",
                        tint = if (isLiked) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Таймлайн (Слайдер прогресса)
            var sliderPosition by remember { mutableStateOf<Float?>(null) }
            val currentSlider = sliderPosition ?: if (durationMs > 0) (currentPosMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

            Slider(
                value = currentSlider,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = {
                    sliderPosition?.let {
                        onSeekTo((it * durationMs).toLong())
                        sliderPosition = null
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onBackground,
                    activeTrackColor = MaterialTheme.colorScheme.onBackground,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Метки времени
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(currentPosMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (durationMs > 0) "-" + formatMs((durationMs - currentPosMs).coerceAtLeast(0)) else "--:--",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопки управления (Shuffle, Prev, Play/Pause, Next, Repeat)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Случайно",
                        tint = if (isShuffle) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onPrevClick) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Предыдущий",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Большая кнопка Play/Pause
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onBackground)
                        .clickable(onClick = onPlayPauseClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Следующий",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(38.dp)
                    )
                }

                IconButton(onClick = onToggleRepeat) {
                    val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                    val repeatTint = if (repeatMode != Player.REPEAT_MODE_OFF) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Повтор",
                        tint = repeatTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Нижняя панель действий (Текст песни, Скачивание)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Текст песни
                IconButton(onClick = { isLyricsMode = !isLyricsMode }) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = "Текст песни",
                        tint = if (isLyricsMode) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Скачивание трека в MP3
                IconButton(onClick = onDownloadClick) {
                    Icon(
                        imageVector = if (track.isLocal) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = if (track.isLocal) "Скачано" else "Скачать на телефон",
                        tint = if (track.isLocal) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentPosMs: Long,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lyrics == null || (lyrics.lines.isEmpty() && lyrics.plainText.isEmpty())) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Текст песни недоступен",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    if (lyrics.isSynchronized) {
        // Синхронизированный караоке режим
        val activeIndex = lyrics.lines.indexOfLast { it.beginMs <= currentPosMs }

        LaunchedEffect(activeIndex) {
            if (activeIndex >= 0) {
                coroutineScope.launch {
                    listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 40.dp)
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isActive = index == activeIndex
                Text(
                    text = line.text,
                    fontSize = if (isActive) 22.sp else 18.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLineClick(line.beginMs) }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Start
                )
            }
        }
    } else {
        // Обычный текст
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 20.dp)
        ) {
            itemsIndexed(lyrics.plainText) { _, line ->
                Text(
                    text = line,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
