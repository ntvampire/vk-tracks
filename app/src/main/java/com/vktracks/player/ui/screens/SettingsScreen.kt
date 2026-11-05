package com.vktracks.player.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.vktracks.player.service.PlaybackService
import com.vktracks.player.ui.theme.AppleRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authManager: VkAuthManager,
    onLoginClick: () -> Unit,
    onCheckUpdateClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isAuthorized = authManager.isAuthorized()
    val userName = authManager.getUserName() ?: if (isAuthorized) "Пользователь VK" else "Не авторизован"
    val userPhoto = authManager.getUserPhoto()
    val userId = authManager.getUserId()

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
                                authManager.logout()
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

            // Звук
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SettingsHeader("ЗВУК")
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.GraphicEq,
                        title = "Системный эквалайзер",
                        subtitle = "Открыть системную панель эффектов устройства",
                        onClick = { PlaybackService.instance?.openSystemEqualizer(context) }
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
                        subtitle = "Music/VK Tracks (с ID3v2 тегами и обложками)",
                        onClick = {}
                    )
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingsItem(
                        icon = Icons.Default.Code,
                        title = "Исходный код на GitHub",
                        subtitle = "ntvampire/vk-tracks",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ntvampire/vk-tracks"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingsItem(
                        icon = Icons.Default.Public,
                        title = "Страница проекта",
                        subtitle = "ntvampire.github.io/vk-tracks",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ntvampire.github.io/vk-tracks/"))
                            context.startActivity(intent)
                        }
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
