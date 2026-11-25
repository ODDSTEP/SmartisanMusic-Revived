package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * [1.4.21·P2] 软件音量 ↔ 系统音量条 的「平滑接力 + 读回确认」。
 *
 * 背景：本项目自 1.2.6 起音量统一走软件增益（PCM 数字衰减），系统音量条是唯一的音量入口。
 * 过去每次音量变化都是「瞬间阶跃」地 setSoftwareGain(g)，拖动/连按时会出现音量跳变、爆豆音。
 * 本类把每一次目标增益变化变成一段短促的**等功率渐变**（默认 45ms，分若干步），
 * 逐帧把增益「手递手」交给输出端，听感连续、无咔哒。
 *
 * 同时提供**读回确认**：每步施加后记录已施加增益 [appliedGain]，并可由 [UsbDac.Sink] 的
 * softwareGain() 从输出端读回真实生效值，与 [targetGain] 比对；UI 面板显示目标/实际/是否一致，
 * 让「设置有没有真的生效」用户可见（对应用户反馈的「音量调节跳变/不确定有没有生效」）。
 *
 * 设计约束：
 *  - 不依赖硬件音量（硬件音量整套因历史 bug 已不在用户路径上），纯软件增益，零回归风险。
 *  - 新旧 USB 引擎都适用：只经 [UsbDac.Sink.setSoftwareGain] 这一层，旧引擎有逐包钩子、
 *    新引擎即使空实现也不受影响（新引擎音量另走系统/硬件路径）。
 *  - 频繁变化（连续按键/拖动）时取消上一段渐变、从「当前已施加值」续接，绝不回跳。
 */
