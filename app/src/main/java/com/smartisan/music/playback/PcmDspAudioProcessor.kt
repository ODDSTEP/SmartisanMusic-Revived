@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.smartisan.music.diagnostics.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [1.4.17·普通模式DSP] 把自研 [PcmDsp] 包装成 Media3 [AudioProcessor]，
 * 注入到普通（非 USB 独占）播放链的 DefaultAudioSink。
 *
 * 背景：此前 PcmDsp.process() 唯一调用点是 UsbExclusiveAudioSink.convert()（USB 独占写盘前），
 * 普通播放走系统 AudioTrack + PlaybackAudioFxController 的系统 EQ，与 PcmDsp 无关——
 * 于是出现「设置里选了 Salt 30 预设，普通模式却毫无变化」的假实现。
 *
 * 本处理器挂在 DefaultAudioSink 的音频处理链上，普通模式（PCM 经 delegate 到 AudioTrack）
 * 必然经过；USB 独占时 PCM 分流到 UAC sink、不经过 DefaultAudioSink，天然不会双处理。
 *
 * 关键设计：
 * 1. 兼容 16bit 与 float 两种输入（AudioOutputPlanner 对非 highRes 源会下发 16bit）。
 * 2. 音效关闭时 [queueInput] 原样字节级透传，保证 bit-perfect 零改动。
 * 3. [isActive] 恒 true，保证设置 flow 中途切换预设/开关即时生效（media3 只在
 *    configure 时缓存 active 状态，若依赖 PcmDsp.enabled 则中途开关不会生效）。
 * 4. [reset] 清 IIR 历史，防止换曲尾音串场。
 */
internal class PcmDspAudioProcessor : AudioProcessor {

    // media3 把 AudioFormat/EMPTY_BUFFER/UnhandledAudioFormatException 都定义为 AudioProcessor
    // 的嵌套类型，Kotlin 需显式 import 限定名。
    private companion object {
        val EMPTY_BUFFER: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    }

