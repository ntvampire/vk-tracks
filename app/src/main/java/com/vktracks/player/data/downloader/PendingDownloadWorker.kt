package com.vktracks.player.data.downloader

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vktracks.player.data.api.VkApiClient
import com.vktracks.player.data.api.VkAuthManager
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.toTrack
import com.vktracks.player.data.settings.PlayerSettingsManager
import java.util.concurrent.TimeUnit

/**
 * Фоновый воркер WorkManager для автоматической докачки незавершённых треков
 * после восстановления сети (или при появлении Wi-Fi, если включён соответствующий фильтр).
 */
class PendingDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val pendingDao = database.pendingDownloadDao()
        val authManager = VkAuthManager(applicationContext)
        val apiClient = VkApiClient(authManager)
        val downloader = TrackDownloader(applicationContext, database)

        downloader.cleanupStalePartials()

        val pendingItems = pendingDao.getAllPending()
        if (pendingItems.isEmpty()) {
            return Result.success()
        }
        if (!authManager.isAuthorized()) {
            return Result.retry()
        }

        var hasRetryableFailure = false

        for (item in pendingItems) {
            if (isStopped) return Result.retry()

            val existing = database.trackDao().getTrackById(item.id)
            if (existing?.isDownloaded == true) {
                downloader.deletePartialFile(item.id)
                pendingDao.deleteById(item.id)
                continue
            }

            var track = item.toTrack()
            // Если ссылки ещё нет — получаем актуальную перед стартом
            if (track.streamUrl.isEmpty()) {
                track = apiClient.resolveTrack(track)
            }

            if (track.isRestricted) {
                downloader.deletePartialFile(item.id)
                pendingDao.deleteById(item.id)
                continue
            }

            if (track.streamUrl.isEmpty()) {
                if (item.retryCount + 1 >= MAX_RETRIES) {
                    downloader.deletePartialFile(item.id)
                    pendingDao.deleteById(item.id)
                } else {
                    pendingDao.incrementRetry(item.id, "Empty streamUrl")
                    hasRetryableFailure = true
                }
                continue
            }

            var result = downloader.downloadTrack(track, item.subfolder)

            // При 401/403/404/410 ссылка могла устареть — перерезолвим и повторим сразу один раз
            val failure = result.exceptionOrNull()
            if (failure is DownloadHttpException && failure.code in EXPIRED_URL_CODES) {
                val refreshed = apiClient.resolveTrack(track.copy(streamUrl = ""))
                if (refreshed.isRestricted) {
                    downloader.deletePartialFile(item.id)
                    pendingDao.deleteById(item.id)
                    continue
                }
                if (refreshed.streamUrl.isNotEmpty()) {
                    track = refreshed
                    result = downloader.downloadTrack(track, item.subfolder)
                }
            }

            if (result.isSuccess) {
                pendingDao.deleteById(item.id)
                Log.d(TAG, "Auto-resumed download succeeded for ${item.id}")
            } else {
                val errMsg = result.exceptionOrNull()?.message ?: "Download failed"
                if (item.retryCount + 1 >= MAX_RETRIES) {
                    downloader.deletePartialFile(item.id)
                    pendingDao.deleteById(item.id)
                    Log.w(TAG, "Dropped pending download ${item.id} after $MAX_RETRIES retries: $errMsg")
                } else {
                    pendingDao.incrementRetry(item.id, errMsg)
                    hasRetryableFailure = true
                }
            }
        }

        return if (hasRetryableFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "PendingDownloadWorker"
        private const val WORK_NAME = "vk_tracks_pending_downloads"
        private const val MAX_RETRIES = 5
        private val EXPIRED_URL_CODES = setOf(401, 403, 404, 410)

        fun enqueue(context: Context, replace: Boolean = false) {
            val appCtx = context.applicationContext
            val wifiOnly = PlayerSettingsManager.getInstance(appCtx).isAutoResumeWifiOnly
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .build()

            val request = OneTimeWorkRequestBuilder<PendingDownloadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(appCtx).enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
