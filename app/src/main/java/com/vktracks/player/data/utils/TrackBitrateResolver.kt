package com.vktracks.player.data.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TrackBitrateResolver {
    private val cache = ConcurrentHashMap<String, String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolveBitrate(context: Context, track: Track): String = withContext(Dispatchers.IO) {
        val cached = cache[track.fullId]
        if (cached != null) return@withContext cached

        // 1. Локальный трек
        if (track.isLocal && !track.localFilePath.isNullOrBlank()) {
            try {
                val file = File(track.localFilePath)
                if (file.exists() && file.length() > 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
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
                    if (track.duration > 0) {
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
            val fallback = "320 кбит/с • HLS"
            cache[track.fullId] = fallback
            return@withContext fallback
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

        val res = if (track.streamUrl.isNotEmpty()) "320 кбит/с • MP3" else "Неизвестно"
        cache[track.fullId] = res
        res
    }
}
