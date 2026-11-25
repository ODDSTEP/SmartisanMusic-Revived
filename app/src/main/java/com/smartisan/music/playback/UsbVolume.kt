package com.smartisan.music.playback

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

/**
 * USB 独占期间的音量中枢（v22：数字音量 / DAC 硬件音量 / 自动）。
 *
 * 独占绕开了 AudioFlinger，系统音量条默认对这条通道无效，所以把系统媒体音量的**档位**
 * 当作音量输入，再按 Salt Player 的口径映射：
 *
 *   档位比例 f = 当前档 / 最大档
 *   目标 dB   = -60 × (1 - f)          —— 等 dB 线性，33/150 档正好是 -46.8 dB
 *   数字增益  = 10^(dB / 20)
 *
 * 数字模式把该增益交给输出端逐样本衰减；硬件模式则通过 UAC Feature Unit 把同一个 dB
 * 写进 DAC（[UsbDac.Sink.setHardwareVolumeDb]），数字域保持满档，因此**仍然是 bit-perfect**。
 *
 * [UsbVolumeMode.AUTO] 会在会话建立、开声之前探测一次硬件音量：能写就优先硬件，
 * 写不进去就退回数字。零损失开启时，只要硬件可用就强制硬件——否则"零损失"与"能调音量"
 * 无法同时成立。
 */
object UsbVolume {

    private const val TAG = "UsbVolume"

    /** Salt 口径：0 档 = -60 dB，满档 = 0 dB。0 档本身按下述规则直接静音。 */
    const val MinDb = -60f

    /** 硬件音量在 0 档时写入的下限（UAC raw 通常 -127.5 dB 就等同静音）。 */
    private const val HardwareMuteDb = -127f

    /**
     * 软件音量增益 = 数字模式下由档位换算的线性增益（0..1）。
     * 独占期间由 [syncSystemVolume] 随系统音量变化实时更新。
     */
    @Volatile
    var systemVolumeFraction: Float = 1f
        private set

    /**
     * 播放器自身音量（ExoPlayer 经 AudioSink.setVolume 传入，区间 [0,1]）。
     * 与数字增益相乘得到最终增益，二者任一变化都会即时推送到 USB 输出端。
     */
    @Volatile
    var playerVolume: Float = 1f

    private val _hardwareSupported = MutableStateFlow(false)

    /** 当前会话的 DAC 是否支持（且验证过可写）硬件音量。 */
    var hardwareSupported: Boolean = false
        private set(value) {
            field = value
            _hardwareSupported.value = value
        }

    /** UI 观察用：硬件音量能力（会话建立/关闭时变化）。 */
    val hardwareSupportedFlow: StateFlow<Boolean> = _hardwareSupported

    private val _hardwareActive = MutableStateFlow(false)

    /** 当前实际生效的是否为硬件音量（UI 胶囊/设置页展示用）。 */
    var hardwareActive: Boolean = false
        private set(value) {
            field = value
            _hardwareActive.value = value
        }

    val hardwareActiveFlow: StateFlow<Boolean> = _hardwareActive

    private val _sessionActive = MutableStateFlow(false)

    /** UI 观察用：当前是否有 UAC 独占会话（播放页音量胶囊据此显示）。 */
    val sessionActiveFlow: StateFlow<Boolean> = _sessionActive

    private val _currentDb = MutableStateFlow(0f)

    /** 当前目标音量（dB，-60..0），UI 展示用。 */
    var currentDb: Float = 0f
        private set(value) {
            field = value
            _currentDb.value = value
        }

    val currentDbFlow: StateFlow<Float> = _currentDb

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * [1.4.21·P2] 平滑接力器。由 PlaybackService 注入（持有 serviceScope）。
     * 为 null 时退化为旧的「瞬间施加」，保证无服务场景下音量仍可用。
     */
    @Volatile
    var handoff: SmoothVolumeHandoff? = null

    /** 在 App 启动时调用一次：拿到 AudioManager 并读一次当前系统媒体音量 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (audioManager != null) return
        synchronized(this) {
            if (audioManager != null) return
            audioManager = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            syncSystemVolume(context.applicationContext)
            AppLog.i(
                TAG,
                "init, step=${(systemVolumeFraction * 100).toInt()}% db=${"%.1f".format(currentDb)}",
            )
        }
    }

    /** 档位比例 → dB（-60..0）。 */
    fun fractionToDb(fraction: Float): Float =
        (MinDb * (1f - fraction.coerceIn(0f, 1f))).coerceIn(MinDb, 0f)

