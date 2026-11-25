package com.vktracks.player.data.model

import androidx.compose.runtime.Immutable

enum class TrackRestriction(val code: Int, val description: String) {
    NONE(0, ""),
    ARTIST_DECISION(1, "Изъято правообладателем"),
    GEO(2, "Недоступно в регионе"),
    SUBSCRIPTION(3, "Требуется подписка"),
    REPLACEMENT(4, "Заменено"),
    SOON(5, "Скоро появится"),
    LABEL_DECISION(10, "Изъято лейблом"),
    UNKNOWN(-1, "Недоступно");

    companion object {
        fun from(code: Int): TrackRestriction = entries.firstOrNull { it.code == code } ?: if (code > 0) UNKNOWN else NONE
    }
}

@Immutable
data class Track(
    val id: Long,
    val ownerId: Long,
    val title: String,
    val artist: String,
    val duration: Int, // seconds
    val streamUrl: String,
    val coverUrl: String? = null,
    val albumTitle: String? = null,
    val isExplicit: Boolean = false,
    val lyricsId: Long? = null,
    val accessKey: String? = null,
    val isLiked: Boolean = false,
    val localFilePath: String? = null,
    val restriction: TrackRestriction = TrackRestriction.NONE,
    val restrictionText: String? = null
) {
    val fullId: String get() = "${ownerId}_${id}"
    val isLocal: Boolean get() = !localFilePath.isNullOrEmpty()
    val isRestricted: Boolean get() = restriction != TrackRestriction.NONE || (streamUrl.isEmpty() && !isLocal && duration > 0)
    val displayRestriction: String get() = restrictionText?.takeIf { it.isNotBlank() } ?: restriction.description.takeIf { it.isNotEmpty() } ?: "Недоступно"
}

@Immutable
data class Playlist(
    val id: Long,
    val ownerId: Long,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val count: Int = 0,
    val accessKey: String? = null,
    val isAlbum: Boolean = false,
    val year: Int? = null,
    val mainArtist: String? = null
) {
    val fullId: String get() = "${ownerId}_${id}"
}

fun Int.toTrackCountString(): String {
    val rem100 = this % 100
    val rem10 = this % 10
    val word = when {
        rem100 in 11..19 -> "треков"
        rem10 == 1 -> "трек"
        rem10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$this $word"
}

@Immutable
data class LyricLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String
)

@Immutable
data class Lyrics(
    val plainText: List<String> = emptyList(),
    val lines: List<LyricLine> = emptyList(),
    val isInstrumental: Boolean = false
) {
    val isSynchronized: Boolean get() = lines.isNotEmpty()
}

@Immutable
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val mirrorUrl: String? = null,
    val changelog: String,
    val sha256: String? = null
)

@Immutable
data class Artist(
    val id: String,
    val name: String,
    val photoUrl: String? = null
)

@Immutable
data class ArtistDetail(
    val artist: Artist,
    val popularTracks: List<Track> = emptyList(),
    val albums: List<Playlist> = emptyList(),
    val bio: String? = null
)

@Immutable
data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList()
)

@Immutable
data class StreamMix(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val images: List<String> = emptyList(),
    val actionUrl: String? = null,
    val mixType: String = "general" // "artist", "genre", "personal", "algorithm"
)

@Immutable
data class CatalogButtonItem(
    val id: String,
    val title: String,
    val url: String? = null,
    val iconUrl: String? = null,
    val colorHex: String? = null
)

@Immutable
data class CatalogBlock(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val layoutName: String = "slider",
    val dataType: String = "",
    val mixes: List<StreamMix> = emptyList(),
    val buttons: List<CatalogButtonItem> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val nextFrom: String? = null
)

enum class RadioStation(
    val id: String,
    val title: String,
    val subtitle: String,
    val streamUrl: String,
    val logoUrl: String
) {
    STUDIO_21(
        id = "radio_studio_21",
        title = "Studio 21",
        subtitle = "Hip-Hop & R&B • Прямой эфир",
        streamUrl = "https://icecast-studio21.cdnvideo.ru/S21cl_1r",
        logoUrl = "https://studio21.ru/_nuxt/img/disk.2bba1d4.png"
    ),
    RADIO_RECORD(
        id = "radio_record",
        title = "Radio Record",
        subtitle = "Dance Radio • Прямой эфир",
        streamUrl = "https://radiorecord.hostingradio.ru/rr_main96.aacp",
        logoUrl = "https://www.radiorecord.ru/upload/stations_images/record_image600_colored_fill.png"
    );

    fun toTrack(): Track = Track(
        id = id.hashCode().toLong(),
        ownerId = 0,
        title = title,
        artist = subtitle,
        duration = 0,
        streamUrl = streamUrl,
        coverUrl = logoUrl,
        albumTitle = "Онлайн-радио"
    )
}

@Immutable
data class VkMixSettings(
    val mood: String = "any", // "any", "energetic", "calm", "sad", "happy"
    val familiarity: String = "any", // "any", "familiar", "discover", "popular"
    val language: String = "any", // "any", "russian", "foreign"
    val genre: String = "any" // "any", "pop", "rock", "hiphop", "electro", "indie", "metal"
)

@Immutable
data class AudiosPage(
    val tracks: List<Track> = emptyList(),
    val totalCount: Int = 0,
    val rawCount: Int = 0
)
