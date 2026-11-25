package com.vktracks.player.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ItunesApiClient(
    private val httpClient: OkHttpClient = NetworkModule.fastMetadataClient
) {
    suspend fun fetchTrackCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (TrackMetadataMatcher.isGeneric(artist, title)) return@withContext null

        val cleanArtist = TrackMetadataMatcher.extractMainArtist(artist).lowercase().trim()
        val cleanTitle = TrackMetadataMatcher.cleanTrackTitle(title).lowercase().trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        val query = "$cleanArtist $cleanTitle".trim()
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            .build()

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

                val trackArtists = TrackMetadataMatcher.splitArtists(artist)

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itunesArtist = item.optString("artistName", "").trim()
                    val itunesTrack = item.optString("trackName", "").trim()
                    val itunesCollection = item.optString("collectionName", "").trim()

                    val fullCombinedText = "$itunesArtist $itunesTrack $itunesCollection".lowercase()
                    if (TrackMetadataMatcher.containsJunkKeyword(fullCombinedText)) continue
                    if (!TrackMetadataMatcher.isArtistExactMatch(itunesArtist, cleanArtist, trackArtists)) continue
                    if (!TrackMetadataMatcher.isTitleExactMatch(itunesTrack, cleanTitle)) continue

                    val rawArtwork = item.optString("artworkUrl100", "").takeIf { it.isNotEmpty() }
                        ?: item.optString("artworkUrl60", "").takeIf { it.isNotEmpty() }
                    if (!rawArtwork.isNullOrEmpty()) {
                        return@withContext rawArtwork
                            .replace("100x100bb.jpg", "600x600bb.jpg")
                            .replace("60x60bb.jpg", "600x600bb.jpg")
                            .replace("100x100", "600x600")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ItunesApiClient", "Failed to fetch iTunes artwork for '$artist - $title'", e)
        }
        null
    }

    suspend fun fetchArtistCover(artist: String): String? = withContext(Dispatchers.IO) {
        if (TrackMetadataMatcher.isGeneric(artist, "")) return@withContext null
        val cleanArtist = TrackMetadataMatcher.extractMainArtist(artist).trim()
        if (cleanArtist.isBlank()) return@withContext null

        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", cleanArtist)
            .addQueryParameter("entity", "album")
            .addQueryParameter("limit", "5")
            .build()

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

                val normClean = TrackMetadataMatcher.normalizeArtistName(cleanArtist)

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itunesArtist = item.optString("artistName", "").trim()
                    val itunesCollection = item.optString("collectionName", "").trim()

                    val normItunes = TrackMetadataMatcher.normalizeArtistName(itunesArtist)
                    val fullCombinedText = "$itunesArtist $itunesCollection".lowercase()

                    if (TrackMetadataMatcher.containsJunkKeyword(fullCombinedText)) continue
                    if (normItunes != normClean) continue

                    val rawArtwork = item.optString("artworkUrl100", "").takeIf { it.isNotEmpty() }
                        ?: item.optString("artworkUrl60", "").takeIf { it.isNotEmpty() }
                    if (!rawArtwork.isNullOrEmpty()) {
                        return@withContext rawArtwork
                            .replace("100x100bb.jpg", "600x600bb.jpg")
                            .replace("60x60bb.jpg", "600x600bb.jpg")
                            .replace("100x100", "600x600")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ItunesApiClient", "Failed to fetch iTunes artist cover for '$artist'", e)
        }
        null
    }
}
