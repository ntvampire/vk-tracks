package com.vktracks.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vktracks.player.data.model.VkMixSettings
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VkMixSettingsSheet(
    currentSettings: VkMixSettings,
    onApply: (VkMixSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var mood by remember { mutableStateOf(currentSettings.mood) }
    var familiarity by remember { mutableStateOf(currentSettings.familiarity) }
    var language by remember { mutableStateOf(currentSettings.language) }
    var genre by remember { mutableStateOf(currentSettings.genre) }

    val moods = listOf(
        "any" to "Любое",
        "energetic" to "Бодрое",
        "calm" to "Спокойное",
        "sad" to "Грустное",
        "happy" to "Радостное"
    )

    val familiarities = listOf(
        "any" to "Вперемешку",
        "familiar" to "Знакомое",
        "discover" to "Открытия",
        "popular" to "Популярное"
    )

    val languages = listOf(
        "any" to "Любой",
        "russian" to "Русский",
        "foreign" to "Иностранный"
    )

    val genres = listOf(
        "any" to "Все жанры",
        "pop" to "Поп",
        "rock" to "Рок",
        "hiphop" to "Хип-хоп",
        "electro" to "Электроника",
        "indie" to "Инди",
        "metal" to "Метал"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Настройки VK mix",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Настройте поток под ваше текущее настроение и предпочтения",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Настроение
            SettingsSectionTitle("Настроение")
            ChipRow(
                options = moods,
                selected = mood,
                onSelect = { mood = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Узнаваемость
            SettingsSectionTitle("Узнаваемость")
            ChipRow(
                options = familiarities,
                selected = familiarity,
                onSelect = { familiarity = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Язык
            SettingsSectionTitle("Язык")
            ChipRow(
                options = languages,
                selected = language,
                onSelect = { language = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Жанр
            SettingsSectionTitle("Жанр")
            ChipRow(
                options = genres,
                selected = genre,
                onSelect = { genre = it }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Кнопки Применить и Сбросить
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        mood = "any"
                        familiarity = "any"
                        language = "any"
                        genre = "any"
                    }
                ) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Button(
                    onClick = {
                        onApply(VkMixSettings(mood = mood, familiarity = familiarity, language = language, genre = genre))
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Слушать", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(options) { (key, label) ->
            val isSelected = selected == key
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(key) },
                color = if (isSelected) AppleRed else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}
