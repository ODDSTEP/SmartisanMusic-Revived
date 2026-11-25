package com.smartisan.music.data.settings

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.playback.UsbExclusiveSinkRef
import com.smartisan.music.playback.UsbVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [v20·USB 零损失（Bit-perfect）] 开关。
 *
 * 开启后：USB 独占通道在「源编码==目标编码 且 无 DSP 且 无爆豆/打碟叠加 且 软件音量满档」
 * 时逐字节直传 PCM，不经过 float 归一化往返，消除 ±1 LSB 量化损失。
 * 与之冲突的功能（EQ/压限/混响/响度归一化/ReplayGain/爆豆音/打碟音/软件音量）在设置页置灰，
 * 运行时也以本开关为真值强制旁路，保证"零损失"名副其实。
 *
 * 独立于 UsbEngineStore；只有 USB 独占输出生效时有意义。默认关。
 */
object UsbZeroLossStore {
    private const val PrefName = "usb_zero_loss_settings"
    private const val Key = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(Key, false)
        UsbExclusiveSinkRef.zeroLossEnabled = _enabled.value
    }

    fun current(): Boolean = _enabled.value

    fun set(context: Context, enabled: Boolean) {
        val old = _enabled.value
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit().putBoolean(Key, enabled).apply()
        _enabled.value = enabled
        // 即时写进 sink 引用，handleBuffer 每次读取，开关立刻生效（无需等 settings flow）。
        UsbExclusiveSinkRef.zeroLossEnabled = enabled
        // [v22·音量] 零损失开启且 DAC 支持硬件音量时，音量会自动切到硬件模式
        // （数字域保持满档，逐字节直传才成立）；关掉时按用户选择恢复。
        UsbVolume.onModeChanged()
        AppLog.i("State", "usbZeroLoss: $old -> $enabled")
    }
}
