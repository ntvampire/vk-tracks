package com.vktracks.player.data.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vktracks.player.data.api.NetworkModule
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object TrackBitrateResolver {
    private val cache = ConcurrentHashMap<String, String>()
    private val client get() = NetworkModule.fastMetadataClient

    suspend fun resolveBitrate(context: Context, track: Track): String = withContext(Dispatchers.IO) {
        val cached = cache[track.fullId]
        if (cached != null) return@withContext cached

        // 1. Локальный трек (content:// URI или файловый путь)
        if (track.isLocal && !track.localFilePath.isNullOrBlank()) {
            val localPath = track.localFilePath
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    if (localPath.startsWith("content://")) {
                        retriever.setDataSource(context, Uri.parse(localPath))
                    } else {
                        val file = File(localPath)
                        if (file.exists() && file.length() > 0) {
                            retriever.setDataSource(file.absolutePath)
                        }
                    }
                    val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    val br = brStr?.toIntOrNull()?.div(1000)
                    if (br != null && br > 0) {
                        val res = "$br кбит/с • Локальный MP3"
                        cache[track.fullId] = res
                        return@withContext res
                    }
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
                if (!localPath.startsWith("content://") && track.duration > 0) {
                    val file = File(localPath)
                    if (file.exists() && file.length() > 0) {
                        val calcBr = ((file.length() * 8) / (track.duration * 1000L)).toInt()
                        if (calcBr in 32..400) {
                            val res = "$calcBr кбит/с • Локальный MP3"
                            cache[track.fullId] = res
                            return@withContext res
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. HLS m3u8 поток
        if (track.streamUrl.contains(".m3u8")) {
            try {
                val req = Request.Builder().url(track.streamUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string() ?: ""
                        val matches = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).findAll(text)
                        val maxBandwidth = matches.mapNotNull { it.groupValues[1].toLongOrNull() }.maxOrNull()
                        if (maxBandwidth != null && maxBandwidth > 0) {
                            val kbps = (maxBandwidth / 1000).toInt()
                            val res = "$kbps кбит/с • HLS (AAC/MP3)"
                            cache[track.fullId] = res
                            return@withContext res
                        }
                    }
                }
            } catch (_: Exception) {}
            return@withContext "HLS (AAC/MP3)"
        }

        // 3. Прямой MP3 поток
        if (track.streamUrl.isNotEmpty() && track.duration > 0) {
            try {
                val headReq = Request.Builder().url(track.streamUrl).head().build()
                client.newCall(headReq).execute().use { resp ->
                    val len = resp.body?.contentLength() ?: -1L
                    val headerLen = resp.header("Content-Length")?.toLongOrNull() ?: len
                    if (headerLen > 0) {
                        val kbps = ((headerLen * 8) / (track.duration * 1000L)).toInt()
                        val normalized = when (kbps) {
                            in 300..350 -> 320
                            in 240..270 -> 256
                            in 180..210 -> 192
                            in 120..140 -> 128
                            else -> kbps
                        }
                        val res = "$normalized кбит/с • MP3"
                        cache[track.fullId] = res
                        return@withContext res
                    }
                }
            } catch (_: Exception) {}
        }

        if (track.streamUrl.isNotEmpty()) "MP3 поток" else "Неизвестно"
    }
}
