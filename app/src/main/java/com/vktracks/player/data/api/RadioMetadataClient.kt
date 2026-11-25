package com.vktracks.player.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Клиент метаданных онлайн-радиостанций (Radio Record HTTP API и Studio 21 EMG WebSocket).
 */
class RadioMetadataClient(
    private val httpClient: OkHttpClient = NetworkModule.apiClient
) {
    suspend fun getRecordNowPlaying(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.radiorecord.ru/api/stations/now/")
                .header("User-Agent", "VKTracks/1.0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
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

        return httpClient.newWebSocket(req, object : WebSocketListener() {
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
                        var artist = targetObj.optString("artist").ifEmpty { targetObj.optString("singer") }.trim()
                        var title = targetObj.optString("title").ifEmpty { targetObj.optString("song") }.trim()
                        if (artist.isEmpty() && title.contains(" - ")) {
                            val parts = title.split(" - ", limit = 2)
                            artist = parts[0].trim()
                            title = parts[1].trim()
                        }
                        val rawCover = targetObj.optString("coverImageUrl600").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl300").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl100").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("coverImageUrl").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("cover").takeIf { it.isNotEmpty() }
                            ?: targetObj.optString("image").takeIf { it.isNotEmpty() }

                        if (title.isNotEmpty()) {
                            onTrackUpdate(title, artist, rawCover)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RadioMetadataClient", "Failed to parse Studio 21 WS message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("RadioMetadataClient", "Studio 21 WS connection failed: ${t.message}")
            }
        })
    }
}
