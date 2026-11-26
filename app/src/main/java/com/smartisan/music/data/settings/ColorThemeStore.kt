package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.ui.theme.ColorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.3.7 色彩主题存储：全局单例 + StateFlow，设置页写入后整棵 Compose 树立即重染，
 * 不用层层回调传参。读取与 ThemeSettingsStore 一样用轻量 SharedPreferences，
 * 保证冷启动首帧就能拿到上次选择（不闪默认彩色）。
 */
object ColorThemeStore {

    private const val PrefName = "color_theme_settings"
    private const val Key = "color_theme"

    private val _theme = MutableStateFlow(ColorTheme.Colorful)
    val theme: StateFlow<ColorTheme> = _theme

    /** Application.onCreate 里调一次，同步载入上次选择。 */
    fun initialize(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        _theme.value = ColorTheme.fromPreference(prefs.getString(Key, null))
    }

    fun current(): ColorTheme = _theme.value

    fun set(context: Context, theme: ColorTheme) {
        val app = context.applicationContext
        app.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(Key, theme.preferenceValue)
            .apply()
        _theme.value = theme
    }
}
