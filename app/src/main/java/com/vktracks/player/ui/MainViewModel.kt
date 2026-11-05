package com.vktracks.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.vktracks.player.data.api.VkApiClient
import com.vktracks.player.data.api.VkAuthManager
import com.vktracks.player.data.cover.CoverArtResolver
import com.vktracks.player.data.downloader.TrackDownloader
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.AppUpdateInfo
import com.vktracks.player.data.model.Lyrics
import com.vktracks.player.data.model.Playlist
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.updater.UpdateChecker
import com.vktracks.player.service.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val database = AppDatabase.getInstance(application)
    val authManager = VkAuthManager(application)
    val coverArtResolver = CoverArtResolver(database.coverCacheDao())
    val apiClient = VkApiClient(authManager, coverArtResolver)
    val downloader = TrackDownloader(application, database)
    val updateChecker = UpdateChecker(application)

    val isAuthorized = authManager.authState

    private val _myTracks = MutableStateFlow<List<Track>>(emptyList())
    val myTracks: StateFlow<List<Track>> = _myTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _radioTracks = MutableStateFlow<List<Track>>(emptyList())
    val radioTracks: StateFlow<List<Track>> = _radioTracks.asStateFlow()

    private val _recommendations = MutableStateFlow<List<Track>>(emptyList())
    val recommendations: StateFlow<List<Track>> = _recommendations.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private var searchJob: Job? = null

    private val _curatedPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val curatedPlaylists: StateFlow<List<Playlist>> = _curatedPlaylists.asStateFlow()

    private val _chartTracks = MutableStateFlow<List<Track>>(emptyList())
    val chartTracks: StateFlow<List<Track>> = _chartTracks.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    private val _selectedPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedPlaylistTracks: StateFlow<List<Track>> = _selectedPlaylistTracks.asStateFlow()

    private val _isLoadingPlaylistTracks = MutableStateFlow(false)
    val isLoadingPlaylistTracks: StateFlow<Boolean> = _isLoadingPlaylistTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _availableUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val availableUpdate: StateFlow<AppUpdateInfo?> = _availableUpdate.asStateFlow()

    private val _updateProgress = MutableStateFlow(-1)
    val updateProgress: StateFlow<Int> = _updateProgress.asStateFlow()

    // Слушатели плеера
    val currentTrack = PlaybackService.currentTrack
    val isPlaying = PlaybackService.isPlaying
    val currentPosMs = PlaybackService.currentPositionMs
    val durationMs = PlaybackService.durationMs
    val isShuffle = PlaybackService.isShuffle
    val repeatMode = PlaybackService.repeatMode

    // Офлайн треки из локальной базы Room
    val downloadedTracks: StateFlow<List<Track>> = database.trackDao().getDownloadedTracks()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // История прослушивания
    val historyTracks: StateFlow<List<Track>> = database.trackDao().getHistoryTracks()
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            authManager.authState.collect { auth ->
                if (auth) {
                    loadLibrary()
                    loadPersonalRadio()
                }
            }
        }

        // Автоматическая подгрузка текста песни при смене трека
        viewModelScope.launch {
            currentTrack.collect { track ->
                if (track != null) {
                    loadLyrics(track)
                } else {
                    _currentLyrics.value = null
                }
            }
        }

        // Проверка автообновления
        checkForUpdates()
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
            try {
                _myTracks.value = apiClient.getMyAudios(0, 100)
                _playlists.value = apiClient.getPlaylists(0, 50)
                _recommendations.value = apiClient.getRecommendations(0, 50)
                _curatedPlaylists.value = apiClient.getCuratedPlaylists(30)
                _chartTracks.value = apiClient.getChartTracks(50)
            } catch (_: Exception) {}
            _isLoading.value = false
        }
    }

    fun loadPersonalRadio() {
        viewModelScope.launch {
            try {
                _radioTracks.value = apiClient.getPersonalRadio()
            } catch (_: Exception) {}
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            delay(350)
            try {
                _searchResults.value = apiClient.searchAudios(q)
            } catch (_: Exception) {
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        _selectedPlaylist.value = playlist
        _selectedPlaylistTracks.value = emptyList()
        viewModelScope.launch {
            _isLoadingPlaylistTracks.value = true
            try {
                _selectedPlaylistTracks.value = apiClient.getPlaylistTracks(playlist.id, playlist.ownerId, playlist.accessKey)
            } catch (_: Exception) {
            } finally {
                _isLoadingPlaylistTracks.value = false
            }
        }
    }

    fun closePlaylist() {
        _selectedPlaylist.value = null
        _selectedPlaylistTracks.value = emptyList()
    }

    fun downloadAllInPlaylist(tracks: List<Track>) {
        viewModelScope.launch {
            for (track in tracks) {
                downloader.downloadTrack(track)
            }
        }
    }

    fun playTrack(track: Track, queue: List<Track>) {
        viewModelScope.launch {
            var currentTrackToPlay = track
            if (currentTrackToPlay.streamUrl.isEmpty() && !currentTrackToPlay.isLocal) {
                val resolvedUrl = apiClient.resolveTrackUrl(currentTrackToPlay)
                if (!resolvedUrl.isNullOrEmpty()) {
                    currentTrackToPlay = currentTrackToPlay.copy(streamUrl = resolvedUrl)
                }
            }
            val updatedQueue = queue.map { if (it.fullId == currentTrackToPlay.fullId) currentTrackToPlay else it }
            val index = updatedQueue.indexOfFirst { it.fullId == currentTrackToPlay.fullId }.coerceAtLeast(0)
            PlaybackService.instance?.playQueue(updatedQueue, index)
        }
    }

    fun togglePlayPause() {
        PlaybackService.instance?.togglePlayPause()
    }

    fun playNext() {
        PlaybackService.instance?.playNext()
    }

    fun playPrevious() {
        PlaybackService.instance?.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        PlaybackService.instance?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        PlaybackService.instance?.toggleShuffle()
    }

    fun toggleRepeat() {
        PlaybackService.instance?.toggleRepeat()
    }

    fun downloadTrack(track: Track) {
        viewModelScope.launch {
            downloader.downloadTrack(track)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val update = updateChecker.checkForUpdates()
            if (update != null) {
                _availableUpdate.value = update
            }
        }
    }

    fun installUpdate(info: AppUpdateInfo) {
        viewModelScope.launch {
            _updateProgress.value = 0
            updateChecker.downloadAndInstallApk(info) { progress ->
                _updateProgress.value = progress
            }
        }
    }

    fun dismissUpdate() {
        _availableUpdate.value = null
    }

    private fun loadLyrics(track: Track) {
        viewModelScope.launch {
            _currentLyrics.value = null
            val lyrics = apiClient.getLyrics(track.id, track.lyricsId)
            _currentLyrics.value = lyrics
        }
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
