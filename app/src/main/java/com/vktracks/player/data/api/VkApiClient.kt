package com.vktracks.player.data.api

import android.util.Log
import com.vktracks.player.data.cover.CoverArtResolver
import com.vktracks.player.data.model.Artist
import com.vktracks.player.data.model.CatalogBlock
import com.vktracks.player.data.model.LyricLine
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.SearchResult
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class VkApiClient(
    private val authManager: VkAuthManager,
    private val coverArtResolver: CoverArtResolver
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Официальные профили User-Agent (VK Музыка и VK Android)
    private val vkMusicUserAgent = "VKMusic/1.1.0 (Android 14; SDK 34; arm64-v8a; ru)"
    private val vkAndroidUserAgent = "VKAndroidApp/8.14-16238 (Android 14; SDK 34; arm64-v8a; ru)"
    private val apiVersion = "5.131"

    @Volatile
    private var preferredUserAgent = vkMusicUserAgent

    suspend fun getUserProfile(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val json = callVkApi("users.get", mapOf("fields" to "photo_100")) ?: return@withContext null
        val response = json.optJSONArray("response") ?: return@withContext null
        val user = response.optJSONObject(0) ?: return@withContext null
        val firstName = user.optString("first_name")
        val lastName = user.optString("last_name")
        val photo = user.optString("photo_100")
        Pair("$firstName $lastName", photo)
    }

    suspend fun validateAndSaveToken(input: String): Boolean = withContext(Dispatchers.IO) {
        var token = input.trim()
        if (token.isEmpty()) return@withContext false

        // Если вставлена полная ссылка blank.html#access_token=...
        if (token.contains("access_token=")) {
            val redirectData = VkAuthManager.parseRedirectUrl(token)
            if (redirectData != null) {
                token = redirectData.first
            } else {
                val match = Regex("access_token=([a-zA-Z0-9._-]+)").find(token)
                if (match != null) {
                    token = match.groupValues[1]
                }
            }
        }

        // Проверяем токен через users.get
        val url = "https://api.vk.com/method/users.get?v=$apiVersion&access_token=$token&fields=photo_100"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", preferredUserAgent)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false
            val json = JSONObject(body)
            val responseArray = json.optJSONArray("response") ?: return@withContext false
            val user = responseArray.optJSONObject(0) ?: return@withContext false
            val userId = user.optLong("id")
            val firstName = user.optString("first_name")
            val lastName = user.optString("last_name")
            val photo = user.optString("photo_100")

            if (userId > 0) {
                authManager.saveAuthData(token, userId, "$firstName $lastName", photo)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("VkApiClient", "Token validation failed", e)
            return@withContext false
        }
        false
    }

    suspend fun getMyAudios(offset: Int = 0, count: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId()
        val params = mutableMapOf(
            "offset" to offset.toString(),
            "count" to count.toString()
        )
        if (userId > 0) {
            params["owner_id"] = userId.toString()
        }
        val json = callVkApi("audio.get", params) ?: return@withContext emptyList()
        parseTracksList(json)
    }

    suspend fun getPlaylists(offset: Int = 0, count: Int = 30): List<Playlist> = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId()
        val params = mutableMapOf(
            "offset" to offset.toString(),
            "count" to count.toString()
        )
        if (userId > 0) {
            params["owner_id"] = userId.toString()
        }
        val json = callVkApi("audio.getPlaylists", params) ?: return@withContext emptyList()
        parsePlaylists(json)
    }

    suspend fun getPlaylistTracks(playlistId: Long, ownerId: Long, accessKey: String? = null): List<Track> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "playlist_id" to playlistId.toString(),
            "album_id" to playlistId.toString(),
            "owner_id" to ownerId.toString(),
            "count" to "150"
        )
        if (!accessKey.isNullOrEmpty()) {
            params["access_key"] = accessKey
        }
        val json = callVkApi("audio.get", params)
        if (json != null && !json.has("error")) {
            val tracks = parseTracksList(json)
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        // Резервный вызов для официальных/редакционных плейлистов VK
        val pParams = mutableMapOf(
            "playlist_id" to playlistId.toString(),
            "owner_id" to ownerId.toString(),
            "need_audios" to "1",
            "count" to "150"
        )
        if (!accessKey.isNullOrEmpty()) {
            pParams["access_key"] = accessKey
        }
        val pJson = callVkApi("audio.getPlaylistById", pParams)
        if (pJson != null && !pJson.has("error")) {
            val pResp = pJson.optJSONObject("response")
            val audios = pResp?.optJSONArray("audios") ?: pResp?.optJSONArray("items")
            if (audios != null) {
                return@withContext parseTracksArray(audios)
            }
        }

        emptyList()
    }

    suspend fun searchCatalog(query: String): SearchResult = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchResult()

        var tracks = emptyList<Track>()
        var playlists = emptyList<Playlist>()
        var artists = emptyList<Artist>()

        // 1. Полноценный каталог поиска ВК (catalog.getAudioSearch из официального VK Music API)
        try {
            val json = callVkApi("catalog.getAudioSearch", mapOf(
                "query" to q,
                "need_blocks" to "1"
            ))
            if (json != null && !json.has("error")) {
                val resp = json.optJSONObject("response")
                if (resp != null) {
                    val audiosArr = resp.optJSONArray("audios")
                    if (audiosArr != null && audiosArr.length() > 0) {
                        tracks = parseTracksArray(audiosArr)
                    }
                    val playlistsArr = resp.optJSONArray("playlists")
                    if (playlistsArr != null && playlistsArr.length() > 0) {
                        playlists = parsePlaylistsArray(playlistsArr)
                    }
                    val artistsArr = resp.optJSONArray("artists")
                    if (artistsArr != null && artistsArr.length() > 0) {
                        artists = parseArtistsArray(artistsArr)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "catalog.getAudioSearch failed for '$q'", e)
        }

        // 2. Если треки не получены через каталог, вызываем прямой audio.search
        if (tracks.isEmpty()) {
            try {
                val audioSearchJson = callVkApi("audio.search", mapOf(
                    "q" to q,
                    "auto_complete" to "1",
                    "count" to "50"
                ))
                if (audioSearchJson != null && !audioSearchJson.has("error")) {
                    tracks = parseTracksList(audioSearchJson)
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "audio.search failed for '$q'", e)
            }
        }

        // 3. Если плейлисты не найдены через каталог, ищем через audio.searchPlaylists
        if (playlists.isEmpty()) {
            try {
                playlists = searchPlaylists(q, 20)
            } catch (e: Exception) {
                Log.w("VkApiClient", "audio.searchPlaylists failed for '$q'", e)
            }
        }

        SearchResult(tracks = tracks, playlists = playlists, artists = artists)
    }

    suspend fun searchAudios(query: String, offset: Int = 0, count: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        searchCatalog(query).tracks
    }

    suspend fun searchPlaylists(query: String, count: Int = 20): List<Playlist> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "q" to query,
            "count" to count.toString(),
            "extended" to "1"
        )
        val json = callVkApi("audio.searchPlaylists", params) ?: return@withContext emptyList()
        parsePlaylists(json)
    }

    suspend fun getCatalogExplore(): Pair<List<Track>, List<CatalogBlock>> = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<CatalogBlock>()
        var chart = listOf<Track>()

        // 1. Официальный раздел Explore (Обзор) каталога VK Музыки
        try {
            val json = callVkApi("catalog.getAudio", mapOf(
                "url" to "https://vk.com/audios?section=explore",
                "need_blocks" to "1"
            ))
            if (json != null && !json.has("error")) {
                val parsed = parseCatalogBlocks(json)
                if (parsed.isNotEmpty()) {
                    for (block in parsed) {
                        if (chart.isEmpty() && block.tracks.isNotEmpty() &&
                            (block.title.contains("Чарт", ignoreCase = true) || block.title.contains("Топ", ignoreCase = true))) {
                            chart = block.tracks
                        } else if (block.playlists.isNotEmpty()) {
                            blocks.add(block)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "catalog.getAudio explore failed", e)
        }

        // Если чарт не найден в explore, загружаем через audio.getPopular
        if (chart.isEmpty()) {
            chart = getChartTracks(50)
        }

        // 2. Тематические подборки VK: жанры, настроение, подборки редакции, новинки, спорт, авто
        if (blocks.size < 4) {
            val curatedCategories = listOf(
                "🔥 Новые релизы" to "Новинки",
                "🎧 Подборки редакции VK" to "Подборка редакции",
                "⚡ Популярные плейлисты" to "Популярное",
                "🚗 В дорогу и авто" to "В дорогу",
                "💪 Для тренировок и спорта" to "Тренировка",
                "🎸 Рок и альтернатива" to "Рок",
                "🎤 Рэп и хип-хоп" to "Хип-хоп",
                "☕ Спокойствие и релакс" to "Релакс",
                "🎉 Танцевальная музыка" to "Танцевальная"
            )

            try {
                coroutineScope {
                    val deferreds = curatedCategories.map { (catTitle, query) ->
                        async {
                            val playlists = searchPlaylists(query, 16)
                            if (playlists.isNotEmpty()) {
                                CatalogBlock(
                                    id = "curated_${query.hashCode()}",
                                    title = catTitle,
                                    playlists = playlists
                                )
                            } else null
                        }
                    }
                    val fetched = deferreds.awaitAll().filterNotNull()
                    blocks.addAll(fetched)
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "Failed to fetch curated playlists for explore", e)
            }
        }

        Pair(chart, blocks)
    }

    suspend fun getCuratedPlaylists(count: Int = 30): List<Playlist> = withContext(Dispatchers.IO) {
        searchPlaylists("Подборка редакции", count)
    }

    suspend fun getChartTracks(count: Int = 50): List<Track> = withContext(Dispatchers.IO) {
        // 1. Треки чарта из раздела Explore каталога ВК
        try {
            val json = callVkApi("catalog.getAudio", mapOf(
                "url" to "https://vk.com/audios?section=explore",
                "need_blocks" to "1"
            ))
            if (json != null && !json.has("error")) {
                val tracks = parseTracksList(json)
                if (tracks.isNotEmpty()) return@withContext tracks
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "catalog.getAudio chart failed", e)
        }

        // 2. Резерв: топ популярных треков ВКонтакте
        try {
            val json = callVkApi("audio.getPopular", mapOf("count" to count.toString()))
            if (json != null && !json.has("error")) {
                val tracks = parseTracksList(json)
                if (tracks.isNotEmpty()) return@withContext tracks
            }
        } catch (_: Exception) {}

        // 3. Рекомендации
        getRecommendations(0, count)
    }

    suspend fun getRecommendations(offset: Int = 0, count: Int = 40): List<Track> = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId()
        val params = mutableMapOf(
            "offset" to offset.toString(),
            "count" to count.toString()
        )
        if (userId > 0) {
            params["user_id"] = userId.toString()
        }
        val json = callVkApi("audio.getRecommendations", params) ?: return@withContext emptyList()
        parseTracksList(json)
    }

    suspend fun getPersonalRadio(): List<Track> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken() ?: return@withContext emptyList()
        val userId = authManager.getUserId()

        // 1. Попытка запроса в UMA (VK Mix / Boom радио как в VKX)
        try {
            val path = "/radio/personal/"
            val crc = CRC32()
            val userStr = if (userId > 0) userId.toString() else "anonymous"
            crc.update(userStr.toByteArray(Charsets.UTF_8))
            crc.update(path.toByteArray(Charsets.UTF_8))
            crc.update(48) // '0'

            val request = Request.Builder()
                .url("https://api.moosic.io$path")
                .header("User-Agent", vkAndroidUserAgent)
                .header("X-App-Id", "6767438")
                .header("X-Client-Version", "10683")
                .header("X-CRC", crc.value.toString())
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val tracks = parseUmaTracks(JSONObject(body))
                        if (tracks.isNotEmpty()) return@withContext tracks
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "UMA personal radio request failed, falling back to recommendations", e)
        }

        // 2. Fallback на аудиорекомендации VK
        getRecommendations(0, 40)
    }

    suspend fun resolveTrackUrl(track: Track): String? = withContext(Dispatchers.IO) {
        val audiosParam = if (!track.accessKey.isNullOrEmpty()) {
            "${track.ownerId}_${track.id}_${track.accessKey}"
        } else {
            "${track.ownerId}_${track.id}"
        }
        val json = callVkApi("audio.getById", mapOf("audios" to audiosParam)) ?: return@withContext null
        val responseArray = json.optJSONArray("response")
        val item = responseArray?.optJSONObject(0)
            ?: json.optJSONObject("response")?.optJSONArray("items")?.optJSONObject(0)
            ?: return@withContext null
        val url = item.optString("url")
        if (url.isNotEmpty()) url else null
    }

    suspend fun getLyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val fullAudioId = "${track.ownerId}_${track.id}"
        val params = mutableMapOf("audio_id" to fullAudioId)
        if (track.lyricsId != null && track.lyricsId > 0) {
            params["lyrics_id"] = track.lyricsId.toString()
        }

        val json = callVkApi("audio.getLyrics", params) ?: return@withContext null
        val response = json.optJSONObject("response") ?: return@withContext null
        val lyricsObj = response.optJSONObject("lyrics") ?: response

        val timestamps = lyricsObj.optJSONArray("timestamps")
        val textArray = lyricsObj.optJSONArray("text")
        val plainTextList = mutableListOf<String>()

        if (textArray != null) {
            for (i in 0 until textArray.length()) {
                plainTextList.add(textArray.getString(i))
            }
        } else {
            val textStr = lyricsObj.optString("text")
            if (textStr.isNotEmpty()) {
                plainTextList.addAll(textStr.split("\n"))
            }
        }

        val lines = mutableListOf<LyricLine>()
        if (timestamps != null) {
            for (i in 0 until timestamps.length()) {
                val item = timestamps.getJSONObject(i)
                lines.add(
                    LyricLine(
                        beginMs = item.optLong("begin"),
                        endMs = item.optLong("end"),
                        text = item.optString("line")
                    )
                )
            }
        }

        Lyrics(plainText = plainTextList, lines = lines)
    }

    suspend fun addAudio(audioId: Long, ownerId: Long): Long? = withContext(Dispatchers.IO) {
        val params = mapOf(
            "audio_id" to audioId.toString(),
            "owner_id" to ownerId.toString()
        )
        val json = callVkApi("audio.add", params) ?: return@withContext null
        if (json.has("response")) {
            json.optLong("response")
        } else null
    }

    suspend fun deleteAudio(audioId: Long, ownerId: Long): Boolean = withContext(Dispatchers.IO) {
        val params = mapOf(
            "audio_id" to audioId.toString(),
            "owner_id" to ownerId.toString()
        )
        val json = callVkApi("audio.delete", params) ?: return@withContext false
        json.optInt("response", 0) == 1
    }

    suspend fun followPlaylist(playlistId: Long, ownerId: Long, accessKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "playlist_id" to playlistId.toString(),
            "owner_id" to ownerId.toString()
        )
        if (!accessKey.isNullOrEmpty()) {
            params["access_key"] = accessKey
        }
        val json = callVkApi("audio.followPlaylist", params) ?: return@withContext false
        !json.has("error")
    }

    suspend fun deletePlaylist(playlistId: Long, ownerId: Long): Boolean = withContext(Dispatchers.IO) {
        val params = mapOf(
            "playlist_id" to playlistId.toString(),
            "owner_id" to ownerId.toString()
        )
        val json = callVkApi("audio.deletePlaylist", params) ?: return@withContext false
        json.optInt("response", 0) == 1
    }

    private fun callVkApi(method: String, params: Map<String, String>): JSONObject? {
        val token = authManager.getAccessToken() ?: return null

        // Попытка 1: С текущим preferred User-Agent
        val result = executeMethodCall(method, params, token, preferredUserAgent)
        if (result != null) {
            val errCode = result.optJSONObject("error")?.optInt("error_code") ?: 0
            if (errCode == 8 || errCode == 27) {
                // Несоответствие User-Agent типу токена -> переключаем и пробуем второй профиль
                val fallbackUa = if (preferredUserAgent == vkAndroidUserAgent) vkMusicUserAgent else vkAndroidUserAgent
                Log.w("VkApiClient", "VK API $method returned error $errCode with $preferredUserAgent, retrying with $fallbackUa")
                val fallbackResult = executeMethodCall(method, params, token, fallbackUa)
                if (fallbackResult != null && !fallbackResult.has("error")) {
                    preferredUserAgent = fallbackUa
                    return fallbackResult
                }
            }
        }
        return result
    }

    private fun executeMethodCall(method: String, params: Map<String, String>, token: String, ua: String): JSONObject? {
        val formBuilder = FormBody.Builder()
            .add("access_token", token)
            .add("v", apiVersion)

        for ((k, v) in params) {
            formBuilder.add(k, v)
        }

        val requestBuilder = Request.Builder()
            .url("https://api.vk.com/method/$method")
            .header("User-Agent", ua)
            .post(formBuilder.build())

        if (ua.contains("VKMusic")) {
            requestBuilder.header("X-App-Id", "6767438")
            requestBuilder.header("X-Client-Version", "10683")
            requestBuilder.header("X-VK-Android-Client", "new")
        } else if (ua.contains("VKAndroidApp")) {
            requestBuilder.header("X-App-Id", "2274003")
            requestBuilder.header("X-VK-Android-Client", "new")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("VkApiClient", "HTTP error ${response.code} for $method")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.has("error")) {
                    val err = json.optJSONObject("error")
                    Log.e("VkApiClient", "VK API error for $method: [${err?.optInt("error_code")}] ${err?.optString("error_msg")}")
                }
                json
            }
        } catch (e: Exception) {
            Log.e("VkApiClient", "Network exception in callVkApi($method)", e)
            null
        }
    }

    private suspend fun parseTracksList(json: JSONObject): List<Track> {
        val response = json.optJSONObject("response")
        val items = response?.optJSONArray("items")
            ?: response?.optJSONArray("audios")
            ?: json.optJSONArray("response")
            ?: json.optJSONArray("items")

        if (items != null) {
            return parseTracksArray(items)
        }
        return emptyList()
    }

    private suspend fun parseTracksArray(items: org.json.JSONArray): List<Track> {
        val tracks = mutableListOf<Track>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val track = parseTrackObject(item)
            if (track != null) {
                tracks.add(track)
            }
        }
        return tracks
    }

    private suspend fun parseTrackObject(item: JSONObject): Track? {
        val id = item.optLong("id")
        if (id == 0L) return null

        val ownerId = item.optLong("owner_id")
        val artist = item.optString("artist", "Неизвестный исполнитель")
        val title = item.optString("title", "Без названия")
        val duration = item.optInt("duration", 0)
        val isExplicit = item.optBoolean("is_explicit", false)
        val lyricsId = item.optLong("lyrics_id", 0).takeIf { it > 0 }
        val accessKey = item.optString("access_key").takeIf { it.isNotEmpty() }
        val url = item.optString("url", "")

        // Обложка из альбома
        var coverUrl: String? = null
        val album = item.optJSONObject("album")
        if (album != null) {
            val thumb = album.optJSONObject("thumb")
            coverUrl = thumb?.optString("photo_600")
                ?: thumb?.optString("photo_300")
                ?: thumb?.optString("photo_1200")
        }

        // Если в VK обложки нет, смотрим только локальный кэш Room (без блокирующих сетевых запросов)
        if (coverUrl.isNullOrEmpty()) {
            coverUrl = coverArtResolver.getCachedCover(artist, title)
        }

        return Track(
            id = id,
            ownerId = ownerId,
            title = title,
            artist = artist,
            duration = duration,
            streamUrl = url,
            coverUrl = coverUrl,
            albumTitle = album?.optString("title"),
            isExplicit = isExplicit,
            lyricsId = lyricsId,
            accessKey = accessKey
        )
    }

    private fun parseUmaTracks(json: JSONObject): List<Track> {
        val tracksJson = json.optJSONArray("tracks") ?: return emptyList()
        val list = mutableListOf<Track>()

        for (i in 0 until tracksJson.length()) {
            val item = tracksJson.optJSONObject(i) ?: continue
            val fileUrl = item.optString("file")
            if (fileUrl.isEmpty()) continue

            val title = item.optString("name")
            val artist = item.optString("artistDisplayName")
            val duration = item.optInt("duration")
            val coverObj = item.optJSONObject("cover")
            val coverUrl = coverObj?.optString("uri")
            val vkAudioId = item.optString("vkAudioId")

            var id = 0L
            var ownerId = 0L
            if (vkAudioId.contains("_")) {
                val parts = vkAudioId.split("_")
                ownerId = parts[0].toLongOrNull() ?: 0L
                id = parts[1].toLongOrNull() ?: 0L
            }

            list.add(
                Track(
                    id = if (id != 0L) id else System.currentTimeMillis() + i,
                    ownerId = ownerId,
                    title = title,
                    artist = artist,
                    duration = duration,
                    streamUrl = fileUrl,
                    coverUrl = coverUrl
                )
            )
        }
        return list
    }

    private suspend fun parseCatalogBlocks(json: JSONObject): List<CatalogBlock> {
        val result = mutableListOf<CatalogBlock>()
        val resp = json.optJSONObject("response") ?: return emptyList()

        val rawBlocks = mutableListOf<JSONObject>()

        // Вариант 1: response.catalog.sections[i].blocks
        val catalog = resp.optJSONObject("catalog")
        if (catalog != null) {
            val sections = catalog.optJSONArray("sections")
            if (sections != null) {
                for (s in 0 until sections.length()) {
                    val sec = sections.optJSONObject(s) ?: continue
                    val bl = sec.optJSONArray("blocks")
                    if (bl != null) {
                        for (b in 0 until bl.length()) {
                            bl.optJSONObject(b)?.let { rawBlocks.add(it) }
                        }
                    }
                }
            }
        }

        // Вариант 2: response.section.blocks
        val section = resp.optJSONObject("section")
        if (section != null) {
            val bl = section.optJSONArray("blocks")
            if (bl != null) {
                for (b in 0 until bl.length()) {
                    bl.optJSONObject(b)?.let { rawBlocks.add(it) }
                }
            }
        }

        // Вариант 3: response.blocks
        val directBlocks = resp.optJSONArray("blocks")
        if (directBlocks != null) {
            for (b in 0 until directBlocks.length()) {
                directBlocks.optJSONObject(b)?.let { rawBlocks.add(it) }
            }
        }

        for (blockJson in rawBlocks) {
            val id = blockJson.optString("id", "")
            val layout = blockJson.optJSONObject("layout")
            val title = layout?.optString("title")?.takeIf { it.isNotBlank() }
                ?: blockJson.optString("title").takeIf { it.isNotBlank() }
                ?: continue

            val subtitle = layout?.optString("subtitle")?.takeIf { it.isNotBlank() }
                ?: blockJson.optString("subtitle").takeIf { it.isNotBlank() }

            val dataType = blockJson.optString("data_type", "").lowercase()

            // Парсим плейлисты блока
            val playlistsJson = blockJson.optJSONArray("playlists")
                ?: if (dataType.contains("playlist")) blockJson.optJSONArray("items") else null
            val playlists = if (playlistsJson != null) parsePlaylistsArray(playlistsJson) else emptyList()

            // Парсим треки блока
            val audiosJson = blockJson.optJSONArray("audios")
                ?: if (dataType.contains("audio") || dataType.contains("track")) blockJson.optJSONArray("items") else null
            val tracks = if (audiosJson != null) parseTracksArray(audiosJson) else emptyList()

            if (playlists.isNotEmpty() || tracks.isNotEmpty()) {
                result.add(
                    CatalogBlock(
                        id = id.ifEmpty { "block_${result.size}" },
                        title = title,
                        subtitle = subtitle,
                        playlists = playlists,
                        tracks = tracks
                    )
                )
            }
        }

        return result
    }

    private fun parsePlaylists(json: JSONObject): List<Playlist> {
        val response = json.optJSONObject("response")
        val items = response?.optJSONArray("items")
            ?: response?.optJSONArray("playlists")
            ?: json.optJSONArray("response")
            ?: json.optJSONArray("items")
            ?: return emptyList()

        return parsePlaylistsArray(items)
    }

    private fun parsePlaylistsArray(items: org.json.JSONArray): List<Playlist> {
        val list = mutableListOf<Playlist>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val ownerId = item.optLong("owner_id")
            val title = item.optString("title", "Плейлист")
            val description = item.optString("description", "")
            val count = item.optInt("count", 0)
            val accessKey = item.optString("access_key")

            var coverUrl: String? = null

            // 1. photo (VK API v5.131+ official)
            val photo = item.optJSONObject("photo")
            if (photo != null) {
                coverUrl = photo.optString("photo_600").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_300").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_1200").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_270").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_135").takeIf { it.isNotEmpty() }
            }

            // 2. thumbs array
            if (coverUrl.isNullOrEmpty()) {
                val thumbs = item.optJSONArray("thumbs")
                if (thumbs != null && thumbs.length() > 0) {
                    val firstThumb = thumbs.optJSONObject(0)
                    coverUrl = firstThumb?.optString("photo_600")?.takeIf { it.isNotEmpty() }
                        ?: firstThumb?.optString("photo_300")?.takeIf { it.isNotEmpty() }
                        ?: firstThumb?.optString("photo_1200")?.takeIf { it.isNotEmpty() }
                        ?: firstThumb?.optString("photo_270")?.takeIf { it.isNotEmpty() }
                }
            }

            // 3. thumb object
            if (coverUrl.isNullOrEmpty()) {
                val thumb = item.optJSONObject("thumb")
                coverUrl = thumb?.optString("photo_600")?.takeIf { it.isNotEmpty() }
                    ?: thumb?.optString("photo_300")?.takeIf { it.isNotEmpty() }
                    ?: thumb?.optString("photo_1200")?.takeIf { it.isNotEmpty() }
            }

            // 4. cover.uri
            if (coverUrl.isNullOrEmpty()) {
                coverUrl = item.optJSONObject("cover")?.optString("uri")?.takeIf { it.isNotEmpty() }
            }

            list.add(
                Playlist(
                    id = id,
                    ownerId = ownerId,
                    title = title,
                    description = description,
                    coverUrl = coverUrl,
                    count = count,
                    accessKey = accessKey
                )
            )
        }
        return list
    }

    private fun parseArtistsArray(items: JSONArray): List<Artist> {
        val list = mutableListOf<Artist>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = item.optString("name")
            var photoUrl: String? = null
            val photo = item.optJSONObject("photo")
            if (photo != null) {
                photoUrl = photo.optString("photo_300").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_600").takeIf { it.isNotEmpty() }
                    ?: photo.optString("photo_1200").takeIf { it.isNotEmpty() }
            }
            if (name.isNotEmpty()) {
                list.add(Artist(id = id, name = name, photoUrl = photoUrl))
            }
        }
        return list
    }
}
