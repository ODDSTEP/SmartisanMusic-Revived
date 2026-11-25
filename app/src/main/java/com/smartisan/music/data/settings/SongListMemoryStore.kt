package com.smartisan.music.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.4.11 · S16 记忆式首页（方案 A：现有「歌曲」页当首页，不新增页面）。
 *
 * 只做一件事：记住歌曲列表上次的滚动位置，回到歌曲页时接着看；纯本地、无推荐。
 * 与「理解记忆」（ListeningMemoryStore=听歌统计）职责分开，互不合并（S16-6）。
 *
 * - 开关 [enabled]：默认 true（S16-7 加开关、默认开），可在设置里关掉恢复默认行为。
 * - 滚动位置按排序维度分别保存（不同排序的行序不同，位置不能串用）。
 * - 用轻量 SharedPreferences 同步载入，冷启动首帧不闪（仿 StatusBarStyleStore）。
 */
object SongListMemoryStore {

    private const val PrefName = "song_list_memory_settings"
    private const val KeyEnabled = "song_list_memory_enabled"
    /** 位置键前缀 + 排序维度序号，避免不同排序串位。 */
    private const val KeyScrollPrefix = "song_list_scroll_"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /** Application.onCreate 里调一次，同步载入（默认开）。 */
    fun initialize(context: Context) {
        val prefs = prefs(context)
        _enabled.value = prefs.getBoolean(KeyEnabled, true)
    }

    fun current(): Boolean = _enabled.value

    fun set(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KeyEnabled, value).apply()
        _enabled.value = value
    }

    /** 读取某排序维度下保存的首个可见项索引；-1 表示无记录/从头开始。 */
    fun scrollIndex(context: Context, sortKey: Int): Int =
        prefs(context).getInt("$KeyScrollPrefix$sortKey", -1)

    /** 保存滚动位置（仅在开关开启时由调用方写入）。 */
    fun saveScrollIndex(context: Context, sortKey: Int, index: Int) {
        prefs(context).edit().putInt("$KeyScrollPrefix$sortKey", index.coerceAtLeast(0)).apply()
    }

    /** 清除某排序维度的位置（列表重建/数据变化时可调用）。 */
    fun clearScrollIndex(context: Context, sortKey: Int) {
        prefs(context).edit().remove("$KeyScrollPrefix$sortKey").apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
}
