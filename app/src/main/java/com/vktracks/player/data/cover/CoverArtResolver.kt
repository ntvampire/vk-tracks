package com.vktracks.player.data.cover

import com.vktracks.player.data.api.VkApiClient
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
    companion object {
        @Volatile
        var instance: CoverArtResolver? = null

        // Прекомпилированные регулярные выражения для максимальной производительности (0 аллокаций)
        private val REGEX_PARENS = Regex("""\([^)]*\)""")
        private val REGEX_BRACKETS = Regex("""\[[^\]]*\]""")
        private val REGEX_BRACES = Regex("""\{[^}]*\}""")
        private val REGEX_TRACK_NUM = Regex("""^\d+[\s.-]+""")
        private val REGEX_TAGS = Regex("""(?i)\b(feat|ft|prod|remix|mix|edit|deluxe|version|slowed|reverb|speed up|bass boosted|cover|instrumental|live|bonus)\b.*$""")
        private val REGEX_TRAILING_DASH = Regex("""\s*[-–—]\s*$""")
        private val REGEX_FEAT_SPLIT = Regex("""\s*,\s*|\s+[/&x+]\s+|\b(feat|ft|vs)\b""", RegexOption.IGNORE_CASE)
        private val REGEX_NORMALIZE = Regex("""[^a-z0-9а-яё]""")
    }

    @Volatile
    var vkApiClient: VkApiClient? = null

    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    init {
        instance = this
    }

    suspend fun preloadMemoryCache() = withContext(Dispatchers.IO) {
        try {
            val list = coverCacheDao.getAllRecentCovers()
            for (item in list) {
                memoryCache[item.queryKey] = item.coverUrl
            }
        } catch (_: Exception) {}
    }

    /**
     * Мгновенный поиск обложки исключительно в оперативной памяти (0 ms, ConcurrentHashMap).
     * Не делает блокирующих запросов к Room SQLite или диску.
     * Используется при массовом парсинге списков треков (библиотека, каталог, поиск).
     */
    fun getCachedCover(rawArtist: String, rawTitle: String): String? {
        val cleanArtist = sanitizeArtist(rawArtist)
        val cleanTitle = sanitizeTitle(rawTitle)
        val trackKey = "$cleanArtist - $cleanTitle".lowercase()
        val artistKey = "artist:$cleanArtist".lowercase()
        return memoryCache[trackKey] ?: memoryCache[artistKey]
    }

    /**
     * Трёхступенчатый ультрабыстрый поиск обложки (по аналогии с VKX):
     * 1. Проверка оперативной памяти (0 ms)
     * 2. Проверка локальной базы Room (<1 ms)
     * 3. iTunes API: персональная обложка трека / сингла (entity=song, limit=3, ~80 ms)
     * 4. iTunes API: обложка альбома исполнителя (entity=album, limit=3)
     * 5. iTunes API Fallback: официальное фото / обложка топ-релиза исполнителя (entity=album, limit=3)
     *
     * Не нагружает VK API каталогами и не блокирует поток воспроизведения.
     */
    suspend fun resolveCover(
        rawArtist: String,
        rawTitle: String,
        rawAlbum: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cleanArtist = sanitizeArtist(rawArtist)
        val cleanTitle = sanitizeTitle(rawTitle)
        if (cleanArtist.isBlank() && cleanTitle.isBlank()) return@withContext null

        val trackKey = "$cleanArtist - $cleanTitle".lowercase()
        val artistKey = "artist:$cleanArtist".lowercase()

        // 1. Проверяем оперативную память
        val inMem = memoryCache[trackKey] ?: if (cleanTitle.isBlank()) memoryCache[artistKey] else null
        if (!inMem.isNullOrEmpty()) return@withContext inMem

        // 2. Проверяем локальный кэш Room
        try {
            val cachedTrack = coverCacheDao.getCover(trackKey)
            if (!cachedTrack.isNullOrEmpty()) {
                memoryCache[trackKey] = cachedTrack
                return@withContext cachedTrack
            }

            val cachedArtist = coverCacheDao.getCover(artistKey)
            if (!cachedArtist.isNullOrEmpty() && cleanTitle.isBlank()) {
                memoryCache[artistKey] = cachedArtist
                return@withContext cachedArtist
            }
        } catch (_: Exception) {}

        // ЭТАП 1: Поиск обложки конкретного трека / сингла в iTunes
        if (cleanTitle.isNotBlank()) {
            val itunesTrackCover = searchItunesTrack(cleanArtist, cleanTitle)
            if (!itunesTrackCover.isNullOrEmpty()) {
                saveToCache(trackKey, itunesTrackCover)
                return@withContext itunesTrackCover
            }
        }

        // ЭТАП 2: Поиск обложки альбома исполнителя в iTunes
        val cleanAlbum = rawAlbum?.takeIf { it != "Онлайн-радио" }?.let { sanitizeTitle(it) }
        val albumSearchTerm = if (!cleanAlbum.isNullOrBlank()) cleanAlbum else cleanTitle
        if (albumSearchTerm.isNotBlank()) {
            val itunesAlbumCover = searchItunesAlbum(cleanArtist, albumSearchTerm)
            if (!itunesAlbumCover.isNullOrEmpty()) {
                saveToCache(trackKey, itunesAlbumCover)
                return@withContext itunesAlbumCover
            }
        }

        // ЭТАП 3: Fallback — Официальная обложка / релиз исполнителя в iTunes
        if (cleanArtist.isNotBlank()) {
            try {
                val cachedArtist = coverCacheDao.getCover(artistKey)
                if (!cachedArtist.isNullOrEmpty()) {
                    saveToCache(trackKey, cachedArtist)
                    return@withContext cachedArtist
                }
            } catch (_: Exception) {}

            val itunesArtistPhoto = searchItunesArtistFallback(cleanArtist)
            if (!itunesArtistPhoto.isNullOrEmpty()) {
                saveToCache(artistKey, itunesArtistPhoto)
                saveToCache(trackKey, itunesArtistPhoto)
                return@withContext itunesArtistPhoto
            }
        }

        null
    }

    private fun searchItunesTrack(artist: String, title: String): String? {
        val query = "$artist $title"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=3"

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

                    if (isArtistMatch(artist, resArtist) && isTitleMatch(title, resTrack)) {
                        return artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchItunesAlbum(artist: String, album: String): String? {
        val query = "$artist $album"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=3"

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
                    val resAlbum = item.optString("collectionName")
                    val artworkUrl = item.optString("artworkUrl100")

                    if (isArtistMatch(artist, resArtist) && isTitleMatch(album, resAlbum)) {
                        return artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchItunesArtistFallback(artist: String): String? {
        val encoded = URLEncoder.encode(artist, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=3"

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
                    val artworkUrl = item.optString("artworkUrl100")

                    if (isArtistMatch(artist, resArtist) && artworkUrl.isNotEmpty()) {
                        return artworkUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveToCache(key: String, url: String) {
        memoryCache[key] = url
        try {
            coverCacheDao.insertCover(CoverCacheEntity(queryKey = key, coverUrl = url))
        } catch (_: Exception) {}
    }

    /**
     * Тотальная очистка названия от шума и скобок:
     * Удаляет любые (...), [...], {...}, порядковые номера (01. ) и служебные приписки.
     */
    fun sanitizeTitle(title: String): String {
        return title
            .replace(REGEX_PARENS, "")
            .replace(REGEX_BRACKETS, "")
            .replace(REGEX_BRACES, "")
            .replace(REGEX_TRACK_NUM, "")
            .replace(REGEX_TAGS, "")
            .replace(REGEX_TRAILING_DASH, "")
            .trim()
    }

    /**
     * Выделение главного артиста с отсечением фитов и коллабораций.
     * Сохраняет имена вроде AC/DC (деление строго по окружающим пробелам или запятым).
     */
    fun sanitizeArtist(artist: String): String {
        return artist
            .replace(REGEX_PARENS, "")
            .replace(REGEX_BRACKETS, "")
            .split(REGEX_FEAT_SPLIT)
            .firstOrNull()?.trim() ?: artist.trim()
    }

    /**
     * Строгая двухфакторная проверка совпадения артиста (Strict Guard).
     * Требует совпадения нормализованных строк >= 85%.
     * Исключает ложное срабатывание на похожие слова или частичные подстроки.
     */
    fun isArtistMatch(expected: String, actual: String): Boolean {
        val s1 = normalize(expected)
        val s2 = normalize(actual)
        if (s1.isEmpty() || s2.isEmpty()) return false
        if (s1 == s2) return true

        val distance = levenshtein(s1, s2)
        val maxLen = max(s1.length, s2.length)
        val similarity = 1.0 - (distance.toDouble() / maxLen.toDouble())
        return similarity >= 0.85
    }

    /**
     * Строгая проверка совпадения названия трека.
     * Запрещает нечёткий "contains" для коротких названий песен.
     */
    fun isTitleMatch(expected: String, actual: String): Boolean {
        val s1 = normalize(expected)
        val s2 = normalize(actual)
        if (s1.isEmpty() || s2.isEmpty()) return false
        if (s1 == s2) return true

        // Для длинных названий допускается опечатка в 1 символ
        val distance = levenshtein(s1, s2)
        return distance <= 1 && s1.length > 5 && s2.length > 5
    }

    private fun normalize(str: String): String {
        return str.lowercase().replace(REGEX_NORMALIZE, "")
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