    private var encoding: Int = C.ENCODING_PCM_FLOAT
    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var bytesPerFrame: Int = 0

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var floatBuffer = FloatArray(0)
    // [1.4.23·音效可观测] 处理前的原始样本副本，用于计算 DSP 前后的 RMS/峰值变化。
    private var beforeBuffer = FloatArray(0)
    private var ended = false
    private var configured = false
    private var lastLogAtMs = 0L
    private var lastStatsMs = 0L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // [1.4.23·高解析DSP] 24bit(packed,3字节/样本) 是 DFF→FLAC 88.2kHz 等曲目的解码输出。
        // 旧版只认 16bit/float，24bit 会抛 UnhandledAudioFormatException——而 media3 的
        // AudioProcessingPipeline.configure() 无 try/catch，异常直接冒泡导致整个处理器被跳过，
        // 于是高解析曲目上 EQ/混响/压限/音效全部失效。这里补上 24bit 支持，读写时做
        // packed24 ↔ float 线性转换，让高解析曲目也能跑全套自研 DSP。
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT -> Unit
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(channelCount * 2)
        configured = true
        // 通知音效控制器：自研 DSP 已进入普通播放链，系统 EQ/BassBoost 应让位避免叠加。
        PcmDsp.audioProcessorActive = true
        AppLog.i(
            "PcmDsp",
            "普通模式 DSP 注入生效 enc=$encoding sr=$sampleRate ch=$channelCount bpf=$bytesPerFrame",
        )
        // 输出格式与输入一致（不改变采样率/声道/位深），由后续 resampler/AudioTrack 处理。
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0 || bytesPerFrame <= 0) {
            outputBuffer = EMPTY_BUFFER
            return
        }
        val frames = remaining / bytesPerFrame
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = EMPTY_BUFFER
            return
        }
        ensureOutputCapacity(remaining)

        // [1.4.23·噪声根治] BASS PCM 链路已用 BASS_FX 施加同一套 EQ/压限/混响/ReplayGain，
        // 这里必须字节级透传，绝不能再跑一遍自研 biquad——否则两条 DSP 链叠加。
        // [1.4.23·独立音效] 透传条件由旧的"仅看 EQ(enabled)"改为"任一效果激活(hasActiveEffect)"：
        // 旧逻辑把"原声 EQ + 单独开压限/音乐厅/V3/声道平衡/ReplayGain/显式混响"统统挡在
        // 处理链外，导致这些开关毫无作用。现在只有"完全无任何效果"才字节透传（bit-perfect）。
        if (PcmDsp.bassPcmOwnsDsp || !PcmDsp.hasActiveEffect()) {
            outputBuffer.clear()
            outputBuffer.put(inputBuffer.slice())
            outputBuffer.flip()
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val sampleCount = frames * channelCount
        if (floatBuffer.size < sampleCount) floatBuffer = FloatArray(sampleCount)
        if (beforeBuffer.size < sampleCount) beforeBuffer = FloatArray(sampleCount)
        // [1.4.23·噪声根治·真因] ByteBuffer.slice() 会把字节序重置为 BIG_ENDIAN，
        // 不继承父 buffer（Media3 送入的是 LITTLE_ENDIAN）。若不显式设回，下面
        // getShort()/getFloat() 会把每个样本的高低字节读反（0x1234→0x3412），
        // 结果就是满耳白噪音。原声走上面的 put(ByteBuffer) 按字节拷贝、不解析
        // short，所以不受影响——正好对上「只有原声正常、切 EQ 全是噪音」。
        val read = inputBuffer.slice().order(inputBuffer.order())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    floatBuffer[i] = read.getShort() / 32768f
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    floatBuffer[i] = read.getFloat()
                }
            }
            C.ENCODING_PCM_24BIT -> {
                // packed 24bit：3 字节/样本、小端、有符号，归一化到 [-1,1)。
                var b = 0
                for (i in 0 until sampleCount) {
                    val v = (read.get(b).toInt() and 0xFF) or
                        ((read.get(b + 1).toInt() and 0xFF) shl 8) or
                        ((read.get(b + 2).toInt() and 0xFF) shl 16)
                    floatBuffer[i] = if (v and 0x800000 != 0) {
                        (v or 0xFF000000.toInt()).toFloat() / 8388608f
                    } else {
                        v.toFloat() / 8388608f
                    }
                    b += 3
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())

        // [1.4.23·音效可观测] 保存处理前样本，用于对比 DSP 前后的 RMS/峰值。
        val doStats = android.os.SystemClock.elapsedRealtime() - lastStatsMs >= 1000L
        if (doStats) System.arraycopy(floatBuffer, 0, beforeBuffer, 0, sampleCount)

        PcmDsp.process(floatBuffer, frames, channelCount, sampleRate)

        // [1.4.18·EQ可观测] 每秒一条，用户可从日志直接确认「均衡器正在处理音频」。
        // [1.4.23·音效可观测] 升级为「全效果快照 + 处理前后 RMS/峰值对比」：
        // 用户切任意开关后，下一秒日志就显示每个效果的开/关/强度和信号变化量，
        // 不再靠耳朵猜。ΔRMS 明显≠0 就证明效果真实改变了信号。
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastLogAtMs >= 1000L) {
            lastLogAtMs = nowMs
            val stats = if (doStats) {
                lastStatsMs = nowMs
                PcmDsp.sampleStats(beforeBuffer, floatBuffer, sampleCount, frames, channelCount)
            } else null
            AppLog.i(
                "PcmDsp",
                "音效处理中 ${PcmDsp.describeFxSnapshot()} sr=$sampleRate ch=$channelCount" +
                    (if (stats != null) " | $stats" else ""),
            )
        } else if (doStats) {
            lastStatsMs = nowMs
            PcmDsp.sampleStats(beforeBuffer, floatBuffer, sampleCount, frames, channelCount)
        }

        outputBuffer.clear()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    val pcm = (floatBuffer[i] * 32768f).toInt().coerceIn(-32768, 32767)
                    outputBuffer.putShort(pcm.toShort())
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    outputBuffer.putFloat(floatBuffer[i])
                }
            }
            C.ENCODING_PCM_24BIT -> {
                // packed 24bit 写回：饱和到 ±8388607，拆成 3 字节小端。
                for (i in 0 until sampleCount) {
                    val v = (floatBuffer[i] * 8388608f).toInt().coerceIn(-8388608, 8388607) and 0xFFFFFF
                    outputBuffer.put((v and 0xFF).toByte())
                    outputBuffer.put(((v shr 8) and 0xFF).toByte())
                    outputBuffer.put(((v shr 16) and 0xFF).toByte())
                }
            }
        }
        outputBuffer.flip()
    }

    private fun ensureOutputCapacity(size: Int) {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun getOutput(): ByteBuffer = outputBuffer

    override fun isEnded(): Boolean = ended && !outputBuffer.hasRemaining()

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        ended = false
    }

    override fun reset() {
        outputBuffer = EMPTY_BUFFER
        ended = false
        configured = false
        if (PcmDsp.audioProcessorActive) {
            PcmDsp.resetState()
        }
    }
}
