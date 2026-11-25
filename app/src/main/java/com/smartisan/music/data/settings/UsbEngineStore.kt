package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [1.4.19·S03] USB 独占引擎选择。
 *
 * - false（默认）= 旧自研引擎（usbdac.cpp / UsbDac.Sink），多年实机验证、稳定；
 * - true = 新开源引擎（TrueSample usbaudio，Apache-2.0），libusb 用户态 + 完整 UAC
 *   描述符解析 + 反馈端点 + 逐芯片模式协商，架构对齐 Salt。
 *
 * 默认关闭是保守策略：新引擎需用户实机验证；任何打开失败/不兼容都会在运行层
 * 自动回退到旧引擎，不会出现"没声音"。开关独立于播放引擎三选一（EngineModeStore），
 * 只在 OFFICIAL 引擎下才有意义（DIRECT 无独占）。
 */
object UsbEngineStore {
    private const val PrefName = "usb_engine_settings"
    private const val KeyNew = "use_new_usb_engine"

    private val _useNew = MutableStateFlow(false)
    val useNew: StateFlow<Boolean> = _useNew

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        _useNew.value = prefs.getBoolean(KeyNew, false)
    }

    fun current(): Boolean = _useNew.value

    fun set(context: Context, enabled: Boolean) {
        val old = _useNew.value
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit().putBoolean(KeyNew, enabled).apply()
        _useNew.value = enabled
        // #94：补状态变更日志（旧值→新值），纳入 State 打点，便于真机从日志确认开关真实状态。
        AppLog.i("State", "usbEngine: useNew $old -> $enabled")
    }
}
