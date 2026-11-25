package com.vktracks.player.data.api

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Централизованный провайдер OkHttpClient на одном ConnectionPool и Dispatcher,
 * исключающий утечку пулов потоков и сокетов между модулями приложения.
 */
object NetworkModule {

    private val sharedConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val apiClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val fastMetadataClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    val imageClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var sharedVkApiClient: VkApiClient? = null

    fun getVkApiClient(context: Context): VkApiClient {
        return sharedVkApiClient ?: synchronized(this) {
            sharedVkApiClient ?: VkApiClient(VkAuthManager(context.applicationContext)).also {
                sharedVkApiClient = it
            }
        }
    }
}
