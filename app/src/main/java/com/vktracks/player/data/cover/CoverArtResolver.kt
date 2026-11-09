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
    }

    @Volatile
    var vkApiClient: VkApiClient? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    init {
        instance = this
    }

    suspend fun getCachedCover(rawArtist: String, rawTitle: String): String? {
        val cleanArtist = sanitizeArtist(rawArtist)
        val cleanTitle = sanitizeTitle(rawTitle)
        val trackKey = "$cleanArtist - $cleanTitle".lowercase()
        val artistKey = "artist:$cleanArtist".lowercase()
        return try {
            coverCacheDao.getCover(trackKey) ?: coverCacheDao.getCover(artistKey)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Трёхступенчатый каскадный поиск обложки (только iTunes и VK):
     * 1. Персональная обложка трека / сингла
     * 2. Обложка альбома исполнителя с этим треком
     * 3. Fallback: официальное фото самого исполнителя
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

        // 0. Проверяем локальный кэш Room
        try {
            val cachedTrack = coverCacheDao.getCover(trackKey)
            if (!cachedTrack.isNullOrEmpty()) return@withContext cachedTrack

            val cachedArtist = coverCacheDao.getCover(artistKey)
            if (!cachedArtist.isNullOrEmpty() && cleanTitle.isBlank()) return@withContext cachedArtist
        } catch (_: Exception) {}

        // ЭТАП 1: Поиск обложки конкретного трека / сингла
        if (cleanTitle.isNotBlank()) {
            // 1.1. iTunes API (entity=song) со строгой двухфакторной проверкой
            val itunesTrackCover = searchItunesTrack(cleanArtist, cleanTitle)
            if (!itunesTrackCover.isNullOrEmpty()) {
                saveToCache(trackKey, itunesTrackCover)
                return@withContext itunesTrackCover
            }

            // 1.2. VK API (audio.search) — поиск трека того же исполнителя с официальным album.thumb
            val vkTrackCover = searchVkTrack(cleanArtist, cleanTitle)
            if (!vkTrackCover.isNullOrEmpty()) {
                saveToCache(trackKey, vkTrackCover)
                return@withContext vkTrackCover
            }
        }

        // ЭТАП 2: Поиск обложки альбома исполнителя
        val cleanAlbum = rawAlbum?.takeIf { it != "Онлайн-радио" }?.let { sanitizeTitle(it) }
        if (!cleanAlbum.isNullOrBlank()) {
            // 2.1. iTunes API (entity=album)
            val itunesAlbumCover = searchItunesAlbum(cleanArtist, cleanAlbum)
            if (!itunesAlbumCover.isNullOrEmpty()) {
                saveToCache(trackKey, itunesAlbumCover)
                return@withContext itunesAlbumCover
            }

            // 2.2. VK API (searchPlaylists) — поиск альбома исполнителя
            val vkAlbumCover = searchVkAlbum(cleanArtist, cleanAlbum)
            if (!vkAlbumCover.isNullOrEmpty()) {
                saveToCache(trackKey, vkAlbumCover)
                return@withContext vkAlbumCover
            }
        }

        // ЭТАП 3: Fallback — Официальное фото исполнителя
        if (cleanArtist.isNotBlank()) {
            // 3.0. Проверяем кэш артиста
            try {
                val cachedArtist = coverCacheDao.getCover(artistKey)
                if (!cachedArtist.isNullOrEmpty()) {
                    saveToCache(trackKey, cachedArtist)
                    return@withContext cachedArtist
                }
            } catch (_: Exception) {}

            // 3.1. VK Artist API (карточка музыканта с photo_1200 / photo_600)
            val vkArtistPhoto = searchVkArtistPhoto(cleanArtist)
            if (!vkArtistPhoto.isNullOrEmpty()) {
                saveToCache(artistKey, vkArtistPhoto)
                saveToCache(trackKey, vkArtistPhoto)
                return@withContext vkArtistPhoto
            }

            // 3.2. iTunes Artist / Top album fallback
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

                    if (isArtistMatch(artist, resArtist) && isTitleMatch(title, resTrack)) {
                        return artworkUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchVkTrack(artist: String, title: String): String? {
        val vk = vkApiClient ?: return null
        return try {
            val tracks = vk.searchAudios("$artist $title", count = 10)
            for (t in tracks) {
                if (isArtistMatch(artist, t.artist) && isTitleMatch(title, t.title) && !t.coverUrl.isNullOrEmpty()) {
                    return t.coverUrl
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun searchItunesAlbum(artist: String, album: String): String? {
        val query = "$artist $album"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=5"

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
                        return artworkUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchVkAlbum(artist: String, album: String): String? {
        val vk = vkApiClient ?: return null
        return try {
            val playlists = vk.searchPlaylists("$artist $album", count = 5)
            for (p in playlists) {
                val pArtist = p.mainArtist ?: p.title
                if (p.isAlbum && isArtistMatch(artist, pArtist) && isTitleMatch(album, p.title) && !p.coverUrl.isNullOrEmpty()) {
                    return p.coverUrl
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchVkArtistPhoto(artist: String): String? {
        val vk = vkApiClient ?: return null
        return try {
            val searchResult = vk.searchCatalog(artist)
            for (a in searchResult.artists) {
                if (isArtistMatch(artist, a.name) && !a.photoUrl.isNullOrEmpty()) {
                    return a.photoUrl
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun searchItunesArtistFallback(artist: String): String? {
        val encoded = URLEncoder.encode(artist, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=music&entity=album&limit=5"

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
                        return artworkUrl.replace("100x100bb.jpg", "1000x1000bb.jpg")
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun saveToCache(key: String, url: String) {
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
            .replace(Regex("""\([^)]*\)"""), "") // Удаление всех (...)
            .replace(Regex("""\[[^\]]*\]"""), "") // Удаление всех [...]
            .replace(Regex("""\{[^}]*\}"""), "") // Удаление всех {...}
            .replace(Regex("""^\d+[\s.-]+"""), "") // Удаление порядкового номера "01. " или "01 - "
            .replace(Regex("""(?i)\b(feat|ft|prod|remix|mix|edit|deluxe|version|slowed|reverb|speed up|bass boosted|cover|instrumental|live|bonus)\b.*$"""), "")
            .replace(Regex("""\s*[-–—]\s*$"""), "")
            .trim()
    }

    /**
     * Выделение главного артиста с отсечением фитов и коллабораций.
     * Сохраняет имена вроде AC/DC (деление строго по окружающим пробелам или запятым).
     */
    fun sanitizeArtist(artist: String): String {
        return artist
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .split(Regex("""\s*,\s*|\s+[/&x+]\s+|\b(feat|ft|vs)\b""", RegexOption.IGNORE_CASE))
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
