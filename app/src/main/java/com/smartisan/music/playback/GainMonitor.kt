package com.smartisan.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * [1.4.20·P0 增益结构可视化] 实时信号监测器。
 *
 * 对标 Salt Player 的 usb_exclusive_clipped_samples / headroom / signal_monitor 一组能力：
 * 在PCM 域统计【峰值(dBFS)、数字余量、削波样本数、RMS】，节流发布给 UI。
 * 两条解码链（自研 PcmDsp / BASS）都在输出 PCM 后喂进来，因此独占与共享都覆盖。
 *
 * 设计要点：
 *  - 热路径零分配：feed() 只做比较与累加，不 new 对象、不 publish；
 *  - 节流：每 ~250ms 由 UI ticker 调 publishIfDue()，避免每帧刷新 StateFlow 抖动；
 *  - 单调累计的 clippedSamples 在 reset()/切歌时清零。
 */
object GainMonitor {

    data class Stats(
        val peakDbfs: Float = Float.NEGATIVE_INFINITY,
        val headroomDb: Float = Float.POSITIVE_INFINITY,
        val rmsDbfs: Float = Float.NEGATIVE_INFINITY,
        val clippedSamples: Long = 0L,
        val sampleCount: Long = 0L,
    ) {
        val hasSignal: Boolean get() = sampleCount > 0L && peakDbfs.isFinite()
    }

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** 是否统计（由 PcmDsp.configure 依据 PlaybackSettings.gainMeterEnabled 设置）。 */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) reset()
        }

    // 窗口内累加器（单线程音频线程写，publishIfDue 读；用 volatile/原子足够，UI 只读快照）
    private var winPeak = 0f
    private var winSquareSum = 0.0
    private var winSamples = 0L
    private var totalClipped = 0L
    private var totalSamples = 0L
    private var lastPublishNs = 0L

    /** 由 DSP 每个处理块调用。data 为交错 float PCM，|v|<=1 为正常范围。 */
    fun feed(data: FloatArray, frames: Int, channels: Int) {
        if (!enabled || frames <= 0 || channels <= 0) return
        val n = frames * channels
        var i = 0
        while (i < n) {
            val v = data[i]
            val a = if (v.isNaN() || v.isInfinite()) 1f else abs(v)
            if (a > winPeak) winPeak = a
            winSquareSum += a.toDouble() * a.toDouble()
            if (a >= 0.999f) totalClipped++
            i++
        }
        winSamples += n.toLong()
        totalSamples += n.toLong()
    }

    /** UI ticker 每 ~250ms 调一次，节流发布窗口统计。 */
    fun publishIfDue(nowNs: Long = System.nanoTime()) {
        if (!enabled || winSamples == 0L) return
        if (nowNs - lastPublishNs < 200_000_000L) return
        lastPublishNs = nowNs
        val peak = winPeak
        val peakDb = if (peak > 0f) 20f * log10(peak.toDouble()).toFloat() else Float.NEGATIVE_INFINITY
        val rms = kotlin.math.sqrt(winSquareSum / winSamples).toFloat()
        val rmsDb = if (rms > 0f) 20f * log10(rms.toDouble()).toFloat() else Float.NEGATIVE_INFINITY
        _stats.value = Stats(
            peakDbfs = peakDb,
            headroomDb = -peakDb,
            rmsDbfs = rmsDb,
            clippedSamples = totalClipped,
            sampleCount = totalSamples,
        )
        // 窗口归零，累计项（clipped/sampleCount）保留
        winPeak = 0f
        winSquareSum = 0.0
        winSamples = 0L
    }

    /** 切歌 / seek / 关闭时清一切，避免上一首的削波计数串到新曲。 */
    fun reset() {
        winPeak = 0f
        winSquareSum = 0.0
        winSamples = 0L
        totalClipped = 0L
        totalSamples = 0L
        lastPublishNs = 0L
        _stats.value = Stats()
    }

    /** 格式化 dBFS 供 UI 直接显示：无信号显示 —。 */
    fun formatDb(db: Float): String =
        if (!db.isFinite()) "—" else "${if (db > 0f) "+" else ""}${"%.1f".format(db)} dB"
}
