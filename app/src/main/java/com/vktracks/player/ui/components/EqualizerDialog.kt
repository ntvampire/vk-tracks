package com.vktracks.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vktracks.player.audio.EqualizerPreset
import com.vktracks.player.audio.EqualizerPresets
import com.vktracks.player.ui.theme.AppleRed
import kotlin.math.roundToInt

@Composable
fun EqualizerDialog(
    isEnabled: Boolean,
    selectedPresetId: String,
    bandGains: List<Float>,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Эквалайзер",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = AppleRed
                    )
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Пресеты
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(EqualizerPresets.PRESETS, key = { it.id }) { preset ->
                        val selected = selectedPresetId == preset.id
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(preset.name, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppleRed.copy(alpha = 0.18f),
                                selectedLabelColor = AppleRed
                            )
                        )
                    }
                }

                if (selectedPresetId == EqualizerPresets.CUSTOM_PRESET_ID) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Режим: Пользовательский",
                        fontSize = 12.sp,
                        color = AppleRed,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 10 вертикальных ползунков полос (-12 .. +12 дБ)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (band in 0 until EqualizerPresets.BAND_COUNT) {
                        val gain = bandGains.getOrElse(band) { 0f }
                        val label = EqualizerPresets.BAND_LABELS[band]
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val roundedDb = gain.roundToInt()
                            Text(
                                text = if (roundedDb > 0) "+$roundedDb" else "$roundedDb",
                                fontSize = 10.sp,
                                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            VerticalEqSlider(
                                value = gain,
                                enabled = isEnabled,
                                onValueChange = { onBandGainChange(band, it) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово", color = AppleRed, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text("Сбросить")
            }
        }
    )
}

@Composable
private fun VerticalEqSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = if (enabled) AppleRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    fun updateFromY(y: Float, height: Float) {
        if (height <= 0f) return
        val padding = 12f
        val usable = (height - padding * 2f).coerceAtLeast(1f)
        val normalized = (1f - ((y - padding) / usable)).coerceIn(0f, 1f)
        val rawDb = EqualizerPresets.MIN_GAIN_DB +
            normalized * (EqualizerPresets.MAX_GAIN_DB - EqualizerPresets.MIN_GAIN_DB)
        onValueChange(rawDb.roundToInt().toFloat())
    }

    Canvas(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    updateFromY(offset.y, size.height.toFloat())
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    updateFromY(change.position.y, size.height.toFloat())
                }
            }
    ) {
        val padding = 10.dp.toPx()
        val usableH = (size.height - padding * 2f).coerceAtLeast(1f)
        val centerX = size.width / 2f
        val zeroY = padding + usableH * 0.5f
        val norm = ((value - EqualizerPresets.MIN_GAIN_DB) /
            (EqualizerPresets.MAX_GAIN_DB - EqualizerPresets.MIN_GAIN_DB)).coerceIn(0f, 1f)
        val thumbY = padding + usableH * (1f - norm)
        val trackW = 4.dp.toPx()

        // Фоновая дорожка
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(centerX - trackW / 2f, padding),
            size = Size(trackW, usableH),
            cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
        )

        // Линия нуля (0 дБ)
        drawLine(
            color = trackColor.copy(alpha = 0.9f),
            start = Offset(centerX - 8.dp.toPx(), zeroY),
            end = Offset(centerX + 8.dp.toPx(), zeroY),
            strokeWidth = 1.dp.toPx()
        )

        // Активный участок от 0 дБ до текущего значения
        val segTop = minOf(zeroY, thumbY)
        val segHeight = kotlin.math.abs(thumbY - zeroY)
        if (segHeight > 0.5f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(centerX - trackW / 2f, segTop),
                size = Size(trackW, segHeight),
                cornerRadius = CornerRadius(trackW / 2f, trackW / 2f)
            )
        }

        // Ползунок
        drawCircle(
            color = activeColor,
            radius = 7.dp.toPx(),
            center = Offset(centerX, thumbY)
        )
        drawCircle(
            color = thumbColor,
            radius = 3.dp.toPx(),
            center = Offset(centerX, thumbY)
        )
    }
}
