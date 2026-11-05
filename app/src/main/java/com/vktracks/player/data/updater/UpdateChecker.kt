package com.vktracks.player.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdates(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UPDATE_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val remoteCode = json.optInt("versionCode")
                val remoteName = json.optString("versionName")
                val downloadUrl = json.optString("downloadUrl")
                val changelog = json.optString("changelog")

                if (remoteCode > BuildConfig.VERSION_CODE) {
                    AppUpdateInfo(
                        versionCode = remoteCode,
                        versionName = remoteName,
                        downloadUrl = downloadUrl,
                        changelog = changelog
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallApk(updateInfo: AppUpdateInfo, onProgress: (Int) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "VK-Tracks-${updateInfo.versionName}.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder().url(updateInfo.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty APK body")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            // Запуск системного установщика пакетов
            withContext(Dispatchers.Main) {
                installApk(apkFile)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
            context.startActivity(installIntent)
        } catch (e: Exception) {
            android.util.Log.e("UpdateChecker", "Failed to start install intent", e)
        }
    }

    companion object {
        const val UPDATE_URL = "https://ntvampire.github.io/vk-tracks/update.json"
    }
}
