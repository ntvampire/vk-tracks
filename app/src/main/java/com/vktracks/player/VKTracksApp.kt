package com.vktracks.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class VKTracksApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val playbackChannel = NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "Воспроизведение музыки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением треков и экран блокировки"
                setShowBadge(false)
            }

            val downloadChannel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Загрузка треков",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс скачивания аудиофайлов"
            }

            notificationManager?.createNotificationChannel(playbackChannel)
            notificationManager?.createNotificationChannel(downloadChannel)
        }
    }

    companion object {
        lateinit var instance: VKTracksApp
            private set

        const val PLAYBACK_CHANNEL_ID = "vk_tracks_playback"
        const val DOWNLOAD_CHANNEL_ID = "vk_tracks_downloads"
    }
}
