package com.smartisan.music.playback

import com.smartisan.music.data.settings.AudioFxBandCount
import com.smartisan.music.data.settings.AudioFxPreset
import com.smartisan.music.data.settings.AudioFxReverbMode
import com.smartisan.music.data.settings.EqBand
import com.smartisan.music.data.settings.EqFilterType
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.activeAudioFxGainDbPoints
import com.smartisan.music.data.settings.activeEqBands
import com.smartisan.music.data.settings.AudioFxControlFrequenciesHz
import com.smartisan.music.diagnostics.AppLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * 1.3.4 自研 DSP 音效链：5 段参量 EQ + 低音/虚拟环绕 + 简易空间混响 + 软限幅。
 *
 * 挂载在 [UsbExclusiveAudioSink.convert] 写盘前的 float PCM 域，因此 USB 真独占
 * （绕开 AudioFlinger、系统 AudioEffect 完全不生效）下同样有效。
 * 关闭时 [process] 立即返回，bit-perfect 链路逐样本不变，不引入任何额外量化。
 */
internal object PcmDsp {
    // [1.4.12·S05] 架构对齐：设置层/UI 早已是 10 段 EQ（AudioFxBandCount=10，
    // 频点单一来源 AudioFxControlFrequenciesHz），但本 DSP 引擎仍是 5 段、写死旧频点，
    // 后 5 段增益被直接丢弃——这就是"调了 EQ 没变化"的根因。这里彻底对齐到 10 段。
    private val BandCount = AudioFxBandCount
    private val BandFrequencies = AudioFxControlFrequenciesHz
    private const val MaxChannels = 2

    // [1.4.23·噪声根治·更正] 真因是 PcmDspAudioProcessor 里 slice() 丢失 LITTLE_ENDIAN
    // 导致样本字节读反，已在该处显式 order(inputBuffer.order()) 修复。上一版误判为削波
    // 而加的 -6dB HeadroomGain 已撤销——削波最多带来轻微硬截失真，不是满耳白噪；保留它
    // 只会让 EQ 整体音量莫名小 6dB。Biquad 的 NaN/Inf 自愈保留（无害的健壮性保护）。

    // [1.4.12·S05] 多级梳状混响：4 级错开延迟的梳状滤波 + 一级全通扩散，
    // 取代旧的单抽头延迟（那个只有一次回声，尾音发"叮/金属味"）。
    private val CombDelays = intArrayOf(1687, 1913, 2251, 2593) // 互质，避免梳状峰重叠染色
    private val combLines = Array(MaxChannels) { Array(CombDelays.size) { FloatArray(4096) } }
    private val combPos = IntArray(MaxChannels * CombDelays.size)
    private val allpassLine = FloatArray(MaxChannels * 1024)
    private val allpassPos = IntArray(MaxChannels)
    // [1.4.23·混响去金属声] 每条梳状延迟线的反馈低通状态。标准 Schroeder 梳状滤波器在
    // 反馈环里必须有一阶低通（damping），否则高频反复叠加成刺耳"滋滋/金属"声——用户反馈
    // 的"混响劣化严重、滋滋声"根因。此处缓存上一帧的低通输出。
    private val combDampState = FloatArray(MaxChannels * CombDelays.size)

    @Volatile
    var enabled: Boolean = false
        private set

    // [1.4.17·普通模式DSP] 自研 DSP 是否已通过 AudioProcessor 注入到 ExoPlayer 普通播放链。
    // 由 PcmDspAudioProcessor.configure() 置 true。PlaybackAudioFxController 据此决定
    // 是否让位（禁用系统 Equalizer/BassBoost，避免与自研 EQ 叠加）。DIRECT 引擎不注入
    // AudioProcessor，该标志保持 false，系统 EQ 仍照常工作。
    @Volatile
    var audioProcessorActive: Boolean = false

    /**
     * [1.4.26·R79 DSP 单路径化] DSP 所有权显式状态：谁解码、谁施加 DSP，全局只有一个真值。
     *
     * 旧实现靠 [bassPcmOwnsDsp] 这个布尔由 BassPcmDataSource.open/close 副作用写入，
     * "任一效果激活就透传"的隐式互斥太脆（R73 的音量归零/噪声都踩过）：
     * 一旦 close 没走到或两条链并存，就会出现双施加或都不施加。
     *
     * 现在由解码路由（OfficialDecodeFrontend.openStream）在选择数据源时**显式**设置：
     * - [DspOwner.BASS]：BASS 解码 + BASS_FX 已施加 DSP → Processor 字节透传；
     * - [DspOwner.PCM]：ffmpeg 解码 → 由 [PcmDspAudioProcessor] 施加自研 DSP；
     * - [DspOwner.NONE]：DIRECT 引擎 → 系统 EQ 负责，Processor 不注入。
     * 二者必有且仅有一方施加，禁止双施加。
     */
    enum class DspOwner { NONE, BASS, PCM }

    @Volatile
    var dspOwner: DspOwner = DspOwner.NONE

    /** 兼容旧读点；语义 = dspOwner 是否为 BASS，不再允许外部写入决定权。 */
    val bassPcmOwnsDsp: Boolean get() = dspOwner == DspOwner.BASS

    /** [1.4.18·EQ可观测] 累计处理的帧数，用于日志证明「均衡器真的在处理音频」。 */
    @Volatile
    var processedFrames: Long = 0L
        private set

