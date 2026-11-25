package com.vktracks.player.data.api

import android.util.LruCache
import com.vktracks.player.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Каскадный резолвер и ограниченный LRU-кэш обложек треков и альбомов (с TTL для отрицательных результатов).
 */
class CoverResolverService(
    private val yandexMusicApiClient: YandexMusicApiClient = YandexMusicApiClient(),
    private val itunesApiClient: ItunesApiClient = ItunesApiClient()
) {
    companion object {
        private const val NEGATIVE_CACHE_TTL_MS = 10 * 60 * 1000L // 10 минут для повторной попытки после сбоя сети
        private val GENERIC_ALBUMS = setOf("single", "single edit", "remix", "ep", "сингл", "unknown")

        private val trackCoverCache = LruCache<String, String>(2000)
        private val albumCoverCache = LruCache<String, String>(500)
        private val negativeCoverExpiry = ConcurrentHashMap<String, Long>()

        fun preloadCovers(covers: Map<String, String>) {
            for ((key, url) in covers) {
                if (url.isNotEmpty()) {
                    trackCoverCache.put(key, url)
                    negativeCoverExpiry.remove(key)
                }
            }
        }

        fun cacheCover(fullId: String, coverUrl: String) {
            if (coverUrl.isNotEmpty()) {
                trackCoverCache.put(fullId, coverUrl)
                negativeCoverExpiry.remove(fullId)
            }
        }

        fun cacheAlbumCover(albumKey: String, coverUrl: String) {
            if (coverUrl.isNotEmpty()) {
                albumCoverCache.put(albumKey, coverUrl)
            }
        }

        fun getCachedCover(fullId: String): String? = trackCoverCache.get(fullId)?.takeIf { it.isNotEmpty() }

        fun getCachedAlbumCover(albumKey: String): String? = albumCoverCache.get(albumKey)?.takeIf { it.isNotEmpty() }

        fun isCoverResolved(fullId: String): Boolean {
            if (trackCoverCache.get(fullId) != null) return true
            val expiry = negativeCoverExpiry[fullId] ?: return false
            if (System.currentTimeMillis() < expiry) return true
            negativeCoverExpiry.remove(fullId)
            return false
        }

        fun markNegative(fullId: String) {
            if (negativeCoverExpiry.size > 2000) {
                val now = System.currentTimeMillis()
                negativeCoverExpiry.entries.removeIf { it.value <= now }
            }
            negativeCoverExpiry[fullId] = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS
        }

        fun clearCaches() {
            trackCoverCache.evictAll()
            albumCoverCache.evictAll()
            negativeCoverExpiry.clear()
        }

        fun albumKey(artist: String, albumTitle: String?): String? {
            if (albumTitle.isNullOrBlank() || artist.isBlank()) return null
            val cleanArtist = artist.trim().lowercase()
            val cleanAlbum = albumTitle.trim().lowercase()
            if (cleanAlbum in GENERIC_ALBUMS) return null
            return "${cleanArtist}__${cleanAlbum}"
        }

        fun isValidImageUrl(url: String): Boolean {
            val lower = url.trim().lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            if (lower.contains(".m3u8") || lower.contains(".mp3") || lower.contains(".mp4") || lower.contains(".aac")) return false
            return true
        }
    }

    suspend fun enrichTracksWithVkDetails(tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()

        tracks.map { track ->
            if (track.coverUrl.isNullOrEmpty() || !isValidImageUrl(track.coverUrl)) {
                val cached = getCachedCover(track.fullId)
                    ?: albumKey(track.artist, track.albumTitle)?.let { getCachedAlbumCover(it) }
                if (!cached.isNullOrEmpty()) track.copy(coverUrl = cached) else track
            } else {
                cacheCover(track.fullId, track.coverUrl)
                albumKey(track.artist, track.albumTitle)?.let { cacheAlbumCover(it, track.coverUrl) }
                track
            }
        }
    }

    suspend fun resolveTrackCover(track: Track): String? = withContext(Dispatchers.IO) {
        if (!track.coverUrl.isNullOrEmpty() && isValidImageUrl(track.coverUrl)) {
            cacheCover(track.fullId, track.coverUrl)
            albumKey(track.artist, track.albumTitle)?.let { cacheAlbumCover(it, track.coverUrl) }
            return@withContext track.coverUrl
        }

        getCachedCover(track.fullId)?.let { return@withContext it }

        val expiry = negativeCoverExpiry[track.fullId]
        if (expiry != null && System.currentTimeMillis() < expiry) {
            return@withContext null
        }

        val albumCached = albumKey(track.artist, track.albumTitle)?.let { getCachedAlbumCover(it) }
        if (!albumCached.isNullOrEmpty() && isValidImageUrl(albumCached)) {
            cacheCover(track.fullId, albumCached)
            return@withContext albumCached
        }

        if (TrackMetadataMatcher.isGeneric(track.artist, track.title)) {
            markNegative(track.fullId)
            return@withContext null
        }

        val yandexCover = yandexMusicApiClient.fetchTrackCover(track.artist, track.title)
        if (!yandexCover.isNullOrEmpty() && isValidImageUrl(yandexCover)) {
            cacheCover(track.fullId, yandexCover)
            albumKey(track.artist, track.albumTitle)?.let { cacheAlbumCover(it, yandexCover) }
            return@withContext yandexCover
        }

        val itunesCover = itunesApiClient.fetchTrackCover(track.artist, track.title)
        if (!itunesCover.isNullOrEmpty() && isValidImageUrl(itunesCover)) {
            cacheCover(track.fullId, itunesCover)
            albumKey(track.artist, track.albumTitle)?.let { cacheAlbumCover(it, itunesCover) }
            return@withContext itunesCover
        }

        val artistCover = itunesApiClient.fetchArtistCover(track.artist)
        if (!artistCover.isNullOrEmpty() && isValidImageUrl(artistCover)) {
            cacheCover(track.fullId, artistCover)
            return@withContext artistCover
        }

        markNegative(track.fullId)
        null
    }
}
