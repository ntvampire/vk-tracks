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

fun LyricsEntity.toLyrics(): Lyrics = LrcParser.buildLyrics(plainText, syncedLyrics, isInstrumental)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM track_lyrics WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyrics(trackId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM track_lyrics WHERE trackId = :trackId")
    suspend fun deleteLyrics(trackId: String)
}
