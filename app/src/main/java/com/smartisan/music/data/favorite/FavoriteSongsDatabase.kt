package com.smartisan.music.data.favorite

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_songs")
data class FavoriteSongEntity(
    @PrimaryKey val mediaId: String,
    val likedAt: Long,
)

@Dao
interface FavoriteSongDao {

    @Query("SELECT * FROM favorite_songs ORDER BY likedAt DESC")
    fun observeAll(): Flow<List<FavoriteSongEntity>>

    @Query("SELECT mediaId FROM favorite_songs")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteSongEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllMissing(entities: List<FavoriteSongEntity>)

    @Query("DELETE FROM favorite_songs WHERE mediaId = :mediaId")
    suspend fun deleteById(mediaId: String)

    @Query("DELETE FROM favorite_songs WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByIds(mediaIds: Set<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_songs WHERE mediaId = :mediaId)")
    suspend fun exists(mediaId: String): Boolean

    /**
     * 1.2.26：读-改-写放进同一事务。
     *
     * 旧实现是 Repository 里先 `exists()` 再 `delete/upsert`，两个独立事务；连点两次心形
     * （或歌曲行与播放条同时点）时两次都读到「未收藏」，最终变成「收藏」而不是「取消」。
     * Room 的 @Transaction 会把这段串行化，语义与「连点两次 = 恢复原状」一致。
     */
    @androidx.room.Transaction
    suspend fun toggleInTransaction(mediaId: String, likedAt: Long): Boolean {
        return if (exists(mediaId)) {
            deleteById(mediaId)
            false
        } else {
            upsert(FavoriteSongEntity(mediaId = mediaId, likedAt = likedAt))
            true
        }
    }

    @Query(
        """
        DELETE FROM favorite_songs
        WHERE mediaId = :oldId
          AND EXISTS(SELECT 1 FROM favorite_songs WHERE mediaId = :newId)
        """,
    )
    suspend fun deleteOldIfNewExists(oldId: String, newId: String)

    @Query("UPDATE OR IGNORE favorite_songs SET mediaId = :newId WHERE mediaId = :oldId")
    suspend fun renameId(oldId: String, newId: String)

    @Query("DELETE FROM favorite_songs WHERE mediaId = :oldId")
    suspend fun deleteAnyById(oldId: String)

    /**
     * 1.2.26：MediaStore 重新发号（文件移动/重命名、SD 卡重挂载）后，把收藏从旧 id 迁到新 id。
     *
     * 索引层有 stableKey 能自愈，收藏表只有 mediaId——不迁的话用户会看到「红心突然空了」。
     * 目标 id 已存在时保留一条（以新 id 为准），避免主键冲突。
     */
    @androidx.room.Transaction
    suspend fun remapId(oldId: String, newId: String) {
        if (oldId.isBlank() || newId.isBlank() || oldId == newId) return
        deleteOldIfNewExists(oldId, newId)
        renameId(oldId, newId)
        deleteAnyById(oldId)
    }
}

@Database(
    entities = [FavoriteSongEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FavoriteSongsDatabase : RoomDatabase() {
    abstract fun favoriteSongDao(): FavoriteSongDao

    companion object {
        @Volatile
        private var instance: FavoriteSongsDatabase? = null

        fun getInstance(context: Context): FavoriteSongsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FavoriteSongsDatabase::class.java,
                    "favorite_songs.db",
                )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
