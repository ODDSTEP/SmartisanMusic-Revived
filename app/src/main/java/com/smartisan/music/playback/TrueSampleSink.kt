@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.smartisan.music.diagnostics.AppLog
import com.truesample.usbaudio.ResampleQuality
import com.truesample.usbaudio.UsbAudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [1.4.19·S03 推倒重建] 把开源 TrueSample [UsbAudioOutput] 包装成现有 [UsbDac.Sink] 接口，
 * 让上层 [UsbExclusiveAudioSink] 一行不改即可跑在新 USB 独占地基上。
 *
 * 新地基相比旧自研 usbdac.cpp 的能力（正是用户要求"照抄 Salt"的核心）：
 * - 用 libusb 用户态驱动 + libusb_wrap_sys_device，无需 root、脱离内核 snd-usb-audio；
 * - 完整解析 UAC1/UAC2 描述符（格式/时钟/Feature Unit/反馈端点），按曲目选最佳模式；
 * - 异步 DAC 用 feedback 端点跟踪 DAC 自身时钟；
 * - DAC 不支持源采样率时用 libsamplerate 重采样 + TPDF dither 降位深，避免爆音/变调。
 *
 * 数据流：上层 convert() 产出小端交错 PCM（bytesPerSample 为每样本 2/3/4 字节）→ 本适配器
 * 转成 TrueSample 要求的"左对齐 int32"交错 ByteBuffer → output.write()。
 */
