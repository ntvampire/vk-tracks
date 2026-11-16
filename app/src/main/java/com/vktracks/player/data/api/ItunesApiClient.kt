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
        val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=10"

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

                val trackArtists = splitArtists(artist)

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itunesArtist = item.optString("artistName", "").trim()
                    val itunesTrack = item.optString("trackName", "").trim()
                    val itunesCollection = item.optString("collectionName", "").trim()

                    val fullCombinedText = "$itunesArtist $itunesTrack $itunesCollection".lowercase()

                    // 1. Исключаем караоке, трибьюты, кавер-сборники (Sunfly, All-Baroque Box и т.д.)
                    val containsJunk = JUNK_KEYWORDS.any { fullCombinedText.contains(it) }
                    if (containsJunk) continue

                    // 2. Строгая проверка исполнителя: точное совпадение имени или одного из участников дуэта
                    val artistMatches = isArtistExactMatch(itunesArtist, cleanArtist, trackArtists)
                    if (!artistMatches) continue

                    // 3. Строгая проверка названия трека: точное совпадение или расширенная версия (Radio Edit, Club Mix)
                    val titleMatches = isTitleExactMatch(itunesTrack, cleanTitle)
                    if (!titleMatches) continue

                    // 4. Извлечение студийной обложки высокого разрешения (600x600 px)
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

    /**
     * Поиск официальной обложки альбома/релиза артиста в Apple Music при отсутствии трека в iTunes.
     * Требует строгого 100% совпадения имени артиста.
     */
    suspend fun fetchArtistCover(artist: String): String? = withContext(Dispatchers.IO) {
        if (isGeneric(artist, "")) return@withContext null
        val cleanArtist = extractMainArtist(artist).trim()
        if (cleanArtist.isBlank()) return@withContext null

        val encodedQuery = try {
            URLEncoder.encode(cleanArtist, "UTF-8")
        } catch (_: Exception) {
            cleanArtist
        }
        val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=album&limit=5"

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

                val normClean = normalizeArtistName(cleanArtist)

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itunesArtist = item.optString("artistName", "").trim()
                    val itunesCollection = item.optString("collectionName", "").trim()

                    val normItunes = normalizeArtistName(itunesArtist)
                    val fullCombinedText = "$itunesArtist $itunesCollection".lowercase()

                    if (JUNK_KEYWORDS.any { fullCombinedText.contains(it) }) continue

                    // Строгое совпадение имени артиста
                    if (normItunes != normClean) continue

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
            Log.w("ItunesApiClient", "Failed to fetch iTunes artist cover for '$artist'", e)
        }
        null
    }

    fun splitArtists(artist: String): List<String> {
        return artist.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE))
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() }
    }

    private fun isArtistExactMatch(rawItunesArtist: String, cleanArtist: String, trackArtists: List<String>): Boolean {
        val normItunes = normalizeArtistName(rawItunesArtist)
        val normClean = normalizeArtistName(cleanArtist)
        if (normItunes == normClean || trackArtists.contains(normItunes)) {
            return true
        }
        val itunesTokens = splitArtists(rawItunesArtist)
        for (token in itunesTokens) {
            if (token == normClean || trackArtists.contains(token)) {
                return true
            }
        }
        return false
    }

    private fun isTitleExactMatch(rawItunesTitle: String, rawCleanTitle: String): Boolean {
        val normItunes = normalizeTitle(rawItunesTitle)
        val normClean = normalizeTitle(rawCleanTitle)
        if (normItunes.isEmpty() || normClean.isEmpty()) return false
        if (normItunes == normClean) return true
        if (normItunes.startsWith("$normClean ") || normItunes.startsWith("$normClean(")) {
            return true
        }
        if (normClean.startsWith("$normItunes ") || normClean.startsWith("$normItunes(")) {
            return true
        }
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
