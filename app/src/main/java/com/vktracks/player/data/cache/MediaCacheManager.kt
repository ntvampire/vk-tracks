package com.vktracks.player.data.cache

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.text.DecimalFormat

@UnstableApi
class MediaCacheManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("media_cache_settings", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: MediaCacheManager? = null

        fun getInstance(context: Context): MediaCacheManager {
            return instance ?: synchronized(this) {
                instance ?: MediaCacheManager(context.applicationContext).also { instance = it }
            }
        }

        const val PREF_CACHE_ENABLED = "cache_enabled"
        const val PREF_CACHE_MAX_MB = "cache_max_mb"
        const val DEFAULT_MAX_MB = 2048 // 2 ГБ по умолчанию (как в VKX)
    }

    private val cacheDir = File(context.cacheDir, "media_stream_cache").apply { if (!exists()) mkdirs() }
    private val databaseProvider = StandaloneDatabaseProvider(context)
    val simpleCache: SimpleCache

    init {
        val maxMb = prefs.getInt(PREF_CACHE_MAX_MB, DEFAULT_MAX_MB)
        val maxBytes = if (maxMb <= 0) 50L * 1024 * 1024 * 1024 else maxMb.toLong() * 1024 * 1024
        val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
        simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
    }

    fun isCacheEnabled(): Boolean {
        return prefs.getBoolean(PREF_CACHE_ENABLED, true)
    }

    fun setCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_CACHE_ENABLED, enabled).apply()
    }

    fun getMaxCacheMb(): Int {
        return prefs.getInt(PREF_CACHE_MAX_MB, DEFAULT_MAX_MB)
    }

    fun setMaxCacheMb(mb: Int) {
        prefs.edit().putInt(PREF_CACHE_MAX_MB, mb).apply()
    }

    fun getCurrentCacheSizeBytes(): Long {
        return try {
            simpleCache.cacheSpace
        } catch (_: Exception) {
            0L
        }
    }

    fun getFormattedCacheSize(): String {
        val bytes = getCurrentCacheSizeBytes()
        if (bytes <= 0) return "0 МБ"
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1024) {
            val gb = mb / 1024
            DecimalFormat("#.## ГБ").format(gb)
        } else {
            DecimalFormat("#.# МБ").format(mb)
        }
    }

    fun clearCache() {
        try {
            val keys = simpleCache.keys
            for (key in keys) {
                simpleCache.removeResource(key)
            }
        } catch (_: Exception) {
            try {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            } catch (_: Exception) {}
        }
    }

    fun isTrackCached(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            simpleCache.isCached(url, 0, 1024 * 64)
        } catch (_: Exception) {
            false
        }
    }

    fun isLiveStreamUri(uriStr: String): Boolean {
        if (uriStr.isBlank()) return false
        return uriStr.contains("icecast", ignoreCase = true) ||
               uriStr.contains("radiorecord", ignoreCase = true) ||
               uriStr.contains("cdnvideo", ignoreCase = true) ||
               uriStr.contains("hostingradio", ignoreCase = true) ||
               uriStr.contains("studio21", ignoreCase = true) ||
               uriStr.contains(".aacp", ignoreCase = true) ||
               uriStr.contains("live", ignoreCase = true) ||
               uriStr.contains("stream.", ignoreCase = true)
    }

    fun getDataSourceFactory(): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VKTracks/1.0 (Android)")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)

        if (!isCacheEnabled()) {
            return httpFactory
        }

        val cacheSink = CacheDataSink.Factory()
            .setCache(simpleCache)
            .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheWriteDataSinkFactory(cacheSink)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Делегирующая фабрика: живое радио играет напрямую без кэша, обычные треки ВК кэшируются на лету
        return object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                val httpSource = httpFactory.createDataSource()
                val cacheSource = cacheDataSourceFactory.createDataSource()
                return object : DataSource {
                    private var activeSource: DataSource = cacheSource

                    override fun addTransferListener(transferListener: TransferListener) {
                        httpSource.addTransferListener(transferListener)
                        cacheSource.addTransferListener(transferListener)
                    }

                    override fun open(dataSpec: DataSpec): Long {
                        val uriStr = dataSpec.uri.toString()
                        activeSource = if (isLiveStreamUri(uriStr)) httpSource else cacheSource
                        return activeSource.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        return activeSource.read(buffer, offset, length)
                    }

                    override fun getUri(): Uri? = activeSource.uri

                    override fun getResponseHeaders(): Map<String, List<String>> = activeSource.responseHeaders

                    override fun close() {
                        activeSource.close()
                    }
                }
            }
        }
    }
}
