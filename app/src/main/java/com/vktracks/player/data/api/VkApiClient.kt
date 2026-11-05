package com.vktracks.player.data.api

import android.util.Log
import com.vktracks.player.data.cover.CoverArtResolver
import com.vktracks.player.data.model.LyricLine
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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

    // Профили User-Agent для совместимости с токенами VK Android / VK Музыки и Kate Mobile
    private val vkAndroidUserAgent = "VKAndroidApp/8.14-16238 (Android 14; SDK 34; arm64-v8a; ru)"
    private val kateUserAgent = "KateMobileAndroid/108.1 lite-535 (Android 14; SDK 34; arm64-v8a; ru)"
    private val apiVersion = "5.131"

    @Volatile
    private var preferredUserAgent = vkAndroidUserAgent

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
            "album_id" to playlistId.toString(),
            "owner_id" to ownerId.toString(),
            "count" to "100"
        )
        if (!accessKey.isNullOrEmpty()) {
            params["access_key"] = accessKey
        }
        val json = callVkApi("audio.get", params) ?: return@withContext emptyList()
        parseTracksList(json)
    }

    suspend fun searchAudios(query: String, offset: Int = 0, count: Int = 40): List<Track> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "q" to query,
            "auto_complete" to "1",
            "sort" to "2",
            "offset" to offset.toString(),
            "count" to count.toString()
        )
        val json = callVkApi("audio.search", params) ?: return@withContext emptyList()
        parseTracksList(json)
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
        val response = json.optJSONArray("response") ?: return@withContext null
        val item = response.optJSONObject(0) ?: return@withContext null
        val url = item.optString("url")
        if (url.isNotEmpty()) url else null
    }

    suspend fun getLyrics(audioId: Long, lyricsId: Long?): Lyrics? = withContext(Dispatchers.IO) {
        if (lyricsId == null || lyricsId == 0L) return@withContext null
        val params = mapOf("lyrics_id" to lyricsId.toString())
        val json = callVkApi("audio.getLyrics", params) ?: return@withContext null
        val response = json.optJSONObject("response") ?: return@withContext null

        val timestamps = response.optJSONArray("timestamps")
        val textArray = response.optJSONArray("text")
        val plainTextList = mutableListOf<String>()

        if (textArray != null) {
            for (i in 0 until textArray.length()) {
                plainTextList.add(textArray.getString(i))
            }
        } else {
            val textStr = response.optString("text")
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

    private fun callVkApi(method: String, params: Map<String, String>): JSONObject? {
        val token = authManager.getAccessToken() ?: return null

        // Попытка 1: С текущим preferred User-Agent
        val result = executeMethodCall(method, params, token, preferredUserAgent)
        if (result != null) {
            val errCode = result.optJSONObject("error")?.optInt("error_code") ?: 0
            if (errCode == 8 || errCode == 27) {
                // Несоответствие User-Agent типу токена -> переключаем и пробуем второй профиль
                val fallbackUa = if (preferredUserAgent == vkAndroidUserAgent) kateUserAgent else vkAndroidUserAgent
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

        if (ua == vkAndroidUserAgent) {
            requestBuilder.header("X-App-Id", "6767438")
            requestBuilder.header("X-Client-Version", "10683")
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
        val response = json.optJSONObject("response") ?: return emptyList()
        val items = response.optJSONArray("items") ?: return emptyList()
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

        // Если в VK обложки нет, подключаем умный CoverArtResolver
        if (coverUrl.isNullOrEmpty()) {
            coverUrl = coverArtResolver.resolveCover(artist, title)
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

    private fun parsePlaylists(json: JSONObject): List<Playlist> {
        val response = json.optJSONObject("response") ?: return emptyList()
        val items = response.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<Playlist>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val ownerId = item.optLong("owner_id")
            val title = item.optString("title")
            val description = item.optString("description")
            val count = item.optInt("count")
            val accessKey = item.optString("access_key")

            val thumb = item.optJSONObject("thumb")
            val coverUrl = thumb?.optString("photo_600")
                ?: thumb?.optString("photo_300")
                ?: thumb?.optString("photo_1200")

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
}
