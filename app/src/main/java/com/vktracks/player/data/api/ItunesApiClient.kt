package com.vktracks.player.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ItunesApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchTrackCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        val cleanArtist = extractMainArtist(artist).lowercase().trim()
        val cleanTitle = cleanTrackTitle(title).lowercase().trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        val query = "$cleanArtist $cleanTitle".trim()
        val encodedQuery = withContext(Dispatchers.IO) {
            try {
                URLEncoder.encode(query, "UTF-8")
            } catch (_: Exception) {
                query
            }
        }
        val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=5"

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "VKTracks/1.0 (Android)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext null

                val trackArtists = artist.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE))
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itunesArtist = item.optString("artistName", "").trim().lowercase()
                    val itunesTrack = item.optString("trackName", "").trim().lowercase()
                    val itunesCollection = item.optString("collectionName", "").trim().lowercase()

                    // 1. Строгая проверка совпадения артиста
                    var artistMatches = false
                    if (itunesArtist == cleanArtist || trackArtists.contains(itunesArtist) || cleanArtist.contains(itunesArtist)) {
                        artistMatches = true
                    }
                    if (!artistMatches) {
                        for (ta in trackArtists) {
                            if (itunesArtist.contains(ta) || ta.contains(itunesArtist)) {
                                artistMatches = true
                                break
                            }
                        }
                    }
                    if (!artistMatches) continue

                    // 2. Строгая проверка совпадения названия трека
                    var titleMatches = false
                    if (cleanTitle.isNotEmpty() && (itunesTrack.contains(cleanTitle) || cleanTitle.contains(itunesTrack) ||
                        itunesCollection.contains(cleanTitle) || cleanTitle.contains(itunesCollection))) {
                        titleMatches = true
                    }
                    if (!titleMatches) continue

                    // 3. Извлечение студийной обложки высокого разрешения (600x600 px)
                    val rawArtwork = item.optString("artworkUrl100", "").takeIf { it.isNotEmpty() }
                        ?: item.optString("artworkUrl60", "").takeIf { it.isNotEmpty() }
                    if (!rawArtwork.isNullOrEmpty()) {
                        val hdArtwork = rawArtwork
                            .replace("100x100bb.jpg", "600x600bb.jpg")
                            .replace("60x60bb.jpg", "600x600bb.jpg")
                            .replace("100x100", "600x600")
                        return@withContext hdArtwork
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ItunesApiClient", "Failed to fetch iTunes artwork for '$artist - $title'", e)
        }
        null
    }

    fun extractMainArtist(artist: String): String {
        return artist.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: artist.trim()
    }

    fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("\\[vk\\.com/.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((feat|ft|with).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(feat|ft|with).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\[\\d{4}\\]"), "")
            .trim()
    }
}
