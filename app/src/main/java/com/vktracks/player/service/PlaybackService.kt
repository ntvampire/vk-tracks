package com.vktracks.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.vktracks.player.data.api.NetworkModule
import com.vktracks.player.data.api.VkApiClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.vktracks.player.R
import com.vktracks.player.data.local.AppDatabase
import com.vktracks.player.data.local.TrackEntity
import com.vktracks.player.data.model.Track
import com.vktracks.player.data.model.TrackRestriction
import com.vktracks.player.data.settings.PlayerSettingsManager
import com.vktracks.player.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var artworkJob: Job? = null
    private var historyJob: Job? = null
    private var lastLoadedArtworkKey: String? = null
    @Volatile
    private var currentNotificationBitmap: Bitmap? = null
    private var lastSeekTimestamp: Long = 0L
    private var lastSeekPositionMs: Long = 0L
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothResumeJob: Job? = null
    private var fadeJob: Job? = null

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean {
        if (controller.packageName == applicationContext.packageName) return true
        val session = mediaSession
        if (session != null && (
                session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller)
            )
        ) {
            return true
        }
        if (controller.uid == android.os.Process.SYSTEM_UID || controller.uid == 1002) {
            return true
        }
        return controller.packageName in TRUSTED_MEDIA_PACKAGES
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (!isTrustedController(controller)) {
                android.util.Log.w("PlaybackService", "Rejected untrusted media controller: ${controller.packageName} (uid=${controller.uid})")
                return MediaSession.ConnectionResult.reject()
            }
            val baseResult = super.onConnect(session, controller)
            val sessionCommands = (baseResult.availableSessionCommands ?: MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .build()
            val track = _currentTrack.value
            val isLiked = if (track != null) isCurrentTrackLiked(track) else false
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(baseResult.availablePlayerCommands ?: MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .setCustomLayout(listOf(createLikeCommandButton(isLiked)))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (!isTrustedController(controller)) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            if (customCommand.customAction == ACTION_TOGGLE_LIKE) {
                _currentTrack.value?.let { track ->
                    serviceScope.launch(Dispatchers.Main) {
                        handleToggleLike(track)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!isTrustedController(controller)) {
                return Futures.immediateFailedFuture(SecurityException("Untrusted controller: ${controller.packageName}"))
            }
            val isOwnApp = controller.packageName == applicationContext.packageName
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val resolvedItems = mutableListOf<MediaItem>()
                    val db = AppDatabase.getInstance(applicationContext)
                    for (item in mediaItems) {
                        when (item.mediaId) {
                            "RADIO_STUDIO_21" -> {
                                resolvedItems.add(
                                    item.buildUpon()
                                        .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.STUDIO_21.streamUrl))
                                        .setMimeType(MimeTypes.AUDIO_MPEG)
                                        .setLiveConfiguration(
                                            MediaItem.LiveConfiguration.Builder()
                                                .setMaxPlaybackSpeed(1.0f)
                                                .setMinPlaybackSpeed(1.0f)
                                                .build()
                                        )
                                        .build()
                                )
                            }
                            "RADIO_RECORD" -> {
                                resolvedItems.add(
                                    item.buildUpon()
                                        .setUri(Uri.parse(com.vktracks.player.data.model.RadioStation.RADIO_RECORD.streamUrl))
                                        .setMimeType(MimeTypes.AUDIO_AAC)
                                        .setLiveConfiguration(
                                            MediaItem.LiveConfiguration.Builder()
                                                .setMaxPlaybackSpeed(1.0f)
                                                .setMinPlaybackSpeed(1.0f)
                                                .build()
                                        )
                                        .build()
                                )
                            }
                            else -> {
                                if (isOwnApp && (item.requestMetadata.mediaUri != null || item.localConfiguration?.uri != null)) {
                                    resolvedItems.add(item)
                                } else {
                                    val rawId = item.mediaId.removePrefix("TRACK_")
                                    val entity = try { db.trackDao().getTrackById(rawId) } catch (_: Exception) { null }
                                    if (entity != null) {
                                        val uri = if (!entity.localFilePath.isNullOrEmpty()) Uri.parse(entity.localFilePath) else Uri.parse(entity.streamUrl)
                                        if (uri != Uri.EMPTY) {
                                            resolvedItems.add(item.buildUpon().setUri(uri).build())
                                        }
                                    } else {
                                        val queueTrack = currentQueue.firstOrNull { it.fullId == rawId || it.fullId == item.mediaId }
                                        if (queueTrack != null) {
                                            val uri = if (queueTrack.isLocal && !queueTrack.localFilePath.isNullOrEmpty()) {
                                                Uri.parse(queueTrack.localFilePath)
                                            } else {
                                                Uri.parse(queueTrack.streamUrl)
                                            }
                                            if (uri != Uri.EMPTY) {
                                                resolvedItems.add(item.buildUpon().setUri(uri).build())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val safeIndex = if (resolvedItems.isEmpty()) 0 else startIndex.coerceIn(0, resolvedItems.lastIndex)
                    future.set(MediaSession.MediaItemsWithStartPosition(resolvedItems, safeIndex, startPositionMs))
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!isTrustedController(controller)) {
                return Futures.immediateFailedFuture(SecurityException("Untrusted controller: ${controller.packageName}"))
            }
            val item = player.currentMediaItem
            val pos = player.currentPosition.coerceAtLeast(0)
            if (item != null) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(item),
                        0,
                        pos
                    )
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    emptyList(),
                    0,
                    0L
                )
            )
        }

        // Поддержка Android Auto / MediaLibraryService
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!isTrustedController(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
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
            if (!isTrustedController(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val items = mutableListOf<MediaItem>()
            when (parentId) {
                "ROOT" -> {
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("NODE_DOWNLOADS")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setTitle("Скачанное (Офлайн)")
                                    .setSubtitle("Треки на устройстве")
                                    .build()
                            )
                            .build()
                    )
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("NODE_FAVORITES")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setTitle("Мои треки (Избранное)")
                                    .setSubtitle("Любимая музыка")
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
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }
                "NODE_DOWNLOADS" -> {
                    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val downloaded = db.trackDao().getDownloadedTracksList()
                            val mediaItems = downloaded.map { entity ->
                                MediaItem.Builder()
                                    .setMediaId("TRACK_${entity.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setIsBrowsable(false)
                                            .setIsPlayable(true)
                                            .setTitle(entity.title)
                                            .setArtist(entity.artist)
                                            .setArtworkUri(entity.coverUrl?.let { Uri.parse(it) })
                                            .build()
                                    )
                                    .build()
                            }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params))
                        } catch (e: Exception) {
                            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                    }
                    return future
                }
                "NODE_FAVORITES" -> {
                    val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val liked = db.trackDao().getLikedTracksList()
                            val mediaItems = liked.map { entity ->
                                MediaItem.Builder()
                                    .setMediaId("TRACK_${entity.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setIsBrowsable(false)
                                            .setIsPlayable(true)
                                            .setTitle(entity.title)
                                            .setArtist(entity.artist)
                                            .setArtworkUri(entity.coverUrl?.let { Uri.parse(it) })
                                            .build()
                                    )
                                    .build()
                            }
                            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(mediaItems), params))
                        } catch (e: Exception) {
                            future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        }
                    }
                    return future
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
                        if (track.streamUrl.isNotEmpty() || (track.isLocal && !track.localFilePath.isNullOrEmpty())) {
                            items.add(
                                MediaItem.Builder()
                                    .setMediaId("TRACK_${track.fullId}")
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
            if (!isTrustedController(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
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
            if (mediaId.startsWith("TRACK_")) {
                val rawId = mediaId.removePrefix("TRACK_")
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val db = AppDatabase.getInstance(applicationContext)
                        val entity = db.trackDao().getTrackById(rawId)
                        if (entity != null) {
                            val item = MediaItem.Builder()
                                .setMediaId(mediaId)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(entity.title)
                                        .setArtist(entity.artist)
                                        .setArtworkUri(entity.coverUrl?.let { Uri.parse(it) })
                                        .setIsBrowsable(false)
                                        .setIsPlayable(true)
                                        .build()
                                )
                                .build()
                            future.set(LibraryResult.ofItem(item, null))
                        } else {
                            future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                        }
                    } catch (e: Exception) {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                }
                return future
            }
            val track = currentQueue.firstOrNull { it.fullId == mediaId }
            if (track != null) {
                val item = MediaItem.Builder()
                    .setMediaId("TRACK_${track.fullId}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setArtworkUri(track.coverUrl?.let { Uri.parse(it) })
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(item, null))
            }
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            if (!isTrustedController(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            session.notifySearchResultChanged(browser, query, 50, params)
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!isTrustedController(browser)) {
                return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    val results = db.trackDao().searchTracks(query)
                    val items = results.map { entity ->
                        MediaItem.Builder()
                            .setMediaId("TRACK_${entity.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setTitle(entity.title)
                                    .setArtist(entity.artist)
                                    .setArtworkUri(entity.coverUrl?.let { Uri.parse(it) })
                                    .build()
                            )
                            .build()
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
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
        cacheManager.isUriFavoriteCheck = { uriStr ->
            try {
                currentQueue.any { it.streamUrl == uriStr && isCurrentTrackLiked(it) }
            } catch (_: Exception) {
                false
            }
        }

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

        // Автоматическое определение подключения/отключения Bluetooth аудиоустройств (Pioneer, гарнитуры)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    val hasBtAudio = addedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                    } == true
                    if (hasBtAudio) {
                        handleBluetoothConnected()
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val hasBtAudio = removedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
                    } == true
                    if (hasBtAudio) {
                        handleBluetoothDisconnected()
                    }
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                        saveQueueState()
                    }
                    updateNotificationLikeButton()
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Error in onIsPlayingChanged", e)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                try {
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
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Error in onMediaItemTransition", e)
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
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            saveQueueState(sync = true)
            progressJob?.cancel()
            artworkJob?.cancel()
            historyJob?.cancel()
            player.stop()
            player.clearMediaItems()
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            nm?.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            nm?.cancelAll()
            mediaSession?.run {
                player.release()
                release()
            }
            mediaSession = null
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error stopping onTaskRemoved", e)
        }
        serviceScope.cancel()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            MediaCacheManager.getInstance(this).isUriFavoriteCheck = null
        } catch (_: Exception) {}
        audioDeviceCallback?.let { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.unregisterAudioDeviceCallback(callback)
            }
        }
        bluetoothResumeJob?.cancel()
        fadeJob?.cancel()
        progressJob?.cancel()
        artworkJob?.cancel()
        historyJob?.cancel()
        saveQueueState(sync = true)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val validStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val validTrack = tracks[validStartIndex]
        if (validTrack.streamUrl.isEmpty() && !validTrack.isLocal) {
            android.util.Log.e("PlaybackService", "Cannot play track without stream URL: ${validTrack.fullId}")
            return
        }

        // Если очередь та же самая — не пересоздаем все MediaItems и не сбрасываем ExoPlayer
        val isSameQueue = currentQueue.size == tracks.size &&
                currentQueue.isNotEmpty() &&
                currentQueue.first().fullId == tracks.first().fullId &&
                currentQueue.last().fullId == tracks.last().fullId

        if (isSameQueue && player.mediaItemCount == tracks.size) {
            currentQueue = tracks
            _currentQueueFlow.value = tracks
            player.seekToDefaultPosition(validStartIndex)
            player.play()
            _currentTrack.value = validTrack
            _durationMs.value = (validTrack.duration.toLong() * 1000L).coerceAtLeast(0)
            saveHistory(validTrack)
            loadArtworkAsync(validTrack)
            updateNotificationLikeButton()
            saveQueueState()
            return
        }

        currentQueue = tracks
        _currentQueueFlow.value = tracks

        val mediaItems = tracks.map { track ->
            buildMediaItem(track)
        }

        currentNotificationBitmap = null
        player.setMediaItems(mediaItems, validStartIndex, 0L)
        player.prepare()
        player.play()
        _currentTrack.value = validTrack
        _durationMs.value = (validTrack.duration.toLong() * 1000L).coerceAtLeast(0)
        saveHistory(validTrack)
        loadArtworkAsync(validTrack)
        updateNotificationLikeButton()
        saveQueueState()
    }

    fun buildMediaItem(track: Track, fallbackStreamUrl: String = ""): MediaItem {
        val uri = when {
            track.isLocal && !track.localFilePath.isNullOrEmpty() -> Uri.parse(track.localFilePath)
            track.streamUrl.isNotEmpty() -> Uri.parse(track.streamUrl)
            fallbackStreamUrl.isNotEmpty() -> Uri.parse(fallbackStreamUrl)
            else -> Uri.EMPTY
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

        return builder.build()
    }

    fun playNextInQueue(track: Track) {
        if (currentQueue.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }
        val curIndex = player.currentMediaItemIndex
        val insertIndex = (curIndex + 1).coerceIn(0, currentQueue.size)
        val mediaItem = buildMediaItem(track)

        val newQueue = currentQueue.toMutableList()
        newQueue.add(insertIndex, track)
        currentQueue = newQueue
        _currentQueueFlow.value = newQueue

        player.addMediaItem(insertIndex, mediaItem)
        saveQueueState()
    }

    fun addToQueueEnd(track: Track) {
        if (currentQueue.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }
        val mediaItem = buildMediaItem(track)

        val newQueue = currentQueue.toMutableList()
        newQueue.add(track)
        currentQueue = newQueue
        _currentQueueFlow.value = newQueue

        player.addMediaItem(mediaItem)
        saveQueueState()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return
        val newQueue = currentQueue.toMutableList()
        val item = newQueue.removeAt(fromIndex)
        newQueue.add(toIndex, item)
        currentQueue = newQueue
        _currentQueueFlow.value = newQueue

        player.moveMediaItem(fromIndex, toIndex)
        saveQueueState()
    }

    fun removeQueueItem(index: Int) {
        if (index !in currentQueue.indices) return
        val curIndex = player.currentMediaItemIndex
        val isRemovingCurrent = curIndex == index

        val newQueue = currentQueue.toMutableList()
        newQueue.removeAt(index)
        currentQueue = newQueue
        _currentQueueFlow.value = newQueue

        player.removeMediaItem(index)
        if (isRemovingCurrent && currentQueue.isNotEmpty()) {
            val newCur = player.currentMediaItemIndex
            if (newCur in currentQueue.indices) {
                val newTrack = currentQueue[newCur]
                _currentTrack.value = newTrack
                _durationMs.value = (newTrack.duration.toLong() * 1000L).coerceAtLeast(0)
                saveHistory(newTrack)
                loadArtworkAsync(newTrack)
                updateNotificationLikeButton()
            }
        }
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
        if (player.isPlaying) softPause() else softPlay()
    }

    private fun handleBluetoothConnected() {
        val settings = PlayerSettingsManager.getInstance(this)
        if (!settings.isBtAutoResumeEnabled) return

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager?.isMusicActive == true && !player.isPlaying) return

        if (!player.isPlaying && (_currentTrack.value != null || player.currentMediaItem != null || currentQueue.isNotEmpty())) {
            bluetoothResumeJob?.cancel()
            bluetoothResumeJob = serviceScope.launch(Dispatchers.Main) {
                delay(700)
                if (!player.isPlaying) {
                    softPlay()
                }
            }
        }
    }

    private fun handleBluetoothDisconnected() {
        val settings = PlayerSettingsManager.getInstance(this)
        if (!settings.isBtAutoPauseEnabled) return
        bluetoothResumeJob?.cancel()
        if (player.isPlaying) {
            softPause()
        }
    }

    fun softPlay(durationMs: Long = 150L) {
        val settings = PlayerSettingsManager.getInstance(this)
        if (!settings.isSoftFadingEnabled) {
            player.volume = 1.0f
            player.play()
            return
        }
        fadeJob?.cancel()
        fadeJob = serviceScope.launch(Dispatchers.Main) {
            player.volume = 0.0f
            player.play()
            val steps = 5
            val stepDelay = durationMs / steps
            for (i in 1..steps) {
                delay(stepDelay)
                player.volume = (i.toFloat() / steps.toFloat()).coerceIn(0.0f, 1.0f)
            }
            player.volume = 1.0f
        }
    }

    fun softPause(durationMs: Long = 150L) {
        val settings = PlayerSettingsManager.getInstance(this)
        if (!settings.isSoftFadingEnabled) {
            player.pause()
            player.volume = 1.0f
            return
        }
        fadeJob?.cancel()
        fadeJob = serviceScope.launch(Dispatchers.Main) {
            val steps = 5
            val stepDelay = durationMs / steps
            for (i in 1..steps) {
                delay(stepDelay)
                player.volume = (1.0f - (i.toFloat() / steps.toFloat())).coerceIn(0.0f, 1.0f)
            }
            player.pause()
            player.volume = 1.0f
        }
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

    private var lastNotifiedLikedState: Boolean? = null

    fun updateNotificationLikeButton() {
        try {
            val track = _currentTrack.value
            val isLiked = if (track != null) isCurrentTrackLiked(track) else false
            if (lastNotifiedLikedState == isLiked) return
            lastNotifiedLikedState = isLiked
            val likeButton = createLikeCommandButton(isLiked)
            mediaSession?.setCustomLayout(listOf(likeButton))
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Failed to update notification like button", e)
        }
    }

    private fun createLikeCommandButton(isLiked: Boolean): CommandButton {
        return CommandButton.Builder()
            .setDisplayName(if (isLiked) "В медиатеке" else "Добавить в медиатеку")
            .setIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_border)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .build()
    }

    fun updateTrackCover(fullId: String, coverUrl: String) {
        serviceScope.launch(Dispatchers.Main) {
            val cur = _currentTrack.value
            if (cur != null && cur.fullId == fullId && cur.coverUrl != coverUrl) {
                val updated = cur.copy(coverUrl = coverUrl)
                _currentTrack.value = updated
                loadArtworkAsync(updated)
            }
            val idx = currentQueue.indexOfFirst { it.fullId == fullId }
            if (idx >= 0 && currentQueue[idx].coverUrl != coverUrl) {
                val updatedQueue = currentQueue.toMutableList().also { it[idx] = it[idx].copy(coverUrl = coverUrl) }
                currentQueue = updatedQueue
                _currentQueueFlow.value = updatedQueue
            }
        }
    }

    private fun loadArtworkAsync(track: Track) {
        val currentKey = "${track.fullId}_${track.title}_${track.artist}_${track.coverUrl}"
        if (lastLoadedArtworkKey == currentKey) {
            return
        }
        lastLoadedArtworkKey = currentKey

        artworkJob?.cancel()
        artworkJob = serviceScope.launch(Dispatchers.IO) {
            val coverUrl: String? = track.coverUrl?.takeIf { it.isNotBlank() }
                ?: VkApiClient.getCachedCover(track.fullId)?.takeIf { it.isNotBlank() }
                ?: run {
                    try {
                        val db = AppDatabase.getInstance(applicationContext)
                        db.trackDao().getTrackById(track.fullId)?.coverUrl?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
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
                        .size(128, 128)
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
                                if (isCurrent && currentNotificationBitmap != bitmap) {
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
        progressJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val pos = if (now - lastSeekTimestamp >= 1200L) {
                    player.currentPosition.coerceAtLeast(0)
                } else {
                    lastSeekPositionMs
                }
                if (_currentPositionMs.value != pos) {
                    _currentPositionMs.value = pos
                }
                val dur = player.duration
                if (dur > 0 && _durationMs.value != dur) {
                    _durationMs.value = dur
                }
                delay(500)
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

    // Сохранение состояния очереди и позиции (VKX QueueSaveHolder) с адаптивным скользящим окном
    private fun saveQueueState(sync: Boolean = false) {
        try {
            val queue = currentQueue.toList()
            if (queue.isEmpty()) return
            val currentIndex = player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
            val currentPos = player.currentPosition.coerceAtLeast(0)

            val windowStart = (currentIndex - 30).coerceAtLeast(0)
            val windowEnd = (windowStart + 150).coerceAtMost(queue.size)
            val slice = queue.subList(windowStart, windowEnd).toList()
            val relativeSavedIndex = (currentIndex - windowStart).coerceIn(0, (slice.size - 1).coerceAtLeast(0))

            val writeAction = {
                try {
                    val jsonArray = JSONArray()
                    slice.forEach { track ->
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
                    val editor = prefs.edit()
                        .putString("saved_queue_json", jsonArray.toString())
                        .putInt("saved_queue_index", relativeSavedIndex)
                        .putLong("saved_position_ms", currentPos)
                    if (sync) editor.commit() else editor.apply()
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Error serializing queue state", e)
                }
            }

            if (sync) {
                writeAction()
            } else {
                serviceScope.launch(Dispatchers.IO) { writeAction() }
            }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Error saving queue state", e)
        }
    }

    private fun handleToggleLike(track: Track) {
        val listener = onToggleLikeListener
        if (listener != null) {
            listener.invoke(track)
        } else {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    val isCurrentlyLiked = isCurrentTrackLiked(track)
                    val newLikedState = !isCurrentlyLiked

                    setTrackLiked(track.fullId, newLikedState)
                    db.trackDao().updateLike(track.fullId, newLikedState)

                    withContext(Dispatchers.Main) {
                        updateNotificationLikeButton()
                    }

                    val apiClient = NetworkModule.getVkApiClient(applicationContext)
                    if (newLikedState) {
                        apiClient.addAudio(track.id, track.ownerId)
                    } else {
                        apiClient.deleteAudio(track.id, track.ownerId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Failed to toggle like in background", e)
                }
            }
        }
    }

    // Восстановление состояния очереди и позиции при перезапуске (VKX QueueSaveHolder)
    private fun restoreSavedQueue() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("playback_state_prefs", Context.MODE_PRIVATE)
                val jsonStr = prefs.getString("saved_queue_json", null) ?: return@launch
                if (jsonStr.isEmpty() || jsonStr == "[]") return@launch

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

                if (restored.isEmpty()) return@launch
                val savedIndex = prefs.getInt("saved_queue_index", 0).coerceIn(0, restored.lastIndex)
                val savedPos = prefs.getLong("saved_position_ms", 0L).coerceAtLeast(0L)

                // Обновляем streamUrl текущего VK-трека на случай истечения подписи ссылки
                var current = restored[savedIndex]
                if (!current.isLocal && current.duration > 0) {
                    val refreshed = try {
                        NetworkModule.getVkApiClient(applicationContext).resolveTrack(current)
                    } catch (_: Exception) {
                        null
                    }
                    if (refreshed != null && refreshed.streamUrl.isNotEmpty()) {
                        current = refreshed
                        restored[savedIndex] = refreshed
                    }
                }

                withContext(Dispatchers.Main) {
                    // Если пользователь уже запустил новую очередь за время фонового чтения — не перезаписываем
                    if (currentQueue.isNotEmpty()) return@withContext

                    currentQueue = restored
                    _currentQueueFlow.value = restored

                    val mediaItems = restored.map { track -> buildMediaItem(track) }
                    player.setMediaItems(mediaItems, savedIndex, savedPos)
                    player.prepare()
                    player.playWhenReady = false

                    _currentTrack.value = current
                    _currentPositionMs.value = savedPos
                    _durationMs.value = (current.duration.toLong() * 1000L).coerceAtLeast(0)
                    loadArtworkAsync(current)
                    updateNotificationLikeButton()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Error restoring saved queue", e)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_LIKE = "ACTION_TOGGLE_LIKE"
        const val ACTION_OPEN_PLAYER = "com.vktracks.player.ACTION_OPEN_PLAYER"
        const val EXTRA_OPEN_FULL_PLAYER = "extra_open_full_player"

        private val TRUSTED_MEDIA_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.bluetooth",
            "com.google.android.projection.gearhead",
            "com.google.android.googlequicksearchbox",
            "com.pioneer.smartsync"
        )

        @Volatile
        var instance: PlaybackService? = null
            private set

        @Volatile
        var onToggleLikeListener: ((Track) -> Unit)? = null
        private val likedTrackLock = Any()
        @Volatile
        private var likedTrackIds: Set<String> = emptySet()

        fun updateLikedTrackIds(ids: Set<String>) {
            synchronized(likedTrackLock) {
                likedTrackIds = ids.toSet()
            }
            instance?.updateNotificationLikeButton()
        }

        fun setTrackLiked(fullId: String, liked: Boolean) {
            synchronized(likedTrackLock) {
                likedTrackIds = if (liked) likedTrackIds + fullId else likedTrackIds - fullId
            }
        }

        fun isCurrentTrackLiked(track: Track): Boolean {
            return likedTrackIds.contains(track.fullId) || track.isLiked
        }

        @Volatile
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
