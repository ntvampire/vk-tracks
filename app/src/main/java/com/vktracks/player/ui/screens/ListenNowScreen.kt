package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.vktracks.player.data.model.VkMixSettings
import com.vktracks.player.ui.components.VkMixSettingsSheet
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenNowScreen(
    currentTrack: Track?,
    isPlaying: Boolean,
    mixSettings: VkMixSettings,
    onVkMixClick: () -> Unit,
    onUpdateMixSettings: (VkMixSettings) -> Unit,
    onRadioClick: (RadioStation) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMixSettingsSheet by remember { mutableStateOf(false) }

    if (showMixSettingsSheet) {
        VkMixSettingsSheet(
            currentSettings = mixSettings,
            onApply = { onUpdateMixSettings(it) },
            onDismiss = { showMixSettingsSheet = false }
        )
    }
    val isDark = isSystemInDarkTheme()

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
            val mixGradient = if (isDark) {
                listOf(Color(0xFF2E2638), Color(0xFF1E1A24))
            } else {
                listOf(Color(0xFFF4EEF8), Color(0xFFE8DEF8))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .shadow(if (isDark) 12.dp else 4.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(mixGradient))
                    .padding(20.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showMixSettingsSheet = true }
                    ) {
                        Text(
                            text = "VK mix",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Настройки микса",
                            tint = if (isDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Динамический плейлист, который подстраивается под ваши вкусы",
                        fontSize = 13.sp,
                        color = if (isDark) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                containerColor = if (isDark) Color.White.copy(alpha = 0.15f) else AppleRed,
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
                            onClick = { showMixSettingsSheet = true },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isDark) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Настройки микса",
                                tint = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Активные фильтры VK Mix
                    val activeFilters = buildList {
                        if (mixSettings.mood != "any") {
                            add(when (mixSettings.mood) {
                                "energetic" -> "Бодрое"
                                "calm" -> "Спокойное"
                                "sad" -> "Грустное"
                                "happy" -> "Радостное"
                                else -> mixSettings.mood
                            })
                        }
                        if (mixSettings.familiarity != "any") {
                            add(when (mixSettings.familiarity) {
                                "familiar" -> "Знакомое"
                                "discover" -> "Открытия"
                                "popular" -> "Популярное"
                                else -> mixSettings.familiarity
                            })
                        }
                        if (mixSettings.language != "any") {
                            add(if (mixSettings.language == "russian") "Русский" else "Иностранный")
                        }
                        if (mixSettings.genre != "any") {
                            add(when (mixSettings.genre) {
                                "pop" -> "Поп"
                                "rock" -> "Рок"
                                "hiphop" -> "Хип-хоп"
                                "electro" -> "Электроника"
                                "indie" -> "Инди"
                                "metal" -> "Метал"
                                else -> mixSettings.genre
                            })
                        }
                    }

                    if (activeFilters.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            activeFilters.forEach { filterText ->
                                Surface(
                                    color = if (isDark) AppleRed.copy(alpha = 0.25f) else AppleRed.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = filterText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isDark) Color.White else AppleRed,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
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
                    gradient = if (isDark) {
                        listOf(Color(0xFF1F1F24), Color(0xFF141416))
                    } else {
                        listOf(Color(0xFFFFF6EE), Color(0xFFFFEDE0))
                    },
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
                    gradient = if (isDark) {
                        listOf(Color(0xFF381216), Color(0xFF1C090B))
                    } else {
                        listOf(Color(0xFFFFF0F3), Color(0xFFFFE5E9))
                    },
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
    val isDark = isSystemInDarkTheme()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .shadow(if (isDark) 8.dp else 4.dp, RoundedCornerShape(20.dp))
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
                    .background(if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f)),
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
                        color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
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
                    color = if (isCurrent) accentColor else if (isDark) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Кнопка Play / Pause
            val playBgColor = if (isCurrent && isPlaying) {
                accentColor
            } else if (isDark) {
                Color.White
            } else {
                accentColor
            }
            val playIconColor = if (isCurrent && isPlaying) {
                Color.White
            } else if (isDark) {
                Color.Black
            } else {
                Color.White
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(playBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isCurrent && isPlaying) "Пауза" else "Слушать",
                    tint = playIconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
