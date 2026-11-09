package com.vktracks.player.data.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vktracks.player.data.cover.CoverArtResolver
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
import java.io.IOException
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

    suspend fun downloadTrack(track: Track, subfolder: String? = null): Result<File> = withContext(Dispatchers.IO) {
        val flow = _downloadProgress.getOrPut(track.fullId) { MutableStateFlow(0) }
        flow.value = 0

        val tempFile = File(context.cacheDir, "temp_dl_${track.fullId}.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            val safeArtist = track.artist.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val safeTitle = track.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val fileName = "$safeArtist - $safeTitle.mp3"

            // 1. Проверяем тип потока: прямой MP3 или HLS m3u8
            if (track.streamUrl.contains(".m3u8")) {
                downloadHlsStream(track.streamUrl, tempFile) { progress ->
                    flow.value = progress
                }
            } else {
                downloadDirectStream(track.streamUrl, tempFile) { progress ->
                    flow.value = progress
                }
            }

            // 2. Скачиваем обложку для прошивки в ID3
            val coverUrlToUse = track.coverUrl?.takeIf { it.isNotBlank() }
                ?: CoverArtResolver.instance?.resolveCover(track.artist, track.title, track.albumTitle)
            val coverBytes = if (!coverUrlToUse.isNullOrEmpty()) {
                downloadBytes(coverUrlToUse)
            } else {
                null
            }

            // 3. Вшиваем ID3v2 теги
            Id3Tagger.tagMp3(tempFile, track.title, track.artist, track.albumTitle, coverBytes)

            // 4. Перемещаем в целевое хранилище (MediaStore на Android 10+ или файловую систему)
            val savedFile = saveToStorage(tempFile, fileName, subfolder, track)

            // 5. Сохраняем в локальную базу данных Room
            val entity = TrackEntity(
                id = track.fullId,
                trackId = track.id,
                ownerId = track.ownerId,
                title = track.title,
                artist = track.artist,
                duration = track.duration,
                streamUrl = track.streamUrl,
                coverUrl = coverUrlToUse ?: track.coverUrl,
                albumTitle = track.albumTitle,
                isExplicit = track.isExplicit,
                lyricsId = track.lyricsId,
                accessKey = track.accessKey,
                isLiked = track.isLiked,
                localFilePath = savedFile.absolutePath,
                isDownloaded = true
            )
            database.trackDao().insertOrUpdate(entity)

            flow.value = 100
            Result.success(savedFile)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            flow.value = -1
            Result.failure(e)
        }
    }

    private fun saveToStorage(
        tempFile: File,
        fileName: String,
        subfolder: String?,
        track: Track
    ): File {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val customPath = prefs.getString("custom_download_path", null)

        // Пользовательская папка сохранения
        if (!customPath.isNullOrBlank()) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.canWrite()) {
                val targetDir = if (!subfolder.isNullOrBlank()) {
                    val safeSub = subfolder.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                    File(customDir, safeSub).apply { if (!exists()) mkdirs() }
                } else customDir
                val targetFile = File(targetDir, fileName)
                if (targetFile.exists()) targetFile.delete()
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("audio/mpeg"), null)
                return targetFile
            }
        }

        // Стандартное сохранение на Android 10+ (API 29+) через официальный MediaStore API в общедоступную папку Music/VK Tracks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = if (!subfolder.isNullOrBlank()) {
                val safeSub = subfolder.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                "Music/VK Tracks/$safeSub"
            } else {
                "Music/VK Tracks"
            }

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.TITLE, track.title)
                put(MediaStore.Audio.Media.ARTIST, track.artist)
                if (!track.albumTitle.isNullOrBlank()) {
                    put(MediaStore.Audio.Media.ALBUM, track.albumTitle)
                }
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            // Удаляем старую запись в MediaStore с таким именем и путем, если она уже существовала
            try {
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(fileName, "$relativePath%")
                resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
            } catch (_: Exception) {}

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Не удалось создать запись MediaStore для $fileName")

            resolver.openOutputStream(uri).use { output ->
                if (output == null) throw IOException("Не удалось открыть выходной поток MediaStore")
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            // Физический путь к сохранённому файлу: /storage/emulated/0/Music/VK Tracks/...
            val externalMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val subPart = relativePath.removePrefix("Music/").removePrefix("Music")
            val targetDir = if (subPart.isNotBlank()) File(externalMusic, subPart) else externalMusic
            val physicalFile = File(targetDir, fileName)

            tempFile.delete()

            // Оповещаем системный MediaScanner для немедленного отображения файла в проводниках
            MediaScannerConnection.scanFile(context, arrayOf(physicalFile.absolutePath), arrayOf("audio/mpeg"), null)

            return physicalFile
        } else {
            // Android 9 и ниже: прямая запись в общедоступную папку Music
            val baseMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val targetDir = if (!subfolder.isNullOrBlank()) {
                val safeSub = subfolder.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                File(File(baseMusic, "VK Tracks"), safeSub).apply { if (!exists()) mkdirs() }
            } else {
                File(baseMusic, "VK Tracks").apply { if (!exists()) mkdirs() }
            }
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) targetFile.delete()
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("audio/mpeg"), null)
            return targetFile
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

    suspend fun deleteDownloadedTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            val entity = database.trackDao().getTrackById(track.fullId)
            val path = entity?.localFilePath
            if (!path.isNullOrBlank()) {
                if (path.startsWith("content://")) {
                    try {
                        context.contentResolver.delete(Uri.parse(path), null, null)
                    } catch (_: Exception) {}
                } else {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val selection = "${MediaStore.Audio.Media.DATA} = ?"
                            context.contentResolver.delete(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                selection,
                                arrayOf(path)
                            )
                        } catch (_: Exception) {}
                    }
                    MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/mpeg"), null)
                }
            }
            database.trackDao().removeDownload(track.fullId)
            _downloadProgress.remove(track.fullId)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchMediaPlaylist(m3u8Url: String): Pair<String, List<String>> {
        val playlistRequest = Request.Builder().url(m3u8Url).build()
        val playlistText = client.newCall(playlistRequest).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HLS playlist HTTP ${response.code}")
            response.body?.string() ?: throw Exception("Empty HLS playlist")
        }

        val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
        val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val childM3u8 = lines.firstOrNull { !it.startsWith("#") && it.contains(".m3u8") }
        if (childM3u8 != null) {
            val childUrl = if (childM3u8.startsWith("http")) childM3u8 else baseUrl + childM3u8
            return fetchMediaPlaylist(childUrl)
        }
        return Pair(baseUrl, lines)
    }

    private fun downloadHlsStream(m3u8Url: String, target: File, onProgress: (Int) -> Unit) {
        val (baseUrl, lines) = fetchMediaPlaylist(m3u8Url)
        val segmentUrls = mutableListOf<String>()

        for (line in lines) {
            if (!line.startsWith("#")) {
                val segmentUrl = if (line.startsWith("http")) line else baseUrl + line
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

    fun getMusicDirectory(): File {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val customPath = prefs.getString("custom_download_path", null)
        if (!customPath.isNullOrBlank()) {
            val customDir = File(customPath)
            if (customDir.exists()) {
                return customDir
            }
        }
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(publicMusic, "VK Tracks")
    }

    fun setCustomDownloadPath(path: String?) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_download_path", path).apply()
    }

    fun getDownloadPathDisplay(): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val customPath = prefs.getString("custom_download_path", null)
        return if (!customPath.isNullOrBlank()) {
            customPath
        } else {
            "Music/VK Tracks"
        }
    }
}
