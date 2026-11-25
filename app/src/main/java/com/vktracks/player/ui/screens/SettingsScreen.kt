package com.vktracks.player.ui.screens

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vktracks.player.BuildConfig
import com.vktracks.player.data.api.VkAuthManager
import com.vktracks.player.data.downloader.TrackDownloader
import com.vktracks.player.service.PlaybackService
import com.vktracks.player.ui.theme.AppleRed
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: VkAuthManager,
    downloader: TrackDownloader,
    cacheSizeDisplay: String = "0 МБ",
    isCacheEnabled: Boolean = true,
    maxCacheMb: Int = 2048,
    isCacheAllPlayed: Boolean = true,
    isCacheOnlyFavorites: Boolean = false,
    onToggleCacheEnabled: (Boolean) -> Unit = {},
    onToggleCacheAllPlayed: (Boolean) -> Unit = {},
    onToggleCacheOnlyFavorites: (Boolean) -> Unit = {},
    onSelectMaxCacheMb: (Int) -> Unit = {},
    onClearCache: () -> Unit = {},
    onClearImageCache: () -> Unit = {},
    isBtAutoResume: Boolean = true,
    isBtAutoPause: Boolean = true,
    isSoftFading: Boolean = true,
    onToggleBtAutoResume: (Boolean) -> Unit = {},
    onToggleBtAutoPause: (Boolean) -> Unit = {},
    onToggleSoftFading: (Boolean) -> Unit = {},
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit = {},
    onCheckUpdateClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isAuthorized = authManager.isAuthorized()
    val userName = authManager.getUserName() ?: if (isAuthorized) "Пользователь VK" else "Не авторизован"
    val userPhoto = authManager.getUserPhoto()
    val userId = authManager.getUserId()

    var downloadPathDisplay by remember { mutableStateOf(downloader.getDownloadPathDisplay()) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var showCacheLimitDialog by remember { mutableStateOf(false) }
    var showCacheModeDialog by remember { mutableStateOf(false) }

    val cacheModeOptions = listOf(
        false to Pair("Все проигрываемые треки", "Автоматически сохранять любой воспроизводимый трек в кэш"),
        true to Pair("Только избранные треки", "Кэшировать при воспроизведении только треки из Избранного")
    )

    val cacheLimitOptions = listOf(
        500 to "500 МБ",
        1024 to "1 ГБ",
        2048 to "2 ГБ",
        5120 to "5 ГБ",
        10240 to "10 ГБ",
        0 to "Без ограничений"
    )

    val currentLimitDisplay = when (maxCacheMb) {
        0 -> "Без ограничений"
        500 -> "500 МБ"
        1024 -> "1 ГБ"
        2048 -> "2 ГБ"
        5120 -> "5 ГБ"
        10240 -> "10 ГБ"
        else -> "$maxCacheMb МБ"
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val segment = uri.lastPathSegment ?: ""
            val resolvedPath = if (segment.startsWith("primary:")) {
                val relative = segment.substringAfter("primary:")
                File(Environment.getExternalStorageDirectory(), relative).absolutePath
            } else {
                uri.path ?: ""
            }
            if (resolvedPath.isNotBlank()) {
                val targetDir = File(resolvedPath)
                if (!targetDir.exists()) targetDir.mkdirs()
                downloader.setCustomDownloadPath(resolvedPath)
                downloadPathDisplay = downloader.getDownloadPathDisplay()
                Toast.makeText(context, "Папка сохранения изменена", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Папка для сохранения") },
            text = {
                Column {
                    Text("Текущий путь:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(downloadPathDisplay, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "На Android 10+ аудиофайлы сохраняются в общедоступную системную папку Music/VK Tracks устройства (согласно стандартам Scoped Storage) с вшитыми ID3-тегами и обложками.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFolderDialog = false
                        folderPickerLauncher.launch(null)
                    }
                ) {
                    Text("Выбрать другую папку")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showFolderDialog = false
                        downloader.setCustomDownloadPath(null)
                        downloadPathDisplay = downloader.getDownloadPathDisplay()
                        Toast.makeText(context, "Сброшено на Music/VK Tracks", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Сбросить по умолчанию")
                }
            }
        )
    }

    if (showCacheLimitDialog) {
        AlertDialog(
            onDismissRequest = { showCacheLimitDialog = false },
            title = { Text("Лимит дискового кэша") },
            text = {
                Column {
                    Text(
                        "При превышении лимита старые треки удаляются автоматически (LRU).",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    cacheLimitOptions.forEach { (mb, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectMaxCacheMb(mb)
                                    showCacheLimitDialog = false
                                    Toast.makeText(context, "Лимит кэша: $label", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = maxCacheMb == mb,
                                onClick = {
                                    onSelectMaxCacheMb(mb)
                                    showCacheLimitDialog = false
                                    Toast.makeText(context, "Лимит кэша: $label", Toast.LENGTH_SHORT).show()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AppleRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = if (maxCacheMb == mb) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCacheLimitDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showCacheModeDialog) {
        AlertDialog(
            onDismissRequest = { showCacheModeDialog = false },
            title = { Text("Кэширование треков") },
            text = {
                Column {
                    Text(
                        "Выберите, какие треки сохранять во временный кэш при прослушивании:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    cacheModeOptions.forEach { (onlyFav, info) ->
                        val (label, desc) = info
                        val selected = isCacheOnlyFavorites == onlyFav
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onToggleCacheOnlyFavorites(onlyFav)
                                    showCacheModeDialog = false
                                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onToggleCacheOnlyFavorites(onlyFav)
                                    showCacheModeDialog = false
                                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = AppleRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = label,
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = desc,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCacheModeDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Профиль VK
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!userPhoto.isNullOrEmpty()) {
                                AsyncImage(
                                    model = userPhoto,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (isAuthorized && userId > 0) {
                                Text(
                                    text = "ID: $userId",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Вход открывает аудиозаписи и плейлисты",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                    if (isAuthorized) {
                        TextButton(
                            onClick = {
                                onLogoutClick()
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text("Выйти из аккаунта", color = Color(0xFFFF3B30), fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onLoginClick,
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("Войти через ВКонтакте", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Воспроизведение и переходы
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("ВОСПРОИЗВЕДЕНИЕ")
                SettingsCard {
                    SettingsSwitchItem(
                        icon = Icons.Default.Tune,
                        title = "Плавное затухание (микро-фейдинг)",
                        subtitle = "Мягкое нарастание и затухание звука (150 мс) при старте, паузе и переключении",
                        checked = isSoftFading,
                        onCheckedChange = onToggleSoftFading
                    )
                }
            }

            // Автомобиль и Bluetooth
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("АВТОМОБИЛЬ И BLUETOOTH")
                SettingsCard {
                    SettingsSwitchItem(
                        icon = Icons.Default.Bluetooth,
                        title = "Автовоспроизведение Bluetooth",
                        subtitle = "Автоматически возобновлять воспроизведение при подключении Bluetooth-устройства",
                        checked = isBtAutoResume,
                        onCheckedChange = onToggleBtAutoResume
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingsSwitchItem(
                        icon = Icons.Default.Pause,
                        title = "Автопауза при отключении",
                        subtitle = "Автоматически ставить на паузу при разрыве Bluetooth-соединения",
                        checked = isBtAutoPause,
                        onCheckedChange = onToggleBtAutoPause
                    )
                }
            }

            // Звук
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("ЗВУК")
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.GraphicEq,
                        title = "Системный эквалайзер",
                        subtitle = "Открыть системную панель эффектов устройства",
                        onClick = {
                            val opened = PlaybackService.instance?.openSystemEqualizer(context) ?: false
                            if (!opened) {
                                Toast.makeText(
                                    context,
                                    "Системный эквалайзер недоступен на этом устройстве",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }

            // Память и загрузки
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("ЗАГРУЗКИ")
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.Folder,
                        title = "Папка с сохраненными треками",
                        subtitle = downloadPathDisplay,
                        onClick = { showFolderDialog = true }
                    )
                }
            }

            // Кэширование
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("КЭШИРОВАНИЕ")
                SettingsCard {
                    SettingsSwitchItem(
                        icon = Icons.Default.CloudSync,
                        title = "Кэшировать треки на лету",
                        subtitle = "Сохранять воспроизводимые треки во временный кэш для офлайн-прослушивания",
                        checked = isCacheEnabled,
                        onCheckedChange = onToggleCacheEnabled
                    )
                    if (isCacheEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = Icons.Default.FilterList,
                            title = "Кэшировать все / только избранные треки",
                            subtitle = if (isCacheOnlyFavorites) "Только избранные треки" else "Все проигрываемые треки",
                            onClick = { showCacheModeDialog = true }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = Icons.Default.Storage,
                            title = "Размер кэша",
                            subtitle = "Занято: $cacheSizeDisplay",
                            onClick = {
                                Toast.makeText(context, "Занято кэшем: $cacheSizeDisplay", Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = Icons.Default.DataUsage,
                            title = "Лимит размера кэша",
                            subtitle = currentLimitDisplay,
                            onClick = { showCacheLimitDialog = true }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = Icons.Default.DeleteOutline,
                            title = "Очистить кэш треков",
                            subtitle = "Удалить все кэшированные аудиозаписи",
                            onClick = {
                                onClearCache()
                                Toast.makeText(context, "Кэш треков очищен", Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        SettingsItem(
                            icon = Icons.Default.Image,
                            title = "Очистить кэш обложек",
                            subtitle = "Сбросить кэш обложек и загрузить заново",
                            onClick = {
                                onClearImageCache()
                                Toast.makeText(context, "Кэш обложек очищен", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // О приложении и обновления
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("О ПРИЛОЖЕНИИ")
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.SystemUpdate,
                        title = "Проверить обновления",
                        subtitle = "Версия v${BuildConfig.VERSION_NAME} (Сборка ${BuildConfig.VERSION_CODE})",
                        onClick = onCheckUpdateClick
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppleRed,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppleRed,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppleRed
            )
        )
    }
}
