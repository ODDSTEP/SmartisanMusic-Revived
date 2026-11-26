package com.smartisan.music.data.playback

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_stats")
internal data class PlaybackStatsEntity(
    @PrimaryKey val mediaId: String,
    val playCount: Long,
    val score: Int,
    val updatedAt: Long,
)

internal data class PlaybackStatsRow(
    val mediaId: String,
    val playCount: Long,
    val score: Int,
)

@Dao
internal interface PlaybackStatsDao {

    @Query("SELECT mediaId, playCount, score FROM playback_stats")
    fun getStats(): List<PlaybackStatsRow>

    /** 实时观察全量播放次数（Room 数据流，写入后自动发射新快照）。 */
    @Query("SELECT mediaId, playCount, score FROM playback_stats")
    fun observeStats(): Flow<List<PlaybackStatsRow>>

    @Query("SELECT mediaId, playCount, score FROM playback_stats WHERE mediaId IN (:mediaIds)")
    fun getStats(mediaIds: List<String>): List<PlaybackStatsRow>

    @Query("SELECT playCount FROM playback_stats WHERE mediaId = :mediaId")
    suspend fun getPlayCount(mediaId: String): Long?

    @Query("SELECT score FROM playback_stats WHERE mediaId = :mediaId")
    suspend fun getScore(mediaId: String): Int?

    @Query(
        """
        UPDATE playback_stats
        SET playCount = playCount + 1,
            updatedAt = :updatedAt
        WHERE mediaId = :mediaId
        """,
    )
    suspend fun incrementExisting(mediaId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE playback_stats
        SET score = :score,
            updatedAt = :updatedAt
        WHERE mediaId = :mediaId
        """,
    )
    suspend fun updateScore(mediaId: String, score: Int, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PlaybackStatsEntity): Long

    @Query(
        """
        UPDATE playback_stats
        SET playCount = playCount + COALESCE(
                (SELECT playCount FROM playback_stats WHERE mediaId = :oldId), 0),
            score = MAX(score, COALESCE(
                (SELECT score FROM playback_stats WHERE mediaId = :oldId), 0)),
            updatedAt = :now
        WHERE mediaId = :newId
        """,
    )
    suspend fun mergeFromOld(newId: String, oldId: String, now: Long): Int

    @Query("UPDATE OR IGNORE playback_stats SET mediaId = :newId WHERE mediaId = :oldId")
    suspend fun renameId(oldId: String, newId: String)

    @Query("DELETE FROM playback_stats WHERE mediaId = :oldId")
    suspend fun deleteAnyById(oldId: String)

    /**
     * 1.2.26：MediaStore 重新发号后把播放次数/评分迁到新 id。
     *
     * 新 id 已有记录时合并（次数相加、评分取高），避免「移动文件后播放次数归零」。
     */
    @androidx.room.Transaction
    suspend fun remapId(oldId: String, newId: String, now: Long) {
        if (oldId.isBlank() || newId.isBlank() || oldId == newId) return
        if (mergeFromOld(newId, oldId, now) > 0) {
            deleteAnyById(oldId)
        } else {
            renameId(oldId, newId)
            deleteAnyById(oldId)
        }
    }
}

@Database(
    entities = [PlaybackStatsEntity::class],
    version = 2,
    exportSchema = false,
)
internal abstract class PlaybackStatsDatabase : RoomDatabase() {
    abstract fun playbackStatsDao(): PlaybackStatsDao

    companion object {
        @Volatile
        private var instance: PlaybackStatsDatabase? = null

        fun getInstance(context: Context): PlaybackStatsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlaybackStatsDatabase::class.java,
                    "playback_stats.db",
                )
                    .addMigrations(Migration1To2)
                    .build()
                    .also { instance = it }
            }
        }

        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playback_stats ADD COLUMN score INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
