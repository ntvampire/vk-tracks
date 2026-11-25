package com.vktracks.player.data.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.vktracks.player.data.api.NetworkModule
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

// Санитаризация имен файлов (Murglar & VKX: замена недопустимых символов на читаемые аналоги)
object FileSanitizer {
    private val REPLACEMENTS = mapOf(
        '"' to '\'',
        '<' to '[',
        '>' to ']',
        ':' to '-',
        '*' to '+',
        '?' to '_',
        '/' to '_',
        '\\' to '_',
        '|' to '_'
    )

    private const val MAX_NAME_LENGTH = 100

    fun sanitize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.trim()) {
            val rep = REPLACEMENTS[ch]
            if (rep != null) {
                sb.append(rep)
            } else if (ch.code >= 32) {
                sb.append(ch)
            }
        }
        var cleaned = sb.toString().trim().trim('.')
        if (cleaned == "." || cleaned == ".." || cleaned.isEmpty()) {
            cleaned = "Unknown"
        }
        if (cleaned.length > MAX_NAME_LENGTH) {
            cleaned = cleaned.take(MAX_NAME_LENGTH).trim()
        }
        return cleaned.ifEmpty { "Unknown" }
    }

    fun sanitizeSubfolder(subfolder: String?): String? {
        val raw = subfolder?.trim() ?: return null
        if (raw.isEmpty() || raw == "." || raw == "..") return null
        if (raw.contains("..") || raw.contains("/") || raw.contains("\\")) {
            val parts = raw.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.any { it == "." || it == ".." }) return null
        }
        val cleaned = sanitize(raw).trim('.', '_', ' ')
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
}

