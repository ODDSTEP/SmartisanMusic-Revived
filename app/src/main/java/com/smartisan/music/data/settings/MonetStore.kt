package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [1.4.23·莫奈取色] 四个开关（回退到 v12 后重做 + v16 补全 + v17 M3）：
 *  - [enabled]：「莫奈取色」主开关（原本就有），负责 v12 定好的原有内容——
 *    底栏选中、顶栏方块、播放列表/随机播放按钮、搜索方块背景、列表按下高亮、
 *    大播放页背景晕染、顶栏/底栏表面色，以及 v16 新增的语义角色（分割线
 *    outlineVariant / 字母栏与副标题 onSurfaceVariant）。
 *  - [backgroundEnabled]：「背景莫奈化」子开关，只管白色内容背景铺莫奈渐变；
 *    **依赖 [enabled]**：只有「莫奈取色」开启后才能开启；「莫奈取色」关闭时自动关闭。
 *  - [separatorEnabled]：「分隔栏莫奈化」子开关，只管首字母/分组灰栏是否铺莫奈
 *    surfaceContainer 色；**依赖 [enabled]**，默认关（保持官方灰，真机手动开对比）。
 *  - [playbackTransition]：「播放栏渐变过渡」开关，控制小播放栏与底栏衔接方案：
 *    开 = 方案 A（底部纵向渐变自然收口到底栏色）；关 = 方案 B（容器统一，与底栏同底色）。
 *    **不依赖主开关**，是纯排版/配色方案切换，默认开（方案 A）；v17 在 M3 模式下也保留可调。
 *
 * [v21] M3 三色开关已删除：M3 样式改为 4 个独立固化色彩主题（选中即生效，色板写死），
 * 不再有 m3Enabled 开关与互斥逻辑；其余莫奈开关、逐元素开关保持不变。
 *
 * [v17·M3 三色]（历史）：
 *  - [searchGrayMode]：[v18] 用户实测拍板去掉「严格同灰/自适应清晰灰」，深色模式固定用
 *    「明亮统一灰」(#b8bbc0)。字段保留只为兼容旧 SP 值，initialize 恒置 0、setter 无效，
 *    调用点一律走 searchGrayFor() 单一亮灰，不再有三模式分支。
 *
 * [v18·分隔栏三档] 已删除（1.4.28）：用户实测三档肉眼无区别，且配色方案只保留
 * 「同色深浅」一种，separatorDepth 字段与 setter 一并移除，不留只写不读的死字段。
 *
 * [v18·逐元素取色] 新增：
 *  - [perElementEnabled]：实验开关。开=每个 UI 元素用它自己现有的 XML/PNG 原色独立提纯，
 *    分别反作用于各元素（各取各色）；与 M3/莫奈叠加，可随时关掉回退。默认关。
 *
 * 已删除 v13/v14 的「文字莫奈化」开关与全部语义角色取色 token，回退到 v12。
 */
object MonetStore {
    private const val PrefName = "monet_color_settings"
    private const val KeyEnabled = "monet_enabled"
    private const val KeyBackground = "monet_background_enabled"
    private const val KeySeparator = "monet_separator_enabled"
    private const val KeyPlaybackTransition = "monet_playback_transition"
    private const val KeyPerElement = "per_element_enabled"
    /** [1.4.29·R125] 空白区统一加深：开启后全 App 所有页面的竖纹空白区都用暗档。 */
    private const val KeyDeepBackground = "monet_deep_background"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _backgroundEnabled = MutableStateFlow(false)
    val backgroundEnabled: StateFlow<Boolean> = _backgroundEnabled

    private val _separatorEnabled = MutableStateFlow(false)
    val separatorEnabled: StateFlow<Boolean> = _separatorEnabled

    private val _playbackTransition = MutableStateFlow(true)
    val playbackTransition: StateFlow<Boolean> = _playbackTransition

    /** [v18] 搜索放大镜灰固定明亮统一灰。字段保留兼容旧 SP，恒为 0。 */
    private val _searchGrayMode = MutableStateFlow(0)
    val searchGrayMode: StateFlow<Int> = _searchGrayMode

    /** [v18·逐元素取色] 实验开关，默认关。 */
    private val _perElementEnabled = MutableStateFlow(false)
    val perElementEnabled: StateFlow<Boolean> = _perElementEnabled

    /**
     * [1.4.29·R125] 空白区统一加深，默认关。
     * 关闭：只有更多页与设置页的空白区用暗档，其余页面正常档。
     * 开启：全 App 所有页面的空白区统一用暗档。
     * 依赖「莫奈取色 + 背景莫奈化」，二者未开时置灰。
     */
    private val _deepBackgroundEnabled = MutableStateFlow(false)
    val deepBackgroundEnabled: StateFlow<Boolean> = _deepBackgroundEnabled

    /** Application.onCreate 里调一次，同步载入上次选择。 */
    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KeyEnabled, false)
        _backgroundEnabled.value = prefs.getBoolean(KeyBackground, false)
        _separatorEnabled.value = prefs.getBoolean(KeySeparator, false)
        _playbackTransition.value = prefs.getBoolean(KeyPlaybackTransition, true)
        // [v21] M3 开关已删除（改为 4 个固化主题），不再读取旧 SP 的 m3_enabled。
        // [v18] 搜索灰收敛：无论旧 SP 存的什么，一律固定明亮统一灰(0)。
        _searchGrayMode.value = 0
        _perElementEnabled.value = prefs.getBoolean(KeyPerElement, false)
        _deepBackgroundEnabled.value = prefs.getBoolean(KeyDeepBackground, false)
    }

    fun current(): Boolean = _enabled.value

    fun setEnabled(context: Context, enabled: Boolean) {
        persist(context, KeyEnabled, enabled)
        _enabled.value = enabled
        // 「莫奈取色」关闭 → 「背景莫奈化」自动关闭。
        // 分隔栏莫奈化是独立开关，不受主开关影响。
        if (!enabled) {
            persist(context, KeyBackground, false)
            _backgroundEnabled.value = false
        }
        logState("monet=$enabled")
    }

    fun setBackground(context: Context, enabled: Boolean) {
        persist(context, KeyBackground, enabled)
        _backgroundEnabled.value = enabled
        logState("background=$enabled")
    }

    fun setSeparator(context: Context, enabled: Boolean) {
        persist(context, KeySeparator, enabled)
        _separatorEnabled.value = enabled
        logState("separator=$enabled")
    }

    fun setPlaybackTransition(context: Context, enabled: Boolean) {
        persist(context, KeyPlaybackTransition, enabled)
        _playbackTransition.value = enabled
        logState("playbackTransition=$enabled")
    }

    /** [v18·逐元素取色] 开关。 */
    fun setPerElementEnabled(context: Context, enabled: Boolean) {
        persist(context, KeyPerElement, enabled)
        _perElementEnabled.value = enabled
        logState("perElement=$enabled")
    }

    /** [1.4.29·R125] 空白区统一加深开关。 */
    fun setDeepBackground(context: Context, enabled: Boolean) {
        persist(context, KeyDeepBackground, enabled)
        _deepBackgroundEnabled.value = enabled
        logState("deepBackground=$enabled")
    }

    /** #95：莫奈/M3/主题状态变更日志打点，纳入 I/State 便于真机验证。 */
    private fun logState(msg: String) {
        runCatching { AppLog.i("State", "monetState: $msg") }
    }

    private fun persist(context: Context, key: String, value: Boolean) {
        context.applicationContext
            .getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun persist(context: Context, key: String, value: Int) {
        context.applicationContext
            .getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, value)
            .apply()
    }
}
