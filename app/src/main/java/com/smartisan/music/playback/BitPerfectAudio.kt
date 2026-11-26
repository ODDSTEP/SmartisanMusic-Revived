@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.smartisan.music.diagnostics.AppLog

/**
 * 高保真音频输出工厂。
 *
 * 针对“高品质音源播放出现杂音 / 噪声 / 无法播放”的处理（参考 bit-perfect 播放器实现）：
 *  1. **float 输出**：解码器统一输出归一化浮点 PCM。源文件为 24bit（packed）时，
 *     若被按 16bit 直接写出会变成极强的白噪声；float 输出让整条链路以 32bit 浮点处理，
 *     彻底避开 packed-24bit 的位对齐问题。
 *  2. **禁用硬件直通（offload）**：offload 模式下音频绕过混音器直接送硬件解码，
 *     与音频效果链（均衡器 / 音量均衡）组合在大量机型上会爆音甚至静音失败。
 *     禁用后统一走软件解码 + PCM 输出，兼容性最好。
 *  3. **加大输出缓冲**：高码率音源（如 24bit/192kHz FLAC 接近 9Mbps）默认缓冲容易
 *     underrun，表现为周期性“嘟嘟声”。这里按最小缓冲的 4 倍取值，且不超过系统上限。
 *  4. **输出规格降级链**（[AudioOutputPlanner]）：AudioTrack 能接受的采样率有上限
 *     （多数设备 192kHz，超过就创建失败），浮点输出也不是所有机型都支持。
 *     先探测再配置，让 DXD / 384kHz 这类文件"降级播出"而不是"播不出来"。
 */
class BitPerfectRenderersFactory(
    context: Context,
    /** 是否尝试 USB 独占（UAC 直出）。每次 configure 时读取，开关可即时生效。 */
    private val usbExclusiveEnabled: () -> Boolean = { false },
    /** UAC 会话中途死掉（拔线 / 传输失败）时回调，由上层重建输出。 */
    private val onUsbExclusiveLost: () -> Unit = {},
    /** UAC 会话建立时回调（上层据此抑制误报的 NOISY 广播）。 */
    private val onUsbExclusiveOpened: () -> Unit = {},
) : DefaultRenderersFactory(context) {

    init {
        // 硬件解码器不支持的格式（个别无损/高码率文件）回退软件解码，避免“直接播放不出来”。
        setEnableDecoderFallback(true)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        // [1.4.23·高解析DSP根治] 必须关闭 float 输出。
        // media3 1.10.1 DefaultAudioSink.configure 铁证（javap）：shouldUseFloatOutput=true 时
        // 走 float 分支只 add(ToFloatPcmAudioProcessor)，**不 addAll(自定义 AudioProcessor)**；
        // 只有非 float 分支才把 setAudioProcessors 注入的 PcmDspAudioProcessor 加入处理链。
        // 高解析曲目（24bit）会触发 float 分支 → 自研 DSP 的 configure() 从未被调用 →
        // EQ/混响/压限/声道平衡/ReplayGain 全部失效（日志：高解析曲目零"注入生效"）。
        // 关闭 float 后所有格式走非 float 分支，24bit 先由 media3 转 16bit 再过 DSP，音效全可用。
        // 注意：这只影响普通（非 USB 独占）播放——独占 PCM 走 UsbExclusiveAudioSink 独立处理，
        // 本就不经过 DefaultAudioSink，bit-perfect 卖点不受影响。
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioTrackBufferSizeProvider(BitPerfectBufferSizeProvider)
            // 用支持度提供器彻底关闭直通：ExoPlayer 内部会在曲目切换时重新设置 offload 模式，
            // 只在实例上 setOffloadMode(DISABLED) 会被覆盖。
            .setAudioOffloadSupportProvider { _, _ -> AudioOffloadSupport.DEFAULT_UNSUPPORTED }
            // [1.4.17·普通模式DSP] 把自研 PcmDsp（含 Salt 30 预设 EQ）挂进普通播放链。
            // 普通模式 PCM 经此 DefaultAudioSink → AudioTrack，必然过该处理器；
            // USB 独占时 PCM 分流到 UAC sink、不经此链，天然不双处理。
            .setAudioProcessors(arrayOf(PcmDspAudioProcessor()))
            .build()
        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        val monitoring = MonitoringAudioSink(sink, context.applicationContext)
        AudioOutputRouter.attach(monitoring)
        // 输出链：UAC 真独占 →（失败静默降级）→ 系统共享输出（MonitoringAudioSink 负责规格降级）
        return UsbExclusiveAudioSink(
            context = context,
            delegate = monitoring,
            exclusiveEnabled = usbExclusiveEnabled,
            onUacLost = onUsbExclusiveLost,
            onUacOpened = onUsbExclusiveOpened,
        )
    }
}

