package com.vktracks.player.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

data class TrackCoverTuple(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "coverUrl") val coverUrl: String?
)

@Dao
interface TrackDao {
    @Query("SELECT id, coverUrl FROM tracks WHERE coverUrl IS NOT NULL AND coverUrl != ''")
    suspend fun getAllCachedCovers(): List<TrackCoverTuple>

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

    @Query("UPDATE tracks SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun updateCover(id: String, coverUrl: String)

    @Query("UPDATE tracks SET coverUrl = NULL WHERE isDownloaded = 0")
    suspend fun clearCachedTrackCovers()
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS cover_cache")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE tracks SET coverUrl = NULL WHERE isDownloaded = 0")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Очищаем потенциально некорректные обложки из сторонних плейлистов,
        // чтобы они подгрузились заново строго по правилам v1.0.48
        db.execSQL("UPDATE tracks SET coverUrl = NULL WHERE isDownloaded = 0")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Сбрасываем кэш обложек для чистой строгой иерархии v1.0.50
        db.execSQL("UPDATE tracks SET coverUrl = NULL WHERE isDownloaded = 0")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Очищаем кэш старых обложек из ВК для перехода на официальные HD-обложки Apple iTunes
        db.execSQL("UPDATE tracks SET coverUrl = NULL WHERE isDownloaded = 0")
    }
}

@Database(entities = [TrackEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vk_tracks.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
