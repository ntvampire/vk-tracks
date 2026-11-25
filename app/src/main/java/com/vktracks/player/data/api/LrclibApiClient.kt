package com.vktracks.player.data.api

import android.util.Log
import com.vktracks.player.data.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class LrclibResponse(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val instrumental: Boolean
) {
    fun toLyrics(): Lyrics = LrcParser.buildLyrics(plainLyrics, syncedLyrics, instrumental)
}

class LrclibApiClient(
    private val httpClient: OkHttpClient = NetworkModule.fastMetadataClient
) {
    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "VKTracks/1.0 (https://github.com/ntvampire/vk-tracks)"
    }

    suspend fun fetchLyrics(artist: String, title: String, durationSec: Int): LrclibResponse? = withContext(Dispatchers.IO) {
        if (TrackMetadataMatcher.isGeneric(artist, title)) return@withContext null

        val cleanArtist = TrackMetadataMatcher.extractMainArtist(artist)
        val cleanTitle = TrackMetadataMatcher.cleanTrackTitle(title, stripEditionTags = true)
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        // 1. Точный поиск по исполнителю, названию и длительности (если известна)
        if (durationSec > 0) {
            val exactWithDuration = queryGet(cleanArtist, cleanTitle, durationSec)
            if (exactWithDuration != null) return@withContext exactWithDuration
        }

        // 2. Точный поиск без длительности (на случай расхождения метаданных на пару секунд)
        val exactWithoutDuration = queryGet(cleanArtist, cleanTitle, null)
        if (exactWithoutDuration != null) return@withContext exactWithoutDuration

        // 3. Полнотекстовый поиск в LRCLIB (резервный метод)
        return@withContext querySearch(cleanArtist, cleanTitle)
    }

    private fun queryGet(artist: String, title: String, durationSec: Int?): LrclibResponse? {
        try {
            val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist)
                .addQueryParameter("track_name", title)
            if (durationSec != null && durationSec > 0) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
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
            val url = "$BASE_URL/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()

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
}