/** 缓冲区放大策略：最小缓冲 × 4，同时保证至少 480ms 的 PCM 数据量。 */
private object BitPerfectBufferSizeProvider : DefaultAudioSink.AudioTrackBufferSizeProvider {

    private const val TARGET_DURATION_MS = 480

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        val scaled = (minBufferSizeInBytes * 4).coerceAtLeast(minBufferSizeInBytes)
        val durationBytes = if (sampleRate > 0 && pcmFrameSize > 0) {
            ((sampleRate.toLong() * TARGET_DURATION_MS / 1000L) * pcmFrameSize)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
        val target = maxOf(scaled, durationBytes)
        // 缓冲区必须包含整数个帧
        val frames = if (pcmFrameSize > 0) target / pcmFrameSize else target
        return (frames * pcmFrameSize).coerceAtLeast(minBufferSizeInBytes)
    }
}

/**
 * 输出规格规划：在真正创建 AudioTrack 之前，用 `AudioTrack.getMinBufferSize` 探测
 * 哪些「采样率 × PCM 编码」组合本机确实能开出来，按优先级排成一串候选。
 *
 * 这样 DXD（352.8kHz）/ 384kHz 这类超出设备上限的文件会降级到 192kHz 或设备原生采样率播出，
 * 而不是直接抛 AudioTrack 初始化失败 → 整首歌"播不出来"。
 */
internal object AudioOutputPlanner {

    private const val MinSampleRateHz = 4_000
    private const val MaxSampleRateHz = 192_000

    private val probeCache = HashMap<String, Boolean>()
    private val cacheLock = Any()

    /** 按优先级返回候选配置；第一个元素即为首选。 */
    fun attempts(format: Format): List<Format> {
        if (!MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) return listOf(format)
        if (!Util.isEncodingLinearPcm(format.pcmEncoding)) return listOf(format)
        if (format.sampleRate <= 0 || format.channelCount <= 0) return listOf(format)
        val mask = channelMaskOf(format.channelCount) ?: return listOf(format)

        val highRes = Util.isEncodingHighResolutionPcm(format.pcmEncoding)
        val encodings =
            if (highRes) {
                intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
            } else {
                intArrayOf(AudioFormat.ENCODING_PCM_16BIT)
            }

        val result = ArrayList<Format>(6)
        for (rate in candidateRates(format.sampleRate)) {
            for (encoding in encodings) {
                if (!supported(rate, mask, encoding)) continue
                val candidate = format.buildUpon()
                    .setSampleRate(rate)
                    .setPcmEncoding(exoEncodingOf(encoding))
                    .build()
                if (result.none { it.sampleRate == rate && it.pcmEncoding == candidate.pcmEncoding }) {
                    result += candidate
                }
            }
        }
        // 全部探测失败时给一个最保守的组合，让上层拿到"能播"的结果而不是崩溃。
        if (result.isEmpty()) {
            result += format.buildUpon()
                .setSampleRate(candidateRates(format.sampleRate).last())
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build()
        }
        return result
    }

    fun describe(format: Format): String =
        "${format.sampleRate}Hz/${format.channelCount}ch/pcm=${format.pcmEncoding}"

    private fun candidateRates(sampleRate: Int): List<Int> {
        val ordered = LinkedHashSet<Int>()
        fun add(rate: Int) {
            ordered += rate.coerceIn(MinSampleRateHz, MaxSampleRateHz)
        }
        add(sampleRate)
        add(MaxSampleRateHz)
        add(nativeOutputSampleRateHz())
        add(48_000)
        add(44_100)
        return ordered.toList()
    }

    private fun nativeOutputSampleRateHz(): Int {
        val manager = AudioOutputRouter.audioManager
        val raw = runCatching {
            manager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        }.getOrNull()
        return raw?.toIntOrNull()?.takeIf { it > 0 } ?: 48_000
    }

    private fun channelMaskOf(channelCount: Int): Int? = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        4 -> AudioFormat.CHANNEL_OUT_QUAD
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> null
    }

    @SuppressLint("WrongConstant")
    private fun supported(sampleRate: Int, channelMask: Int, encoding: Int): Boolean {
        val key = "$sampleRate|$channelMask|$encoding"
        synchronized(cacheLock) {
            probeCache[key]?.let { return it }
        }
        val ok = runCatching {
            AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        }.getOrDefault(AudioTrack.ERROR) > 0
        synchronized(cacheLock) { probeCache[key] = ok }
        return ok
    }

    private fun exoEncodingOf(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT
        AudioFormat.ENCODING_PCM_8BIT -> C.ENCODING_PCM_8BIT
        AudioFormat.ENCODING_PCM_16BIT -> C.ENCODING_PCM_16BIT
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> C.ENCODING_PCM_24BIT
        AudioFormat.ENCODING_PCM_32BIT -> C.ENCODING_PCM_32BIT
        else -> C.ENCODING_PCM_16BIT
    }
}

