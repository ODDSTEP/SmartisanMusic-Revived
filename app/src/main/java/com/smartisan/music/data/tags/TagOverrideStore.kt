package com.smartisan.music.data.tags

import android.content.Context
import org.json.JSONObject

/**
 * 歌曲信息编辑的**应用内显示覆盖层**（1.2.16 起）。
 *
 * 背景：编辑歌曲信息后要等媒体库自行重扫，各列表才刷新，原因有三：
 *  1. MediaStore.update 对 TITLE/ARTIST/ALBUM 列在部分系统上静默失败；
 *  2. MediaScannerConnection.scanFile 异步提取，分钟级才生效；
 *  3. 索引重建时若文件属性未变，旧行会被复用。
 *
 * 这里把用户刚保存的「标题/歌手/专辑」按 stableKey（回退 mediaId）持久化，
 * [com.smartisan.music.playback.LocalAudioLibrary] 构建 MediaItem 时叠加到元数据上，
 * 使歌曲页 / 播放列表 / 搜索 / 收藏页 / 大播放页 / 小播放条**立即**显示新值，
 * 不再依赖媒体扫描器什么时候追上来（追上来后两边值本就一致，覆盖层无副作用）。
 */
internal object TagOverrideStore {

    private const val PREFS_NAME = "tag_overrides"
    private const val KEY_ENTRIES = "entries"

    data class Override(
        val title: String?,
        val artist: String?,
        val album: String?,
    ) {
        /**
         * [1.4.24·R28] null = 这个字段用户没改；空串 = 用户**显式清空**。
         * 两者必须区分，否则"清空专辑"会被上层当成"没改"而回填原值。
         */
        fun hasChanges(): Boolean = title != null || artist != null || album != null
    }

    private val lock = Any()
    private var cache: Map<String, Override>? = null

    /**
     * 保存一次编辑结果。stableKey 优先（跨重扫稳定），mediaId 作为回退键同时写入。
     */
    fun put(
        context: Context,
        mediaId: String?,
        stableKey: String?,
        title: String?,
        artist: String?,
        album: String?,
    ) {
        val override = Override(title, artist, album)
        synchronized(lock) {
            val current = loadLocked(context)
            val next = current.toMutableMap()
            stableKey?.trim()?.takeIf(String::isNotEmpty)?.let { next["sk:$it"] = override }
            mediaId?.trim()?.takeIf(String::isNotEmpty)?.let { next["id:$it"] = override }
            saveLocked(context, next)
        }
    }

    /**
     * 查找覆盖值：先按 stableKey，再按 mediaId。两者都空返回 null。
     */
    fun get(context: Context, stableKey: String?, mediaId: String?): Override? {
        synchronized(lock) {
            val entries = loadLocked(context)
            if (entries.isEmpty()) return null
            stableKey?.trim()?.takeIf(String::isNotEmpty)?.let { key ->
                entries["sk:$key"]?.let { return it }
            }
            mediaId?.trim()?.takeIf(String::isNotEmpty)?.let { key ->
                entries["id:$key"]?.let { return it }
            }
            return null
        }
    }

    private fun loadLocked(context: Context): Map<String, Override> {
        cache?.let { return it }
        val json =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, null)
        val parsed = mutableMapOf<String, Override>()
        if (!json.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    val item = obj.optJSONObject(key) ?: return@forEach
                    parsed[key] =
                        Override(
                            // [1.4.24·R28] 用 has() 而不是 isNotBlank()：空串是"显式清空"的
                            // 有效值，必须原样保留；只有 key 不存在才是"未修改"(null)。
                            title = if (item.has("t")) item.optString("t") else null,
                            artist = if (item.has("a")) item.optString("a") else null,
                            album = if (item.has("al")) item.optString("al") else null,
                        )
                }
            }
        }
        cache = parsed
        return parsed
    }

    private fun saveLocked(context: Context, entries: Map<String, Override>) {
        val obj = JSONObject()
        entries.forEach { (key, value) ->
            val item = JSONObject()
            value.title?.let { item.put("t", it) }
            value.artist?.let { item.put("a", it) }
            value.album?.let { item.put("al", it) }
            obj.put(key, item)
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, obj.toString())
            .apply()
        cache = entries
    }
}
