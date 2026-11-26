package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import com.smartisan.music.diagnostics.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * [1.4.20·拍板2] Morvanium 思路自研复刻：智能过渡的【分析 + 决策】层。
 *
 * 严格按 55 号报告从 Salt smali 反编译出的 Morvanium 参数复刻策略（非照抄 native，自研实现）：
 *  - 分析配置：FFT1024、BPM 范围 65~190、静音地板检测（分频能量）；
 *  - 数据维度：BPM、拍长(beatSec)、低/中/高频能量、段落能量曲线、是否同专辑；
 *  - 决策策略：BPM 差 ≤ 12% 才做变速对齐、按 preferredBeatCount(24 拍) 算过渡长度、
 *    2~12 秒自适应、同专辑保留原衔接、变速不变调（BASS_FX_TempoCreate 在 mixer 层做）；
 *  - 切入点：对齐到下一曲的节拍边界（beatSec 整数倍），不是死板固定时长。
 *
 * 所有分析都在【独立 decode channel】上跑（绝不碰正在播放的 channel，
 * 修审计发现的 detectBpm 消费播放流 bug）。分析完即释放，结果缓存复用。
 */
object MorvaniumAnalyzer {

    data class TrackProfile(
        val uri: String,
        val bpm: Float,                  // 0=未检出
        val beatSec: Float,              // 一拍秒数
        val lowEnergyShare: Float,       // 低频能量占比 0..1
        val midEnergyShare: Float,
        val highEnergyShare: Float,
        val avgEnergy: Float,            // 平均能量（dB 归一到 0..1）
        val outroIsQuiet: Boolean,       // 尾部是否为低能量过门（适合过渡切入）
    ) {
        val hasBeat: Boolean get() = bpm > 0f && beatSec > 0f
    }

    data class TransitionPlan(
        val fadeSec: Int,                // 实际交叉淡化时长（秒）
        val beatAligned: Boolean,        // 是否已对齐节拍
        val tempoAdjustPct: Float,       // 下一曲变速百分比（-50..50，0=不变速）
        val useDoubleSpeed: Boolean,     // 是否需变速把 BPM 拉齐
        val preserveContinuity: Boolean, // 同专辑：保留更短的过渡
        val reason: String,              // 决策理由（日志可见）
    )

    // Salt MorvaniumAnalysisConfig 对齐值
    private const val MIN_BPM = 65
    private const val MAX_BPM = 190
    private const val PREFERRED_BEATS = 24
    private const val MAX_TEMPO_CHANGE = 0.12f
    private const val MIN_FADE_SEC = 2
    private const val MAX_FADE_SEC = 12
    private const val FALLBACK_FADE_SEC = 8

