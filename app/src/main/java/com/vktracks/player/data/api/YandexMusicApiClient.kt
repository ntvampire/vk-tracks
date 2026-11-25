package com.vktracks.player.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class YandexMusicApiClient(
    private val httpClient: OkHttpClient = NetworkModule.fastMetadataClient
) {
    companion object {
        fun isGeneric(artist: String, title: String): Boolean = TrackMetadataMatcher.isGeneric(artist, title)
    }

    suspend fun fetchTrackCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (TrackMetadataMatcher.isGeneric(artist, title)) return@withContext null

        val cleanArtist = TrackMetadataMatcher.extractMainArtist(artist).lowercase().trim()
        val cleanTitle = TrackMetadataMatcher.cleanTrackTitle(title).lowercase().trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        val query = "$cleanArtist $cleanTitle".trim()
        val url = "https://api.music.yandex.net/search".toHttpUrl().newBuilder()
            .addQueryParameter("text", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("page", "0")
            .build()

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Yandex-Music-API")
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val resultObj = json.optJSONObject("result") ?: return@withContext null
                val tracksObj = resultObj.optJSONObject("tracks") ?: return@withContext null
                val results = tracksObj.optJSONArray("results") ?: return@withContext null

                val trackArtists = TrackMetadataMatcher.splitArtists(artist)

                for (i in 0 until results.length().coerceAtMost(5)) {
                    val track = results.optJSONObject(i) ?: continue
                    val trackTitle = track.optString("title", "").trim()
                    val artistsArray = track.optJSONArray("artists")

                    val yandexArtists = mutableListOf<String>()
                    if (artistsArray != null) {
                        for (j in 0 until artistsArray.length()) {
                            val aObj = artistsArray.optJSONObject(j) ?: continue
                            val aName = aObj.optString("name", "").trim()
                            if (aName.isNotEmpty()) yandexArtists.add(aName)
                        }
                    }

                    val combinedArtists = yandexArtists.joinToString(" ")
                    val fullCombinedText = "$combinedArtists $trackTitle".lowercase()

                    if (TrackMetadataMatcher.containsJunkKeyword(fullCombinedText)) continue

                    val artistMatches = yandexArtists.any { yArtist ->
                        TrackMetadataMatcher.isArtistExactMatch(yArtist, cleanArtist, trackArtists)
                    }
                    if (!artistMatches) continue

                    if (!TrackMetadataMatcher.isTitleExactMatch(trackTitle, cleanTitle)) continue

                    var coverUri = track.optString("coverUri", "").takeIf { it.isNotEmpty() }
                    if (coverUri.isNullOrEmpty()) {
                        val albums = track.optJSONArray("albums")
                        coverUri = albums?.optJSONObject(0)?.optString("coverUri", "")?.takeIf { it.isNotEmpty() }
                    }

                    if (!coverUri.isNullOrEmpty()) {
                        return@withContext "https://" + coverUri.replace("%%", "600x600")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YandexMusicApiClient", "Failed to search cover for '$artist - $title'", e)
        }
        null
    }
}
