package com.smartisan.music.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.4.8 状态栏样式存储（全局单例 + StateFlow，仿 [ColorThemeStore]）。
 *
 * - true（默认）= 沉浸：状态栏与顶栏保持同色，即 1.4.8 前的现状零改动；
 * - false = 状态栏按安卓走法变黑、图标自动转浅色。
 *
 * 用轻量 SharedPreferences 同步载入，保证冷启动首帧不闪；开关在设置页「外观」卡片内，
 * 由 MainActivity 在顶层画状态栏遮罩 + 切系统栏图标颜色实现，不切 decorFitsSystemWindows、
 * 不动任何页面的 WindowInsets padding（符合 22 号页「遮罩法」定稿）。
 */
object StatusBarStyleStore {

    private const val PrefName = "status_bar_style_settings"
    private const val KeyImmersive = "status_bar_immersive"
    private const val KeyHidden = "status_bar_hidden"

    private val _immersive = MutableStateFlow(true)
    val immersive: StateFlow<Boolean> = _immersive

    // [1.4.14·S08] 真正隐藏状态栏：开=全屏、状态栏消失；关=显示。独立于沉浸配色。
    private val _hidden = MutableStateFlow(false)
    val hidden: StateFlow<Boolean> = _hidden

    /** Application.onCreate 里调一次，同步载入上次选择（默认沉浸=同色、不隐藏）。 */
    fun initialize(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        _immersive.value = prefs.getBoolean(KeyImmersive, true)
        _hidden.value = prefs.getBoolean(KeyHidden, false)
    }

    fun current(): Boolean = _immersive.value

    fun set(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyImmersive, enabled)
            .apply()
        _immersive.value = enabled
    }

    fun setHidden(context: Context, hidden: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyHidden, hidden)
            .apply()
        _hidden.value = hidden
    }
}
