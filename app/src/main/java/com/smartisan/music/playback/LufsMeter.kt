package com.smartisan.music.playback

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.PI

/**
 * [1.4.23·响度归一化] 实时 LUFS（EBU R128 / ITU-R BS.1770）响度估计器。
 *
 * 目标：给"无标签响度归一化 / 音量均衡"一个真正的响度测量依据——旧实现只是把
 * 用户手填的 0dB 常量当增益（纯空壳），本类用 K-weighting 加权 + 长时滑动均方
 * 估计当前节目的积分响度，PcmDsp 据此把增益平滑收敛到目标 -16 LUFS
 * （对齐 FFmpeg loudnorm=I=-16），让不同响度的曲目听起来一样大声。
 *
 * K-weighting = 一级 high-shelf（+4dB @ ~1500Hz，模拟人耳高频敏感）+
 * 一级 high-pass（@ 38Hz，滤除次声隆隆），系数用 RBJ 公式按采样率实时算。
 * 由于实时流无法做 BS.1770 严格的两遍双门控，这里用 1.5s 时间常数的指数滑动
 * 均方近似"积分响度"，对连续音乐足够稳定，且无自激（测量点在增益施加之前）。
 */
internal class LufsMeter {

    /** 自包含的 RBJ biquad，避免依赖 PcmDsp 的私有内部类。 */
    private class Biquad {
        private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

        fun setHighShelf(sr: Float, freqHz: Float, gainDb: Float) {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2.0 * PI * freqHz / sr).toFloat()
            val alpha = sin(w0) / 2f / 0.7071f
            val cw = cos(w0)
            val t = 2f * kotlin.math.sqrt(a) * alpha
            val b0n = a * ((a + 1f) + (a - 1f) * cw + t)
            val b1n = -2f * a * ((a - 1f) + (a + 1f) * cw)
            val b2n = a * ((a + 1f) + (a - 1f) * cw - t)
            val a0n = (a + 1f) - (a - 1f) * cw + t
            val a1n = 2f * ((a - 1f) - (a + 1f) * cw)
            val a2n = (a + 1f) - (a - 1f) * cw - t
            assign(b0n / a0n, b1n / a0n, b2n / a0n, a1n / a0n, a2n / a0n)
        }

        fun setHighPass(sr: Float, freqHz: Float) {
            val w0 = (2.0 * PI * freqHz / sr).toFloat()
            val alpha = sin(w0) / 2f / 0.5f
            val cw = cos(w0)
            val b0n = (1f + cw) / 2f
            val b1n = -(1f + cw)
            val b2n = (1f + cw) / 2f
            val a0n = 1f + alpha
            val a1n = -2f * cw
            val a2n = 1f - alpha
            assign(b0n / a0n, b1n / a0n, b2n / a0n, a1n / a0n, a2n / a0n)
        }

        private fun assign(b0n: Float, b1n: Float, b2n: Float, a1n: Float, a2n: Float) {
            if (b0n.isNaN() || a1n.isNaN()) {
                b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            } else {
                b0 = b0n; b1 = b1n; b2 = b2n; a1 = a1n; a2 = a2n
            }
            reset()
        }

        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (y.isNaN() || y.isInfinite()) { reset(); return 0f }
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private val shelf = arrayOf(Biquad(), Biquad())
    private val highpass = arrayOf(Biquad(), Biquad())
    private var configuredRate = 0

    /** 长时滑动均方（K 加权、左右求和后）。 */
    private var meanSquare = 0.0
    /** 已累积的有效样本数（warmup 用，避免开头响度低估导致增益猛冲）。 */
    private var accumulated = 0L

    fun configure(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(8_000)
        if (sr == configuredRate) return
        configuredRate = sr
        val f = sr.toFloat()
        for (c in 0..1) {
            shelf[c].setHighShelf(f, 1500f, 4f)
            highpass[c].setHighPass(f, 38f)
        }
        reset()
    }

    fun reset() {
        meanSquare = 0.0
        accumulated = 0L
        for (c in 0..1) { shelf[c].reset(); highpass[c].reset() }
    }

    /**
     * 喂入一个处理块的增益前样本（交错排列）。返回累积是否已"热身"完毕
     * （达到 ~1s 有效测量），未热身时调用方应维持单位增益，避免瞬时误判。
     */
    fun feed(data: FloatArray, frames: Int, channels: Int, sampleRate: Int): Boolean {
        configure(sampleRate)
        if (frames <= 0) return accumulated >= sampleRate.toLong()
        val ch = if (channels > 2) 2 else max(1, channels)
        var f = 0
        while (f < frames) {
            val base = f * channels
            var sum = 0.0
            var c = 0
            while (c < ch) {
                var v = data[base + c]
                v = shelf[c].process(v)
                v = highpass[c].process(v)
                sum += v.toDouble() * v
                c++
            }
            // BS.1770 各通道等权求和（双声道即 L+R）
            val blockMs = sum
            // 1.5s 时间常数的指数平滑
            val alpha = 1.0 - exp(-1.0 / (1.5 * configuredRate))
            meanSquare += (blockMs - meanSquare) * alpha
            accumulated += 1
            f++
        }
        return accumulated >= configuredRate.toLong()
    }

    /** 当前积分响度估计（LUFS）。未热身时返回 -70（视为静音，不触发增益）。 */
    fun integratedLufs(): Float {
        if (accumulated < configuredRate.toLong() || meanSquare <= 1e-12) return -70f
        return (-0.691 + 10.0 * log10(meanSquare)).toFloat()
    }
}