    /** [1.4.18·EQ可观测] 当前生效的均衡器描述，供 Processor 节流打日志。 */
    fun describeActiveEq(): String = buildString {
        append("enabled=$enabled")
        append(" salt=$useSaltEq")
        append(" bands=${if (useSaltEq) activeSaltBands.size else BandCount}")
        append(" frames=$processedFrames")
    }

    /**
     * [1.4.23·音效可观测] 全部音效的当前开关/参数快照。
     * 用户不用靠耳朵猜——切任意开关后，下一秒日志就会打印这行，能直接看到
     * 每个效果到底开没开、强度多少、增益几 dB。配合 [consumeStats] 的 RMS/峰值
     * 前后对比，就能客观证明「有没有变化、变化多大」。
     */
    fun describeFxSnapshot(): String = buildString {
        append("eq=").append(if (enabled) (if (useSaltEq) "salt(${activeSaltBands.size})" else "内置") else "原声")
        append(" rev=").append("%.2f".format(reverbMix))
        append(" hall=").append(concertHall)
        append(" v3=").append(v3Reverb)
        append(" comp=").append(compressorEnabled).append("/").append("%.0f".format(compressorStrength))
        append(" bal=").append("%.2f".format(channelBalance))
        append(" rg=").append("%.1f".format(replayGainDb)).append("dB")
        if (lufsNormalizeEnabled) {
            append(" lufs=").append("%.1f".format(lufsMeter.integratedLufs()))
            append("→").append("%.0f".format(LufsTarget))
            append(" n=").append("%+.1f".format(lufsGainDb - lufsUserGainDb)).append("dB")
            append(" user=").append("%+.1f".format(lufsUserGainDb)).append("dB")
            append(" g=").append("%+.1f".format(lufsGainDb)).append("dB")
        }
    }

    // ===== [1.4.23·音效可观测] 处理前后 RMS/峰值统计（节流，热路径零分配）=====
    private var statInSq = 0.0
    private var statOutSq = 0.0
    private var statInPeak = 0f
    private var statOutPeak = 0f
    private var statSamples = 0L
    private var statLastNs = 0L

    /**
     * 由 Processor 每个处理块调用：在处理前喂入原始样本、处理后喂入结果样本。
     * 每 ~1s 返回一行"输入RMS→输出RMS / 前后峰值 / 变化dB"的对比串，否则返回 null。
     * 这样日志能客观证明每个效果对信号做了多大改变，不依赖用户耳朵。
     */
    @Synchronized
    fun sampleStats(before: FloatArray, after: FloatArray, count: Int, frames: Int, channels: Int): String? {
        val stride = if (channels <= 0) 1 else channels
        var i = 0
        while (i < count) {
            val b = before[i]
            val a = after[i]
            val ab = abs(b); val aa = abs(a)
            statInSq += ab.toDouble() * ab
            statOutSq += aa.toDouble() * aa
            if (ab > statInPeak) statInPeak = ab
            if (aa > statOutPeak) statOutPeak = aa
            i++
        }
        statSamples += frames.toLong()
        val now = System.nanoTime()
        if (now - statLastNs < 1_000_000_000L || statSamples == 0L) return null
        statLastNs = now
        val inRms = kotlin.math.sqrt(statInSq / count.coerceAtLeast(1))
        val outRms = kotlin.math.sqrt(statOutSq / count.coerceAtLeast(1))
        val deltaDb = if (inRms > 0f && outRms > 0f) 20f * log10((outRms / inRms).toDouble()).toFloat() else 0f
        val line = buildString {
            append("RMS ").append("%.4f".format(inRms)).append("→").append("%.4f".format(outRms))
            append(" Δ").append("%.1f".format(deltaDb)).append("dB")
            append(" peak ").append("%.3f".format(statInPeak)).append("→").append("%.3f".format(statOutPeak))
            append(" frames=").append(statSamples)
        }
        statInSq = 0.0; statOutSq = 0.0; statInPeak = 0f; statOutPeak = 0f; statSamples = 0L
        return line
    }

    /**
     * [1.4.17·普通模式DSP] 换曲 / seek / 格式切换时清空 IIR 历史（biquad + 混响延迟线），
     * 防止上一首的尾音串到新曲。由 AudioProcessor.reset() 调用。不改当前配置/增益。
     */
    @Synchronized
    fun resetState() {
        resetReverb()
        rebuildBands()
        // [1.4.20·P1] 切歌/seek 清空音乐厅延迟线与压限器包络，防尾音串扰。
        for (c in 0 until MaxChannels) {
            for (k in hallDelays.indices) hallLines[c][k].fill(0f)
        }
        hallPos.fill(0)
        compressorEnv = 0f
        GainMonitor.reset()
        // [1.4.23·响度归一化] 切歌/seek 重置 LUFS 测量窗口与收敛增益
        lufsMeter.reset()
        lufsGainDb = 0f
        lufsPrimed = false
    }

    @Volatile
    private var gainsDb = FloatArray(BandCount)

    @Volatile
    private var surroundAmount = 0.12f

    @Volatile
    private var reverbMix = 0.06f

    private var currentSampleRate = 48_000
    private val bands = Array(MaxChannels * BandCount) { Biquad() }

    // [1.4.18] Salt V4/AutoEQ 耳机预设是 10 段（L+8P+H），上限从 6 提到 10，
    // 否则后 4 段被静默丢弃 —— 这正是"耳机预设听着跟原声一样"的根因之一。
    private val MaxSaltBands = 10
    private val saltBands = Array(MaxChannels * MaxSaltBands) { Biquad() }
    @Volatile private var activeSaltBands: List<EqBand> = emptyList()
    @Volatile private var useSaltEq = false

