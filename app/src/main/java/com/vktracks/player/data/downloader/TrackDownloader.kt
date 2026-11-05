package com.vktracks.player.data.downloader

import android.content.Context
import android.os.Environment
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TrackDownloader(
    private val context: Context,
    private val database: AppDatabase
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _downloadProgress = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    fun getProgressFlow(fullId: String): StateFlow<Int> {
        return _downloadProgress.getOrPut(fullId) { MutableStateFlow(-1) }.asStateFlow()
    }

    suspend fun downloadTrack(track: Track): Result<File> = withContext(Dispatchers.IO) {
        val flow = _downloadProgress.getOrPut(track.fullId) { MutableStateFlow(0) }
        flow.value = 0

        try {
            val musicDir = getMusicDirectory()
            val safeArtist = track.artist.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val safeTitle = track.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val targetFile = File(musicDir, "$safeArtist - $safeTitle.mp3")

            val tempFile = File(context.cacheDir, "temp_dl_${track.fullId}.tmp")
            if (tempFile.exists()) tempFile.delete()

            // Проверяем тип потока: прямой MP3 или HLS m3u8
            if (track.streamUrl.contains(".m3u8")) {
                downloadHlsStream(track.streamUrl, tempFile) { progress ->
                    flow.value = progress
                }
            } else {
                downloadDirectStream(track.streamUrl, tempFile) { progress ->
                    flow.value = progress
                }
            }

            // Скачиваем обложку для прошивки в ID3
            val coverBytes = if (!track.coverUrl.isNullOrEmpty()) {
                downloadBytes(track.coverUrl)
            } else {
                null
            }

            // Вшиваем ID3v2 теги
            Id3Tagger.tagMp3(tempFile, track.title, track.artist, track.albumTitle, coverBytes)

            // Перемещаем в целевую папку
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)

            // Сохраняем в локальную базу
            val entity = TrackEntity(
                id = track.fullId,
                trackId = track.id,
                ownerId = track.ownerId,
                title = track.title,
                artist = track.artist,
                duration = track.duration,
                streamUrl = track.streamUrl,
                coverUrl = track.coverUrl,
                albumTitle = track.albumTitle,
                isExplicit = track.isExplicit,
                lyricsId = track.lyricsId,
                accessKey = track.accessKey,
                isLiked = track.isLiked,
                localFilePath = targetFile.absolutePath,
                isDownloaded = true
            )
            database.trackDao().insertOrUpdate(entity)

            flow.value = 100
            Result.success(targetFile)
        } catch (e: Exception) {
            flow.value = -1
            Result.failure(e)
        }
    }

    private fun downloadDirectStream(url: String, target: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("Empty body")
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 99)
                            onProgress(percent)
                        }
                    }
                }
            }
        }
    }

    private fun downloadHlsStream(m3u8Url: String, target: File, onProgress: (Int) -> Unit) {
        val playlistRequest = Request.Builder().url(m3u8Url).build()
        val playlistText = client.newCall(playlistRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HLS playlist HTTP ${response.code}")
            response.body?.string() ?: throw Exception("Empty HLS playlist")
        }

        val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
        val segmentUrls = mutableListOf<String>()

        for (line in playlistText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val segmentUrl = if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                segmentUrls.add(segmentUrl)
            }
        }

        if (segmentUrls.isEmpty()) {
            throw Exception("No audio segments found in HLS playlist")
        }

        FileOutputStream(target).use { output ->
            segmentUrls.forEachIndexed { index, segUrl ->
                val req = Request.Builder().url(segUrl).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        res.body?.byteStream()?.use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                val percent = (((index + 1) * 100) / segmentUrls.size).coerceIn(0, 99)
                onProgress(percent)
            }
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { it.body?.bytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun getMusicDirectory(): File {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(publicMusic, "VK Tracks")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.exists() && dir.canWrite()) dir else File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VK Tracks").apply { mkdirs() }
    }
}
