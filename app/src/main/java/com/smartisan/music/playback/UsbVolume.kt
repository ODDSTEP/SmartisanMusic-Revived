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

    /**
     * [R169] 硬件音量能力三态。`probeMs=0`（新引擎仅凭描述符判定存在控件、没做写入回读）
     * 不得直接显示成"支持/可用"，否则用户看到"硬件可用"但实际调不动（R156 的假阳性）。
     */
    enum class HardwareVerification {
        /** 没有可用的硬件音量控件 */
        UNAVAILABLE,
        /** 描述符/端点里检测到音量控件，但没有做过写入回读验证 */
        DETECTED,
        /** 写入两个电平并回读跟随（旧引擎 write-verify 通过） */
        VERIFIED,
    }

    private val _hardwareVerification =
        MutableStateFlow(HardwareVerification.UNAVAILABLE)

    /**
     * [1.4.30·R170] 三态对外可观察。
     *
     * 设置页/胶囊必须能区分「检测到」和「验证过」：旧实现只暴露 [hardwareSupported] 一个布尔，
     * `probeMs=0`（只读描述符、没做过写回读）也被当成 `true`，UI 就直接写"硬件音量可用"，
     * 而用户实际调不动——这正是 R156 说的"假可用"。现在 UI 按三态给不同文案，
     * 不把"检测到"说成"可用"。
     */
    val hardwareVerificationFlow: StateFlow<HardwareVerification> = _hardwareVerification

    @Volatile
    var hardwareVerification: HardwareVerification = HardwareVerification.UNAVAILABLE
        private set(value) {
            field = value
            _hardwareVerification.value = value
        }

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
        // [1.4.32·A3/R153-补④/R160-3] 零损失优先，但"硬件可用"必须是**回读验证过**（VERIFIED）。
        // 旧实现只看 hardwareSupported：probeMs<=1 的假阳性 DAC（只读描述符、没做写回读，
        // 例如 TEARS DSP）也会被置 true，于是零损失生效 → 数字域恒 1 → FLOAT 位模式满档直传
        // → 爆音伤耳（R153/R156）。DETECTED（仅探测到控件）绝不能支撑零损失直传。
        // 注意 R161 的教训：假阴性（旧引擎描述符没解析出 playback FU 而超时）不能被"一刀切"
        // 永久拒绝——那种情况应通过修探测（v24 playbackUnit 优先）拿到 VERIFIED，而不是放宽
        // 这里的判据。所以这里只允许 VERIFIED，理由写进日志便于复盘。
        if (UsbExclusiveSinkRef.zeroLossEnabled && hardwareVerifiedForZeroLoss()) {
            return UsbVolumeMode.HARDWARE
        }
        val chosen = UsbVolumeModeStore.current()
        return when (chosen) {
            UsbVolumeMode.AUTO -> if (hardwareSupported) UsbVolumeMode.HARDWARE else UsbVolumeMode.DIGITAL
            UsbVolumeMode.HARDWARE -> if (hardwareSupported) UsbVolumeMode.HARDWARE else UsbVolumeMode.DIGITAL
            UsbVolumeMode.DIGITAL -> UsbVolumeMode.DIGITAL
        }
    }

    /**
     * [1.4.32·A3] 零损失直传的硬件音量前置条件：必须"写入回读验证过"（VERIFIED）。
     *
     * 为什么不用 hardwareSupported：它在 probeMs<=1（只读描述符/端点、没做双电平写回读）时
     * 也为 true（[onSessionReady] 的 DETECTED 分支），正是 R156 的"假可用"——用户看到硬件音量
     * 可用，实际调不动、DAC 一直满档。零损失 + 假可用 + FLOAT 源 = R153 的伤耳爆音链。
     * 只有 VERIFIED（写两个电平并回读跟随）才能证明"App 真能把音量压下来"，此时零损失才有意义。
     */
    fun hardwareVerifiedForZeroLoss(): Boolean {
        if (!hardwareSupported) return false
        // [1.4.32·A4/R161②] 两条可信路径：
        //  ① L2 写回读验证通过（常规 VERIFIED）；
        //  ② L2 只到 DETECTED（假阳性嫌疑），但用户做了音频域闭环 L3 试音并确认"音量真能变"
        //     ——这是台账 R161② 说的"假阳性只能靠音频域闭环兜"的落地。
        return hardwareVerification == HardwareVerification.VERIFIED ||
            (hardwareVerification == HardwareVerification.DETECTED && UsbVolumeProbe.isL3Verified())
    }

    /** 拒绝零损失的硬件侧原因（诊断/开关拦截提示用）。 */
    fun zeroLossHardwareGateReason(): String = when {
        hardwareVerification == HardwareVerification.VERIFIED -> "OK(L2 write-verify)"
        hardwareVerification == HardwareVerification.DETECTED && UsbVolumeProbe.isL3Verified() ->
            "OK(L3 audio-loopback confirmed)"
        hardwareVerification == HardwareVerification.DETECTED ->
            "HW_UNVERIFIED(仅探测到控件，未做写回读验证；可用『硬件音量验证』试音确认后再开)"
        hardwareVerification == HardwareVerification.UNAVAILABLE && hardwareSupported ->
            "HW_PROBE_TIMEOUT(探测超时/描述符未解析，见 R161；请等探测修复后再开)"
        else -> "HW_UNAVAILABLE(无硬件音量控件，软件音量下零损失不成立)"
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
            applyFraction(frac, immediate = frac <= 0f, source = "system-sync")
        }
    }

    /**
     * [v10·0 格必静音] 由音量写入口（setMusicStreamVolumeFraction）在写完系统音量后**直接**调用，
     * 把目标比例立即落到 systemVolumeFraction 并推送到输出端，不依赖 VOLUME_CHANGED 广播回环。
     */
    fun applyFraction(fraction: Float, immediate: Boolean, source: String = "unknown") {
        val frac = fraction.coerceIn(0f, 1f)
        systemVolumeFraction = frac
        lastLoggedVolume = -1 // 让下一次 syncSystemVolume 的日志如实记录真实系统值
        val db = fractionToDb(frac)
        currentDb = db
        val mode = effectiveMode()
        hardwareActive = mode == UsbVolumeMode.HARDWARE
        val h = handoff
        // [1.4.30·R183③] 落点要能被证明：硬件模式写没写成、有没有降级、数字增益多少。
        var hwWrote: Boolean? = null
        if (mode == UsbVolumeMode.HARDWARE) {
            // 硬件模式：音量写 DAC，数字域保持满档（playerVolume 若被 App 单独调小仍要乘进去）。
            val target = if (frac <= 0f) HardwareMuteDb else db
            if (h != null) {
                val wrote = h.requestHardware(target, immediate = immediate)
                hwWrote = wrote
                if (!wrote) {
                    // [R174] 硬件写入口不可用：立即降级数字音量，并把零损失直传一起撤销，
                    // 否则"音量键完全无效"。onHardwareWriteFailed 内部会做这两件事。
                    onHardwareWriteFailed("writer unavailable (frac=$frac)")
                }
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
        logVolumeChange(frac, db, mode, hwWrote, source)
    }

    private var lastVolLoggedFrac = -1f
    private var lastVolLoggedMode: UsbVolumeMode? = null

    /**
     * [1.4.30·R183③] 一行说清"谁改的、改到哪、落到哪"。
     *
     * 只在比例变化 ≥1% 或模式变化时打：音量滑杆一次拖动会打进来几十次，
     * 全打只会把别的帧淹掉（R177 的教训——日志要能读，不是越全越好）。
     * `src` 是动因（音量键 / 界面滑杆 / 系统同步 / 会话初始化），
     * `hwWrote=null` 表示走数字域、没有硬件写入这一步。
     */
    private fun logVolumeChange(
        frac: Float,
        db: Float,
        mode: UsbVolumeMode,
        hwWrote: Boolean?,
        source: String,
    ) {
        val moved = kotlin.math.abs(frac - lastVolLoggedFrac) >= 0.01f
        if (!moved && mode == lastVolLoggedMode) return
        lastVolLoggedFrac = frac
        lastVolLoggedMode = mode
        AppLog.i(
            TAG,
            "VOL src=$source frac=${"%.3f".format(frac)} db=${"%.1f".format(db)}" +
                " mode=$mode hwVer=$hardwareVerification" +
                " hwWrote=${hwWrote?.let { if (it) 1 else 0 } ?: "-"}" +
                " softGain=${"%.3f".format(softwareGain())}" +
                " result=${if (mode == UsbVolumeMode.HARDWARE && hwWrote == false) "FALLBACK_DIGITAL" else "APPLIED"}",
        )
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
        val probeMs = SystemClock.elapsedRealtime() - started
        hardwareSupported = ok
        // [1.4.32·A5/R170] 三态改由探测级别决定，不再用 probeMs<=1 猜：
        // 旧判据把"响应极快但已完成写回读"的好 DAC 误降成 DETECTED，又把"没验证"的当 VERIFIED。
        // 现在以 Sink 记录的 probeLevel 为准：L2=写两电平回读跟随=VERIFIED；L1=仅探测到控件
        // （描述符命中 FU 但没走通写回读）=DETECTED；L0=没有控件/探测失败=UNAVAILABLE。
        val level = runCatching { sink.hardwareProbeLevel }.getOrDefault(if (ok) 2 else 0)
        hardwareVerification = when {
            level >= 2 || (ok && runCatching { sink.hardwareVolumeVerified }.getOrDefault(false)) ->
                HardwareVerification.VERIFIED
            level >= 1 || ok -> HardwareVerification.DETECTED
            else -> HardwareVerification.UNAVAILABLE
        }
        AppLog.i(
            TAG,
            "session ready hwVol=$ok verification=$hardwareVerification probeLevel=L$level probeMs=$probeMs " +
                "mode=${effectiveMode()} zeroLoss=${UsbExclusiveSinkRef.zeroLossEnabled} " +
                "zeroLossGate=${zeroLossHardwareGateReason()}",
        )
        // 新会话的 DSP 侧可能是上一首残留：先把抖动开关同步过去。
        runCatching { sink.setDither(UsbVolumeModeStore.ditherEnabled()) }
        val ctx = appContext
        // 走 syncSystemVolume 时来源由它内部标"systen-sync"；这里只处理没有 context 的兜底。
        if (ctx != null) syncSystemVolume(ctx) else applyFraction(systemVolumeFraction, immediate = true, source = "session-init")
    }

    /** 会话关闭：清掉硬件能力，避免 UI 继续显示"支持硬件音量"。 */
    fun onSessionClosed() {
        hardwareSupported = false
        hardwareVerification = HardwareVerification.UNAVAILABLE
        hardwareActive = false
        _sessionActive.value = false
        // [1.4.32·A4] L3 音频域闭环结论绑定具体 DAC 会话，换设备/重连必须重验，
        // 否则上一台"音量真能变"的结论会错误放行新设备上的零损失。
        UsbVolumeProbe.reset()
        AppLog.i(TAG, "session closed, hwVol + L3 reset")
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
        hardwareVerification = HardwareVerification.UNAVAILABLE
        // [R169/R174] 硬件音量写不进去时，零损失直传必须一起撤销——否则软件音量也失效，
        // 用户没有任何手段把音量压下来（实测只有 DAC 物理旋钮能救）。
        runCatching { UsbExclusiveSinkRef.current?.dropZeroLossRoute("hw-write-failed: $reason") }
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