class TrackDownloader(
    private val context: Context,
    private val database: AppDatabase
) {
    private val client get() = NetworkModule.downloadClient

    private val _downloadProgress = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    fun getProgressFlow(fullId: String): StateFlow<Int> {
        return _downloadProgress.getOrPut(fullId) { MutableStateFlow(-1) }.asStateFlow()
    }

    fun buildTargetPaths(track: Track, subfolder: String?): Pair<String?, String> {
        val safeArtist = FileSanitizer.sanitize(track.artist)
        val safeTitle = FileSanitizer.sanitize(track.title)
        val fileName = "$safeArtist - $safeTitle.mp3"
        return Pair(FileSanitizer.sanitizeSubfolder(subfolder), fileName)
    }

    suspend fun downloadTrack(track: Track, subfolder: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val flow = _downloadProgress.getOrPut(track.fullId) { MutableStateFlow(0) }
        flow.value = 0

        val tempFile = File(context.cacheDir, "temp_dl_${track.fullId}_${System.nanoTime()}.tmp")

        try {
            val (effectiveSubfolder, fileName) = buildTargetPaths(track, subfolder)

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
            val coverBytes = if (!coverUrlToUse.isNullOrEmpty()) {
                downloadBytes(coverUrlToUse)
            } else {
                null
            }

            // 3. Вшиваем ID3v2 теги
            Id3Tagger.tagMp3(tempFile, track.title, track.artist, track.albumTitle, coverBytes)

            // 4. Перемещаем в целевое хранилище (MediaStore content:// на Android 10+ или файловую систему)
            val savedPath = saveToStorage(tempFile, fileName, effectiveSubfolder, track)

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
                localFilePath = savedPath,
                isDownloaded = true
            )
            database.trackDao().insertOrUpdate(entity)

            flow.value = 100
            Result.success(savedPath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            flow.value = -1
            Result.failure(e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
            if (_downloadProgress.size > 60) {
                _downloadProgress.entries.removeIf { (k, v) -> k != track.fullId && (v.value == -1 || v.value == 100) }
            }
        }
    }

    private fun saveToStorage(
        tempFile: File,
        fileName: String,
        subfolder: String?,
        track: Track
    ): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val customPath = prefs.getString("custom_download_path", null)

        // Пользовательская папка сохранения
        if (!customPath.isNullOrBlank()) {
            val customDir = File(customPath)
            if (customDir.exists() && customDir.canWrite()) {
                val safeSub = FileSanitizer.sanitizeSubfolder(subfolder)
                val targetDir = if (safeSub != null) {
                    File(customDir, safeSub).apply { if (!exists()) mkdirs() }
                } else customDir
                val targetFile = File(targetDir, fileName)
                if (targetFile.exists()) targetFile.delete()
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("audio/mpeg"), null)
                return targetFile.absolutePath
            }
        }

        // Стандартное сохранение на Android 10+ (API 29+) через официальный MediaStore API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val safeSub = FileSanitizer.sanitizeSubfolder(subfolder)
            val relativePath = if (safeSub != null) {
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

            // Удаляем старую запись только в целевой папке (точное совпадение без затрагивания подпапок)
            try {
                val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} IN (?, ?)"
                val selectionArgs = arrayOf(fileName, relativePath, "$relativePath/")
                resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
            } catch (_: Exception) {}

            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Не удалось создать запись MediaStore для $fileName")

            try {
                resolver.openOutputStream(uri).use { output ->
                    if (output == null) throw IOException("Не удалось открыть выходной поток MediaStore")
                    tempFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            } catch (e: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {}
                throw e
            }

            tempFile.delete()
            return uri.toString()
        } else {
            // Android 9 и ниже: прямая запись в общедоступную папку Music
            val baseMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val safeSub = FileSanitizer.sanitizeSubfolder(subfolder)
            val targetDir = if (safeSub != null) {
                File(File(baseMusic, "VK Tracks"), safeSub).apply { if (!exists()) mkdirs() }
            } else {
                File(baseMusic, "VK Tracks").apply { if (!exists()) mkdirs() }
            }
            val targetFile = File(targetDir, fileName)
            if (targetFile.exists()) targetFile.delete()
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("audio/mpeg"), null)
            return targetFile.absolutePath
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
            var deletedOnStorage = true
            if (!path.isNullOrBlank()) {
                if (path.startsWith("content://")) {
                    deletedOnStorage = try {
                        val uri = Uri.parse(path)
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) true else {
                            // Проверяем, существует ли ещё URI
                            try {
                                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { false } ?: true
                            } catch (_: Exception) {
                                true
                            }
                        }
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    val file = File(path)
                    if (file.exists()) {
                        deletedOnStorage = file.delete()
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
            if (deletedOnStorage) {
                database.trackDao().removeDownload(track.fullId)
                _downloadProgress.remove(track.fullId)
            }
            deletedOnStorage
        } catch (_: Exception) {
            false
        }
    }

    private data class HlsVariant(val bandwidth: Long, val url: String)

    private fun resolveHlsUrl(baseUri: URI, line: String): String {
        return try {
            if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                line
            } else {
                baseUri.resolve(line).toString()
            }
        } catch (_: Exception) {
            baseUri.toString().substringBeforeLast("/") + "/" + line
        }
    }

    private fun fetchMediaPlaylist(m3u8Url: String, depth: Int = 0): Pair<URI, List<String>> {
        if (depth > 5) throw IOException("Превышена глубина вложенности HLS плейлиста")

        val playlistRequest = Request.Builder().url(m3u8Url).build()
        val playlistText = client.newCall(playlistRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HLS playlist HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty HLS playlist")
        }

        val baseUri = URI(m3u8Url)
        val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.any { it.startsWith("#EXT-X-KEY", ignoreCase = true) && !it.contains("METHOD=NONE", ignoreCase = true) }) {
            throw IOException("Зашифрованный HLS-поток (AES-128) не поддерживается для офлайн-сохранения")
        }

        // Murglar HlsBitrateEstimator: поиск мастер-плейлиста с несколькими потоками качества
        val variants = mutableListOf<HlsVariant>()
        var currentBandwidth: Long? = null

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val match = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)
                currentBandwidth = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } else if (!line.startsWith("#") && line.contains(".m3u8")) {
                variants.add(HlsVariant(currentBandwidth ?: 0L, resolveHlsUrl(baseUri, line)))
                currentBandwidth = null
            }
        }

        // Если обнаружены варианты битрейта, выбираем поток с максимальным качеством (320 kbps)
        if (variants.isNotEmpty()) {
            val bestVariant = variants.maxByOrNull { it.bandwidth } ?: variants.first()
            return fetchMediaPlaylist(bestVariant.url, depth + 1)
        }

        val childM3u8 = lines.firstOrNull { !it.startsWith("#") && it.contains(".m3u8") }
        if (childM3u8 != null) {
            return fetchMediaPlaylist(resolveHlsUrl(baseUri, childM3u8), depth + 1)
        }
        return Pair(baseUri, lines)
    }

    private fun downloadHlsStream(m3u8Url: String, target: File, onProgress: (Int) -> Unit) {
        val (baseUri, lines) = fetchMediaPlaylist(m3u8Url)
        val segmentUrls = mutableListOf<String>()

        for (line in lines) {
            if (!line.startsWith("#")) {
                segmentUrls.add(resolveHlsUrl(baseUri, line))
            }
        }

        if (segmentUrls.isEmpty()) {
            throw IOException("No audio segments found in HLS playlist")
        }

        FileOutputStream(target).use { output ->
            segmentUrls.forEachIndexed { index, segUrl ->
                val req = Request.Builder().url(segUrl).build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        throw IOException("Ошибка загрузки HLS-сегмента (${res.code})")
                    }
                    val body = res.body ?: throw IOException("Пустой ответ HLS-сегмента")
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
                val percent = (((index + 1) * 100) / segmentUrls.size).coerceIn(0, 99)
                onProgress(percent)
            }
            output.flush()
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val contentType = res.header("Content-Type")?.lowercase() ?: ""
                if (contentType.isNotEmpty() && !contentType.startsWith("image/") && !contentType.contains("octet-stream")) {
                    return null
                }
                val bytes = res.body?.bytes() ?: return null
                if (bytes.size < 12) return null
                val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
                val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
                val isWebp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
                if (isJpeg || isPng || isWebp) bytes else null
            }
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
