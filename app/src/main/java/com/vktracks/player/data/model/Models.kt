package com.vktracks.player.data.model

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
    val localFilePath: String? = null
) {
    val fullId: String get() = "${ownerId}_${id}"
    val isLocal: Boolean get() = !localFilePath.isNullOrEmpty()
}

data class Playlist(
    val id: Long,
    val ownerId: Long,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val count: Int = 0,
    val accessKey: String? = null
) {
    val fullId: String get() = "${ownerId}_${id}"
}

data class LyricLine(
    val beginMs: Long,
    val endMs: Long,
    val text: String
)

data class Lyrics(
    val plainText: List<String> = emptyList(),
    val lines: List<LyricLine> = emptyList()
) {
    val isSynchronized: Boolean get() = lines.isNotEmpty()
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String
)

data class Artist(
    val id: String,
    val name: String,
    val photoUrl: String? = null
)

data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList()
)

data class StreamMix(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val images: List<String> = emptyList(),
    val actionUrl: String? = null,
    val mixType: String = "general" // "artist", "genre", "personal", "algorithm"
)

data class CatalogButtonItem(
    val id: String,
    val title: String,
    val url: String? = null,
    val iconUrl: String? = null,
    val colorHex: String? = null
)

data class CatalogBlock(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val layoutName: String = "slider",
    val dataType: String = "",
    val mixes: List<StreamMix> = emptyList(),
    val buttons: List<CatalogButtonItem> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val tracks: List<Track> = emptyList()
)
