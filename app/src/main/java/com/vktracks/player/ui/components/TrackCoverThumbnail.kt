package com.vktracks.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vktracks.player.R

/**
 * Компонент миниатюры обложки для списков, очередей и мини-плеера.
 * Если URL обложки передан — отображается реальное изображение через Coil.
 * Если обложка отсутствует (null/empty) — отображается заглушка VK Музыка.
 */
@Composable
fun TrackCoverThumbnail(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 48.dp,
    cornerRadiusDp: Dp = 8.dp,
    iconSizeDp: Dp = 24.dp
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_vk_music_placeholder),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
