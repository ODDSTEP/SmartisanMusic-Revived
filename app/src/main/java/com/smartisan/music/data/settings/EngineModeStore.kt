package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.4.11 · S12 三包收敛为单包：播放引擎运行时模式。
 *
 * 三套真实不同的播放引擎现在都编进同一个 official APK，用户可在设置里选择：
 *  - OFFICIAL（默认）：双路 ExoPlayer 引擎（常见格式系统直读、冷门格式/独占自解码，
 *    表三 11 项功能全保）。即原 B 正式版。
 *  - DIRECT：系统 MediaPlayer 直出（点开即播、零缓存、不碰 PCM；无 USB 独占 bit-perfect /
 *    自研 DSP / 64bit 浮点 WAV）。即原 A 测试版。
 *  - STREAM：冷门格式 ffmpeg 流式 PCM 不落盘（seek 竞态由逐阶段日志暴露）。即原 C 测试版。
 *
 * 引擎是播放器的地基，运行中切换会销毁重建 Player 并中断当前播放——因此切换后**提示
 * 重启 App 生效**，不假装无缝切换（1.3.7 教训：切内核曾整片失效）。
 * 用轻量 SharedPreferences 同步载入，冷启动首帧即定引擎；Application.onCreate 调 initialize。
 */
object EngineModeStore {

    enum class Mode(
        val id: String,
        val label: String,
        val summary: String,
        val labelRes: Int,
        val summaryRes: Int,
    ) {
        OFFICIAL(
            "official", "双路正式版（推荐）",
            "常见格式直读零缓存，冷门格式/USB独占自解码，功能最全",
            R.string.engine_official, R.string.engine_official_summary,
        ),
        DIRECT(
            "direct", "系统直读",
            "真·系统MediaPlayer出声，零缓存；无USB独占/DSP/浮点WAV",
            R.string.engine_direct, R.string.engine_direct_summary,
        ),
        STREAM(
            "stream", "流式解码",
            "冷门格式ffmpeg边解边喂PCM不落盘；seek竞态待实机",
            R.string.engine_stream, R.string.engine_stream_summary,
        ),
    }

    private const val PrefName = "engine_mode_settings"
    private const val KeyMode = "engine_mode"

    /** [1.4.25·R67a] 切引擎恢复后是否自动播放，默认 false（暂停待用户点播）。 */
    private const val KeyRestoreAutoPlay = "restore_auto_play"

    private val _mode = MutableStateFlow(Mode.OFFICIAL)
    val mode: StateFlow<Mode> = _mode

    /** [1.4.25·R67a] 切引擎恢复上一首后自动播；默认 false=暂停。 */
    private val _restoreAutoPlay = MutableStateFlow(false)
    val restoreAutoPlay: StateFlow<Boolean> = _restoreAutoPlay

    fun initialize(context: Context) {
        val id = prefs(context).getString(KeyMode, Mode.OFFICIAL.id) ?: Mode.OFFICIAL.id
        _mode.value = Mode.entries.firstOrNull { it.id == id } ?: Mode.OFFICIAL
        _restoreAutoPlay.value = prefs(context).getBoolean(KeyRestoreAutoPlay, false)
    }

    fun current(): Mode = _mode.value

    fun set(context: Context, mode: Mode) {
        // [1.4.29·R127] 必须 commit：切引擎后播放内核重建，1.4.28 因跨线程崩溃在写入后
        // 20ms 就挂，apply() 的异步落盘没完成 → 重启读回旧引擎，用户看到"切了没生效"。
        // 切引擎是低频操作，几十毫秒同步写盘远好过设置丢失。
        prefs(context).edit().putString(KeyMode, mode.id).commit()
        _mode.value = mode
    }

    /** [1.4.25·R67a] 切引擎恢复后自动播放开关。 */
    fun setRestoreAutoPlay(context: Context, enabled: Boolean) {
        // [1.4.29·R127] 同 set()：与切引擎同一流程，崩溃后必须保留用户选择。
        prefs(context).edit().putBoolean(KeyRestoreAutoPlay, enabled).commit()
        _restoreAutoPlay.value = enabled
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
}
