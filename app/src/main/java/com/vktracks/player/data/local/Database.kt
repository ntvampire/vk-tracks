package com.vktracks.player.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String, // ownerId_id
    val trackId: Long,
    val ownerId: Long,
    val title: String,
    val artist: String,
    val duration: Int,
    val streamUrl: String,
    val coverUrl: String?,
    val albumTitle: String?,
    val isExplicit: Boolean,
    val lyricsId: Long?,
    val accessKey: String?,
    val isLiked: Boolean,
    val localFilePath: String?,
    val isDownloaded: Boolean = false,
    val lastPlayedTimestamp: Long = 0
)

@Entity(tableName = "cover_cache")
data class CoverCacheEntity(
    @PrimaryKey val queryKey: String, // "artist - cleanTitle" lowercase
    val coverUrl: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 ORDER BY title ASC")
    fun getDownloadedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE lastPlayedTimestamp > 0 ORDER BY lastPlayedTimestamp DESC LIMIT 100")
    fun getHistoryTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isDownloaded = 0, localFilePath = NULL WHERE id = :id")
    suspend fun removeDownload(id: String)

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE id = :id")
    suspend fun updateLike(id: String, isLiked: Boolean)
}

@Dao
interface CoverCacheDao {
    @Query("SELECT coverUrl FROM cover_cache WHERE queryKey = :key LIMIT 1")
    suspend fun getCover(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCover(entity: CoverCacheEntity)
}

@Database(entities = [TrackEntity::class, CoverCacheEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun coverCacheDao(): CoverCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vk_tracks.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
