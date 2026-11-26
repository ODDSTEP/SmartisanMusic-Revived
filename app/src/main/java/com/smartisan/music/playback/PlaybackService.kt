@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.core.content.ContextCompat
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.smartisan.music.MainActivity
import com.smartisan.music.MusicApplication
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.memory.ListeningMemoryStore
import com.smartisan.music.data.memory.TrackMemory
import com.smartisan.music.data.playback.PlaybackStatsRepository
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.hardware.usb.UsbManager
import com.smartisan.music.data.settings.FileScanSettings
import com.smartisan.music.data.settings.FileScanSettingsStore
import com.smartisan.music.data.settings.isHiddenByFileScan
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.UiSettingsStore
import com.smartisan.music.data.settings.EngineModeStore
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.ui.playback.VinylPopcornSoundController
import com.smartisan.music.diagnostics.DecodeDiagnostics
import com.smartisan.music.diagnostics.PlaybackDiagnostics
import com.smartisan.music.platform.media.AudioFormatProbe
import com.smartisan.music.platform.media.FormatCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 音频效果链（EQ / 低音 / 音量均衡）可安全挂载的最大输出采样率。 */
internal const val AudioFxMaxSafeSampleRateHz = 48_000

class PlaybackService : MediaLibraryService() {

    private var player: Player? = null

    /** 1.4.18·切引擎即时生效：重建 player 需要的工厂对象提升为字段，避免只建一次。 */
    private var engineRenderersFactory: BitPerfectRenderersFactory? = null
    private var engineMediaSourceFactory: DefaultMediaSourceFactory? = null
    private var engineAudioAttributes: AudioAttributes? = null
    private var rebuildingForEngine = false

    /** 1.4.0：normal / relaxed 两套缓冲策略，按是否需 App 解码切换（见 [TunableLoadControl]）。 */
    private var playbackLoadControl: TunableLoadControl? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var localAudioLibrary: LocalAudioLibrary
    private lateinit var libraryExecutor: ListeningExecutorService
    private lateinit var libraryRefreshExecutor: ListeningExecutorService
    private lateinit var libraryExclusionsStore: LibraryExclusionsStore
    private lateinit var fileScanSettingsStore: FileScanSettingsStore
    private lateinit var playbackSettingsStore: PlaybackSettingsStore
    private lateinit var playbackStatsRepository: PlaybackStatsRepository
    private lateinit var playbackSessionStateStore: PlaybackSessionStateStore
    private var playbackSessionStateCoordinator: PlaybackSessionStateCoordinator? = null
    private var playbackPlayCountTracker: PlaybackPlayCountTracker? = null
    private var playbackAudioFxController: PlaybackAudioFxController? = null
    private var playbackMetadataPreloader: PlaybackMetadataPreloader? = null
    private var mediaSessionArtworkBitmapLoader: MediaSessionArtworkBitmapLoader? = null
    private var notificationProvider: ChuiziNotificationProvider? = null
    // [1.4.24·R33 自绘通知] 双轨：custom = 自绘大按钮，system = 系统媒体卡片。
    private var customNotificationProvider: ChuiziCustomNotificationProvider? = null
    @Volatile private var notificationCustomLayout = true
    private var notificationProgressJob: Job? = null
    private var favoriteRepository: FavoriteSongsRepository? = null
    private var listeningMemoryStore: ListeningMemoryStore? = null
    private var lastMemorizedMediaId: String? = null
    private var playedMsPendingFlush = 0L
    // [1.4.5·清单 5·护栏 G-17] 每次赋值都同步给全局 flow，让设置页能如实显示「开关开了但独占没建立」。
    // 用自定义 setter 包一层：下面 7 处 usbExclusiveActive=true/false 赋值点一字不改，
    // 读仍是 @Volatile 真值，播放逻辑语义完全不变（新增的只是可观察状态同步）。
    @Volatile private var usbExclusiveActiveField = false
    private var usbExclusiveActive: Boolean
        get() = usbExclusiveActiveField
        set(value) {
            usbExclusiveActiveField = value
            PlaybackPolicy.reportUsbExclusiveEstablished(value)
        }
    @Volatile private var usbExclusivePref = false
    @Volatile private var plugAutoResumePref = false
    @Volatile private var usbDevices: List<UsbAudioDevice> = emptyList()
    @Volatile private var usbRouteLogged = false
    private var usbPermissionRequested = false

    // [1.4.24·R38] 独占看门狗：开关打开后若 3 秒仍无真实 UAC 会话，把"假独占"记录在案。
    // token 用来作废过期的延迟检查（连续开关/切歌时只认最后一次）。
    private var exclusiveWatchdogToken = 0
    @Volatile private var exclusiveWatchdogRequestedAtMs = 0L
    @Volatile private var usbExclusiveRequestedAtMs = 0L

