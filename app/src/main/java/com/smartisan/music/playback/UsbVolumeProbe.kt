package com.smartisan.music.playback

import android.os.SystemClock
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * [1.4.32·A2/A4/R153-补③/R161②] 硬件音量的"音频域闭环"验证探针。
 *
 * 为什么需要它：probe 双电平写回读（L2）只能证明"控制传输写进去、回读跟随"，
 * 证明不了"DAC 真的把这个音量作用在播放输出上"——TEARS DSP 那类假阳性 DAC 回读跟随
 * 但输出不变（R156/R161）。唯一能证伪的手段是**播一个已知电平的测试音，让人耳/测量确认
 * 音量写入真的改变了响度**，即台账 R161② 的"音频域闭环 L3"。
 *
 * 实现约束（防错优先）：
 *  - 只在**暂停态**注入测试音：此时 ExoPlayer 不写 Sink，避免两个线程并发写同一 native
 *    handle / 共享 pcmBuf（LoudnessEnhancer 之外的又一处并发陷阱）。
 *  - 测试音固定 440Hz 正弦、-20 dBFS（台账 A2 指定），时长 2 秒，前后 flush，不残留。
 *  - 写入走现有 [UsbDac.Sink.write]，按 sink 的 bytesPerSample 生成左对齐 PCM，
 *    软件增益置满（测的是硬件音量，不让软件增益干扰）。
 *
 * L3 结论由用户确认（App 无法自动测 DAC 模拟输出电平）——这正是"音频域闭环"里
 * '测输出电平'这一步在纯软件侧的落地：用户耳朵或外接测量就是那个 detector。
 * 确认通过后 [markL3Verified] 才把硬件音量升级为可支撑零损失直传的级别。
 */
object UsbVolumeProbe {

    private const val TAG = "UsbVolProbe"
    private const val TONE_HZ = 440.0
    private const val TONE_DB = -20f
    private const val TONE_SECONDS = 2

    /** 音频域闭环验证状态。 */
    enum class L3State {
        /** 本会话未做 L3 验证。 */
        NOT_DONE,
        /** 测试音正在播放。 */
        PLAYING,
        /** 已播放，等待用户确认"音量写入是否真的改变了响度"。 */
        AWAIT_CONFIRM,
        /** 用户确认音量写入真实生效。 */
        VERIFIED,
        /** 用户确认音量没变化（假阳性 DAC）。 */
        FAILED,
    }

    private val _state = MutableStateFlow(L3State.NOT_DONE)
    val state: StateFlow<L3State> = _state

    @Volatile
    private var toneJob: Job? = null

    /**
     * 注入 -20dB 测试音 2 秒。要求调用方保证当前处于暂停态（设置页在开/usb 配置态调用）。
     * @return true=已开始播放（结果需用户确认）；false=无 sink / 正在播放 / 已在播放测试音。
     */
    fun playTestTone(scope: CoroutineScope): Boolean {
        if (_state.value == L3State.PLAYING) return false
        val sink = UsbDacSinkRef.current
        if (sink == null) {
            AppLog.w(TAG, "L3 测试音无法播放：没有活跃 UAC sink（需先开 USB 独占）")
            return false
        }
        // 生成 -20dBFS 440Hz 正弦 PCM，按 sink 位深左对齐。
        val bps = sink.bytesPerSample
        val rate = sink.sampleRate
        val channels = sink.channels
        val amp = (10.0.pow(TONE_DB / 20.0)).toFloat() // 0.1 (-20dBFS)
        val frames = (rate * TONE_SECONDS).coerceAtMost(rate * 4)
        val pcm = ByteArray(frames * channels * bps)
        val phaseStep = 2.0 * PI * TONE_HZ / rate
        for (f in 0 until frames) {
            val s16 = (sin(f * phaseStep) * amp * 32767.0).toInt()
            for (c in 0 until channels) {
                val off = (f * channels + c) * bps
                when (bps) {
                    2 -> {
                        pcm[off] = (s16 and 0xFF).toByte()
                        pcm[off + 1] = ((s16 shr 8) and 0xFF).toByte()
                    }
                    3 -> {
                        val s24 = s16 shl 8
                        pcm[off] = (s24 and 0xFF).toByte()
                        pcm[off + 1] = ((s24 shr 8) and 0xFF).toByte()
                        pcm[off + 2] = ((s24 shr 16) and 0xFF).toByte()
                    }
                    else -> {
                        val s32 = s16 shl 16
                        pcm[off] = (s32 and 0xFF).toByte()
                        pcm[off + 1] = ((s32 shr 8) and 0xFF).toByte()
                        pcm[off + 2] = ((s32 shr 16) and 0xFF).toByte()
                        pcm[off + 3] = ((s32 shr 24) and 0xFF).toByte()
                    }
                }
            }
        }
        _state.value = L3State.PLAYING
        AppLog.state(
            "SET", "l3ToneStart", "UsbVolumeProbe.playTestTone",
            mapOf("db" to TONE_DB, "hz" to TONE_HZ, "seconds" to TONE_SECONDS, "rate" to rate, "bps" to bps, "ch" to channels),
        )
        toneJob?.cancel()
        toneJob = scope.launch(Dispatchers.IO) {
            runCatching {
                sink.flush()
                // 软件增益置满：这一步测的是硬件音量写入，不能被软件增益掩盖。
                sink.setSoftwareGain(1f)
                val chunk = 4096 * channels * bps
                var off = 0
                while (off < pcm.size) {
                    val n = minOf(chunk, pcm.size - off)
                    val wrote = sink.write(pcm, off, n)
                    if (wrote <= 0) break
                    off += wrote
                }
                delay((TONE_SECONDS * 1000L))
            }
            runCatching { sink.flush() }
            if (_state.value == L3State.PLAYING) _state.value = L3State.AWAIT_CONFIRM
            AppLog.i(TAG, "L3 测试音播放结束，等待用户确认音量写入是否改变响度")
        }
        return true
    }

    /**
     * 用户确认 L3 结果。pass=true 表示"播放中调音量确实改变了响度"→ 音频域闭环通过。
     */
    fun confirmL3(pass: Boolean) {
        val prev = _state.value
        _state.value = if (pass) L3State.VERIFIED else L3State.FAILED
        AppLog.state(
            "SET", "l3Confirm", "UsbVolumeProbe.confirmL3",
            mapOf("old" to prev.name, "pass" to pass, "new" to _state.value.name),
        )
    }

    /** 会话关闭/引擎切换时重置，避免把上一台 DAC 的 L3 结论带到新设备。 */
    fun reset() {
        toneJob?.cancel()
        toneJob = null
        if (_state.value != L3State.NOT_DONE) {
            AppLog.i(TAG, "L3 状态重置：${_state.value} -> NOT_DONE")
        }
        _state.value = L3State.NOT_DONE
    }

    fun isL3Verified(): Boolean = _state.value == L3State.VERIFIED
}
