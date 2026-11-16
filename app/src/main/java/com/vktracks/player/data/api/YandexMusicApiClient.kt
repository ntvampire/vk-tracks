package com.vktracks.player.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class YandexMusicApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private val JUNK_KEYWORDS = listOf(
            "karaoke", "tribute", "cover version", "in the style of",
            "originally performed by", "sunfly", "backing track",
            "various artists", "разные артисты", "hits 20", "best of 20",
            "top 100", "soundtrack", "instrumental version", "piano version",
            "acoustic cover", "bbq", "bbq soundtrack", "baroque", "all-baroque",
            "compilation", "greatest hits", "100 hits", "party hits",
            "cover band", "tribute band", "studio group", "workout hits", "fitness"
        )

        private val GENERIC_ARTISTS = setOf(
            "unknown artist", "неизвестный исполнитель", "неизвестный", "unknown",
            "various artists", "разные артисты", "разные исполнители", ""
        )

        private val GENERIC_TITLES = setOf(
            "untitled", "без названия", "аудиозапись", "track", "audio", ""
        )

        fun isGeneric(artist: String, title: String): Boolean {
            val a = artist.trim().lowercase()
            val t = title.trim().lowercase()
            if (a in GENERIC_ARTISTS || t in GENERIC_TITLES) return true
            if (t.matches(Regex("^(track|audio|аудиозапись|untitled)?\\s*\\d+$", RegexOption.IGNORE_CASE))) return true
            return false
        }
    }

    suspend fun fetchTrackCover(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (isGeneric(artist, title)) return@withContext null

        val cleanArtist = extractMainArtist(artist).lowercase().trim()
        val cleanTitle = cleanTrackTitle(title).lowercase().trim()
        if (cleanArtist.isBlank() || cleanTitle.isBlank()) return@withContext null

        val query = "$cleanArtist $cleanTitle".trim()
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query
        }

        val url = "https://api.music.yandex.net/search?text=$encodedQuery&type=track&page=0"

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

                val trackArtists = splitArtists(artist)

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

                    // 1. Исключаем караоке и сторонние сборники
                    if (JUNK_KEYWORDS.any { fullCombinedText.contains(it) }) continue

                    // 2. Строгая проверка совпадения артиста
                    val artistMatches = yandexArtists.any { yArtist ->
                        isArtistExactMatch(yArtist, cleanArtist, trackArtists)
                    }
                    if (!artistMatches) continue

                    // 3. Строгая проверка названия
                    if (!isTitleExactMatch(trackTitle, cleanTitle)) continue

                    // 4. Извлечение студийной обложки высокого разрешения (600x600)
                    var coverUri = track.optString("coverUri", "").takeIf { it.isNotEmpty() }
                    if (coverUri.isNullOrEmpty()) {
                        val albums = track.optJSONArray("albums")
                        coverUri = albums?.optJSONObject(0)?.optString("coverUri", "")?.takeIf { it.isNotEmpty() }
                    }

                    if (!coverUri.isNullOrEmpty()) {
                        val fullUrl = "https://" + coverUri.replace("%%", "600x600")
                        return@withContext fullUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YandexMusicApiClient", "Failed to search cover for '$artist - $title'", e)
        }
        null
    }

    private fun splitArtists(artist: String): List<String> {
        return artist.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE))
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() }
    }

    private fun isArtistExactMatch(rawFoundArtist: String, cleanArtist: String, trackArtists: List<String>): Boolean {
        val normFound = normalizeArtistName(rawFoundArtist)
        val normClean = normalizeArtistName(cleanArtist)
        if (normFound == normClean || trackArtists.contains(normFound)) {
            return true
        }
        val foundTokens = splitArtists(rawFoundArtist)
        for (token in foundTokens) {
            if (token == normClean || trackArtists.contains(token)) {
                return true
            }
        }
        return false
    }

    private fun isTitleExactMatch(rawFoundTitle: String, rawCleanTitle: String): Boolean {
        val normFound = normalizeTitle(rawFoundTitle)
        val normClean = normalizeTitle(rawCleanTitle)
        if (normFound.isEmpty() || normClean.isEmpty()) return false
        if (normFound == normClean) return true
        if (normFound.startsWith("$normClean ") || normFound.startsWith("$normClean(")) return true
        if (normClean.startsWith("$normFound ") || normClean.startsWith("$normClean(")) return true
        return false
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

    fun normalizeArtistName(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-zа-я0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalizeTitle(title: String): String {
        return cleanTrackTitle(title).lowercase()
            .replace(Regex("[^a-zа-я0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
