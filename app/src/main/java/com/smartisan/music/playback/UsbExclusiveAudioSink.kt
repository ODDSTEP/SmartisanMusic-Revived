@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * USB 独占输出：把 ExoPlayer 解码出来的 PCM 直接推给 USB DAC（UAC 等时传输），
 * **完全绕开 AudioFlinger 与系统重采样**。
 *
 * 工作方式：
 * - `configure()` 时若独占开关打开且 `UsbDac.open()` 成功 → 走 UAC 通道；
 * - 任何一步失败 → **静默降级**到内部的 `DefaultAudioSink`（系统共享输出），
 *   保证"不会没声音"；
 * - UAC 会话中途死掉（拔线 / 传输失败）→ 回调 [onUacLost]，由上层重建输出。
 *
 * 这是"真独占"与之前"只是把输出路由钉到 USB 设备"的最大区别：
 * 会话建立后系统里这块声卡已被摘掉，其它 App 拿不到它。
 */
internal class UsbExclusiveAudioSink(
    private val context: Context,
    private val delegate: AudioSink,
    private val exclusiveEnabled: () -> Boolean,
    private val onUacLost: () -> Unit,
    private val onUacOpened: () -> Unit = {},
) : AudioSink {

    private val appContext = context.applicationContext

    private var usbSink: UsbDac.Sink? = null
    private var usbFormat: Format? = null
    private var usbFrameBytes = 0
    private var usbChannels = 2
    private var usbSampleRate = 44_100

    /**
     * [1.4.24·R38 假独占根治] 最近一次 configure 收到的输出格式。
     *
     * 根因：UAC 会话唯一建立入口是 configure()，而 configure() 只在**输出格式变化**时
     * 才被 ExoPlayer 调用。用户打开独占开关时若正在播同格式的曲子，rebuildOutput 的
     * stop/prepare 不会触发 configure → 会话永远不建立 → 开关是假的（2026-11-24
     * 03:08:21 开独占 2 分 44 秒无 uac open，切到 88.2k 曲目才真正 claim）。
     *
     * 存下这个格式，开关打开时可主动用它重开会话，不再等下一次格式变化。
     */
    @Volatile private var lastConfiguredFormat: Format? = null
    @Volatile private var lastConfiguredChannels: IntArray? = null

    /**
     * 解码器输出的 PCM 编码。**不能假设是 float**：
     * MP3/ AAC 解码器输出 16-bit，FLAC 可能输出 24-bit，WAV 浮点才是 float。
     * 1.2.1 之前一律按 float 读 16-bit 样本 → NaN → `roundToInt` 抛
     * "Cannot round NaN value" → 播放器报错 → 表现为"播放失败/一直缓冲"。
     */
    private var usbInputEncoding = C.ENCODING_PCM_FLOAT
    private var usbInputBytesPerSample = 4

    /** 上次 flush 之后已写入的帧数 */
    private var framesWritten = 0L
    /** 上次 flush 时原生层已经播出的帧数（native 计数是单调的，位置要减掉它） */
    private var framesBase = 0L
    private var endOfStream = false

    /**
     * 进度位置锚点（1.2.6 修复独占进度条跳 00:00）。
     *
     * 旧实现用 `framesPlayed - framesBase`，这个值在每次 UAC 会话重建（开启独占、
     * 看门狗自愈、拔插复位）时都会归零，于是进度条反复跳到 00:00。
     * 现在改用**媒体时间**锚点：每次写入都拿到本帧的 `presentationTimeUs`（媒体时间，
     * 连续不随会话重建归零），以此建立锚点。即使 UAC 会话中途重建，只要下一帧的
     * presentationTimeUs 正确，进度就能无缝续上，不再归零。
     */
    private var anchorMediaUs: Long = 0L   // 锚点对应的媒体时间
    private var anchorFrames: Long = 0L    // 建立锚点那一刻的 native framesPlayed
    private var awaitAnchor: Boolean = true // 下一帧重新建立锚点
    private var paused: Boolean = false
    private var pausedPosUs: Long = 0L     // 暂停时冻结的进度
    private var uacLostReported = false
    /** UAC worker 线程是否已启动：只在真正播放时启动，避免暂停态空转抽干缓冲 */
    private var exclusiveStarted = false
    /** 1.3.4：播放器是否真的在播放。UAC 重建时不得凭空把 worker 拉起来。 */
    @Volatile private var playerPlaying: Boolean = false

    /**
     * [1.4.4 单一不变式] App 播放意图的唯一真值，由 onEvents(playWhenReady) / play() /
     * pause() / rebuildOutput 同步，**configure 不清零**（这是与 1.4.3 wasPlaying 的本质区别：
     * 1.4.3 在 configure 里无条件 start+resume 是「凭空出声」，这里只是如实记录 App 想不想播）。
     * worker 出声 = appWantsPlayback && !paused && 会话已建立，由 [reconcileWorker] 单点对齐。
     */
    @Volatile private var appWantsPlayback: Boolean = false

    /**
     * [1.4.5·清单 1/2·G-15/G-16 闭合·单一真值源]
     * **出声与否只由 [appWantsPlayback] 一个真值决定**（onEvents(playWhenReady) /
     * play()/pause() / rebuildOutput 汇入 [setPlayerPlaying]），[reconcileWorker] 是唯一
     * 把该真值确定性落实到 native worker 的地方。1.4.4 的病根是把 appWantsPlayback 与
     * Kotlin paused 绑成 AND、又让 native paused 由另一条路径清，三套状态没闭合；
     * 本版起 paused **只是** reconcile 内部同步给「进度冻结 / flush 语义」用的影子，
     * 绝不作为第二真值源参与出声判定。严禁再引入第二个「worker 是否启动」布尔。
     */
    /** [1.4.5·门禁 6] 上次对齐后记录的状态，用于「只在状态翻转时打日志」防刷屏。 */
    private var lastSoundStateLogged: Boolean? = null
    /** [1.4.5·门禁 6] 门槛丢弃的帧累计，用于可 grep 证明门槛真的被触发（不是 0 次）。 */
    private var gateDroppedFrames: Long = 0L
    /**
     * [1.4.10·G-51] 用户播放意图闸：true=明确暂停，false=未暂停/在播。
     * 由 [setPlayerPlaying] 唯一权威写入（playing -> !playing）。1.4.9 只由 pause()/play()
     * 写它，而真实暂停走 onEvents -> setPlayerPlaying(false) 从不经过 pause()，导致闸恒 false、
     * 门槛自愈反复拉起播放。configure/rebuild 中间态走 reconcileWorker 不写此闸，
     * 因此既能挡住真暂停误拉起，又不影响重建假暂停的 12 帧自愈。
     */
    @Volatile private var userExplicitlyPaused: Boolean = false
    /** [护栏 G-25·1.4.6 落地] 连续真实 PCM 帧流入达到此数即判定真值被污染并自愈（约 0.3 秒，无感）。
     *  不变式：门槛绝不无限丢帧；真暂停由 userExplicitlyPaused 守住，绝不因数据流误拉起（防幽灵播放）。
     *  严禁再退回 1.4.5「appWantsPlayback=false 一律丢帧且死等翻转事件」的死锁写法。 */
    private val GATE_DATAFLOW_RECOVERY_FRAMES = 12L

    /**
     * 后台/灭屏保活唤醒锁。
     *
     * 独占直出绕开了系统 AudioTrack，原本由 `DefaultAudioSink` 经 `setWakeMode(WAKE_MODE_LOCAL)`
     * 持有的 PARTIAL_WAKE_LOCK 在独占通道里根本不会生效。一旦 App 退到后台、屏幕熄灭，
     * CPU 与 USB 总线被系统挂起，原生送 URB 的 worker 线程被饿死、等时传输断粮 → DAC 欠载，
     * 用户就听到"断断续续的怪声"；切回前台调度恢复又正常。
     * 所以在 UAC 会话活跃且正在播放时，由这里自己持有这把锁，暂停/释放时交还。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 周期取证：每 10 秒把 UAC 会话的原生统计打进 AppLog（1.2.3），
     *  用户反馈"卡住/缓冲"时，日志里就有卡住瞬间的 spp/err/short/fb 等证据。 */
    private var lastStatsLogAt = 0L
    private var statsLogCount = 0L
    // [1.4.9·G-42] 后台/语音欠载真值：连续 played 不推进 + underrun 高 = 环写满但没出声。
    private var lastStatsPlayed = -1L
    private var silentStatsRuns = 0
    // [1.4.23·缓冲卡住根治] framesBase 在满 ring 恢复/flush 时被重置为当前 native 计数，
    // 导致 playedNow=framesPlayed-framesBase 骤降（如 100000→0），被 G-58 误判为"连续停摆"
    // → handleUacLost 重建 → 用户感知"本来能播突然一直缓冲、要拖进度条才恢复"。
    // 记录上周期的 framesBase 与 framesWritten：基准变化时跳过本周期判定并重置计数；
    // 真停摆时 framesWritten 也会停止递增，用它作辅助判据，避免重置瞬间误伤。
    private var lastStatsFramesBase = 0L
    private var lastStatsFramesWritten = -1L
    private var lastUnderrunRebuildAt = 0L

    private var audioAttributes: AudioAttributes? = null
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffect: AuxEffectInfo? = null
    private var offloadMode = AudioSink.OFFLOAD_MODE_DISABLED
    private var playerVolume = 1f
    private var streamOffsetUs = 0L
    private var preferredDevice: android.media.AudioDeviceInfo? = null
    private var listener: AudioSink.Listener? = null

    private var scratch = ByteArray(DEFAULT_SCRATCH)
    private var outBytes = ByteArray(DEFAULT_SCRATCH)
    // [1.4.19·代码质量#1] DSP 转换热路径复用缓冲，避免每帧分配。
    private var dspScratch = FloatArray(0)
    private lateinit var dspDup: ByteBuffer
    /** 打碟旁路专用的转换缓冲：与 ExoPlayer 写入线程分开，避免并发踩同一块数组 */
    private var scratchOutBytes = ByteArray(DEFAULT_SCRATCH)
    /** 打碟期间是否由本类临时接管 URB（已 resume 给刮擦音用）——见 [writeScratchFrames] */
    private var scratchStreaming = false
    /** 上次调用时观察到的 framesPlayed，用于判定 UAC 流是否真的在消费数据 */
    private var scratchLastPlayed = 0L
    /** 上次观察到 framesPlayed 前进的时刻（elapsedRealtime ms） */
    private var scratchLastProgressMs = 0L
    /** 上次因停摆而 resume 的时刻，用于限制重启发频率 */
    private var scratchLastRestartMs = 0L
    /**
     * [1.4.5·清单 3] 连续「满 ring 写不进导致丢帧」的 handleBuffer 次数。
     * 正常写满即清零；连续达到阈值做**本地确定性恢复**：清 ring + reconcile 按 App
     * 真值重新对齐 worker（1.4.4 曾在此主动 handleUacLost 让上层重建，重建后又回到
     * 同一死状态，形成满 ring 重建风暴，1.4.5 已改为本地恢复，收紧而非放宽）。
     */
    private var consecutiveWriteStalls = 0

    /**
     * 1.3.3：唱盘爆豆音在 USB 独占下的叠加通道。
     *
     * 独占时 USB 声卡已从 AudioFlinger 摘掉，UI 层爆豆音控制器的 SoundPool
     * 默认路由找不到这块设备，于是「设置里明明开了唱盘爆豆音却一点声音都没有」。
     * 这里不新开 URB、不接管流（那是打碟旁路 [writeScratchFrames] 的做法，会把音乐打断），
     * 而是在 [handleBuffer] 把解码 PCM 转成整数**之前**，把短音效叠加进同一帧音乐样本里，
     * 随音乐一起写进同一条 UAC 会话。framesWritten / 锚点 / paused 全部不受影响。
     *
     * 与 SoundPool 的 maxStreams=3 对齐，最多三层同时叠加，避免每次触发都把上一段
     * 从头覆盖（那样用户只能听到每段采样的开头一小截）。
     */
    private val popcornLock = Any()
    private val popcornLayers = ArrayDeque<PopcornLayer>()
    private var popcornLogged = false
    /** [v20·零损失] bit-perfect 路径首帧日志去重。 */
    private var zeroLossLogged = false
    /** [v20·零损失] "为什么没走直传"首帧日志去重。 */
    private var zeroLossMissLogged = false
    /** [v20·零损失] "强制旁路 DSP"首帧日志去重。 */
    private var zeroLossDspBypassLogged = false

    private class PopcornLayer(
        val pcm: ShortArray,
        val sampleRate: Int,
        val gain: Float,
        var cursor: Double = 0.0,
    )

    // [1.4.10·G-53] 源采样率 -> 就近 UAC 原生率。44.1k 族规整到 44100，48k 族规整到 48000。
    // [1.4.20·DSD根治] 关键修复：旧版把 88.2k/176.4k/352.8k 一律强降到 44100（8 倍/4 倍/2 倍
    // 粗暴降采样），这正是 DSD 独占怪声的根因——注释写着"保持原样"但代码却强降，自相矛盾。
    // BASS 的 libbassdsd 已把 DSD 解成 88.2kHz PCM（保真），这里必须保留 DSD 原生率族，
    // 交给 UsbDac 的 UAC alt 协商 / 新引擎 plan() 按 DAC 能力选：DAC 支持就原生输出，
    // 不支持再走设备侧重采样。只有在设备确实不支持该率时，pick() 才会按 alt 表就近回退。
    // [1.4.25·变调根治] 独占必须按音源采样率直出，绝不人为强降。
    // 旧逻辑把 96k/192k/384k 强降到 48k，但 convert() 逐样本写、Media3 按 format.sampleRate
    // 解码不做重采样（configure 未改写 format），导致 192k 数据以 48k 时钟播放 = 4 倍速变调。
    // 注释里"由解码侧重采样"从未真正发生。DSD 率族历史上也因强降到 44.1k 产生 8 倍速怪声，
    // 已在 1.4.20 改为保源率。这里把 PCM 高解析率族一并与源率对齐：
    //   - 设备支持该率（alt 协商命中）→ bit-perfect 原生直出，无变调；
    //   - 设备不支持 → UsbDac 的端点协商（rankCandidates）自然返回空 → open 失败 → 静默降级系统共享输出，
    //     虽非独占但音高正确，好过"变调的独占"。
    private fun negotiateUsbRate(source: Int): Int =
        if (source > 0) source else 44_100

    /** [R75] 源 PCM 位深（写进降级文案，明确"不支持的是哪个格式"）。 */
    private fun sourceBitDepth(format: Format): Int = when (format.pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    /**
     * [R75·降级原因细分] 把 [UsbDac.OpenFailure] 翻成设置页能直接看懂的一句话。
     *
     * 只有"设备确实没有能覆盖当前源的端点 / 采样率回读不一致"才算 DAC 不支持格式；
     * 权限未授予、接口被占用、claim/setInterface/nativeOpen 失败、描述符读取失败，
     * 全部属于"会话未建立"——它们跟"DAC 不支持源"是两件事，不能混用一句话。
     */
    private fun describeOpenFailure(
        failure: UsbDac.OpenFailure?, rate: Int, channels: Int, bits: Int,
    ): String = when (failure) {
        UsbDac.OpenFailure.FORMAT_UNSUPPORTED, UsbDac.OpenFailure.RATE_UNSUPPORTED ->
            "DAC 不支持当前源（${rate}Hz / ${bits}bit / ${channels}ch），当前系统输出"
        else -> FALLBACK_SESSION_NOT_ESTABLISHED
    }

    /**
     * [1.4.19·S03] 打开新开源 USB 独占地基。复用 UsbDac 的设备查找与权限判断，
     * 故与旧引擎指向同一台 DAC。返回 null 时上层自动回退旧引擎。
     */
    private fun tryOpenNewEngine(rate: Int, channels: Int, targetBits: Int = 0): UsbDac.Sink? {
        val mgr = appContext.getSystemService(android.content.Context.USB_SERVICE)
            as? android.hardware.usb.UsbManager ?: return null
        val dev = UsbDac.findDac(mgr) ?: run {
            AppLog.w("UsbNew", "未找到 DAC")
            return null
        }
        if (!mgr.hasPermission(dev)) {
            AppLog.w("UsbNew", "无 DAC 权限，回退旧引擎")
            return null
        }
        // [v18·#115] 补"新引擎打开失败的具体原因"，供真机定位回退根因。
        val opened = try {
            TrueSampleSink.open(mgr, dev, rate, channels, preferHigh = true, targetBits = targetBits)
        } catch (error: Throwable) {
            AppLog.w(
                "UsbNew",
                "新引擎打开异常，回退旧引擎: ${error.javaClass.simpleName} ${error.message} " +
                    "(rate=$rate channels=$channels dev=${dev.deviceName})",
            )
            null
        }
        if (opened == null) {
            AppLog.w(
                "UsbNew",
                "新引擎打开返回 null（接口协商/端点 claimed 失败），回退旧引擎 " +
                    "(rate=$rate channels=$channels dev=${dev.deviceName})",
            )
        }
        return opened
    }

    /** 是否正在走 UAC 通道 */
    val isExclusiveActive: Boolean
        get() = usbSink != null

    /**
     * [护栏 G-01/G-02/G-07·1.4.4 单一不变式] 记录 App 播放意图并按不变式对齐 worker。
     * onEvents（playWhenReady 变化）、rebuildOutput 末尾、以及 configure 会话建立后
     * 都汇聚到 [reconcileWorker]，不赌 ExoPlayer 回调时序、不 post 猜测帧。
     * worker 出声 = appWantsPlayback && !paused；相比 1.4.3「数据流入即放行」是收紧。
     */
    fun setPlayerPlaying(playing: Boolean) {
        // [1.4.5·G-15 闭合] 只写 App 播放意图真值；worker 启停一律交给 reconcileWorker
        // 单点对齐（含清 native paused），不再依赖 ExoPlayer 是否回调 play()。
        // 收紧：playing=false 绝不出声（防幽灵播放），playing=true 确定性 resume（防死锁）。
        appWantsPlayback = playing
        playerPlaying = playing
        // [1.4.10·G-51] 暂停闸的权威写入点：真实暂停走 onEvents -> setPlayerPlaying(false)，
        // 从 1.4.9 起不再经过 AudioSink.pause()，导致闸恒 false、门槛数据流自愈把真暂停
        // 误判成「重建假意图污染」→ 反复拉起播放（开独占自播 / 暂停失效 / 断续）。
        // 所有真实意图都汇聚到本函数，闸必须在这里跟随 playing 同步；configure/rebuild
        // 中间态走 reconcileWorker、不经过本函数，故不会误立/误清闸。
        userExplicitlyPaused = !playing
        reconcileWorker("setPlayerPlaying($playing)")
    }

    /**
     * [1.4.5·唯一出声判定点·单一真值源]
     * wantSound = appWantsPlayback（会话已建立时）。paused / native paused / exclusiveStarted
     * 全部由本函数**从这一个真值确定性派生**，不再作为独立真值源参与判定（1.4.4 曾用
     * 「App 真值与 paused 相与」把两套状态绑成 AND，是 B 包 PAUSE 死锁根因；1.4.5 已废止）。
     * 会话尚未 configure 时 usbSink==null 直接返回，等 configure 末尾再对齐一次，
     * 因此既不会「重建后 worker 永不启动」(1.4.2 C 缓冲极久)，也不会在
     * playWhenReady=false 时凭空出声（1.4.3 C 幽灵播放）。
     */
    private fun reconcileWorker(reason: String) {
        val sink = usbSink ?: return
        val wantSound = appWantsPlayback
        if (wantSound) {
            // 从暂停恢复：重建进度锚点，避免恢复时「倒带」（原 play() 的语义搬到这里）。
            if (paused) {
                anchorFrames = sink.framesPlayed
                paused = false
                awaitAnchor = true
            }
            // 会话建立后 worker 确定性启动并 resume：只在 appWantsPlayback=true 时，
            // 是修 1.4.2「C 独占缓冲极久」的正解，也是防 1.4.3 幽灵播放的收紧。
            if (!exclusiveStarted) {
                runCatching { sink.start() }
                    .onFailure { AppLog.w("Usb", "uac start on reconcile failed: ${it.message}") }
                exclusiveStarted = true
            }
            // 确定性清 native paused（sink.resume = nativePause(false)）。1.4.4 缺这一步，
            // 才有「App 要播但 native paused=1 只发静音包」的 PAUSE 死锁。
            runCatching { sink.resume() }
        } else {
            // App 不播 / 暂停：确定性置 native paused=1（只发静音包）+ 冻结进度。
            // [内联冻结·不调 getCurrentPositionUs] 后者内部会触发 ensureExclusiveRunning
            // 自愈；暂停路径绝不能走那条调用链（G-01/G-02/G-03 本意）。
            if (!paused) {
                pausedPosUs = if (awaitAnchor || usbSampleRate <= 0) pausedPosUs
                else (anchorMediaUs + (sink.framesPlayed - anchorFrames) * 1_000_000L / usbSampleRate)
                    .coerceAtLeast(0)
            }
            paused = true
            if (exclusiveStarted) {
                runCatching { sink.pause() }
            }
        }
        // [1.4.5·门禁 6] 只在状态翻转时打一条对齐日志：onEvents 会高频调 setPlayerPlaying，
        // 每条都打会刷屏；翻转帧才是实机复核「B 是否还 PAUSE 死锁 / C 是否真 START」的判据。
        if (lastSoundStateLogged != wantSound) {
            lastSoundStateLogged = wantSound
            AppLog.i(
                "Usb",
                "1.4.5(G-15) 出声态翻转对齐 [${if (wantSound) "G-02 START" else "G-07 PAUSE"}]: " +
                    "appWantsPlayback=$wantSound ($reason) stats=${sink.stats}",
            )
        }
    }

    /* ---------------- 配置 ---------------- */

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // [1.4.24·R38] 先记住这次输出格式：后续开关打开时可据此主动重开会话，
        // 不再依赖"下一首歌恰好换了采样率"这种偶然条件。
        lastConfiguredFormat = format
        lastConfiguredChannels = outputChannels
        // [护栏 G-01·台账登记·1.4.4 恢复] 重建 UAC 会话一律先按「暂停」处理：
        // playerPlaying=false、exclusiveStarted=false，worker 推迟到 play() / 状态同步才启动。
        // 防的 bug：暂停时插耳机 / 开启独占会新建 UAC 会话，UI 周期性查询进度
        // （getCurrentPositionUs → ensureExclusiveRunning）会把 worker 直接拉起，
        // 表现为「暂停却自动放歌 / 抽搐播放 / 静音包空转直到 session lost」。
        // 1.4.3 曾把 wasPlaying 带过重建并在此处直接 start()+resume()，
        // 造成 C 版幽灵播放（playWhenReady=false 却出声）——严禁再犯。
        // C 独占缓冲极久（1.4.2 真实存在）不靠这里放宽，改由 rebuildOutput 末尾
        // 显式把 App 真实 playWhenReady 推给新 sink（见 PlaybackService P2-c）。
        releaseUsb()
        playerPlaying = false
        // [1.4.25·开独占/切引擎后自动放歌根治] 重建 UAC 会话一律先「立暂停闸」。
        // configure 把 playerPlaying 置 false 但不清闸，若上一会话是播放态被切走，
        // 闸残留 false；此时 PCM 预读流入 12 帧即触发 G-51 数据流自愈，看到闸=false
        // 误判"重建假意图" → 强置 playerPlaying=true → 开独占/切引擎后莫名自动放歌。
        // 正解：新会话默认立闸(true)=「未收到播放确认前一律暂停」，等 App 的
        // onEvents(playWhenReady) → setPlayerPlaying(true) 权威解除（rebuildOutput 末尾
        // 也会用真实 playWhenReady 兜底同步，见 P2-c）。这样既堵幽灵播放，又不影响真播放。
        userExplicitlyPaused = true
        // [1.4.9·G-42] 新会话重置欠载停滞观测基线。
        lastStatsPlayed = -1L
        silentStatsRuns = 0
        lastStatsFramesBase = 0L
        lastStatsFramesWritten = -1L
        val enabled = exclusiveEnabled()
        if (!enabled) {
            delegate.configure(format, specifiedBufferSize, outputChannels)
            // [R75·降级原因细分] 开关"看起来是开的"却走共享输出，只有两种真实原因：
            // ① 持久化/内存态熔断（音频异常降级）——提示用户重开开关可重试；
            // ② 用户自己关掉了独占——没有降级可言，清掉旧提示，避免设置页挂着一句假话。
            if (UsbExclusiveGuard.isDisabled(appContext)) {
                UsbExclusiveSinkRef.setFallbackReason(FALLBACK_FUSED)
            } else {
                UsbExclusiveSinkRef.setFallbackReason(null)
            }
            AppLog.i("Usb", "output -> shared (exclusive off)")
            return
        }
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        // [v18·#115] 补播放源格式打点：判定"某首歌只有一边耳机出声"是源文件本身单声道
        // 还是引擎渲染问题——真机从日志即可看 sourceChannels=1 就是文件单声道。
        AppLog.i(
            "Usb",
            "uac source format: sourceChannels=${format.channelCount} effectiveChannels=$channels " +
                "sampleRate=${format.sampleRate}",
        )
        // [1.4.10·G-53] 采样率协商：88.2k/176.4k 这类 44.1k 的非整数倍源，在某些
        // UAC 设备的反馈端点（spp 11.03↔12.93 震荡、under/sil 暴涨）上会失稳，表现为
        // 「所有歌曲声音都奇怪」。就近规整到设备原生率 44100/48000，由 ExoPlayer 解码侧
        // 重采样到该率再写入，反馈环落在整数倍上即稳定。源率<=48000 时原样请求（bit-perfect 不变）。
        val requestedRate = negotiateUsbRate(format.sampleRate)
        // [v24·bit-perfect] 零损失开启时把源位深传下去，让 UAC 端点按源位深精确选，
        // 消除 16bit→32bit 的整数扩展，使逐字节直传（sink.enc==源字节数）成立。
        // 只在采样率未经重采样（源率==请求率）时才追求端点精确匹配——SRC 后本就不 bit-perfect。
        val zeroLossForRoute = com.smartisan.music.data.settings.UsbZeroLossStore.current()
        val sourceBits = if (zeroLossForRoute && requestedRate == format.sampleRate) {
            when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                else -> 0
            }
        } else 0
        AppLog.i("Usb", "bit-perfect route: zeroLoss=$zeroLossForRoute sourceBits=$sourceBits")
        var sink: UsbDac.Sink? = null
        // [1.4.19·S03] 新开源引擎优先（若开关打开）；失败一律回退旧自研引擎，
        // 旧引擎再失败才降级到系统共享输出。任何一层都不让用户没声音。
        if (com.smartisan.music.data.settings.UsbEngineStore.current()) {
            sink = tryOpenNewEngine(requestedRate, channels, sourceBits)
            if (sink == null) {
                AppLog.w("Usb", "新USB引擎不可用 -> 回退旧自研引擎")
            }
        }
        if (sink == null) {
            sink = try {
                UsbDac.open(appContext, requestedRate, channels, preferHigh = true, targetBits = sourceBits)
            } catch (error: Throwable) {
                AppLog.e("Usb", "uac open threw: ${error.message}", error)
                null
            }
        }
        if (sink == null) {
            // [R75·降级原因细分] 不再把所有失败都写成"DAC 无法打开或不支持当前源"：
            // 实测多数是权限未授予 / 接口被占用 / 会话未建立，跟"DAC 不支持源"无关。
            // UsbDac 把真实失败归类带出来，这里翻成设置页能直接看懂的一句话。
            val reason = describeOpenFailure(
                UsbDac.lastOpenFailure(),
                requestedRate,
                channels,
                sourceBitDepth(format),
            )
            AppLog.w(
                "Usb",
                "uac unavailable -> fall back to shared output. reason=$reason " +
                    "cause=${UsbDac.lastOpenFailure()} detail=${UsbDac.lastOpenFailureDetail()}",
            )
            UsbExclusiveSinkRef.setFallbackReason(reason)
            delegate.configure(format, specifiedBufferSize, outputChannels)
            return
        }
        // 独占成功：清掉上次的降级提示。
        UsbExclusiveSinkRef.setFallbackReason(null)
        usbSink = sink
        // [v22·音量] 开声之前探测 DAC 硬件音量并绑定写入口：AUTO 能硬件就硬件，
        // 零损失开启且硬件可用时强制硬件（数字域保持满档，逐字节直传仍然成立）。
        runCatching {
            // 先绑硬件写入口，再 onSessionReady：后者里的 syncSystemVolume 会按当前模式
            // 立刻施加一次音量，绑定晚了硬件音量就不会被写进去。
            UsbVolume.handoff?.bindHardwareWriter { db ->
                runCatching { sink.setHardwareVolumeDb(db) }.getOrDefault(false)
            }
            UsbVolume.onSessionReady(sink)
        }.onFailure { AppLog.w("Usb", "volume session init failed: ${it.message}") }
        usbFormat = format
        usbChannels = sink.channels
        // [1.4.10·G-53] 用协商后 sink 实际打开率（可能 != 源率），进度/锚点/DSP 统一口径。
        usbSampleRate = sink.sampleRate
        if (format.sampleRate > 0 && sink.sampleRate != format.sampleRate) {
            AppLog.w(
                "Usb",
                "1.4.10(G-53) 采样率协商：源 ${format.sampleRate}Hz -> UAC 原生 ${sink.sampleRate}Hz",
            )
            // [R75] 这属于"DAC 不支持源的采样率"，如实写清源率与设备实际输出率。
            UsbExclusiveSinkRef.setFallbackReason(
                "DAC 不支持源采样率 ${format.sampleRate}Hz，已按 ${sink.sampleRate}Hz 输出（非 bit-perfect）",
            )
        }
        usbFrameBytes = sink.enc * sink.channels
        usbInputEncoding = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> format.pcmEncoding
            else -> C.ENCODING_PCM_16BIT
        }
        usbInputBytesPerSample = when (usbInputEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        if (format.pcmEncoding != usbInputEncoding) {
            AppLog.w(
                "Usb",
                "输入 PCM 编码未知(${format.pcmEncoding})，按 16bit 处理——若音源是高规格，位深会被截断",
            )
        }
        framesWritten = 0L
        framesBase = 0L
        endOfStream = false
        uacLostReported = false
        // UAC 会话为新开：进度锚点必须重新建立，否则会继承上一个会话的旧位置
        awaitAnchor = true
        anchorFrames = 0L
        anchorMediaUs = 0L
        // [护栏 G-01 / G-04·台账登记] 暂停态绝不能在这里被「重置」成播放态。
        // paused 严格由 pause()/play() 维护；播放态下 ExoPlayer 一定会回调 play()。
        // 1.4.3 曾删掉这段注释并放宽，1.4.4 逐字恢复。
        exclusiveStarted = false   // [G-02] worker 推迟到 play() 才启动，避免暂停态空转
        scratchStreaming = false
        UsbDacSinkRef.current = sink
        // UI 层的打碟旁路拿不到 ExoPlayer 的 sink 实例，用引用给它一个写入口
        UsbExclusiveSinkRef.current = this
        // [v12·切引擎/会话重建爆音根治] 新 native sink 句柄的增益从 0 起步：先把新 sink
        // 增益强制设 0（此刻还没有 PCM 写入，静默无爆音），再让 handoff 起点归零，随后
        // syncSystemVolume 会触发一次从 0→目标系统音量的平滑淡入（45ms），消除"重开独占/
        // 切引擎继续播放开头很大一声爆音"。旧实现 handoff.appliedGain 残留旧会话满档值，
        // ramp 第一步就写满增益 → 首包满幅爆音。
        // [R73·软件音量无声] 只有存在平滑接力（handoff）时才能"从 0 起步"——0 必须由
        // 随后的 ramp 拉回来。若 handoff 不存在（音量服务未就绪/被关闭），把 0 写进
        // native 后就再也没有人拉起来 → 永久静音（日志 VolHandoff gain=0.000 的来源之一）。
        // 这种情况下直接写目标增益，宁可没有淡入也不能没有声音。
        val handoff = UsbVolume.handoff
        if (handoff != null) {
            runCatching { sink.setSoftwareGain(0f) }
            handoff.resetForNewSession()
        } else {
            runCatching {
                sink.setSoftwareGain(
                    (playerVolume * UsbVolume.softwareGain()).coerceIn(0f, 1f),
                )
            }
        }
        // 独占一开，软件音量（PCM 增益）要跟随系统当前媒体音量，避免以满档开声
        UsbVolume.syncSystemVolume(appContext)
        KeepAlive.start(appContext)
        // [1.4.6·X01/X02] 独占会话建立 -> 应用性能优先（线程优先级 / ADPF 提示 / 后台保活取证）。
        // 排在 G-20/G-21 死锁修复之后，只是叠加保障，不替代真值/门槛修复。
        UsbExclusivePerformance.bindAppContext(appContext)
        UsbExclusivePerformance.onExclusiveSessionStarted(appContext)
        // 独占直出绕开了系统 AudioTrack，必须自己持锁，否则后台/灭屏 CPU 与 USB 总线休眠 → 爆音
        acquireWakeLock()
        // 独占一建立，系统里这块声卡就消失了，紧接着会收到一条
        // ACTION_AUDIO_BECOMING_NOISY（误报），上层要在此期间抑制它
        onUacOpened()
        // [1.4.24·R38] claim 成功后系统会把这块声卡的输入口一起摘掉（麦克风不可用），
        // 这是真独占的**预期副作用**，也是判断"独占是否真的生效"的旁证。之前日志里没有，
        // 用户反馈"麦克风失效"时无法确认是独占造成还是系统异常，这里落一条基线。
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val inputs = am?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)?.size ?: -1
            val outputs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.size ?: -1
            AppLog.i(
                "Usb",
                "uac claim 后系统设备基线: inputs=$inputs outputs=$outputs " +
                    "（独占期间麦克风/其他媒体不可用属预期，关独占后应恢复）",
            )
        }
        // [护栏 G-01·1.4.4] 会话刚建立时**绝不在此处 resume() 出声**。
        // worker 「出声」的唯一收紧不变式 = playerPlaying && !paused，由 play() /
        // setPlayerPlaying() / rebuildOutput 显式状态同步驱动。重建后若 App 确实在播，
        // rebuildOutput 末尾会用真实 playWhenReady 调 setPlayerPlaying(true)（P2-c），
        // 既不让 C 独占缓冲极久（worker 永不启动），也不赌 ExoPlayer 回调时序，
        // 更不会在冷启动 playWhenReady=false 时凭空出声（幽灵播放）。
        AppLog.i("Usb", "uac 会话已建立（G-01: 先按暂停，worker 待 App 播放态启动）stats=${sink.stats}")
        // [1.4.4 单一不变式·会话就绪确定性对齐] 新会话建好后立即按 App 播放意图对齐：
        // 重建且在播 → 启动 worker（修 1.4.2「C 独占缓冲极久」，rebuildOutput 不必赌
        // ExoPlayer 是否回调 play()）；重建且暂停/冷启动未播 → 不出声（防幽灵播放）。
        // 这里用 appWantsPlayback（真值），不是无条件 start+resume，属于收紧不变式。
        // [1.4.5·G-16] 新会话显式翻转日志标记为「未记录」，确保 configure 后的这次
        // 对齐一定留一条可 grep 的翻转帧（门禁 6）。handleBuffer 的「不得灌数据」门槛
        // 只看 appWantsPlayback 单一真值，杜绝 C 流式管道在 worker 未出声时灌满 ring。
        lastSoundStateLogged = null
        reconcileWorker("configure-rebuild")
        AppLog.i(
            "Usb",
            "output -> UAC exclusive ${format.sampleRate}Hz ${sink.channels}ch " +
                "${sink.enc * 8}bit dev=${sink.deviceName} hwVol=${sink.hardwareVolumeSupported}",
        )
        if (sink.enc < usbInputBytesPerSample) {
            AppLog.w(
                "Usb",
                "独占输出位深(${sink.enc * 8}bit)低于音源位深(${usbInputBytesPerSample * 8}bit)：" +
                    "DAC 在该采样率/声道下未提供更高位深 alt，音质将被截断（bit-perfect 不成立）",
            )
        }
    }

    /**
     * [1.4.24·R38 假独占根治] 用户打开独占开关时，主动用「上一次 configure 的格式」
     * 重跑一次配置，把 UAC 会话真正建起来。
     *
     * 为什么必须主动：ExoPlayer 只在输出格式变化时调用 configure()。开关打开时若正在播
     * 同格式的曲子（绝大多数情况），stop/prepare 不会触发 configure → usbSink 永远为 null，
     * 数据继续走系统共享输出，用户看到「开关已开」但实际没有任何独占（日志证据：
     * 2026-11-24 03:08:21 开独占，直到 03:11:05 切到 88.2k 曲目才出现第一条 uac open）。
     *
     * 复用 configure() 而不是复制一套初始化：音量绑定、锚点、wakelock、keepalive、
     * onUacOpened 回调、worker 对齐全在里面，复制必然漏项（历史上已因此踩过多次坑）。
     *
     * @param startPlaying 当前 App 真实播放意图；新会话默认按暂停建，必须显式对齐。
     * @return 会话是否建立成功（false 时调用方应保持共享输出并告知用户）。
     */
    fun forceReopen(startPlaying: Boolean): Boolean {
        if (usbSink?.alive == true) return true
        val format = lastConfiguredFormat
        if (format == null) {
            AppLog.w("Usb", "forceReopen 跳过：尚未收到任何 configure（播放器还没准备过输出）")
            return false
        }
        AppLog.audit(
            "Usb",
            "主动重建独占会话（开关打开，不等下一次格式变化）",
            "UsbExclusiveAudioSink.forceReopen",
            mapOf(
                "rate" to format.sampleRate,
                "ch" to format.channelCount,
                "pcm" to format.pcmEncoding,
                "startPlaying" to startPlaying,
            ),
        )
        val ok = runCatching {
            configure(format, 0, lastConfiguredChannels)
            usbSink?.alive == true
        }.onFailure {
            AppLog.e("Usb", "forceReopen 失败: ${it.javaClass.simpleName} ${it.message}", it)
        }.getOrDefault(false)
        if (ok) {
            setPlayerPlaying(startPlaying)
            reconcileWorker("force-reopen")
            AppLog.i("Usb", "forceReopen 成功：UAC 会话已建立，startPlaying=$startPlaying")
        } else {
            // configure 开头的 releaseUsb() 会把全局引用清空（它以为要换会话）。
            // 失败时必须把引用还回来，否则爆豆音/看门狗/降级提示都拿不到这个 sink。
            UsbExclusiveSinkRef.current = this
            AppLog.w("Usb", "forceReopen 失败：保持系统共享输出（不会假装已独占）")
        }
        return ok
    }

    /** [1.4.24] 仅供看门狗/诊断读取：当前是否真的持有一个活着的 UAC 会话。 */
    fun usbSessionActive(): Boolean = usbSink?.alive == true

    /* ---------------- 写入 ---------------- */

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val sink = usbSink
        if (sink == null) {
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!sink.alive) {
            handleUacLost()
            // 已经把 buffer 里的数据丢弃，避免上层死循环重试同一段
            buffer.position(buffer.limit())
            return true
        }
        // [1.4.5·G-16 核心门槛·收紧·单一真值] App 不打算播时，worker 只发静音包、
        // 不消费 ring，此时绝不把 PCM 写进 native ring。1.4.4 缺这道门：C 流式管道在
        // configure/rebuild（先按暂停、playing=false 假意图）期间不等 worker 就灌数据，
        // 2 秒灌满 used=cap=705600，played 恒 0 -> write timeout -> 满 ring 退避 ->
        // handleUacLost -> 重建 -> 又灌满，死循环（C 版 04:02 216 次 timeout /
        // 51 次退避 / 10 次重建）。丢弃本帧并 return true（消费掉，不判失败、不累计
        // stall、不触发重建）；worker 由 reconcileWorker 确定性拉起后下一帧即正常写。
        // 这不是「放宽兜底」：暂停/未播放态本就不该有 PCM 灌进 ring（G-01/G-02 本意）。
        if (!appWantsPlayback || paused) {
            gateDroppedFrames++
            // [1.4.6·G-20/G-21 根治·数据流自愈] 1.4.5 把重建瞬间 playing=false 的
            // 「假意图」写进唯一真值 appWantsPlayback，门槛据此无限丢帧（B 丢 2165
            // 帧 / C frames=0 持续 2m42s、sil 静音帧暴涨）。真实 PCM 帧持续流入就是
            // 「播放链路已活跃」的铁证：连续超过阈值即判定真值被污染，纠正真值并
            // reconcile 拉起 worker，本帧起照常写 ring —— 既不拆 G-16「真暂停不灌
            // ring / 满 ring 本地恢复」的本意，也不再死等窗口内被吞掉的翻转事件。
            // 真暂停由 userExplicitlyPaused 守住，绝不因数据流误拉起（防幽灵播放）。
            // [1.4.10·G-51] 单一前置：只看 userExplicitlyPaused 闸（现在由 setPlayerPlaying
            // 权威同步）。1.4.9 的 `playerPlaying || !userExplicitlyPaused` 把「闸未立且
            // playerPlaying 残留 true」当成想播，真暂停瞬间 playerPlaying 还没翻、闸也没立，
            // 于是自愈照样触发 → 开独占自播/暂停失效。重构后：真暂停闸=true 必被挡住；
            // 重建假暂停闸=false（configure 不经过 setPlayerPlaying）仍在 12 帧后自愈恢复。
            if (!userExplicitlyPaused && gateDroppedFrames >= GATE_DATAFLOW_RECOVERY_FRAMES) {
                AppLog.w(
                    "Usb",
                    "1.4.10(G-51) 门槛数据流自愈：连续 $gateDroppedFrames 帧真实 PCM 流入但真值=false，" +
                        "用户未显式暂停(闸=false) -> 判定重建假意图污染 -> 纠正 appWantsPlayback=true stats=${sink.stats}",
                )
                appWantsPlayback = true
                playerPlaying = true
                reconcileWorker("1.4.6-gate-dataflow-recovery")
                gateDroppedFrames = 0L
                // 落到下方正常写入路径，本帧不丢
            } else {
                // [门禁 6] 首帧 + 每 100 帧打一条，既不刷屏又能证明门槛真的被触发。
                if (gateDroppedFrames == 1L || gateDroppedFrames % 100L == 0L) {
                    // [1.4.9·G-41] 暂停态 PCM 流入诊断帧：如实记下「用户显式暂停但仍有PCM」，
                    // 用来在实机证明挡板确实拦住了幽灵播放（0 次触发=未复现，不算证据）。
                    AppLog.i(
                        "Usb",
                        "1.4.10(G-51) 灌数据门槛生效(暂停态PCM流入已拦截)：暂丢本帧不写 ring " +
                            "(累计 $gateDroppedFrames 帧, appWantsPlayback=$appWantsPlayback, " +
                            "用户显式暂停=$userExplicitlyPaused) stats=${sink.stats}",
                    )
                }
                buffer.position(buffer.limit())
                return true
            }
        }
        // 正常出声态：若此前被门槛挡过，翻转时打一条汇总帧（证明门槛→恢复链路完整）。
        if (gateDroppedFrames > 0L) {
            AppLog.i(
                "Usb",
                "1.4.6(G-16) 门槛放行：worker 已出声，此前累计挡下 $gateDroppedFrames 帧未灌满 ring",
            )
            gateDroppedFrames = 0L
        }
        // 自愈兜底：见 [ensureExclusiveRunning]。
        ensureExclusiveRunning(sink)
        // MediaCodec 输出的 PCM 是小端，但 ByteBuffer 默认按大端读 float，
        // 不切成小端会把每个样本读成垃圾（表现为极响的白噪）
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = usbFrameBytes
        val srcChannels = usbFormat!!.channelCount
        val frames = buffer.remaining() / (usbInputBytesPerSample * srcChannels)
        if (frames <= 0) {
            buffer.position(buffer.limit())
            return true
        }
        val outFrameBytes = frameBytes
        val need = frames * outFrameBytes
        if (outBytes.size < need) outBytes = ByteArray(need)

        // [v20·零损失] 逐字节直传：当源==目标编码、无 DSP、无爆豆/打碟叠加、软件音量满档时，
        // 直接把解码 PCM 原样拷进 outBytes，跳过 convert 的 float 归一化往返（消除 ±1 LSB 量化）。
        // 条件任一不满足就走下面的 convert 正常路径，零损失不成立也不影响原有输出。
        val popcornMix = takePopcornMix(frames, sink.sampleRate)
        val zeroLoss = UsbExclusiveSinkRef.zeroLossEnabled
        // [v20·零损失] 开启后强制旁路 DSP：设置页置灰只挡住"再改设置"，用户之前已开着的
        // EQ/压限/混响如果继续生效，这个开关就名不副实。以开关为真值旁路，关掉零损失即恢复。
        val dspActive = PcmDsp.hasActiveEffect() && !zeroLoss
        if (zeroLoss && PcmDsp.hasActiveEffect() && !zeroLossDspBypassLogged) {
            zeroLossDspBypassLogged = true
            AppLog.i("Usb", "v20 零损失：已强制旁路 DSP 音效（EQ/压限/混响/响度），关掉零损失即恢复")
        }
        val bytePerfect =
            zeroLoss &&
                sink.enc == usbInputBytesPerSample &&
                srcChannels == usbChannels &&
                sink.sampleRate == usbFormat!!.sampleRate &&
                !dspActive &&
                popcornMix == null &&
                !scratchStreaming &&
                (playerVolume * UsbVolume.softwareGain()) >= 1f
        if (bytePerfect) {
            // 源与目标位深/声道/采样率全同，字节布局一致（均小端交错），逐字节拷贝即 bit-perfect。
            buffer.get(outBytes, 0, need)
            if (!zeroLossLogged) {
                zeroLossLogged = true
                AppLog.i(
                    "Usb",
                    "v20 零损失 bit-perfect 生效：源==目标 ${usbInputBytesPerSample * 8}bit " +
                        "$srcChannels ch @${sink.sampleRate}Hz，逐字节直传（无 DSP/叠加/音量衰减）",
                )
            }
        } else {
            // [v20·零损失] 开关开着但本次没走直传时，打一次"为什么没生效"，避免用户以为开关失灵。
            if (zeroLoss && !zeroLossMissLogged) {
                zeroLossMissLogged = true
                val why =
                    buildList {
                        if (sink.enc != usbInputBytesPerSample)
                            add("位深 ${usbInputBytesPerSample * 8}bit→${sink.enc * 8}bit")
                        if (srcChannels != usbChannels) add("声道 $srcChannels→$usbChannels")
                        if (sink.sampleRate != usbFormat!!.sampleRate)
                            add("采样率 ${usbFormat!!.sampleRate}→${sink.sampleRate}")
                        if (dspActive) add("DSP 音效生效")
                        if (popcornMix != null) add("爆豆音叠加")
                        if (scratchStreaming) add("打碟音进行中")
                        if ((playerVolume * UsbVolume.softwareGain()) < 1f)
                            add("软件音量未满档(${playerVolume}×${UsbVolume.softwareGain()})")
                    }
                AppLog.i(
                    "Usb",
                    "v20 零损失 bit-perfect 未生效，走普通转换路径，原因：" +
                        (if (why.isEmpty()) "未知" else why.joinToString("、")),
                )
            }
            // 软件音量增益不再在"写入侧"应用：交由原生层在输出端逐包施加（见 pushGain），
            // 这样环形缓冲即使很长、吸收抖动保证流畅，音量调节也能即时生效，且不引入额外量化。
            convert(buffer, srcChannels, usbChannels, sink.enc, 1f, need, popcornMix)
        }

        // nativeWrite 有 200ms 的内部限时，可能一次写不完，循环补齐
        val workStartNanos = System.nanoTime()  // [1.4.6·X01] ADPF 实际工作时长上报
        var off = 0
        val deadline = System.currentTimeMillis() + WRITE_DEADLINE_MS
        var stalledThisFrame = false
        while (off < need) {
            val wrote = sink.write(outBytes, off, need - off)
            if (wrote <= 0) {
                if (!sink.alive) {
                    handleUacLost()
                    buffer.position(buffer.limit())
                    return true
                }
                if (System.currentTimeMillis() > deadline) {
                    stalledThisFrame = true
                    AppLog.w(
                        "Usb",
                        "uac write timeout, drop ${need - off} bytes (framesW=$framesWritten " +
                            "played=${sink.framesPlayed - framesBase}) stats=${sink.stats}",
                    )
                    break
                }
                Thread.sleep(2)
                continue
            }
            off += wrote
        }
        // [1.4.5·清单 3] 满 ring 连续写不进的确定性本地恢复：不无限丢帧空转，
        // 也不再主动上报掉线让上层重建（1.4.4 那条路会回同一死状态形成风暴）。
        // 复用已存在的 sink.flush() / reconcileWorker()（不新增 0 次触发的自愈函数）。
        if (stalledThisFrame) {
            consecutiveWriteStalls++
            if (consecutiveWriteStalls >= MAX_WRITE_STALLS) {
                // [1.4.5·清单 3·撤掉重建风暴] 1.4.4 这里立刻 handleUacLost -> 上层
                // rebuildOutput("uac retry") -> configure 又先按暂停 + playing=false 假
                // 意图 -> 上游又灌满 -> 又退避，51 次退避 / 10 次重建回同一死状态。
                // 改口径为「确定性本地恢复」：先清 ring 再按真值重新对齐 worker，
                // 让写线程马上能继续；只有设备真的失败（sink.alive=false）才在下一帧
                // 由开头那条 !sink.alive 分支 handleUacLost，交给上层节流，不自发循环。
                // 复用已存在的 sink.flush() / reconcileWorker()，不新增 0 次触发的自愈函数。
                AppLog.w(
                    "Usb",
                    "1.4.5(G-16) 满 ring 本地恢复：连续 $consecutiveWriteStalls 帧写不进，" +
                        "清 ring + 按 App 真值重新对齐 worker（不再立刻重建回同一状态）" +
                        " appWantsPlayback=$appWantsPlayback stats=${sink.stats}",
                )
                consecutiveWriteStalls = 0
                runCatching { sink.flush() }
                framesWritten = 0L
                framesBase = sink.framesPlayed
                awaitAnchor = true
                reconcileWorker("full-ring-local-recovery")
                buffer.position(buffer.limit())
                return true
            }
        } else {
            consecutiveWriteStalls = 0
        }
        // [1.4.6·X01] 每成功写一帧上报一次实际工作时长（ADPF 尽力提示，反射失败静默降级）。
        UsbExclusivePerformance.reportWorkDuration(
            (System.nanoTime() - workStartNanos).coerceAtLeast(1L),
        )
        // 超时丢帧时只能按实际写出的量计数，否则位置（进度条）会虚跳
        val wroteFrames = off / outFrameBytes.coerceAtLeast(1)
        framesWritten += wroteFrames
        // 用本帧的媒体时间戳建立/推进进度锚点（见 anchor 字段说明）
        if (awaitAnchor && usbSampleRate > 0) {
            // [R73·seek 位置错位] native 的 framesPlayed 是**单调累计**（flush 不清零），
            // framesBase 才是"本次 flush 之后的播放原点"。旧公式用 (framesPlayed - framesWrittenBefore)
            // 少了 framesBase：同一会话里 seek 一次，锚点就会把"本会话已播过的全部帧"
            // 再加到 seek 目标上（例：播了 60s 后拖到 120s，进度直接显示 180s）。
            // 正确口径：本帧之前 buffer 里已播出的帧 = (framesPlayed - framesBase) - framesWrittenBefore。
            val framesWrittenBefore = framesWritten - wroteFrames
            val playedSinceFlush = sink.framesPlayed - framesBase
            val outSinceBufferStart = (playedSinceFlush - framesWrittenBefore).coerceAtLeast(0)
            val outPosUs = presentationTimeUs +
                outSinceBufferStart * 1_000_000L / usbSampleRate
            anchorMediaUs = outPosUs.coerceAtLeast(0)
            anchorFrames = sink.framesPlayed
            awaitAnchor = false
        }
        buffer.position(buffer.limit())
        logStatsPeriodically(sink)
        return true
    }

    /** 每 10 秒采一条 UAC 会话统计；失败/重试时的状态变化一目了然 */
    private fun logStatsPeriodically(sink: UsbDac.Sink) {
        val now = System.currentTimeMillis()
        if (now - lastStatsLogAt < STATS_INTERVAL_MS) return
        lastStatsLogAt = now
        statsLogCount++
        val playedNow = sink.framesPlayed - framesBase
        AppLog.i(
            "Usb",
            "uac stats #$statsLogCount framesW=$framesWritten played=$playedNow " +
                "stats=${sink.stats}",
        )
        // [1.4.9·G-42] sink 实际出声态并入真值：appWantsPlayback=true 却连续 3 个统计周期
        // （约30秒）played 完全不推进 = worker 停摆/欠载环满，UI「在播」是假象。
        // 此时主动请求一次受节流重建（复用 handleUacLost，60秒冷却，避免重建风暴），
        // 等同用户「拖进度条能救」的 flush+rebuild 语义，但由系统自动完成并如实记帧。
        // [1.4.23·缓冲卡住根治] 若 framesBase 刚被重置（满 ring 恢复/flush），playedNow
        // 会骤降，这是锚点变化不是停摆——跳过本周期并刷新基准，绝不触发重建。
        val baseChanged = lastStatsFramesWritten >= 0L && framesBase != lastStatsFramesBase
        lastStatsFramesBase = framesBase
        if (baseChanged) {
            AppLog.i("Usb", "1.4.23(G-58修) framesBase 变化($lastStatsFramesBase→$framesBase)，跳过停摆判定")
            lastStatsPlayed = playedNow
            lastStatsFramesWritten = framesWritten
            silentStatsRuns = 0
            return
        }
        // 真停摆判据：played 不推进 **且** framesWritten 也不再增长（写入侧也停了）。
        // 只看 played 会在"写帧正常但播放位置因锚点抖动短暂不动"时误判。
        val writeStalled = lastStatsFramesWritten >= 0L && framesWritten <= lastStatsFramesWritten
        if (appWantsPlayback && !paused && lastStatsPlayed >= 0L &&
            playedNow <= lastStatsPlayed && writeStalled
        ) {
            silentStatsRuns++
        } else {
            silentStatsRuns = 0
        }
        lastStatsPlayed = playedNow
        lastStatsFramesWritten = framesWritten
        if (silentStatsRuns >= 3 && now - lastUnderrunRebuildAt >= 60_000L) {
            lastUnderrunRebuildAt = now
            silentStatsRuns = 0
            AppLog.w(
                "Usb",
                "1.4.10(G-58) 检出 UAC 停摆(played 连续不推进+在播意图)，主动请求受节流重建 " +
                    "played=$playedNow stats=${sink.stats}",
            )
            handleUacLost()
        }
    }

    /**
     * float32 → 目标整数位深。
     * [gain] 为 1f 时只做位深转换（不乘），保证满档不引入额外的量化。
     */
    private fun convert(
        buffer: ByteBuffer,
        srcChannels: Int,
        dstChannels: Int,
        enc: Int,
        gain: Float,
        needBytes: Int,
        /** 1.3.3：本帧要叠加的爆豆音立体声 float 样本，长度 = frames*2；null 表示无叠加。 */
        mixPlan: FloatArray? = null,
    ) {
        val frames = needBytes / (enc * dstChannels)
        // 1.3.4：自研 DSP 音效链在 float 域处理（独占直出同样生效）。
        // 关闭时 dspInput 为 null，读样本路径与 1.3.3 逐字节一致。
        // [1.4.24·独占DSP失效根治] 门控从「仅看 EQ(enabled)」改为「任一音效激活(hasActiveEffect)」。
        // 旧代码下：原声 EQ + 单独开压限/混响/响度平衡/响度归一化时 enabled=false，
        // 整条 process() 被跳过 → 独占模式这些音效全失效（只有选了 EQ 预设才生效）。
        // 与系统模式 PcmDspAudioProcessor 的门控口径对齐。
        // [v20·零损失] 零损失开启时这里也必须旁路，否则"逐字节直传"分支即使命中，
        // 音效也会在别处被重新应用（反之亦然），两者口径必须一致。
        val dspInput: FloatArray? =
            if (PcmDsp.hasActiveEffect() && !UsbExclusiveSinkRef.zeroLossEnabled && frames > 0) {
            // [1.4.19·代码质量#1] 热路径优化：原实现每样本 runCatching{} 创建 Result+lambda
            // （48k 立体声约 10 万次/秒），且每帧新建 FloatArray。readPcmSample 本身不抛异常
            // （样本数由 frames 算出、不会越界），改为直接读 + 复用字段级缓冲，消除 GC 压力。
            val need = frames * srcChannels
            if (dspScratch.size < need) dspScratch = FloatArray(need)
            val arr = dspScratch
            dspDup = buffer.duplicate().order(buffer.order())
            val dup = dspDup
            for (i in 0 until need) {
                val v = readPcmSample(dup)
                arr[i] = if (v.isNaN() || v.isInfinite()) 0f else v
            }
            PcmDsp.process(arr, frames, srcChannels, usbSampleRate)
            arr
        } else null
        var p = 0
        // 任何异常的 gain（0/0 之类的 NaN）都当作 1 处理，绝不让 NaN 进样本
        val g = if (gain.isNaN() || gain.isInfinite()) 1f else gain
        val applyGain = g != 1f
        for (f in 0 until frames) {
            for (ch in 0 until dstChannels) {
                var v = if (ch < srcChannels) {
                    if (dspInput != null) dspInput[f * srcChannels + ch]
                    else readPcmSample(buffer)
                } else {
                    0f
                }
                if (v.isNaN() || v.isInfinite()) v = 0f
                // 1.3.3：爆豆音是「音乐播放中的常驻氛围音」，在 float 域随本帧叠加，
                // 不碰 URB、不碰 framesWritten / 锚点 / paused，音乐进度完全不受影响。
                if (mixPlan != null) {
                    val mixIndex = f * 2 + ch.coerceAtMost(1)
                    if (mixIndex < mixPlan.size) {
                        v = (v + mixPlan[mixIndex]).coerceIn(-1f, 1f)
                    }
                }
                if (applyGain) v = (v * g).coerceIn(-1f, 1f)
                when (enc) {
                    UsbDac.ENC_16 -> {
                        val s = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
                        outBytes[p++] = (s and 0xFF).toByte()
                        outBytes[p++] = ((s shr 8) and 0xFF).toByte()
                    }
                    UsbDac.ENC_24 -> {
                        val s = (v * 8388607f).roundToInt().coerceIn(-8388608, 8388607)
                        outBytes[p++] = (s and 0xFF).toByte()
                        outBytes[p++] = ((s shr 8) and 0xFF).toByte()
                        outBytes[p++] = ((s shr 16) and 0xFF).toByte()
                    }
                    else -> {
                        val s = (v * 2147483647.0).roundToInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE)
                        outBytes[p++] = (s and 0xFF).toByte()
                        outBytes[p++] = ((s shr 8) and 0xFF).toByte()
                        outBytes[p++] = ((s shr 16) and 0xFF).toByte()
                        outBytes[p++] = ((s shr 24) and 0xFF).toByte()
                    }
                }
            }
        }
    }

    /** 按解码器实际输出的 PCM 编码读一个样本，归一到 [-1, 1] 的 float。 */
    private fun readPcmSample(buffer: ByteBuffer): Float = when (usbInputEncoding) {
        C.ENCODING_PCM_FLOAT -> buffer.float
        C.ENCODING_PCM_8BIT -> ((buffer.get().toInt() - 128) / 128f)
        C.ENCODING_PCM_16BIT -> buffer.short / 32768f
        C.ENCODING_PCM_24BIT -> {
            val lo = buffer.get().toInt() and 0xFF
            val mid = buffer.get().toInt() and 0xFF
            val hi = buffer.get().toInt() and 0xFF
            // 24bit 移到 32bit 高位做符号扩展
            ((hi shl 24) or (mid shl 16) or (lo shl 8)) / 2147483648f
        }
        C.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
        else -> buffer.short / 32768f
    }

    private fun handleUacLost() {
        if (uacLostReported) return
        uacLostReported = true
        AppLog.w("Usb", "uac session lost, requesting rebuild stats=${usbSink?.stats ?: "n/a"}")
        onUacLost()
    }

    /**
     * 把当前软件音量增益推送到原生输出端。
     *
     * 增益 = 播放器自身音量 × 系统媒体音量比例，区间 [0,1]。
     * 由原生层在"提交 DAC 前"逐包施加，因此无论环形缓冲多长，音量调节都即时生效，
     * 且只做一次量化、不损害音质。增益变化（系统音量键 / 播放器音量）都会触发本调用。
     */
    private fun pushGain() {
        val sink = usbSink ?: return
        val g = (playerVolume * UsbVolume.softwareGain()).coerceIn(0f, 1f)
        // [1.4.21·P2] 播放器音量变化也走平滑接力，与系统音量条口径统一；0 档立即静音。
        val h = UsbVolume.handoff
        if (h != null) {
            h.request(g, immediate = g <= 0f)
        } else {
            runCatching { sink.setSoftwareGain(g) }
        }
    }

    /* ---------------- 位置 ---------------- */

    /**
     * 自愈：换曲时 ExoPlayer 的调用次序可能是
     * `flush()`（此刻 `paused=1`，按旧规则不补 resume）→ `configure()` 重建会话、
     * 把 `exclusiveStarted` 归零 → 而 `play()` 恰好在 flush 与 configure 之间的窗口里
     * 已经调过、**之后不再调用** → 新会话的 URB worker 永远不启动。
     *
     * 日志特征：`paused=0`、`framesW` 持续上涨、`played=0`、`urbs=0/inflight=0`，
     * ring `used=cap` 之后 `write timeout` 连发，直到手动点歌（触发 configure+start）才恢复。
     * 手动点歌能秒播正是因为它换了会话；而规格相同的下一首走不到这条路，于是卡死。
     *
     * 因此只要"不在暂停态 + worker 未启动"就直接拉起，不再依赖上层的调用时序。
     * 本方法同时挂在 [handleBuffer] 与 [getCurrentPositionUs] 两条路径上：
     * 一旦卡住，上层会因 [hasPendingData] 恒为真而停止喂数据，此时只有进度查询
     * 仍会被周期性调用，必须靠它把流救回来。
     */
    private fun ensureExclusiveRunning(sink: UsbDac.Sink) {
        // [护栏 G-02·台账登记·1.4.4 恢复] 硬门槛：暂停态 / 未播放态一律不启动 worker。
        // 1.4.3 加的「dataFlowing = !paused && framesWritten>0」把「缓冲期也在写帧」
        // 误当成「App 在播放」，是幽灵播放直接原因（playWhenReady=false 也 -> start now，
        // C 版 16:07:40 实机日志）。严禁再加任何「放宽」兜底：
        // worker 出声唯一判据 = playerPlaying && !paused。
        if (exclusiveStarted || paused || !playerPlaying) return
        // [1.4.5·G-15] 自愈只在 App 播放意图为真时生效（单一真值）。
        if (!appWantsPlayback) return
        AppLog.i("Usb", "uac worker not running while playing -> start now stats=${sink.stats}")
        runCatching { sink.start() }
            .onFailure { AppLog.w("Usb", "uac start on feed failed: ${it.message}") }
        exclusiveStarted = true
        runCatching { sink.resume() }
        AppLog.i("Usb", "1.4.5(G-15) 自愈启动已确定性 resume（清 native paused）")
    }

    // [护栏 G-02 / G-06·1.4.4] 1.4.3 曾在此处放一个 requestWorkerRecovery()：
    // 它开头 `if (exclusiveStarted || paused) return`，而 exclusiveStarted 早被
    // ensureExclusiveRunning 置 true，实机日志 `uac worker recovery` 0 次触发；
    // 又把唯一能救满 ring 的看门狗 seekTo 解卡换成它，导致「一直卡到掉线」。
    // 1.4.4 删除该空转函数，看门狗恢复 seekTo（见 PlaybackService P2-b），
    // worker 启动回到 play()/setPlayerPlaying 收紧入口。护栏台账「六、变更记录」已登记。

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // [护栏 G-03·台账登记] 进度查询只在播放态触发 worker 自愈；暂停冻结在 pausedPosUs。
        // 防「暂停态进度查询把 worker 拉起 / 用旧 anchor 算虚增进度 → 进度条自己走又跳回」。
        val sink = usbSink ?: return delegate.getCurrentPositionUs(sourceEnded)
        if (usbSampleRate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        // 自愈兜底只在播放态执行：暂停时 UI 仍会周期性查询进度，不能借此启动 UAC worker
        //（1.3.2 修复「暂停态开独占自动放歌 / 抽搐播放 / 静音包空转拖死会话」）。
        if (!paused) ensureExclusiveRunning(sink)
        // 暂停或锚点尚未重建时冻结位置：否则会用上一会话的 anchorFrames 算出虚增进度，
        // 表现为「进度条自己往前走，又跳回去」（1.3.2）。
        if (paused || awaitAnchor) return pausedPosUs
        val playedSinceAnchor = sink.framesPlayed - anchorFrames
        return (anchorMediaUs + playedSinceAnchor * 1_000_000L / usbSampleRate).coerceAtLeast(0)
    }

    override fun hasPendingData(): Boolean {
        val sink = usbSink ?: return delegate.hasPendingData()
        // 环形缓冲里还没送出去的帧
        return framesWritten - (sink.framesPlayed - framesBase) > usbSampleRate / 20
    }

    override fun isEnded(): Boolean {
        val sink = usbSink ?: return delegate.isEnded()
        return endOfStream && !hasPendingData()
    }

    override fun playToEndOfStream() {
        val sink = usbSink
        if (sink == null) {
            delegate.playToEndOfStream()
            return
        }
        endOfStream = true
    }

    override fun handleDiscontinuity() {
        usbSink ?: delegate.handleDiscontinuity()
        // 媒体流出现不连续（seek/切换）：进度锚点交给下一帧重建，避免位置错乱
        awaitAnchor = true
        // 1.3.2：seek / 切歌不改变播放或暂停状态，paused 只由 pause()/play() 维护。
        // 这里若清成 false，暂停态拖进度条后 UAC worker 会被进度查询重新拉起。
    }

    override fun flush() {
        // [护栏 G-04 / G-05·台账登记] flush（seek/切歌/重建触发）不把 paused 清成 false
        // （paused 只由 pause()/play() 维护，防暂停态被进度查询重新拉起 worker）；
        // wasPlaying 时主动 start()+resume() 重启 URB（nativeFlush 会停摆等时 URB，
        // 自动跳下一首只 flush 不再 play()，不重启则 framesPlayed 恒 0 / ring 灌满 / 一直缓冲）。
        val sink = usbSink
        if (sink == null) {
            delegate.flush()
            return
        }
        // UAC 环形缓冲有 600ms，不 flush 的话 seek 后会先播一段旧位置的声音
        val wasPlaying = !paused
        AppLog.i(
            "Usb",
            "uac flush (seek/track) framesW=$framesWritten played=${sink.framesPlayed - framesBase} " +
                "wasPlaying=$wasPlaying stats=${sink.stats}",
        )
        sink.flush()
        // 1.3.3：seek / 切歌后不把上一段爆豆音的残留样本带到新位置。
        clearPopcorn()
        // nativeFlush 在清空环形缓冲的同时会顺带停摆等时 URB。ExoPlayer 在「自动跳下一首」
        // 这类已经处于播放态的 seek 中只会调用 flush()，不会再调用 play()；此时若不主动重启
        // URB，设备将永远不再消费数据——日志表现为 framesPlayed 恒为 0、ring 被写满
        // (used=cap)、wdrops 一路飙升，而 hasPendingData() 恒为真，界面就一直在转缓冲。
        // 手动点歌之所以正常，是因为不同规格的曲目触发 configure() 重建会话并重新 start()，
        // 规格相同的下一首走不到这条路径，于是卡死。
        // [1.4.25·拖进度后无声根治] seek/切歌是明确的用户操作：只要 flush 前处于播放态
        // (wasPlaying=!paused)，就必须让 UAC 解除 pause 并恢复出声。旧代码附加了
        // `&& appWantsPlayback`，但 seek 瞬间该真值可能被重建假意图污染成 false，
        // 于是 flush 后 UAC 仍停在 nativePause(true) → worker 不从 ring 取数 →
        // Media3 进度照常推进、ring 无人消费 → "进度条在走但不出声"。
        // 去掉该附加条件，播放态 seek 无条件 resume（nativePause(false) 幂等）。
        if (wasPlaying) {
            // [1.4.5·G-15] flush 后重启 URB 必须清 native paused，
            // 否则 seek/切歌后 worker 又停在静音态，ring 无人消费（1.4.4 B 包同源）。
            if (!exclusiveStarted) {
                sink.start()
                exclusiveStarted = true
            }
            runCatching { sink.resume() }
                .onFailure { error ->
                    AppLog.w("Usb", "uac resume after flush failed: ${error.message}")
                }
            // 同步把真值/闸对齐到播放态，防止后续帧被 G-16 门槛误丢：
            // 用户既然在播放态拖进度，意图就是继续播，这里显式纠正被污染的假意图。
            appWantsPlayback = true
            playerPlaying = true
            userExplicitlyPaused = false
            gateDroppedFrames = 0L
            acquireWakeLock()
        }
        // native 的 framesPlayed 是单调累加的，位置必须减掉这个基准，
        // 否则 seek 之后进度会从"上一首累计的值"继续往前跑
        framesBase = sink.framesPlayed
        framesWritten = 0L
        endOfStream = false
        // 进度锚点交给下一帧重建（presentationTimeUs 已是 seek 后的正确媒体时间）
        awaitAnchor = true
        // 1.3.2：flush 常被 seek / 换曲 / 重建输出触发，此时若播放器处于暂停态，
        // 不能把 paused 清成 false，否则进度查询会把 UAC worker 重新拉起。
        // wasPlaying 已按 flush 前的 paused 计算，恢复播放交给后续 play()。
        // [1.4.5·清单 3] seek / 换曲是正常操作，清掉跨帧满 ring 丢帧计数，
        // 避免把上一首偶发丢帧累计到新会话误触发满 ring 本地恢复。
        consecutiveWriteStalls = 0
    }

    override fun play() {
        // [1.4.5·G-15 闭合] play() 不再自成一套「清 paused + start + resume」，
        // 统一走 setPlayerPlaying(true) -> reconcileWorker 单点对齐，消除第二真值源。
        // 这是收紧：出声仍只由 appWantsPlayback=true 驱动，没有额外放宽条件。
        val sink = usbSink
        if (sink == null) {
            playerPlaying = true
            delegate.play()
            return
        }
        // [1.4.10·G-51] 用户明确恢复播放：闸由 setPlayerPlaying(true) 统一解除。
        setPlayerPlaying(true)
        pushGain()
        // 从暂停恢复：重新持锁，避免后台/灭屏时休眠导致爆音
        acquireWakeLock()
    }

    override fun pause() {
        val sink = usbSink
        if (sink == null) {
            playerPlaying = false
            delegate.pause()
            return
        }
        // [1.4.5·G-15 闭合] 暂停同样走单一真值：setPlayerPlaying(false) -> reconcile
        // 确定性置 native paused=1（只发静音包保活）+ 冻结进度 + 清 ring。
        // 保留 flush() 清掉未送到 DAC 的旧数据，避免恢复时「倒带」约 2 秒（G-07）。
        runCatching { sink.flush() }
        // [1.4.10·G-51] 用户明确暂停：闸由 setPlayerPlaying(false) 统一立（见该函数），
        // 不再在此单独赋值——真实暂停走 onEvents 时这个函数是唯一汇聚点。
        setPlayerPlaying(false)
    }

    override fun reset() {
        delegate.reset()
        releaseUsb()
        // [1.4.24·"嘟一声"根治] reset() 不再清闸。
        // 时序铁证（用户日志 02:47:46）：暂停态开独占 → 重建窗口内 setPlayerPlaying(false)
        // 已把闸立成 true（真暂停）→ 随后 prepare()/stop() 触发本 reset() 把闸清回 false →
        // configure 先按暂停(appWantsPlayback=false) → 12 帧真实 PCM 流入 → G-51 自愈
        // 看到闸=false 误判"重建假意图污染" → 强行 G-02 START 出声（嘟一声）→ finally
        // setPlayerPlaying(false) 再 PAUSE。真暂停由 pause()→setPlayerPlaying(false) 立闸，
        // 不依赖 reset；重建窗口结束也有 setPlayerPlaying(真实值) 对齐。故 reset 清闸是纯有害。
        // 只有 release()（真·播放器销毁）才清闸，防跨会话残留。
        // userExplicitlyPaused 保持不变。
    }

    override fun release() {
        delegate.release()
        releaseUsb()
        // [1.4.9·G-41] 同上，真销毁清闸。
        userExplicitlyPaused = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChuiziMusic:UsbExclusive",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.i("Usb", "wakelock acquired (exclusive playback)")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
            AppLog.i("Usb", "wakelock released")
        }
        wakeLock = null
    }

    private fun releaseUsb() {
        // [1.4.6·X03] 切歌 / rebuild 窗口释放 USB 带宽（UAC 会话即将关闭），防声卡掉线。
        if (usbSink != null) UsbExclusivePerformance.onExclusiveSessionStopped(appContext)
        UsbDacSinkRef.current = null
        if (UsbExclusiveSinkRef.current === this) UsbExclusiveSinkRef.current = null
        scratchStreaming = false
        clearPopcorn()
        popcornLogged = false
        zeroLossLogged = false
        zeroLossMissLogged = false
        zeroLossDspBypassLogged = false
        // [v22·音量] 会话销毁：解绑硬件写入口，清掉"支持硬件音量"状态，避免 UI 继续显示。
        UsbVolume.handoff?.bindHardwareWriter(null)
        UsbVolume.onSessionClosed()
        releaseWakeLock()
        KeepAlive.stop()
        usbSink?.let { sink ->
            try { sink.close() } catch (error: Throwable) {
                AppLog.w("Usb", "uac close failed: ${error.message}")
            }
        }
        usbSink = null
        usbFormat = null
        framesWritten = 0L
        framesBase = 0L
        endOfStream = false
        exclusiveStarted = false
        lastSoundStateLogged = null   // [1.4.5] 会话销毁，翻转日志重新起算
        gateDroppedFrames = 0L
        // [1.4.9·G-41] 修复 1.4.8 独占自动断续：原「会话释放即无条件清闸」会在
        // configure-rebuild / 插拔 / 切歌重建时把「用户显式暂停」挡板清成 false，
        // 重建 configure 先按暂停(appWantsPlayback=false)后，G-20 门槛数据流自愈
        // 看到有真实 PCM 流入就误判成「假意图污染」，强行拉起播放 → 反复启停=断续。
        // 改为：重建不清闸；只有用户真正 play()/pause() 才改这个挡板（见 play/pause）。
        // reset()/release() 这类真·会话销毁才清闸，交给调用方显式处理。
        // userExplicitlyPaused 保持不变（重建后仍维持用户此前的暂停/播放意图）。
    }

    /* ---------------- 直通给 delegate 的部分 ---------------- */

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        delegate.getFormatOffloadSupport(format)

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        delegate.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        usbSink?.let { playbackParameters } ?: delegate.playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        delegate.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean = usbSink?.let { skipSilence } ?: delegate.skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        delegate.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? =
        usbSink?.let { audioAttributes } ?: delegate.audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        delegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        auxEffect = auxEffectInfo
        delegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        if (usbSink == null) delegate.setPreferredDevice(audioDeviceInfo)
        // 独占期间不能把系统输出钉到 USB：那块卡已被我们从系统里摘掉
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun getAudioTrackBufferSizeUs(): Long =
        usbSink?.let { 600_000L } ?: delegate.audioTrackBufferSizeUs

    override fun enableTunnelingV21() {
        if (usbSink == null) delegate.enableTunnelingV21()
    }

    override fun disableTunneling() {
        if (usbSink == null) delegate.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        this.offloadMode = offloadMode
        delegate.setOffloadMode(offloadMode)
    }

    override fun setVolume(volume: Float) {
        // 独占时系统音量对这条通道无效，但播放器自己的音量要叠加进来
        playerVolume = volume
        UsbVolume.playerVolume = volume
        if (usbSink == null) {
            delegate.setVolume(volume)
        } else {
            pushGain()
        }
    }

    /** 供 UI/日志展示当前是否真的在独占 */
    fun describe(): String {
        val sink = usbSink ?: return "shared"
        return "UAC ${usbSampleRate}Hz ${sink.channels}ch ${sink.enc * 8}bit ${sink.deviceName}"
    }

    /* ---------------- 唱盘爆豆音：随音乐叠加进 UAC（1.3.3） ---------------- */

    /**
     * 登记一段爆豆音采样，之后由 [handleBuffer] 逐帧混进主音乐 PCM。
     *
     * 与打碟旁路不同：这里**不新开 URB、不接管流**（那会把正在放的音乐打断），
     * 只在音乐样本转成整数前叠一下，随音乐一起写进同一条 UAC 会话。
     *
     * @param pcm 16bit 立体声交错样本
     * @param sampleRate 采样采样率（资源 16kHz，UAC 可能是 44.1k/96k/352.8k，
     *                   由 [takePopcornMix] 线性插值重采样）
     * @param volume 0..1 相对音量，与 SoundPool 那条路径取同一个随机值
     * @return true 已交由 UAC 叠加；false 当前没有可用独占会话，调用方走 SoundPool
     */
    fun enqueuePopcorn(pcm: ShortArray, sampleRate: Int, volume: Float): Boolean {
        // [v13·爆豆音开关真值] 关掉唱盘爆豆音后，即使 UI 的 LaunchedEffect 因竞态漏调
        // stop()，底层也绝不再把爆豆样本叠进 UAC，避免"关了却还响"。
        if (!UsbExclusiveSinkRef.popcornEnabled) return false
        val sink = usbSink ?: return false
        if (!sink.alive || sampleRate <= 0 || pcm.size < 4) return false
        // 主播放器暂停时 UAC 流停摆，叠进去也没人消费；这种时刻交给 SoundPool 兜底。
        if (paused || !exclusiveStarted) return false
        synchronized(popcornLock) {
            // 与 SoundPool maxStreams=3 对齐：满了就丢最早的一层，绝不打断正在播的层。
            while (popcornLayers.size >= PopcornMaxLayers) {
                popcornLayers.removeFirst()
            }
            popcornLayers.addLast(PopcornLayer(pcm, sampleRate, volume.coerceIn(0f, 1f)))
        }
        if (!popcornLogged) {
            popcornLogged = true
            AppLog.i("Usb", "唱盘爆豆音改走 UAC 叠加（独占下 SoundPool 默认路由听不到）")
        }
        return true
    }

    /** 停止叠加（暂停 / 关闭开关 / 释放会话时调用）。 */
    fun clearPopcorn() {
        synchronized(popcornLock) {
            popcornLayers.clear()
        }
    }

    /**
     * 生成本帧的叠加计划：长度固定 dstFrames*2（L/R），各层线性插值重采样后累加，
     * 播完的层移除。返回 null 表示没有待叠加音效，[convert] 走原路径、零额外开销。
     */
    private fun takePopcornMix(dstFrames: Int, dstRate: Int): FloatArray? {
        if (dstFrames <= 0 || dstRate <= 0) return null
        return synchronized(popcornLock) {
            if (popcornLayers.isEmpty()) return@synchronized null
            val out = FloatArray(dstFrames * 2)
            val finished = ArrayList<PopcornLayer>(PopcornMaxLayers)
            for (layer in popcornLayers) {
                val pcm = layer.pcm
                val srcFrames = pcm.size / 2
                val step = layer.sampleRate.toDouble() / dstRate.toDouble()
                var cursor = layer.cursor
                var exhausted = false
                for (f in 0 until dstFrames) {
                    if (exhausted) {
                        cursor += step
                        continue
                    }
                    val i = cursor.toInt()
                    if (i < 0 || i >= srcFrames) {
                        exhausted = true
                        cursor += step
                        continue
                    }
                    val frac = (cursor - i).toFloat()
                    val j = (i + 1).coerceAtMost(srcFrames - 1)
                    val l = ((pcm[i * 2].toFloat() * (1f - frac) +
                        pcm[j * 2].toFloat() * frac) / 32768f) * layer.gain
                    val r = ((pcm[i * 2 + 1].toFloat() * (1f - frac) +
                        pcm[j * 2 + 1].toFloat() * frac) / 32768f) * layer.gain
                    out[f * 2] += l
                    out[f * 2 + 1] += r
                    cursor += step
                }
                if (exhausted) finished.add(layer) else layer.cursor = cursor
            }
            for (layer in finished) popcornLayers.remove(layer)
            out
        }
    }

    /* ---------------- 打碟（刮擦）旁路：UI 层直接写 UAC ---------------- */

    /**
     * 把打碟产生的 PCM 直接写进当前 UAC 会话。
     *
     * 背景（1.2.13 修复）：刮擦音由 ScratchSoundController 用一条独立的 AudioTrack
     * 旁路播放。USB 独占时这块声卡已从系统里摘除，AudioTrack 的默认路由找不到它，
     * 于是打碟声只能从手机扬声器出来。要让打碟声与音乐走同一条通道，
     * 就必须把刮擦 PCM 交给这里的 sink。
     *
     * 关键点：刮擦开始前主播放器已经 pause()，而 pause() 会把等时 URB 停摆
     * （nativePause + nativeFlush），环形缓冲里的数据没人消费。所以这里必须在首次
     * 写入前把 URB 重新拉起，否则写进去只会堆在 ring 里、DAC 一声不出。
     *
     * 播放恢复时 ExoPlayer 自己会 flush（清掉残留的刮擦帧、重置 framesBase 与锚点），
     * 因此这里**不参与** framesWritten / 进度锚点记账，避免污染音乐播放的位置计算。
     *
     * @param sampleRate 刮擦 PCM 的采样率；与 UAC 会话不一致时必须回退（不能重采样，否则失同步）
     * @param pcm 小端 16bit 立体声交错样本
     * @return true 表示已交给 UAC；false 表示条件不满足，调用方应回退到 AudioTrack
     */
    fun writeScratchFrames(
        sampleRate: Int,
        pcm: ShortArray,
        frames: Int,
    ): Boolean {
        val sink = usbSink ?: return false
        if (!sink.alive) return false
        // 采样率不一致宁可不接管：混进不同速率的 PCM 会整个变调
        if (sampleRate <= 0 || sampleRate != sink.sampleRate) return false
        val channels = sink.channels.coerceAtLeast(1)
        val enc = sink.enc
        val frameBytes = (enc * channels).coerceAtLeast(1)
        if (frames <= 0) return false
        val need = frames * frameBytes
        if (scratchOutBytes.size < need) scratchOutBytes = ByteArray(need)
        packScratchFrames(pcm, frames, channels, enc, scratchOutBytes, need)

        // URB 可能是停摆状态（主播放器在刮擦期间处于暂停态），先把流拉起来
        if (!exclusiveStarted) {
            sink.start()
            exclusiveStarted = true
        }
        ensureScratchStreamRunning(sink)

        // nativeWrite 有内部限时，一次写不完就循环补齐（与音乐写入同一套策略）
        var off = 0
        val deadline = System.currentTimeMillis() + WRITE_DEADLINE_MS
        while (off < need) {
            val wrote = runCatching { sink.write(scratchOutBytes, off, need - off) }
                .getOrDefault(-1)
            if (wrote > 0) {
                off += wrote
                continue
            }
            if (!sink.alive) {
                AppLog.w("Usb", "scratch write aborted: uac session dead")
                return off > 0
            }
            if (System.currentTimeMillis() > deadline) {
                AppLog.w("Usb", "scratch write timeout, drop ${need - off} bytes")
                break
            }
            Thread.sleep(2)
        }
        return true
    }

    /**
     * 打碟结束：把临时接管的 URB 交还给音乐通道。
     *
     * 只有在**主播放器仍处于暂停态**时才做清理（清环形缓冲 + 重新停摆 URB）：
     * 若此时 ExoPlayer 已经恢复播放，它自己的 flush 已经处理过残留数据，
     * 这里再 flush 一次会把刚写进去的音乐帧也倒掉，反而造成断音。
     */
    fun endScratchOutputStream() {
        if (!scratchStreaming) return
        scratchStreaming = false
        val sink = usbSink ?: return
        if (!paused) return   // 播放器已恢复播放：由 ExoPlayer 自己收尾
        runCatching { sink.flush() }
        framesBase = sink.framesPlayed
        framesWritten = 0L
        awaitAnchor = true
        runCatching { sink.pause() }
        AppLog.i("Usb", "scratch -> uac released (player still paused)")
    }

    /**
     * 保证 UAC 流在打碟期间真的在往外送数据。
     *
     * `controller.pause()` 是异步落到这里的：刮擦线程可能先一步 resume 了 URB，
     * 随后才收到 pause()（内部也是 flush + pause），于是整段刮擦又被按停、DAC 沉默。
     * 判据很简单——连续多次调用 framesPlayed 都没有前进，说明没人在消费，重新 resume。
     */
    private fun ensureScratchStreamRunning(sink: UsbDac.Sink) {
        val playedNow = sink.framesPlayed
        val now = android.os.SystemClock.elapsedRealtime()
        if (!scratchStreaming) {
            runCatching { sink.resume() }
            acquireWakeLock()
            scratchStreaming = true
            scratchLastPlayed = playedNow
            scratchLastProgressMs = now
            scratchLastRestartMs = 0L
            AppLog.i(
                "Usb",
                "scratch -> uac direct ${sink.sampleRate}Hz ${sink.channels}ch " +
                    "${sink.enc * 8}bit dev=${sink.deviceName}",
            )
            return
        }
        if (playedNow != scratchLastPlayed) {
            scratchLastProgressMs = now
        } else if (
            now - scratchLastProgressMs >= SCRATCH_STALL_AFTER_MS &&
            now - scratchLastRestartMs >= SCRATCH_RESTART_INTERVAL_MS
        ) {
            // 按“无进展的持续时间”判定，而不是按调用次数：resume 是异步生效的，
            // 逐次调用检查会在几十毫秒里连发几十条日志却起不到额外作用
            scratchLastRestartMs = now
            AppLog.w(
                "Usb",
                "scratch stream stalled ${now - scratchLastProgressMs}ms at " +
                    "framesPlayed=$playedNow, restart URB stats=${sink.stats}",
            )
            runCatching { sink.resume() }
        }
        scratchLastPlayed = playedNow
    }

    /** 16bit 立体声 → UAC 目标位深布局（小端、声道交错，多余声道补静音） */
    private fun packScratchFrames(
        pcm: ShortArray,
        frames: Int,
        channels: Int,
        enc: Int,
        out: ByteArray,
        needBytes: Int,
    ) {
        var p = 0
        for (f in 0 until frames) {
            if (p >= needBytes) break
            val base = f * ScratchStereoChannels
            for (ch in 0 until channels) {
                if (p >= needBytes) break
                val sample = when (ch) {
                    0 -> pcm[base]
                    1 -> pcm[base + 1]
                    else -> 0
                }.toInt()
                when (enc) {
                    UsbDac.ENC_16 -> {
                        out[p++] = (sample and 0xFF).toByte()
                        out[p++] = ((sample shr 8) and 0xFF).toByte()
                    }
                    UsbDac.ENC_24 -> {
                        val wide = sample shl 8
                        out[p++] = (wide and 0xFF).toByte()
                        out[p++] = ((wide shr 8) and 0xFF).toByte()
                        out[p++] = ((wide shr 16) and 0xFF).toByte()
                    }
                    else -> {
                        val wide = sample shl 16
                        out[p++] = (wide and 0xFF).toByte()
                        out[p++] = ((wide shr 8) and 0xFF).toByte()
                        out[p++] = ((wide shr 16) and 0xFF).toByte()
                        out[p++] = ((wide shr 24) and 0xFF).toByte()
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_SCRATCH = 64 * 1024

        /**
         * [R75] 降级原因文案（设置页直接显示这段中文，不走 string resource）。
         * 必须把"熔断/音频异常"和"独占会话未建立"分开：旧版一律写成
         * "DAC 不支持当前源"，而日志里真实原因是会话没建起来或熔断，属于误报。
         */
        const val FALLBACK_FUSED = "已因音频异常降级为系统输出，重开独占可重试"
        const val FALLBACK_SESSION_NOT_ESTABLISHED = "独占会话未建立，当前系统输出"
        /**
         * 1.2.4 从 4s 降到 1.2s：写不进时少堵播放线程，卡死场景交给看门狗快速重建。
         * 1.3.2 再降到 300ms：handleBuffer 跑在播放线程，写超时期间 play/pause/seek/拖进度条
         * 全部排队，用户会感到「点了没反应、过好久才变」。nativeWrite 内部限时约 200ms，
         * 300ms 足够一次完整写入加一次重试；真写不进就及早丢帧让看门狗重建会话。
         */
        const val WRITE_DEADLINE_MS = 300L
        const val STATS_INTERVAL_MS = 10_000L
        /** [1.4.5·清单 3] 连续「满 ring 写不进丢帧」的 handleBuffer 达到该次数，
         *  判定 worker 无法消费，做本地确定性恢复：清 ring + reconcile 重新对齐 worker。
         *  1.4.4 曾在此主动 handleUacLost 让上层重建，重建后又回同一死状态形成风暴，
         *  1.4.5 改为本地恢复（收紧而非放宽），杜绝无限 buffering。 */
        const val MAX_WRITE_STALLS = 4
        /** 打碟旁路输出固定 2 声道 */
        const val ScratchStereoChannels = 2
        /** 唱盘爆豆音叠加层数上限，与 SoundPool 的 maxStreams 一致 */
        const val PopcornMaxLayers = 3
        /** framesPlayed 持续这么久不动（ms），认为等时流被按停，需要重新 resume */
        const val SCRATCH_STALL_AFTER_MS = 400L
        /** 两次停摆重启之间的最小间隔（ms） */
        const val SCRATCH_RESTART_INTERVAL_MS = 800L
    }
}

/** 当前活跃的 USB 独占输出实例；UI 层的打碟旁路通过它写入同一条 UAC 会话。 */
internal object UsbExclusiveSinkRef {
    @Volatile
    var current: UsbExclusiveAudioSink? = null

    /**
     * [v13·爆豆音开关真值] 唱盘爆豆音(popcorn)是否允许叠加进 UAC。
     * 由 PlaybackService 的设置流实时写入；关闭时 enqueuePopcorn 直接拒绝入队，
     * 从底层杜绝"开关关了仍有唱针爆豆声"。默认 true 兼容未注入时的旧行为。
     */
    @Volatile
    var popcornEnabled: Boolean = true

    /**
     * [v20·零损失] 由 PlaybackService 从 UsbZeroLossStore 实时注入；为 true 时
     * [UsbExclusiveAudioSink.handleBuffer] 在条件满足时走逐字节直传。
     */
    @Volatile
    var zeroLossEnabled: Boolean = false

    /** [v20·透明降级] 最近一次降级原因；null=无降级（独占正常或已关闭）。设置页据此展示提示。 */
    private val _fallbackReason = MutableStateFlow<String?>(null)
    val fallbackReason: StateFlow<String?> = _fallbackReason

    fun setFallbackReason(reason: String?) {
        _fallbackReason.value = reason
    }
}

/**
 * 后台保活：UAC 直出绕开了 AudioFlinger，系统里没有任何活跃音频输出，
 * 国产 ROM 会认为这个进程没在放歌、一进后台就冻结 → 等时传输停摆。
 *
 * 做法：一条音量 0 的静态 AudioTrack（只写一次静音），并把输出**钉在内置扬声器**上。
 * 不能让它走默认路由：独占时 USB 卡的 ALSA card 已不存在，默认路由仍指向它，
 * 轻则 HAL 反复重试，重则把设备 reset 掉、把独占打飞。
 */
internal object KeepAlive {

    private const val TAG = "Usb"

    @Volatile
    private var track: AudioTrack? = null
    private val lock = Any()

    fun start(context: Context) {
        synchronized(lock) {
            if (track != null) return
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val speaker = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val sampleRate = 48_000
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(1024)
            val built = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (error: Throwable) {
                AppLog.w(TAG, "keepalive build failed: ${error.message}")
                null
            } ?: return
            if (speaker != null) {
                runCatching { built.setPreferredDevice(speaker) }
                    .onFailure { AppLog.w(TAG, "keepalive pin speaker failed: ${it.message}") }
            }
            try {
                built.setVolume(0f)
                built.write(ByteArray(minBuffer), 0, minBuffer)
                built.play()
                built.setLoopPoints(0, minBuffer / 2, -1)
                track = built
                AppLog.i(TAG, "keepalive started (silent, pinned to speaker)")
            } catch (error: Throwable) {
                AppLog.w(TAG, "keepalive start failed: ${error.message}")
                runCatching { built.release() }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            track?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            track = null
        }
    }
}