    // [v10·独占会话健康度熔断] 早期在"会话本身不稳"的设备上（UAC2 异步端点、
    // 主机侧缓冲偏小的 dongle）实测过独占会话 1~2 秒就 session lost（pkterr/underrun 爆表），
    // 那时反复 rebuild 只会无限缓冲 + native 崩溃，所以做了这个熔断。
    // [1.4.25·R74] 基准设备更正：用户的播放设备是「原道 Tears DSP」（UAC2，16/24/32bit），
    // 实测可稳定独占；1.4.24 之前判定的"设备不可靠"是误诊——真因是 BASS 侧崩溃连坐，
    // 已由 UsbExclusiveGuard 的归因修复（只在崩溃时确有独占会话才熔断）解决。
    // 因此这里的熔断只作为"真·硬件不争气"的最后兜底，不再对正常 DAC 生效。
    private val exclusiveLostAtMs = ArrayDeque<Long>()
    @Volatile private var exclusiveDegraded = false
    @Volatile private var exclusiveSessionOpenedAtMs = 0L
    // [v11] 设置流首帧标志：native 崩溃重启后设置里独占仍是 true，而 usbExclusivePref 初始
    // 为 false，首帧会把持久化的 true 误判成"用户重开"而清掉熔断。只有首帧之后的真实
    // 开关翻转才算用户操作，才允许解除持久化熔断、重开独占通道。
    private var usbSettingsObservedOnce = false
    // [v11] 持久化熔断命中后，进程内只做一次"清脏路由 + USB 复位 + 共享输出重建"，
    // 避免 refreshUsbRoute 被切歌/重枚举反复调用又变成重建风暴。
    private var usbGuardCleanupDone = false
    private var pendingStatsLibraryRefreshJob: Job? = null
    private var pendingRatingLibraryRefreshJob: Job? = null
    private var pendingPlaybackStartJob: Job? = null
    private var pendingPlaybackStartFuture: SettableFuture<SessionResult>? = null
    private val playbackStartRequestGeneration = AtomicLong()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 「插入音频设备自动续播」：接入有线耳机 / USB 声卡时自动恢复播放 */
    private val headsetPlugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_HEADSET_PLUG) return
            // 注册时粘性广播会立刻送达一次（耳机本来就插着），不能当成"刚插入"
            if (isInitialStickyBroadcast) return
            if (intent.getIntExtra("state", 0) != 1) return
            AppLog.i("Player", "wired headset plugged")
            maybeAutoResumeOnPlug("wired headset")
        }
    }

    /**
     * 系统媒体音量变化监听（1.2.6）：独占期间软件音量（PCM 增益）要跟随系统弹窗显示的量，
     * 所以这里把系统 STREAM_MUSIC 音量读出来写进 [UsbVolume]，供 UAC sink 作为增益。
     */
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "android.media.VOLUME_CHANGED_ACTION") return
            UsbVolume.syncSystemVolume(this@PlaybackService)
        }
    }

    /** 所有必须在主线程执行的 Player 操作都通过它切回主线程
     * （AudioSink 回调运行在 ExoPlayer 播放线程，直接调 Player 会触发
     *  "Player is accessed on the wrong thread" 致命错误）。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackStartFadeController = PlaybackStartFadeController(serviceScope)
    // [1.4.19·S07] 智能过渡控制器：切歌前淡出、切歌后淡入（默认关，开了才生效）。
    private val smartCrossfadeController = SmartCrossfadeController(serviceScope)
    @Volatile private var exclusionsSnapshot: LibraryExclusions = LibraryExclusions()
    @Volatile private var fileScanSettingsSnapshot = FileScanSettings()
    private val exclusionsReady = CompletableDeferred<LibraryExclusions>()
    /**
     * 1.2.28：误判错误自愈去重。
     * 用户实测「播放失败后必须拖一下进度条再点播放键才能恢复，不拖就永远失败」——
     * 这是 Media3 进入 error 状态后 `play()` 不重新 prepare 的固有行为。
     * 根因（缓存被删 + seek 竞态）已修，这里只做**每首最多一次**的兜底续播。
     */
    private val errorRecoveryIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 1.3.8：3003 自动回退去重，同一 mediaId 只回退一次，避免 prepare 死循环
    private val decodeFallbackIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 1.3.3：设置项的最新快照，供 [logState] 随时打印「当时到底开了什么」。 */
    @Volatile
    private var playbackSettingsSnapshot = PlaybackSettings()

    /**
     * 1.3.3：一帧完整播放状态快照。
     *
     * 用户明确要求：日志要记录「当前播放状态、各类开关条件、到底是什么操作」，
     * 因为人的描述和软件实际执行/实际被操作可能有偏差，事后不能靠猜。
     * 这里把独占/自动续播/打碟/爆豆音/前后台/播放器状态/曲目一次打全，
     * 每帧都带 `op`（人话操作）和 `src`（触发来源），字段含义见 AppLog.LogGlossary。
     */
    private fun logState(op: String, source: String, extra: Map<String, Any?> = emptyMap()) {
        val pl = player
        val s = playbackSettingsSnapshot
        val item = pl?.currentMediaItem
        PlaybackPolicy.update(
            usbExclusiveActive,
            s.excludeDsdEnabled,
        )
        val fields = LinkedHashMap<String, Any?>(30)
        fields["exclusive"] = usbExclusiveActive
        fields["exclusivePref"] = usbExclusivePref
        fields["dsp"] = PcmDsp.enabled
        fields["excludeDsd"] = s.excludeDsdEnabled
        fields["plugAutoResume"] = plugAutoResumePref
        fields["djing"] = s.scratchEnabled
        fields["popcorn"] = s.popcornSoundEnabled
        // [1.4.32·B/R140/R160-1] State 帧补 crossfade 字段：旧实现 State{} 里查不到智能过渡
        // 开关，复盘"智能过渡到底生效没"只能靠 separate 事件行反推。现在每帧带开关+时长基准，
        // 与 crossfadeFade 事件帧、XFD 帧三层对齐（开关态/真混音次数/淡出包络）。
        fields["crossfade"] = com.smartisan.music.data.settings.SmartCrossfadeStore.currentEnabled()
        fields["crossfadeDur"] = com.smartisan.music.data.settings.SmartCrossfadeStore.currentDurationSec()
        fields["fg"] = MusicApplication.isForeground
        fields["repeat"] = pl?.repeatMode ?: -1
        fields["shuffle"] = pl?.shuffleModeEnabled ?: false
        fields["playWhenReady"] = pl?.playWhenReady ?: false
        fields["isPlaying"] = pl?.isPlaying ?: false
        fields["playerState"] = pl?.playbackState ?: -1
        fields["buffering"] = pl?.playbackState == Player.STATE_BUFFERING
        fields["mediaId"] = item?.mediaId
        fields["format"] = item?.localConfiguration?.mimeType
            ?: item?.localConfiguration?.uri?.lastPathSegment?.takeLast(32)
        fields["posMs"] = runCatching { pl?.currentPosition ?: -1L }.getOrDefault(-1L)
        fields["durMs"] = runCatching { pl?.duration ?: -1L }.getOrDefault(-1L)
        // 1.4.3：每帧都带 initiator / traceId；「切歌」帧若窗口内有用户操作则复用其
        // traceId 标 USER，把 UI 帧与切歌帧串起来；其余帧默认 SYSTEM + 独立新号。
        // extra 里显式传入的 initiator/traceId 优先（看门狗、切歌帧会显式给）。
        val linkedUser = if (op == "切歌") AppLog.LastUserAction.recent(1_500L) else null
        fields["initiator"] = if (linkedUser != null) "USER" else "SYSTEM"
        fields["traceId"] = linkedUser?.first ?: AppLog.Trace.next()
        fields.putAll(extra)
        AppLog.state("State", op, source, fields)
    }

    /** 1.3.4 / 问题 17：转码缓冲卡死看门狗，以及暂停态 UAC worker 的播放状态同步。 */
    private var bufferingWatchdog: Runnable? = null

    /**
     * [1.4.5·清单 2·G-16] rebuildOutput 正在 stop()->prepare() 的窗口。
     * 窗口内 onEvents 的瞬时 playWhenReady 不代表 App 播放意图，禁止同步给 sink。
     * 由 rebuildOutput 在 launch 开始置 true、末尾（含异常路径）置 false。
     */
    @Volatile private var outputRebuilding = false

    /** [1.4.24] 同一曲连续缓冲自愈次数：超过上限上报错误并暂停，避免"一直缓冲"死循环。 */
    private var bufferingSelfHealCount = 0
    private var bufferingSelfHealMediaId: String? = null
    private val maxBufferingSelfHeal = 3

    /**
     * [R182·P0·2026-11-26] 每秒一条 P 心跳帧。
     *
     * 用户原话："优先把日志改完善才是最重要的，不然连问题都无法复现，只能看着残缺的
     * 日志内容瞎猜。" 本轮复盘时八处疑问有六处靠猜或读源码——引擎是哪个、enc=4 是整数
     * 还是浮点、为什么进 BUFFERING、零损失何时开关、唱盘为什么转。
     * 统一成一条固定格式后，这些都不再需要猜。
     */
    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                runCatching { logHeartbeat() }
            }
        }
    }

    private var heartbeatJob: Job? = null

    /* ---------------- [R164/R188] 唱盘爆豆音：调度下沉到服务层 ---------------- */

    private var popcornController: VinylPopcornSoundController? = null
    private var popcornLoopJob: Job? = null

    /** 由设置流实时同步（popcornSoundEnabled 的真值副本）。 */
    @Volatile private var popcornSoundOn = false

    /**
     * 旧实现把随机爆豆循环放在 PlaybackScreen 的 LaunchedEffect 里，页面不在 composition
     * 就完全不跑——用户在设置页开开关，日志里连"被闸拦下"都没有一条。现在改由服务层驱动：
     * 开关 + 正在播 + 未搓碟 三个条件与旧口径一致，但不再依赖任何页面。
     */
    private fun ensurePopcornLoop() {
        if (popcornController == null) {
            popcornController = runCatching { VinylPopcornSoundController(this) }.getOrNull()
        }
        if (popcornLoopJob != null) return
        popcornLoopJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
                PopcornScheduler.logFrame(popcornSoundOn, playing, usbExclusiveActive)
                val ctrl = popcornController
                if (!popcornSoundOn || !playing || PopcornScheduler.scratching || ctrl == null) {
                    PopcornScheduler.reason = when {
                        !popcornSoundOn -> "switch-off"
                        !playing -> "not-playing"
                        PopcornScheduler.scratching -> "scratching"
                        else -> "no-controller"
                    }
                    continue
                }
                val interval = PopcornScheduler.nextIntervalMs()
                PopcornScheduler.nextDueAtMs =
                    android.os.SystemClock.elapsedRealtime() + interval
                delay(interval)
                // [1.4.30·R183④] 到了该响的时刻，逐个条件复核并**写明是哪一条把它拦下**。
                // 旧实现在这里直接 continue，日志里只有"没触发"，看不出是"开关关了"
                // 还是"刚好暂停了"，与 R188 的"永远不触发"是同一个坑。
                when {
                    !popcornSoundOn -> PopcornScheduler.reason = "due-switch-off"
                    PopcornScheduler.scratching -> PopcornScheduler.reason = "due-scratching"
                    !runCatching { player?.isPlaying == true }.getOrDefault(false) ->
                        PopcornScheduler.reason = "due-not-playing"
                    else -> {
                        ctrl.playRandomPop()
                        PopcornScheduler.noteTrigger()
                    }
                }
            }
        }
    }

    private fun logHeartbeat() {
        val pl = player ?: return
        val usb = runCatching { UsbExclusiveSinkRef.current?.heartbeat() }.getOrNull() ?: "route=SHARED"
        val item = runCatching { pl.currentMediaItem }.getOrNull()
        val st = when (pl.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        AppLog.i(
            "P",
            "P st=$st pw=${if (pl.playWhenReady) 1 else 0} ip=${if (pl.isPlaying) 1 else 0}" +
                " pos=${pl.currentPosition} dur=${pl.duration} buf=${pl.bufferedPosition}" +
                " eng=${EngineModeStore.mode.value.id}" +
                " n=${(pl.currentMediaItemIndex + 1)}/${
                    runCatching { pl.mediaItemCount }.getOrDefault(0)
                }" +
                " id=${item?.mediaId ?: "-"} $usb",
        )
    }

    private fun armBufferingWatchdog(target: Player) {
        if (bufferingWatchdog != null) return
        val task = Runnable {
            bufferingWatchdog = null
            if (target.playbackState == Player.STATE_BUFFERING && target.playWhenReady) {
                // [R180·2026-11-26·替代 R163 原表述] 先问一句：DAC 到底有没有在出声？
                //
                // 用户实机：界面一直缓冲、posMs 恒 0，但同一段日志 framesSent 每 10 秒
                // 精确涨 441000 帧（= 实时）、underrun=0 —— 声音一直在正常出。
                // 旧看门狗只看 posMs 不动就 seek，每 12 秒打断一次正在播的流，三次后静默暂停，
                // 这就是"播着播着突然进入一直缓冲"的原样。
                //
                // 判据：played 在按实时速度增长 → SINK_REPORT_FAULT（进度上报故障），
                // 只报 WARN，不 seek、不暂停；played 真的不动才是数据源故障，才允许 seek。
                val sinkRate = runCatching { UsbExclusiveSinkRef.current?.outputSampleRate() }.getOrNull() ?: 0
                val playedRate = runCatching { UsbExclusiveSinkRef.current?.recentPlayedRate() }.getOrNull() ?: -1L
                val audible = sinkRate > 0 && playedRate >= (sinkRate * 0.85).toLong()
                if (audible) {
                    AppLog.w(
                        "Player",
                        "WD verdict=SINK_REPORT_FAULT playedRate=${playedRate}fps/${sinkRate}Hz " +
                            "posMs=${runCatching { target.currentPosition }.getOrDefault(-1L)} " +
                            "state=BUFFERING —— 有声音，禁止 seek/暂停（R180）",
                    )
                    AppLog.state(
                        "State", "看门狗-进度上报故障", "PlaybackService.armBufferingWatchdog",
                        mapOf(
                            "initiator" to "WATCHDOG", "verdict" to "SINK_REPORT_FAULT",
                            "playedRate" to playedRate, "sinkRate" to sinkRate,
                            "posMs" to runCatching { target.currentPosition }.getOrDefault(-1L),
                            "action" to "none",
                        ),
                    )
                    armBufferingWatchdog(target)   // 继续观察，等真实进度恢复或真的停摆
                    return@Runnable
                }
                // [1.4.24] 换歌则从 0 计起（切歌不该继承上一首的自愈次数）。
                val mid = runCatching { target.currentMediaItem?.mediaId }.getOrNull()
                if (mid != bufferingSelfHealMediaId) {
                    bufferingSelfHealMediaId = mid
                    bufferingSelfHealCount = 0
                }
                bufferingSelfHealCount++
                // [1.4.24] 数据源异常（转码失败/坏轨）seek 到同一位置无效，连续 N 次仍缓冲
                // 就明确上报并暂停，不再每 12s 无限重试（用户看到的就是"一直缓冲没结果"）。
                if (bufferingSelfHealCount >= maxBufferingSelfHeal) {
                    AppLog.e(
                        "Player",
                        "缓冲连续自愈 $bufferingSelfHealCount 次仍未恢复，" +
                            "verdict=REAL_STALL playedRate=${runCatching { UsbExclusiveSinkRef.current?.recentPlayedRate() }.getOrNull() ?: -1L}" +
                            " sinkRate=${runCatching { UsbExclusiveSinkRef.current?.outputSampleRate() }.getOrNull() ?: 0}，" +
                            "判定数据源异常，暂停并上报（不再无限重试）",
                    )
                    AppLog.state(
                        "State", "缓冲自愈失败-暂停", "PlaybackService.armBufferingWatchdog",
                        mapOf("initiator" to "WATCHDOG", "selfHealCount" to bufferingSelfHealCount,
                            "mediaId" to (runCatching { target.currentMediaItem?.mediaId }.getOrNull() ?: "-")),
                    )
                    bufferingSelfHealCount = 0
                    PlaybackIntentAudit.trace(
                        "缓冲看门狗：连续自愈失败，判定数据源异常",
                        mapOf("selfHealCount" to maxBufferingSelfHeal),
                    ) { runCatching { target.pause() } }
                    return@Runnable
                }
                // 1.4.3：无论走哪条分支都先打一帧可追溯的自愈帧，明确 initiator=WATCHDOG。
                AppLog.state(
                    "State",
                    "看门狗缓冲自愈",
                    "PlaybackService.armBufferingWatchdog",
                    mapOf(
                        "initiator" to "WATCHDOG",
                        "traceId" to AppLog.Trace.next(),
                        "posMs" to runCatching { target.currentPosition }.getOrDefault(-1L),
                        "exclusive" to usbExclusiveActive,
                        "action" to "seekTo-currentPosition(G-06)",
                    ),
                )
                // [护栏 G-06·台账登记·1.4.4 恢复] 缓冲超时一律 seekTo(currentPosition)
                // 强制重新定位解卡。1.4.3 曾在独占时跳过它改调 requestWorkerRecovery()，
                // 而该函数实机 0 次触发（exclusiveStarted 提前 return），满 ring 无人救 →
                // 每秒一次 write timeout 直到 session lost（C 版 11-14 日志 wdrops 2→80）。
                // 1.4.4 恢复 seekTo；恢复 G-01/G-02 后 worker 正常消费 ring，
                // seekTo 触发的 flush 不再是死循环放大器。
                AppLog.w("Player", "1.4.4(G-06) buffering 看门狗：缓冲超时未恢复，强制重新定位解卡 exclusive=$usbExclusiveActive")
                runCatching { target.seekTo(target.currentPosition) }
            }
        }
        bufferingWatchdog = task
        mainHandler.postDelayed(task, 12_000L)
    }

    private fun cancelBufferingWatchdog() {
        bufferingWatchdog?.let { mainHandler.removeCallbacks(it) }
        bufferingWatchdog = null
    }

    private val playbackPolicyPlayerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // [1.4.5·清单 2·G-16] 不让 rebuild 中间态的 playWhenReady 覆盖 App 真实播放意图。
            // 1.4.4 每次 onEvents 都无条件 setPlayerPlaying(player.playWhenReady)，
            // 而 rebuildOutput 会 stop()->prepare()，期间 playWhenReady 瞬时为 false，
            // 于是把 playing=false「假意图」写进 sink -> worker 不启动 -> C 满 ring 死循环。
            // 收紧：重建窗口内跳过这次同步，等 rebuildOutput 末尾用捕获的真值一次性对齐。
            // [1.4.23·幽灵播放根治] 重建窗口内原样跳过全部同步（1.4.5 G-16），但真暂停
            // 例外：用户按暂停时 playWhenReady=false 若恰落进重建窗口被吞掉，sink 的
            // userExplicitlyPaused 闸就立不起来，门槛数据流自愈(UsbExclusiveAudioSink:490)
            // 会把真暂停误判成"重建假意图"→ 强行拉起 worker → 幽灵播放。
            // playing=false 一律立即同步立闸（重建 stop 产生的 false 也是暂停意图，立闸无害，
            // 重建末尾 reconcileWorker 会按真值恢复）；只跳过 playing=true 的假意图。
            if (!outputRebuilding || !player.playWhenReady) {
                UsbExclusiveSinkRef.current?.setPlayerPlaying(player.playWhenReady)
            } else {
                AppLog.i("Usb", "1.4.5(G-16) 重建窗口内跳过 playWhenReady=true 假意图同步")
            }
            // 1.3.5 实验性功能：播放到 DSD 时自动跳下一首（覆盖开关开启前已在队列里的项）
            if (PlaybackPolicy.excludeDsd) {
                val current = player.currentMediaItem
                if (
                    current != null && current.isDsdItem() &&
                        player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED &&
                        player.hasNextMediaItem()
                ) {
                    AppLog.i("Player", "实验性功能：跳过 DSD 曲目 mediaId=${current.mediaId}")
                    player.seekToNext()
                }
            }
            when (player.playbackState) {
                Player.STATE_READY -> {
                    cancelBufferingWatchdog()
                    bufferingSelfHealCount = 0  // [1.4.24] 就绪即清零连续自愈计数
                    // 1.4.4 清单 4：播放器已恢复就绪 → 撤销挂起的 3001 误报 Toast
                    pendingErrorToast?.let {
                        mainHandler.removeCallbacks(it)
                        pendingErrorToast = null
                        AppLog.i("Player", "3001 挂起复核：STATE_READY，静默撤销")
                    }
                }
                Player.STATE_BUFFERING -> armBufferingWatchdog(player)
                else -> Unit
            }
        }
    }

    private val audioFxPlayerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            playbackAudioFxController?.setAudioSessionId(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e("Player", "playback error ${PlaybackDiagnostics.errorLine(error)}")
            logState(
                op = "播放器错误",
                source = "Player.Listener.onPlayerError",
                extra = mapOf(
                    "errorCode" to error.errorCode,
                    "errorLine" to PlaybackDiagnostics.errorLine(error),
                ),
            )
            val item = player?.currentMediaItem
            val failedPlayer = player
            AppLog.e(
                "Player",
                "failed item: ${PlaybackDiagnostics.trackLine(item)}" +
                    "\n  head(原始 MediaItem Uri 的头部，仅用于确认源格式；实际读取的缓存文件请看下方 索引诊断)=" +
                    PlaybackDiagnostics.fingerprint(this@PlaybackService, item) +
                    "\n  dsOpen=${ApeTranscoder.lastOpen()}" +
                    "\n  transcode=${ApeTranscoder.describe(this@PlaybackService, item?.localConfiguration?.uri)}" +
                    "\n  state=seekable=${failedPlayer?.isCurrentMediaItemSeekable}" +
                    " pos=${failedPlayer?.currentPosition} dur=${failedPlayer?.duration}" +
                    " buf=${failedPlayer?.playbackState} playWhenReady=${failedPlayer?.playWhenReady}",
            )
            // 1.2.29：ERROR_CODE_PARSING_CONTAINER_MALFORMED(3001) **不再参与自愈**。
            // 1.2.28 把它当「seek 取消的误判」，于是 prepare() + seekTo(resumeAt) 重试；
            // 但日志证明那是 SEEKTABLE 写坏导致的真实解析失败，seekTo 回原位会立刻复现
            // 同一个 3001（日志 23:15:40.093 自愈 → 23:15:40.136 再次 load error → 3001）。
            // 每首 DFF 都这样重试一次，叠加 Media3 错误后自动跳下一首，就成了用户看到的
            // 「点最高音质 DFF 直接跳过所有 DFF，直到第一首 FLAC 才停下」。
            // 真正的修复在 FlacSeekTablePatcher（相对偏移 + 真实 blockSize），这里只保留
            // 确实是瞬态的 IO 类错误自愈。
            val code = error.errorCode
            val transient =
                code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            val failedId = item?.mediaId
            val resumeAt = failedPlayer?.currentPosition ?: 0L
            val autoRecover =
                transient && !failedId.isNullOrBlank() && errorRecoveryIds.add(failedId)
            // 1.3.8 路线 A：冷门容器被系统 extractor 判 3003 时，非硬解版自动回退
            // 到 ffmpeg 解码前端。markForceTranscode 后重新 prepare 会再走一次
            // DataSource.open() -> needsTranscode() 命中强制转码 -> 转 FLAC 缓存 -> 能播。
            val unsupportedContainer =
                code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            val fallbackUri = item?.localConfiguration?.uri
            val shouldFallback =
                unsupportedContainer &&
                    DecodeRouter.fallbackOnUnsupported &&
                    !PlaybackPolicy.usbExclusiveActive &&
                    fallbackUri != null &&
                    !DecodeRouter.isForceTranscode(fallbackUri) &&
                    !failedId.isNullOrBlank() &&
                    decodeFallbackIds.add(failedId!!)
            if (shouldFallback) {
                DecodeRouter.markForceTranscode(fallbackUri)
                AppLog.w(
                    "Player",
                    "3003 容器不受支持 -> 自动回退 ffmpeg 解码前端: " +
                        "${DecodeDiagnostics.advise(code, fallbackUri, true)}\n" +
                        "  engine=${DecodeRouter.engineId} track=${PlaybackDiagnostics.trackLine(item)}",
                )
                serviceScope.launch {
                    kotlinx.coroutines.delay(250L)
                    val pl = player ?: return@launch
                    if (pl.currentMediaItem?.mediaId != failedId) return@launch
                    pl.prepare()
                    pl.playWhenReady = true
                }
            } else if (unsupportedContainer) {
                AppLog.w(
                    "Player",
                    "3003 容器不受支持（未回退）: " +
                        DecodeDiagnostics.advise(code, fallbackUri, false),
                )
            }
            if (autoRecover) {
                serviceScope.launch {
                    kotlinx.coroutines.delay(300L)
                    val pl = player ?: return@launch
                    if (pl.currentMediaItem?.mediaId != failedId) return@launch
                    AppLog.i(
                        "Player",
                        "误判错误自愈: id=$failedId resumeAt=$resumeAt code=$code",
                    )
                    pl.prepare()
                    if (resumeAt > 0L) pl.seekTo(resumeAt)
                    pl.playWhenReady = true
                }
            }
            // 卡死自愈（1.2.5）：UAC 独占下"Player stuck 10s"（ERROR_CODE_TIMEOUT）大多是
            // UAC 流停滞——看门狗没赶在 ExoPlayer 之前判死时，这里兜底重建输出。
            // rebuildOutput 会 stop→prepare→seekTo 原位置，进度不清零、自动续播。
            if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT && usbExclusiveActive) {
                // 1.3.3：重建会重新 prepare() → DataSource.open()。若此刻转码缓存还没落地，
                // 旧链路会把原始 DFF/APE 交给 Media3 再报一次 3003「不支持格式」。
                // open() 已堵住回退并会等待缓存；这里把重建瞬间的缓存状态打清楚便于复盘。
                AppLog.w(
                    "Usb",
                    "player stuck on exclusive output -> rebuild and resume\n" +
                        "  transcode=${ApeTranscoder.describe(this@PlaybackService, item?.localConfiguration?.uri)}",
                )
                rebuildOutput("stuck recover", delayMs = 400L)
                return
            }
            if (!autoRecover) {
                // [1.4.4 清单 4·B/C 播放失败误报挂起复核] 3001（容器错配）与
                // canceled=true 的 load error 多为前后台切换瞬间缓存落地时的瞬时错配
                // （日志：c2.android.flac.decoder 被喂了 MP3 字节 head=ff fb e0 64，
                //  重试即正确识别 audio/mpeg 恢复）。1.4.2 给 A 包做过「挂起复核」，
                // B/C 这条路径没同步做。这里统一挂起 1200ms：期间播放器恢复播放即
                // 完全静默撤销；确实失败则超时只弹一次 Toast，不重复刷屏。
                val code3001 = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                val toastRunnable = Runnable {
                    pendingErrorToast = null
                    val pl = player
                    // 二次复核：此刻仍在同一首、且仍未恢复播放，才认定真失败。
                    // [v24·R19] 冷启动恢复队列时 playWhenReady=false（用户还没点播放），
                    // 此时缓存落地的瞬时 3001 会在"用户什么都没做"时弹出"播放失败"，
                    // 用户点播放键其实正常。要求用户确有播放意图才弹 Toast。
                    if (pl != null && pl.playbackState != Player.STATE_READY &&
                        !pl.isPlaying && pl.playWhenReady
                    ) {
                        runCatching {
                            Toast.makeText(
                                this@PlaybackService,
                                PlaybackDiagnostics.userMessageRes(error, pl.currentMediaItem),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        AppLog.w("Player", "3001 挂起复核超时：确认真失败，弹一次 Toast code=$code")
                    } else {
                        AppLog.i("Player", "3001 挂起复核：已恢复播放，静默撤销 Toast code=$code")
                    }
                }
                if (code3001) {
                    pendingErrorToast?.let { mainHandler.removeCallbacks(it) }
                    pendingErrorToast = toastRunnable
                    mainHandler.postDelayed(toastRunnable, 1_200L)
                    AppLog.i("Player", "3001 挂起复核：延迟 1200ms 判定是否误报 code=$code")
                } else {
                    runCatching {
                        Toast.makeText(
                            this@PlaybackService,
                            PlaybackDiagnostics.userMessageRes(error, player?.currentMediaItem),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            errorRecoveryIds.clear()
            // 切歌允许同一曲目以后再次触发回退
            decodeFallbackIds.clear()
            // 1.4.3：把「谁发起的这次切歌」写进日志。窗口内有用户操作（拖进度条 /
            // 唱针 / 点下一首）就复用它的 traceId 标 USER，否则 SYSTEM 另起新号。
            // 用户明确要求能一眼分清「我自己拖的还是系统乱搞的」，不再事后猜。
            val recentUser = AppLog.LastUserAction.recent(1_500L)
            val initiator = if (recentUser != null) "USER" else "SYSTEM"
            val traceId = recentUser?.first ?: AppLog.Trace.next()
            logState(
                op = "切歌",
                source = "Player.Listener.onMediaItemTransition",
                extra = mapOf(
                    "reason" to transitionReasonName(reason),
                    "initiator" to initiator,
                    "traceId" to traceId,
                    "userSrc" to (recentUser?.second ?: "-"),
                ),
            )
            // [1.4.30·R183①] TR 切歌聚合帧第一段：源文件位深/采样率/码率 + 扩展名 + 切歌原因，
            // 外加 sink 快照（fmtIn/sink/是否直传都在 heartbeat 里）。
            // 解码链路要等 ExoPlayer 真正打开数据源才确定，所以 dec 先写 PENDING，
            // 由 DecodeRoute.record 打第二帧补齐——两条用同一个 id= 对齐。
            runCatching {
                val extras = mediaItem.mediaMetadata.extras
                val bits = extras?.getInt(LocalAudioLibrary.BitsPerSampleExtraKey, 0) ?: 0
                val rate = extras?.getInt(LocalAudioLibrary.SampleRateHzExtraKey, 0) ?: 0
                val bps = extras?.getInt(LocalAudioLibrary.BitrateBpsExtraKey, 0) ?: 0
                val ext =
                    mediaItem.localConfiguration?.uri?.lastPathSegment
                        ?.substringAfterLast('.', "-")
                        ?: "-"
                DecodeRoute.begin(
                    id = mediaItem.mediaId,
                    srcSummary = "$bits/$rate/$bps ext=$ext",
                    transitionReason = transitionReasonName(reason),
                )
            }
            // [护栏 G-24·1.4.6 取证帧·不臆断] 「下一曲在响、播放条还是上一曲」的同步链路
            // 台账仍标「待进一步定位」，这里只补成对可 grep 帧：把 transition 收到的 mediaId
            // 与播放器当前实际 currentMediaItem 一起打出来，实机核对「transition 帧 ↔ UI 标题
            // 刷新帧」是否成对。不硬改未验证的元数据同步逻辑、不编结论（15 号页 #5 口径）。
            AppLog.i(
                PlaybackDiagnosticsTag,
                "1.4.6(G-24) 切歌元数据取证: transitionId=${mediaItem.mediaId}" +
                    " transitionTitle=${mediaItem.mediaMetadata.title ?: "-"}" +
                    " currentId=${player?.currentMediaItem?.mediaId ?: "-"}" +
                    " currentIndex=${player?.currentMediaItemIndex ?: -1}" +
                    " traceId=$traceId initiator=$initiator（同步链路待实机进一步定位）",
            )
            // 1.4.0：需 App 解码的曲目（B 版冷门格式转码 / C 版流式管道）临时放宽缓冲阈值，
            // 避免后台/USB 独占下凑不够缓冲触发 ExoPlayer「10 秒无进度」看门狗(1003)；
            // 原生直读格式不受影响。探测放 IO 线程，别堵主线程。
            val transitionUri = mediaItem.localConfiguration?.uri
            if (transitionUri == null) {
                playbackLoadControl?.relaxedForTranscode = false
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    val needs = runCatching {
                        ApeTranscoder.needsTranscode(this@PlaybackService, transitionUri)
                    }.getOrDefault(false)
                    withContext(Dispatchers.Main.immediate) {
                        // 1.4.3：IO 探测是异步的，结果回来时可能已经切歌。若把这帧
                        // 探测结果写到新曲目上，会把「放宽缓冲阈值」设置给错误的曲目，
                        // 后台/独占下容易反复触发 1003（B 包切后台卡的放大环节之一）。
                        if (player?.currentMediaItem?.mediaId != mediaItem.mediaId) {
                            AppLog.i("Player", "忽略过期转码探测结果 id=${mediaItem.mediaId}（结果返回前已切歌）")
                            return@withContext
                        }
                        playbackLoadControl?.relaxedForTranscode = needs
                        if (needs) {
                            AppLog.i(
                                "Player",
                                "需转码曲目：临时放宽缓冲阈值，避免后台/独占下 10s 无进度(1003)",
                            )
                        }
                    }
                }
            }
            val pl = player
            // 1.2.30：reason=3 是 PLAYLIST_CHANGED（有人重建了队列），不是「播完自动切歌」。
            // 光看 id 分不清是谁把队列换了，必须带 index/count 和上面的「重建队列」日志对照。
            AppLog.i(
                "Player",
                "transition: ${PlaybackDiagnostics.trackLine(mediaItem)} reason=$reason" +
                    " reasonName=${transitionReasonName(reason)}" +
                    " initiator=$initiator traceId=$traceId" +
                    " idx=${pl?.currentMediaItemIndex}/${pl?.mediaItemCount}" +
                    " id=${mediaItem.mediaId}",
            )
        }
    }

    private fun transitionReasonName(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        // Media3 1.10.1 没有把 OVERRIDDEN 暴露成 Player 常量，用数值 4（@IntDef 定义）。
        4 -> "OVERRIDDEN"
        else -> "UNKNOWN($reason)"
    }

    /** 听歌记忆采集：每首歌第一次真正出声时记一次，播放时长按 30 秒一档累计。 */
    private val memoryListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                flushMemorizedPlayTime()
                return
            }
            val item = player?.currentMediaItem ?: return
            if (item.mediaId == lastMemorizedMediaId) return
            lastMemorizedMediaId = item.mediaId
            memorizeTrack(item)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            lastMemorizedMediaId = null
            flushMemorizedPlayTime()
        }
    }

    private val playbackStartFadePlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val playbackPlayer = player ?: return
            playbackStartFadeController.onPlayWhenReadyChanged(playbackPlayer, playWhenReady)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val playbackPlayer = player ?: return
            playbackStartFadeController.onIsPlayingChanged(playbackPlayer, isPlaying)
        }
    }

    /**
     * [v19·USB日志补全·E状态机/F配置快照] 只记录不干预的播放器状态观测。
     * 用户症状「切后台自动暂停→切前台点继续无声但唱盘转」需要状态机 + 配置快照才能对账：
     * 每次播放状态/播放意图变化时，带时间戳记录 isPlaying/playWhenReady/state/position/isUsb，
     * 并读 USB 独占实际会话状态（不是开关），定位开关与实际不同步。
     * Media3 内部处理音频焦点，这里用状态变化间接反映焦点事件，不改焦点行为。
     */
    private val usbDiagnosticsPlayerListener = object : Player.Listener {
        // [1.4.32·B/R182] BUF 缓冲进出帧去重：只在 BUFFERING↔非BUFFERING 边界打，避免刷屏。
        private var lastBufEnter = false
        private var lastBufAtMs = 0L

        override fun onPlaybackStateChanged(playbackState: Int) {
            val pl = player ?: return
            val stateName =
                when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
            AppLog.i(
                "UsbDiag",
                "state机: state=$stateName isPlaying=${pl.isPlaying} playWhenReady=${pl.playWhenReady} " +
                    "pos=${pl.currentPosition} isUsb=${UsbDac.isActive} exclusiveDisabled=${UsbExclusiveGuard.isDisabled(applicationContext)}",
            )
            // [1.4.32·B/R182] BUF 帧：缓冲进入/退出一行聚合，带 st=状态、why=触发方向、
            // buf=ExoPlayer 缓冲百分比、pos/dur、isUsb。为何进入缓冲（seek/切歌/underrun）
            // 由调用方在 seek/切歌处另打票据，这里只记录"进出"事实和持续时长。
            val entering = playbackState == Player.STATE_BUFFERING
            if (entering != lastBufEnter) {
                val now = SystemClock.elapsedRealtime()
                val why = if (entering) {
                    if (!pl.playWhenReady) "renderer_not_ready(paused)"
                    else if (UsbDac.isActive) "usb_source_or_ring"
                    else "renderer_not_ready"
                } else "ready"
                AppLog.state(
                    "BUF",
                    if (entering) "enter" else "exit",
                    "Player.Listener.onPlaybackStateChanged",
                    mapOf(
                        "st" to stateName,
                        "why" to why,
                        "durMs" to (if (!entering && lastBufAtMs > 0L) now - lastBufAtMs else 0L),
                        "buffered" to pl.bufferedPercentage,
                        "pos" to pl.currentPosition,
                        "isUsb" to UsbDac.isActive,
                        "playWhenReady" to pl.playWhenReady,
                    ),
                )
                lastBufEnter = entering
                if (entering) lastBufAtMs = now
            }
            // [1.4.32·验收13] 自然播完（STATE_ENDED）后 playWhenReady 仍为 true，
            // 而进度 ticker 靠 isPlaying 门控会停摆 → 通知图标卡在"暂停"态不再刷新。
            // 这里在 ENDED 帧主动重建一次通知：图标改绑 isPlaying 后立即显示播放键。
            if (playbackState == Player.STATE_ENDED) {
                val session = mediaLibrarySession
                if (session != null && notificationCustomLayout) {
                    onUpdateNotification(session, false)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val pl = player ?: return
            // [1.4.24·暂停追责] 旧日志只有 reason=1(USER_REQUEST)，无法回答"到底是谁调的"。
            // 这里补调用栈 + 最近用户操作 + 系统音乐活跃态，下次再出现"自己暂停"能直接定位。
            //
            // [1.4.24·R39] 重要修正：这里的 `caller=` 是 media3 **内部派发栈**
            // （ExoPlayerImpl → ListenerSet → onPlayWhenReadyChanged），业务调用方早返回了，
            // 根本不在栈里 —— 打了等于没打。真正的动因由 [PlaybackIntentAudit] 在**下指令当场**
            // 记成「票据」，这里按时间窗口取出来贴上，日志里 `intent=` 直接写明是谁干的。
            AppLog.audit(
                "UsbDiag",
                if (playWhenReady) "播放意图->播放" else "播放意图->暂停",
                "Player.Listener.onPlayWhenReadyChanged",
                mapOf(
                    "reason" to reasonName(reason),
                    "isPlaying" to pl.isPlaying,
                    "state" to playbackStateName(pl.playbackState),
                    "pos" to pl.currentPosition,
                    "isUsb" to UsbDac.isActive,
                    "musicActive" to (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive,
                    "lastUser" to (AppLog.LastUserAction.recent(5_000L)?.let { "${it.first}/${it.second}" } ?: "none"),
                    // [1.4.24·R39] 谁下的指令（票据）——这才是真正可定位到行的来源
                    "intent" to (PlaybackIntentAudit.consumeRecentTicket() ?: "none"),
                ),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pl = player ?: return
            AppLog.i(
                "UsbDiag",
                "state机: isPlaying=$isPlaying playWhenReady=${pl.playWhenReady} " +
                    "state=${playbackStateName(pl.playbackState)} pos=${pl.currentPosition} " +
                    "isUsb=${UsbDac.isActive}",
            )
        }
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    /**
     * [1.4.24] ExoPlayer 的 playWhenReady reason 翻译成人话（常量值以 media3 1.10.1 为准，
     * 已用 javap 核对：1=用户 2=焦点丢失 3=音频断开 4=远程 5=播完 6=抑制过久）。
     * 之前日志只有裸数字，排障时要查文档才知道含义，浪费大量时间。
     */
    private fun reasonName(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST(1)"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS(2)"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY(3)"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE(4)"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM(5)"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG(6)"
        else -> "OTHER($reason)"
    }

    /**
     * [1.4.19·S07] 智能过渡的自动切歌监听：
     * - 自动播到结尾切下一曲（AUTO）：若上一首已淡出，则给新曲淡入；
     * - 用户暂停：撤销可能进行中的淡出并恢复音量，避免卡在半淡出静音。
     */
    private val smartCrossfadePlayerListener = object : Player.Listener {
        /** [1.4.23] 上一次 transition 的 mediaId，用于区分"真换歌"与队列重建时同一首重复回调。 */
        private var lastTransitionMediaId: String? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // [1.4.20·路径A] 无论何种切歌，都先把"当前曲→下一曲"喂给交叉淡化队列桥，
            // 让当前曲 DataSource 在尾部窗口能预取下一曲。这段对所有 reason 生效。
            updateCrossfadeQueue()
            // [1.4.20·P1 ReplayGain] 每次切歌后台读当前曲目标签增益并注入 DSP（两条链都生效）。
            if (mediaItem != null) applyReplayGainForItem(mediaItem)
            val playbackPlayer = player ?: return
            val newId = mediaItem?.mediaId
            val reallyChanged = newId != null && newId != lastTransitionMediaId
            lastTransitionMediaId = newId
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                smartCrossfadeController.onAutoTransition(playbackPlayer)
            } else if (reallyChanged) {
                // [1.4.23·过渡生效兜底] PLAYLIST_CHANGED / SEEK 等真换歌也补一次淡入。
                // 真机日志显示声音库切歌几乎全是这两个 reason，旧逻辑只认 AUTO 导致过渡形同虚设。
                smartCrossfadeController.onTransitionFadeIn(playbackPlayer, newId)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                smartCrossfadeController.cancel(player)
            }
        }
    }

    /** [1.4.20·P1] 后台读当前曲的 ReplayGain 标签，注入 PcmDsp/BASS 增益链。 */
    private fun applyReplayGainForItem(item: MediaItem) {
        val settings = playbackSettingsSnapshot
        if (!settings.replayGainEnabled) {
            PcmDsp.applyTrackReplayGain(null, null, preferAlbum = false)
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            val uri = item.localConfiguration?.uri ?: return@launch
            val gain = ReplayGainReader.read(this@PlaybackService, uri)
            PcmDsp.applyTrackReplayGain(gain?.trackDb, gain?.albumDb, preferAlbum = false)
        }
    }

    /** [1.4.20·路径A] 把播放器队列的当前曲/下一曲推给 BASS 交叉淡化桥。 */
    private fun updateCrossfadeQueue() {
        val pl = player ?: return
        val idx = pl.currentMediaItemIndex
        if (idx < 0) return
        val current = pl.getMediaItemAt(idx).localConfiguration?.uri
        val nextUri = if (idx + 1 < pl.mediaItemCount) {
            pl.getMediaItemAt(idx + 1).localConfiguration?.uri
        } else null
        BassCrossfadeQueue.update(current, nextUri)
        // [1.4.20·Morvanium] 智能过渡开启时后台预分析当前曲与下一曲（切歌即预热，读尾部时零阻塞）。
        if (com.smartisan.music.data.settings.SmartCrossfadeStore.currentEnabled() && BassEngine.isReady) {
            serviceScope.launch(Dispatchers.IO) {
                current?.let { MorvaniumAnalyzer.analyze(this@PlaybackService, it) }
                nextUri?.let { MorvaniumAnalyzer.analyze(this@PlaybackService, it) }
            }
        }
    }

    /**
     * 1.2.26：**每一次切歌**都补一次时长/音质。
     *
     * 旧版只在「从列表点歌」这一条路径（秒切快路径 / 队列重建）里调 [enrichDuration]，
     * 于是同一首 DFF：从列表点进去有进度条，按「下一首」切过去就退回 0:00 —— 用户看到的
     * 「只有部分 DFF 修好了」其实是「只有点播的那几首修好了」。
     *
     * 这里只做探测 + 写 [FormatCache] + 通知 UI 重组，**不 replaceMediaItem**（那会重建
     * MediaSource 造成一次断音）。
     */
    private val durationBackfillListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return
            backfillDurationAsync(mediaItem.mediaId)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // 队列刚装载时 transition 不一定触发，这里兜一次
            player?.currentMediaItem?.mediaId?.let(::backfillDurationAsync)
        }
    }

    /** 正在探测时长的 mediaId（transition + timeline 两个回调可能同时打进来）。 */
    private val backfillingMediaIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun backfillDurationAsync(mediaId: String) {
        if (mediaId.isBlank()) return
        val pl = player ?: return
        val index = pl.currentMediaItemIndex
        if (index < 0 || index >= pl.mediaItemCount) return
        val item = pl.getMediaItemAt(index)
        if (item.mediaId != mediaId) return
        if ((item.mediaMetadata.durationMs ?: 0L) > 0L) return
        val uri = item.localConfiguration?.uri ?: return
        // 缓存已有就直接让 UI 重读，省掉一次探测
        if (runCatching { FormatCache.get(uri.toString(), 0L) }.getOrNull()?.durationMs ?: 0L > 0L) {
            PlaybackFormatBus.bump()
            return
        }
        if (!backfillingMediaIds.add(mediaId)) return
        serviceScope.launch {
            try {
                val enriched =
                    withContext(Dispatchers.IO) {
                        runCatching { enrichDuration(item) }.getOrDefault(item)
                    }
                if (enriched !== item) {
                    PlaybackFormatBus.bump()
                    AppLog.i(
                        PlaybackDiagnosticsTag,
                        "切歌补时长 mediaId=$mediaId durationMs=${enriched.mediaMetadata.durationMs}",
                    )
                }
            } finally {
                backfillingMediaIds.remove(mediaId)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.sessionStart(this)
        ApeTranscoder.logCacheSummary(this)
        playbackStatsRepository = PlaybackStatsRepository.getInstance(this)
        favoriteRepository = FavoriteSongsRepository.getInstance(this)
        localAudioLibrary = LocalAudioLibrary(
            context = this,
            playbackStatsProvider = playbackStatsRepository::getStats,
            playbackStatsByIdsProvider = playbackStatsRepository::getStats,
        )
        libraryExclusionsStore = LibraryExclusionsStore(this)
        // 1.2.26：文件被移动/重命名后 MediaStore 会换号，索引靠 stableKey 自愈，
        // 但收藏/统计/歌单/隐藏项仍指向旧 id。索引重建时把映射接过来就地迁移。
        localAudioLibrary.onMediaIdRemapped = { mapping ->
            serviceScope.launch(Dispatchers.IO) {
                MediaIdRemapper.remap(this@PlaybackService, mapping)
            }
        }
        playbackSettingsStore = PlaybackSettingsStore(this)
        fileScanSettingsStore = FileScanSettingsStore(this)
        playbackSessionStateStore = PlaybackSessionStateStore(this)
        libraryExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        libraryRefreshExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        engineAudioAttributes = audioAttributes

        // 1.4.0：缓冲策略只有 normal / relaxed 两套（后者供 App 解码曲目放宽阈值），
        // 共用同一个 allocator；1.3.9 遗留的「极速播放 instant」死分支已删除。
        val sharedAllocator =
            androidx.media3.exoplayer.upstream.DefaultAllocator(
                true,
                androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE,
            )
        val tunableLoadControl = TunableLoadControl(sharedAllocator)
        playbackLoadControl = tunableLoadControl
        val renderersFactory = BitPerfectRenderersFactory(
            context = this,
            // [v10·熔断] 熔断降级后即使开关仍开，也强制 configure 走共享输出，
            // 防止 rebuild 又把坏 UAC 通道 claim 回来（用户手动重开开关会清熔断态恢复独占）。
            // [v11] 三保险：开关开 ∧ 内存态未熔断 ∧ 持久化熔断未命中（native 崩溃后置位，
            // 跨进程存活）。任一命中都让 configure 走共享输出，杜绝重启后再冲进坏 UAC 通道。
            usbExclusiveEnabled = {
                usbExclusivePref && !exclusiveDegraded && !UsbExclusiveGuard.isDisabled(applicationContext)
            },
            onUsbExclusiveLost = { handleUsbExclusiveLost() },
            onUsbExclusiveOpened = { handleUsbExclusiveOpened() },
        )
        engineRenderersFactory = renderersFactory
        startHeartbeat()
        ensurePopcornLoop()
        // 1.2.16：转码拦截下沉到 DataSource 层（TranscodingDataSource），
        // 队列装载不再逐首同步转码，主线程零阻塞（方案 B）
        val mediaSourceFactory = DefaultMediaSourceFactory(
            TranscodingDataSourceFactory(
                context = this,
                upstream = DefaultDataSource.Factory(this),
            ),
            HiResWavExtractorsFactory(),
        )
        engineMediaSourceFactory = mediaSourceFactory
        // 1.4.1：播放引擎改为 flavor 工厂。B/C 仍是 ExoPlayer；A 默认链真用系统 MediaPlayer。
        val enginePlayer = FlavorPlaybackEngine.buildPlayer(
            context = this,
            renderersFactory = renderersFactory,
            mediaSourceFactory = mediaSourceFactory,
            loadControl = tunableLoadControl,
            audioAttributes = audioAttributes,
        )
        val artworkBitmapLoader = MediaSessionArtworkBitmapLoader(this)

        player = enginePlayer
        logState(op = "播放服务就绪", source = "PlaybackService.onCreate")
        playbackAudioFxController = PlaybackAudioFxController().also { controller ->
            controller.setAudioSessionId(enginePlayer.audioSessionId)
        }
        enginePlayer.addListener(audioFxPlayerListener)
        enginePlayer.addListener(playbackPolicyPlayerListener)
        enginePlayer.addListener(playbackStartFadePlayerListener)
        enginePlayer.addListener(usbDiagnosticsPlayerListener) // [v19·USB日志补全] 状态机/config快照观测
        enginePlayer.addListener(durationBackfillListener)
        enginePlayer.addListener(smartCrossfadePlayerListener)
        (enginePlayer as? ExoPlayer)?.addAnalyticsListener(PlaybackDiagnosticsListener { this })

        listeningMemoryStore = ListeningMemoryStore(this)
        enginePlayer.addListener(memoryListener)
        startMemoryPlayTimeTicker()
        startCrossfadeTicker()
        playbackMetadataPreloader = PlaybackMetadataPreloader(
            context = this,
            player = enginePlayer,
            scope = serviceScope,
        ).also { preloader ->
            preloader.start()
        }
        // 高采样率输出（>48kHz）时系统效果链会被强制重采样，部分机型产生杂音，自动禁用音效。
        serviceScope.launch {
            AudioOutputMonitor.spec.collect { spec ->
                playbackAudioFxController?.setOutputEffectsSafe(
                    spec == null || (!spec.isCompressedPassthrough && spec.sampleRateHz <= AudioFxMaxSafeSampleRateHz),
                )
            }
        }
        // [1.4.18·切引擎即时生效] 监听引擎切换，同进程重建播放内核。
        observeEngineModeChanges()
        mediaSessionArtworkBitmapLoader = artworkBitmapLoader
        // 通知中心：默认系统 MediaStyle；[R33] 另有自绘大按钮版本，由界面设置切换。
        val notificationProvider = ChuiziNotificationProvider(this)
        this.notificationProvider = notificationProvider
        customNotificationProvider = ChuiziCustomNotificationProvider(
            context = this,
            buttons = notificationProvider,
            onOpenApp = { createAppResumePendingIntent() },
        )
        setMediaNotificationProvider(notificationProvider)
        // DataStore 是异步的：先挂系统卡片（安全默认），读到开关值后立刻切到自绘。
        serviceScope.launch {
            UiSettingsStore(this@PlaybackService).notificationCustomLayout.collect { custom ->
                applyNotificationLayoutMode(custom)
            }
        }
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            PlaybackStartFadePlayer(enginePlayer, playbackStartFadeController, smartCrossfadeController),
            PlaybackLibrarySessionCallback(),
        )
            .setSessionActivity(createSessionActivityPendingIntent())
            .setBitmapLoader(artworkBitmapLoader)
            .setPeriodicPositionUpdateEnabled(true)
            .build()

        // [1.4.24·R33] 会话建好后补一次通知样式落位：DataStore 首次 emit 可能早于
        // session 创建，那次 onUpdateNotification 是空转，这里补上。
        serviceScope.launch {
            kotlinx.coroutines.delay(300)
            applyNotificationLayoutMode(notificationCustomLayout)
        }

        playbackPlayCountTracker = PlaybackPlayCountTracker(
            player = enginePlayer,
            repository = playbackStatsRepository,
            scope = serviceScope,
            onPlayCountChanged = {
                scheduleStatsLibraryRefresh()
            },
        ).also { tracker ->
            tracker.start()
        }

        playbackSessionStateCoordinator = PlaybackSessionStateCoordinator(
            player = enginePlayer,
            stateStore = playbackSessionStateStore,
            scope = serviceScope,
            canLoadLibraryItems = { hasAudioPermission() },
            loadLibraryItemsByQueueKeys = { queueKeys -> getAudioItemsByQueueKeys(queueKeys) },
            consumeCrashRecovery = { CrashRecoveryFlag.consumeCrashPending(applicationContext) },
        ).also { coordinator ->
            coordinator.start()
        }

        serviceScope.launch(Dispatchers.IO) {
            libraryExclusionsStore.exclusions.collect { exclusions ->
                exclusionsSnapshot = exclusions
                if (!exclusionsReady.isCompleted) {
                    exclusionsReady.complete(exclusions)
                }
                withContext(Dispatchers.Main.immediate) {
                    removeHiddenQueuedItems(exclusions)
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        Int.MAX_VALUE,
                        null,
                    )
                }
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            // 文件扫描开关变化时同步快照，并通知客户端重建列表树（开关生效 = 当作从未扫描到）
            fileScanSettingsStore.settings.collect { settings ->
                fileScanSettingsSnapshot = settings
                withContext(Dispatchers.Main.immediate) {
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        Int.MAX_VALUE,
                        null,
                    )
                }
            }
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            playbackSettingsStore.settings.collect { settings ->
                val prevSettings = playbackSettingsSnapshot
                playbackSettingsSnapshot = settings
                // 1.3.4：设置变化时立即同步 DSP 音效链与两个播放策略开关
                PcmDsp.configure(settings)
                // [1.4.20·P0] 播放/暂停淡入淡出开关实时生效
                playbackStartFadeController.setEnabled(settings.playPauseFadeEnabled, player)
                // [1.4.21·P2] 软件音量平滑接力+读回：注入实例并实时同步开关。
                val h = UsbVolume.handoff ?: SmoothVolumeHandoff(serviceScope).also { UsbVolume.handoff = it }
                h.enabled = settings.smoothVolumeHandoffEnabled
                PlaybackPolicy.update(
                    usbExclusiveActive,
                    settings.excludeDsdEnabled,
                )
                // 1.3.3：开关变化必须留一帧完整现场——用户常忘记说明「是否开独占 /
                // 还开了什么」，光看描述无法判断问题发生时的真实条件。
                val changed = buildList {
                    if (prevSettings.usbExclusiveEnabled != settings.usbExclusiveEnabled) {
                        add("exclusive=${settings.usbExclusiveEnabled}")
                    }
                    if (prevSettings.excludeDsdEnabled != settings.excludeDsdEnabled) {
                        add("excludeDsd=${settings.excludeDsdEnabled}")
                    }
                    if (prevSettings.plugAutoResumeEnabled != settings.plugAutoResumeEnabled) {
                        add("plugAutoResume=${settings.plugAutoResumeEnabled}")
                    }
                    if (prevSettings.scratchEnabled != settings.scratchEnabled) {
                        add("djing=${settings.scratchEnabled}")
                    }
                    if (prevSettings.popcornSoundEnabled != settings.popcornSoundEnabled) {
                        add("popcorn=${settings.popcornSoundEnabled}")
                        // [R164] 服务层爆豆音开关真值 + 开关打开时立即触发一次试听（旧行为）
                        popcornSoundOn = settings.popcornSoundEnabled
                        UsbExclusiveSinkRef.popcornEnabled = settings.popcornSoundEnabled
                        if (settings.popcornSoundEnabled) {
                            popcornController?.playRandomPop()
                            PopcornScheduler.noteTrigger()
                        }
                    }
                }.joinToString(",").ifEmpty { "首次加载/无开关变化" }
                logState(
                    op = "设置项变化",
                    source = "PlaybackSettingsStore.settings",
                    extra = mapOf("changed" to changed),
                )
                // [v11·独占死循环根治] 区分"首帧装载"与"用户真实切换开关"：
                // native 崩溃重启后设置里独占仍是 true、usbExclusivePref 初始 false，
                // 首帧 exclusiveChanged 会是 true 但不是用户操作，不能借此清掉持久化熔断。
                val rawExclusiveChanged = settings.usbExclusiveEnabled != usbExclusivePref
                val isFirstSettingsFrame = !usbSettingsObservedOnce
                usbSettingsObservedOnce = true
                // 持久化熔断命中时，重置内存态与重建节流，确保本次启动一定走共享输出
                val guardDisabled = UsbExclusiveGuard.isDisabled(applicationContext)
                if (guardDisabled) {
                    exclusiveDegraded = true
                    exclusiveLostAtMs.clear()
                }
                // 只有首帧之后的真实开关翻转，才算用户手动操作，允许解除持久化熔断。
                val exclusiveChanged = rawExclusiveChanged && !isFirstSettingsFrame
                if (exclusiveChanged) {
                    // [R185] 带方向：真值是 settings 里的新值
                    UsbExclusiveGuard.clear(applicationContext, reenabled = settings.usbExclusiveEnabled)
                    exclusiveDegraded = false
                }
                usbExclusivePref = settings.usbExclusiveEnabled
                plugAutoResumePref = settings.plugAutoResumeEnabled
                // [v13·爆豆音开关真值] 把唱盘爆豆音开关写进 UAC sink，关掉即拒绝入队，
                // 杜绝"关了却还响"；关闭时同时清空已入队的残留样本。
                UsbExclusiveSinkRef.popcornEnabled = settings.popcornSoundEnabled
                if (!settings.popcornSoundEnabled) {
                    UsbExclusiveSinkRef.current?.clearPopcorn()
                }
                playbackAudioFxController?.setSettings(settings)
                refreshUsbRoute(exclusiveChanged)
            }
        }
        UsbAudioMonitor.start(this)
        ContextCompat.registerReceiver(
            this,
            headsetPlugReceiver,
            IntentFilter(AudioManager.ACTION_HEADSET_PLUG),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 1.2.6：监听系统媒体音量变化，独占时把软件增益同步到系统弹窗音量
        ContextCompat.registerReceiver(
            this,
            volumeChangeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        UsbVolume.syncSystemVolume(this)
        serviceScope.launch(Dispatchers.Main.immediate) {
            UsbAudioMonitor.devices.collect { devices ->
                val changed = devices.map { it.name } != usbDevices.map { it.name }
                val added = changed && devices.size > usbDevices.size
                usbDevices = devices
                if (changed) {
                    AppLog.i(
                        "Usb",
                        "devices=${devices.size}" +
                            devices.joinToString(prefix = " [", postfix = "]") { device ->
                                "${device.name}(${device.specSummary})"
                            },
                    )
                }
                // [1.4.25·关闭独占后被自己reset的枚举重新占住/自动放歌根治]
                // 关闭独占时用 nativeUsbReset 做"等效插拔"，内核会重新枚举设备并发
                // DEVICE_ATTACHED → 这里 added=true → 旧代码立刻 autoResume + refreshUsbRoute
                // 重开独占，于是"关了又自己占住/莫名放歌"，只能靠物理插拔复位。
                // UsbDac.recentlyActive() 在刚 close(reset) 后 3s 内为真，用它识别
                // "这是我自己 reset 出来的假插入"，本次跳过自动续播与自动开独占。
                val selfResetReenumeration =
                    com.smartisan.music.playback.UsbDac.recentlyActive(3000L)
                if (selfResetReenumeration) {
                    AppLog.i(
                        "Usb",
                        "忽略自发达成的USB重枚举(刚关闭独占reset)，不自动续播/不开独占",
                    )
                } else {
                    if (added) maybeAutoResumeOnPlug("usb audio")
                    refreshUsbRoute()
                }
            }
        }
        serviceScope.launch(Dispatchers.Default) {
            favoriteRepository?.observeFavoriteIds()?.collect { ids ->
                notificationProvider.setFavoriteIds(ids)
                withContext(Dispatchers.Main.immediate) {
                    refreshNotificationButtons()
                }
            }
        }
    }

    /**
     * [1.4.24·R33 自绘通知] 切换「自绘大按钮 / 系统媒体卡片」并重建通知。
     * 两者是不同 Provider 实例，setMediaNotificationProvider 之后必须手动触发一次
     * onUpdateNotification，否则要等下一次播放状态变化才换样子。
     */
    private fun applyNotificationLayoutMode(custom: Boolean) {
        if (notificationCustomLayout == custom && notificationProgressJob?.isActive == true) {
            // 值没变、ticker 已在跑：只补一次刷新（session 可能刚建好）
            runCatching { mediaLibrarySession?.let { onUpdateNotification(it, false) } }
            return
        }
        notificationCustomLayout = custom
        if (custom) {
            customNotificationProvider?.let { runCatching { setMediaNotificationProvider(it) } }
            startNotificationProgressTicker()
        } else {
            notificationProvider?.let { runCatching { setMediaNotificationProvider(it) } }
            stopNotificationProgressTicker()
        }
        runCatching { mediaLibrarySession?.let { onUpdateNotification(it, false) } }
        AppLog.audit(
            tag = "通知",
            op = if (custom) "切到自绘大按钮" else "切回系统媒体卡片",
            source = "PlaybackService.applyNotificationLayoutMode",
        )
    }

    /**
     * 自绘通知的进度条不会自己走（系统只在原生媒体卡片里代刷位置），
     * 播放中每秒重建一次 RemoteViews；暂停就停，不做无意义 IO。
     */
    private fun startNotificationProgressTicker() {
        if (notificationProgressJob?.isActive == true) return
        notificationProgressJob = serviceScope.launch {
            while (true) {
                delay(1000)
                if (!notificationCustomLayout) break
                val session = mediaLibrarySession ?: continue
                if (!session.player.isPlaying) continue
                runCatching { onUpdateNotification(session, false) }
            }
        }
    }

    private fun stopNotificationProgressTicker() {
        notificationProgressJob?.cancel()
        notificationProgressJob = null
    }

    /**
     * [1.4.20·问题8] 收藏状态变化后强制 media3 重建通知按钮。
     * 根因：1.4.18 用 setPlayer(同一实例) 走 media3 fast path，直接复用缓存的
     * CommandButton（含旧爱心图标），所以 App 里变红但通知栏不变。
     * 正确做法：用官方 setMediaButtonPreferences() 推送一份全新生成的按钮列表
     * （含按最新 favoriteIds 构造的爱心图标），media3 会据此重建通知。
     */
    private fun refreshNotificationButtons() {
        runCatching { triggerNotificationUpdate() }
        runCatching {
            val session = mediaLibrarySession ?: return@runCatching
            val provider = notificationProvider ?: return@runCatching
            session.setMediaButtonPreferences(provider.buildButtons(session))
        }
        AppLog.i("Player", "1.4.20 通知栏按钮已重建（setMediaButtonPreferences）")
    }

    private fun memorizeTrack(item: MediaItem) {
        val store = listeningMemoryStore ?: return
        val extras = item.mediaMetadata.extras
        val sampleRate = extras?.getInt(LocalAudioLibrary.SampleRateHzExtraKey) ?: 0
        val bits = extras?.getInt(LocalAudioLibrary.BitsPerSampleExtraKey) ?: 0
        val track = TrackMemory(
            mediaId = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            playCount = 1,
            lastPlayedAt = System.currentTimeMillis(),
            highResolution = sampleRate >= 96_000 || bits >= 24,
        )
        serviceScope.launch(Dispatchers.Default) {
            runCatching { store.recordPlay(track, ListeningMemoryStore.currentHour()) }
                .onFailure { error ->
                    AppLog.w("Memory", "record failed: ${error.message}")
                }
        }
    }

    private fun startMemoryPlayTimeTicker() {
        serviceScope.launch {
            while (true) {
                delay(MemoryPlayTimeTickMs)
                if (player?.isPlaying == true) {
                    playedMsPendingFlush += MemoryPlayTimeTickMs
                    if (playedMsPendingFlush >= MemoryPlayTimeFlushMs) {
                        flushMemorizedPlayTime()
                    }
                }
            }
        }
    }

    /**
     * [1.4.19·S07] 智能过渡的进度轮询：每 250ms 检查是否临近曲末需要淡出。
     * 只启动一次（onCreate 首次构建 player 时），引擎重建不重复启动；
     * 关闭时 [SmartCrossfadeController.onProgressTick] 内部直接 return，开销可忽略。
     */
    private fun startCrossfadeTicker() {
        serviceScope.launch {
            while (true) {
                delay(250L)
                val pl = player ?: continue
                smartCrossfadeController.onProgressTick(pl)
            }
        }
    }

    private fun flushMemorizedPlayTime() {
        val pending = playedMsPendingFlush
        if (pending <= 0L) return
        playedMsPendingFlush = 0L
        val store = listeningMemoryStore ?: return
        serviceScope.launch(Dispatchers.Default) {
            runCatching { store.addPlayedTime(pending) }
                .onFailure { error ->
                    AppLog.w("Memory", "played time flush failed: ${error.message}")
                }
        }
    }

    /**
     * USB 独占（真独占）。
     *
     * 判定设备是否还在**只能用 `UsbManager`**（[UsbDac.findDac]）：
     * 一旦 UAC 会话建立，内核驱动被从接口上摘掉，系统音频里这块声卡**根本不存在**，
     * 用 `AudioManager.getDevices`（[UsbAudioMonitor] 的依据）会把"正在独占"
     * 误判成"已拔出"，进而误暂停。
     */
    private fun refreshUsbRoute(prefChanged: Boolean = false) {
        val manager = getSystemService(Context.USB_SERVICE) as? UsbManager
        val enabled = usbExclusivePref
        val dac = manager?.let { UsbDac.findDac(it) }
        // 1.3.3：USB 独占开关 / 插拔 / 权限授予是绝大多数问题的前提条件，
        // 每次都留一帧，明确「开关值、是否检测到 DAC、是否由设置触发」。
        logState(
            op = "刷新 USB 输出路由",
            source = "refreshUsbRoute",
            extra = mapOf(
                "prefExclusive" to enabled,
                "dacFound" to (dac != null),
                "prefChanged" to prefChanged,
            ),
        )

        // [v10·独占熔断] 用户开关的变化优先于熔断态：关→清熔断；手动重开→清熔断并给新机会。
        // 这样"点了开关没反应"不再发生——每次手动操作都真正生效。
        if (prefChanged) {
            usbGuardCleanupDone = false
            if (!enabled) {
                exclusiveDegraded = false
                exclusiveLostAtMs.clear()
                exclusiveSessionOpenedAtMs = 0L
                AppLog.i("Usb", "用户关闭独占：清除熔断态，下次开启重新尝试")
            } else {
                // 手动重新打开：无条件清熔断，给一次新的机会（尊重用户意图）
                exclusiveDegraded = false
                exclusiveLostAtMs.clear()
                exclusiveSessionOpenedAtMs = 0L
                AppLog.i("Usb", "用户重新打开独占：清除熔断态，重新尝试独占通道")
            }
        }
        // 已熔断且开关仍处于开、但本次不是用户主动重开：保持共享输出，绝不自动重试独占。
        // 这是止损的核心——后台/切歌/重枚举都不会再把坏通道拖进重建风暴。
        if (enabled && exclusiveDegraded && !prefChanged) {
            AppLog.w("Usb", "独占处于熔断降级态，保持共享输出（用户手动重开开关可重试）")
            // [v11·关独占后无声根治] native 崩溃重启后，声卡可能残留在"内核已认领但 ALSA
            // 状态脏 / 旧 preferredDevice 仍指向已崩 UAC 通道"的半占用态，导致共享输出
            // 没声音。这里在熔断态下做一次性的 USB 复位 + 清路由 + 共享输出重建，把声卡
            // 干净地还给系统（等效重新插拔）。进程内只做一次，绝不反复重建。
            if (!usbGuardCleanupDone) {
                usbGuardCleanupDone = true
                AppLog.i("Usb", "熔断态首次清理：USB 复位 + 清路由 + 重建共享输出（恢复关独占后无声）")
                usbExclusiveActive = false
                usbRouteLogged = false
                usbPermissionRequested = false
                UsbDac.closeQuietly(reset = true)
                setNoisySuppression(true)
                AudioOutputRouter.setPreferredDevice(null)
                rebuildOutput("exclusive guard cleanup", delayMs = ExclusiveRebindDelayMs)
            }
            return
        }

        if (!enabled) {
            // [1.4.24·R38] 关掉独占时作废看门狗，避免 3 秒后误报"假独占"。
            exclusiveWatchdogToken++
            if (usbExclusiveActive || usbRouteLogged) {
                AppLog.i("Usb", "exclusive off -> release dac and rebuild shared output")
                usbExclusiveActive = false
                usbRouteLogged = false
                usbPermissionRequested = false
                // 1.2.4：用户手动关闭独占时对设备做一次 USB 复位（等效重新插拔），
                // 让内核重新认领全部接口——否则带麦耳机的麦克风会一直失效，必须重新插拔。
                UsbDac.closeQuietly(reset = true)
                setNoisySuppression(true)
                AudioOutputRouter.setPreferredDevice(null)
                // 内核驱动要重新 probe 才会把声卡还给系统，立刻重建会落到扬声器
                rebuildOutput("exclusive off", delayMs = ExclusiveRebindDelayMs)
            } else if (prefChanged) {
                // [v24·R20-a] 重启后独占开关记忆为开、但会话因首曲 3001 错误没建立成功时，
                // usbExclusiveActive/usbRouteLogged 都是 false，上面的 reset 分支整个被跳过，
                // 上一进程 force-stop 残留的内核半占用态没清 → 关了开关 USB 仍被独占、麦克风
                // 失效。用户手动关开关（prefChanged）时，即使本次没有活动会话，也做一次
                // 不依赖进程内 active 状态的设备复位（claim+USBDEVFS_RESET），把声卡干净地
                // 还给系统。只在开关真正由开→关时触发，避免每次启动/插拔都重置声卡。
                AppLog.i("Usb", "exclusive off (no active session) -> resetOrphanedDevice (clear kernel residue)")
                UsbDac.resetOrphanedDevice(this)
                setNoisySuppression(true)
                AudioOutputRouter.setPreferredDevice(null)
            }
            return
        }

        if (dac == null) {
            // 没插 DAC：保持共享输出；若刚才是独占状态说明真拔线了
            if (usbExclusiveActive) {
                usbExclusiveActive = false
                usbRouteLogged = false
                // [1.4.24·USB释放重连] 非手动关闭（掉线/切引擎等）也必须像手动关闭一样
                // 复位设备并清除输出路由偏好，否则内核驱动不重绑、系统媒体/麦克风仍被占用，
                // 用户得手动开关一次独占才恢复。closeQuietly(reset=true) 内部对已拔设备
                // 会安全失败（closeQuietly 吞异常），不会引发新问题。
                UsbDac.closeQuietly(reset = true)
                setNoisySuppression(true)
                AudioOutputRouter.setPreferredDevice(null)
                if (player?.isPlaying == true) {
                    PlaybackIntentAudit.trace("USB DAC 物理掉线（独占中）") { player?.pause() }
                    runCatching {
                        Toast.makeText(this, R.string.usb_device_lost, Toast.LENGTH_SHORT).show()
                    }
                    AppLog.w("Usb", "usb dac detached while exclusive: paused, not rerouted")
                }
            }
            return
        }

        if (!UsbDac.hasPermission(this)) {
            if (usbPermissionRequested) return
            usbPermissionRequested = true
            AppLog.i("Usb", "requesting usb permission for ${dac.productName}")
            UsbDac.ensure(this) { device ->
                usbPermissionRequested = false
                if (device != null) {
                    AppLog.i("Usb", "usb permission granted -> build exclusive output")
                    // claim 之前先抑制误报的 NOISY（此回调不在主线程，统一经主线程下发）
                    setNoisySuppression(false)
                    rebuildOutput("permission granted")
                } else {
                    AppLog.w("Usb", "usb permission denied, exclusive unavailable")
                }
            }
            return
        }

        // [1.4.24·R38 假独占根治] 关键改动：不再把 AudioTrack 的 preferredDevice 指到 USB。
        //
        // 旧实现的顺序是「先设 preferredDevice=USB，再 rebuildOutput」。当 UAC 会话实际
        // 没建起来时，共享 AudioTrack 会被路由到一个未被激活的 USB 端点：数据被静默丢弃，
        // 进度条照走、耳机没声，只有走独立 SoundPool 通道的爆豆音能响（2026-11-24 03:08
        // 用户实测）。USB DAC 插着时系统本来就会把媒体路由过去，强制指定反而制造了这个坑。
        //
        // 正确顺序：先主动把 UAC 会话开起来；开不起来就老老实实走系统共享输出（系统自己
        // 选路由），并明确告知用户"未建立独占"，绝不允许出现"开关显示已开、实际没独占"。
        val currentSink = UsbExclusiveSinkRef.current
        val opened = currentSink?.forceReopen(player?.playWhenReady == true) == true
        if (opened) {
            usbExclusiveActive = true
            usbRouteLogged = true
            usbExclusiveRequestedAtMs = 0L
            // 会话已直连 UAC，共享 AudioTrack 不再参与放音；清掉之前可能残留的
            // preferredDevice（指向已被系统摘除的 USB 端点会让 AudioTrack 静默丢数据）。
            AudioOutputRouter.setPreferredDevice(null)
            // claim 之前先抑制误报的 NOISY，否则系统会把它当成"拔耳机"直接暂停
            setNoisySuppression(false)
            AppLog.i("Usb", "exclusive on -> 已主动建立 UAC 会话（未重建输出、未改 preferredDevice）")
        } else if (!UsbDac.isActive && (prefChanged || !usbRouteLogged)) {
            AudioOutputRouter.setPreferredDevice(usbDevices.firstOrNull()?.audioDeviceInfo)
            AppLog.i("Usb", "exclusive on -> 主动开会话失败，rebuild output 兜底（等 configure）")
            usbRouteLogged = true
            usbExclusiveActive = true
            setNoisySuppression(false)
            rebuildOutput("exclusive on")
            startExclusiveWatchdog()
        } else if (UsbDac.isActive) {
            usbExclusiveActive = true
        }
    }

    /**
     * [1.4.24·R38] 独占看门狗：开关打开后 3 秒仍没有真实 UAC 会话，就把"假独占"钉死在日志里，
     * 并给 UI 一个明确的降级原因。历史上这个问题只能靠"切歌后突然好了"这种玄学现象发现，
     * 现在从日志一眼可判。
     */
    private fun startExclusiveWatchdog() {
        val token = ++exclusiveWatchdogToken
        exclusiveWatchdogRequestedAtMs = SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            if (token != exclusiveWatchdogToken) return@postDelayed
            if (!usbExclusivePref) return@postDelayed
            val active = UsbDac.isActive
            val elapsed = SystemClock.elapsedRealtime() - exclusiveWatchdogRequestedAtMs
            if (!active) {
                AppLog.audit(
                    "Usb",
                    "独占看门狗：开关已开但 UAC 会话仍未建立（假独占）",
                    "PlaybackService.startExclusiveWatchdog",
                    mapOf(
                        "elapsedMs" to elapsed,
                        "isActive" to false,
                        "sinkUsbOpen" to (UsbExclusiveSinkRef.current?.usbSessionActive() ?: false),
                        "degraded" to exclusiveDegraded,
                    ),
                )
                UsbExclusiveSinkRef.setFallbackReason(
                    "USB 独占未建立：DAC 未响应或输出格式未变化，当前为系统共享输出（非独占、非零损失）",
                )
            } else {
                AppLog.i("Usb", "独占看门狗：会话正常（elapsedMs=$elapsed）")
            }
        }, 3_000L)
    }

    /**
     * 抑制 / 恢复「音频输出断开」(ACTION_AUDIO_BECOMING_NOISY) 引起的自动暂停。
     *
     * UAC 真独占会把内核声卡从系统摘掉，系统误以为"输出设备没了"并广播 NOISY。
     * 必须在 claim 接口**之前**就抑制，否则会被当成拔耳机直接暂停。
     * 所有 Player 调用都统一切回主线程，避免 AudioSink 回调（播放线程）直接触碰 Player
     * 触发 "Player is accessed on the wrong thread" 致命错误。
     */
    private fun setNoisySuppression(suppress: Boolean) {
        mainHandler.post { (player as? ExoPlayer)?.setHandleAudioBecomingNoisy(!suppress) }
    }

    /** UAC 会话建立（由 AudioSink 回调，运行在 ExoPlayer 播放线程） */
    private fun handleUsbExclusiveOpened() {
        // 此回调来自 AudioSink.configure()，在 ExoPlayer 播放线程执行，
        // 所有 Player 方法必须切回主线程，否则会抛 "wrong thread" 致命错误。
        mainHandler.post {
            usbExclusiveActive = true
            // [v10·健康度] 记录本次会话建立时刻，配合 lost 时间窗判断"会话存活多久就死了"
            exclusiveSessionOpenedAtMs = SystemClock.elapsedRealtime()
            // 独占建立时系统会发一条误报的 NOISY（因为USB 输出"消失"了），必须抑制。
            // claim 之前 refreshUsbRoute 已经设过一次，这里再确认一次。
            (player as? ExoPlayer)?.setHandleAudioBecomingNoisy(false)
            AppLog.i("Usb", "uac active, suppressing audio-becoming-noisy")
        }
    }

    /** UAC 会话中途死掉（拔线 / 传输失败，由 AudioSink 回调，运行在播放线程） */
    private fun handleUsbExclusiveLost() {
        // 同样来自播放线程（AudioSink.handleBuffer），必须切回主线程再操作 Player。
        mainHandler.post {
            if (!usbExclusiveActive) return@post
            usbExclusiveActive = false
            usbRouteLogged = false
            AppLog.w("Usb", "uac session lost")
            val dac = (getSystemService(Context.USB_SERVICE) as? UsbManager)
                ?.let { UsbDac.findDac(it) }
            if (dac == null) {
                // 真拔线：恢复"音频断开自动暂停"并暂停，绝不偷偷切回扬声器续播
                setNoisySuppression(true)
                if (player?.isPlaying == true) {
                    PlaybackIntentAudit.trace("UAC 会话丢失后设备已拔线") { player?.pause() }
                    runCatching {
                        Toast.makeText(this, R.string.usb_device_lost, Toast.LENGTH_SHORT).show()
                    }
                }
                AppLog.w("Usb", "dac gone after uac loss: paused")
            } else {
                // 设备还在、只是会话挂了。
                // [v10·换思路·会话健康度熔断] 旧实现无条件 rebuild("uac retry")：坏通道会
                // 1~2 秒丢一次、4 秒节流吞掉救命重建 → 无限缓冲 → native 崩溃。这里改为先
                // 统计滑动窗口内的丢失次数：偶发 1 次仍重建（保留正常设备/切歌的自愈能力）；
                // 短时间内反复丢失即判定通道不可靠，透明降级到共享输出，彻底止损。
                val now = SystemClock.elapsedRealtime()
                exclusiveLostAtMs.addLast(now)
                while (exclusiveLostAtMs.isNotEmpty() && now - exclusiveLostAtMs.first() > ExclusiveLostWindowMs) {
                    exclusiveLostAtMs.removeFirst()
                }
                val lostCount = exclusiveLostAtMs.size
                val survivedMs =
                    if (exclusiveSessionOpenedAtMs > 0L) now - exclusiveSessionOpenedAtMs else -1L
                AppLog.state(
                    "State", "UAC 会话健康度", "PlaybackService.handleUsbExclusiveLost",
                    mapOf(
                        "lostInWindow" to lostCount,
                        "windowMs" to ExclusiveLostWindowMs,
                        "sessionSurvivedMs" to survivedMs,
                        "willDegrade" to (lostCount >= ExclusiveLostThreshold),
                    ),
                )
                if (lostCount >= ExclusiveLostThreshold) {
                    degradeExclusiveToShared("UAC 会话 $ExclusiveLostWindowMs/60s 内丢失 $lostCount 次，判定通道不可靠")
                } else {
                    AppLog.i("Usb", "dac still present, retry exclusive output (lost $lostCount/$ExclusiveLostThreshold)")
                    // 首次丢失（lostCount==1）用 "uac retry first" 绕过 4 秒节流：
                    // 旧实现首次救命重建常被上一次 stuck recover 的节流窗口吞掉，会话再也回不来
                    // → 无限缓冲。只有第 2 次起才走节流的 "uac retry"，配合熔断止损。
                    val rebuildReason = if (lostCount == 1) "uac retry first" else "uac retry"
                    rebuildOutput(rebuildReason, delayMs = ExclusiveRebindDelayMs)
                }
            }
        }
    }

    /**
     * [v10·换思路·止损] 独占通道不可靠时，一次性退回到共享 USB 输出并熔断后续重试。
     *
     * 关键点：_pcm DSP/音效不丢_ —— 共享输出下 PCM 域的 EQ/混响/响度仍经 AudioProcessor
     * 生效（用户本轮已实测非独占下这些音效全部正常），丢的只是 libusb 直写的 bit-perfect
     * 与 UAC 独占硬件音量；相较"无限缓冲 + native 崩溃 + 完全听不了"，这是可接受的降级。
     * 熔断只在用户手动重新打开独占开关时解除（见 refreshUsbRoute 的 prefChanged 分支），
     * 避免后台反复重建把设备/系统拖死。
     */
    private fun degradeExclusiveToShared(reason: String) {
        if (exclusiveDegraded) return
        exclusiveDegraded = true
        // [v11] 熔断持久化：判定通道不可靠即落盘，进程重启后 direct 读取，绝不自动重试坏通道。
        UsbExclusiveGuard.disableAfterNativeCrash(applicationContext)
        usbGuardCleanupDone = false
        AppLog.w("Usb", "独占通道熔断降级：$reason；切回共享 USB 输出（DSP 音效保留）")
        AppLog.state(
            "State", "独占熔断降级", "PlaybackService.degradeExclusiveToShared",
            mapOf("reason" to reason, "dspKept" to true),
        )
        // 归还 DAC（带复位，恢复系统对声卡的认领）+ 清路由偏好 + 恢复 NOISY 处理
        UsbDac.closeQuietly(reset = true)
        usbExclusiveActive = false
        usbRouteLogged = false
        usbPermissionRequested = false
        setNoisySuppression(true)
        AudioOutputRouter.setPreferredDevice(null)
        exclusiveLostAtMs.clear()
        exclusiveSessionOpenedAtMs = 0L
        // 重建为共享输出；reason 用非节流名，确保这次一定能执行（熔断降级必须落地）
        rebuildOutput("exclusive degraded", delayMs = ExclusiveRebindDelayMs)
        runCatching {
            Toast.makeText(this, R.string.usb_exclusive_degraded, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 「插入音频设备自动续播」：接入有线耳机 / USB 声卡时，若队列里有歌且当前是暂停态，
     * 自动恢复播放。仅在该设置项打开时生效。
     */
    private fun maybeAutoResumeOnPlug(source: String) {
        // 1.3.3：1.3.2 日志里出现过「明明关了『插入设备自动续播』，插 USB 开独占后
        // 还是莫名自动播放」。这里无论开关是否打开都留一帧，明确记录当时的开关值和
        // 播放器状态，避免事后靠用户回忆到底开没开。
        logState(
            op = "插入音频设备",
            source = "maybeAutoResumeOnPlug($source)",
            extra = mapOf(
                "plugSource" to source,
                "autoResumeEnabled" to plugAutoResumePref,
            ),
        )
        if (!plugAutoResumePref) return
        val playbackPlayer = player ?: return
        if (playbackPlayer.currentMediaItem == null) return
        if (playbackPlayer.isPlaying || playbackPlayer.playWhenReady) return
        playbackPlayer.play()
        AppLog.i("Player", "auto resume on audio device plugged ($source)")
        logState(
            op = "自动续播已触发",
            source = "maybeAutoResumeOnPlug($source)",
            extra = mapOf("plugSource" to source),
        )
    }

    /**
     * 重建输出通道（切换独占/共享）。
     *
     * 原理：`stop()` 会让渲染器 `reset()` → AudioSink 释放 UAC 会话（把设备还给系统），
     * 重新 `prepare()` 时渲染器会再次 `configure()`，此时读到新的独占开关值。
     * 这比重建整个播放器和 MediaSession 轻得多，队列与进度都不丢。
     */
    /** 重建请求代数：single-flight，防止重叠的 stop/prepare 把播放器打进中间态 */
    @Volatile
    private var rebuildGeneration = 0L
    /** [护栏 G-11·台账登记] 自动重试类重建节流 + 转码探测 mediaId 归属校验，
     *  防「B 后台反复 prepare 把转码打回 0%→99%→0% / 把上一首缓冲放宽写到当前首」。
     *  1.4.3：自动重试类重建的最近一次时间，用于节流（避免后台反复 prepare 打断转码） */
    @Volatile
    private var lastAutoRebuildAtMs = 0L
    /** 1.4.4 清单 4：3001 挂起复核的延迟 Toast，恢复播放时撤销 */
    private var pendingErrorToast: Runnable? = null
    private fun rebuildOutput(reason: String, delayMs: Long = 0L) {
        // 1.4.3：自动重试类重建（卡死自愈 / UAC 掉线重试）加节流。后台/独占卡顿时
        // 每次 1003 都会 rebuild → stop/prepare → DataSource.open() 重新读源，
        // 转码曲目被打回 0% 重新转（日志「0%→99%→0%」反复重启），越重建越卡。
        // 用户主动开关独占 / 授权回调不受此限制（reason 不同，不拦）。
        if (reason == "stuck recover" || reason == "uac retry") {
            val now = SystemClock.elapsedRealtime()
            // 1.4.4 清单 5：前后台**切换窗口内**（3 秒）冻结自动重建。切前后台瞬间系统
            // 回收 / 重建 Activity，此时自动重试类 rebuild 会叠加 stop/prepare 把转码打回 0%，
            // 正是 B/C「开独占切后台卡」的放大器。收紧：窗口内直接跳过，不排队、不延后。
            // 用切换时间戳而非「整个后台期间」——窗口外后台稳定播放时掉线自救仍必须可用，
            // 冻结整个后台会误伤断线恢复（这是与护栏台账门禁一致的收紧判断，不是放宽）。
            val sinceFgChange = now - MusicApplication.lastForegroundChangeAtMs
            if (MusicApplication.lastForegroundChangeAtMs > 0L && sinceFgChange < 3_000L) {
                AppLog.w(
                    "Usb",
                    "自动重建冻结：距前后台切换 ${sinceFgChange}ms < 3000ms，跳过 $reason" +
                        "（避免切换瞬间反复 prepare 打断转码/独占；3 秒后掉线自救恢复可用）",
                )
                return
            }
            if (now - lastAutoRebuildAtMs < 4_000L) {
                AppLog.w(
                    "Usb",
                    "自动重建节流：距上次 ${now - lastAutoRebuildAtMs}ms < 4000ms，跳过 $reason，" +
                        "避免反复 prepare 打断转码/独占",
                )
                return
            }
            lastAutoRebuildAtMs = now
        }
        val playbackPlayer = player ?: return
        val index = playbackPlayer.currentMediaItemIndex.takeIf { it >= 0 } ?: 0
        val position = playbackPlayer.currentPosition.takeIf { it > 0 } ?: 0L
        // [1.4.5·清单 2·G-16] 重建「前」捕获真实播放意图，后续全程只用这一个值，
        // 不再在 stop()/prepare() 之后重新读 playWhenReady（1.4.4 就是在这里读到 false，
        // 日志 "output rebuilt (permission granted) playing=false 已把 App 播放意图(false)
        // 写入 sink"，成为 C 满 ring 死循环的起点）。
        val wasPlaying = playbackPlayer.playWhenReady
        val gen = ++rebuildGeneration
        serviceScope.launch(Dispatchers.Main.immediate) {
            outputRebuilding = true
            try {
            // 立即 stop：旧 UAC 会话已死或待释放，先掐断渲染，
            // 避免延迟窗口内 handleBuffer 持续打在失效 sink 上（"关独占后播放键失灵"的诱因）
            runCatching { playbackPlayer.stop() }
            if (delayMs > 0) delay(delayMs)
            // single-flight：期间若发起了更新的重建请求，本次直接作废，防止两次 stop/prepare 重叠
            if (gen != rebuildGeneration) return@launch
            runCatching {
                playbackPlayer.prepare()
                if (index > 0 || position > 0) playbackPlayer.seekTo(index, position)
                playbackPlayer.playWhenReady = wasPlaying
                if (wasPlaying) playbackPlayer.play()
            }.onFailure { error ->
                AppLog.e("Usb", "rebuild output ($reason) failed", error)
            }
            // [1.4.4 C 独占缓冲极久正解·不拆护栏] 重建换了 AudioSink：
            // playWhenReady 若重建前后都为 true，不会触发 onEvents 播放态变化事件，
            // ExoPlayer 也可能不再回调 AudioSink.play()——这是 1.4.2「重建后 worker
            // 永不启动、ring 灌满、一直缓冲」的根因。
            // 这里只把 App 真实播放意图记进 sink 的真值 appWantsPlayback（不直接 start）；
            // 新会话 configure() 完成后会由 sink 的 reconcileWorker() 按该真值确定性对齐，
            // 不赌 ExoPlayer 回调时序、不 post 猜测帧。收紧：传的是真实 playWhenReady，
            // 暂停重建记 false 绝不会误出声（幽灵播放）。
            // 这条路径用 configure-rebuild 的 START 日志即可在实机证明被触发（台账门禁 6）。
            // [1.4.5·清单 2·G-16] 用重建前捕获的 wasPlaying，不读重建中间态的
            // playWhenReady（那会是 false 假意图）。这是收紧：暂停重建仍写 false，
            // 绝不凭空出声（防幽灵播放）；播放重建写 true，worker 确定性启动（防缓冲极久）。
            UsbExclusiveSinkRef.current?.setPlayerPlaying(wasPlaying)
            AppLog.i(
                "Usb",
                "output rebuilt ($reason) idx=$index pos=$position playing=$wasPlaying" +
                    " 已把重建前捕获的 App 播放意图($wasPlaying)写入 sink，待 configure 后 reconcile 对齐",
            )
            } finally {
                // single-flight：只有最后一次重建才关窗，避免先结束的重叠请求
                // 提前把标志置 false 让 onEvents 的假意图漏进来。
                if (gen == rebuildGeneration) {
                    outputRebuilding = false
                    // [护栏 G-25·1.4.6 落地] 真值防污染：重建窗口关闭必须补一次真值对齐
                    // [1.4.6·G-20/G-21 根治·死锁真凶] 窗口内 onEvents 的播放态翻转被跳过
                    // （防假意图污染 sink），但窗口关闭后 1.4.5 没补对齐：重建前捕获的
                    // false 若是中间态，真值就永久卡住 -> C frames=0 2m42s / B 丢 2165 帧。
                    // 这里按「窗口关闭时的当前真实 playWhenReady」补一次真值对齐，把窗口内
                    // 被吞掉的翻转补回来。收紧：传的仍是真实值，暂停重建写 false 绝不出声，
                    // 不 post 猜测帧、不赌 ExoPlayer 回调时序。
                    val nowPlaying = playbackPlayer.playWhenReady
                    UsbExclusiveSinkRef.current?.setPlayerPlaying(nowPlaying)
                    AppLog.i(
                        "Usb",
                        "1.4.6(G-20) 重建窗口关闭(gen=$gen)：按当前 playWhenReady=$nowPlaying 补一次真值对齐，恢复 onEvents 同步",
                    )
                }
            }
        }
    }

    /**
     * [1.4.18·切引擎即时生效] 监听引擎模式变化，在同一进程内重建播放内核，无需重启 App。
     * 用 lastMode 快照对比，只在用户真正切换时重建（首次 collect 的当前值不触发）。
     */
    private fun observeEngineModeChanges() {
        serviceScope.launch {
            var lastMode = com.smartisan.music.data.settings.EngineModeStore.current()
            com.smartisan.music.data.settings.EngineModeStore.mode.collect { mode ->
                if (mode != lastMode) {
                    lastMode = mode
                    withContext(Dispatchers.Main.immediate) {
                        rebuildPlayerForEngineChange(mode)
                    }
                }
            }
        }
    }

    /**
     * 重建播放 player 图：保存队列/进度 → 拆除旧图 → 按新引擎建 player → 恢复。
     * 全程在主线程，避免 listener 回调竞态；失败时保留旧图并记日志，绝不让用户陷入"不能播"。
     */
    private suspend fun rebuildPlayerForEngineChange(mode: com.smartisan.music.data.settings.EngineModeStore.Mode) {
        if (rebuildingForEngine) return
        rebuildingForEngine = true
        AppLog.i("Player", "1.4.18 开始重建播放内核 -> ${mode.id}")
        try {
            val renderers = engineRenderersFactory ?: return
            val sourceFactory = engineMediaSourceFactory ?: return
            val attrs = engineAudioAttributes ?: return
            val loadControl = playbackLoadControl ?: return
            val oldPlayer = player ?: return

            // 1) 保存当前队列、索引、进度、播放意图
            val savedIndex = oldPlayer.currentMediaItemIndex
            val savedPosition = oldPlayer.currentPosition
            val savedPlaying = oldPlayer.playWhenReady
            val savedRepeat = oldPlayer.repeatMode
            val savedShuffle = oldPlayer.shuffleModeEnabled
            // [1.4.25·R66] 直接把旧 player 的 MediaItem 列表抓在手里。
            // 原因：旧实现只调 coordinator.restoreIfQueueEmpty()，而它是 scope.launch 异步的，
            // 返回时 newPlayer.mediaItemCount 还是 0 → seekTo/play 全被 if 拦掉，
            // 用户看到的就是"切引擎后歌没了、进度也没了"。MediaItem 是不可变值对象，
            // oldPlayer.release() 之后依然可用，这里同步 setMediaItems 才能真正接上。
            val savedItems = ArrayList<androidx.media3.common.MediaItem>(oldPlayer.mediaItemCount)
            for (i in 0 until oldPlayer.mediaItemCount) {
                savedItems.add(oldPlayer.getMediaItemAt(i))
            }
            runCatching { playbackSessionStateCoordinator?.saveNow() }

            // [1.4.20·问题3] 切引擎前彻底归还 USB 设备：oldPlayer.release() 只走 close(reset=false)，
            // 内核驱动不会重绑 → 带麦声卡的麦克风/其它 App 媒体声一直失效。切引擎是独占会话的
            // 真实终点，必须做一次 USB reset（等效重新插拔），把声卡完整还给系统。
            // 新引擎若仍需独占，会在 configure 时重新 open，不受影响。
            if (UsbDac.isActive) {
                AppLog.i("Usb", "1.4.20 切引擎：归还 USB 设备并复位（恢复麦克风/系统媒体声）")
                UsbDac.closeQuietly(reset = true)
                usbExclusiveActive = false
                usbRouteLogged = false
                setNoisySuppression(true)
                AudioOutputRouter.setPreferredDevice(null)
            }

            // 2) 拆除依赖组件
            playbackMetadataPreloader?.stop()
            playbackMetadataPreloader = null
            // [1.4.28] 必须抓局部引用再置 null：旧写法 launch 后才置 null，
            // 协程真正执行时字段已是 null，未满阈值的时长/次数根本没 flush。
            val trackerToFlush = playbackPlayCountTracker
            playbackPlayCountTracker = null
            if (trackerToFlush != null) {
                // [1.4.29·R127] 必须回主线程：stopAndFlush() 第一行 syncPlaybackClock()
                // 就读 player.currentMediaItem / isPlaying，末尾还要 removeListener。
                // 1.4.28 为修「协程执行时字段已置 null」额外加的 Dispatchers.IO 把这段挪到
                // worker 线程，ExoPlayer 线程校验直接抛 IllegalStateException → 切任意引擎必崩。
                // serviceScope 本身是 Main.immediate；flushCountsSynchronously 里的 Room
                // 事务内部自带 IO executor，不会卡主线程。
                runCatching {
                    serviceScope.launch { trackerToFlush.stopAndFlush() }
                }
            }
            playbackSessionStateCoordinator?.stop()
            playbackSessionStateCoordinator = null
            listeningMemoryStore?.let { oldPlayer.removeListener(memoryListener) }
            oldPlayer.removeListener(audioFxPlayerListener)
            oldPlayer.removeListener(playbackPolicyPlayerListener)
            oldPlayer.removeListener(playbackStartFadePlayerListener)
            oldPlayer.removeListener(durationBackfillListener)
            runCatching { playbackStartFadeController.release(oldPlayer) }
            playbackAudioFxController?.release()
            playbackAudioFxController = null
            oldPlayer.release()

            // 3) 按新引擎重建 player
            val newPlayer = FlavorPlaybackEngine.buildPlayer(
                context = this,
                renderersFactory = renderers,
                mediaSourceFactory = sourceFactory,
                loadControl = loadControl,
                audioAttributes = attrs,
            )
            player = newPlayer
            newPlayer.repeatMode = savedRepeat
            newPlayer.shuffleModeEnabled = savedShuffle

            // 4) 重新绑定 listener 与音效控制器
            newPlayer.addListener(audioFxPlayerListener)
            newPlayer.addListener(playbackPolicyPlayerListener)
            newPlayer.addListener(playbackStartFadePlayerListener)
            newPlayer.addListener(usbDiagnosticsPlayerListener) // [v19·USB日志补全] 状态机/config快照观测
            newPlayer.addListener(durationBackfillListener)
            newPlayer.addListener(smartCrossfadePlayerListener)
            playbackAudioFxController = PlaybackAudioFxController().also { controller ->
                controller.setAudioSessionId(newPlayer.audioSessionId)
            }
            (newPlayer as? androidx.media3.exoplayer.ExoPlayer)
                ?.addAnalyticsListener(PlaybackDiagnosticsListener { this })

            // 5) 重建依赖组件
            listeningMemoryStore = ListeningMemoryStore(this)
            newPlayer.addListener(memoryListener)
            startMemoryPlayTimeTicker()
            playbackMetadataPreloader = PlaybackMetadataPreloader(
                context = this, player = newPlayer, scope = serviceScope,
            ).also { it.start() }
            playbackPlayCountTracker = PlaybackPlayCountTracker(
                player = newPlayer, repository = playbackStatsRepository, scope = serviceScope,
                onPlayCountChanged = { scheduleStatsLibraryRefresh() },
            ).also { it.start() }
            playbackSessionStateCoordinator = PlaybackSessionStateCoordinator(
                player = newPlayer,
                stateStore = playbackSessionStateStore,
                scope = serviceScope,
                canLoadLibraryItems = { hasAudioPermission() },
                loadLibraryItemsByQueueKeys = { keys -> getAudioItemsByQueueKeys(keys) },
            ).also { it.start() }

            // 6) 会话换到新 player（通知/控制面无感）
            mediaLibrarySession?.setPlayer(
                PlaybackStartFadePlayer(newPlayer, playbackStartFadeController, smartCrossfadeController),
            )
            refreshNotificationButtons()

            // 7) 恢复队列与播放意图
            // [1.4.25·R66] 优先用第 1 步抓下来的 MediaItem 列表做同步恢复：
            // setMediaItems + prepare 都是同步生效的，紧接着 seekTo 才有对象可 seek。
            // 队列真的空（比如启动即切引擎、快照还没加载）时才退回 coordinator 的异步快照恢复。
            var restoredSynchronously = false
            if (savedItems.isNotEmpty()) {
                val idx = savedIndex.coerceIn(0, savedItems.lastIndex)
                val pos = if (idx == savedIndex) savedPosition.coerceAtLeast(0L) else 0L
                runCatching {
                    newPlayer.pause()
                    newPlayer.setMediaItems(savedItems, idx, pos)
                    newPlayer.prepare()
                    restoredSynchronously = true
                }.onFailure {
                    AppLog.w("Player", "1.4.25 切引擎同步恢复队列失败，退回快照恢复: ${it.message}")
                }
            }
            if (!restoredSynchronously && newPlayer.mediaItemCount == 0 && savedIndex >= 0 && savedPosition >= 0) {
                runCatching {
                    playbackSessionStateCoordinator?.restoreIfQueueEmpty()
                    if (newPlayer.mediaItemCount > 0) {
                        val idx = savedIndex.coerceAtMost(newPlayer.mediaItemCount - 1)
                        newPlayer.seekTo(idx, savedPosition)
                    }
                }
            }
            // [1.4.25·R67a] 恢复后是否自动续播由设置项决定，默认"暂停"（保持用户手动点播放）。
            // 只有用户在设置里打开"切引擎后自动续播"，且切之前本来就在放，才继续播。
            val autoPlayAfterRestore =
                com.smartisan.music.data.settings.EngineModeStore.restoreAutoPlay.value
            if (newPlayer.mediaItemCount > 0) {
                if (savedPlaying && autoPlayAfterRestore) {
                    newPlayer.play()
                    AppLog.i("Player", "1.4.25 切引擎恢复后自动续播（设置已开启）")
                } else {
                    AppLog.i(
                        "Player",
                        "1.4.25 切引擎恢复后保持暂停 autoPlay=$autoPlayAfterRestore savedPlaying=$savedPlaying",
                    )
                }
            }
            AppLog.i(
                "Player",
                "1.4.18 播放内核重建完成 engine=${mode.id} items=${newPlayer.mediaItemCount}" +
                    " idx=$savedIndex pos=$savedPosition playing=$savedPlaying" +
                    " syncRestored=$restoredSynchronously autoPlay=$autoPlayAfterRestore",
            )
        } catch (t: Throwable) {
            AppLog.w("Player", "1.4.18 播放内核重建失败，保留现状: ${t.message}")
        } finally {
            rebuildingForEngine = false
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        if (!exclusionsReady.isCompleted) {
            exclusionsReady.complete(exclusionsSnapshot)
        }
        playbackSessionStateCoordinator?.let { coordinator ->
            // [1.4.28] onDestroy 在主线程：DataStore 写入时长不可控，旧写法无限期阻塞会 ANR。
            // 最多等 800ms，超时就放弃这次落盘（下次启动会按已有快照恢复）。
            runBlocking {
                withTimeoutOrNull(800L) { coordinator.saveNow() }
            }
            coordinator.stop()
        }
        playbackSessionStateCoordinator = null
        playbackPlayCountTracker?.let { tracker ->
            // [1.4.28] 同上：统计落库最多等 800ms，不拿主线程无限期等磁盘。
            runBlocking {
                withTimeoutOrNull(800L) { tracker.stopAndFlush() }
            }
        }
        playbackPlayCountTracker = null
        pendingStatsLibraryRefreshJob?.cancel()
        pendingStatsLibraryRefreshJob = null
        pendingRatingLibraryRefreshJob?.cancel()
        pendingRatingLibraryRefreshJob = null
        flushMemorizedPlayTime()
        player?.removeListener(memoryListener)
        listeningMemoryStore = null
        playbackMetadataPreloader?.stop()
        playbackMetadataPreloader = null
        UsbAudioMonitor.stop()
        runCatching { unregisterReceiver(headsetPlugReceiver) }
        runCatching { unregisterReceiver(volumeChangeReceiver) }
        AudioOutputRouter.clear()
        AudioOutputMonitor.clear()
        stopNotificationProgressTicker()
        customNotificationProvider = null
        notificationProvider = null
        favoriteRepository = null
        cancelPendingPlaybackStart()
        serviceScope.cancel()
        PlaybackSleepTimer.cancel()
        player?.removeListener(audioFxPlayerListener)
        player?.removeListener(playbackStartFadePlayerListener)
        player?.removeListener(durationBackfillListener)
        playbackStartFadeController.release(player)
        UsbVolume.handoff?.release()
        UsbVolume.handoff = null
        playbackAudioFxController?.release()
        playbackAudioFxController = null
        mediaLibrarySession?.release()
        mediaLibrarySession = null

        player?.release()
        player = null
        playbackLoadControl = null
        mediaSessionArtworkBitmapLoader?.shutdown()
        mediaSessionArtworkBitmapLoader = null
        libraryExecutor.shutdown()
        libraryRefreshExecutor.shutdown()

        super.onDestroy()
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getAudioItems(forceRefresh: Boolean = false): List<MediaItem> {
        if (!hasAudioPermission()) {
            return emptyList()
        }
        val exclusions = if (exclusionsReady.isCompleted) {
            exclusionsSnapshot
        } else {
            runBlocking { exclusionsReady.await() }
        }
        // 资料库列表只剔除「目录被隐藏」的歌曲；单独隐藏的歌曲必须保留在列表里，
        // 否则歌曲页编辑模式下它们会彻底消失、无法恢复显示（与文件夹页「标记隐藏」模型一致）。
        // 单独隐藏的歌曲由各页面按 hiddenMediaIds 在展示层过滤，且播放解析(getAudioItemsByIds)仍会拦截。
        // 文件扫描开关命中的内容在此直接排除——当作从未扫描到过，不进入任何页面与编辑模式。
        val fileScan = fileScanSettingsSnapshot
        return localAudioLibrary.getAudioItems(forceRefresh = forceRefresh)
            .asSequence()
            .filter { item ->
                val relativePath = item.mediaMetadata.extras
                    ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                !exclusions.isHiddenByDirectory(relativePath) && !item.isHiddenByFileScan(fileScan)
            }
            .toList()
    }

    private fun getAudioItemsByIds(mediaIds: List<String>): List<MediaItem> {
        if (!hasAudioPermission() || mediaIds.isEmpty()) {
            return emptyList()
        }
        val exclusions = if (exclusionsReady.isCompleted) {
            exclusionsSnapshot
        } else {
            runBlocking { exclusionsReady.await() }
        }
        val fileScan = fileScanSettingsSnapshot
        return localAudioLibrary.getAudioItemsByIds(mediaIds)
            .asSequence()
            .filter { item ->
                val relativePath = item.mediaMetadata.extras
                    ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                !exclusions.isMediaHidden(item.mediaId, relativePath) &&
                    !item.isHiddenByFileScan(fileScan)
            }
            .toList()
    }

    private fun resolveSessionPlaybackMediaItems(mediaItems: List<MediaItem>): MutableList<MediaItem> {
        mediaItems.resolveDirectSessionPlaybackItemsOrNull()?.let { directItems ->
            return directItems
        }
        val localItemsById = getAudioItemsByIds(
            mediaItems
                .filterNot(MediaItem::canResolveDirectSessionPlaybackItem)
                .map(MediaItem::mediaId),
        ).associateBy(MediaItem::mediaId)
        return mediaItems.mapTo(mutableListOf()) { item ->
            // 1.2.22：严禁在重建队列时丢弃任何项。解析失败（如索引未刷新导致 f 前缀
            // DFF 补录行查不到）时保留原始 UI MediaItem——它自带可用 URI，且能保证
            // 队列长度与索引不变，避免「播错歌 / 顺序播放跳歌 / 部分歌点不动」的错位问题。
            item.toDirectSessionPlaybackItemOrNull()
                ?: localItemsById[item.mediaId]
                ?: item.also {
                    AppLog.w(
                        PlaybackDiagnosticsTag,
                        "队列重建保留原始项（解析缺失）mediaId=${item.mediaId}" +
                            " hasUri=${item.localConfiguration?.uri != null}",
                    )
                }
        }
    }

    /**
     * 1.2.24：转码格式（DFF/APE/DSF/DTS…）MediaStore 无时长，且流式 FLAC 起播瞬间
     * `player.duration` 仍是未知，导致大播放页进度条永远 0:00、无法拖动。
     * 点播起始项时按需从 [FormatCache]/文件头补一次时长并写回元数据（仅在 IO 线程调用）。
     */
    private fun enrichDuration(item: MediaItem): MediaItem {
        val existing = item.mediaMetadata.durationMs ?: 0L
        if (existing > 0L) return item
        val uri = item.localConfiguration?.uri ?: return item
        val srcExtras = item.mediaMetadata.extras ?: Bundle()
        var probed = runCatching { FormatCache.get(uri.toString(), 0L) }.getOrNull()
        var durationMs = probed?.durationMs ?: 0L
        if (durationMs <= 0L) {
            probed =
                runCatching {
                    AudioFormatProbe.probe(
                        context = this,
                        uri = uri,
                        filePath = null,
                        fileSize = srcExtras.getLong(LocalAudioLibrary.FileSizeExtraKey, 0L),
                        durationMs = 0L,
                        mimeHint = srcExtras.getString(LocalAudioLibrary.MimeTypeExtraKey),
                        container =
                            srcExtras.getString(LocalAudioLibrary.DisplayNameExtraKey)
                                ?.substringAfterLast('.', "")
                                ?.lowercase()
                                .orEmpty(),
                    )
                }.getOrNull()
            durationMs = probed?.durationMs ?: 0L
            if (probed != null) {
                // 就地回填：播过一次后，列表/下次播放即可直接拿到采样率/位深/码率/时长。
                // 1.2.25：同时落盘（旧版只 put 不 save，重启后全丢，每次冷启都得重探一遍）。
                runCatching {
                    FormatCache.put(uri.toString(), 0L, probed)
                    FormatCache.save(this)
                }
            }
        }
        if (durationMs <= 0L) {
            AppLog.i(PlaybackDiagnosticsTag, "起点补时长失败 mediaId=${item.mediaId} uri=${uri}")
            return item
        }
        AppLog.i(PlaybackDiagnosticsTag, "起点补时长 mediaId=${item.mediaId} durationMs=$durationMs")
        // 1.2.25：顺带把采样率/位深/码率/声道写回 extras——「歌曲信息」弹层直接读这几个
        // 字段，旧版只补了 duration，所以那几行一直显示「—」。
        val mergedExtras =
            Bundle(srcExtras).apply {
                probed?.let { spec ->
                    if (spec.sampleRateHz > 0) {
                        putInt(LocalAudioLibrary.SampleRateHzExtraKey, spec.sampleRateHz)
                    }
                    if (spec.bitsPerSample > 0) {
                        putInt(LocalAudioLibrary.BitsPerSampleExtraKey, spec.bitsPerSample)
                    }
                    if (spec.bitrateBps > 0) {
                        putInt(LocalAudioLibrary.BitrateBpsExtraKey, spec.bitrateBps)
                    }
                    if (spec.channelCount > 0) {
                        putInt(LocalAudioLibrary.ChannelCountExtraKey, spec.channelCount)
                    }
                    if (spec.codec.isNotBlank()) {
                        putString(LocalAudioLibrary.FormatExtraKey, spec.codec)
                    }
                }
            }
        val metadata =
            item.mediaMetadata
                .buildUpon()
                .setDurationMs(durationMs)
                .setExtras(mergedExtras)
                .build()
        return item.buildUpon().setMediaMetadata(metadata).build()
    }

    /** 取当前播放队列的 mediaId 序列（用于判断是否「队列已匹配」走秒切快路径）。 */
    private fun currentTimelineMediaIds(player: Player): List<String>? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val ids = ArrayList<String>(timeline.windowCount)
        val window = androidx.media3.common.Timeline.Window()
        for (i in 0 until timeline.windowCount) {
            timeline.getWindow(i, window)
            val id = window.mediaItem.mediaId
            if (id.isNullOrBlank()) return null
            ids.add(id)
        }
        return ids
    }

    private fun replaceResolvedQueueAndPlay(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        shuffleModeEnabled: Boolean,
    ): SessionResult {
        val safeStartIndex = playbackQueueStartIndex(
            itemCount = mediaItems.size,
            startIndex = startIndex,
        ) ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val playbackPlayer = player ?: return SessionResult(SessionError.ERROR_UNKNOWN)
        AppLog.i(
            PlaybackDiagnosticsTag,
            "重建队列(setMediaItems): count=${mediaItems.size} startIndex=$safeStartIndex" +
                " startId=${mediaItems[safeStartIndex].mediaId}" +
                " firstId=${mediaItems.firstOrNull()?.mediaId}" +
                " shuffle=$shuffleModeEnabled" +
                " beforeId=${playbackPlayer.currentMediaItem?.mediaId}" +
                " beforeIndex=${playbackPlayer.currentMediaItemIndex}/${playbackPlayer.mediaItemCount}",
        )
        playbackStartFadeController.protectNextPlayback(playbackPlayer)
        // 1.2.31：setMediaItems 之前的参数诊断不够——1.2.30 日志显示「重建队列」打印了、
        // 「replaceQueueAndPlayDirect」也打印了，但播放器队列始终为 0、零次 transition，
        // 而异常被 Log.w（只进 logcat、不写导出文件）吞掉，用户日志里完全看不到失败。
        // 现在把异常写进文件，并在调用后打印真实队列状态，起播是否成功一眼可判。
        val missingUriCount = mediaItems.count { it.localConfiguration?.uri == null }
        if (missingUriCount > 0) {
            AppLog.e(
                PlaybackDiagnosticsTag,
                "重建队列含 $missingUriCount 个无 URI 的 MediaItem（会导致 setMediaItems 失败）:" +
                    " head=" +
                    mediaItems.filter { it.localConfiguration?.uri == null }
                        .take(5)
                        .joinToString(",") { it.mediaId },
            )
        }
        // 兜底：个别项缺 URI 时补一个空 URI 占位，保证 setMediaItems 不会因为单项
        // 无 localConfiguration 而整体失败（1.2.30 的 652 首队列就是这样全军覆没的）。
        // 队列长度与起点索引保持不变，只有那一首会报源错误，不会连累整张列表。
        val playableItems =
            if (missingUriCount == 0) {
                mediaItems
            } else {
                mediaItems.map { item ->
                    if (item.localConfiguration?.uri != null) {
                        item
                    } else {
                        item.buildUpon().setUri(android.net.Uri.EMPTY).build()
                    }
                }
            }
        playbackPlayer.replaceQueueAndPlayDirect(
            mediaItems = playableItems,
            startIndex = safeStartIndex,
            shuffleModeEnabled = shuffleModeEnabled,
        )
        // [1.4.30·R140] 队列一建立就把"当前曲 → 下一曲"喂给交叉淡化桥。
        // 不能只依赖 onMediaItemTransition：那是"切到新曲之后"才发生的，
        // 而对于刚建立队列的第一首，DataSource 已经在读了，桥里还没登记它，
        // 尾部窗口会查不到下一曲（旧实现就是这样整首错过淡化）。
        runCatching { updateCrossfadeQueue() }
        AppLog.i(
            PlaybackDiagnosticsTag,
            "重建队列完成: afterCount=${playbackPlayer.mediaItemCount}" +
                " afterIndex=${playbackPlayer.currentMediaItemIndex}" +
                " afterId=${playbackPlayer.currentMediaItem?.mediaId}" +
                " playWhenReady=${playbackPlayer.playWhenReady}" +
                " missingUri=$missingUriCount",
        )
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    /**
     * 1.2.26：大队列的「只传 id」通道。
     *
     * 整条队列的 MediaItemBundle（每首附带 uri/metadata/extras）在千首量级会超过 Binder 1MB
     * 事务上限，`sendCustomCommand` 直接失败且没人处理 → 用户看到「点了播放全部没反应」。
     * 这里只传 id，服务端按 id 从资料库还原；被隐藏/已删除的条目自然消失，起点按 mediaId 重新对齐。
     */
    /**
     * 1.3：把 `f` 前缀的 Files 补录行 mediaId（如 `f1000311782`）还原成可播放 uri
     * `content://media/external/file/1000311782`，与 LocalAudioLibrary 补录行的构造规则
     * (`ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)`) 逐字一致。
     * 「只传 id」通道不再依赖资料库索引新鲜度 / 缓存命中 / bundle 反序列化，DFF 永不丢项。
     */
    private fun unscannedFileUri(mediaId: String): Uri? {
        if (!mediaId.startsWith(LocalAudioLibrary.UnscannedMediaIdPrefix)) {
            return null
        }
        val fileId =
            mediaId
                .removePrefix(LocalAudioLibrary.UnscannedMediaIdPrefix)
                .toLongOrNull()
                ?: return null
        if (fileId <= 0L) return null
        return ContentUris.withAppendedId(
            MediaStore.Files.getContentUri("external"),
            fileId,
        )
    }

    private fun replaceQueueAndPlayByIds(
        args: Bundle,
        requestedIds: List<String>,
    ): ListenableFuture<SessionResult> {
        val startIndex = args.getInt(ReplaceQueueStartIndexKey, 0)
        val shuffleModeEnabled = args.getBoolean(ReplaceQueueShuffleModeKey, false)
        val targetId =
            requestedIds.getOrNull(startIndex.coerceIn(0, (requestedIds.size - 1).coerceAtLeast(0)))
        val directItems = args.decodeReplaceQueueDirectItems()
        AppLog.i(
            PlaybackDiagnosticsTag,
            "收到重建队列[只传 id]: requested=${requestedIds.size} startIndex=$startIndex" +
                " targetId=$targetId direct=${directItems.size} shuffle=$shuffleModeEnabled",
        )

        val requestGeneration = playbackStartRequestGeneration.incrementAndGet()
        cancelPendingPlaybackStart()
        val resultFuture = SettableFuture.create<SessionResult>()
        pendingPlaybackStartFuture = resultFuture
        val startJob =
            serviceScope.launch {
                val result =
                    try {
                        val resolved =
                            withContext(Dispatchers.IO) {
                                val byId =
                                    getAudioItemsByIds(requestedIds)
                                        .associateBy { it.mediaId }
                                        .toMutableMap()
                                val directUris = args.decodeReplaceQueueDirectUris()
                                // 1.3：**按 requestedIds 原顺序逐项产出，绝不丢弃任何一项**。
                                // 旧逻辑用 mapNotNull + `byId[id] ?: return null`：一旦资料库索引过期 /
                                // 缓存未命中，f 前缀（Files 补录行，DFF/DSF/APE）查不到就被整批丢弃 →
                                // targetPresent=false → 拒绝重建队列 → 用户点歌曲列表里的 DFF 没反应，
                                // 而搜索页队列短走「完整 bundle」通道所以能播。
                                // f 前缀的 uri 是确定性规则 `content://media/external/file/<id>`，
                                // 去掉前缀即可还原，不依赖索引/缓存/也不依赖客户端 MediaItem bundle
                                // 反序列化（1.2.32 那两重兜底都因源数据本就不带 uri 而空转）。
                                // 优先用资料库还原项（元数据最全），缺 uri 时按规则补齐；每项必返回
                                // MediaItem，队列长度与顺序与 UI 完全一致，targetPresent 恒为真。
                                var uriFromLibrary = 0
                                var uriFromDirectStrings = 0
                                var uriFromIdRule = 0
                                var uriPlaceholders = 0
                                val items =
                                    requestedIds.map { id ->
                                        val libraryItem = byId[id]
                                        if (libraryItem?.localConfiguration?.uri != null) {
                                            uriFromLibrary += 1
                                            return@map libraryItem
                                        }
                                        val base =
                                            libraryItem
                                                ?: directItems[id]
                                                ?: MediaItem.Builder().setMediaId(id).build()
                                        val directUri = directUris[id]
                                        val bundledUri = base.localConfiguration?.uri
                                        val ruleUri = unscannedFileUri(id)
                                        val uri =
                                            when {
                                                directUri != null -> {
                                                    uriFromDirectStrings += 1
                                                    directUri
                                                }
                                                bundledUri != null -> bundledUri
                                                ruleUri != null -> {
                                                    uriFromIdRule += 1
                                                    ruleUri
                                                }
                                                else -> null
                                            }
                                        if (uri == null) {
                                            uriPlaceholders += 1
                                            // 仍不丢项：空 URI 占位，保证队列长度/索引不错位；
                                            // replaceResolvedQueueAndPlay 会为单项补占位，不拖垮整表。
                                            base.buildUpon().setUri(Uri.EMPTY).build()
                                        } else {
                                            base.buildUpon().setUri(uri).build()
                                        }
                                    }
                                AppLog.i(
                                    PlaybackDiagnosticsTag,
                                    "按 id 还原队列明细: fromLibrary=$uriFromLibrary" +
                                        " fromDirectStrings=$uriFromDirectStrings" +
                                        " fromIdRule=$uriFromIdRule placeholders=$uriPlaceholders" +
                                        " produced=${items.size}/${requestedIds.size}",
                                )
                                items
                            }
                        val resolvedIds = resolved.mapTo(HashSet(resolved.size)) { it.mediaId }
                        val dropped = requestedIds.filter { id -> id !in resolvedIds }
                        val targetPresent = targetId != null && targetId in resolvedIds
                        AppLog.i(
                            PlaybackDiagnosticsTag,
                            "按 id 解析队列: requested=${requestedIds.size} resolved=${resolved.size}" +
                                " dropped=${dropped.size}" +
                                (if (dropped.isEmpty()) "" else " droppedHead=" +
                                    dropped.take(8).joinToString(",")) +
                                " first=${resolved.firstOrNull()?.mediaId}" +
                                " targetPresent=$targetPresent",
                        )
                        if (playbackStartRequestGeneration.get() != requestGeneration) {
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } else if (resolved.isEmpty() || !targetPresent) {
                            // 1.2.30：目标 id 不在队列里时**绝不重建**。
                            // 旧代码 indexOfFirst(...).coerceAtLeast(0) 会把当前播放打回第 0 首，
                            // 之后每次队列/媒体库变化都重建一次 → 用户看到的「一直跳」。
                            // 宁可这次点击不生效，也不能把正在播的歌冲掉。
                            AppLog.w(
                                PlaybackDiagnosticsTag,
                                "按 id 重建队列被拒绝（保持当前队列不动）:" +
                                    " targetId=$targetId resolved=${resolved.size}" +
                                    " resolvedHead=" +
                                    resolved.take(5).joinToString(",") { it.mediaId },
                            )
                            SessionResult(SessionError.ERROR_BAD_VALUE)
                        } else {
                            val actualStart = resolved.indexOfFirst { it.mediaId == targetId }
                            replaceResolvedQueueAndPlay(
                                mediaItems = resolved,
                                startIndex = actualStart,
                                shuffleModeEnabled = shuffleModeEnabled,
                            )
                        }
                    } catch (_: CancellationException) {
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (error: Exception) {
                        // 1.2.31：原来用 android.util.Log.w，只进 logcat、不写导出文件，
                        // 用户发来的日志里永远看不到「为什么点了没反应」。改成 AppLog.e
                        // 并带上异常类型与堆栈。
                        AppLog.e(
                            PlaybackDiagnosticsTag,
                            "按 id 重建队列失败 type=${error.javaClass.simpleName}" +
                                " msg=${error.message}",
                            error,
                        )
                        SessionResult(SessionError.ERROR_UNKNOWN)
                    }
                if (!resultFuture.isDone) resultFuture.set(result)
            }
        pendingPlaybackStartJob = startJob
        return resultFuture
    }

    private fun replaceQueueAndPlayFromSessionCommand(args: Bundle): ListenableFuture<SessionResult> {
        // 1.2.26：大队列只传了 mediaId，先按 id 从资料库还原成 MediaItem（顺序与请求一致）。
        val requestedIds = args.decodeReplaceQueueAndPlayMediaIds()
        if (requestedIds != null) {
            return replaceQueueAndPlayByIds(args, requestedIds)
        }
        val mediaItems = args.decodeReplaceQueueAndPlayMediaItems()
        val startIndex = args.getInt(ReplaceQueueStartIndexKey, 0)
        val shuffleModeEnabled = args.getBoolean(ReplaceQueueShuffleModeKey, false)
        AppLog.i(
            PlaybackDiagnosticsTag,
            "收到重建队列[完整 bundle]: count=${mediaItems.size} startIndex=$startIndex" +
                " targetId=${mediaItems.getOrNull(startIndex)?.mediaId} shuffle=$shuffleModeEnabled",
        )
        val safeStartIndex = playbackQueueStartIndex(
            itemCount = mediaItems.size,
            startIndex = startIndex,
        ) ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))

        // 1.2.23：队列已完全匹配时走「秒切」快路径——不再把整张（可能数千首）歌单交给
        // getAudioItemsByIds 重新解析（旧逻辑每次点击耗时 1~3 秒，表现为「点了没反应、正在放的
        // 歌先停一下、再点才播」）。仅 seek 到目标位置并续播，播放栏立即切到该曲、缓冲时显示「正在缓冲」。
        // 1.2.25：秒切路径同样要补时长——DFF/APE 等转码格式 MediaStore 无时长，旧版只有完整
        // 重建路径（enrichDuration）补，导致从歌曲页点歌（队列没变、走快路径）时进度条永远 0:00、
        // 拖动无效、打碟按钮被 durationMs>0 的守卫禁用。这里对目标项补一次（单首、只读文件头）。
        if (player != null) {
            val pl = player!!
            val incomingIds = mediaItems.map { it.mediaId }
            val currentIds = currentTimelineMediaIds(pl)
            if (currentIds != null &&
                currentIds.size == incomingIds.size &&
                currentIds == incomingIds &&
                pl.shuffleModeEnabled == shuffleModeEnabled
            ) {
                playbackStartRequestGeneration.incrementAndGet()
                cancelPendingPlaybackStart()
                playbackStartFadeController.protectNextPlayback(pl)
                val target = mediaItems[safeStartIndex]
                pl.seekToDefaultPosition(safeStartIndex)
                pl.playWhenReady = true
                // 1.2.26：时长补齐挪到后台 IO。旧版在这里直接调 enrichDuration()——它在
                // onCustomCommand（主线程）上做 MediaMetadataRetriever 探测 + 磁盘写，
                // 点一首没缓存过的 DFF/APE 会卡几百 ms，正好抵消了「秒切」。
                backfillDurationAsync(target.mediaId)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        val requestGeneration = playbackStartRequestGeneration.incrementAndGet()
        cancelPendingPlaybackStart()

        val resultFuture = SettableFuture.create<SessionResult>()
        pendingPlaybackStartFuture = resultFuture
        lateinit var startJob: Job
        startJob = serviceScope.launch {
            val result = try {
                val resolvedItems = withContext(Dispatchers.IO) {
                    val resolved = resolveSessionPlaybackMediaItems(mediaItems)
                    if (safeStartIndex in resolved.indices) {
                        resolved[safeStartIndex] = enrichDuration(resolved[safeStartIndex])
                    }
                    resolved
                }
                if (playbackStartRequestGeneration.get() != requestGeneration) {
                    SessionResult(SessionResult.RESULT_SUCCESS)
                } else {
                    replaceResolvedQueueAndPlay(
                        mediaItems = resolvedItems,
                        startIndex = safeStartIndex,
                        shuffleModeEnabled = shuffleModeEnabled,
                    )
                }
            } catch (_: CancellationException) {
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (error: Exception) {
                AppLog.e(
                    PlaybackDiagnosticsTag,
                    "Playback start resolution failed type=${error.javaClass.simpleName} " +
                        "message=${error.message}",
                    error,
                )
                SessionResult(SessionError.ERROR_UNKNOWN)
            }
            if (!resultFuture.isDone) {
                resultFuture.set(result)
            }
            if (pendingPlaybackStartJob === startJob) {
                pendingPlaybackStartJob = null
                pendingPlaybackStartFuture = null
            }
        }
        pendingPlaybackStartJob = startJob
        resultFuture.addListener(
            {
                if (resultFuture.isCancelled) {
                    startJob.cancel()
                }
            },
            MoreExecutors.directExecutor(),
        )
        return resultFuture
    }

    private fun cancelPendingPlaybackStart() {
        pendingPlaybackStartJob?.cancel()
        pendingPlaybackStartJob = null
        pendingPlaybackStartFuture
            ?.takeUnless(SettableFuture<SessionResult>::isDone)
            ?.set(SessionResult(SessionResult.RESULT_SUCCESS))
        pendingPlaybackStartFuture = null
    }

    private suspend fun getAudioItemsByQueueKeys(queueKeys: List<PlaybackQueueSnapshotItem>): List<MediaItem> {
        if (queueKeys.isEmpty()) {
            return emptyList()
        }
        return if (hasAudioPermission()) {
            val exclusions = if (exclusionsReady.isCompleted) {
                exclusionsSnapshot
            } else {
                exclusionsReady.await()
            }
            localAudioLibrary.getAudioItemsByQueueKeys(queueKeys)
                .asSequence()
                .filter { item ->
                    val relativePath = item.mediaMetadata.extras
                        ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                    !exclusions.isMediaHidden(item.mediaId, relativePath)
                }
                .toList()
        } else {
            emptyList()
        }
    }

    private fun createSessionActivityPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            PlaybackSessionActivityRequestCode,
            MainActivity.createOpenPlaybackIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * [1.4.25·R63] 点通知文字/空白处只"回到 App 上次界面"，不强行跳大播放页。
     *
     * 之前通知的 contentIntent 和 sessionActivity 共用一个 [createSessionActivityPendingIntent]，
     * 两者都带 ActionOpenPlayback → 用户只是点了一下通知想回列表继续挑歌，却被硬拽进大播放页。
     * 现在拆成两个：contentIntent 用普通的 LAUNCHER 式恢复（不改变用户当前所在页面），
     * sessionActivity 仍保留"跳大播放页"——那是锁屏/媒体控制面板的语义，本来就该直达播放。
     */
    private fun createAppResumePendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            AppResumeRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun removeHiddenQueuedItems(exclusions: LibraryExclusions) {
        (player as? ExoPlayer)?.removeMediaItemsMatching { item ->
            val relativePath = item.mediaMetadata.extras
                ?.getString(LocalAudioLibrary.RelativePathExtraKey)
            exclusions.isMediaHidden(item.mediaId, relativePath)
        }
    }

    private inner class PlaybackLibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(ScratchSeekModeCommand)
                .add(StartSleepTimerCommand)
                .add(CancelSleepTimerCommand)
                .add(RefreshLibraryCommand)
                .add(InvalidateLibraryCommand)
                .add(SetTrackRatingCommand)
                .add(ReplaceQueueAndPlayCommand)
                .add(NotificationPlaybackModeCommand)
                .add(NotificationFavoriteCommand)
                .add(NotificationPlayPauseCommand)
                .add(NotificationNextCommand)
                .add(NotificationPreviousCommand)
                .add(UpdateTrackMetadataCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(
                LibraryResult.ofItem(localAudioLibrary.getRootItem(), params),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != LocalAudioLibrary.ROOT_ID) {
                    return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
                }

                val items = getAudioItems()
                if (items.isEmpty() && !hasAudioPermission()) {
                    return@submit LibraryResult.ofError(
                        SessionError.ERROR_PERMISSION_DENIED,
                        params,
                    )
                }

                val fromIndex = (page * pageSize).coerceAtMost(items.size)
                val toIndex = (fromIndex + pageSize).coerceAtMost(items.size)
                LibraryResult.ofItemList(items.subList(fromIndex, toIndex), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return libraryExecutor.submit<LibraryResult<MediaItem>> {
                if (!hasAudioPermission() && mediaId != LocalAudioLibrary.ROOT_ID) {
                    return@submit LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
                }

                val item = if (mediaId == LocalAudioLibrary.ROOT_ID) {
                    localAudioLibrary.getRootItem()
                } else {
                    getAudioItemsByIds(listOf(mediaId)).firstOrNull()
                }
                if (item == null) {
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                } else {
                    LibraryResult.ofItem(item, null)
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            mediaItems.resolveDirectSessionPlaybackItemsOrNull()?.let { resolvedItems ->
                return Futures.immediateFuture(resolvedItems)
            }
            return libraryExecutor.submit<MutableList<MediaItem>> {
                resolveSessionPlaybackMediaItems(mediaItems)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            // 1.3.3：记录「到底是什么操作」——每条 UI 命令都在此落一帧，
            // 用户说「点了切歌/拖了进度条」时可直接按 op 与时间戳对上。
            logState(
                op = "自定义命令:${customCommand.customAction}",
                source = "MediaLibrarySession.Callback.onCustomCommand",
            )
            if (customCommand.customAction == ScratchSeekModeAction) {
                val enabled = args.getBoolean(ScratchSeekModeEnabledKey, false)
                (player as? ExoPlayer)?.setSeekParameters(
                    if (enabled) SeekParameters.EXACT else SeekParameters.DEFAULT,
                )
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == StartSleepTimerAction) {
                val durationMs = args.getLong(SleepTimerDurationMsKey, 0L)
                if (durationMs <= 0L) {
                    return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_BAD_VALUE),
                    )
                }
                PlaybackSleepTimer.start(durationMs) {
                    PlaybackIntentAudit.trace("睡眠定时器到期") { player?.pause() }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CancelSleepTimerAction) {
                PlaybackSleepTimer.cancel()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ReplaceQueueAndPlayAction) {
                return replaceQueueAndPlayFromSessionCommand(args)
            }
            if (customCommand.customAction == RefreshLibraryAction) {
                return libraryRefreshExecutor.submit<SessionResult> {
                    if (!hasAudioPermission()) {
                        return@submit SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    }

                    val result = localAudioLibrary.refreshAudioItems()
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        result.items.size,
                        null,
                    )
                    serviceScope.launch {
                        playbackSessionStateCoordinator?.restoreIfQueueEmpty()
                    }
                    SessionResult(
                        if (result.successful) {
                            SessionResult.RESULT_SUCCESS
                        } else {
                            SessionError.ERROR_UNKNOWN
                        },
                    )
                }
            }
            if (customCommand.customAction == InvalidateLibraryAction) {
                return libraryRefreshExecutor.submit<SessionResult> {
                    if (!hasAudioPermission()) {
                        return@submit SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                    }

                    val items = getAudioItems(forceRefresh = true)
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        items.size,
                        null,
                    )
                    serviceScope.launch {
                        playbackSessionStateCoordinator?.restoreIfQueueEmpty()
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction == NotificationPlayPauseAction) {
                val playbackPlayer = player
                if (playbackPlayer == null) {
                    AppLog.w("Player", "通知栏播放键：player 为空，命令丢弃")
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                }
                val wasReady = playbackPlayer.playWhenReady
                val stateBefore = playbackPlayer.playbackState
                // [1.4.30·R134] 语义对齐 media3 默认的 COMMAND_PLAY_PAUSE，并补 STATE_ENDED：
                // 播到结尾后 playWhenReady 可能仍是 true、但 playbackState=ENDED，
                // 只调 play() 不会重播，必须先把位置拉回开头。
                if (wasReady) {
                    PlaybackIntentAudit.pause(playbackPlayer, "通知栏·播放/暂停键")?.pause()
                } else {
                    if (stateBefore == Player.STATE_ENDED) {
                        playbackPlayer.seekToDefaultPosition()
                    }
                    PlaybackIntentAudit.play(playbackPlayer, "通知栏·播放/暂停键")?.play()
                }
                AppLog.i(
                    "Player",
                    "通知栏播放键: playWhenReady $wasReady -> ${playbackPlayer.playWhenReady}" +
                        " state=${playbackStateName(stateBefore)}",
                )
                // 图标按 playWhenReady 画的，命令改完必须重发一次通知，否则图标停在旧态。
                refreshNotificationButtons()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            // [1.4.32·验收12] 通知栏上一首/下一首走自定义命令（R134 只修了 play/pause，这里补齐）。
            // 冷启后台、未建立 playWhenReady 会话时，media3 标准媒体动作 PendingIntent 投递不到，
            // 表现为「没点过播放就点下一首没反应」。走自定义通道与 play/pause 同理由可达。
            // 语义与 App 内 UI 一致：next 调 player.seekToNext()（命中 PlaybackStartFadePlayer
            // 的 override，保留智能过渡淡化），prev 调 seekToPreviousMediaItem()（播过3秒直接切，
            // 不用 seekToPrevious 的「倒回当前曲开头」语义）；均不改 playWhenReady。
            if (customCommand.customAction == NotificationNextAction ||
                customCommand.customAction == NotificationPreviousAction
            ) {
                val playbackPlayer = player
                if (playbackPlayer == null) {
                    AppLog.w("Player", "通知栏切歌键：player 为空，命令丢弃")
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
                }
                val isNext = customCommand.customAction == NotificationNextAction
                PlaybackIntentAudit.trace("通知栏·${if (isNext) "下一首" else "上一首"}") {
                    if (isNext) playbackPlayer.seekToNext()
                    else playbackPlayer.seekToPreviousMediaItem()
                }
                AppLog.i("Player", "通知栏切歌键: ${if (isNext) "next" else "prev"} " +
                    "pos=${playbackPlayer.currentPosition} item=${playbackPlayer.currentMediaItem?.mediaId}")
                refreshNotificationButtons()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == NotificationPlaybackModeAction) {
                val playbackPlayer = player ?: return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_UNKNOWN),
                )
                val shuffle = playbackPlayer.shuffleModeEnabled
                val repeat = playbackPlayer.repeatMode
                when {
                    !shuffle && repeat == Player.REPEAT_MODE_OFF -> {
                        playbackPlayer.repeatMode = Player.REPEAT_MODE_ALL
                    }
                    !shuffle && repeat == Player.REPEAT_MODE_ALL -> {
                        playbackPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    }
                    !shuffle -> {
                        playbackPlayer.repeatMode = Player.REPEAT_MODE_OFF
                        playbackPlayer.shuffleModeEnabled = true
                    }
                    else -> {
                        playbackPlayer.shuffleModeEnabled = false
                        playbackPlayer.repeatMode = Player.REPEAT_MODE_OFF
                    }
                }
                AppLog.i(
                    "Player",
                    "notification playback mode -> shuffle=${playbackPlayer.shuffleModeEnabled}" +
                        " repeat=${playbackPlayer.repeatMode}",
                )
                // [1.4.25·R69] 模式键图标是 provider.buildButtons() 里按当前模式算出来的，
                // 只 triggerNotificationUpdate() 只会把旧布局再发一遍（图标永远停在切换前那个）。
                // 必须 setMediaButtonPreferences 重建按钮列表 + 重发通知，图标才跟着变。
                refreshNotificationButtons()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == NotificationFavoriteAction) {
                val mediaId = player?.currentMediaItem?.mediaId
                if (mediaId.isNullOrBlank()) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                return libraryRefreshExecutor.submit<SessionResult> {
                    val repository = favoriteRepository
                        ?: return@submit SessionResult(SessionError.ERROR_UNKNOWN)
                    val liked = runBlocking { repository.toggle(mediaId) }
                    AppLog.i("Player", "notification favorite -> id=$mediaId liked=$liked")
                    // [1.4.20·问题8] 立即把最新收藏态增量写进 provider，避免等异步 collect
                    // 更新 favoriteIds 才刷新——否则 buildButtons 读到旧值，图标仍不变色。
                    runBlocking(Dispatchers.Main.immediate) {
                        val current = notificationProvider?.favoriteIdsSnapshot().orEmpty()
                        notificationProvider?.setFavoriteIds(
                            if (liked) current + mediaId else current - mediaId,
                        )
                        refreshNotificationButtons()
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction == SetTrackRatingAction) {
                val mediaId = args.getString(TrackRatingMediaIdKey)?.trim().orEmpty()
                val score = args.getInt(TrackRatingScoreKey, -1)
                if (mediaId.isBlank() || score !in TrackRatingMinScore..TrackRatingMaxScore) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                return libraryRefreshExecutor.submit<SessionResult> {
                    val savedScore = runBlocking {
                        playbackStatsRepository.setScore(mediaId, score)
                    } ?: return@submit SessionResult(SessionError.ERROR_UNKNOWN)
                    runBlocking(Dispatchers.Main.immediate) {
                        updateQueuedTrackRating(mediaId, savedScore)
                        scheduleRatingLibraryRefresh()
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            if (customCommand.customAction == UpdateTrackMetadataAction) {
                val mediaId = args.getString(UpdateTrackMetadataIdKey)?.trim().orEmpty()
                if (mediaId.isBlank()) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val title = args.getString(UpdateTrackMetadataTitleKey)
                val artist = args.getString(UpdateTrackMetadataArtistKey)
                val album = args.getString(UpdateTrackMetadataAlbumKey)
                // player 只能在主线程操作；与评分更新同一模式
                return libraryRefreshExecutor.submit<SessionResult> {
                    runBlocking(Dispatchers.Main.immediate) {
                        updateQueuedTrackMetadata(mediaId, title, artist, album)
                    }
                    // 编辑后同步失效媒体库缓存并通知列表重建：配合 TagOverrideStore
                    // 覆盖层，歌曲页/播放列表/搜索等立刻显示新标签，不等媒体扫描器
                    localAudioLibrary.invalidateAudioItems()
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        Int.MAX_VALUE,
                        null,
                    )
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun scheduleStatsLibraryRefresh() {
        serviceScope.launch(Dispatchers.Main.immediate) {
            pendingStatsLibraryRefreshJob?.cancel()
            pendingStatsLibraryRefreshJob = serviceScope.launch(Dispatchers.Main.immediate) {
                delay(StatsLibraryRefreshDebounceMs)
                refreshStatsLibrary()
            }
        }
    }

    private fun scheduleRatingLibraryRefresh() {
        pendingRatingLibraryRefreshJob?.cancel()
        pendingRatingLibraryRefreshJob = serviceScope.launch(Dispatchers.Main.immediate) {
            delay(RatingLibraryRefreshDebounceMs)
            pendingStatsLibraryRefreshJob?.cancel()
            pendingStatsLibraryRefreshJob = null
            refreshStatsLibrary()
        }
    }

    private fun refreshStatsLibrary() {
        localAudioLibrary.invalidateAudioItems()
        mediaLibrarySession?.notifyChildrenChanged(
            LocalAudioLibrary.ROOT_ID,
            Int.MAX_VALUE,
            null,
        )
    }

    private fun updateQueuedTrackRating(mediaId: String, score: Int) {
        val playbackPlayer = player ?: return
        for (index in 0 until playbackPlayer.mediaItemCount) {
            val item = playbackPlayer.getMediaItemAt(index)
            if (item.mediaId == mediaId) {
                playbackPlayer.replaceMediaItem(index, item.withPlaybackRating(score))
            }
        }
    }

    /** 歌曲信息编辑后，把播放队列中同 mediaId 条目的标题/歌手/专辑替换为新值。 */
    private fun updateQueuedTrackMetadata(mediaId: String, title: String?, artist: String?, album: String?) {
        val playbackPlayer = player ?: return
        for (index in 0 until playbackPlayer.mediaItemCount) {
            val item = playbackPlayer.getMediaItemAt(index)
            if (item.mediaId == mediaId) {
                playbackPlayer.replaceMediaItem(index, item.withTrackMetadata(title, artist, album))
            }
        }
    }

    private companion object {
        private const val PlaybackSessionActivityRequestCode = 1001
        private const val AppResumeRequestCode = 1002
        /** 释放/回绑内核驱动后，给 ALSA 重新 probe 的时间（否则重建输出会落到扬声器） */
        private const val ExclusiveRebindDelayMs = 600L
        // [v10·独占熔断] 滑动窗口内 UAC 会话丢失达此次数即降级；窗口 60 秒。
        // 正常设备偶发 1 次 lost（切歌/重枚举）不触发；1~2 秒连续丢 3 次的坏通道必触发。
        private const val ExclusiveLostWindowMs = 60_000L
        private const val ExclusiveLostThreshold = 3
        private const val StatsLibraryRefreshDebounceMs = 600L
        private const val RatingLibraryRefreshDebounceMs = 250L
        private const val PlaylistPreloadDurationUs = 30_000_000L
        private const val MemoryPlayTimeTickMs = 5_000L
        private const val MemoryPlayTimeFlushMs = 30_000L
    }
}

private class PlaybackStartFadePlayer(
    private val playbackPlayer: Player,
    private val fadeController: PlaybackStartFadeController,
    private val crossfadeController: SmartCrossfadeController,
) : ForwardingPlayer(playbackPlayer) {

    override fun play() {
        fadeController.protectResumeIfNeeded(playbackPlayer)
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            fadeController.protectResumeIfNeeded(playbackPlayer)
        }
        super.setPlayWhenReady(playWhenReady)
    }

    /**
     * [1.4.19·S07] 用户主动「下一曲」：智能过渡开启时淡出当前曲 → 切歌 → 淡入下一曲。
     * 关闭时 [SmartCrossfadeController.crossfadeOnUserSkip] 直接透传，行为与旧版一致。
     * 这里调用 delegate 的 seekToNext 与 super 等价（ForwardingPlayer 本就转发给 delegate）。
     */
    override fun seekToNext() {
        crossfadeController.crossfadeOnUserSkip(playbackPlayer) { playbackPlayer.seekToNext() }
    }

    /** [1.4.19·S07] 淡出途中用户拖进度：撤销淡出恢复音量，不改变 seek 本身。 */
    override fun seekTo(positionMs: Long) {
        crossfadeController.cancel(playbackPlayer)
        super.seekTo(positionMs)
    }

    /**
     * 1.2.6 起：独占音量走系统音量（LOCAL 设备类型）。
     * 音量键由系统接管，弹的是手机自带的音量条；软件音量（PCM 增益）跟随系统媒体音量。
     * 自定义音量弹窗与硬件音量调节已移除，故不再把会话报成 REMOTE，也不再接管音量键。
     */
}

private class PlaybackStartFadeController(
    private val scope: CoroutineScope,
) {
    private var fadeJob: Job? = null
    private var fadeArmTimeoutJob: Job? = null
    private var fadeArmed = false
    private var lastPausedAtMs: Long = 0L
    private var lastPausedMediaId: String? = null
    private var hasPlayed = false
    /** [1.4.20·P0] 播放/暂停淡入淡出开关；关闭时不压低音量、直接播放。默认开。 */
    @Volatile private var fadeEnabled: Boolean = true

    fun setEnabled(enabled: Boolean, playbackPlayer: Player?) {
        if (fadeEnabled == enabled) return
        fadeEnabled = enabled
        if (!enabled) cancel(playbackPlayer, resetVolumeToFull = true)
    }

    fun onPlayWhenReadyChanged(playbackPlayer: Player, playWhenReady: Boolean) {
        if (playWhenReady) {
            protectResumeIfNeeded(playbackPlayer)
            return
        }
        cancel(playbackPlayer, resetVolumeToFull = true)
        val currentMediaItem = playbackPlayer.currentMediaItem
        if (hasPlayed && currentMediaItem != null) {
            lastPausedAtMs = SystemClock.elapsedRealtime()
            lastPausedMediaId = currentMediaItem.mediaId
        }
    }

    fun onIsPlayingChanged(playbackPlayer: Player, isPlaying: Boolean) {
        if (isPlaying) {
            hasPlayed = true
            startFadeIfArmed(playbackPlayer)
        }
    }

    fun protectResumeIfNeeded(playbackPlayer: Player) {
        // [1.4.20·P0] 开关关闭：不施加任何淡入，直接播放。
        if (!fadeEnabled) return
        if (fadeJob != null || fadeArmed || !shouldProtectResume(playbackPlayer)) {
            return
        }
        lastPausedAtMs = 0L
        lastPausedMediaId = null
        armFade(playbackPlayer, startImmediatelyIfPlaying = true)
    }

    fun protectNextPlayback(playbackPlayer: Player) {
        lastPausedAtMs = 0L
        lastPausedMediaId = null
        hasPlayed = false
        if (!fadeEnabled) return
        armFade(playbackPlayer, startImmediatelyIfPlaying = false)
    }

    fun release(playbackPlayer: Player?) {
        cancel(playbackPlayer, resetVolumeToFull = true)
    }

    private fun shouldProtectResume(playbackPlayer: Player): Boolean {
        val currentMediaItem = playbackPlayer.currentMediaItem ?: return false
        if (lastPausedAtMs <= 0L) {
            return false
        }
        val pausedMediaId = lastPausedMediaId
        if (!pausedMediaId.isNullOrBlank() && currentMediaItem.mediaId != pausedMediaId) {
            return false
        }
        return SystemClock.elapsedRealtime() - lastPausedAtMs >= ResumeFadeThresholdMs
    }

    private fun armFade(
        playbackPlayer: Player,
        startImmediatelyIfPlaying: Boolean,
    ) {
        cancel(playbackPlayer = null, resetVolumeToFull = false)
        fadeArmed = true
        playbackPlayer.volume = 0f
        fadeArmTimeoutJob = scope.launch {
            delay(StartFadeArmTimeoutMs)
            if (fadeArmed && fadeJob == null) {
                playbackPlayer.volume = 1f
                fadeArmed = false
                fadeArmTimeoutJob = null
            }
        }
        // [1.4.9·G-48] 修复「暂停拖音量后点播放没反应」：armFade 把音量压到 0，
        // 但渐入要等 onIsPlayingChanged(isPlaying=true) 才启动；若该事件在音量回调
        // 竞态下缺失，音量保持 0 直到 5 秒超时——表现为点播放无声。改为「请求播放时
        // 若已在播就立即启动渐入，并把兜底超时从 5s 缩到 800ms」，不给长静默窗口。
        if (startImmediatelyIfPlaying && playbackPlayer.isPlaying) {
            startFadeIfArmed(playbackPlayer)
        }
    }

    private fun startFadeIfArmed(playbackPlayer: Player) {
        if (!fadeArmed || fadeJob != null) {
            return
        }
        fadeArmTimeoutJob?.cancel()
        fadeArmTimeoutJob = null
        val steps = (ResumeFadeDurationMs / ResumeFadeStepIntervalMs)
            .toInt()
            .coerceIn(ResumeFadeMinSteps, ResumeFadeMaxSteps)
        val stepDelay = (ResumeFadeDurationMs / steps).coerceAtLeast(1L)
        fadeJob = scope.launch {
            repeat(steps) { step ->
                delay(stepDelay)
                playbackPlayer.volume = ((step + 1).toFloat() / steps).coerceAtMost(1f)
            }
            playbackPlayer.volume = 1f
            fadeJob = null
            fadeArmed = false
            lastPausedMediaId = null
        }
    }

    private fun cancel(
        playbackPlayer: Player?,
        resetVolumeToFull: Boolean,
    ) {
        val shouldRestoreVolume = resetVolumeToFull &&
            (fadeArmed || fadeJob?.isActive == true || fadeArmTimeoutJob?.isActive == true)
        fadeJob?.cancel()
        fadeJob = null
        fadeArmTimeoutJob?.cancel()
        fadeArmTimeoutJob = null
        fadeArmed = false
        if (shouldRestoreVolume) {
            playbackPlayer?.volume = 1f
        }
    }

    private companion object {
        private const val ResumeFadeThresholdMs = 800L
        private const val ResumeFadeDurationMs = 120L
        private const val ResumeFadeStepIntervalMs = 20L
        // [1.4.9·G-48] 兜底静默窗口 5s -> 800ms：极端情况下事件缺失也不再长时间无声。
        private const val StartFadeArmTimeoutMs = 800L
        private const val ResumeFadeMinSteps = 8
        private const val ResumeFadeMaxSteps = 60
    }
}

private const val PlaybackDiagnosticsTag = "SmartisanPlayback"