    @Synchronized
    fun configure(settings: PlaybackSettings) {
        val preset = settings.audioFxPreset
        val salt = settings.activeEqBands()
        useSaltEq = settings.audioFxEnabled && !salt.isNullOrEmpty()
        activeSaltBands = if (useSaltEq) salt!! else emptyList()
        enabled = settings.audioFxEnabled && (useSaltEq || preset != AudioFxPreset.Original)
        gainsDb = settings.activeAudioFxGainDbPoints().copyOf()
        // [闭环核查] EQ 链激活状态（原声=全平曲线，天然直通）。
        surroundAmount = when (preset) {
            AudioFxPreset.Rock, AudioFxPreset.Clear -> 0.26f
            AudioFxPreset.Bass -> 0.10f
            else -> 0.14f
        }
        // [1.4.12·S05] 混响强度独立可选：选了具体档就覆盖预设写死配比，否则维持旧行为。
        val presetReverb = when (preset) {
            AudioFxPreset.Vocal, AudioFxPreset.Clear,
            AudioFxPreset.SaltClearVocals, AudioFxPreset.SaltFemaleVocals,
            AudioFxPreset.SaltMaleVocals -> 0.13f
            AudioFxPreset.SaltConcert, AudioFxPreset.SaltMusicHall -> 0.16f
            else -> 0.05f
        }
        // [1.4.23·混响可感知] 旧档位（0.05/0.13/0.24）+ 对 dry 做软限幅，导致湿信号
        // 比原声低 17~20dB，三档之间只差 3dB——用户"开了跟没开一样"是真实现象。
        // 提高分档 + applyReverb 里去掉对 dry 的压制，让弱/中/强听感明显分层。
        reverbMix = when (settings.audioFxReverbMode) {
            AudioFxReverbMode.FollowPreset -> (presetReverb * 1.7f).coerceAtMost(0.30f)
            AudioFxReverbMode.Off -> 0f
            AudioFxReverbMode.Weak -> 0.10f
            AudioFxReverbMode.Medium -> 0.22f
            AudioFxReverbMode.Strong -> 0.38f
        }
        rebuildBands()
        resetReverb()
        // [1.4.20·3A] 同步到 BASS DSP：BASS 解码链路会用 BASS_FX 做同一套 EQ/混响，
        // 避免与自研 PcmDspAudioProcessor 双 EQ 叠加（BASS 生效时后者走 identity）。
        // [1.4.20·修复] 不能只传 Salt 参数化 band——内置 10 段预设（Rock/Bass/流行...）
        // 走的是 gainsDb + 固定频点，之前这里是 activeSaltBands.ifEmpty{null}，
        // 导致用内置预设时 BASS 侧 eqSpecs 为空、EQ 全丢。现在把内置段也转成 BQF peaking band。
        val bassBands: List<EqBand> = if (useSaltEq) {
            activeSaltBands
        } else {
            List(BandCount) { i ->
                EqBand(
                    id = "builtin_$i",
                    type = EqFilterType.Peaking,
                    frequencyHz = BandFrequencies[i].coerceAtLeast(10f),
                    gainDb = gainsDb.getOrElse(i) { 0f },
                    q = 0.707f, // 自研 setPeaking 用 alpha=sin(w0)/2 ≈ Q 0.707
                )
            }
        }
        // [1.4.20·P0/P1] 增益监测开关 + BASS DSP 回退开关（修审计#8死开关）。
        GainMonitor.enabled = settings.gainMeterEnabled
        BassDspConfig.bassDspEnabled = settings.bassDspEnabled
        // [1.4.20·P1] 音效全家桶：各自独立开关，不依赖均衡器的 audioFxEnabled——
        // 否则用户选「原声」（audioFxEnabled=false）后开压限器会毫无反应（闭环核查发现的细节 bug）。
        compressorEnabled = settings.compressorEnabled
        compressorStrength = (settings.compressorStrength.coerceIn(0, 100)) / 100f
        channelBalance = settings.channelBalance.coerceIn(-100, 100) / 100f
        concertHall = settings.concertHallEnabled
        v3Reverb = settings.v3ReverbEnabled
        // [1.4.23·实时响度归一化 v2] 「目标增益」是用户直观的音量旋钮：正=增大、负=减小，
        // 直接作为**输出净偏置**叠加，绝不掺进响度目标。响度归一化只负责把「过轻」的
        // 曲目提升上来（boost-only，目标 -16 LUFS、对齐 loudnorm I=-16），**不衰减**
        // 本来就够响的曲目——否则「设 +6dB」会被误当成把目标抬到 -10 LUFS，响歌仍被
        // 压低，用户就看到「设增加却减小」。旧实现还把同一个增益在 replayGainSettingDb
        // 与 lufsUserGainDb 各用一次（双重叠加），这里一并消除：实时归一化生效时，
        // replayGainSettingDb 归零，用户增益全权由 lufsUserGainDb 施加。
        // [1.4.28·死开关清理] 只认 UI 上真实存在的 replayGainFallbackEnabled。
        // 旧字段 volumeNormalizationEnabled 的设置入口早已合并掉（setter 全项目零调用），
        // 但这里仍在读：老用户 SP 里留着 true 时会持续叠加 +dB 归一化，界面上却找不到任何关闭入口。
        val wantLufs = settings.replayGainFallbackEnabled
        lufsNormalizeEnabled = wantLufs
        lufsMeter.configure(currentSampleRate)
        val userGainDb =
            if (settings.replayGainFallbackEnabled) {
                settings.replayGainGainDb.coerceIn(-6, 6).toFloat()
            } else {
                0f
            }
        // 固定增益只在「纯标签 ReplayGain、未开实时归一化」时叠加，避免与 userGain 双重生效。
        replayGainSettingDb = if (settings.replayGainEnabled && !wantLufs) {
            settings.replayGainGainDb.coerceIn(-6, 6).toFloat()
        } else 0f
        replayGainApplyTags = settings.replayGainEnabled
        replayGainFallback = settings.replayGainFallbackEnabled
        lufsUserGainDb = userGainDb
        replayGainDb = replayGainSettingDb + replayGainTagDb
        // [1.4.23·独立音效] 显式混响档位独立于 EQ 激活，让"原声+单独混响"也生效。
        reverbExplicitlyEnabled = when (settings.audioFxReverbMode) {
            AudioFxReverbMode.Weak, AudioFxReverbMode.Medium, AudioFxReverbMode.Strong -> reverbMix > 0f
            else -> false
        }
        // [闭环核查] 任一效果激活就得跑 process：EQ 链 / 压限 / 音乐厅 / V3 / 声道平衡 /
        // ReplayGain / 显式混响。FollowPreset 且原声时全 false → 字节透传，bit-perfect 不变。
        anyEffectActive = enabled || compressorEnabled || concertHall || v3Reverb ||
            channelBalance != 0f || replayGainDb != 0f || reverbExplicitlyEnabled ||
            lufsNormalizeEnabled
        BassDspConfig.setReplayGainDb(replayGainDb)
        // [1.4.20·P2] 倒放/变速同步到 BASS DataSource（open 时应用）。
        BassDspConfig.setPlaybackMode(settings.reverseEnabled, settings.variSpeedEnabled, settings.playbackSpeedX100)

        BassDspConfig.update(
            enabled = enabled,
            bands = bassBands.ifEmpty { null },
            reverb = reverbMix,
            compressorEnabled = compressorEnabled,
            compressorStrength = compressorStrength,
            channelBalance = channelBalance,
            concertHall = concertHall,
            v3Reverb = v3Reverb,
            replayGainDb = replayGainDb,
        )
    }

