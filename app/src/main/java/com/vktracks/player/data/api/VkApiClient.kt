package com.vktracks.player.data.api

import android.util.Log
import androidx.core.text.HtmlCompat
import com.vktracks.player.data.cover.CoverArtResolver
import com.vktracks.player.data.model.Artist
import com.vktracks.player.data.model.ArtistDetail
import com.vktracks.player.data.model.CatalogBlock
import com.vktracks.player.data.model.CatalogButtonItem
import com.vktracks.player.data.model.LyricLine
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.SearchResult
import com.vktracks.player.data.model.StreamMix
import com.vktracks.player.data.model.Track
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
    private val authManager: VkAuthManager,
    private val coverArtResolver: CoverArtResolver
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Официальные профили User-Agent (VK Музыка и VK Android)
    private val vkMusicUserAgent = "VKMusic/1.1.0 (Android 14; SDK 34; arm64-v8a; ru)"
    private val vkAndroidUserAgent = "VKAndroidApp/8.17-15376 (Android 9.0; SDK 28; armeabi-v7a; xiaomi Redmi Note 3; ru; 1920x1080)"
    private val apiVersion = "5.155"

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
            "count" to count.toString()
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
                        val artist = targetObj.optString("artist")
                        val title = targetObj.optString("title")
                        val cover = targetObj.optString("coverImageUrl600").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl300").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl100").takeIf { it.isNotEmpty() }
                        if (title.isNotEmpty()) {
                            onTrackUpdate(title, artist, cover)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("VkApiClient", "Failed to parse Studio 21 WS message", e)
                }
            }
        })
    }

    suspend fun getStudio21CurrentTrack(): Triple<String, String, String?>? = withContext(Dispatchers.IO) {
        // 1. Официальный сайт Studio 21 (прямой российский источник)
        try {
            val req = Request.Builder()
                .url("https://studio21.ru")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val nameMatcher = java.util.regex.Pattern.compile("""<h3 class="name"[^>]*>(.*?)</h3>""", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                    val singerMatcher = java.util.regex.Pattern.compile("""<h3 class="singers"[^>]*>(.*?)</h3>""", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                    if (nameMatcher.find() && singerMatcher.find()) {
                        val rawTitle = nameMatcher.group(1)?.trim() ?: ""
                        val rawArtist = singerMatcher.group(1)?.trim() ?: ""
                        if (rawTitle.isNotEmpty()) {
                            val title = HtmlCompat.fromHtml(rawTitle, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                            val artist = HtmlCompat.fromHtml(rawArtist, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                            val imgMatcher = java.util.regex.Pattern.compile("""(https://meta\.hostingradio\.ru/files/elements/cover-images/[^"'\s]+)""").matcher(html)
                            val cover = if (imgMatcher.find()) imgMatcher.group(1) else coverArtResolver.resolveCover(artist, title)
                            return@withContext Triple(title, artist, cover)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "studio21.ru scrape failed, falling back to top-radio", e)
        }

        // 2. Резервный российский источник top-radio.ru
        try {
            val req = Request.Builder()
                .url("https://top-radio.ru/playlist/studio-21")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: return@withContext null
                    val pattern = java.util.regex.Pattern.compile(
                        """<div class="name_track">\s*<span class="artist">(.*?)</span>\s*-\s*<span class="song">(.*?)</span>""",
                        java.util.regex.Pattern.CASE_INSENSITIVE
                    )
                    val matcher = pattern.matcher(html)
                    if (matcher.find()) {
                        val rawArtist = matcher.group(1)?.trim() ?: ""
                        val rawTitle = matcher.group(2)?.trim() ?: ""
                        if (rawTitle.isNotEmpty()) {
                            val artist = HtmlCompat.fromHtml(rawArtist, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                            val title = HtmlCompat.fromHtml(rawTitle, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                            val cover = coverArtResolver.resolveCover(artist, title)
                            return@withContext Triple(title, artist, cover)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VkApiClient", "getStudio21CurrentTrack fallback failed", e)
        }
        null
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
        ArtistDetail(
            artist = updatedArtist,
            popularTracks = popularTracks,
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
