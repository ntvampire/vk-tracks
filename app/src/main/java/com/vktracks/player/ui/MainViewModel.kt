package com.vktracks.player.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.vktracks.player.data.api.VkApiClient
import com.vktracks.player.data.api.VkAuthManager
import com.vktracks.player.data.cache.MediaCacheManager
import com.vktracks.player.data.downloader.TrackDownloader
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.AppUpdateInfo
import com.vktracks.player.data.model.Artist
import com.vktracks.player.data.model.ArtistDetail
import com.vktracks.player.data.model.CatalogBlock
import com.vktracks.player.data.model.CatalogButtonItem
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.RadioStation
import com.vktracks.player.data.model.SearchResult
import com.vktracks.player.data.model.StreamMix
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.VkMixSettings
import com.vktracks.player.data.updater.UpdateChecker
import com.vktracks.player.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val database = AppDatabase.getInstance(application)
    val authManager = VkAuthManager(application)
    val apiClient = VkApiClient(authManager)
    val downloader = TrackDownloader(application, database)
    val updateChecker = UpdateChecker(application)

    val isAuthorized = authManager.authState

    private val _myTracks = MutableStateFlow<List<Track>>(emptyList())
    val myTracks: StateFlow<List<Track>> = _myTracks.asStateFlow()

    private val _isLoadingMoreTracks = MutableStateFlow(false)
    val isLoadingMoreTracks: StateFlow<Boolean> = _isLoadingMoreTracks.asStateFlow()
    private var myAudiosOffset = 0
    private var canLoadMoreTracks = true
    private var isLoadingMoreJob: Job? = null

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _radioTracks = MutableStateFlow<List<Track>>(emptyList())
    val radioTracks: StateFlow<List<Track>> = _radioTracks.asStateFlow()

    private val _recommendations = MutableStateFlow<List<Track>>(emptyList())
    val recommendations: StateFlow<List<Track>> = _recommendations.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _searchPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val searchPlaylists: StateFlow<List<Playlist>> = _searchPlaylists.asStateFlow()

    private val _searchArtists = MutableStateFlow<List<Artist>>(emptyList())
    val searchArtists: StateFlow<List<Artist>> = _searchArtists.asStateFlow()

    private val _likedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val likedTrackIds: StateFlow<Set<String>> = _likedTrackIds.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private var searchJob: Job? = null

    private val _mixBlocks = MutableStateFlow<List<CatalogBlock>>(emptyList())
    val mixBlocks: StateFlow<List<CatalogBlock>> = _mixBlocks.asStateFlow()

    private val _catalogBlocks = MutableStateFlow<List<CatalogBlock>>(emptyList())
    val catalogBlocks: StateFlow<List<CatalogBlock>> = _catalogBlocks.asStateFlow()

    private val _chartTracks = MutableStateFlow<List<Track>>(emptyList())
    val chartTracks: StateFlow<List<Track>> = _chartTracks.asStateFlow()

    private val _vkMixSettings = MutableStateFlow(VkMixSettings())
    val vkMixSettings: StateFlow<VkMixSettings> = _vkMixSettings.asStateFlow()

    private val playlistTracksCache = ConcurrentHashMap<String, List<Track>>()

    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    private val _selectedPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedPlaylistTracks: StateFlow<List<Track>> = _selectedPlaylistTracks.asStateFlow()

    private val _isLoadingPlaylistTracks = MutableStateFlow(false)
    val isLoadingPlaylistTracks: StateFlow<Boolean> = _isLoadingPlaylistTracks.asStateFlow()

    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _selectedArtistDetail = MutableStateFlow<ArtistDetail?>(null)
    val selectedArtistDetail: StateFlow<ArtistDetail?> = _selectedArtistDetail.asStateFlow()

    private val _isLoadingArtistDetail = MutableStateFlow(false)
    val isLoadingArtistDetail: StateFlow<Boolean> = _isLoadingArtistDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<AppUpdateInfo?> = _availableUpdate.asStateFlow()

    val updateProgress: StateFlow<Int> = com.vktracks.player.data.updater.UpdateDownloadService.progressFlow

    // Слушатели плеера
    val currentTrack = PlaybackService.currentTrack
    val currentQueue = PlaybackService.currentQueueFlow
    val isPlaying = PlaybackService.isPlaying
    val currentPosMs = PlaybackService.currentPositionMs
    val durationMs = PlaybackService.durationMs
    val isShuffle = PlaybackService.isShuffle
    val repeatMode = PlaybackService.repeatMode

    // Быстрый In-Memory реактивный кэш разрешенных обложек (для предотвращения исчезновения обложек в списках)
    private val _resolvedCovers = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedCovers: StateFlow<Map<String, String>> = _resolvedCovers.asStateFlow()

    // Полноэкранный плеер
    private val _showFullPlayer = MutableStateFlow(false)
    val showFullPlayer: StateFlow<Boolean> = _showFullPlayer.asStateFlow()

    fun openFullPlayer() {
        if (PlaybackService.currentTrack.value != null) {
            _showFullPlayer.value = true
        }
    }

    fun closeFullPlayer() {
        _showFullPlayer.value = false
    }

    // Офлайн треки из локальной базы Room (с проверкой физического наличия файла на диске)
    val downloadedTracks: StateFlow<List<Track>> = database.trackDao().getDownloadedTracks()
        .map { list ->
            list.mapNotNull { entity ->
                val path = entity.localFilePath
                if (!path.isNullOrBlank() && (path.startsWith("content://") || File(path).exists())) {
                    entity.toTrack()
                } else {
                    viewModelScope.launch(Dispatchers.IO) {
                        database.trackDao().removeDownload(entity.id)
                    }
                    null
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadedTrackIds: StateFlow<Set<String>> = downloadedTracks
        .map { list -> list.map { it.fullId }.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    // Кэш-менеджер потоков (VKX & Murglar)
    val cacheManager = MediaCacheManager.getInstance(application)
    private val _cacheSizeDisplay = MutableStateFlow(cacheManager.getFormattedCacheSize())
    val cacheSizeDisplay: StateFlow<String> = _cacheSizeDisplay.asStateFlow()
    private val _isCacheEnabled = MutableStateFlow(cacheManager.isCacheEnabled())
    val isCacheEnabled: StateFlow<Boolean> = _isCacheEnabled.asStateFlow()
    private val _maxCacheMb = MutableStateFlow(cacheManager.getMaxCacheMb())
    val maxCacheMb: StateFlow<Int> = _maxCacheMb.asStateFlow()

    // История прослушивания
    val historyTracks: StateFlow<List<Track>> = database.trackDao().getHistoryTracks()
        .map { list -> list.map { it.toTrack() } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        initMediaController()

        viewModelScope.launch {
            authManager.authState.collect { auth ->
                if (auth) {
                    loadLibrary()
                    loadPersonalRadio()
                }
            }
        }

        // Предзагрузка сохраненных обложек из базы данных Room в память для мгновенного показа в списках
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = database.trackDao().getAllCachedCovers()
                val map = cached.mapNotNull { tuple ->
                    tuple.coverUrl?.takeIf { it.isNotEmpty() }?.let { tuple.id to it }
                }.toMap()
                apiClient.preloadCovers(map)
                _resolvedCovers.value = map
            } catch (_: Exception) {}
        }

        // Автоматическая подгрузка текста песни и синхронизация обложек при смене трека
        var lastLyricsTrackId: String? = null
        viewModelScope.launch {
            currentTrack.collect { track ->
                if (track != null) {
                    if (track.fullId != lastLyricsTrackId) {
                        lastLyricsTrackId = track.fullId
                        loadLyrics(track)
                    }
                    if (!track.coverUrl.isNullOrEmpty() && _resolvedCovers.value[track.fullId] != track.coverUrl) {
                        _resolvedCovers.value = _resolvedCovers.value + (track.fullId to track.coverUrl)
                    }
                } else {
                    lastLyricsTrackId = null
                    _currentLyrics.value = null
                }
            }
        }

        // Проверка автообновления
        checkForUpdates()
    }

    private var mediaController: MediaController? = null

    private fun initMediaController() {
        try {
            val context = getApplication<Application>()
            val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture.addListener({
                try {
                    mediaController = controllerFuture.get()
                    android.util.Log.d("MainViewModel", "MediaController bound to PlaybackService successfully")
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Failed to get MediaController", e)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to init MediaController", e)
        }
    }

    private fun ensurePlaybackService(onReady: (PlaybackService) -> Unit) {
        val current = PlaybackService.instance
        if (current != null) {
            onReady(current)
            return
        }
        val context = getApplication<Application>()
        try {
            val intent = Intent(context, PlaybackService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "Failed to start PlaybackService", e)
        }
        viewModelScope.launch {
            for (i in 1..25) {
                delay(60)
                PlaybackService.instance?.let {
                    onReady(it)
                    return@launch
                }
            }
            android.util.Log.e("MainViewModel", "PlaybackService failed to become ready after 1.5s")
        }
    }

    fun onAuthSuccess() {
        viewModelScope.launch {
            try {
                val profile = apiClient.getUserProfile()
                if (profile != null) {
                    val token = authManager.getAccessToken() ?: ""
                    val userId = authManager.getUserId()
                    authManager.saveAuthData(token, userId, profile.first, profile.second)
                }
            } catch (_: Exception) {}
            loadLibrary()
            loadPersonalRadio()
        }
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _isLoading.value = true
            myAudiosOffset = 0
            canLoadMoreTracks = true
            try {
                val page = apiClient.getMyAudios(0, 100)
                val audios = page.tracks
                myAudiosOffset = page.rawCount
                canLoadMoreTracks = page.rawCount > 0 && (page.totalCount <= 0 || myAudiosOffset < page.totalCount)
                _myTracks.value = audios
                _likedTrackIds.value = audios.map { it.fullId }.toSet()
                _playlists.value = apiClient.getPlaylists(0, 100)
                _recommendations.value = apiClient.getRecommendations(0, 50)

                // 1. Секции для вкладки "Слушать" (VK MIX, Миксы по артистам, Миксы по жанрам)
                _mixBlocks.value = apiClient.getCatalogMixes(audios)

                // 2. Секции для вкладки "Обзор"
                val exploreData = apiClient.getCatalogExplore()
                _chartTracks.value = exploreData.first
                _catalogBlocks.value = exploreData.second
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun playStreamMix(mix: StreamMix) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val tracks = apiClient.getMixTracks(mix)
                if (tracks.isNotEmpty()) {
                    playTrack(tracks.first(), tracks)
                }
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun playCategory(button: CatalogButtonItem) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val tracks = apiClient.getCategoryTracks(button.title)
                if (tracks.isNotEmpty()) {
                    playTrack(tracks.first(), tracks)
                }
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun playAlgorithm(type: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val tracks = if (type == "for_you") {
                    _recommendations.value.ifEmpty { apiClient.getRecommendations(0, 50) }
                } else {
                    apiClient.getPersonalRadio()
                }
                if (tracks.isNotEmpty()) {
                    playTrack(tracks.first(), tracks)
                }
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun openTopTracks() {
        val tracks = historyTracks.value.ifEmpty { _myTracks.value }
        if (tracks.isNotEmpty()) {
            _selectedPlaylist.value = Playlist(
                id = -101,
                ownerId = 0,
                title = "Топ прослушанных треков",
                description = "Самые популярные треки из вашей истории",
                count = tracks.size
            )
            _selectedPlaylistTracks.value = tracks
        }
    }

    fun openTopArtists() {
        val tracks = _myTracks.value
        if (tracks.isNotEmpty()) {
            _selectedPlaylist.value = Playlist(
                id = -102,
                ownerId = 0,
                title = "Топ исполнителей",
                description = "Треки ваших любимых исполнителей",
                count = tracks.size
            )
            _selectedPlaylistTracks.value = tracks
        }
    }

    fun openTopGenres() {
        val tracks = _recommendations.value.ifEmpty { _myTracks.value }
        if (tracks.isNotEmpty()) {
            _selectedPlaylist.value = Playlist(
                id = -103,
                ownerId = 0,
                title = "Топ жанров",
                description = "Популярные жанровые направления",
                count = tracks.size
            )
            _selectedPlaylistTracks.value = tracks
        }
    }

    private var radioMetadataJob: Job? = null
    private var studio21WebSocket: WebSocket? = null

    fun updateVkMixSettings(settings: VkMixSettings) {
        _vkMixSettings.value = settings
        playVkMix(settings)
    }

    fun loadPersonalRadio() {
        viewModelScope.launch {
            try {
                _radioTracks.value = apiClient.getPersonalRadio(_vkMixSettings.value)
            } catch (_: Exception) {}
        }
    }

    fun playVkMix(settings: VkMixSettings = _vkMixSettings.value) {
        radioMetadataJob?.cancel()
        studio21WebSocket?.close(1000, "Switch to mix")
        studio21WebSocket = null

        viewModelScope.launch {
            _isLoading.value = true
            try {
                var tracks = apiClient.getPersonalRadio(settings)
                if (settings.familiarity == "familiar" && _myTracks.value.isNotEmpty()) {
                    val favSub = _myTracks.value.shuffled().take(15)
                    tracks = (favSub + tracks).distinctBy { it.fullId }
                } else if (settings.familiarity == "discover" && _likedTrackIds.value.isNotEmpty()) {
                    val liked = _likedTrackIds.value
                    tracks = tracks.filter { !liked.contains(it.fullId) }
                } else if (settings.familiarity == "popular" && _chartTracks.value.isNotEmpty()) {
                    tracks = (_chartTracks.value.take(10) + tracks).distinctBy { it.fullId }
                }
                if (tracks.isNotEmpty()) {
                    _radioTracks.value = tracks
                    playTrack(tracks.first(), tracks)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to play VK mix", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun playRadio(station: RadioStation) {
        radioMetadataJob?.cancel()
        studio21WebSocket?.close(1000, "Switch radio")
        studio21WebSocket = null

        val radioTrack = station.toTrack()
        ensurePlaybackService { service ->
            service.playQueue(listOf(radioTrack), 0)
        }
        startRadioPolling(station)
    }

    private fun startRadioPolling(station: RadioStation) {
        radioMetadataJob?.cancel()
        studio21WebSocket?.close(1000, "Start radio")
        studio21WebSocket = null

        if (station == RadioStation.RADIO_RECORD) {
            radioMetadataJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val json = apiClient.getRecordNowPlaying()
                        if (json != null) {
                            val trackObj = json.optJSONObject("track")
                            var rawArtist = trackObj?.optString("artist", "")?.trim() ?: ""
                            var rawSong = trackObj?.optString("song", "")?.trim() ?: ""
                            if (rawArtist.isEmpty() && rawSong.contains(" - ")) {
                                val p = rawSong.split(" - ", limit = 2)
                                rawArtist = p[0].trim()
                                rawSong = p[1].trim()
                            }
                            val img = trackObj?.optString("image600")?.takeIf { it.isNotEmpty() }
                            if (rawSong.isNotBlank() || rawArtist.isNotBlank()) {
                                val title = if (rawSong.isNotBlank()) rawSong else station.title
                                val art = if (rawArtist.isNotBlank()) rawArtist else station.subtitle
                                val cov = img ?: station.logoUrl
                                withContext(Dispatchers.Main) {
                                    PlaybackService.instance?.updateLiveMetadata(
                                        title = title,
                                        artist = art,
                                        coverUrl = cov
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    delay(12000)
                }
            }
        } else if (station == RadioStation.STUDIO_21) {
            // Прямой официальный WebSocket холдинга Европейской медиагруппы (EMG)
            studio21WebSocket = apiClient.connectStudio21WebSocket { title, artist, coverUrl ->
                viewModelScope.launch(Dispatchers.Main) {
                    val art = if (artist.isNotBlank()) artist else station.subtitle
                    val cov = coverUrl ?: station.logoUrl
                    PlaybackService.instance?.updateLiveMetadata(
                        title = title,
                        artist = art,
                        coverUrl = cov
                    )
                }
            }
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        val q = query.trim()
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            _searchPlaylists.value = emptyList()
            _searchArtists.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            delay(350)
            try {
                val res = apiClient.searchCatalog(q)
                _searchResults.value = res.tracks
                _searchPlaylists.value = res.playlists
                _searchArtists.value = res.artists
            } catch (_: Exception) {
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun toggleTrackLike(track: Track) {
        viewModelScope.launch {
            val isCurrentlyLiked = _likedTrackIds.value.contains(track.fullId) || track.isLiked
            if (isCurrentlyLiked) {
                val success = apiClient.deleteAudio(track.id, track.ownerId)
                if (success) {
                    _likedTrackIds.value = _likedTrackIds.value - track.fullId
                    _myTracks.value = _myTracks.value.filter { it.fullId != track.fullId }
                    try {
                        database.trackDao().updateLike(track.fullId, false)
                    } catch (_: Exception) {}
                }
            } else {
                val newId = apiClient.addAudio(track.id, track.ownerId)
                if (newId != null) {
                    _likedTrackIds.value = _likedTrackIds.value + track.fullId
                    val liked = track.copy(isLiked = true)
                    _myTracks.value = listOf(liked) + _myTracks.value.filter { it.fullId != track.fullId }
                    try {
                        database.trackDao().updateLike(track.fullId, true)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun togglePlaylistFollow(playlist: Playlist) {
        viewModelScope.launch {
            val isFollowed = _playlists.value.any { it.id == playlist.id && it.ownerId == playlist.ownerId }
            if (isFollowed) {
                val success = apiClient.deletePlaylist(playlist.id, playlist.ownerId)
                if (success) {
                    _playlists.value = _playlists.value.filterNot { it.id == playlist.id && it.ownerId == playlist.ownerId }
                }
            } else {
                val success = apiClient.followPlaylist(playlist.id, playlist.ownerId, playlist.accessKey)
                if (success) {
                    _playlists.value = listOf(playlist) + _playlists.value.filterNot { it.id == playlist.id && it.ownerId == playlist.ownerId }
                }
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        val cacheKey = "${playlist.ownerId}_${playlist.id}"
        val cached = playlistTracksCache[cacheKey]
        if (!cached.isNullOrEmpty()) {
            _selectedPlaylistTracks.value = cached
            _isLoadingPlaylistTracks.value = false
        } else {
            _selectedPlaylistTracks.value = emptyList()
            _isLoadingPlaylistTracks.value = true
        }

        viewModelScope.launch {
            try {
                val tracks = apiClient.getPlaylistTracks(playlist.id, playlist.ownerId, playlist.accessKey, isAlbum = playlist.isAlbum)
                if (tracks.isNotEmpty()) {
                    playlistTracksCache[cacheKey] = tracks
                    _selectedPlaylistTracks.value = tracks
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to load playlist tracks", e)
            } finally {
                _isLoadingPlaylistTracks.value = false
            }
        }
    }

    fun closePlaylist() {
        _selectedPlaylist.value = null
        _selectedPlaylistTracks.value = emptyList()
    }

    fun openArtist(artist: Artist) {
        _selectedArtist.value = artist
        _selectedArtistDetail.value = ArtistDetail(artist = artist)
        _isLoadingArtistDetail.value = true
        viewModelScope.launch {
            try {
                val detail = apiClient.getArtistDetail(artist)
                _selectedArtistDetail.value = detail
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to load artist detail", e)
            } finally {
                _isLoadingArtistDetail.value = false
            }
        }
    }

    fun closeArtist() {
        _selectedArtist.value = null
        _selectedArtistDetail.value = null
    }

    fun downloadTrack(track: Track, subfolder: String? = null) {
        viewModelScope.launch {
            val isAlreadyDownloaded = downloadedTrackIds.value.contains(track.fullId) || track.isLocal
            if (isAlreadyDownloaded) {
                val deleted = downloader.deleteDownloadedTrack(track)
                if (deleted) {
                    Toast.makeText(getApplication(), "Удалено из скачанных: ${track.title}", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            var targetTrack = track
            if (targetTrack.streamUrl.isEmpty() && !targetTrack.isLocal) {
                val resolvedUrl = apiClient.resolveTrackUrl(targetTrack)
                if (!resolvedUrl.isNullOrEmpty()) {
                    targetTrack = targetTrack.copy(streamUrl = resolvedUrl)
                } else {
                    Toast.makeText(getApplication(), "Не удалось получить аудиопоток трека", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }

            Toast.makeText(getApplication(), "Скачивание: ${targetTrack.artist} — ${targetTrack.title}", Toast.LENGTH_SHORT).show()
            val result = downloader.downloadTrack(targetTrack, subfolder)
            if (result.isSuccess) {
                Toast.makeText(getApplication(), "Трек сохранён: ${targetTrack.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(getApplication(), "Ошибка скачивания: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun downloadAllInPlaylist(tracks: List<Track>, playlistTitle: String = "Playlist") {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            Toast.makeText(getApplication(), "Начата загрузка плейлиста «$playlistTitle» (${tracks.size} треков)", Toast.LENGTH_SHORT).show()
            var count = 0
            for (track in tracks) {
                if (downloadedTrackIds.value.contains(track.fullId) || track.isLocal) {
                    count++
                    continue
                }
                var targetTrack = track
                if (targetTrack.streamUrl.isEmpty()) {
                    val resolved = apiClient.resolveTrackUrl(targetTrack)
                    if (!resolved.isNullOrEmpty()) {
                        targetTrack = targetTrack.copy(streamUrl = resolved)
                    } else {
                        continue
                    }
                }
                val res = downloader.downloadTrack(targetTrack, playlistTitle)
                if (res.isSuccess) {
                    count++
                }
            }
            Toast.makeText(getApplication(), "Загрузка завершена: сохранено $count из ${tracks.size} треков", Toast.LENGTH_LONG).show()
        }
    }

    fun refreshCacheSize() {
        _cacheSizeDisplay.value = cacheManager.getFormattedCacheSize()
    }

    fun clearCache() {
        cacheManager.clearCache()
        refreshCacheSize()
        Toast.makeText(getApplication(), "Кэш треков очищен", Toast.LENGTH_SHORT).show()
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loader = coil.Coil.imageLoader(getApplication())
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                com.vktracks.player.data.api.VkApiClient.clearCaches()
                database.trackDao().clearCachedTrackCovers()
            } catch (_: Exception) {}
        }
    }

    fun setCacheMaxMb(mb: Int) {
        cacheManager.setMaxCacheMb(mb)
        _maxCacheMb.value = mb
        refreshCacheSize()
    }

    fun setCacheEnabled(enabled: Boolean) {
        cacheManager.setCacheEnabled(enabled)
        _isCacheEnabled.value = enabled
    }

    fun playTrack(track: Track, queue: List<Track>) {
        radioMetadataJob?.cancel()
        studio21WebSocket?.close(1000, "Normal track")
        studio21WebSocket = null
        viewModelScope.launch {
            var currentTrackToPlay = track
            // Если нет ссылки на поток (например, трек из расширенного поиска), резолвим ссылку
            if (currentTrackToPlay.streamUrl.isEmpty() && !currentTrackToPlay.isLocal) {
                currentTrackToPlay = apiClient.resolveTrack(currentTrackToPlay)
            }
            if (currentTrackToPlay.streamUrl.isEmpty() && !currentTrackToPlay.isLocal) {
                android.util.Log.w("MainViewModel", "Could not resolve streamUrl for ${currentTrackToPlay.fullId}")
                return@launch
            }
            val updatedQueue = if (currentTrackToPlay === track) queue else queue.map { if (it.fullId == currentTrackToPlay.fullId) currentTrackToPlay else it }
            val index = updatedQueue.indexOfFirst { it.fullId == currentTrackToPlay.fullId }.coerceAtLeast(0)
            // Моментальное сохранение обложки в реактивный кэш памяти и фоновая запись в БД Room
            if (!currentTrackToPlay.coverUrl.isNullOrEmpty() && currentTrackToPlay.coverUrl != track.coverUrl) {
                _resolvedCovers.value = _resolvedCovers.value + (currentTrackToPlay.fullId to currentTrackToPlay.coverUrl!!)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        database.trackDao().updateCover(currentTrackToPlay.fullId, currentTrackToPlay.coverUrl!!)
                    } catch (_: Exception) {}
                }
            }
            ensurePlaybackService { service ->
                service.playQueue(updatedQueue, index)
            }
        }
    }

    fun togglePlayPause() {
        val service = PlaybackService.instance
        if (service != null) {
            service.togglePlayPause()
        } else {
            mediaController?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }
    }

    fun playNext() {
        val service = PlaybackService.instance
        if (service != null) {
            service.playNext()
        } else {
            mediaController?.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        val service = PlaybackService.instance
        if (service != null) {
            service.playPrevious()
        } else {
            mediaController?.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        val service = PlaybackService.instance
        if (service != null) {
            service.seekTo(positionMs)
        } else {
            mediaController?.seekTo(positionMs)
        }
    }

    fun toggleShuffle() {
        val service = PlaybackService.instance
        if (service != null) {
            service.toggleShuffle()
        } else {
            mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
    }

    fun toggleRepeat() {
        val service = PlaybackService.instance
        if (service != null) {
            service.toggleRepeat()
        } else {
            mediaController?.let {
                val next = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                it.repeatMode = next
            }
        }
    }

    fun checkForUpdates(isManual: Boolean = false, onNoUpdate: (() -> Unit)? = null) {
        viewModelScope.launch {
            val update = updateChecker.checkForUpdates()
            if (update != null) {
                _availableUpdate.value = update
            } else if (isManual) {
                onNoUpdate?.invoke()
            }
        }
    }

    fun installUpdate(info: AppUpdateInfo) {
        com.vktracks.player.data.updater.UpdateDownloadService.start(
            context = getApplication(),
            versionName = info.versionName,
            downloadUrl = info.downloadUrl,
            mirrorUrl = info.mirrorUrl
        )
    }

    fun dismissUpdate() {
        _availableUpdate.value = null
    }

    fun loadMoreMyTracks() {
        if (!canLoadMoreTracks || _isLoadingMoreTracks.value || _isLoading.value) return
        if (_myTracks.value.isEmpty()) return

        isLoadingMoreJob?.cancel()
        isLoadingMoreJob = viewModelScope.launch {
            _isLoadingMoreTracks.value = true
            try {
                val offsetToFetch = myAudiosOffset
                val page = apiClient.getMyAudios(offset = offsetToFetch, count = 100)
                if (page.rawCount == 0) {
                    canLoadMoreTracks = false
                } else {
                    myAudiosOffset += page.rawCount
                    if (page.totalCount > 0 && myAudiosOffset >= page.totalCount) {
                        canLoadMoreTracks = false
                    }
                    if (page.tracks.isNotEmpty()) {
                        val currentIds = _myTracks.value.map { it.fullId }.toHashSet()
                        val distinctNew = page.tracks.filter { !currentIds.contains(it.fullId) }
                        if (distinctNew.isNotEmpty()) {
                            _myTracks.value = _myTracks.value + distinctNew
                            _likedTrackIds.value = _likedTrackIds.value + distinctNew.map { it.fullId }
                        }
                    }
                    if (page.rawCount < 100) {
                        canLoadMoreTracks = false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to load more tracks", e)
            } finally {
                _isLoadingMoreTracks.value = false
            }
        }
    }

    private var lyricsJob: Job? = null

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _currentLyrics.value = null
            val lyrics = apiClient.getLyrics(track)
            _currentLyrics.value = lyrics
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaController?.release()
        mediaController = null
        radioMetadataJob?.cancel()
        studio21WebSocket?.close(1000, "ViewModel cleared")
        studio21WebSocket = null
    }

    private fun TrackEntity.toTrack(): Track {
        return Track(
            id = this.trackId,
            ownerId = this.ownerId,
            title = this.title,
            artist = this.artist,
            duration = this.duration,
            streamUrl = this.streamUrl,
            coverUrl = this.coverUrl,
            albumTitle = this.albumTitle,
            isExplicit = this.isExplicit,
            lyricsId = this.lyricsId,
            accessKey = this.accessKey,
            isLiked = this.isLiked,
            localFilePath = this.localFilePath
        )
    }
}