    /** dB → 线性增益。0 档由调用方单独判静音，这里只处理 -60..0。 */
    fun dbToGain(db: Float): Float {
        if (db <= MinDb) return 0f
        if (db >= 0f) return 1f
        return 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 当前实际生效模式：把 [UsbVolumeModeStore] 的用户选择 + 硬件可用性 + 零损失联动
     * 收敛成 DIGITAL / HARDWARE 两个真实分支。
     */
    fun effectiveMode(): UsbVolumeMode {
        // 零损失优先：能硬件就硬件，数字域必须满档才能逐字节直传。
        if (UsbExclusiveSinkRef.zeroLossEnabled && hardwareSupported) return UsbVolumeMode.HARDWARE
        val chosen = UsbVolumeModeStore.current()
        return when (chosen) {
            UsbVolumeMode.AUTO -> if (hardwareSupported) UsbVolumeMode.HARDWARE else UsbVolumeMode.DIGITAL
            UsbVolumeMode.HARDWARE -> if (hardwareSupported) UsbVolumeMode.HARDWARE else UsbVolumeMode.DIGITAL
            UsbVolumeMode.DIGITAL -> UsbVolumeMode.DIGITAL
        }
    }

    /**
     * 读取一次系统媒体音量（STREAM_MUSIC）并分发到当前音量模式。
     * 供初始化、独占建立、以及音量变化广播时调用。
     */
    @Volatile
    private var lastLoggedVolume = -1

    fun syncSystemVolume(context: Context) {
        val am = audioManager
            ?: (context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            var cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            // [1.4.24·独占音量不实时] 拉到 0 档（尤其大播放页拖到 0/1 格）时系统可能用
            // ADJUST_MUTE 只置静音标志、不改 STREAM_MUSIC 数值，此时 getStreamVolume 仍是旧值
            // → 独占增益不更新，"降到0还有声/隔一首才静音"。这里显式读静音态并归零。
            val muted =
                runCatching { @Suppress("DEPRECATION") am.isStreamMute(AudioManager.STREAM_MUSIC) }
                    .getOrDefault(false)
            if (muted) cur = 0
            val frac = cur.toFloat() / max.toFloat()
            // 同一次按键会被广播 + 监听器回调打进来 3~4 次，重复行只会淹没真正的日志
            if (lastLoggedVolume != cur) {
                lastLoggedVolume = cur
                AppLog.d(TAG, "system volume -> $cur/$max (${"%.1f".format(fractionToDb(frac))} dB)")
            }
            applyFraction(frac, immediate = frac <= 0f)
        }
    }

    /**
     * [v10·0 格必静音] 由音量写入口（setMusicStreamVolumeFraction）在写完系统音量后**直接**调用，
     * 把目标比例立即落到 systemVolumeFraction 并推送到输出端，不依赖 VOLUME_CHANGED 广播回环。
     */
    fun applyFraction(fraction: Float, immediate: Boolean) {
        val frac = fraction.coerceIn(0f, 1f)
        systemVolumeFraction = frac
        lastLoggedVolume = -1 // 让下一次 syncSystemVolume 的日志如实记录真实系统值
        val db = fractionToDb(frac)
        currentDb = db
        val mode = effectiveMode()
        hardwareActive = mode == UsbVolumeMode.HARDWARE
        val h = handoff
        if (mode == UsbVolumeMode.HARDWARE) {
            // 硬件模式：音量写 DAC，数字域保持满档（playerVolume 若被 App 单独调小仍要乘进去）。
            val target = if (frac <= 0f) HardwareMuteDb else db
            if (h != null) {
                h.requestHardware(target, immediate = immediate)
                // 数字域必须回到满档：新会话 resetForNewSession 会把 appliedGain 置 0，
                // 如果只写硬件不推数字，等于 0 × 硬件 = 静音（首帧没声音的典型原因）。
                if (h.appliedGain < playerVolume - 0.001f) {
                    h.request(playerVolume, immediate = immediate)
                }
            } else {
                runCatching { sink()?.setHardwareVolumeDb(target) }
                runCatching { sink()?.setSoftwareGain(playerVolume) }
            }
        } else {
            val g = (playerVolume * dbToGain(db)).coerceIn(0f, 1f)
            if (h != null) {
                h.request(g, immediate = immediate || g <= 0f)
            } else {
                runCatching { sink()?.setSoftwareGain(g) }
            }
        }
    }

    /**
     * 数字模式的实际数字增益；硬件模式恒 1f，保证零损失的 `softwareGain() >= 1` 判定成立。
     */
    fun softwareGain(): Float {
        if (effectiveMode() == UsbVolumeMode.HARDWARE) return 1f
        val f = systemVolumeFraction
        return if (f <= 0f) 0f else dbToGain(fractionToDb(f))
    }

    /** 当前软件音量百分比（设置页/日志展示用） */
    fun softwarePercent(): Int =
        ((systemVolumeFraction * 100f) + 0.5f).toInt().coerceIn(0, 100)

    /**
     * 会话建立：开声之前调用。同步探测一次硬件音量（此时 DAC 还没收到 PCM，探测里的
     * 写中间值/写回原值不会被人耳听到），然后按当前模式初始化输出端。
     */
    fun onSessionReady(sink: UsbDac.Sink) {
        hardwareSupported = false
        hardwareActive = false
        _sessionActive.value = true
        val started = SystemClock.elapsedRealtime()
        val ok = runCatching { sink.probeHardwareVolume() }.getOrDefault(false)
        hardwareSupported = ok
        AppLog.i(
            TAG,
            "session ready hwVol=$ok probeMs=${SystemClock.elapsedRealtime() - started} " +
                "mode=${effectiveMode()} zeroLoss=${UsbExclusiveSinkRef.zeroLossEnabled}",
        )
        // 新会话的 DSP 侧可能是上一首残留：先把抖动开关同步过去。
        runCatching { sink.setDither(UsbVolumeModeStore.ditherEnabled()) }
        val ctx = appContext
        if (ctx != null) syncSystemVolume(ctx) else applyFraction(systemVolumeFraction, immediate = true)
    }

    /** 会话关闭：清掉硬件能力，避免 UI 继续显示"支持硬件音量"。 */
    fun onSessionClosed() {
        hardwareSupported = false
        hardwareActive = false
        _sessionActive.value = false
        AppLog.i(TAG, "session closed, hwVol reset")
    }

    /** 用户切换音量模式：按新模式重新施加一次当前音量。 */
    fun onModeChanged() {
        hardwareActive = effectiveMode() == UsbVolumeMode.HARDWARE
        val db = currentDb
        val h = handoff
        val targetHw = if (systemVolumeFraction <= 0f) HardwareMuteDb else db
        if (hardwareActive) {
            if (h != null) {
                h.switchToHardware(targetHw, playerVolume)
            } else {
                runCatching { sink()?.setHardwareVolumeDb(targetHw) }
                runCatching { sink()?.setSoftwareGain(playerVolume) }
            }
        } else {
            val g = (playerVolume * dbToGain(db)).coerceIn(0f, 1f)
            if (h != null) {
                h.switchToDigital(g)
            } else {
                runCatching { sink()?.setHardwareVolumeDb(0f) }
                runCatching { sink()?.setSoftwareGain(g) }
            }
        }
        AppLog.i(TAG, "mode changed -> $hardwareActive (db=${"%.1f".format(db)})")
    }

    /** 硬件写入失败（回读不符）：降级到数字，绝不继续假装硬件音量已生效。 */
    fun onHardwareWriteFailed(reason: String) {
        if (!hardwareSupported) return
        hardwareSupported = false
        hardwareActive = false
        AppLog.w(TAG, "hw volume write failed -> fallback digital: $reason")
        val g = (playerVolume * dbToGain(currentDb)).coerceIn(0f, 1f)
        val h = handoff
        if (h != null) h.switchToDigital(g) else runCatching { sink()?.setSoftwareGain(g) }
    }

    private fun sink(): UsbDac.Sink? = UsbDacSinkRef.current
}

/** 当前活跃的 UAC sink 引用，供进度/诊断等组件读取（1.2.6 起由本文件持有）。 */
object UsbDacSinkRef {
    @Volatile
    var current: UsbDac.Sink? = null
}
