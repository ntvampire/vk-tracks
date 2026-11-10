package com.vktracks.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vktracks.player.R

/**
 * Высокопроизводительный компонент миниатюры обложки для списков, очередей и мини-плеера.
 * Декодирует растровые изображения строго под физический размер экрана (144x144 px = ~80 КБ вместо 4 МБ),
 * что устраняет выделение сотен мегабайт лишней памяти и сборку мусора (GC) при быстром скроллинге.
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
            val context = LocalContext.current
            val px = remember(sizeDp) {
                (sizeDp.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
            }
            val request = remember(coverUrl, px) {
                ImageRequest.Builder(context)
                    .data(coverUrl)
                    .size(px, px)
                    .crossfade(false)
                    .memoryCacheKey("${coverUrl}_$px")
                    .build()
            }
            AsyncImage(
                model = request,
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
