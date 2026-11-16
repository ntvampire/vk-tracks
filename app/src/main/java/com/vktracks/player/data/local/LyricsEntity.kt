package com.vktracks.player.data.local

import androidx.room.*
import com.vktracks.player.data.api.LrcParser
import com.vktracks.player.data.model.Lyrics

@Entity(tableName = "track_lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: String, // ownerId_id
    val plainText: String?,
    val syncedLyrics: String?,
    val isInstrumental: Boolean = false,
    val fetchedTimestamp: Long = System.currentTimeMillis()
)

fun LyricsEntity.toLyrics(): Lyrics {
    if (isInstrumental) {
        return Lyrics(plainText = emptyList(), lines = emptyList(), isInstrumental = true)
    }
    val syncedLines = LrcParser.parse(syncedLyrics)
    val plainList = plainText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    return Lyrics(
        plainText = plainList,
        lines = syncedLines,
        isInstrumental = false
    )
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM track_lyrics WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyrics(trackId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM track_lyrics WHERE trackId = :trackId")
    suspend fun deleteLyrics(trackId: String)
}
