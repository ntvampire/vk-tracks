package com.vktracks.player.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.vktracks.player.R
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Track
import com.vktracks.player.service.PlaybackService
import com.vktracks.player.ui.theme.AppleRed
import kotlinx.coroutines.launch

enum class PlayerCenterMode {
    COVER,
    LYRICS,
    QUEUE
}

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
    queue: List<Track> = emptyList(),
    isLiked: Boolean = false,
    isDownloaded: Boolean = false,
    onLikeClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onDownloadClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (track == null) return
    val context = LocalContext.current
    var centerMode by remember { mutableStateOf(PlayerCenterMode.COVER) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BackHandler(onBack = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхняя плашка управления (закрытие и режим очереди)
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

                IconButton(onClick = {
                    centerMode = if (centerMode == PlayerCenterMode.QUEUE) PlayerCenterMode.COVER else PlayerCenterMode.QUEUE
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Очередь воспроизведения",
                        tint = if (centerMode == PlayerCenterMode.QUEUE) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Основная область: либо большая обложка, либо синхронизированный текст, либо очередь
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (centerMode) {
                    PlayerCenterMode.COVER -> {
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
                                SubcomposeAsyncImage(
                                    model = track.coverUrl,
                                    contentDescription = track.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    loading = {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                                modifier = Modifier.size(80.dp)
                                            )
                                        }
                                    },
                                    error = {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(80.dp)
                                            )
                                        }
                                    }
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(80.dp)
                                )
                            }
                        }
                    }
                    PlayerCenterMode.LYRICS -> {
                        // Караоке-текст (синхронизированные строки с автоскроллом)
                        LyricsView(
                            lyrics = lyrics,
                            currentPosMs = currentPosMs,
                            onLineClick = onSeekTo
                        )
                    }
                    PlayerCenterMode.QUEUE -> {
                        // Очередь воспроизведения
                        PlayerQueueView(
                            queue = queue,
                            currentTrack = track,
                            isPlaying = isPlaying,
                            onTrackClick = { clickedTrack ->
                                onTrackClick(clickedTrack, queue)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Название трека, артист и кнопка сердечка (Apple Music style с плавной бегущей строкой)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = track.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 1500,
                                    repeatDelayMillis = 2000,
                                    velocity = 35.dp
                                )
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
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1500,
                                repeatDelayMillis = 2000,
                                velocity = 35.dp
                            )
                    )
                }

                val isLiveStream = track.duration == 0

                IconButton(
                    onClick = onLikeClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (!isLiveStream && isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiveStream) "Найти в поиске" else if (isLiked) "В медиатеке" else "Добавить в медиатеку",
                        tint = if (!isLiveStream && isLiked) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val isLiveStream = track.duration == 0

            if (isLiveStream) {
                // Бейдж прямого эфира
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppleRed)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ПРЯМОЙ ЭФИР",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            } else {
                // Таймлайн (Слайдер прогресса)
                val trackDurationMs = if (durationMs > 0) durationMs else ((track?.duration ?: 0).toLong() * 1000L)
                val coroutineScope = rememberCoroutineScope()
                var sliderPosition by remember(track?.id) { mutableStateOf<Float?>(null) }
                var seekJob by remember(track?.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val currentSlider = sliderPosition ?: if (trackDurationMs > 0) (currentPosMs.toFloat() / trackDurationMs).coerceIn(0f, 1f) else 0f

                val displayPosMs = if (sliderPosition != null && trackDurationMs > 0) {
                    (sliderPosition!! * trackDurationMs).toLong()
                } else {
                    currentPosMs
                }

                Slider(
                    value = currentSlider,
                    onValueChange = {
                        seekJob?.cancel()
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        sliderPosition?.let { targetFraction ->
                            if (trackDurationMs > 0) {
                                onSeekTo((targetFraction * trackDurationMs).toLong())
                            }
                            seekJob?.cancel()
                            seekJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(800)
                                sliderPosition = null
                            }
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
                        text = formatMs(displayPosMs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (trackDurationMs > 0) "-" + formatMs((trackDurationMs - displayPosMs).coerceAtLeast(0)) else "--:--",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                IconButton(onClick = {
                    centerMode = if (centerMode == PlayerCenterMode.LYRICS) PlayerCenterMode.COVER else PlayerCenterMode.LYRICS
                }) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = "Текст песни",
                        tint = if (centerMode == PlayerCenterMode.LYRICS) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Скачивание трека в MP3
                val isTrackSaved = isDownloaded || track.isLocal
                IconButton(onClick = onDownloadClick) {
                    Icon(
                        imageVector = if (isTrackSaved) Icons.Default.CheckCircle else Icons.Default.Download,
                        contentDescription = if (isTrackSaved) "Скачано (нажмите для удаления)" else "Скачать на телефон",
                        tint = if (isTrackSaved) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun PlayerQueueView(
    queue: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    if (queue.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Очередь воспроизведения пуста",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val activeIndex = remember(queue, currentTrack) {
        queue.indexOfFirst { it.id == currentTrack?.id }
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Далее в очереди (${queue.size})",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(queue, key = { index, item -> "${item.id}_$index" }) { index, item ->
                val isCurrent = item.id == currentTrack?.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onTrackClick(item) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackCoverThumbnail(
                        coverUrl = item.coverUrl,
                        sizeDp = 44.dp,
                        iconSizeDp = 22.dp
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrent) AppleRed else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            softWrap = false,
                            modifier = if (isCurrent) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 1500,
                                    repeatDelayMillis = 2000,
                                    velocity = 30.dp
                                )
                            } else {
                                Modifier
                            },
                            overflow = if (isCurrent) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.artist,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = if (isCurrent) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 1500,
                                    repeatDelayMillis = 2000,
                                    velocity = 30.dp
                                )
                            } else {
                                Modifier
                            },
                            overflow = if (isCurrent) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                    }

                    if (isCurrent && isPlaying) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Играет",
                            tint = AppleRed,
                            modifier = Modifier.size(20.dp)
                        )
                    } else if (item.duration > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatMs(item.duration * 1000L),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
