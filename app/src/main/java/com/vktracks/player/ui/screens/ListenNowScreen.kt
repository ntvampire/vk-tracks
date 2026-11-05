package com.vktracks.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.vktracks.player.data.model.CatalogBlock
import com.vktracks.player.data.model.StreamMix
import com.vktracks.player.ui.theme.AppleRed

@Composable
fun ListenNowScreen(
    mixBlocks: List<CatalogBlock>,
    onPlayMix: (StreamMix) -> Unit,
    onRadioClick: () -> Unit,
    onTopTracksClick: () -> Unit,
    onTopArtistsClick: () -> Unit,
    onTopGenresClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vkMixBlock = mixBlocks.firstOrNull { it.id == "section_vk_mix" || it.title.contains("VK mix", ignoreCase = true) }
    val artistMixesBlock = mixBlocks.firstOrNull { it.id == "section_artist_mixes" || it.title.contains("артист", ignoreCase = true) }
    val genreMixesBlock = mixBlocks.firstOrNull { it.id == "section_genre_mixes" || it.title.contains("жанр", ignoreCase = true) }

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
                    text = "Миксы",
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

        // 1. Секция VK MIX (Скриншот 1)
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
                            onClick = onRadioClick,
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
                            onClick = onRadioClick,
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

        // 2. Секция: Миксы по артистам (Скриншот 1)
        if (artistMixesBlock != null && artistMixesBlock.mixes.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = artistMixesBlock.title.ifBlank { "Миксы по артистам" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(artistMixesBlock.mixes) { mix ->
                        ArtistMixCard(mix = mix, onPlayClick = { onPlayMix(mix) })
                    }
                }
            }
        }

        // 3. Секция: Миксы по жанрам (Скриншот 2)
        if (genreMixesBlock != null && genreMixesBlock.mixes.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = genreMixesBlock.title.ifBlank { "Миксы по жанрам" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(genreMixesBlock.mixes) { mix ->
                        GenreMixCard(mix = mix, onPlayClick = { onPlayMix(mix) })
                    }
                }
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

@Composable
private fun ArtistMixCard(
    mix: StreamMix,
    onPlayClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(165.dp)
            .height(185.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFF8B2B36),
                        Color(0xFF42151B)
                    )
                )
            )
            .clickable(onClick = onPlayClick)
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Круглое фото артиста
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!mix.imageUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = mix.imageUrl,
                            contentDescription = mix.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Круглая кнопка Play
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Слушать",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = mix.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!mix.subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mix.subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun GenreMixCard(
    mix: StreamMix,
    onPlayClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(165.dp)
            .height(185.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFF8B4D2B),
                        Color(0xFF3E1F11)
                    )
                )
            )
            .clickable(onClick = onPlayClick)
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Коллаж обложек альбомов соответствующего жанра
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Круглая кнопка Play
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Слушать",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = mix.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!mix.subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mix.subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
