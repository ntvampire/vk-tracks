package com.vktracks.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class VKTracksApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
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
