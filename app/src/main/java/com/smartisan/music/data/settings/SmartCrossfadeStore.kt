package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [1.4.19·S07] 智能过渡（自动混音 / crossfade）设置。
 *
 * 语音转写术语：相邻歌曲在切歌时做音量淡出 → 淡入包络，让两首歌听感上平滑衔接。
 * 单 Player 无法同时解码两首歌做真双曲叠加，这里用「切歌前淡出当前曲、切歌后淡入下一曲」
 * 的音量包络实现（见 SmartCrossfadeController），用户能明显感知，且不碰 DSP 核心。
 *
 * - enabled 默认 **false**：不改任何现有切歌行为，开了才生效（机主拍板要求）。
 * - durationSec 默认 6 秒，可选 3 / 6 / 10 秒，控制淡出与淡入各自的时长基准。
 *
 * 用轻量 SharedPreferences 同步载入（与 UsbEngineStore 一致），冷启动首帧即定；
 * 独立 object，不并入 PlaybackSettingsStore，避免动到 team-lead 正在改的文件区域。
 */
object SmartCrossfadeStore {
    private const val PrefName = "smart_crossfade_settings"
    private const val KeyEnabled = "crossfade_enabled"
    private const val KeyDurationSec = "crossfade_duration_sec"

    /** 可选的过渡时长（秒）。 */
    val supportedDurationSecs = listOf(3, 6, 10)
    const val DEFAULT_DURATION_SEC = 6

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _durationSec = MutableStateFlow(DEFAULT_DURATION_SEC)
    val durationSec: StateFlow<Int> = _durationSec

    /** [1.4.23] 允许的自定义时长范围（秒）。 */
    const val MIN_DURATION_SEC = 1
    const val MAX_DURATION_SEC = 60

    fun initialize(context: Context) {
        val prefs = prefs(context)
        _enabled.value = prefs.getBoolean(KeyEnabled, false)
        val stored = prefs.getInt(KeyDurationSec, DEFAULT_DURATION_SEC)
        _durationSec.value = if (stored in MIN_DURATION_SEC..MAX_DURATION_SEC) stored else DEFAULT_DURATION_SEC
    }

    fun currentEnabled(): Boolean = _enabled.value

    fun currentDurationSec(): Int = _durationSec.value

    fun setEnabled(context: Context, enabled: Boolean) {
        val old = _enabled.value
        prefs(context).edit().putBoolean(KeyEnabled, enabled).apply()
        _enabled.value = enabled
        // [1.4.30·R140/R186] 与零损失/引擎/音量模式四个开关一致：状态变化必须落一帧。
        AppLog.state(
            "SET",
            "crossfade",
            "SmartCrossfadeStore.setEnabled",
            mapOf("old" to old, "new" to enabled, "durationSec" to _durationSec.value),
        )
    }

    fun setDurationSec(context: Context, durationSec: Int) {
        setCustomDurationSec(context, durationSec)
    }

    /** [1.4.23] 自定义时长：夹到 1~60 秒。 */
    fun setCustomDurationSec(context: Context, durationSec: Int) {
        // 下面仍会写库；这里先记一帧原始意图，方便对上"用户选了几秒"与"实际用了几秒"。
        val normalized = durationSec.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
        val old = _durationSec.value
        prefs(context).edit().putInt(KeyDurationSec, normalized).apply()
        _durationSec.value = normalized
        AppLog.state(
            "SET",
            "crossfadeDuration",
            "SmartCrossfadeStore.setCustomDurationSec",
            mapOf("old" to old, "new" to normalized, "enabled" to _enabled.value),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
}