/**
 * 输出路由：把「首选输出设备」（USB 独占时用得上）下发到所有已创建的 AudioSink，
 * 并保存 AudioManager 供采样率探测复用。
 */
internal object AudioOutputRouter {

    @Volatile
    var audioManager: AudioManager? = null
        private set

    private val sinks = LinkedHashSet<AudioSink>()
    private val lock = Any()

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun bind(context: Context) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
    }

    fun attach(sink: AudioSink) {
        synchronized(lock) { sinks += sink }
        preferredDevice?.let { device ->
            runCatching { sink.setPreferredDevice(device) }
        }
    }

    fun clear() {
        synchronized(lock) { sinks.clear() }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        val snapshot = synchronized(lock) { sinks.toList() }
        snapshot.forEach { sink ->
            runCatching {
                sink.setPreferredDevice(device)
            }.onFailure { error ->
                AppLog.e("AudioOut", "set preferred device failed", error)
            }
        }
    }
}

/**
 * AudioSink 装饰器：把内部回调转发给 ExoPlayer 自身监听器的同时，
 * 旁路记录真实输出参数（[AudioOutputMonitor]）与 underrun / sink 错误日志，
 * 并在配置阶段按 [AudioOutputPlanner] 做规格降级。
 */
internal class MonitoringAudioSink(
    private val delegate: AudioSink,
    context: Context,
) : AudioSink by delegate {

    private val appContext = context.applicationContext

    private var externalListener: AudioSink.Listener? = null

    override fun setListener(listener: AudioSink.Listener) {
        externalListener = listener
        delegate.setListener(forwardingListener)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        AppLog.i(
            "AudioOut",
            "preferred device -> ${audioDeviceInfo?.let { describeDevice(it) } ?: "default"}",
        )
        delegate.setPreferredDevice(audioDeviceInfo)
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        AudioOutputRouter.bind(appContext)
        val attempts = AudioOutputPlanner.attempts(format)
        var lastError: Throwable? = null
        for ((index, candidate) in attempts.withIndex()) {
            try {
                delegate.configure(candidate, specifiedBufferSize, outputChannels)
                if (index == 0) {
                    AppLog.i(
                        "AudioOut",
                        "configure: ${AudioOutputPlanner.describe(candidate)} buffer=$specifiedBufferSize",
                    )
                } else {
                    AppLog.w(
                        "AudioOut",
                        "output downgraded: requested ${AudioOutputPlanner.describe(format)}" +
                            " -> ${AudioOutputPlanner.describe(candidate)} (attempt $index)",
                    )
                }
                return
            } catch (error: Throwable) {
                lastError = error
                AppLog.w(
                    "AudioOut",
                    "configure rejected ${AudioOutputPlanner.describe(candidate)}: ${error.message}",
                )
            }
        }
        AppLog.e("AudioOut", "configure failed for ${AudioOutputPlanner.describe(format)}", lastError)
        lastError?.let { throw it }
    }

    private fun describeDevice(device: AudioDeviceInfo): String =
        "type=${device.type} id=${device.id} name=${device.productName}"

    private val forwardingListener = object : AudioSink.Listener {
        override fun onPositionDiscontinuity() {
            externalListener?.onPositionDiscontinuity()
        }

        override fun onPositionAdvancing(positionUs: Long) {
            externalListener?.onPositionAdvancing(positionUs)
        }

        override fun onUnderrun(bufferSize: Int, bufferSizeUs: Long, elapsedSinceLastFeedMs: Long) {
            AppLog.w(
                "AudioOut",
                "underrun: buffer=$bufferSize bytes ($bufferSizeUs us), gapMs=$elapsedSinceLastFeedMs",
            )
            externalListener?.onUnderrun(bufferSize, bufferSizeUs, elapsedSinceLastFeedMs)
        }

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
            externalListener?.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        }

        override fun onOffloadBufferEmptying() {
            externalListener?.onOffloadBufferEmptying()
        }

        override fun onOffloadBufferFull() {
            externalListener?.onOffloadBufferFull()
        }

        override fun onAudioSinkError(error: Exception) {
            AppLog.e("AudioOut", "audio sink error: ${error.message}", error)
            externalListener?.onAudioSinkError(error)
        }

        override fun onAudioCapabilitiesChanged() {
            externalListener?.onAudioCapabilitiesChanged()
        }

        override fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) {
            AudioOutputMonitor.onAudioTrackInitialized(config)
            externalListener?.onAudioTrackInitialized(config)
        }

        override fun onAudioTrackReleased(config: AudioSink.AudioTrackConfig) {
            AppLog.d(
                "AudioOut",
                "track released: ${config.sampleRate}Hz ${AudioOutputMonitor.bitDepthLabelOf(config.encoding)}",
            )
            externalListener?.onAudioTrackReleased(config)
        }

        override fun onSilenceSkipped() {
            externalListener?.onSilenceSkipped()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            externalListener?.onAudioSessionIdChanged(audioSessionId)
        }
    }
}
