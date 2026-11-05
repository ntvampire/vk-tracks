package com.vktracks.player.data.cover

import com.vktracks.player.data.local.CoverCacheDao
import com.vktracks.player.data.local.CoverCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

class CoverArtResolver(
    private val coverCacheDao: CoverCacheDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun getCachedCover(rawArtist: String, rawTitle: String): String? {
        val cleanArtist = sanitizeArtist(rawArtist)
        val cleanTitle = sanitizeTitle(rawTitle)
        val cacheKey = "$cleanArtist - $cleanTitle".lowercase()
        return try {
            coverCacheDao.getCover(cacheKey)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveCover(rawArtist: String, rawTitle: String): String? = withContext(Dispatchers.IO) {
        val cleanArtist = sanitizeArtist(rawArtist)
        val cleanTitle = sanitizeTitle(rawTitle)
        val cacheKey = "$cleanArtist - $cleanTitle".lowercase()

        // 1. Проверяем локальный кэш Room
        try {
            val cached = coverCacheDao.getCover(cacheKey)
            if (!cached.isNullOrEmpty()) {
                return@withContext cached
            }
        } catch (_: Exception) {}

        // 2. Ищем в iTunes API с проверкой соответствия
        val itunesCover = searchItunes(cleanArtist, cleanTitle)
        if (!itunesCover.isNullOrEmpty()) {
            saveToCache(cacheKey, itunesCover)
            return@withContext itunesCover
        }

        // 3. Fallback: ищем в Deezer API (отличная база по русскоязычным трекам и инди)
        val deezerCover = searchDeezer(cleanArtist, cleanTitle)
        if (!deezerCover.isNullOrEmpty()) {
            saveToCache(cacheKey, deezerCover)
            return@withContext deezerCover
        }

        null
    }

    private suspend fun searchItunes(artist: String, title: String): String? {
        val query = "$artist $title"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=10"

        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val resArtist = item.optString("artistName")
                    val resTrack = item.optString("trackName")
                    val artworkUrl = item.optString("artworkUrl100")

                    if (isMatch(artist, resArtist) && isMatch(title, resTrack)) {
                        // Меняем размер обложки на качественный 600x600 или 1000x1000
                        return artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun searchDeezer(artist: String, title: String): String? {
        val query = "artist:\"$artist\" track:\"$title\""
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.deezer.com/search?q=$encoded&limit=5"

        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return null

                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val resArtist = item.optJSONObject("artist")?.optString("name") ?: ""
                    val resTitle = item.optString("title")
                    val album = item.optJSONObject("album")
                    val cover = album?.optString("cover_xl") ?: album?.optString("cover_big")

                    if (isMatch(artist, resArtist) && isMatch(title, resTitle)) {
                        if (!cover.isNullOrEmpty()) return cover
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun saveToCache(key: String, url: String) {
        try {
            coverCacheDao.insertCover(CoverCacheEntity(queryKey = key, coverUrl = url))
        } catch (_: Exception) {}
    }

    // Очистка названия трека от шума
    fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("""\(feat\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(ft\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[feat\..*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[ft\..*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[vk\.com/.*?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(prod\..*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(slowed.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(reverb.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(speed.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(bass.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(official.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\(video.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\d+[\s.-]+"""), "") // Удаление "01 - " в начале
            .trim()
    }

    // Выделение главного артиста
    fun sanitizeArtist(artist: String): String {
        return artist
            .split(Regex("[,&/]|\\bfeat\\b|\\bft\\b", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: artist.trim()
    }

    // Проверка сходства двух строк (защита от ложных совпадений)
    private fun isMatch(expected: String, actual: String): Boolean {
        val s1 = normalize(expected)
        val s2 = normalize(actual)
        if (s1.isEmpty() || s2.isEmpty()) return false
        if (s1 == s2 || s1.contains(s2) || s2.contains(s1)) return true

        // Расчёт расстояния Левенштейна для нечёткого сравнения
        val distance = levenshtein(s1, s2)
        val maxLen = max(s1.length, s2.length)
        val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())
        return similarity >= 0.70
    }

    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("""[^a-z0-9а-яё]"""), "")
    }

    private fun levenshtein(s1: CharSequence, s2: CharSequence): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else minOf(prev, dp[j], dp[j - 1]) + 1
                prev = temp
            }
        }
        return dp[s2.length]
    }
}