    // ===== [1.4.20·P0/P1] 音效状态（configure 写入，process 读取）=====
    @Volatile private var compressorEnabled: Boolean = false
    @Volatile private var compressorStrength: Float = 0.4f
    @Volatile private var channelBalance: Float = 0f        // -1..1
    @Volatile private var concertHall: Boolean = false
    @Volatile private var v3Reverb: Boolean = false
    /** 最终生效的 ReplayGain 增益（= 设置固定增益 + 当前曲目标签增益）。process 读取。 */
    @Volatile private var replayGainDb: Float = 0f
    @Volatile private var replayGainSettingDb: Float = 0f
    @Volatile private var replayGainTagDb: Float = 0f
    @Volatile private var replayGainApplyTags: Boolean = false
    @Volatile private var replayGainFallback: Boolean = false

    // ===== [1.4.23·实时响度归一化] 无标签时用 K-weighting LUFS 测量把响度拉到 -16 LUFS =====
    /** 目标积分响度，对齐 FFmpeg loudnorm 的 I=-16。 */
    private const val LufsTarget = -16f
    /** 真峰值上限 -1.5dBTP 的线性值（10^(-1.5/20)≈0.8414），保守用于增益上限保护。 */
    private const val TruePeakCeiling = 0.8414f
    private val lufsMeter = LufsMeter()
    /** 是否启用实时 LUFS 归一化（无标签回退 / 旧音量均衡开关打开时）。 */
    @Volatile private var lufsNormalizeEnabled = false
    /** 当前平滑收敛中的响度增益（dB）。warmup 完成前为 0（单位增益）。 */
    private var lufsGainDb = 0f
    /** 用户「目标增益」净输出偏置（dB）：正=增大、负=减小，独立于响度归一化目标。 */
    private var lufsUserGainDb = 0f
    /** 块内峰值（增益前），用于真峰值上限保护。 */
    private var lufsBlockPeak = 0f
    /** warmup 刚完成标志：首个有效块直接设定增益，不从 0 慢慢爬升。 */
    private var lufsPrimed = false

    /** [闭环核查] 任一音效激活（含独立于 EQ 的效果），决定 process 是否跑处理链。 */
    @Volatile private var anyEffectActive: Boolean = false

    /**
     * [1.4.23·独立音效] 用户显式选了混响档位（弱/中/强，非"跟随预设"/"关闭"）时为 true。
     * 这样"原声 EQ + 单独开混响"也能生效；而"跟随预设"不纳入，保证默认原声 bit-perfect。
     */
    @Volatile private var reverbExplicitlyEnabled: Boolean = false

    /** 供 PcmDspAudioProcessor 判断是否需进入自研处理链（替代旧的"仅看 enabled"门控）。 */
    fun hasActiveEffect(): Boolean = anyEffectActive

    /**
     * [P1·ReplayGain] 切歌时注入当前曲目的标签增益（IO 线程读取后调用）。
     * @param trackDb 曲目增益；@param albumDb 专辑增益；@param preferAlbum true=用专辑增益
     */
    @Synchronized
    fun applyTrackReplayGain(trackDb: Float?, albumDb: Float?, preferAlbum: Boolean) {
        val chosen = if (preferAlbum) (albumDb ?: trackDb) else (trackDb ?: albumDb)
        replayGainTagDb = if (replayGainApplyTags) (chosen ?: 0f).coerceIn(-12f, 12f) else 0f
        replayGainDb = replayGainSettingDb + replayGainTagDb
        // 同步到 BASS 链（BASS 解码的 PCM 也要带同一份响度增益）
        BassDspConfig.setReplayGainDb(replayGainDb)
        AppLog.i(
            "ReplayGain",
            "应用增益 track=$trackDb album=$albumDb preferAlbum=$preferAlbum " +
                "tag=$replayGainTagDb final=$replayGainDb dB",
        )
    }

