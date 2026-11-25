package com.smartisan.music.data.playback

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PlaybackStatsRecord(
    val playCount: Long,
    val score: Int,
)

class PlaybackStatsRepository private constructor(
    private val database: PlaybackStatsDatabase,
) {

    private val playbackStatsDao = database.playbackStatsDao()

    fun getStats(): Map<String, PlaybackStatsRecord> {
        return playbackStatsDao.getStats()
            .toStatsMap()
    }

    fun getStats(mediaIds: Set<String>): Map<String, PlaybackStatsRecord> {
        if (mediaIds.isEmpty()) {
            return emptyMap()
        }
        return mediaIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .chunked(PlaybackStatsQueryChunkSize)
            .flatMap { chunk -> playbackStatsDao.getStats(chunk) }
            .toList()
            .toStatsMap()
    }

    /**
     * 全量播放次数实时流（mediaId → playCount）。
     * 1.2.23：歌曲页据此显示/排序「实时」播放次数，与「理解记忆」同源（均落 Room playback_stats），
     * 不再依赖扫描时写入 MediaItem extras 的静态快照，从而一点击即 +1 并即时刷新。
     */
    val statsFlow: Flow<Map<String, Long>> =
        playbackStatsDao.observeStats().map { rows ->
            rows.associate { it.mediaId to it.playCount }
        }

    suspend fun incrementPlayCount(
        mediaId: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): Long? {
        val normalizedMediaId = mediaId.trim()
        if (normalizedMediaId.isEmpty()) {
            return null
        }
        return database.withTransaction {
            if (playbackStatsDao.incrementExisting(normalizedMediaId, updatedAt) == 0) {
                val inserted = playbackStatsDao.insert(
                    PlaybackStatsEntity(
                        mediaId = normalizedMediaId,
                        playCount = 1L,
                        score = 0,
                        updatedAt = updatedAt,
                    ),
                )
                if (inserted == -1L) {
                    playbackStatsDao.incrementExisting(normalizedMediaId, updatedAt)
                }
            }
            playbackStatsDao.getPlayCount(normalizedMediaId)
        }
    }

    suspend fun setScore(
        mediaId: String,
        score: Int,
        updatedAt: Long = System.currentTimeMillis(),
    ): Int? {
        val normalizedMediaId = mediaId.trim()
        if (normalizedMediaId.isEmpty()) {
            return null
        }
        val normalizedScore = score.coerceIn(MinScore, MaxScore)
        return database.withTransaction {
            if (playbackStatsDao.updateScore(normalizedMediaId, normalizedScore, updatedAt) == 0) {
                val inserted = playbackStatsDao.insert(
                    PlaybackStatsEntity(
                        mediaId = normalizedMediaId,
                        playCount = 0L,
                        score = normalizedScore,
                        updatedAt = updatedAt,
                    ),
                )
                if (inserted == -1L) {
                    playbackStatsDao.updateScore(normalizedMediaId, normalizedScore, updatedAt)
                }
            }
            playbackStatsDao.getScore(normalizedMediaId)
        }
    }

    companion object {
        @Volatile
        private var instance: PlaybackStatsRepository? = null

        fun getInstance(context: Context): PlaybackStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: PlaybackStatsRepository(
                    PlaybackStatsDatabase.getInstance(context.applicationContext),
                ).also { instance = it }
            }
        }

        internal fun create(database: PlaybackStatsDatabase): PlaybackStatsRepository {
            return PlaybackStatsRepository(database)
        }

        private const val MinScore = 0
        private const val MaxScore = 5
        private const val PlaybackStatsQueryChunkSize = 500
    }
}

private fun List<PlaybackStatsRow>.toStatsMap(): Map<String, PlaybackStatsRecord> {
    return associate { row ->
        row.mediaId to PlaybackStatsRecord(
            playCount = row.playCount,
            score = row.score,
        )
    }
}
