package com.vktracks.player.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vktracks.player.data.api.VkApiClient
import com.vktracks.player.data.api.VkAuthManager
import com.vktracks.player.ui.theme.AppleRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthDialog(
    authManager: VkAuthManager,
    apiClient: VkApiClient,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0 = VK OAuth, 1 = Token
    var tokenInput by remember { mutableStateOf("") }
    var isCheckingToken by remember { mutableStateOf(false) }
    var tokenErrorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Верхняя панель с кнопкой закрытия
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Вход ВКонтакте",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                // Переключатель способов входа в стиле Apple Music
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = AppleRed,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Официальный вход", fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("По токену", fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedTab == 0) {
                    // Официальный вход через веб-интерфейс VK Музыки
                    var isLoading by remember { mutableStateOf(true) }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.databaseEnabled = true
                                    settings.setSupportMultipleWindows(false)
                                    settings.javaScriptCanOpenWindowsAutomatically = true
                                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            if (newProgress >= 90) isLoading = false
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            isLoading = true
                                            if (url != null) {
                                                val authData = VkAuthManager.parseRedirectUrl(url)
                                                if (authData != null) {
                                                    authManager.saveAuthData(authData.first, authData.second)
                                                    onSuccess()
                                                    return
                                                }
                                            }
                                            super.onPageStarted(view, url, favicon)
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                            super.onPageFinished(view, url)
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val uri = request?.url ?: return false
                                            val url = uri.toString()

                                            // Проверка успешной авторизации и редиректа на blank.html
                                            val authData = VkAuthManager.parseRedirectUrl(url)
                                            if (authData != null) {
                                                authManager.saveAuthData(authData.first, authData.second)
                                                onSuccess()
                                                return true
                                            }

                                            // Безопасный перехват intent:// и других схем, чтобы предотвратить сбой приложения
                                            val scheme = uri.scheme?.lowercase() ?: ""
                                            if (scheme != "http" && scheme != "https") {
                                                try {
                                                    val intent = if (url.startsWith("intent://", ignoreCase = true)) {
                                                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                                    } else {
                                                        Intent(Intent.ACTION_VIEW, uri)
                                                    }
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    view?.context?.startActivity(intent)
                                                } catch (_: Exception) {
                                                    // Стороннее приложение не установлено, WebView не падает
                                                }
                                                return true
                                            }

                                            return false
                                        }
                                    }

                                    loadUrl(VkAuthManager.getOAuthUrl())
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = AppleRed)
                            }
                        }
                    }

                    // Нижняя панель с кнопкой открыть в браузере (на случай если удобнее в Chrome)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VkAuthManager.getOAuthUrl()))
                                    context.startActivity(intent)
                                }
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Открыть в браузере телефона")
                            }
                        }
                    }
                } else {
                    // Вход по Access Token (как в VKX)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Прямой вход по токену",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Самый надежный способ авторизации без ввода паролей в приложении. Поддерживаются токены VK Музыки, Kate Mobile или полные ссылки blank.html.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                tokenErrorMessage = null
                            },
                            label = { Text("Access Token или ссылка blank.html") },
                            placeholder = { Text("vk1.a... или https://oauth.vk.com/blank.html#access_token=...") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        val clipText = clipboardManager.getText()?.text
                                        if (!clipText.isNullOrEmpty()) {
                                            tokenInput = clipText
                                            tokenErrorMessage = null
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
                                }
                            },
                            isError = tokenErrorMessage != null,
                            supportingText = {
                                if (tokenErrorMessage != null) {
                                    Text(tokenErrorMessage!!, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        )

                        Button(
                            onClick = {
                                if (tokenInput.isBlank()) {
                                    tokenErrorMessage = "Пожалуйста, вставьте токен"
                                    return@Button
                                }
                                isCheckingToken = true
                                tokenErrorMessage = null
                                coroutineScope.launch {
                                    val success = apiClient.validateAndSaveToken(tokenInput)
                                    isCheckingToken = false
                                    if (success) {
                                        onSuccess()
                                    } else {
                                        tokenErrorMessage = "Не удалось авторизоваться. Проверьте правильность токена."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !isCheckingToken,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppleRed)
                        ) {
                            if (isCheckingToken) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text("Войти по токену", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                        // Инструкция для получения токена в браузере
                        Text(
                            text = "Как получить токен в 1 клик:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "1. Нажмите кнопку ниже — откроется официальная страница VK в вашем браузере.\n2. Подтвердите вход (если уже авторизованы в браузере — в 1 клик).\n3. Скопируйте адрес из адресной строки браузера и вставьте в поле выше.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(VkAuthManager.getOAuthUrl()))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Получить токен через браузер")
                        }
                    }
                }
            }
        }
    }
}
