package com.vktracks.player.data.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.vktracks.player.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UpdateDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VKTracks:UpdateDownloadWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val versionName = intent?.getStringExtra(EXTRA_VERSION_NAME) ?: "1.0.0"
        val downloadUrl = intent?.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
        val mirrorUrl = intent?.getStringExtra(EXTRA_MIRROR_URL)

        if (downloadUrl.isBlank() && mirrorUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(versionName)
        startDownload(versionName, downloadUrl, mirrorUrl)

        return START_NOT_STICKY
    }

    private fun startInForeground(versionName: String) {
        val notification = buildProgressNotification(versionName, 0, "Подключение к серверу...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startDownload(versionName: String, downloadUrl: String, mirrorUrl: String?) {
        wakeLock?.acquire(15 * 60 * 1000L) // Максимум 15 минут
        _progressFlow.value = 0

        serviceScope.launch {
            val updateDir = File(externalCacheDir ?: cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "VK-Tracks-$versionName.apk")
            if (apkFile.exists()) apkFile.delete()

            val urlsToTry = listOfNotNull(
                mirrorUrl?.takeIf { it.isNotBlank() },
                downloadUrl.takeIf { it.isNotBlank() }
            )

            var success = false
            var lastError: Exception? = null

            for (url in urlsToTry) {
                try {
                    Log.d("UpdateService", "Starting download from: $url")
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                        .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                        .header("Accept-Encoding", "identity")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body ?: throw Exception("Empty body")
                        val totalBytes = body.contentLength()

                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var totalRead = 0L
                                var lastNotifTime = 0L

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead

                                    val percent = if (totalBytes > 0) {
                                        ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    } else {
                                        ((totalRead * 100) / 16_000_000L).toInt().coerceIn(1, 99)
                                    }

                                    _progressFlow.value = percent

                                    val now = System.currentTimeMillis()
                                    if (now - lastNotifTime > 600 || percent == 100) {
                                        lastNotifTime = now
                                        val notif = buildProgressNotification(
                                            versionName,
                                            percent,
                                            "Загрузка: $percent%"
                                        )
                                        notificationManager.notify(NOTIFICATION_ID, notif)
                                    }
                                }
                                output.flush()
                            }
                        }
                    }

                    if (apkFile.exists() && apkFile.length() > 1024 * 1024) {
                        success = true
                        break
                    } else {
                        throw Exception("File size too small: ${apkFile.length()} bytes")
                    }
                } catch (e: Exception) {
                    Log.w("UpdateService", "Download failed from $url: ${e.message}")
                    lastError = e
                }
            }

            try {
                wakeLock?.release()
            } catch (_: Exception) {}

            if (success) {
                _progressFlow.value = 100
                showCompletedNotification(versionName, apkFile)
                withContext(Dispatchers.Main) {
                    triggerInstall(apkFile)
                }
            } else {
                _progressFlow.value = -1
                showErrorNotification(downloadUrl, lastError?.message ?: "Сбой загрузки")
            }

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun buildProgressNotification(versionName: String, progress: Int, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Обновление VK Tracks v$versionName")
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showCompletedNotification(versionName: String, apkFile: File) {
        val installIntent = getInstallIntent(apkFile)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Обновление v$versionName готово")
            .setContentText("Нажмите для завершения установки")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(downloadUrl: String, errorMsg: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            browserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ошибка обновления")
            .setContentText("$errorMsg. Нажмите для загрузки через браузер")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun triggerInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(settingsIntent)
                return
            }
        }
        try {
            startActivity(getInstallIntent(apkFile))
        } catch (e: Exception) {
            Log.e("UpdateService", "Failed to launch package installer", e)
        }
    }

    private fun getInstallIntent(apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновление приложения",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс скачивания обновления VK Tracks"
                setShowBadge(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "update_download_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "ACTION_START_UPDATE_DOWNLOAD"
        const val EXTRA_VERSION_NAME = "EXTRA_VERSION_NAME"
        const val EXTRA_DOWNLOAD_URL = "EXTRA_DOWNLOAD_URL"
        const val EXTRA_MIRROR_URL = "EXTRA_MIRROR_URL"

        private val _progressFlow = MutableStateFlow(-1)
        val progressFlow = _progressFlow.asStateFlow()

        fun start(context: Context, versionName: String, downloadUrl: String, mirrorUrl: String? = null) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VERSION_NAME, versionName)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_MIRROR_URL, mirrorUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
