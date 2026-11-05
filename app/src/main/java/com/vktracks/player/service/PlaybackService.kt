package com.vktracks.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.Track
import com.vktracks.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                if (index in currentQueue.indices) {
                    val track = currentQueue[index]
                    _currentTrack.value = track
                    saveHistory(track)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = player.duration.coerceAtLeast(0)
                }
            }
        })

        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        progressJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        currentQueue = tracks

        val mediaItems = tracks.map { track ->
            val uri = if (track.isLocal && !track.localFilePath.isNullOrEmpty()) {
                Uri.parse(track.localFilePath)
            } else {
                Uri.parse(track.streamUrl)
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.albumTitle)
                .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
                .build()

            MediaItem.Builder()
                .setMediaId(track.fullId)
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        }

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
        _currentTrack.value = tracks.getOrNull(startIndex)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun playNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        if (player.currentPosition > 3000) {
            player.seekTo(0)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    fun toggleShuffle() {
        val nextMode = !player.shuffleModeEnabled
        player.shuffleModeEnabled = nextMode
        _isShuffle.value = nextMode
    }

    fun toggleRepeat() {
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        _repeatMode.value = nextMode
    }

    fun openSystemEqualizer(context: Context) {
        try {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                _currentPositionMs.value = player.currentPosition.coerceAtLeast(0)
                _durationMs.value = player.duration.coerceAtLeast(0)
                delay(400)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        _currentPositionMs.value = player.currentPosition.coerceAtLeast(0)
    }

    private fun saveHistory(track: Track) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val entity = TrackEntity(
                    id = track.fullId,
                    trackId = track.id,
                    ownerId = track.ownerId,
                    title = track.title,
                    artist = track.artist,
                    duration = track.duration,
                    streamUrl = track.streamUrl,
                    coverUrl = track.coverUrl,
                    albumTitle = track.albumTitle,
                    isExplicit = track.isExplicit,
                    lyricsId = track.lyricsId,
                    accessKey = track.accessKey,
                    isLiked = track.isLiked,
                    localFilePath = track.localFilePath,
                    isDownloaded = track.isLocal,
                    lastPlayedTimestamp = System.currentTimeMillis()
                )
                db.trackDao().insertOrUpdate(entity)
            } catch (_: Exception) {}
        }
    }

    companion object {
        var instance: PlaybackService? = null
            private set

        private var currentQueue: List<Track> = emptyList()

        private val _currentTrack = MutableStateFlow<Track?>(null)
        val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        private val _currentPositionMs = MutableStateFlow(0L)
        val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

        private val _durationMs = MutableStateFlow(0L)
        val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

        private val _isShuffle = MutableStateFlow(false)
        val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

        private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
        val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    }
}