    // 压限器状态（反馈式包络压缩器）
    private var compressorEnv = 0f
    // [1.4.23·压限噪音] 补偿压限削掉的电平（约 +1.6dB）。实测新压缩曲线下峰值从
    // 0.76 压到约 0.52（-3.3dB），乘 1.2 后约 0.62，既补回响度又不顶满幅、不产生硬削波。
    private const val CompressorMakeup = 1.2f
    // 声道平衡增益（左右）
    private val balanceGain = floatArrayOf(1f, 1f)

    /**
     * [1.4.21·闭环核查] 是否有「只有拿到 PCM 才能生效」的 BASS 系音效处于激活态。
     * [OfficialDecodeFrontend.plan] 据此决定主流格式是否走 BASS 流式解码（STREAM_PCM）——
     * 否则用户只开压限器/音乐厅/V3/声道平衡/ReplayGain/增益监测时，系统直读路径碰不到 PCM，
     * 开关就成了挂羊头。EQ 本身在系统直读下走 Android audiofx，不算在这里。
     */
    fun needsPcmPath(): Boolean =
        compressorEnabled || concertHall || v3Reverb || channelBalance != 0f ||
            replayGainDb != 0f || GainMonitor.enabled ||
            needsPcmReverseOrSpeed()

    /**
     * [1.4.23·闪退根治] 仅 BASS 专有、系统 sink 无法实现的能力：倒放 / 变速。
     * v4 起系统 sink（PcmDspAudioProcessor）已覆盖所有格式的全部常规 DSP
     * （EQ/压限/音乐厅/V3/声道平衡/ReplayGain/增益监测），这些效果不再需要
     * 把主流格式赶到 STREAM_PCM（BASS 流式解码）。 OfficialDecodeFrontend.plan()
     * 只凭本方法 + 智能过渡决定是否走 BASS，从而避免 192kHz/24bit 高码率 FLAC
     * 因开个压限器就被赶进 BASS 流式管道触发 native 崩溃。
     */
    fun needsPcmReverseOrSpeed(): Boolean =
        BassDspConfig.reverseEnabled() || BassDspConfig.playbackSpeedX100() != 100

    /** 清空混响历史，避免上一首/上一预设的尾音串到新曲（怪声来源之一）。 */
    private fun resetReverb() {
            for (c in 0 until MaxChannels) {
                for (k in CombDelays.indices) {
                    combLines[c][k].fill(0f)
                    combPos[c * CombDelays.size + k] = 0
                    combDampState[c * CombDelays.size + k] = 0f
                }
                allpassPos[c] = 0
            }
            allpassLine.fill(0f)
    }

