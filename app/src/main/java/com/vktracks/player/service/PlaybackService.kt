package com.vktracks.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.vktracks.player.data.cache.MediaCacheManager
import androidx.core.app.NotificationCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vktracks.player.R
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.TrackRestriction
import com.vktracks.player.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
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

class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var artworkJob: Job? = null
    private var historyJob: Job? = null
    private var lastLoadedArtworkKey: String? = null
    @Volatile
    private var currentNotificationBitmap: Bitmap? = null
    private var lastSeekTimestamp: Long = 0L
    private var lastSeekPositionMs: Long = 0L

    private val sessionCallback = object : MediaLibrarySession.Callback {
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

        // Поддержка Android Auto / MediaLibraryService (VKX стиль)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("ROOT")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("VK Tracks")
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = mutableListOf<MediaItem>()
            when (parentId) {
                "ROOT" -> {
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("NODE_RADIO")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setTitle("Радио (Прямой эфир)")
                                    .build()
                            )
                            .build()
                    )
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("NODE_QUEUE")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setTitle("Текущая очередь")
                                    .build()
                            )
                            .build()
                    )
                }
                "NODE_RADIO" -> {
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("RADIO_STUDIO_21")
                            .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.STUDIO_21.streamUrl))
                            .setMimeType(MimeTypes.AUDIO_MPEG)
                            .setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setMaxPlaybackSpeed(1.0f)
                                    .setMinPlaybackSpeed(1.0f)
                                    .build()
                            )
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setTitle("Studio 21")
                                    .setArtist("Прямой эфир")
                                    .setArtworkUri(Uri.parse(com.vktracks.player.data.model.RadioStation.STUDIO_21.logoUrl))
                                    .build()
                            )
                            .build()
                    )
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("RADIO_RECORD")
                            .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.RADIO_RECORD.streamUrl))
                            .setMimeType(MimeTypes.AUDIO_AAC)
                            .setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setMaxPlaybackSpeed(1.0f)
                                    .setMinPlaybackSpeed(1.0f)
                                    .build()
                            )
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setTitle("Радио Record")
                                    .setArtist("Прямой эфир")
                                    .setArtworkUri(Uri.parse(com.vktracks.player.data.model.RadioStation.RADIO_RECORD.logoUrl))
                                    .build()
                            )
                            .build()
                    )
                }
                "NODE_QUEUE" -> {
                    currentQueue.forEach { track ->
                        val uri = when {
                            track.isLocal && !track.localFilePath.isNullOrEmpty() -> Uri.parse(track.localFilePath)
                            track.streamUrl.isNotEmpty() -> Uri.parse(track.streamUrl)
                            else -> Uri.EMPTY
                        }
                        if (uri != Uri.EMPTY) {
                            items.add(
                                MediaItem.Builder()
                                    .setMediaId(track.fullId)
                                    .setUri(uri)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setIsBrowsable(false)
                                            .setIsPlayable(true)
                                            .setTitle(track.title)
                                            .setArtist(track.artist)
                                            .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                }
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            when (mediaId) {
                "RADIO_STUDIO_21" -> {
                    val item = MediaItem.Builder()
                        .setMediaId("RADIO_STUDIO_21")
                        .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.STUDIO_21.streamUrl))
                        .setMimeType(MimeTypes.AUDIO_MPEG)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setMaxPlaybackSpeed(1.0f)
                                .setMinPlaybackSpeed(1.0f)
                                .build()
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Studio 21")
                                .setArtist("Прямой эфир")
                                .setArtworkUri(Uri.parse(com.vktracks.player.data.model.RadioStation.STUDIO_21.logoUrl))
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null))
                }
                "RADIO_RECORD" -> {
                    val item = MediaItem.Builder()
                        .setMediaId("RADIO_RECORD")
                        .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.RADIO_RECORD.streamUrl))
                        .setMimeType(MimeTypes.AUDIO_AAC)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setMaxPlaybackSpeed(1.0f)
                                .setMinPlaybackSpeed(1.0f)
                                .build()
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Радио Record")
                                .setArtist("Прямой эфир")
                                .setArtworkUri(Uri.parse(com.vktracks.player.data.model.RadioStation.RADIO_RECORD.logoUrl))
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null))
                }
            }
            val track = currentQueue.firstOrNull { it.fullId == mediaId }
            if (track != null) {
                val uri = if (track.isLocal && !track.localFilePath.isNullOrEmpty()) Uri.parse(track.localFilePath) else Uri.parse(track.streamUrl)
                val item = MediaItem.Builder()
                    .setMediaId(track.fullId)
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(item, null))
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
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
                val actionIndices = super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
                currentNotificationBitmap?.let { bitmap ->
                    builder.setLargeIcon(bitmap)
                }
                return actionIndices
            }
        }
        provider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(provider)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val cacheManager = MediaCacheManager.getInstance(this)

        // Включаем CBR-поиск для MP3/AAC треков VK (исключает срыв таймлайна в 0:00)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(cacheManager.getDataSourceFactory())

        // Адаптивный LoadControl для живых потоков радио и треков:
        // Устраняет спотыкания (buffer underrun) и буферные задержки
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
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

        mediaSession = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(sessionActivityIntent)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                    saveQueueState()
                }
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
                    _durationMs.value = (track.duration.toLong() * 1000L).coerceAtLeast(0)
                    currentNotificationBitmap = null
                    saveHistory(track)
                    loadArtworkAsync(track)
                    updateNotificationLikeButton()
                    saveQueueState()
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

        // Восстановление сохраненной очереди и позиции (VKX QueueSaveHolder)
        restoreSavedQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (player.playWhenReady) {
            mediaSession?.let { onUpdateNotification(it, true) }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            saveQueueState()
            progressJob?.cancel()
            artworkJob?.cancel()
            historyJob?.cancel()
            player.stop()
            player.clearMediaItems()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            nm?.cancelAll()
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error stopping onTaskRemoved", e)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveQueueState()
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

            val builder = MediaItem.Builder()
                .setMediaId(track.fullId)
                .setUri(uri)
                .setMediaMetadata(metadata)

            val uriStr = uri.toString()
            if (track.duration == 0 || MediaCacheManager.getInstance(this).isLiveStreamUri(uriStr)) {
                // Прямой эфир радио: фиксируем скорость 1.0x (запрет time-stretching артефактов / скрипов)
                builder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.0f)
                        .setMinPlaybackSpeed(1.0f)
                        .build()
                )
                if (uriStr.contains("studio21", ignoreCase = true) || uriStr.contains("s21", ignoreCase = true)) {
                    builder.setMimeType(MimeTypes.AUDIO_MPEG)
                } else if (uriStr.contains("radiorecord", ignoreCase = true) || uriStr.contains(".aacp", ignoreCase = true)) {
                    builder.setMimeType(MimeTypes.AUDIO_AAC)
                }
            }

            builder.build()
        }

        currentNotificationBitmap = null
        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
        _currentTrack.value = validTrack
        _durationMs.value = (validTrack.duration.toLong() * 1000L).coerceAtLeast(0)
        loadArtworkAsync(validTrack)
        updateNotificationLikeButton()
        mediaSession?.let { onUpdateNotification(it, true) }
        saveQueueState()
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
            currentQueue = currentQueue.map { if (it.fullId == current.fullId) updated else it }
            _currentQueueFlow.value = currentQueue

            // Бесшовное обновление MediaItem metadata в плеере (для SystemUI в шторке и Android Auto без перезапуска потока)
            val currentItem = player.currentMediaItem
            if (currentItem != null) {
                val newMeta = currentItem.mediaMetadata.buildUpon()
                    .setTitle(cleanTitle)
                    .setArtist(cleanArtist)
                    .setDisplayTitle(cleanTitle)
                    .setArtworkUri(updated.coverUrl?.let { Uri.parse(it) })
                    .build()
                val curIdx = player.currentMediaItemIndex
                if (curIdx >= 0) {
                    player.replaceMediaItem(curIdx, currentItem.buildUpon().setMediaMetadata(newMeta).build())
                }
            }

            player.playlistMetadata = MediaMetadata.Builder()
                .setTitle(cleanTitle)
                .setArtist(cleanArtist)
                .setDisplayTitle(cleanTitle)
                .build()

            loadArtworkAsync(updated)

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
        lastSeekTimestamp = System.currentTimeMillis()
        lastSeekPositionMs = positionMs
        _currentPositionMs.value = positionMs
        player.seekTo(positionMs)
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
        val currentKey = "${track.fullId}_${track.title}_${track.artist}_${track.coverUrl}"
        if (lastLoadedArtworkKey == currentKey) {
            return
        }
        lastLoadedArtworkKey = currentKey

        artworkJob?.cancel()
        artworkJob = serviceScope.launch(Dispatchers.IO) {
            var coverUrl = track.coverUrl?.takeIf { it.isNotBlank() } ?: run {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.trackDao().getTrackById(track.fullId)?.coverUrl?.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }

            // Fallback обогащение через каталог VK строго при воспроизведении трека
            if (coverUrl.isNullOrEmpty() && track.duration > 0 && !track.isLocal) {
                try {
                    val authManager = com.vktracks.player.data.api.VkAuthManager(applicationContext)
                    val apiClient = com.vktracks.player.data.api.VkApiClient(authManager)
                    val enrichedTrack = apiClient.resolveTrack(track)
                    if (!enrichedTrack.coverUrl.isNullOrEmpty()) {
                        coverUrl = enrichedTrack.coverUrl
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val entity = TrackEntity(
                                id = enrichedTrack.fullId,
                                trackId = enrichedTrack.id,
                                ownerId = enrichedTrack.ownerId,
                                title = enrichedTrack.title,
                                artist = enrichedTrack.artist,
                                duration = enrichedTrack.duration,
                                streamUrl = enrichedTrack.streamUrl,
                                coverUrl = enrichedTrack.coverUrl,
                                albumTitle = enrichedTrack.albumTitle,
                                isExplicit = enrichedTrack.isExplicit,
                                lyricsId = enrichedTrack.lyricsId,
                                accessKey = enrichedTrack.accessKey,
                                isLiked = enrichedTrack.isLiked,
                                localFilePath = enrichedTrack.localFilePath,
                                isDownloaded = enrichedTrack.isLocal,
                                lastPlayedTimestamp = System.currentTimeMillis()
                            )
                            db.trackDao().insertOrUpdate(entity)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            if (!coverUrl.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    val isCurrent = _currentTrack.value?.let {
                        it.fullId == track.fullId && (it.duration != 0 || it.title == track.title)
                    } ?: false
                    if (isCurrent && _currentTrack.value?.coverUrl != coverUrl) {
                        _currentTrack.value = _currentTrack.value?.copy(coverUrl = coverUrl)
                    }
                }

                // Фоновая загрузка обложки для системного уведомления шторки (в IO-потоке, без блокировки Main)
                try {
                    val loader = Coil.imageLoader(this@PlaybackService)
                    val req = ImageRequest.Builder(this@PlaybackService)
                        .data(coverUrl)
                        .size(300, 300)
                        .allowHardware(false)
                        .dispatcher(Dispatchers.IO)
                        .build()
                    val result = loader.execute(req)
                    if (result is SuccessResult) {
                        val drawable = result.drawable
                        val bitmap = (drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                val isCurrent = _currentTrack.value?.let {
                                    it.fullId == track.fullId && (it.duration != 0 || it.title == track.title)
                                } ?: false
                                if (isCurrent) {
                                    currentNotificationBitmap = bitmap
                                    mediaSession?.let { onUpdateNotification(it, player.playWhenReady) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PlaybackService", "Failed to load artwork for notification", e)
                }
            }
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastSeekTimestamp >= 1200L) {
                    _currentPositionMs.value = player.currentPosition.coerceAtLeast(0)
                } else {
                    _currentPositionMs.value = lastSeekPositionMs
                }
                val dur = player.duration
                if (dur > 0) {
                    _durationMs.value = dur
                }
                delay(300)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastSeekTimestamp >= 1200L) {
            _currentPositionMs.value = player.currentPosition.coerceAtLeast(0)
        }
    }

    private fun saveHistory(track: Track) {
        if (track.duration == 0) return // Не сохранять радио в историю
        historyJob?.cancel()
        historyJob = serviceScope.launch(Dispatchers.IO) {
            delay(3000) // Сохранять только если трек играет дольше 3 секунд (защита от частых скипов)
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

    // Сохранение состояния очереди и позиции (VKX QueueSaveHolder)
    private fun saveQueueState() {
        try {
            val queue = currentQueue
            if (queue.isEmpty()) return
            val currentIndex = player.currentMediaItemIndex
            val currentPos = player.currentPosition.coerceAtLeast(0)

            val jsonArray = JSONArray()
            queue.take(150).forEach { track ->
                val obj = JSONObject().apply {
                    put("id", track.id)
                    put("ownerId", track.ownerId)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("duration", track.duration)
                    put("streamUrl", track.streamUrl)
                    put("coverUrl", track.coverUrl ?: "")
                    put("albumTitle", track.albumTitle ?: "")
                    put("isExplicit", track.isExplicit)
                    put("lyricsId", track.lyricsId ?: 0L)
                    put("accessKey", track.accessKey ?: "")
                    put("isLiked", track.isLiked)
                    put("localFilePath", track.localFilePath ?: "")
                    put("restrictionCode", track.restriction.code)
                    put("restrictionText", track.restrictionText ?: "")
                }
                jsonArray.put(obj)
            }

            val prefs = getSharedPreferences("playback_state_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("saved_queue_json", jsonArray.toString())
                .putInt("saved_queue_index", currentIndex)
                .putLong("saved_position_ms", currentPos)
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error saving queue state", e)
        }
    }

    // Восстановление состояния очереди и позиции при перезапуске (VKX QueueSaveHolder)
    private fun restoreSavedQueue() {
        try {
            val prefs = getSharedPreferences("playback_state_prefs", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_queue_json", null) ?: return
            if (jsonStr.isEmpty() || jsonStr == "[]") return

            val jsonArray = JSONArray(jsonStr)
            val restored = mutableListOf<Track>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val id = obj.optLong("id")
                if (id == 0L) continue
                val ownerId = obj.optLong("ownerId")
                val title = obj.optString("title", "Без названия")
                val artist = obj.optString("artist", "Неизвестный исполнитель")
                val duration = obj.optInt("duration", 0)
                val streamUrl = obj.optString("streamUrl", "")
                val coverUrl = obj.optString("coverUrl").takeIf { it.isNotEmpty() }
                val albumTitle = obj.optString("albumTitle").takeIf { it.isNotEmpty() }
                val isExplicit = obj.optBoolean("isExplicit", false)
                val lyricsId = obj.optLong("lyricsId", 0L).takeIf { it > 0L }
                val accessKey = obj.optString("accessKey").takeIf { it.isNotEmpty() }
                val isLiked = obj.optBoolean("isLiked", false)
                val localFilePath = obj.optString("localFilePath").takeIf { it.isNotEmpty() }
                val restCode = obj.optInt("restrictionCode", 0)
                val restText = obj.optString("restrictionText").takeIf { it.isNotEmpty() }

                restored.add(
                    Track(
                        id = id,
                        ownerId = ownerId,
                        title = title,
                        artist = artist,
                        duration = duration,
                        streamUrl = streamUrl,
                        coverUrl = coverUrl,
                        albumTitle = albumTitle,
                        isExplicit = isExplicit,
                        lyricsId = lyricsId,
                        accessKey = accessKey,
                        isLiked = isLiked,
                        localFilePath = localFilePath,
                        restriction = TrackRestriction.from(restCode),
                        restrictionText = restText
                    )
                )
            }

            if (restored.isEmpty()) return
            currentQueue = restored
            _currentQueueFlow.value = restored

            val savedIndex = prefs.getInt("saved_queue_index", 0).coerceIn(0, restored.lastIndex)
            val savedPos = prefs.getLong("saved_position_ms", 0L).coerceAtLeast(0L)
            val current = restored[savedIndex]

            val mediaItems = restored.map { track ->
                val uri = when {
                    track.isLocal && !track.localFilePath.isNullOrEmpty() -> Uri.parse(track.localFilePath)
                    track.streamUrl.isNotEmpty() -> Uri.parse(track.streamUrl)
                    else -> Uri.parse(current.streamUrl)
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

            player.setMediaItems(mediaItems, savedIndex, savedPos)
            player.prepare()
            player.playWhenReady = false

            _currentTrack.value = current
            _currentPositionMs.value = savedPos
            _durationMs.value = (current.duration.toLong() * 1000L).coerceAtLeast(0)
            loadArtworkAsync(current)
            updateNotificationLikeButton()
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error restoring saved queue", e)
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
