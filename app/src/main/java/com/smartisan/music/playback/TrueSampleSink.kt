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
 * 数据流：上层 convert() 产出小端交错 PCM（enc 决定每样本 2/3/4 字节）→ 本适配器
 * 转成 TrueSample 要求的"左对齐 int32"交错 ByteBuffer → output.write()。
 */
internal class TrueSampleSink(
    private val output: UsbAudioOutput,
    override val channels: Int,
    override val enc: Int,
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
        val info = runCatching { output.start(sampleRate, bitsForEnc(enc), channels, ResampleQuality.BEST) }
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
        UsbDac.ENC_16 -> 16
        UsbDac.ENC_24 -> 24
        UsbDac.ENC_32 -> 32
        else -> 24
    }

    override val framesPlayed: Long get() = frames.get()

    override val stats: String
        get() = runCatching {
            val s = output.stats()
            "ts frames=${s.framesSent} underrun=${s.underruns} xferErr=${s.transferErrors} disc=${s.disconnected}"
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
        val bytesPerSample = when (enc) {
            UsbDac.ENC_16 -> 2
            UsbDac.ENC_24 -> 3
            UsbDac.ENC_32 -> 4
            else -> 4
        }
        val totalSamples = size / bytesPerSample
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
        val end = off + totalSamples * bytesPerSample
        while (i < end) {
            val raw = when (bytesPerSample) {
                2 -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                3 -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or ((data[i + 2].toInt() and 0xFF) shl 16)
                else -> (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8) or
                    ((data[i + 2].toInt() and 0xFF) shl 16) or ((data[i + 3].toInt() and 0xFF) shl 24)
            }
            // 有符号样本取低 bits 位，再左移到 int32 高位。
            val signed = when (bytesPerSample) {
                2 -> (raw shl 16) shr 16
                3 -> (raw shl 8) shr 8
                else -> raw
            }
            val left = when (bytesPerSample) {
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
            i += bytesPerSample
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
        return wroteSamples * bytesPerSample
    }

    override fun pause() {
        pausedFlag = true
        // TrueSample 无单独 pause：写静音帧保活（上层 reconcile 会持续喂静音）。
    }

    override fun resume() {
        pausedFlag = false
    }

    override fun flush() {
        runCatching { output.drain() }
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
            val enc = when {
                plan.bitPerfect && plan.dacBits <= 16 -> UsbDac.ENC_16
                plan.bitPerfect && plan.dacBits <= 24 -> UsbDac.ENC_24
                else -> UsbDac.ENC_32
            }
            AppLog.i(
                "UsbNew",
                "plan rate=$rate→${plan.outputRate} dacBits=${plan.dacBits} bitPerfect=${plan.bitPerfect} " +
                    "resampled=${plan.resampled} enc=$enc",
            )
            return runCatching { TrueSampleSink(output, channels, enc, rate) }.getOrElse { t ->
                AppLog.w("UsbNew", "新引擎 Sink 构造失败: ${t.message}")
                runCatching { output.close() }
                null
            }
        }
    }
}