    private data class CacheEntry(val profile: TrackProfile?, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val MaxCache = 128

    /** 只读缓存（热路径零阻塞）：未分析过返回 null，调用方回退基础策略。 */
    fun analyzeCached(uriString: String): TrackProfile? = cache[uriString]?.profile

    /** 同步分析（调用方在 IO 线程）。失败返回 null，调用方回退固定时长淡化。 */
    fun analyze(context: Context, uri: Uri): TrackProfile? {
        val key = uri.toString()
        cache[key]?.let { return it.profile }
        // [1.4.25·native 闪退根治] 智能过渡的后台预分析用 BASS 解 PCM，而 libbassape/libbassdsd
        // 解 APE/DSD/高码率会 SIGSEGV（Java 抓不到，直接闪退）。这类格式一律跳过分析，
        // 调用方回退固定时长淡化——放弃"智能过渡"但保住"播放不闪退"。
        if (com.smartisan.music.playback.OfficialDecodeFrontend.isBassUnsafe(context, uri)) {
            AppLog.i("Morvanium", "冷门/高解析格式跳过BASS预分析(防native闪退) ${key.takeLast(40)}")
            return null
        }
        val profile = runCatching { analyzeUnsafe(context, uri, key) }
            .onFailure { AppLog.w("Morvanium", "分析失败 ${key.takeLast(40)}: ${it.message}") }
            .getOrNull()
        if (cache.size >= MaxCache) cache.entries.minByOrNull { it.value.ts }?.let { cache.remove(it.key) }
        cache[key] = CacheEntry(profile, System.currentTimeMillis())
        if (profile != null) {
            AppLog.i(
                "Morvanium",
                "档案 ${key.takeLast(36)} bpm=${"%.1f".format(profile.bpm)} " +
                    "beat=${"%.3f".format(profile.beatSec)}s 低/中/高=" +
                    "${"%.2f".format(profile.lowEnergyShare)}/${"%.2f".format(profile.midEnergyShare)}/" +
                    "${"%.2f".format(profile.highEnergyShare)} outroQuiet=${profile.outroIsQuiet}",
            )
        }
        return profile
    }

    private fun analyzeUnsafe(context: Context, uri: Uri, key: String): TrackProfile? {
        // 独立 channel：BASS_FX BPM 会消费 channel，绝不能用播放中的 handle。
        val ch = BassEngine.openForCrossfade(uri, context) ?: return null
        try {
            val duration = ch.durationSec
            if (duration <= 2.0) return null
            // 1) BPM（65~190，Salt 配置）
            val bpm = ch.detectBpm(MIN_BPM, MAX_BPM)
            val beatSec = if (bpm > 0f) 60f / bpm else 0f
            // 2) FFT 分频能量：在开头 5% 与尾部 92% 各取一帧 + 中段 3 点求均值。
            val fft = FloatArray(513)
            fun energyAt(frac: Double): FloatArray {
                ch.seekSecForAnalysis(duration * frac)
                val n = ch.readFft(fft)
                val out = FloatArray(if (n > 0) n.coerceAtMost(fft.size) else 0)
                if (n > 0) System.arraycopy(fft, 0, out, 0, out.size)
                return out
            }
            val samples = listOf(energyAt(0.05), energyAt(0.30), energyAt(0.55), energyAt(0.80), energyAt(0.94))
            var low = 0f; var mid = 0f; var high = 0f; var count = 0
            for (s in samples) {
                if (s.isEmpty()) continue
                // FFT bin：1024 点→513 bin，按 44.1k 算每 bin≈43Hz。低<250Hz(bin<6)，中 250-4k(bin 6-93)，高>4k。
                val n = s.size
                val lowEnd = (6 * n / 513).coerceIn(1, n)
                val midEnd = (93 * n / 513).coerceIn(lowEnd + 1, n)
                var lo = 0f; var md = 0f; var hi = 0f
                for (i in 0 until n) {
                    // BASS_DATA_FLOAT 返回 dB（负值，静音=-inf/极小）。转成能量权重 0..1。
                    val amp = dbToAmp(s[i])
                    when {
                        i < lowEnd -> lo += amp
                        i < midEnd -> md += amp
                        else -> hi += amp
                    }
                }
                low += lo; mid += md; high += hi; count++
            }
            if (count == 0) return null
            val total = low + mid + high
            if (total <= 0f) return null
            val outro = samples.last()
            var outroEnergy = 0f
            for (v in outro) outroEnergy += dbToAmp(v)
            val avgEnergy = (total / count).coerceIn(0f, 1f)
            // [1.4.28] 量纲修正：outroEnergy 是「最后一帧三个频段能量之和」，
            // avgEnergy 是「每帧三频段之和的平均」，两者同量纲直接比。
            // 旧写法除以整个窗口帧数 count，左边被缩小几百倍，outroQuiet 几乎恒为 true，
            // 「结尾是否安静」这个决策输入永远是同一个答案。
            val outroQuiet = outroEnergy < avgEnergy * 0.55f
            return TrackProfile(
                uri = key,
                bpm = bpm,
                beatSec = beatSec,
                lowEnergyShare = low / total,
                midEnergyShare = mid / total,
                highEnergyShare = high / total,
                avgEnergy = avgEnergy,
                outroIsQuiet = outroQuiet,
            )
        } finally {
            // 分析 channel 用毕即释，绝不残留占用。
            ch.close()
        }
    }

    /** BASS FFT 的 float 值是 dB（如 -60 静音、-30 中等、0 满幅），映射到 0..1 能量权重。 */
    private fun dbToAmp(db: Float): Float {
        if (db.isNaN() || db <= -90f) return 0f
        return ((db + 90f) / 90f).coerceIn(0f, 1f)
    }

    /**
     * 生成过渡决策：结合当前曲尾部与下一曲 profile。
     * Salt MorvaniumTransitionPolicy：preserveAlbumContinuity、maximumTempoChange=0.12、
     * preferredBeatCount=24、min/max=2/12s、fallback=8s。
     */
    fun planTransition(
        current: TrackProfile?,
        next: TrackProfile?,
        baseFadeSec: Int,
        sameAlbum: Boolean,
    ): TransitionPlan {
        if (next == null) {
            return TransitionPlan(baseFadeSec, false, 0f, false, false, "无下一曲档案，用基础时长")
        }
        // 同专辑：保留衔接，过渡缩短（Salt preserveAlbumContinuity=true）
        if (sameAlbum) {
            val sec = MIN_FADE_SEC.coerceAtLeast(baseFadeSec / 2)
            return TransitionPlan(sec, false, 0f, false, true, "同专辑，保留原衔接，缩短过渡")
        }
        var fadeSec = baseFadeSec.coerceIn(MIN_FADE_SEC, MAX_FADE_SEC)
        var beatAligned = false
        var tempoPct = 0f
        var useDouble = false
        val reason = StringBuilder()

        // 按 24 拍算过渡长度（Salt preferredBeatCount），对齐到节拍边界。
        if (next.hasBeat) {
            val beatTarget = PREFERRED_BEATS * next.beatSec
            // 目标时长在允许区间内才采纳，否则退回基础时长
            if (beatTarget >= MIN_FADE_SEC && beatTarget <= MAX_FADE_SEC) {
                fadeSec = ceil(beatTarget.toDouble()).toInt()
                beatAligned = true
                reason.append("按24拍=${"%.1f".format(beatTarget)}s对齐; ")
            } else {
                // 把基础时长向上取整到整数拍
                val beats = ceil(fadeSec / next.beatSec).toInt().coerceAtLeast(1)
                fadeSec = (beats * next.beatSec).toInt().coerceIn(MIN_FADE_SEC, MAX_FADE_SEC)
                beatAligned = true
                reason.append("基础时长取整到${beats}拍=${fadeSec}s; ")
            }
            // BPM 差 ≤ 12% 才变速对齐（Salt maximumTempoChange=0.12）
            if (current != null && current.hasBeat) {
                val ratio = next.bpm / current.bpm
                val diff = abs(ratio - 1f)
                if (diff <= MAX_TEMPO_CHANGE && diff > 0.02f) {
                    tempoPct = ((1f - ratio) * 100f)  // 拉齐到当前 BPM
                    useDouble = true
                    reason.append("BPM差${"%.0f".format(diff * 100)}%≤12%，变速${"%.1f".format(tempoPct)}%对齐; ")
                } else if (diff > MAX_TEMPO_CHANGE) {
                    reason.append("BPM差${"%.0f".format(diff * 100)}%>12%，不变速; ")
                }
            }
        } else {
            fadeSec = FALLBACK_FADE_SEC
            reason.append("无节拍，回退${FALLBACK_FADE_SEC}s; ")
        }
        // 尾部是低能量过门时可适当缩短（在安静段落切入更自然）
        if (current?.outroIsQuiet == true) {
            fadeSec = (fadeSec * 0.8).toInt().coerceAtLeast(MIN_FADE_SEC)
            reason.append("尾部过门，缩短20%; ")
        }
        return TransitionPlan(
            fadeSec = fadeSec.coerceIn(MIN_FADE_SEC, MAX_FADE_SEC),
            beatAligned = beatAligned,
            tempoAdjustPct = tempoPct,
            useDoubleSpeed = useDouble,
            preserveContinuity = false,
            reason = reason.toString().ifBlank { "默认策略" },
        )
    }

    fun clearCache() = cache.clear()
}
