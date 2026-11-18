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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                    // Вход по Access Token или ссылке из браузера
                    val scrollState = rememberScrollState()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .imePadding()
                            .navigationBarsPadding()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Шаг 1: Получение ссылки через браузер
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Шаг 1. Получите ссылку в браузере",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "1. Нажмите кнопку ниже — откроется официальная страница входа VK.\n2. Подтвердите вход в браузере (в 1 клик).\n3. Скопируйте адрес из адресной строки браузера (blank.html).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
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
                                    Text("Открыть страницу входа в браузере")
                                }
                            }
                        }

                        // Шаг 2: Вставка и вход
                        Text(
                            text = "Шаг 2. Завершите вход",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                tokenErrorMessage = null
                            },
                            label = { Text("Ссылка blank.html или токен") },
                            placeholder = { Text("https://oauth.vk.com/blank.html#... или vk1.a...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            maxLines = 1,
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                if (tokenInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            tokenInput = ""
                                            tokenErrorMessage = null
                                        }
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Очистить")
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            val clipText = clipboardManager.getText()?.text?.trim()
                                            if (!clipText.isNullOrEmpty()) {
                                                tokenInput = clipText
                                                tokenErrorMessage = null
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
                                    }
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
                                val targetInput = if (tokenInput.isNotBlank()) {
                                    tokenInput.trim()
                                } else {
                                    val clipText = clipboardManager.getText()?.text?.trim()
                                    if (clipText.isNullOrBlank()) {
                                        tokenErrorMessage = "Буфер обмена пуст. Скопируйте ссылку в браузере."
                                        return@Button
                                    }
                                    tokenInput = clipText
                                    clipText
                                }

                                isCheckingToken = true
                                tokenErrorMessage = null
                                coroutineScope.launch {
                                    val success = apiClient.validateAndSaveToken(targetInput)
                                    isCheckingToken = false
                                    if (success) {
                                        onSuccess()
                                    } else {
                                        tokenErrorMessage = "Не удалось авторизоваться. Проверьте правильность ссылки или токена."
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
                            } else if (tokenInput.isBlank()) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Вставить из буфера и войти", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Войти по токену", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