class SmoothVolumeHandoff(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "VolHandoff"
        /** 渐变总时长（毫秒）。短到不影响响应，长到消除阶跃爆音。 */
        private const val RAMP_MS = 45L
        /** 渐变步数。每步间隔 = RAMP_MS / STEPS，约 5~7ms 一步。 */
        private const val STEPS = 8
        /** 读回值与目标值容差，小于此视为一致。 */
        private const val READBACK_TOL = 0.02f
        /** [v22] DAC 硬件音量写入节流：连续按音量键不能每次都发 USB 控制传输。 */
        private const val HW_THROTTLE_MS = 50L
    }

    /** 是否启用平滑接力；关闭时退化为「瞬间施加」（保持旧行为，可对比）。 */
    @Volatile
    var enabled: Boolean = true

    /** 目标增益（0..1），外部音量变化写这里；[targetEpoch] 递增用于打断上一段渐变。 */
    @Volatile
    var targetGain: Float = 1f
        private set

    /** 已施加到输出端的增益（读回前的本地值）。UI 显示用。 */
    @Volatile
    var appliedGain: Float = 1f
        private set

    /** 输出端读回的真实增益；-1 表示尚未读回/不支持。 */
    @Volatile
    var readbackGain: Float = -1f
        private set

    /** 读回是否与目标一致（UI 显示「音量已生效」）。 */
    val readbackOk: Boolean
        get() {
            val r = readbackGain
            return r >= 0f && kotlin.math.abs(r - targetGain) <= READBACK_TOL
        }

    /** 当前是否正在渐变中（UI 可显示「音量过渡中」）。 */
    val isRamping: Boolean get() = rampJob?.isActive == true

    @Volatile
    private var targetEpoch = 0
    @Volatile
    private var rampJob: Job? = null
    private val gainProvider = { UsbDacSinkRef.current?.softwareGain() }

    /** [v22] 硬件音量写入口（由 UsbVolume 在会话建立时绑定到具体 sink）。 */
    @Volatile
    private var hardwareWriter: ((Float) -> Boolean)? = null

    /** [v22] 硬件音量节流状态。 */
    private var lastHwWriteMs = 0L
    private var hwJob: Job? = null
    private var pendingHwDb: Float? = null

    /** UI 观察用的读回状态快照（目标/实际/是否渐变中）。节流在发布点控制。 */
    data class Snapshot(val target: Float, val applied: Float, val readback: Float, val ramping: Boolean)
    private val _snapshot = MutableStateFlow(Snapshot(1f, 1f, -1f, false))
    val snapshot: StateFlow<Snapshot> get() = _snapshot

    /** 发布一次 UI 快照（读回完成后 / 目标变化时）。 */
    private fun publish() {
        _snapshot.value = Snapshot(targetGain, appliedGain, readbackGain, isRamping)
    }

    /**
     * 音量目标变化：启动/重启一段平滑接力。
     * @param gain 目标增益 0..1
     * @param immediate true=跳过渐变直接施加（初始化、0 档静音等需要立即生效的场景）
     */
    fun request(gain: Float, immediate: Boolean = false) {
        val g = gain.coerceIn(0f, 1f)
        targetGain = g
        val epoch = ++targetEpoch
        rampJob?.cancel()
        if (!enabled || immediate) {
            applyStep(g)
            readback()
            publish()
            return
        }
        publish()
        rampJob = scope.launch(Dispatchers.Default) {
            val start = appliedGain
            val stepMs = (RAMP_MS / STEPS).coerceAtLeast(1L)
            for (i in 1..STEPS) {
                if (!isActive || targetEpoch != epoch) return@launch
                val t = i.toFloat() / STEPS
                // 等功率接力：线性插值 sin/cos，避免中间响度塌陷（Salt/handoff 的核心诉求）。
                val g1 = start * cos(t * Math.PI / 2f).toFloat() + g * sin(t * Math.PI / 2f).toFloat()
                applyStep(g1.coerceIn(0f, 1f))
                delay(stepMs)
            }
            if (isActive && targetEpoch == epoch) {
                applyStep(g)
                readback()
                publish()
            }
        }
    }

    /**
     * [v22] 绑定 DAC 硬件音量写入口。传 null 解绑（会话关闭）。
     */
    fun bindHardwareWriter(writer: ((Float) -> Boolean)?) {
        hardwareWriter = writer
        pendingHwDb = null
        hwJob?.cancel()
        hwJob = null
    }

    /**
     * [v22] 硬件音量目标变化（数字域保持满档）。
     * 50ms 节流：拖动/连按只写最后一次，避免几十次 USB 控制传输把 URB 挤爆。
     */
    fun requestHardware(db: Float, immediate: Boolean = false) {
        if (hardwareWriter == null) return
        val now = SystemClock.elapsedRealtime()
        val wait = if (immediate) 0L else (HW_THROTTLE_MS - (now - lastHwWriteMs)).coerceAtLeast(0L)
        if (wait == 0L) {
            lastHwWriteMs = now
            writeHardware(db)
            return
        }
        pendingHwDb = db
        hwJob?.cancel()
        hwJob = scope.launch {
            delay(wait)
            val target = pendingHwDb ?: return@launch
            lastHwWriteMs = SystemClock.elapsedRealtime()
            writeHardware(target)
        }
    }

    /**
     * [v22] 数字 → 硬件 平滑交接（Salt 口径：先保持当前数字衰减，写入 DAC 硬件音量，
     * 再平滑释放临时衰减）。响度只会先低后高，绝不会突然炸耳。
     */
    fun switchToHardware(db: Float, digitalGain: Float = 1f) {
        if (hardwareWriter == null) return
        if (!writeHardware(db)) return   // 写失败：UsbVolume 已回滚数字模式
        request(digitalGain.coerceIn(0f, 1f), immediate = false)
    }

    /**
     * [v22] 硬件 → 数字 平滑交接：数字先归零 → 硬件回 0 dB（满档）→ 数字从 0 淡入到目标。
     * 先归零是为了避免"数字衰减 + 硬件衰减"叠加造成音量突然变小或切换瞬间爆音。
     */
    fun switchToDigital(gain: Float) {
        request(0f, immediate = true)
        writeHardware(0f)
        request(gain.coerceIn(0f, 1f), immediate = false)
    }

    /** 真正写一次硬件音量；失败时立刻通知 UsbVolume 降级，不静默。 */
    private fun writeHardware(db: Float): Boolean {
        val writer = hardwareWriter ?: return false
        val ok = runCatching { writer(db) }.getOrDefault(false)
        if (!ok) {
            UsbVolume.onHardwareWriteFailed("db=${"%.1f".format(db)}")
        }
        return ok
    }

    /**
     * [v12·切引擎/会话重建爆音根治] 新 UAC 会话建立时，把增益状态重置为 0 起步。
     *
     * 背景：会话重建=全新的 native sink 句柄，其硬件增益可能从默认值起；而 handoff 的
     * appliedGain 仍是旧会话的值（常为 1f）。此时 syncSystemVolume 触发的 ramp 从 appliedGain
     * 起步，第一步就把接近满档的增益写到新 sink → 首包 PCM 瞬间满幅 → "开头很大一声爆音"。
     * 本方法取消进行中的 ramp、把本地起点归零（不写 sink，调用方负责把新 sink 先设 0），
     * 让随后的 request 从 0 平滑淡入到目标，消除重建爆音。
     */
    fun resetForNewSession() {
        rampJob?.cancel()
        rampJob = null
        hwJob?.cancel()
        hwJob = null
        pendingHwDb = null
        appliedGain = 0f
        ++targetEpoch
        publish()
    }

    /** 施加一步增益到输出端。 */
    private fun applyStep(g: Float) {
        appliedGain = g
        runCatching { UsbDacSinkRef.current?.setSoftwareGain(g) }
    }

    /** 从输出端读回真实增益，确认「设置真的生效了」。 */
    private fun readback() {
        val v = runCatching { gainProvider() }.getOrNull()
        readbackGain = v ?: -1f
        if (v != null && kotlin.math.abs(v - targetGain) > READBACK_TOL) {
            AppLog.w(TAG, "readback mismatch target=${"%.3f".format(targetGain)} actual=${"%.3f".format(v)}")
        } else if (v != null) {
            AppLog.d(TAG, "readback ok gain=${"%.3f".format(v)}")
        }
    }

    fun release() {
        rampJob?.cancel()
        rampJob = null
        hwJob?.cancel()
        hwJob = null
        pendingHwDb = null
        hardwareWriter = null
    }
}
