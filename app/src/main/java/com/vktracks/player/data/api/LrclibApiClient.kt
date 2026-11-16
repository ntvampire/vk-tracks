package com.vktracks.player.data.api

import android.util.Log
import com.vktracks.player.data.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LrclibResponse(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val instrumental: Boolean
) {
    fun toLyrics(): Lyrics {
        if (instrumental) {
            return Lyrics(plainText = emptyList(), lines = emptyList(), isInstrumental = true)
        }
        val syncedLines = LrcParser.parse(syncedLyrics)
        val plainList = plainLyrics?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return Lyrics(
            plainText = plainList,
            lines = syncedLines,
            isInstrumental = false
        )
    }
}

class LrclibApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "VKTracks/1.0 (https://github.com/mikhail/vktracks)"
    }

    suspend fun fetchLyrics(artist: String, title: String, durationSec: Int): LrclibResponse? = withContext(Dispatchers.IO) {
        if (YandexMusicApiClient.isGeneric(artist, title)) return@withContext null

        val cleanArtist = cleanArtistName(artist)
        val cleanTitle = cleanTrackTitle(title)
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        // 1. Точный поиск по исполнителю, названию и длительности (если известна)
        if (durationSec > 0) {
            val exactWithDuration = queryGet(cleanArtist, cleanTitle, durationSec)
            if (exactWithDuration != null) return@withContext exactWithDuration
        }

        // 2. Точный поиск без длительности (на случай расхождения метаданных на пару секунд)
        val exactWithoutDuration = queryGet(cleanArtist, cleanTitle, null)
        if (exactWithoutDuration != null) return@withContext exactWithoutDuration

        // 3. Fallback: полнотекстовый поиск по ключевым словам
        val searchResult = querySearch(cleanArtist, cleanTitle)
        if (searchResult != null) return@withContext searchResult

        null
    }

    private fun queryGet(artist: String, title: String, durationSec: Int?): LrclibResponse? {
        try {
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val url = if (durationSec != null && durationSec > 0) {
                "$BASE_URL/get?artist_name=$encArtist&track_name=$encTitle&duration=$durationSec"
            } else {
                "$BASE_URL/get?artist_name=$encArtist&track_name=$encTitle"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                return parseLrclibJson(JSONObject(body))
            }
        } catch (e: Exception) {
            Log.d("LrclibApiClient", "Get failed for $artist - $title: ${e.message}")
            return null
        }
    }

    private fun querySearch(artist: String, title: String): LrclibResponse? {
        try {
            val query = "$artist $title".trim()
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL/search?q=$encQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val jsonArray = JSONArray(body)
                if (jsonArray.length() == 0) return null

                var fallbackResponse: LrclibResponse? = null

                for (i in 0 until jsonArray.length().coerceAtMost(6)) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val resp = parseLrclibJson(item) ?: continue

                    // Приоритет отдаем синхронизированному тексту (караоке)
                    if (!resp.syncedLyrics.isNullOrBlank()) {
                        return resp
                    }
                    if (fallbackResponse == null) {
                        fallbackResponse = resp
                    }
                }
                return fallbackResponse
            }
        } catch (e: Exception) {
            Log.d("LrclibApiClient", "Search failed for $artist $title: ${e.message}")
            return null
        }
    }

    private fun parseLrclibJson(obj: JSONObject): LrclibResponse? {
        val instrumental = obj.optBoolean("instrumental", false)
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }

        if (!instrumental && synced == null && plain == null) {
            return null
        }

        return LrclibResponse(
            plainLyrics = plain,
            syncedLyrics = synced,
            instrumental = instrumental
        )
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: artist.trim()
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("\\[vk\\.com/.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((feat|ft|with).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(feat|ft|with).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((official\\s*(video|audio|music\\s*video)|audio|video|клип|премьера).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(official\\s*(video|audio|music\\s*video)|audio|video|клип|премьера).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((remastered|remaster|deluxe|bonus\\s*track).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(remastered|remaster|deluxe|bonus\\s*track).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[\\d{4}\\]"), "")
            .trim()
    }
}
