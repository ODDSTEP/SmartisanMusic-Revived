package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog
import kotlin.random.Random

/**
 * [R164/R188·2026-11-26] 唱盘爆豆音的调度中枢。
 *
 * 旧实现把"每 1.3~10 秒随机播一次"的循环写在 `PlaybackScreen` 的 `LaunchedEffect` 里，
 * 于是**只要大播放页不在 composition，协程就不运行**——用户在设置页开开关、反复开关 8 次，
 * 日志里 `爆豆音触发` 与 `被开关闸拦下` 各 0 条，功能看起来"永远不触发"。
 *
 * 现在调度下沉到 [PlaybackService]（页面可见性不再是前提），本对象只负责：
 *  - 广播"是否在搓碟"（UI 的拖拽状态，服务层拿不到）；
 *  - 提供与旧实现逐字一致的三档疏密分布；
 *  - 记录"上一次为什么没播"，供每秒一条 PC 帧输出，避免再次出现"完全无日志可查"。
 */
object PopcornScheduler {

    private const val TAG = "Popcorn"

    /** 用户正在搓碟：此时只由 ScratchSoundController 出刮擦声，爆豆音必须停。 */
    @Volatile
    var scratching: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                reason = if (value) "scratching" else "ready"
            }
        }

    /** 大播放页当前是否可见（只作诊断，不再决定是否播放）。 */
    @Volatile
    var pageVisible: Boolean = false

    /** 最近一次"决定"的原因，进 PC 帧。 */
    @Volatile
    var reason: String = "init"

    @Volatile
    var lastTriggerAtMs: Long = 0L

    @Volatile
    var triggerCount: Int = 0

    /**
     * [1.4.30·R183④] 下一次"该响"的时刻（elapsedRealtime）。配合 [reason] 回答
     * "到了点为什么没响"：due 已过 + reason=due-not-playing 就是被播放态拦下，
     * 而不是调度器根本没跑。
     */
    @Volatile
    var nextDueAtMs: Long = 0L

    /**
     * 三档疏密分布（与旧 UI 实现逐字一致，用户 2026-11-24 已按听感调过）：
     * - 75%：灰尘/轻微磨损主流 —— 0.15~0.50 次/秒（9~30 次/分）
     * - 15%：更稀疏偶发灰尘 —— 0.10~0.15 次/秒（6~9 次/分）
     * - 10%：稍密（轻微划痕簇）—— 0.50~0.75 次/秒（30~45 次/分）
     */
    fun nextIntervalMs(): Long {
        val roll = Random.nextFloat()
        val perSecond = when {
            roll < 0.75f -> 0.15f + Random.nextFloat() * 0.35f
            roll < 0.90f -> 0.10f + Random.nextFloat() * 0.05f
            else -> 0.50f + Random.nextFloat() * 0.25f
        }
        return (1_000f / perSecond).toLong().coerceIn(1_333L, 10_000L)
    }

    fun noteTrigger() {
        lastTriggerAtMs = android.os.SystemClock.elapsedRealtime()
        triggerCount++
        reason = "played"
    }

    /** 每秒一条 PC 帧：把"为什么没响"的四个条件全部摊开。 */
    fun logFrame(
        enabled: Boolean,
        playing: Boolean,
        uacActive: Boolean,
    ) {
        AppLog.i(
            TAG,
            "PC frame enabled=${if (enabled) 1 else 0} playing=${if (playing) 1 else 0}" +
                " scratching=${if (scratching) 1 else 0} pageVisible=${if (pageVisible) 1 else 0}" +
                " uac=${if (uacActive) 1 else 0} reason=$reason count=$triggerCount" +
                " dueInMs=${
                    if (nextDueAtMs <= 0L) -1L
                    else nextDueAtMs - android.os.SystemClock.elapsedRealtime()
                }" +
                " sinceLastMs=${
                    if (lastTriggerAtMs <= 0L) -1L
                    else android.os.SystemClock.elapsedRealtime() - lastTriggerAtMs
                }",
        )
    }
}
