package com.smartisan.music.data.settings

import android.content.Context

/**
 * [v20] 设置页卡片展开/收起状态持久化。
 *
 * rememberSaveable 只在配置变更/进程临时恢复时保留，页面切走再进来（composable 销毁）
 * 会丢失，用户实测"没记住"。这里改用 SharedPreferences 按 section key 落盘，
 * 下次打开设置页仍保持上次的展开/收起状态。
 */
object SettingsSectionExpandStore {
    private const val PrefName = "settings_section_expand"
    private const val KeyPrefix = "section_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)

    /** @param key 稳定的 section 标识（用标题字符串资源名） @param default 默认展开态 */
    fun get(context: Context, key: String, default: Boolean): Boolean =
        prefs(context).getBoolean(KeyPrefix + key, default)

    /** 未落盘时返回 null，用于区分「用户从没点过」与「用户明确收起了」，好让默认值仍能按场景生效。 */
    fun getOrNull(context: Context, key: String): Boolean? {
        val p = prefs(context)
        val k = KeyPrefix + key
        return if (p.contains(k)) p.getBoolean(k, false) else null
    }

    fun set(context: Context, key: String, expanded: Boolean) {
        prefs(context).edit().putBoolean(KeyPrefix + key, expanded).apply()
    }
}
