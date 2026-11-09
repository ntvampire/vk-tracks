package com.vktracks.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vktracks.player.data.cache.MediaCacheManager
import com.vktracks.player.data.cover.CoverArtResolver
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vktracks.player.R
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var lastLoadedArtworkKey: String? = null

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .build()
            val track = _currentTrack.value
            val isLiked = if (track != null) isCurrentTrackLiked(track) else false
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(listOf(createLikeCommandButton(isLiked)))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_LIKE) {
                _currentTrack.value?.let { track ->
                    serviceScope.launch(Dispatchers.Main) {
                        onToggleLikeListener?.invoke(track)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Настройка канала уведомлений для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                "Воспроизведение музыки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением музыки"
                setShowBadge(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val provider = object : DefaultMediaNotificationProvider(this) {
            override fun getNotificationContentTitle(mediaMetadata: MediaMetadata): CharSequence? {
                return _currentTrack.value?.title ?: mediaMetadata.title
            }

            override fun getNotificationContentText(mediaMetadata: MediaMetadata): CharSequence? {
                return _currentTrack.value?.artist ?: mediaMetadata.artist
            }

            override fun addNotificationActions(
                mediaSession: MediaSession,
                mediaButtons: ImmutableList<CommandButton>,
                builder: NotificationCompat.Builder,
                actionFactory: MediaNotification.ActionFactory
            ): IntArray {
                mediaSession.sessionActivity?.let { pendingIntent ->
                    builder.setContentIntent(pendingIntent)
                }
                val track = _currentTrack.value
                if (track != null) {
                    builder.setTicker("${track.artist} — ${track.title}")
                    builder.setContentTitle(track.title)
                    builder.setContentText(track.artist)
                    if (!track.albumTitle.isNullOrBlank() && track.albumTitle != "Онлайн-радио") {
                        builder.setSubText(track.albumTitle)
                    }
                }
                return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
            }
        }
        provider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(provider)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val cacheManager = MediaCacheManager.getInstance(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheManager.getDataSourceFactory())

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            putExtra(EXTRA_OPEN_FULL_PLAYER, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(sessionCallback)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                updateNotificationLikeButton()
                mediaSession?.let { onUpdateNotification(it, isPlaying) }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    return
                }
                val index = player.currentMediaItemIndex
                if (index in currentQueue.indices) {
                    val track = currentQueue[index]
                    _currentTrack.value = track
                    saveHistory(track)
                    loadArtworkAsync(track)
                    updateNotificationLikeButton()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = player.duration.coerceAtLeast(0)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val current = _currentTrack.value ?: return
                if (current.duration != 0) return

                val streamArtist = mediaMetadata.artist?.toString()?.trim()
                val streamTitle = mediaMetadata.title?.toString()?.trim()

                // Если в mediaMetadata уже задан исполнитель (от нашего replaceMediaItem), не перепарсиваем
                if (!streamArtist.isNullOrEmpty()) {
                    return
                }

                // Обработка сырых ICY-тегов из потока (StreamTitle="Artist - Title")
                if (!streamTitle.isNullOrEmpty()) {
                    if (streamTitle.contains(" - ")) {
                        val parts = streamTitle.split(" - ", limit = 2)
                        val parsedArtist = parts[0].trim()
                        val parsedTitle = parts[1].trim()
                        if (parsedArtist.isNotEmpty() && parsedTitle.isNotEmpty()) {
                            updateLiveMetadata(title = parsedTitle, artist = parsedArtist, coverUrl = null)
                        }
                    } else if (streamTitle != current.title) {
                        // Одиночная строка без дефиса: обновляем только название, исполнителя не затираем
                        updateLiveMetadata(title = streamTitle, artist = current.artist, coverUrl = null)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "ExoPlayer error: ${error.errorCodeName} (${error.errorCode}) - ${error.message}", error)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (player.playWhenReady) {
            mediaSession?.let { onUpdateNotification(it, true) }
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
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
        _currentQueueFlow.value = tracks

        val validTrack = tracks.getOrNull(startIndex) ?: return
        if (validTrack.streamUrl.isEmpty() && !validTrack.isLocal) {
            android.util.Log.e("PlaybackService", "Cannot play track without stream URL: ${validTrack.fullId}")
            return
        }

        val mediaItems = tracks.map { track ->
            val uri = when {
                track.isLocal && !track.localFilePath.isNullOrEmpty() -> Uri.parse(track.localFilePath)
                track.streamUrl.isNotEmpty() -> Uri.parse(track.streamUrl)
                else -> Uri.parse(validTrack.streamUrl) // Защита: не передавать Uri.EMPTY в ExoPlayer
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setDisplayTitle(track.title)
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
        _currentTrack.value = validTrack
        loadArtworkAsync(validTrack)
        updateNotificationLikeButton()
        mediaSession?.let { onUpdateNotification(it, true) }
    }

    fun refreshNotification() {
        mediaSession?.let { onUpdateNotification(it, player.playWhenReady) }
    }

    fun updateLiveMetadata(title: String, artist: String, coverUrl: String? = null) {
        val current = _currentTrack.value ?: return
        if (current.duration == 0) {
            val cleanTitle = title.trim()
            var cleanArtist = artist.trim()

            // Защита: не допускать дублирования названия трека в поле исполнителя
            if (cleanArtist.equals(cleanTitle, ignoreCase = true) || cleanArtist.isEmpty()) {
                cleanArtist = if (current.artist.isNotEmpty() && !current.artist.equals(cleanTitle, ignoreCase = true)) {
                    current.artist
                } else {
                    "Прямой эфир"
                }
            }

            if (current.title == cleanTitle && current.artist == cleanArtist && (coverUrl == null || coverUrl == current.coverUrl)) {
                return
            }

            val updated = current.copy(
                title = cleanTitle,
                artist = cleanArtist,
                coverUrl = coverUrl ?: current.coverUrl
            )
            _currentTrack.value = updated

            val currentIndex = player.currentMediaItemIndex
            val currentItem = player.currentMediaItem
            if (currentItem != null && currentIndex in currentQueue.indices) {
                val newMeta = currentItem.mediaMetadata.buildUpon()
                    .setTitle(cleanTitle)
                    .setArtist(cleanArtist)
                    .setDisplayTitle(cleanTitle)
                    .build()
                val newItem = currentItem.buildUpon()
                    .setMediaMetadata(newMeta)
                    .build()
                player.replaceMediaItem(currentIndex, newItem)
            }

            if (!coverUrl.isNullOrEmpty() && coverUrl != current.coverUrl) {
                loadArtworkAsync(updated)
            }

            mediaSession?.let { onUpdateNotification(it, player.playWhenReady) }
        }
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

    fun openSystemEqualizer(context: Context): Boolean {
        return try {
            val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun updateNotificationLikeButton() {
        val track = _currentTrack.value
        val isLiked = if (track != null) isCurrentTrackLiked(track) else false
        val likeButton = createLikeCommandButton(isLiked)
        mediaSession?.setCustomLayout(listOf(likeButton))
    }

    private fun createLikeCommandButton(isLiked: Boolean): CommandButton {
        return CommandButton.Builder()
            .setDisplayName(if (isLiked) "В медиатеке" else "Добавить в медиатеку")
            .setIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_border)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .build()
    }

    private fun loadArtworkAsync(track: Track) {
        val currentKey = "${track.fullId}_${track.title}_${track.artist}"
        if (lastLoadedArtworkKey == currentKey) {
            return
        }
        lastLoadedArtworkKey = currentKey

        serviceScope.launch(Dispatchers.IO) {
            var coverUrl = track.coverUrl?.takeIf { it.isNotBlank() } ?: run {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.trackDao().getTrackById(track.fullId)?.coverUrl?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }

            if (coverUrl.isNullOrEmpty()) {
                coverUrl = CoverArtResolver.instance?.resolveCover(
                    track.artist,
                    track.title,
                    track.albumTitle
                )
                if (!coverUrl.isNullOrEmpty()) {
                    val updated = track.copy(coverUrl = coverUrl)
                    withContext(Dispatchers.Main) {
                        if (_currentTrack.value?.fullId == track.fullId) {
                            _currentTrack.value = _currentTrack.value?.copy(coverUrl = coverUrl)
                        }
                        currentQueue = currentQueue.map { if (it.fullId == track.fullId) updated else it }
                        _currentQueueFlow.value = currentQueue
                    }
                }
            }

            if (coverUrl.isNullOrEmpty()) return@launch

            try {
                val loader = ImageLoader(this@PlaybackService)
                val req = ImageRequest.Builder(this@PlaybackService)
                    .data(coverUrl)
                    .size(600, 600)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(req)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        val bytes = stream.toByteArray()

                        withContext(Dispatchers.Main) {
                            if (_currentTrack.value?.fullId == track.fullId && player.currentMediaItemIndex in currentQueue.indices) {
                                val currentIndex = player.currentMediaItemIndex
                                val currentItem = player.currentMediaItem
                                if (currentItem != null && currentItem.mediaId == track.fullId) {
                                    val currentLive = _currentTrack.value
                                    val newMetadata = currentItem.mediaMetadata.buildUpon()
                                        .setTitle(currentLive?.title ?: track.title)
                                        .setArtist(currentLive?.artist ?: track.artist)
                                        .setDisplayTitle(currentLive?.title ?: track.title)
                                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                        .build()
                                    val newItem = currentItem.buildUpon()
                                        .setMediaMetadata(newMetadata)
                                        .build()
                                    player.replaceMediaItem(currentIndex, newItem)
                                    mediaSession?.let { onUpdateNotification(it, player.playWhenReady) }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "Failed to load artwork for notification", e)
            }
        }
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
        const val ACTION_TOGGLE_LIKE = "ACTION_TOGGLE_LIKE"
        const val ACTION_OPEN_PLAYER = "com.vktracks.player.ACTION_OPEN_PLAYER"
        const val EXTRA_OPEN_FULL_PLAYER = "extra_open_full_player"

        var instance: PlaybackService? = null
            private set

        var onToggleLikeListener: ((Track) -> Unit)? = null
        private var likedTrackIds: Set<String> = emptySet()

        fun updateLikedTrackIds(ids: Set<String>) {
            likedTrackIds = ids
            instance?.updateNotificationLikeButton()
        }

        fun isCurrentTrackLiked(track: Track): Boolean {
            return likedTrackIds.contains(track.fullId) || track.isLiked
        }

        private var currentQueue: List<Track> = emptyList()
        private val _currentQueueFlow = MutableStateFlow<List<Track>>(emptyList())
        val currentQueueFlow: StateFlow<List<Track>> = _currentQueueFlow.asStateFlow()

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
