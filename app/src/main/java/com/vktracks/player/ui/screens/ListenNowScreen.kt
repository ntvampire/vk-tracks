package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.data.model.RadioStation
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.theme.AppleRed

@Composable
fun ListenNowScreen(
    currentTrack: Track?,
    isPlaying: Boolean,
    onVkMixClick: () -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onTopTracksClick: () -> Unit,
    onTopArtistsClick: () -> Unit,
    onTopGenresClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Большой заголовок в стиле Apple Music
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Слушать",
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
        }

        // 1. Секция VK MIX
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF2E2638),
                                Color(0xFF1E1A24)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "VK mix",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Динамический плейлист, который подстраивается под ваши вкусы",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onVkMixClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Слушать",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = onVkMixClick,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Настройка",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Меню переходов (Топ треков, Топ исполнителей, Топ жанров)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        color = Color.White.copy(alpha = 0.08f)
                    ) {
                        Column {
                            MixMenuItem(
                                title = "Топ прослушанных треков",
                                onClick = onTopTracksClick
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                            MixMenuItem(
                                title = "Топ исполнителей",
                                onClick = onTopArtistsClick
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                            MixMenuItem(
                                title = "Топ жанров",
                                onClick = onTopGenresClick
                            )
                        }
                    }
                }
            }
        }

        // 2. Секция: Онлайн-радио (Studio 21 и Radio Record)
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Прямой эфир",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Баннер Studio 21
                val isS21 = currentTrack?.streamUrl == RadioStation.STUDIO_21.streamUrl
                RadioBannerCard(
                    station = RadioStation.STUDIO_21,
                    isCurrent = isS21,
                    isPlaying = isS21 && isPlaying,
                    currentLiveTrack = if (isS21) currentTrack?.title else null,
                    gradient = listOf(Color(0xFF1F1F24), Color(0xFF141416)),
                    accentColor = Color(0xFFFF5722),
                    onClick = { onRadioClick(RadioStation.STUDIO_21) }
                )

                // Баннер Radio Record
                val isRecord = currentTrack?.streamUrl == RadioStation.RADIO_RECORD.streamUrl
                RadioBannerCard(
                    station = RadioStation.RADIO_RECORD,
                    isCurrent = isRecord,
                    isPlaying = isRecord && isPlaying,
                    currentLiveTrack = if (isRecord) currentTrack?.title else null,
                    gradient = listOf(Color(0xFF381216), Color(0xFF1C090B)),
                    accentColor = AppleRed,
                    onClick = { onRadioClick(RadioStation.RADIO_RECORD) }
                )
            }
        }
    }
}

@Composable
private fun RadioBannerCard(
    station: RadioStation,
    isCurrent: Boolean,
    isPlaying: Boolean,
    currentLiveTrack: String?,
    gradient: List<Color>,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Логотип станции
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = station.logoUrl,
                    contentDescription = station.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Текстовая информация
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = station.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Бейдж LIVE
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                val subtitleText = if (isCurrent && !currentLiveTrack.isNullOrBlank() && currentLiveTrack != station.title) {
                    currentLiveTrack
                } else {
                    station.subtitle
                }

                Text(
                    text = subtitleText,
                    fontSize = 13.sp,
                    color = if (isCurrent) accentColor else Color.White.copy(alpha = 0.7f),
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Кнопка Play / Pause
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent && isPlaying) accentColor else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrent && isPlaying) "Пауза" else "Слушать",
                    tint = if (isCurrent && isPlaying) Color.White else Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun MixMenuItem(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp)
        )
    }
}
