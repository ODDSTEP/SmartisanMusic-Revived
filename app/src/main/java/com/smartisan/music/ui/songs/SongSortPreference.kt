package com.smartisan.music.ui.songs

import android.content.Context

/**
 * 歌曲页排序选择的持久化（1.2.25）。
 *
 * **为什么需要**：搜索页点歌时要把「全库列表」当播放队列，如果它用的是媒体库索引顺序，
 * 而歌曲页用的是用户当前排序，那么同一个库在两个入口点歌后队列顺序完全不同——用户看到的
 * 就是「播放列表内容好像不一样」。持久化到同一份偏好后，两边按同一顺序建队列。
 *
 * 顺带修掉「排序每次冷启动都回到歌曲名」的问题。用 SharedPreferences 而非 DataStore：
 * 只有两个标量，读写都在主线程一次完成，不需要 Flow/suspend 的复杂度。
 */
internal object SongSortPreference {

    private const val FileName = "song_sort_pref"
    private const val KeyMain = "main"
    private const val KeyAscending = "ascending"

    fun load(context: Context): SongSortSelection {
        val prefs =
            context.applicationContext.getSharedPreferences(FileName, Context.MODE_PRIVATE)
        return SongSortSelection(
            main = prefs.getInt(KeyMain, SongSortMainName),
            ascending = prefs.getBoolean(KeyAscending, true),
        )
    }

    fun save(context: Context, selection: SongSortSelection) {
        context.applicationContext
            .getSharedPreferences(FileName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyMain, selection.main)
            .putBoolean(KeyAscending, selection.ascending)
            .apply()
    }
}
