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

    /**
     * @return true=开关已按请求落库；false=被 A3 安全闸门拦截（未落库）。
     *
     * [1.4.32·A3/R153-补④/R160-3] 开启零损失前检查硬件音量是否"回读验证过"。
     * 只在「已有活跃 UAC 会话 + 探测到控件但只到 DETECTED（假阳性）」时拦截——这正是
     * TEARS DSP 那类"probe ok 但写不进、DAC 满档"的伤耳场景（R153/R156）：
     * 零损失 + 假可用硬件音量 + FLOAT 源 = 满档白噪。UNAVAILABLE（没控件/没插 DAC）时
     * 不拦——用户可能还没插设备就想先设好开关，且播放时 effectiveMode 仍会兜底拒绝直传。
     */
    fun set(context: Context, enabled: Boolean): Boolean {
        val old = _enabled.value
        // [1.4.33·验收第1点] 安全闸门收紧：UNAVAILABLE（探测不到/未插稳/假阳性 DAC）
        // 与 DETECTED（只探测到控件、未写回读）都不得凭空开零损失——两者都无法证明
        // "App 能把 DAC 音量压下来"，零损失 + FLOAT 源 = 满档白噪伤耳链（R153/R156）。
        // 实机 TEARS DSP：hwVer=UNAVAILABLE 却能开零损失（日志 23:42:50），即此漏洞。
        // 唯一放行凭证仍是 L3 音频域闭环试音通过——好 DAC（海贝能调、只是 App 探测不到）
        // 可走"硬件音量验证"入口试音自救，这是用户第1点拍板的"保守拦 + 开放 L3 入口"。
        // 未 L3 验证则一律拦住并给明确提示（zeroLossHardwareGateReason）。
        val unverified = when (UsbVolume.hardwareVerification) {
            UsbVolume.HardwareVerification.DETECTED,
            UsbVolume.HardwareVerification.UNAVAILABLE -> true
            UsbVolume.HardwareVerification.VERIFIED -> false
        }
        if (enabled && UsbVolume.sessionActiveFlow.value && unverified &&
            !com.smartisan.music.playback.UsbVolumeProbe.isL3Verified() // [A4] L3 音频域闭环通过则放行
        ) {
            val reason = UsbVolume.zeroLossHardwareGateReason()
            AppLog.state("SET", "zeroLossRejected", "UsbZeroLossStore.set", mapOf("gate" to reason))
            AppLog.w("State", "zeroLoss 开启被安全闸门拒绝：$reason")
            return false
        }
        context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit().putBoolean(Key, enabled).apply()
        _enabled.value = enabled
        // 即时写进 sink 引用，handleBuffer 每次读取，开关立刻生效（无需等 settings flow）。
        UsbExclusiveSinkRef.zeroLossEnabled = enabled
        // [v22·音量] 零损失开启且 DAC 支持硬件音量时，音量会自动切到硬件模式
        // （数字域保持满档，逐字节直传才成立）；关掉时按用户选择恢复。
        UsbVolume.onModeChanged()
        // [R186] 关键开关必须进 SET 帧。
        AppLog.state(
            "SET", "zeroLoss", "UsbZeroLossStore.set",
            mapOf("old" to old, "new" to enabled, "hwVer" to UsbVolume.hardwareVerification.name),
        )
        AppLog.i("State", "usbZeroLoss: $old -> $enabled")
        return true
    }
}