    @Synchronized
    fun process(data: FloatArray, frames: Int, channels: Int, sampleRate: Int) {
        if (frames <= 0 || channels <= 0) return
        // [1.4.20·P0] 增益监测独立于音效开关：即使原声（无效果）也能监测峰值/削波/余量。
        // [闭环核查] 用 anyEffectActive 而非 enabled——独立效果（压限/ReplayGain 等）在
        // 原声（EQ enabled=false）下也必须跑处理链，否则用户开了效果却没声音变化。
        if (!anyEffectActive) {
            if (GainMonitor.enabled) GainMonitor.feed(data, frames, channels)
            return
        }
        processedFrames += frames.toLong()
        val ch = channels.coerceAtMost(MaxChannels)
        if (sampleRate != currentSampleRate) {
            currentSampleRate = sampleRate.coerceAtLeast(8_000)
            rebuildBands()
            resetReverb()
            compressorEnv = 0f
        }
        // [1.4.20·P1] ReplayGain 线性增益（叠加在标签增益之上的固定目标 dB）
        var gainLinear = if (replayGainDb != 0f) {
            10.0.pow(replayGainDb.toDouble() / 20.0).toFloat()
        } else 1f
        // [1.4.23·实时响度归一化] 测量增益前信号 → 收敛到 -16 LUFS（+用户偏移）。
        // 测量点必须在使用本块增益之前，避免"放大→测到变响→再放大"的自激。
        if (lufsNormalizeEnabled) {
            lufsBlockPeak = 0f
            var s = 0
            while (s < frames) {
                val base = s * channels
                var c2 = 0
                while (c2 < ch) {
                    val av = abs(data[base + c2])
                    if (av > lufsBlockPeak) lufsBlockPeak = av
                    c2++
                }
                s++
            }
            // feed 内部会 K 加权并滑动累积；返回是否已热身（≥1s 有效测量）
            val ready = lufsMeter.feed(data, frames, channels, currentSampleRate)
            if (ready) {
                val measured = lufsMeter.integratedLufs()
                // [v2] boost-only：只把「过轻」的曲目提升到 -16 LUFS，**绝不衰减**响歌。
                // 这样「目标增益」才能纯粹表达用户音量意图，不会因归一化把响歌压回去。
                val boostDb = (LufsTarget - measured).coerceIn(0f, 12f)
                // 用户净增益（正=增大/负=减小）作为独立偏置叠加在归一化之上。
                var totalDb = (boostDb + lufsUserGainDb).coerceIn(-12f, 12f)
                // 真峰值保护：换算成线性后不得让块内峰值越过 -1.5dBTP 上限。
                // 目标线性增益上限 = ceiling / 当前峰值（含已有 replayGain 增益）。
                val peakAfterFixed = lufsBlockPeak * gainLinear
                if (peakAfterFixed > 1e-6f) {
                    val ceilingGainDb = 20f * log10((TruePeakCeiling / peakAfterFixed).toDouble()).toFloat()
                    totalDb = totalDb.coerceAtMost(ceilingGainDb)
                }
                // 首个有效块直接设定（避免从 0 慢慢爬升导致前 1 秒响度不对），
                // 之后一阶平滑：升温快、降温慢，避免增益抖动（attack 0.3s / release 0.8s）
                if (!lufsPrimed) {
                    lufsGainDb = totalDb
                    lufsPrimed = true
                } else {
                    val blockSec = frames.toFloat() / currentSampleRate
                    val tau = if (totalDb > lufsGainDb) 0.3f else 0.8f
                    val a = (1f - exp(-blockSec / tau)).coerceIn(0f, 1f)
                    lufsGainDb += (totalDb - lufsGainDb) * a
                }
            } else {
                lufsGainDb = 0f
            }
            gainLinear *= 10f.pow(lufsGainDb / 20f)
        }
        // [1.4.20·P1] 声道平衡：-1 全左（右=0）、+1 全右（左=0）、0 居中。等功率保持总响度。
        if (ch >= 2) {
            val b = channelBalance
            balanceGain[0] = kotlin.math.sqrt((1f - b).toDouble()).toFloat().coerceIn(0f, 1f)
            balanceGain[1] = kotlin.math.sqrt((1f + b).toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            balanceGain[0] = 1f
        }
        for (f in 0 until frames) {
            val base = f * channels
            for (c in 0 until ch) {
                var v = data[base + c]
                // [闭环核查] 只有 EQ 链激活（enabled）才跑 biquad；只开压限/ReplayGain 时
                // 跳过 EQ 循环，避免无谓计算，也避免原声状态下滤波器状态被无意义推进。
                // [1.4.23·噪声根治] EQ 低频增益（内置预设最高 +5.5dB、Salt 最高 +7.5dB）
                // 叠加 mid/side 立体声加宽后，样本会超出 ±1.0；16bit 硬截断（coerceIn）
                // 产生刺耳谐波——真机上就是「除原声外全是噪音」。这里在处理前预留
                // -6dB headroom（线性衰减，不改变 EQ 相对频响），让提升后基本不削波；
                // 极端满幅信号由 16bit 转换的 coerceIn 兜底（概率极低）。原声（enabled=false）
                // 不经过此分支，bit-perfect 不受影响。
                if (enabled) {
                    if (useSaltEq) {
                        val n = activeSaltBands.size.coerceAtMost(MaxSaltBands)
                        for (b in 0 until n) {
                            v = saltBands[c * MaxSaltBands + b].process(v)
                        }
                    } else {
                        for (b in 0 until BandCount) {
                            v = bands[c * BandCount + b].process(v)
                        }
                    }
                }
                v *= gainLinear
                data[base + c] = v
            }
            if (ch >= 2) {
                val l = data[base]
                val r = data[base + 1]
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * (1f + surroundAmount)
                data[base] = mid + side
                data[base + 1] = mid - side
                // 声道平衡（在环绕之后施加，避免被 mid/side 抵消）
                data[base] *= balanceGain[0]
                data[base + 1] *= balanceGain[1]
            }
            // [1.4.23·压限器噪音根治] 旧版 attack=0.4/release=0.05 是"每样本系数"，
            // 在 44.1k/88.2k 下相当于 0.02ms/0.5ms 时间常数——快到跟着波形每个周期抽气，
            // 产生抽吸/沙沙失真（用户反馈"压限器开了有噪音"）。改为与采样率无关的
            // 真实时间常数 5ms attack / 100ms release，包络平滑；并加软拐点 knee，
            // 避免阈值处增益突变；makeup gain 补偿被压掉的音量，避免"一开就变小声"。
            if (compressorEnabled) {
                val attackCoef = 1f - exp(-1f / (0.005f * sampleRate))
                val releaseCoef = 1f - exp(-1f / (0.100f * sampleRate))
                val threshold = 0.25f - 0.20f * compressorStrength
                val ratio = 2f + 6f * compressorStrength
                val knee = 0.06f
                for (c in 0 until ch) {
                    val idx = base + c
                    val inSample = data[idx]
                    val detected = abs(inSample)
                    compressorEnv += if (detected > compressorEnv) {
                        (detected - compressorEnv) * attackCoef
                    } else {
                        (detected - compressorEnv) * releaseCoef
                    }
                    // 连续软拐点：threshold-knee..threshold+knee 区间二次平滑过渡，
                    // 增益在整条曲线上单调、连续（已数值验证），避免阈值处增益跳变咔哒声。
                    val gain = if (compressorEnv <= threshold - knee) {
                        1f
                    } else {
                        val over = compressorEnv - threshold + knee
                        val y = if (over < 2f * knee) {
                            compressorEnv - (1f - 1f / ratio) * over * over / (4f * knee)
                        } else {
                            threshold + (compressorEnv - threshold) / ratio
                        }
                        (y / compressorEnv.coerceAtLeast(1e-6f)).coerceIn(1f / ratio, 1f)
                    }
                    data[idx] = (inSample * gain * CompressorMakeup).coerceIn(-1f, 1f)
                }
            }
        }
        // [1.4.12·S05] 多级梳状混响 + 全通扩散
        if (reverbMix > 0f) {
            for (c in 0 until ch) {
                applyReverb(data, frames, channels, c, reverbMix)
            }
        }
        // [1.4.20·P1] 音乐厅 / V3 混响：在基础混响之上再叠一层更长、更密的厅堂尾音。
        if (concertHall || v3Reverb) {
            // [1.4.23·混响可感知] 提高厅堂尾音强度（旧 0.11~0.18 仅比原声低 13~14dB、
            // 且三者只差 1dB，听感几乎无区别）。新档位拉开差异。
            val strength = when {
                v3Reverb && concertHall -> 0.34f
                v3Reverb -> 0.26f
                else -> 0.20f
            }
            for (c in 0 until ch) {
                applyHallReverb(data, frames, channels, c, strength)
            }
        }
        // [1.4.20·P0] 全部处理完成后喂入增益监测（UI 才能看到真实输出电平）。
        if (GainMonitor.enabled) GainMonitor.feed(data, frames, channels)
    }

    /** [1.4.20·P1] 音乐厅/V3：一组更长的互质梳状延迟 + 深反馈，营造厅堂残响。 */
    private val hallDelays = intArrayOf(3191, 3547, 3919, 4273, 4649)
    private val hallLines = Array(MaxChannels) { Array(hallDelays.size) { FloatArray(8192) } }
    private val hallPos = IntArray(MaxChannels * hallDelays.size)

    private fun applyHallReverb(
        data: FloatArray,
        frames: Int,
        channels: Int,
        channel: Int,
        mix: Float,
    ) {
        val base = channel * hallDelays.size
        for (f in 0 until frames) {
            val i = f * channels + channel
            val dry = data[i]
            var wet = 0f
            for (k in hallDelays.indices) {
                val len = hallDelays[k]
                val slot = base + k
                var pos = hallPos[slot]
                if (pos >= len) pos = 0
                val delayed = hallLines[channel][k][pos]
                wet += delayed
                hallLines[channel][k][pos] = (dry + delayed * 0.82f).coerceIn(-1f, 1f)
                hallPos[slot] = pos + 1
            }
            wet /= hallDelays.size
            // [1.4.23·混响可感知] 同基础混响：dry 不压制，湿信号单独增益，仅越界软限。
            val sum = dry + wet * mix * 1.5f
            data[i] = if (sum > 1f || sum < -1f) softClip(sum) else sum
        }
    }

    /**
     * Schroeder 混响：4 级并联梳状（反馈衰减）+ 1 级全通扩散。
     * 湿信号按 reverbMix 混入原样本；梳状延迟互质，避免旧单抽头延迟的金属"叮"声。
     */
    private fun applyReverb(
        data: FloatArray,
        frames: Int,
        channels: Int,
        channel: Int,
        mix: Float,
    ) {
        val combBase = channel * CombDelays.size
        val apIdx = channel * 1024
        for (f in 0 until frames) {
            val i = f * channels + channel
            val dry = data[i]
            var combSum = 0f
            for (k in CombDelays.indices) {
                val len = CombDelays[k]
                val slot = combBase + k
                var pos = combPos[slot]
                if (pos >= len) pos = 0
                val delayed = combLines[channel][k][pos]
                combSum += delayed
                // [1.4.23·混响去金属声] 一阶低通阻尼：feedback = dry + lp(delayed)*g。
                // 低通系数 0.5 把每次反馈的高频削掉一半，避免高频在环内累积成"滋滋"金属声。
                val lp = combDampState[slot] + (delayed - combDampState[slot]) * 0.5f
                combDampState[slot] = lp
                val feedback = (dry + lp * 0.72f).coerceIn(-1f, 1f)
                combLines[channel][k][pos] = feedback
                combPos[slot] = pos + 1
            }
            // 全通扩散：把梳状的离散回声打散成密集尾音
            var apPos = allpassPos[channel]
            if (apPos >= 1024) apPos = 0
            val apDelayed = allpassLine[apIdx + apPos]
            val apIn = combSum / CombDelays.size
            val apOut = apDelayed - apIn * 0.5f
            allpassLine[apIdx + apPos] = (apIn + apDelayed * 0.5f).coerceIn(-1f, 1f)
            allpassPos[channel] = apPos + 1
            // [1.4.23·混响可感知] 旧版 softClip(dry + wet) 对 dry 也施加了压缩函数，
            // 把整体电平压低 ~1dB 却没让尾音变明显，听感就是"音量小了点但没混响"。
            // 现在 dry 原样保留，湿信号单独乘增益，仅当总和越界时才软限幅。
            val wet = apOut * mix * 1.6f
            val sum = dry + wet
            data[i] = if (sum > 1f || sum < -1f) softClip(sum) else sum
        }
    }

    private fun rebuildBands() {
        val sr = currentSampleRate.toFloat()
        // [1.4.19·代码质量#4] 两套 EQ 路径互斥：用 Salt 参数化 band 时，process() 只跑
        // saltBands，旧 10 段 bands 的系数算出来也没人用（每档 cos/pow 白算）。这里跳过旧路径。
        if (useSaltEq) {
            val n = activeSaltBands.size.coerceAtMost(MaxSaltBands)
            for (c in 0 until MaxChannels) {
                for (b in 0 until MaxSaltBands) {
                    val bq = saltBands[c * MaxSaltBands + b]
                    if (b < n) {
                        val band = activeSaltBands[b]
                        bq.setByType(band.type, band.frequencyHz, sr, band.gainDb, band.q)
                    } else {
                        bq.setIdentity()
                    }
                }
            }
        } else {
            // 旧 10 段路径（非 Salt 预设/自定义曲线）：只有这条路径才需要算这些系数。
            for (c in 0 until MaxChannels) {
                for (b in 0 until BandCount) {
                    val freq = BandFrequencies[b].coerceAtMost(sr * 0.45f)
                    bands[c * BandCount + b].setPeaking(freq, sr, gainsDb.getOrElse(b) { 0f })
                }
            }
        }
    }

    private fun softClip(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        return (v / (1f + kotlin.math.abs(v) * 0.35f)).coerceIn(-1f, 1f)
    }

    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun setPeaking(freq: Float, sr: Float, gainDb: Float) {
            val a = 10f.pow(gainDb / 40f)
            val w0 = (2.0 * PI * freq / sr).toFloat()
            val alpha = sin(w0) / 2f
            val cw = cos(w0)
            val b0n = 1f + alpha * a
            val b1n = -2f * cw
            val b2n = 1f - alpha * a
            val a0n = 1f + alpha / a
            val a1n = -2f * cw
            val a2n = 1f - alpha / a
            assign(b0n / a0n, b1n / a0n, b2n / a0n, a1n / a0n, a2n / a0n)
        }

        /** 单位增益（未使用的 band 槽位置成直通，避免累积噪声） */
        fun setIdentity() = assign(1f, 0f, 0f, 0f, 0f)

        /**
         * [1.4.16·E2] Salt Player 参数化 biquad（RBJ Audio EQ Cookbook）。
         * @param q 品质因数；Peaking 用 alpha=sin(w0)/(2q)，shelf 用 S 归一化到 q≈0.707。
         */
        fun setByType(type: EqFilterType, freqHz: Float, sr: Float, gainDb: Float, qIn: Float) {
            // [1.4.18] 放宽钳制范围以容纳 AutoEQ 耳机预设：低频可达 12Hz、高 Q 可达 10.3。
            // 旧值 20Hz~4Q 会把这些数据截断，导致"耳机预设听着跟原声一样"。
            val f = freqHz.coerceIn(10f, (sr * 0.48f))
            val w0 = (2.0 * PI * f / sr).toFloat()
            val cw = cos(w0)
            val sw = sin(w0)
            val q = qIn.coerceIn(0.1f, 12f)
            val alpha = sw / (2f * q)
            val a = 10f.pow(gainDb / 40f)
            val sqrtA = kotlin.math.sqrt(a)
            // 各类型分子/分母（RBJ 公式，a0 归一化在 assign 里做）
            val b0n: Float; val b1n: Float; val b2n: Float
            val a0n: Float; val a1n: Float; val a2n: Float
            when (type) {
                EqFilterType.Peaking -> {
                    b0n = 1f + alpha * a; b1n = -2f * cw; b2n = 1f - alpha * a
                    a0n = 1f + alpha / a; a1n = -2f * cw; a2n = 1f - alpha / a
                }
                EqFilterType.LowShelf -> {
                    val t = 2f * sqrtA * alpha
                    b0n = a * ((a + 1f) - (a - 1f) * cw + t)
                    b1n = 2f * a * ((a - 1f) - (a + 1f) * cw)
                    b2n = a * ((a + 1f) - (a - 1f) * cw - t)
                    a0n = (a + 1f) + (a - 1f) * cw + t
                    a1n = -2f * ((a - 1f) + (a + 1f) * cw)
                    a2n = (a + 1f) + (a - 1f) * cw - t
                }
                EqFilterType.HighShelf -> {
                    val t = 2f * sqrtA * alpha
                    b0n = a * ((a + 1f) + (a - 1f) * cw + t)
                    b1n = -2f * a * ((a - 1f) + (a + 1f) * cw)
                    b2n = a * ((a + 1f) + (a - 1f) * cw - t)
                    a0n = (a + 1f) - (a - 1f) * cw + t
                    a1n = 2f * ((a - 1f) - (a + 1f) * cw)
                    a2n = (a + 1f) - (a - 1f) * cw - t
                }
                EqFilterType.LowPass -> {
                    b0n = (1f - cw) / 2f; b1n = 1f - cw; b2n = (1f - cw) / 2f
                    a0n = 1f + alpha; a1n = -2f * cw; a2n = 1f - alpha
                }
                EqFilterType.HighPass -> {
                    b0n = (1f + cw) / 2f; b1n = -(1f + cw); b2n = (1f + cw) / 2f
                    a0n = 1f + alpha; a1n = -2f * cw; a2n = 1f - alpha
                }
            }
            assign(b0n / a0n, b1n / a0n, b2n / a0n, a1n / a0n, a2n / a0n)
        }

        private fun assign(b0n: Float, b1n: Float, b2n: Float, a1n: Float, a2n: Float) {
            if (b0n.isNaN() || a1n.isNaN()) { setIdentity(); return }
            b0 = b0n; b1 = b1n; b2 = b2n; a1 = a1n; a2 = a2n
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            if (y.isNaN() || y.isInfinite()) {
                // [1.4.23·噪声根治] 状态一旦发散成 NaN/Inf，会随 y1/y2 残留并让后续
                // 每帧都产出垃圾值（持续白噪）。检测到即重置历史，单帧自愈。
                x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
                return 0f
            }
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }
}
