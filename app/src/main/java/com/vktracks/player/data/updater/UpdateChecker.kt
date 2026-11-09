package com.vktracks.player.data.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.vktracks.player.BuildConfig
import com.vktracks.player.data.model.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun checkForUpdates(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .header("User-Agent", userAgent)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val remoteCode = json.optInt("versionCode")
                val remoteName = json.optString("versionName")
                val downloadUrl = json.optString("downloadUrl")
                val mirrorUrl = json.optString("mirrorUrl").takeIf { it.isNotEmpty() }
                val changelog = json.optString("changelog")

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    AppUpdateInfo(
                        versionCode = remoteCode,
                        versionName = remoteName,
                        downloadUrl = downloadUrl,
                        mirrorUrl = mirrorUrl,
                        changelog = changelog
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("UpdateChecker", "Failed to check for updates", e)
            null
        }
    }

    suspend fun downloadAndInstallApk(updateInfo: AppUpdateInfo, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDir, "VK-Tracks-${updateInfo.versionName}.apk")
        if (apkFile.exists()) apkFile.delete()

        val urlsToTry = listOfNotNull(
            updateInfo.mirrorUrl.takeIf { !it.isNullOrBlank() },
            updateInfo.downloadUrl.takeIf { !it.isNullOrBlank() }
        )

        var lastException: Exception? = null

        for (url in urlsToTry) {
            try {
                Log.d("UpdateChecker", "Downloading update from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    .header("Accept-Encoding", "identity")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code} from $url")
                    val body = response.body ?: throw Exception("Empty APK body")
                    val totalBytes = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalBytes > 0) {
                                    val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    onProgress(percent)
                                } else {
                                    // Приблизительный процент на базе среднего размера APK (~16MB)
                                    val estPercent = ((totalRead * 100) / 16_000_000L).toInt().coerceIn(1, 99)
                                    onProgress(estPercent)
                                }
                            }
                            output.flush()
                        }
                    }
                }

                if (apkFile.exists() && apkFile.length() > 1024 * 1024) {
                    onProgress(100)
                    Log.d("UpdateChecker", "APK downloaded successfully (${apkFile.length()} bytes)")

                    // Запуск системного установщика пакетов
                    withContext(Dispatchers.Main) {
                        installApk(apkFile)
                    }
                    return@withContext Result.success(Unit)
                } else {
                    throw Exception("Downloaded APK file is too small or missing")
                }
            } catch (e: Exception) {
                Log.w("UpdateChecker", "Failed download from $url, trying next...", e)
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("Не удалось загрузить APK файл"))
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(settingsIntent)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            // Явно выдаём права на чтение URI установщику пакетов
            val resInfoList = context.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                context.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Failed to start install intent", e)
        }
    }

    companion object {
        const val UPDATE_URL = "https://ntvampire.github.io/vk-tracks/update.json"
    }
}
