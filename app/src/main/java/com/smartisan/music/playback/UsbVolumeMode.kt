package com.smartisan.music.playback

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB 独占下的音量模式（口径对齐 Salt Player）：
 *
 * - [AUTO]     自动：播放开始后检测 DAC 硬件音量，能操作硬件就优先硬件，否则转数字；
 * - [DIGITAL]  数字音量：把音量档位映射为 -60~0 dB，再换算线性增益在 PCM 上衰减（会破坏 bit-perfect）；
 * - [HARDWARE] DAC 硬件音量：通过 UAC Feature Unit 的 SET_CUR 改 DAC 内部音量，**音频字节不动**。
 *
 * 零损失开启时，只要 DAC 支持硬件音量就强制走 [HARDWARE]，这样音量可调且仍然逐字节直传。
 */
enum class UsbVolumeMode(val key: String) {
    AUTO("auto"),
    DIGITAL("digital"),
    HARDWARE("hardware"),
    ;

    companion object {
        fun fromKey(key: String?): UsbVolumeMode =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * 音量模式 + TPDF 抖动开关的持久化。
 *
 * 独立于 [com.smartisan.music.data.settings.PlaybackSettingsStore]（DataStore），
 * 原因是这套设置要在 USB 会话建立的原生回调线程里同步读取，SharedPreferences 更直接。
 */
object UsbVolumeModeStore {

    private const val TAG = "UsbVolumeMode"
    private const val PrefName = "usb_volume_settings"
    private const val KeyMode = "mode"
    private const val KeyDither = "tpdf_dither"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private val _mode = MutableStateFlow(UsbVolumeMode.AUTO)
    val mode: StateFlow<UsbVolumeMode> = _mode

    private val _dither = MutableStateFlow(false)
    val dither: StateFlow<Boolean> = _dither

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            prefs = p
            _mode.value = UsbVolumeMode.fromKey(p.getString(KeyMode, null))
            _dither.value = p.getBoolean(KeyDither, false)
            AppLog.i(TAG, "initialize mode=${_mode.value} dither=${_dither.value}")
        }
    }

    fun current(): UsbVolumeMode = _mode.value

    fun ditherEnabled(): Boolean = _dither.value

    fun set(context: Context, mode: UsbVolumeMode) {
        if (prefs == null) initialize(context)
        val old = _mode.value
        if (old == mode) return
        prefs?.edit()?.putString(KeyMode, mode.key)?.apply()
        _mode.value = mode
        // [R186] 音量模式进 SET 帧
        AppLog.state("SET", "volumeMode", "UsbVolumeModeStore.set", mapOf("old" to old, "new" to mode))
        AppLog.i(TAG, "mode $old -> $mode")
        UsbVolume.onModeChanged()
    }

    fun setDither(context: Context, enabled: Boolean) {
        if (prefs == null) initialize(context)
        if (_dither.value == enabled) return
        prefs?.edit()?.putBoolean(KeyDither, enabled)?.apply()
        _dither.value = enabled
        AppLog.state("SET", "dither", "UsbVolumeModeStore.setDither", mapOf("new" to enabled))
        AppLog.i(TAG, "tpdf dither $enabled")
        UsbDacSinkRef.current?.setDither(enabled)
    }
}