internal class TrueSampleSink(
    private val output: UsbAudioOutput,
    override val channels: Int,
    override val bytesPerSample: Int,
    override val sampleRate: Int,
) : UsbDac.Sink {

    private val aliveFlag = AtomicBoolean(true)
    private val frames = AtomicLong(0L)
    @Volatile private var pausedFlag = false
    @Volatile private var hwVolSupportedField = false
    /** [1.4.21] 上层下发的软件增益记录（新引擎 native 无逐包钩子，仅作读回链路一致/UI 显示）。 */
    @Volatile private var softwareGainValue = 1f

    // TrueSample 写缓冲：int32 左对齐、小端、交错。复用避免热路径分配。
    private var pcmBuf: ByteBuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder())

    init {
        hwVolSupportedField = runCatching { output.volumeRange != null }.getOrDefault(false)
        val info = runCatching {
            output.start(sampleRate, bytesPerSample * 8, channels, ResampleQuality.BEST)
        }
            .getOrElse { t ->
                aliveFlag.set(false)
                AppLog.w("UsbNew", "TrueSample start failed: ${t.message}")
                throw t
            }
        AppLog.i(
            "UsbNew",
            "新独占已启动 rate=${info.sampleRate}→${info.outputRate} bits=${info.bitsPerSample}" +
                " ch=$channels bitPerfect=${info.bitPerfect} resampled=${info.resampled} " +
                "dac=${info.format.bitResolution}bit sync=${info.format.syncType}",
        )
    }

    private fun bitsForEnc(e: Int): Int = when (e) {
        UsbDac.SAMPLE_BYTES_2 -> 16
        UsbDac.SAMPLE_BYTES_3 -> 24
        UsbDac.SAMPLE_BYTES_4 -> 32
        else -> 24
    }

    /**
     * [R179·2026-11-26·卡死唯一根因] 这里必须返回 **DAC 端真正送出去的帧数**，
     * 不是 write() 返回的"已排队帧数"。
     *
     * 旧实现 `frames.get()` 在 write() 成功后立刻累加，于是上层
     * `framesWritten - (framesPlayed - framesBase)` 恒等于 0 →
     * `hasPendingData()` 恒 false → Media3 的 `isReady()` 恒 false → 永远 BUFFERING →
     * 看门狗三次后暂停，而 handleBuffer 仍在跑、DAC 仍在出声（声音在播、界面在缓冲）。
     * 同一份日志的旧引擎 A/B：差值恒 97536 帧≈2.03 秒、进度正常。
     *
     * native 的 framesSent 在 fillTransfer 里累加（从 ring 取走、装进 URB 的帧），
     * 与"已写入"之间永远差着 ring 占用，正是 hasPendingData 需要的语义。
     */
    override val framesPlayed: Long
        get() {
            val sent = runCatching { output.stats().framesSent }.getOrNull() ?: return lastSentFrames
            lastSentFrames = sent
            return sent
        }

    @Volatile private var lastSentFrames = 0L

    private var lastUnderruns = 0L
    private var underrunWarnedAt = 0L

    override val stats: String
        get() = runCatching {
            val s = output.stats()
            // [1.4.30·R175] underrun 风暴可见化。
            // 欠载在 native 里是"这一包没供满就填零"，本身不会报错，只会周期性滋一下；
            // 旧实现只在 stats 里留一个累计数，用户听到杂音时日志看不出是"应用供不上"
            // 还是"native 吞太快"。这里按增量判断，并给出结论方向：
            //  - 环形缓冲里存量（written - sent）很小 → 上游（解码/transfer loop）没跟上；
            //  - 存量接近满 → native 侧消耗快于实时（反馈端点/时钟异常）。
            val delta = s.underruns - lastUnderruns
            lastUnderruns = s.underruns
            val thresholdFrames = (sampleRate * 0.2).toLong() // 200ms
            if (delta >= thresholdFrames) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - underrunWarnedAt >= 5_000L) {
                    underrunWarnedAt = now
                    val queuedFrames = frames.get() - s.framesSent
                    val verdict =
                        if (queuedFrames < sampleRate / 5) "UPSTREAM_STARVED" else "NATIVE_CONSUMED_FAST"
                    AppLog.w(
                        "UsbNew",
                        "UNDERRUN delta=$delta 帧(≈${delta * 1_000L / sampleRate}ms)" +
                            " sessionTotal=${s.underruns} sent=${s.framesSent}" +
                            " written=${frames.get()} queued=$queuedFrames verdict=$verdict",
                    )
                }
            }
            // sent=真正送出  written=已排队（两者恒等即 R179 症状回归）
            "ts sent=${s.framesSent} written=${frames.get()} " +
                "underrun=${s.underruns} xferErr=${s.transferErrors} disc=${s.disconnected}"
        }.getOrDefault("ts stats n/a")

    override val alive: Boolean
        get() = aliveFlag.get() && runCatching { !output.stats().disconnected }.getOrDefault(false)

    override val deviceName: String get() = runCatching { output.device.productName ?: "USB DAC" }.getOrDefault("USB DAC")

    override val hardwareVolumeSupported: Boolean get() = hwVolSupportedField

    override fun probeHardwareVolume(): Boolean = hwVolSupportedField

    override fun setHardwareVolume(permille: Int): Boolean {
        if (!hwVolSupportedField) return false
        return runCatching {
            val range = output.volumeRange ?: return false
            val db = range.minDb + (range.maxDb - range.minDb) * (permille.coerceIn(0, 1000) / 1000f)
            output.setVolumeDb(db)
        }.getOrDefault(false)
    }

    override fun setHardwareVolumeDb(db: Float): Boolean {
        if (!hwVolSupportedField) return false
        return runCatching { output.setVolumeDb(db) }.getOrDefault(false)
    }

    override fun start() {
        // start 已在 init 完成，这里幂等。
    }

    override fun write(data: ByteArray, off: Int, size: Int): Int {
        if (!aliveFlag.get() || pausedFlag) return -1
        val bps = bytesPerSample
        val totalSamples = size / bps
        if (totalSamples <= 0) return 0
        val need = totalSamples * 4 // int32
        if (pcmBuf.capacity() < need) {
            pcmBuf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
        }
        pcmBuf.clear()
        // 小端字节 → int32 左对齐（相对满幅）：16bit<<16, 24bit<<8, 32bit 原样。
        // [1.4.28·软件音量真生效] 旧实现 setSoftwareGain 只把值存进字段、从不参与运算，
        // 而 UsbVolume 在硬件音量探测失败时会回退到这条软件音量链路——结果拖音量条、
        // 按音量键完全没反应。这里在写端点前逐样本乘增益，音量立即生效。
        val gain = softwareGainValue.coerceIn(0f, 1f)
        var i = off
        val end = off + totalSamples * bps
        while (i < end) {
            val raw = when (bps) {
                2 -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                3 -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or ((data[i + 2].toInt() and 0xFF) shl 16)
                else -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or
                    ((data[i + 2].toInt() and 0xFF) shl 16) or ((data[i + 3].toInt() and 0xFF) shl 24)
            }
            // 有符号样本取低 bits 位，再左移到 int32 高位。
            val signed = when (bps) {
                2 -> (raw shl 16) shr 16
                3 -> (raw shl 8) shr 8
                else -> raw
            }
            val left = when (bps) {
                2 -> signed shl 16
                3 -> signed shl 8
                else -> signed
            }
            val scaled =
                if (gain >= 1f) {
                    left
                } else {
                    (left.toLong() * gain.toDouble()).toLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                }
            pcmBuf.putInt(scaled)
            i += bps
        }
        pcmBuf.flip()
        val wroteFrames = runCatching { output.write(pcmBuf, totalSamples / channels) }
            .getOrElse { t ->
                AppLog.w("UsbNew", "write failed: ${t.message}")
                aliveFlag.set(false)
                return -1
            }
        if (wroteFrames < 0) {
            aliveFlag.set(false)
            return -1
        }
        val wroteSamples = wroteFrames * channels
        frames.addAndGet(wroteFrames.toLong())
        return wroteSamples * bps
    }

    override fun pause() {
        pausedFlag = true
        // TrueSample 无单独 pause：写静音帧保活（上层 reconcile 会持续喂静音）。
    }

    override fun resume() {
        pausedFlag = false
    }

    override fun flush() {
        // [R155/R162] 旧实现调 drain()，语义是"等 ring 里的数据全部播完"（最多 600ms）。
        // seek/切歌时这会把旧位置的声音继续播完，用户感知为"拖进度条卡死"。
        // 改为 nativeFlush：直接丢弃未发送的数据。
        runCatching { output.flush() }
    }

    override fun setSoftwareGain(gain: Float) {
        // TrueSample 在 native 侧无逐包增益钩子；音量交给 DAC 硬件音量（setHardwareVolume）
        // 或上层 DSP。这里记录增益供读回链路一致/UI 显示，不引入二次量化。
        softwareGainValue = gain
    }

    override fun softwareGain(): Float = softwareGainValue

    override fun abort() {
        runCatching { output.stop() }
    }

    override fun close() {
        aliveFlag.set(false)
        runCatching { output.close() }
        AppLog.i("UsbNew", "新独占已关闭")
    }

    companion object {
        /**
         * 打开新引擎独占。返回 null 表示不支持/失败，由调用方走旧引擎或降级。
         * 与 [UsbDac.open] 用同一台 DAC 查找逻辑（UsbManager 权限已由上层确认）。
         */
        fun open(
            manager: UsbManager, device: UsbDevice, rate: Int, channels: Int, preferHigh: Boolean,
            targetBits: Int = 0,
        ): TrueSampleSink? {
            if (!UsbAudioOutput.isUsbAudioOutput(device)) {
                AppLog.w("UsbNew", "设备非 USB Audio 输出")
                return null
            }
            val output = runCatching { UsbAudioOutput.open(manager, device) }.getOrElse { t ->
                AppLog.w("UsbNew", "新引擎 open 失败: ${t.message}")
                return null
            }
            // 用 plan 选一个 DAC 能吃下的模式；优先 bit-perfect，其次最高采样率。
            // [v24·bit-perfect] 零损失模式传入源位深，让 plan 按源位深出流（16bit 源不再被
            // 扩到 24bit），plan.bitPerfect 才可能为 true、上层逐字节直传才成立。
            val bits = if (targetBits > 0) targetBits else if (preferHigh) 24 else 16
            val plan = runCatching { output.plan(rate, bits, channels) }.getOrNull()
            if (plan == null) {
                AppLog.w("UsbNew", "新引擎 plan 失败，rate=$rate bits=$bits ch=$channels")
                output.close()
                return null
            }
            val bps = when {
                plan.bitPerfect && plan.dacBits <= 16 -> UsbDac.SAMPLE_BYTES_2
                plan.bitPerfect && plan.dacBits <= 24 -> UsbDac.SAMPLE_BYTES_3
                else -> UsbDac.SAMPLE_BYTES_4
            }
            AppLog.i(
                "UsbNew",
                "plan rate=$rate→${plan.outputRate} dacBits=${plan.dacBits} bitPerfect=${plan.bitPerfect} " +
                    "resampled=${plan.resampled} outBps=$bps",
            )
            return runCatching { TrueSampleSink(output, channels, bps, rate) }.getOrElse { t ->
                AppLog.w("UsbNew", "新引擎 Sink 构造失败: ${t.message}")
                runCatching { output.close() }
                null
            }
        }
    }
}
