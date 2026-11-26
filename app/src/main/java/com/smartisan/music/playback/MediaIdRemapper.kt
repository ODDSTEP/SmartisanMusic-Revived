package com.smartisan.music.playback

import android.content.Context
import com.smartisan.music.data.favorite.FavoriteSongsDatabase
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.playback.PlaybackStatsDatabase
import com.smartisan.music.data.playlist.PlaylistDatabase
import com.smartisan.music.diagnostics.AppLog

/**
 * 媒体 id 迁移（1.2.26）。
 *
 * **问题**：所有业务数据（收藏、播放次数、评分、歌单成员、隐藏项）都以 `mediaId` 为主键，
 * 而 `mediaId` 是 MediaStore 的数据库行号——文件被移动/重命名、SD 卡重挂载、备份恢复到
 * 新机器，系统会重新发号。索引表靠 `stableKey`（卷名+相对路径+文件名）能自愈，但这几张
 * 业务表不会，用户在界面上看到的就是「红心全空、播放次数归零、歌单里的歌不见了」。
 *
 * **做法**：[LocalAudioLibrary] 在索引重建时把「旧 id → 新 id」交给这里，逐条改写。
 * 每条都包在 `runCatching` 里：某一类数据迁移失败绝不能拖垮另外几类，更不能崩在启动路径上。
 */
internal object MediaIdRemapper {

    private const val TAG = "MediaIdRemap"

    suspend fun remap(context: Context, mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val appContext = context.applicationContext
        val favoriteDao =
            runCatching { FavoriteSongsDatabase.getInstance(appContext).favoriteSongDao() }.getOrNull()
        val statsDao =
            runCatching { PlaybackStatsDatabase.getInstance(appContext).playbackStatsDao() }.getOrNull()
        val playlistDao =
            runCatching { PlaylistDatabase.getInstance(appContext).playlistDao() }.getOrNull()
        val exclusions = LibraryExclusionsStore(appContext)
        val now = System.currentTimeMillis()
        var migrated = 0

        for ((oldId, newId) in mapping) {
            if (oldId.isBlank() || newId.isBlank() || oldId == newId) continue
            favoriteDao?.let { dao -> runCatching { dao.remapId(oldId, newId) } }
            statsDao?.let { dao -> runCatching { dao.remapId(oldId, newId, now) } }
            playlistDao?.let { dao -> runCatching { dao.remapId(oldId, newId) } }
            migrated++
        }
        runCatching { exclusions.remapMediaIds(mapping) }
            .onFailure { AppLog.w(TAG, "隐藏项迁移失败: ${it.message}") }

        AppLog.i(TAG, "媒体 id 迁移完成 count=$migrated")
    }
}
