package com.vktracks.player.data.api

import android.util.Log
import androidx.core.text.HtmlCompat
import com.vktracks.player.data.model.Artist
import com.vktracks.player.data.model.ArtistDetail
import com.vktracks.player.data.model.AudiosPage
import com.vktracks.player.data.model.CatalogBlock
import com.vktracks.player.data.model.CatalogButtonItem
import com.vktracks.player.data.model.LyricLine
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.SearchResult
import com.vktracks.player.data.model.StreamMix
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.TrackRestriction
import com.vktracks.player.data.model.VkMixSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class VkApiClient(
    private val authManager: VkAuthManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val itunesApiClient = ItunesApiClient()

    // Официальные профили User-Agent (VK Музыка и VK Android)
    private val vkMusicUserAgent = "VKMusic/1.1.0 (Android 14; SDK 34; arm64-v8a; ru)"
    private val vkAndroidUserAgent = "VKAndroidApp/8.17-15376 (Android 9.0; SDK 28; armeabi-v7a; xiaomi Redmi Note 3; ru; 1920x1080)"
    private val apiVersion = "5.155"

    @Volatile
    private var preferredUserAgent = vkMusicUserAgent

    companion object {
        // Быстрый In-Memory кэш обложек треков, альбомов и артистов (0 мс, по эталону VKX)
        private val trackCoverCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val albumCoverCache = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val artistPhotoCache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun preloadCovers(covers: Map<String, String>) {
            trackCoverCache.putAll(covers)
        }

        fun cacheCover(fullId: String, coverUrl: String) {
            trackCoverCache[fullId] = coverUrl
        }

        fun clearCaches() {
            trackCoverCache.clear()
            albumCoverCache.clear()
            artistPhotoCache.clear()
        }

        fun albumKey(artist: String, albumTitle: String?): String? {
            if (albumTitle.isNullOrBlank() || artist.isBlank()) return null
            val cleanArtist = artist.trim().lowercase()
            val cleanAlbum = albumTitle.trim().lowercase()
            if (cleanAlbum in listOf("single", "single edit", "remix", "ep", "сингл", "unknown")) return null
            return "${cleanArtist}__${cleanAlbum}"
        }
    }

    fun preloadCovers(covers: Map<String, String>) = Companion.preloadCovers(covers)
    fun cacheCover(fullId: String, coverUrl: String) = Companion.cacheCover(fullId, coverUrl)
    fun albumKey(artist: String, albumTitle: String?): String? = Companion.albumKey(artist, albumTitle)

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

        // Проверяем токен через users.get (v=5.131)
        val url = "https://api.vk.com/method/users.get?v=5.131&access_token=$token&fields=photo_100"
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

    suspend fun getMyAudios(offset: Int = 0, count: Int = 100): AudiosPage = withContext(Dispatchers.IO) {
        val userId = authManager.getUserId()
        val params = mutableMapOf(
            "offset" to offset.toString(),
            "count" to count.toString(),
            "extended" to "1"
        )
        if (userId > 0) {
            params["owner_id"] = userId.toString()
        }
        val json = callVkApi("audio.get", params) ?: return@withContext AudiosPage()
        val response = json.optJSONObject("response")
        val items = response?.optJSONArray("items")
            ?: response?.optJSONArray("audios")
            ?: json.optJSONArray("response")
            ?: json.optJSONArray("items")

        val totalCount = response?.optInt("count", 0) ?: 0
        val rawCount = items?.length() ?: 0
        val rawTracks = if (items != null) parseTracksArray(items) else emptyList()
        val enrichedTracks = enrichTracksWithVkDetails(rawTracks)
        AudiosPage(tracks = enrichedTracks, totalCount = totalCount, rawCount = rawCount)
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

    suspend fun getPlaylistTracks(playlistId: Long, ownerId: Long, accessKey: String? = null, isAlbum: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        // Резервный вызов для официальных/редакционных плейлистов и альбомов VK (содержит объект playlist с нативной обложкой)
        val pParams = mutableMapOf(
            "playlist_id" to playlistId.toString(),
            "owner_id" to ownerId.toString(),
            "need_audios" to "1",
            "count" to "150",
            "extended" to "1"
        )
        if (!accessKey.isNullOrEmpty()) {
            pParams["access_key"] = accessKey
        }
        val pJson = callVkApi("audio.getPlaylistById", pParams)
        if (pJson != null && !pJson.has("error")) {
            val pResp = pJson.optJSONObject("response")
            val playlistObj = pResp?.optJSONObject("playlist") ?: pResp
            val playlistCover = playlistObj?.let { extractCoverUrl(it) }

            val isAlbumRelease = isAlbum || playlistObj?.let { obj ->
                val albumType = obj.optString("album_type", "").trim().lowercase()
                val isExplicitAlbum = obj.optBoolean("is_album", false) || obj.optInt("is_album", 0) == 1
                val mainArtists = obj.optJSONArray("main_artists")
                val hasMainArtists = mainArtists != null && mainArtists.length() > 0
                val year = obj.optInt("year").takeIf { it > 0 }
                isExplicitAlbum || (albumType.isNotEmpty() && albumType != "playlist" && albumType != "user" && albumType != "null") || (hasMainArtists && year != null)
            } ?: false

            val audios = pResp?.optJSONArray("audios") ?: pResp?.optJSONArray("items")
            if (audios != null) {
                val parsed = parseTracksArray(audios)
                // Для альбомов (1 артист) обложка релиза подходит ко всем трекам.
                // Для плейлистов (сборка разных треков и исполнителей) обложку плейлиста ко всем трекам НЕ применяем!
                return@withContext if (isAlbumRelease && !playlistCover.isNullOrEmpty()) {
                    parsed.map { if (it.coverUrl.isNullOrEmpty()) it.copy(coverUrl = playlistCover) else it }
                } else {
                    parsed
                }
            }
        }

        val params = mutableMapOf(
            "playlist_id" to playlistId.toString(),
            "album_id" to playlistId.toString(),
            "owner_id" to ownerId.toString(),
            "count" to "150",
            "extended" to "1"
        )
        if (!accessKey.isNullOrEmpty()) {
            params["access_key"] = accessKey
        }
        val json = callVkApi("audio.get", params)
        if (json != null && !json.has("error")) {
            val tracks = parseTracksList(json)
            if (tracks.isNotEmpty()) return@withContext tracks
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

                    // Каталожные секции и блоки поиска
                    val catalog = resp.optJSONObject("catalog")
                    val sections = catalog?.optJSONArray("sections") ?: resp.optJSONArray("sections")
                    if (sections != null) {
                        for (s in 0 until sections.length()) {
                            val sec = sections.optJSONObject(s) ?: continue
                            val blocks = sec.optJSONArray("blocks") ?: continue
                            for (b in 0 until blocks.length()) {
                                val blk = blocks.optJSONObject(b) ?: continue
                                val dt = blk.optString("data_type").lowercase()
                                val items = blk.optJSONArray("items")
                                    ?: blk.optJSONArray("audios")
                                    ?: blk.optJSONArray("playlists")
                                    ?: blk.optJSONArray("artists")
                                if (items != null && items.length() > 0) {
                                    if ((dt.contains("artist") || blk.optString("layout_name").contains("artist")) && artists.isEmpty()) {
                                        artists = parseArtistsArray(items)
                                    } else if (dt.contains("playlist") && playlists.isEmpty()) {
                                        playlists = parsePlaylistsArray(items)
                                    } else if ((dt.contains("audio") || dt.contains("track")) && tracks.isEmpty()) {
                                        tracks = parseTracksArray(items)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "catalog.getAudioSearch failed for '$q'", e)
        }

        // 2. Поиск артистов через прямой audio.searchArtists если каталог не вернул
        if (artists.isEmpty()) {
            try {
                val artJson = callVkApi("audio.searchArtists", mapOf(
                    "q" to q,
                    "count" to "30"
                ))
                if (artJson != null && !artJson.has("error")) {
                    val artResp = artJson.optJSONObject("response")
                    val artItems = artResp?.optJSONArray("items")
                        ?: artJson.optJSONArray("response")
                        ?: artResp?.optJSONArray("artists")
                    if (artItems != null && artItems.length() > 0) {
                        artists = parseArtistsArray(artItems)
                    }
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "audio.searchArtists failed for '$q'", e)
            }
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

    suspend fun getCatalogSections(): Map<String, String> = withContext(Dispatchers.IO) {
        val mapping = mutableMapOf<String, String>()
        try {
            val json = callVkApi("catalog.getAudio", mapOf("count" to "20"))
            val response = json?.optJSONObject("response")
            val catalog = response?.optJSONObject("catalog")
            val sections = catalog?.optJSONArray("sections")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val sec = sections.optJSONObject(i) ?: continue
                    val secId = sec.optString("id")
                    val secUrl = sec.optString("url")
                    val secTitle = sec.optString("title").lowercase()

                    if (secId.isNotEmpty()) {
                        val match = Regex("[?&]section=([a-zA-Z0-9_-]+)").find(secUrl)
                        if (match != null) {
                            val sectionKey = match.groupValues[1]
                            mapping[sectionKey] = secId
                        }
                        if (secTitle.isNotEmpty()) {
                            mapping[secTitle] = secId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VkApiClient", "Failed to getCatalogSections", e)
        }
        mapping
    }

    suspend fun getCatalogSection(sectionId: String): List<CatalogBlock> = withContext(Dispatchers.IO) {
        try {
            val json = callVkApi("catalog.getSection", mapOf(
                "section_id" to sectionId,
                "need_blocks" to "1",
                "count" to "100"
            ))
            if (json != null && !json.has("error")) {
                return@withContext parseCatalogBlocks(json)
            }
        } catch (e: Exception) {
            Log.e("VkApiClient", "Failed to getCatalogSection $sectionId", e)
        }
        emptyList()
    }

    suspend fun getCatalogBlockItems(blockId: String, startFrom: String? = null): Pair<List<Track>, List<Playlist>> = withContext(Dispatchers.IO) {
        val params = mutableMapOf("block_id" to blockId, "count" to "100")
        if (!startFrom.isNullOrEmpty()) {
            params["start_from"] = startFrom
        }
        val json = callVkApi("catalog.getBlockItems", params) ?: return@withContext Pair(emptyList(), emptyList())
        val blockObj = json.optJSONObject("response")?.optJSONObject("block") ?: json.optJSONObject("response")
        val tracks = if (blockObj != null) {
            val audios = blockObj.optJSONArray("audios") ?: blockObj.optJSONArray("items")
            if (audios != null) parseTracksArray(audios) else emptyList()
        } else emptyList()

        val playlists = if (blockObj != null) {
            val pl = blockObj.optJSONArray("playlists") ?: blockObj.optJSONArray("items")
            if (pl != null) parsePlaylistsArray(pl) else emptyList()
        } else emptyList()

        Pair(tracks, playlists)
    }

    suspend fun getCatalogMixes(userAudios: List<Track>): List<CatalogBlock> = withContext(Dispatchers.IO) {
        val result = mutableListOf<CatalogBlock>()
        val sections = getCatalogSections()
        val allSectionId = sections["all"]
            ?: sections["custom"]
            ?: sections.entries.firstOrNull { it.key.contains("глав") || it.key.contains("микс") }?.value
            ?: sections.values.firstOrNull()

        if (!allSectionId.isNullOrEmpty()) {
            val parsedBlocks = getCatalogSection(allSectionId)
            for (block in parsedBlocks) {
                if (block.mixes.isNotEmpty() || block.dataType.contains("mix") || block.layoutName.contains("mix") || block.title.contains("Микс", ignoreCase = true) || block.title.contains("VK mix", ignoreCase = true)) {
                    result.add(block)
                }
            }
        }

        // 1. Секция VK mix
        val hasVkMix = result.any { it.id == "section_vk_mix" || it.title.contains("VK mix", ignoreCase = true) }
        if (!hasVkMix) {
            result.add(
                0,
                CatalogBlock(
                    id = "section_vk_mix",
                    title = "VK mix",
                    subtitle = "Динамический плейлист, который подстраивается под ваши вкусы",
                    layoutName = "audio_stream_mix",
                    dataType = "audio_stream_mixes",
                    mixes = listOf(
                        StreamMix(
                            id = "personal_radio",
                            title = "VK mix",
                            subtitle = "Динамический плейлист, который подстраивается под ваши вкусы",
                            mixType = "personal"
                        )
                    )
                )
            )
        }

        // 2. Секция Миксы по артистам (если VK не вернул их в секции - формируем по артистам из библиотеки пользователя)
        val hasArtistMixes = result.any { it.id == "section_artist_mixes" || it.title.contains("артист", ignoreCase = true) }
        if (!hasArtistMixes && userAudios.isNotEmpty()) {
            val artistNames = userAudios.map { it.artist.split(",", "&", "feat.", "ft.").first().trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }
                .take(12)

            val artistMixes = artistNames.map { artistName ->
                val sampleTrack = userAudios.firstOrNull { it.artist.contains(artistName, ignoreCase = true) }
                val otherArtists = userAudios.asSequence()
                    .map { it.artist.split(",", "&").first().trim() }
                    .filter { it.isNotBlank() && !it.equals(artistName, ignoreCase = true) }
                    .distinct()
                    .take(3)
                    .joinToString(", ")

                StreamMix(
                    id = "artist_mix_${artistName.hashCode()}",
                    title = artistName,
                    subtitle = if (otherArtists.isNotBlank()) otherArtists else "Микс треков $artistName",
                    imageUrl = sampleTrack?.coverUrl,
                    mixType = "artist"
                )
            }
            if (artistMixes.isNotEmpty()) {
                result.add(
                    CatalogBlock(
                        id = "section_artist_mixes",
                        title = "Миксы по артистам",
                        subtitle = "Персональные станции по вашим исполнителям",
                        layoutName = "artists_slider",
                        dataType = "audio_stream_mixes",
                        mixes = artistMixes
                    )
                )
            }
        }

        // 3. Секция: Миксы по жанрам (Скриншот 2)
        val hasGenreMixes = result.any { it.id == "section_genre_mixes" || it.title.contains("жанр", ignoreCase = true) }
        if (!hasGenreMixes) {
            val defaultGenreMixes = listOf(
                StreamMix("genre_pop", "Поп", "Популярная музыка и хиты", mixType = "genre"),
                StreamMix("genre_rock", "Рок", "Русский и мировой рок", mixType = "genre"),
                StreamMix("genre_hiphop", "Хип-хоп", "Рэп и хип-хоп новинки", mixType = "genre"),
                StreamMix("genre_dance", "Танцевальная", "Club, Dance & EDM", mixType = "genre"),
                StreamMix("genre_indie", "Инди", "Инди-рок и альтернатива", mixType = "genre"),
                StreamMix("genre_electro", "Электроника", "Electronic, Synth & Wave", mixType = "genre"),
                StreamMix("genre_phonk", "Фонк", "Phonk & Drift", mixType = "genre")
            )
            result.add(
                CatalogBlock(
                    id = "section_genre_mixes",
                    title = "Миксы по жанрам",
                    subtitle = "Плейлисты по вашим любимым стилям",
                    layoutName = "genres_slider",
                    dataType = "audio_stream_mixes",
                    mixes = defaultGenreMixes
                )
            )
        }

        result
    }

    suspend fun getCatalogExplore(): Pair<List<Track>, List<CatalogBlock>> = withContext(Dispatchers.IO) {
        val sections = getCatalogSections()
        val exploreSectionId = sections["explore"]
            ?: sections.entries.firstOrNull { it.key.contains("explore") || it.key.contains("обзор") }?.value

        val blocks = mutableListOf<CatalogBlock>()
        var chart = listOf<Track>()

        if (!exploreSectionId.isNullOrEmpty()) {
            val parsedBlocks = getCatalogSection(exploreSectionId)
            for (block in parsedBlocks) {
                if (chart.isEmpty() && block.tracks.isNotEmpty() &&
                    (block.title.contains("Чарт", ignoreCase = true) || block.title.contains("Топ", ignoreCase = true))) {
                    chart = block.tracks
                } else {
                    blocks.add(block)
                }
            }
        }

        // Если чарт не обнаружен в блоках, запрашиваем популярные треки через audio.getPopular
        if (chart.isEmpty()) {
            chart = getChartTracks(50)
        }

        // Если VK вернул пустые алгоритмические секции, добавляем стандартные карточки
        val hasAlgorithms = blocks.any { it.id == "section_algorithms" || it.title.contains("алгоритм", ignoreCase = true) || it.title.contains("Для вас", ignoreCase = true) }
        if (!hasAlgorithms) {
            blocks.add(
                0,
                CatalogBlock(
                    id = "section_algorithms",
                    title = "Собрано алгоритмами",
                    subtitle = "На основе вашей истории и предпочтений",
                    layoutName = "algorithm_cards",
                    dataType = "algorithm_cards"
                )
            )
        }

        val hasGenres = blocks.any { it.id == "section_genres_grid" || it.title.equals("Жанры", ignoreCase = true) }
        if (!hasGenres) {
            blocks.add(
                1,
                CatalogBlock(
                    id = "section_genres_grid",
                    title = "Жанры",
                    layoutName = "categories_grid",
                    dataType = "categories_grid",
                    buttons = listOf(
                        CatalogButtonItem("btn_pop", "Поп", colorHex = "#0088FF"),
                        CatalogButtonItem("btn_rock", "Рок", colorHex = "#0066EE"),
                        CatalogButtonItem("btn_hiphop", "Хип-хоп", colorHex = "#00AAFF"),
                        CatalogButtonItem("btn_electro", "Электроника", colorHex = "#0055DD")
                    )
                )
            )
        }

        val hasMoods = blocks.any { it.id == "section_moods_grid" || it.title.contains("Настроени", ignoreCase = true) }
        if (!hasMoods) {
            blocks.add(
                2,
                CatalogBlock(
                    id = "section_moods_grid",
                    title = "Настроения и занятия",
                    layoutName = "categories_grid",
                    dataType = "categories_grid",
                    buttons = listOf(
                        CatalogButtonItem("btn_happy", "Радостно", colorHex = "#FFA726"),
                        CatalogButtonItem("btn_sad", "Грустно", colorHex = "#42A5F5"),
                        CatalogButtonItem("btn_work", "Работа", colorHex = "#5C6BC0"),
                        CatalogButtonItem("btn_sport", "Тренировка", colorHex = "#EF5350")
                    )
                )
            )
        }

        // Если в блоках не оказалось подборок редакции, гарантируем секцию с реальными подборками VK Музыки
        val hasEditorial = blocks.any { it.id == "section_editorial" || it.title.contains("редакц", ignoreCase = true) || it.title.contains("собрано", ignoreCase = true) }
        if (!hasEditorial) {
            val editorialPlaylists = searchPlaylists("VK Музыка", 25)
            if (editorialPlaylists.isNotEmpty()) {
                blocks.add(
                    3,
                    CatalogBlock(
                        id = "section_editorial",
                        title = "Собрано редакцией",
                        subtitle = "Официальные подборки VK Музыки",
                        layoutName = "slider",
                        dataType = "music_playlists",
                        playlists = editorialPlaylists
                    )
                )
            }
        }

        Pair(chart, blocks)
    }

    suspend fun getMixTracks(mix: StreamMix): List<Track> = withContext(Dispatchers.IO) {
        when (mix.mixType) {
            "personal" -> getPersonalRadio()
            "artist" -> searchCatalog(mix.title).tracks.ifEmpty { getRecommendations(0, 30) }
            "genre" -> searchCatalog(mix.title).tracks.ifEmpty { getRecommendations(0, 30) }
            else -> {
                if (mix.actionUrl != null && mix.actionUrl.contains("section_id=")) {
                    val secId = Regex("section_id=([a-zA-Z0-9_-]+)").find(mix.actionUrl)?.groupValues?.get(1)
                    if (secId != null) {
                        val blocks = getCatalogSection(secId)
                        val tracks = blocks.flatMap { it.tracks }
                        if (tracks.isNotEmpty()) return@withContext tracks
                    }
                }
                getRecommendations(0, 30)
            }
        }
    }

    suspend fun getCategoryTracks(categoryTitle: String): List<Track> = withContext(Dispatchers.IO) {
        searchCatalog(categoryTitle).tracks.ifEmpty { getRecommendations(0, 30) }
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
            "count" to count.toString(),
            "extended" to "1"
        )
        if (userId > 0) {
            params["user_id"] = userId.toString()
        }
        val json = callVkApi("audio.getRecommendations", params) ?: return@withContext emptyList()
        parseTracksList(json)
    }

    suspend fun getPersonalRadio(settings: VkMixSettings? = null): List<Track> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken() ?: return@withContext emptyList()
        val userId = authManager.getUserId()

        var tracks: List<Track> = emptyList()

        // 1. Если задан конкретный жанр или настроение, ищем целевые треки
        if (settings != null && (settings.genre != "any" || settings.mood != "any")) {
            val queryTag = when {
                settings.genre != "any" -> settings.genre
                settings.mood == "energetic" -> "energy dance"
                settings.mood == "calm" -> "chill relax"
                settings.mood == "sad" -> "sad melancholy"
                settings.mood == "happy" -> "happy party"
                else -> ""
            }
            if (queryTag.isNotEmpty()) {
                val genreTracks = searchCatalog(queryTag).tracks
                if (genreTracks.isNotEmpty()) {
                    tracks = genreTracks
                }
            }
        }

        // 2. Попытка запроса в UMA (VK Mix / Boom радио как в VKX)
        if (tracks.isEmpty()) {
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
                            val umaTracks = parseUmaTracks(JSONObject(body))
                            if (umaTracks.isNotEmpty()) tracks = umaTracks
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "UMA personal radio request failed, falling back to recommendations", e)
            }
        }

        // 3. Fallback на аудиорекомендации VK
        if (tracks.isEmpty()) {
            tracks = getRecommendations(0, 40)
        }

        // 4. Фильтрация по языку
        if (settings != null && tracks.isNotEmpty()) {
            val ruRegex = Regex("[а-яА-ЯёЁ]")
            if (settings.language == "russian") {
                val filtered = tracks.filter { ruRegex.containsMatchIn(it.title) || ruRegex.containsMatchIn(it.artist) }
                if (filtered.isNotEmpty()) tracks = filtered
            } else if (settings.language == "foreign") {
                val filtered = tracks.filter { !ruRegex.containsMatchIn(it.title) && !ruRegex.containsMatchIn(it.artist) }
                if (filtered.isNotEmpty()) tracks = filtered
            }
        }

        tracks
    }

    /**
     * Быстрое обогащение треков для списков (0 мс, 0 сетевых блокировок).
     * В списках отображаются по умолчанию только существующие обложки из In-Memory / Room кэша.
     * Загрузка обложек из каталога происходит строго фоном при воспроизведении трека в плеере.
     */
    suspend fun enrichTracksWithVkDetails(tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()

        tracks.map { track ->
            if (track.coverUrl.isNullOrEmpty() || !isValidImageUrl(track.coverUrl)) {
                val cached = trackCoverCache[track.fullId]
                    ?: albumKey(track.artist, track.albumTitle)?.let { albumCoverCache[it] }
                if (!cached.isNullOrEmpty()) track.copy(coverUrl = cached) else track
            } else {
                trackCoverCache[track.fullId] = track.coverUrl
                albumKey(track.artist, track.albumTitle)?.let { albumCoverCache[it] = track.coverUrl }
                track
            }
        }
    }

    suspend fun resolveTrack(track: Track): Track = withContext(Dispatchers.IO) {
        val cachedCover = trackCoverCache[track.fullId]
            ?: albumKey(track.artist, track.albumTitle)?.let { albumCoverCache[it] }
        val trackWithCached = if (track.coverUrl.isNullOrEmpty() && !cachedCover.isNullOrEmpty()) {
            track.copy(coverUrl = cachedCover)
        } else track

        if (trackWithCached.streamUrl.isNotEmpty() && !trackWithCached.coverUrl.isNullOrEmpty() && isValidImageUrl(trackWithCached.coverUrl)) {
            return@withContext trackWithCached
        }

        var resolvedStream = trackWithCached.streamUrl
        var resolvedCover = trackWithCached.coverUrl
        var resolvedAlbum = trackWithCached.albumTitle
        var resolvedLyrics = trackWithCached.lyricsId
        var resolvedRestriction = trackWithCached.restriction
        var resolvedRestrictionText = trackWithCached.restrictionText

        // Если не хватает потока или обложки, получаем детальные метаданные через audio.getById
        if (resolvedStream.isEmpty() || resolvedCover.isNullOrEmpty() || !isValidImageUrl(resolvedCover)) {
            val audiosParam = if (!trackWithCached.accessKey.isNullOrEmpty()) {
                "${trackWithCached.ownerId}_${trackWithCached.id}_${trackWithCached.accessKey}"
            } else {
                "${trackWithCached.ownerId}_${trackWithCached.id}"
            }
            val json = callVkApi("audio.getById", mapOf("audios" to audiosParam))
            val responseArray = json?.optJSONArray("response")
            val item = responseArray?.optJSONObject(0)
                ?: json?.optJSONObject("response")?.optJSONArray("items")?.optJSONObject(0)
                ?: json?.optJSONObject("response")?.optJSONArray("audios")?.optJSONObject(0)
            if (item != null) {
                val parsed = parseTrackObject(item)
                if (parsed != null) {
                    if (parsed.streamUrl.isNotEmpty()) resolvedStream = parsed.streamUrl
                    if (!parsed.coverUrl.isNullOrEmpty() && isValidImageUrl(parsed.coverUrl)) resolvedCover = parsed.coverUrl
                    if (parsed.albumTitle != null) resolvedAlbum = parsed.albumTitle
                    if (parsed.lyricsId != null) resolvedLyrics = parsed.lyricsId
                    resolvedRestriction = parsed.restriction
                    resolvedRestrictionText = parsed.restrictionText
                }
            }
        }

        // 1. Проверяем кэш обложки альбома трека (если у трека указан альбом)
        if (resolvedCover.isNullOrEmpty() || !isValidImageUrl(resolvedCover)) {
            val albumCached = albumKey(trackWithCached.artist, resolvedAlbum)?.let { albumCoverCache[it] }
            if (!albumCached.isNullOrEmpty() && isValidImageUrl(albumCached)) {
                resolvedCover = albumCached
            }
        }

        // 2. Если нативной обложки нет в объекте трека ВК — запрашиваем официальную студийную обложку в iTunes
        if (resolvedCover.isNullOrEmpty() || !isValidImageUrl(resolvedCover)) {
            val itunesCover = itunesApiClient.fetchTrackCover(trackWithCached.artist, trackWithCached.title)
            if (!itunesCover.isNullOrEmpty() && isValidImageUrl(itunesCover)) {
                resolvedCover = itunesCover
            }
        }

        // 3. Если обложки нет и в iTunes — оставляем null (чистая векторная нота-заглушка)
        if (!resolvedCover.isNullOrEmpty()) {
            trackCoverCache[trackWithCached.fullId] = resolvedCover
            albumKey(trackWithCached.artist, resolvedAlbum)?.let { albumCoverCache[it] = resolvedCover }
        }

        trackWithCached.copy(
            streamUrl = resolvedStream,
            coverUrl = resolvedCover,
            albumTitle = resolvedAlbum,
            lyricsId = resolvedLyrics,
            restriction = resolvedRestriction,
            restrictionText = resolvedRestrictionText
        )
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

    fun cleanCatalogQuery(artist: String, title: String): String {
        val mainArtist = extractMainArtist(artist)
        val cleanTitle = cleanTrackTitle(title)
        return "$mainArtist $cleanTitle".trim()
    }

    suspend fun resolveTrackUrl(track: Track): String? = withContext(Dispatchers.IO) {
        val resolved = resolveTrack(track)
        if (resolved.streamUrl.isNotEmpty()) resolved.streamUrl else null
    }

    suspend fun getRecordNowPlaying(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.radiorecord.ru/api/stations/now/")
                .header("User-Agent", "VKTracks/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val result = json.optJSONArray("result")
                    if (result != null) {
                        for (i in 0 until result.length()) {
                            val st = result.optJSONObject(i) ?: continue
                            if (st.optInt("id") == 15016 || st.optInt("id") == 67) {
                                return@withContext st
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    fun connectStudio21WebSocket(onTrackUpdate: (title: String, artist: String, coverUrl: String?) -> Unit): WebSocket {
        val req = Request.Builder()
            .url("wss://meta.hostingradio.ru/emg/ws?format=native")
            .header("Origin", "https://studio21.ru")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        return client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("{\"subscribe\":{\"full\":[\"studio21\"]},\"fetch\":{\"full\":[\"studio21\"]}}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val s21 = json.optJSONObject("studio21") ?: return
                    val current = s21.optJSONObject("current")
                    val targetObj = if (current != null && current.optString("title").isNotEmpty()) {
                        current
                    } else {
                        val prev = s21.optJSONArray("previous")
                        if (prev != null && prev.length() > 0) prev.optJSONObject(prev.length() - 1) else null
                    }

                    if (targetObj != null) {
                        var artist = targetObj.optString("artist").ifEmpty { targetObj.optString("singer") }.trim()
                        var title = targetObj.optString("title").ifEmpty { targetObj.optString("song") }.trim()
                        if (artist.isEmpty() && title.contains(" - ")) {
                            val parts = title.split(" - ", limit = 2)
                            artist = parts[0].trim()
                            title = parts[1].trim()
                        }
                        val rawCover = targetObj.optString("coverImageUrl600").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl300").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl100").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("cover").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("image").takeIf { it.isNotEmpty() }

                        if (title.isNotEmpty()) {
                            onTrackUpdate(title, artist, rawCover)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("VkApiClient", "Failed to parse Studio 21 WS message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("VkApiClient", "Studio 21 WS connection failed: ${t.message}")
            }
        })
    }

    suspend fun getArtistDetail(artist: Artist): ArtistDetail = withContext(Dispatchers.IO) {
        var popularTracks = emptyList<Track>()
        var albums = emptyList<Playlist>()
        var updatedPhotoUrl = artist.photoUrl
        var bio: String? = null

        // 1. Полноценный каталог страницы артиста catalog.getAudioArtist
        if (artist.id.isNotEmpty()) {
            try {
                val json = callVkApi("catalog.getAudioArtist", mapOf(
                    "artist_id" to artist.id,
                    "need_blocks" to "1"
                ))
                if (json != null && !json.has("error")) {
                    val resp = json.optJSONObject("response")
                    if (resp != null) {
                        val artistObj = resp.optJSONObject("artist")
                        if (artistObj != null) {
                            val photos = parseArtistsArray(JSONArray().put(artistObj))
                            if (photos.isNotEmpty() && photos[0].photoUrl != null) {
                                updatedPhotoUrl = photos[0].photoUrl
                            }
                        }

                        val catalog = resp.optJSONObject("catalog")
                        val sections = catalog?.optJSONArray("sections") ?: resp.optJSONArray("sections")
                        if (sections != null) {
                            for (s in 0 until sections.length()) {
                                val sec = sections.optJSONObject(s) ?: continue
                                val blocks = sec.optJSONArray("blocks") ?: continue
                                for (b in 0 until blocks.length()) {
                                    val blk = blocks.optJSONObject(b) ?: continue
                                    val dt = blk.optString("data_type").lowercase()
                                    val items = blk.optJSONArray("items")
                                        ?: blk.optJSONArray("audios")
                                        ?: blk.optJSONArray("playlists")
                                    if (items != null && items.length() > 0) {
                                        if ((dt.contains("audio") || dt.contains("track")) && popularTracks.isEmpty()) {
                                            popularTracks = parseTracksArray(items)
                                        } else if (dt.contains("playlist") || dt.contains("album")) {
                                            val parsedAlbums = parsePlaylistsArray(items)
                                            albums = (albums + parsedAlbums).distinctBy { it.fullId }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "catalog.getAudioArtist failed for ${artist.id}", e)
            }
        }

        // 2. Резервный вызов: audio.getAudiosByArtist если треки не получены из каталога
        if (popularTracks.isEmpty() && artist.id.isNotEmpty()) {
            try {
                val json = callVkApi("audio.getAudiosByArtist", mapOf(
                    "artist_id" to artist.id,
                    "count" to "50"
                ))
                if (json != null && !json.has("error")) {
                    popularTracks = parseTracksList(json)
                }
            } catch (e: Exception) {
                Log.w("VkApiClient", "audio.getAudiosByArtist failed", e)
            }
        }

        // 3. Fallback: поиск по каталогу по имени исполнителя
        if (popularTracks.isEmpty()) {
            val res = searchCatalog(artist.name)
            popularTracks = res.tracks
            if (albums.isEmpty()) {
                albums = res.playlists.filter { it.isAlbum || it.title.contains(artist.name, ignoreCase = true) }
            }
        }

        if (albums.isEmpty()) {
            try {
                albums = searchPlaylists(artist.name, 30).filter { it.isAlbum || it.title.contains(artist.name, ignoreCase = true) }
            } catch (_: Exception) {}
        }

        val updatedArtist = artist.copy(photoUrl = updatedPhotoUrl ?: artist.photoUrl)
        val finalTracks = if (!updatedArtist.photoUrl.isNullOrEmpty()) {
            popularTracks.map { if (it.coverUrl.isNullOrEmpty()) it.copy(coverUrl = updatedArtist.photoUrl) else it }
        } else {
            popularTracks
        }
        ArtistDetail(
            artist = updatedArtist,
            popularTracks = finalTracks,
            albums = albums,
            bio = bio
        )
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

        val primaryUa = if (method.startsWith("catalog.")) vkMusicUserAgent else preferredUserAgent
        val fallbackUa = if (primaryUa == vkAndroidUserAgent) vkMusicUserAgent else vkAndroidUserAgent

        // Попытка 1: С основным User-Agent (VK Music для официального токена, с авто-фоллбэком)
        var result = executeMethodCall(method, params, token, primaryUa, "api.vk.com")
            ?: executeMethodCall(method, params, token, primaryUa, "api.vk.ru")

        if (result != null) {
            val errCode = result.optJSONObject("error")?.optInt("error_code") ?: 0
            if (errCode in listOf(3, 8, 15, 23, 27, 28)) {
                Log.w("VkApiClient", "VK API $method returned error $errCode with $primaryUa, retrying with $fallbackUa")
                val fallbackResult = executeMethodCall(method, params, token, fallbackUa, "api.vk.com")
                    ?: executeMethodCall(method, params, token, fallbackUa, "api.vk.ru")
                if (fallbackResult != null && !fallbackResult.has("error")) {
                    preferredUserAgent = fallbackUa
                    return fallbackResult
                }
            }
        }
        return result
    }

    private fun executeMethodCall(method: String, params: Map<String, String>, token: String, ua: String, host: String = "api.vk.com"): JSONObject? {
        val methodApiVersion = params["v"] ?: if (method.startsWith("catalog.")) "5.155" else "5.131"
        val formBuilder = FormBody.Builder()
            .add("access_token", token)
            .add("v", methodApiVersion)

        for ((k, v) in params) {
            if (k != "v") {
                formBuilder.add(k, v)
            }
        }

        val requestBuilder = Request.Builder()
            .url("https://$host/method/$method")
            .header("User-Agent", ua)
            .post(formBuilder.build())

        if (ua.contains("VKMusic")) {
            requestBuilder.header("X-App-Id", "6767438")
            requestBuilder.header("X-Client-Version", "10683")
        } else if (ua.contains("VKAndroidApp")) {
            requestBuilder.header("X-App-Id", "2274003")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("VkApiClient", "HTTP error ${response.code} for $method on $host")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.has("error")) {
                    val err = json.optJSONObject("error")
                    Log.e("VkApiClient", "VK API error for $method on $host: [${err?.optInt("error_code")}] ${err?.optString("error_msg")}")
                }
                json
            }
        } catch (e: Exception) {
            Log.e("VkApiClient", "Network exception in callVkApi($method) on $host", e)
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

        // Обложка из альбома / объекта трека (нативные данные VK API v5.155 / Catalog / Boom)
        val album = item.optJSONObject("album")
        var coverUrl = extractCoverUrl(item)
        val fullId = "${ownerId}_$id"

        if (!coverUrl.isNullOrEmpty()) {
            trackCoverCache[fullId] = coverUrl
            val albumTitle = album?.optString("title")
            val key = albumKey(artist, albumTitle)
            if (key != null) {
                albumCoverCache[key] = coverUrl
            }
        } else {
            coverUrl = trackCoverCache[fullId]
                ?: albumKey(artist, album?.optString("title"))?.let { albumCoverCache[it] }
        }

        // Ограничения трека (Murglar RestrictionStatus: правообладатель, геоблок, подписка)
        val contentRestrictedObj = item.optJSONObject("content_restricted")
        val contentRestrictedInt = item.optInt("content_restricted", 0)
        val restrictionStatusInt = item.optInt("restriction_status", 0)

        val restrictionCode = when {
            restrictionStatusInt > 0 -> restrictionStatusInt
            contentRestrictedInt > 0 -> contentRestrictedInt
            contentRestrictedObj != null -> 1
            else -> 0
        }

        val restriction = TrackRestriction.from(restrictionCode)
        val restrictionText = contentRestrictedObj?.optString("text")?.takeIf { it.isNotEmpty() }
            ?: contentRestrictedObj?.optString("title")?.takeIf { it.isNotEmpty() }

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
            accessKey = accessKey,
            restriction = restriction,
            restrictionText = restrictionText
        )
    }

    /**
     * Универсальный извлекатель нативных обложек VK API (v5.131 - v5.155).
     * Обрабатывает вложенные объекты album, photo, thumb, thumbs, cover, sizes
     * и прямые строковые ссылки с максимальным разрешением (600x600, 300x300, 1200x1200).
     */
    private fun isValidImageUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        // Исключаем аудиопотоки
        if (lower.contains(".m3u8") || lower.contains(".mp3") || lower.contains(".mp4") || lower.contains(".aac")) return false
        return true
    }

    private fun extractCoverUrl(item: JSONObject): String? {
        // 1. Из вложенного объекта альбома
        val album = item.optJSONObject("album")
        if (album != null) {
            val fromAlbum = extractCoverFromContainer(album)
            if (!fromAlbum.isNullOrEmpty() && isValidImageUrl(fromAlbum)) return fromAlbum
        }

        // 2. Напрямую из контейнера объекта (для объектов альбомов/плейлистов)
        val fromItem = extractCoverFromContainer(item)
        if (!fromItem.isNullOrEmpty() && isValidImageUrl(fromItem)) return fromItem

        // 3. Прямые строковые поля
        val direct = item.optString("photo_600").takeIf { it.isNotEmpty() }
            ?: item.optString("photo_300").takeIf { it.isNotEmpty() }
            ?: item.optString("photo_1200").takeIf { it.isNotEmpty() }
            ?: item.optString("photo_270").takeIf { it.isNotEmpty() }
            ?: item.optString("photo_135").takeIf { it.isNotEmpty() }
            ?: item.optString("photo_68").takeIf { it.isNotEmpty() }
            ?: item.optString("cover_url").takeIf { it.isNotEmpty() }
        if (!direct.isNullOrEmpty() && isValidImageUrl(direct)) return direct

        return null
    }

    private fun extractCoverFromContainer(container: JSONObject): String? {
        // A. thumb (JSONObject)
        val thumb = container.optJSONObject("thumb")
        if (thumb != null) {
            val u = extractCoverFromImageObject(thumb)
            if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
        }

        // B. photo (JSONObject)
        val photo = container.optJSONObject("photo")
        if (photo != null) {
            val u = extractCoverFromImageObject(photo)
            if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
        }

        // C. cover (JSONObject)
        val cover = container.optJSONObject("cover")
        if (cover != null) {
            val u = cover.optString("uri").takeIf { it.isNotEmpty() }
                ?: cover.optString("url").takeIf { it.isNotEmpty() }
                ?: extractCoverFromImageObject(cover)
            if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
        }

        // D. thumbs или photo в виде массива
        val thumbs = container.optJSONArray("thumbs") ?: container.optJSONArray("photo")
        if (thumbs != null && thumbs.length() > 0) {
            for (i in (thumbs.length() - 1) downTo 0) {
                val tObj = thumbs.optJSONObject(i) ?: continue
                val u = extractCoverFromImageObject(tObj)
                if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
            }
        }

        // E. sizes (стандартный формат размеров фото VK)
        val sizes = container.optJSONArray("sizes")
        if (sizes != null && sizes.length() > 0) {
            val last = sizes.optJSONObject(sizes.length() - 1)
            val u = last?.optString("url")?.takeIf { it.isNotEmpty() }
                ?: last?.optString("src")?.takeIf { it.isNotEmpty() }
            if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
        }

        // F. Прямые строковые поля внутри контейнера
        val str = container.optString("photo_600").takeIf { it.isNotEmpty() }
            ?: container.optString("photo_300").takeIf { it.isNotEmpty() }
            ?: container.optString("photo_1200").takeIf { it.isNotEmpty() }
            ?: container.optString("photo_270").takeIf { it.isNotEmpty() }
            ?: container.optString("photo_135").takeIf { it.isNotEmpty() }
            ?: container.optString("photo_68").takeIf { it.isNotEmpty() }
            ?: container.optString("thumb").takeIf { it.startsWith("http") }
            ?: container.optString("photo").takeIf { it.startsWith("http") }
        return str?.takeIf { isValidImageUrl(it) }
    }

    private fun extractCoverFromImageObject(obj: JSONObject): String? {
        val str = obj.optString("photo_600").takeIf { it.isNotEmpty() }
            ?: obj.optString("photo_300").takeIf { it.isNotEmpty() }
            ?: obj.optString("photo_1200").takeIf { it.isNotEmpty() }
            ?: obj.optString("photo_270").takeIf { it.isNotEmpty() }
            ?: obj.optString("photo_135").takeIf { it.isNotEmpty() }
            ?: obj.optString("photo_68").takeIf { it.isNotEmpty() }
            ?: obj.optString("url").takeIf { it.isNotEmpty() }
            ?: obj.optString("uri").takeIf { it.isNotEmpty() }
            ?: obj.optString("src").takeIf { it.isNotEmpty() }
        if (str != null && isValidImageUrl(str)) return str

        val sizes = obj.optJSONArray("sizes")
        if (sizes != null && sizes.length() > 0) {
            val u = extractBestFromSizes(sizes)
            if (!u.isNullOrEmpty() && isValidImageUrl(u)) return u
        }

        return null
    }

    private fun extractBestFromSizes(sizes: JSONArray): String? {
        var bestUrl: String? = null
        var bestWidth = 0
        for (i in 0 until sizes.length()) {
            val sObj = sizes.optJSONObject(i) ?: continue
            val u = sObj.optString("url").takeIf { it.isNotEmpty() }
                ?: sObj.optString("src").takeIf { it.isNotEmpty() } ?: continue
            val w = sObj.optInt("width", 0)
            if (w in 250..650) {
                return u
            }
            if (w > bestWidth) {
                bestWidth = w
                bestUrl = u
            }
        }
        return bestUrl ?: sizes.optJSONObject(sizes.length() - 1)?.optString("url")?.takeIf { it.isNotEmpty() }
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

        // Вариант 1: response.section.blocks
        val section = resp.optJSONObject("section")
        if (section != null) {
            val bl = section.optJSONArray("blocks")
            if (bl != null) {
                for (b in 0 until bl.length()) {
                    bl.optJSONObject(b)?.let { rawBlocks.add(it) }
                }
            }
        }

        // Вариант 2: response.catalog.sections[i].blocks
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

        // Вариант 3: response.blocks
        val directBlocks = resp.optJSONArray("blocks")
        if (directBlocks != null) {
            for (b in 0 until directBlocks.length()) {
                directBlocks.optJSONObject(b)?.let { rawBlocks.add(it) }
            }
        }

        var pendingHeaderTitle: String? = null
        var pendingSubtitle: String? = null

        for (blockJson in rawBlocks) {
            val layout = blockJson.optJSONObject("layout")
            val layoutName = layout?.optString("name", "slider") ?: "slider"

            // Заголовочный блок (Header) в VK API предваряет блок с содержимым
            if (layoutName == "header") {
                pendingHeaderTitle = layout?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: blockJson.optString("title").takeIf { it.isNotBlank() }
                pendingSubtitle = layout?.optString("subtitle")?.takeIf { it.isNotBlank() }
                    ?: blockJson.optString("subtitle").takeIf { it.isNotBlank() }
                continue
            }

            val id = blockJson.optString("id", "")
            val title = layout?.optString("title")?.takeIf { it.isNotBlank() }
                ?: blockJson.optString("title").takeIf { it.isNotBlank() }
                ?: pendingHeaderTitle
                ?: "Подборка"

            val subtitle = layout?.optString("subtitle")?.takeIf { it.isNotBlank() }
                ?: blockJson.optString("subtitle").takeIf { it.isNotBlank() }
                ?: pendingSubtitle

            pendingHeaderTitle = null
            pendingSubtitle = null

            val dataType = blockJson.optString("data_type", "").lowercase()
            val nextFrom = blockJson.optString("next_from").takeIf { it.isNotBlank() }

            // Парсим плейлисты блока
            val playlistsJson = blockJson.optJSONArray("playlists")
                ?: if (dataType.contains("playlist") || dataType.contains("album")) blockJson.optJSONArray("items") else null
            val playlists = if (playlistsJson != null) parsePlaylistsArray(playlistsJson) else emptyList()

            // Парсим треки блока
            val audiosJson = blockJson.optJSONArray("audios")
                ?: if (dataType.contains("audio") || dataType.contains("track")) blockJson.optJSONArray("items") else null
            val tracks = if (audiosJson != null) parseTracksArray(audiosJson) else emptyList()

            // Парсим миксы блока (VK Mix, миксы по артистам, миксы по жанрам)
            val mixesList = mutableListOf<StreamMix>()
            val mixItems = blockJson.optJSONArray("audio_stream_mixes")
                ?: if (dataType.contains("mix") || layoutName.contains("mix")) blockJson.optJSONArray("items") else null
            if (mixItems != null) {
                for (i in 0 until mixItems.length()) {
                    val mixObj = mixItems.optJSONObject(i) ?: continue
                    val mTitle = mixObj.optString("title", "")
                    val mSub = mixObj.optString("subtitle", "")
                    val photoObj = mixObj.optJSONObject("photo")
                    val thumbs = mixObj.optJSONArray("thumbs") ?: photoObj?.optJSONArray("thumbs")
                    val imgList = mutableListOf<String>()
                    if (thumbs != null) {
                        for (t in 0 until thumbs.length()) {
                            val tObj = thumbs.optJSONObject(t)
                            val tUrl = tObj?.optString("photo_300") ?: tObj?.optString("photo_600") ?: tObj?.optString("photo_100")
                            if (!tUrl.isNullOrEmpty()) imgList.add(tUrl)
                        }
                    }
                    val mImg = photoObj?.optString("photo_300") ?: photoObj?.optString("photo_600") ?: mixObj.optString("image")
                    if (mImg.isNotEmpty() && imgList.isEmpty()) {
                        imgList.add(mImg)
                    }

                    val actionObj = mixObj.optJSONObject("action")
                    val actionUrl = actionObj?.optString("url") ?: mixObj.optString("url")

                    mixesList.add(
                        StreamMix(
                            id = mixObj.optString("id", "mix_$i"),
                            title = mTitle.ifBlank { "Микс" },
                            subtitle = mSub,
                            imageUrl = mImg.takeIf { it.isNotEmpty() },
                            images = imgList,
                            actionUrl = actionUrl.takeIf { it.isNotEmpty() },
                            mixType = if (dataType.contains("artist") || layoutName.contains("artist")) "artist"
                                      else if (dataType.contains("genre") || layoutName.contains("genre")) "genre"
                                      else if (dataType.contains("personal")) "personal"
                                      else "general"
                        )
                    )
                }
            }

            // Парсим кнопки и категории блока (Жанры, Настроения, Алгоритмы)
            val buttonsList = mutableListOf<CatalogButtonItem>()
            val btnItems = blockJson.optJSONArray("buttons")
                ?: if (dataType.contains("custom") || dataType.contains("button") || layoutName.contains("button") || layoutName.contains("grid")) blockJson.optJSONArray("items") else null
            if (btnItems != null) {
                for (i in 0 until btnItems.length()) {
                    val btnObj = btnItems.optJSONObject(i) ?: continue
                    val bTitle = btnObj.optString("title", btnObj.optString("text", ""))
                    if (bTitle.isNotBlank()) {
                        val bUrl = btnObj.optJSONObject("action")?.optString("url") ?: btnObj.optString("url")
                        val bImg = btnObj.optJSONObject("icon")?.optString("uri") ?: btnObj.optString("image")
                        buttonsList.add(
                            CatalogButtonItem(
                                id = btnObj.optString("id", "btn_$i"),
                                title = bTitle,
                                url = bUrl.takeIf { it.isNotEmpty() },
                                iconUrl = bImg.takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
            }

            if (playlists.isNotEmpty() || tracks.isNotEmpty() || mixesList.isNotEmpty() || buttonsList.isNotEmpty()) {
                result.add(
                    CatalogBlock(
                        id = id.ifEmpty { "block_${result.size}" },
                        title = title,
                        subtitle = subtitle,
                        layoutName = layoutName,
                        dataType = dataType,
                        mixes = mixesList,
                        buttons = buttonsList,
                        playlists = playlists,
                        tracks = tracks,
                        nextFrom = nextFrom
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
            val coverUrl = extractCoverUrl(item)

            val albumType = item.optString("album_type", "").trim().lowercase()
            val isExplicitAlbum = item.optBoolean("is_album", false) || item.optInt("is_album", 0) == 1
            val mainArtists = item.optJSONArray("main_artists")
            val hasMainArtists = mainArtists != null && mainArtists.length() > 0
            val year = item.optInt("year").takeIf { it > 0 }
            val mainArtist = mainArtists?.optJSONObject(0)?.optString("name")

            val isAlbum = isExplicitAlbum ||
                (albumType.isNotEmpty() && albumType != "playlist" && albumType != "user" && albumType != "null") ||
                (hasMainArtists && year != null)

            list.add(
                Playlist(
                    id = id,
                    ownerId = ownerId,
                    title = title,
                    description = description,
                    coverUrl = coverUrl,
                    count = count,
                    accessKey = accessKey,
                    isAlbum = isAlbum,
                    year = year,
                    mainArtist = mainArtist
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

            val photoObj = item.optJSONObject("photo")
            if (photoObj != null) {
                photoUrl = photoObj.optString("photo_600").takeIf { it.isNotEmpty() }
                    ?: photoObj.optString("photo_300").takeIf { it.isNotEmpty() }
                    ?: photoObj.optString("photo_1200").takeIf { it.isNotEmpty() }
                    ?: photoObj.optString("url").takeIf { it.isNotEmpty() }
            }
            if (photoUrl == null) {
                val photoArr = item.optJSONArray("photo")
                if (photoArr != null && photoArr.length() > 0) {
                    val last = photoArr.optJSONObject(photoArr.length() - 1)
                    photoUrl = last?.optString("url")?.takeIf { it.isNotEmpty() }
                        ?: photoArr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotEmpty() }
                }
            }
            if (photoUrl == null) {
                photoUrl = item.optString("photo_600").takeIf { it.isNotEmpty() }
                    ?: item.optString("photo_300").takeIf { it.isNotEmpty() }
                    ?: item.optString("photo_1200").takeIf { it.isNotEmpty() }
                    ?: item.optString("avatar_url").takeIf { it.isNotEmpty() }
            }
            if (name.isNotEmpty()) {
                list.add(Artist(id = id, name = name, photoUrl = photoUrl))
            }
        }
        return list
    }
}
