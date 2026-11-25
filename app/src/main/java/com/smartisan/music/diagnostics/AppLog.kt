package com.smartisan.music.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用内日志抓取。所有关键路径通过 [AppLog] 输出，同时落到
 * filesDir/logs/app-YYYYMMDD.log（按天滚动，保留最近 7 天）。
 * 设置页可通过 [LogExporter] 打包导出发回开发者排障。
 *
 * 日志约定（排障时靠这些字段定位，新增埋点请沿用）：
 *  - `Boot`    进程启动 / 设备与应用版本
 *  - `Library` 曲库扫描与索引
 *  - `Player`  播放控制、曲目切换、错误
 *  - `Decode`  解码器名称、输入格式、渲染器状态
 *  - `AudioOut`输出流配置、回退、underrun、AudioTrack 生命周期
 *  - `Usb`     USB 音频设备与独占状态
 *  - `Memory`  听歌记忆
 */
object AppLog {

    private const val KeepDays = 7
    private const val MaxLogBytes = 12L * 1024 * 1024

    @Volatile
    private var logsDir: File? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }

    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lineFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val writtenToday = AtomicLong(0)
    private val glossaryLogged = AtomicBoolean(false)

    fun init(context: Context) {
        if (logsDir != null) return
        synchronized(this) {
            if (logsDir != null) return
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            logsDir = dir
            writtenToday.set(todayFile(dir).length())
            executor.execute { cleanup(dir) }
        }
    }

    /** 与 [init] 等价，供 Application 调用的别名。 */
    fun install(context: Context) = init(context)

    /**
     * 写一段会话头。排障时最先看的就是这一段：没有它，光看日志无法判断
     * "用户跑的是哪个版本 / 什么机型 / 什么系统"。
     */
    fun sessionStart(context: Context, tag: String = "Boot") {
        init(context)
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val abis = Build.SUPPORTED_ABIS.joinToString(",")
        write(
            "I",
            tag,
            "==== session start ====\n" +
                "app=${packageInfo?.versionName} (${packageInfo?.longVersionCode}) pkg=${context.packageName}\n" +
                "build=${com.smartisan.music.BuildConfig.BUILD_LABEL}\n" +
                "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
                "sdk=${Build.VERSION.SDK_INT} abis=$abis\n" +
                "locale=${Locale.getDefault()}",
        )
        // 1.3.3：把「用户口语 ↔ 软件功能 ↔ 日志字段」对照表随每份日志包一起落盘，
        // 排障时不用再靠猜「用户说的打碟/摩擦声到底是日志里哪一行」。
        if (glossaryLogged.compareAndSet(false, true)) {
            write("I", tag, LogGlossary.TEXT)
        }
    }

    /**
     * 1.3.3：结构化状态快照。用户明确要求日志必须记录「当前播放状态 + 各类开关条件 +
     * 到底是什么操作 + 在什么条件下触发」，不能只靠人的描述事后推理。
     *
     * 统一格式 `State{ op=... src=... k=v ... }`，一条就是一帧完整现场，可直接 grep
     * `State{` 把所有状态帧按时间串起来复盘。字段含义见 [LogGlossary]。
     *
     * @param op 本次发生的操作或事件，用人话写（如 `进度条 seek`、`插入设备`、
     *           `切换 USB 独占`、`播放器错误`），**不要写代码符号代替**。
     * @param source 触发来源（类名 / 入口），用于区分「用户点的」还是「系统回调」。
     */
    fun state(tag: String, op: String, source: String, fields: Map<String, Any?>) {
        val body = buildString {
            append("op=").append(op.replace('\n', ' '))
            append(" src=").append(source.replace('\n', ' '))
            for ((key, value) in fields) {
                append(' ').append(key).append('=')
                    .append((value?.toString() ?: "-").replace('\n', ' ').replace('\r', ' '))
            }
        }
        write("I", tag, "State{ $body }")
    }

    /**
     * [1.4.24·调用来源审计] 记录"这一行是谁调出来的"。
     *
     * 教训：2026-11-24 03:11:06 播放器自己 `playWhenReady=false reason=1`，日志里只有结果、
     * 没有调用方，无法判断是用户、通知栏、焦点丢失还是某段自愈代码干的。用户明确要求
     * "日志要未雨绸缪、举一反三"，所以凡是**状态会被别人改**的入口，一律连带调用栈落盘。
     *
     * 只取最近若干帧、裁掉过长的类名，保证一条日志仍是一行、可 grep。
     */
    fun caller(skip: Int = 0, depth: Int = 5): String {
        val frames = Throwable().stackTrace
        if (frames.isEmpty()) return "-"
        val start = (skip + 2).coerceAtMost(frames.size)
        return frames.drop(start).take(depth).joinToString(" <- ") { frame ->
            val cls = frame.className.substringAfterLast('.')
            "$cls.${frame.methodName}:${frame.lineNumber}"
        }.ifEmpty { "-" }
    }

    /**
     * 带调用来源的状态帧。与 [state] 相同，但自动补 `caller=` 字段。
     * 用于「暂停/恢复/切路由/会话建立/焦点变化」这类需要追责到调用点的操作。
     */
    fun audit(tag: String, op: String, source: String, fields: Map<String, Any?> = emptyMap()) {
        val withCaller = LinkedHashMap<String, Any?>(fields.size + 1)
        withCaller.putAll(fields)
        withCaller["caller"] = caller(skip = 1)
        state(tag, op, source, withCaller)
    }

    fun d(tag: String, message: String) = write("D", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun w(tag: String, message: String) = write("W", tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) {
        write("E", tag, if (error != null) "$message\n${Log.getStackTraceString(error)}" else message)
    }

    /** 把一小段二进制（通常是文件头）转成十六进制，用于识别真实容器格式。 */
    fun hex(bytes: ByteArray, limit: Int = 24): String {
        val take = minOf(bytes.size, limit)
        val sb = StringBuilder(take * 3)
        val ascii = StringBuilder(take)
        for (i in 0 until take) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(String.format(Locale.US, "%02x ", v))
            ascii.append(if (v in 0x20..0x7e) v.toChar() else '.')
        }
        return "${sb.toString().trim()} | $ascii"
    }

    /**
     * 1.4.3：一次操作的追踪号。UI 上的 seek / 点下一首与它引起的播放器「切歌」
     * 帧共用同一个 traceId，日志里 grep `traceId=t12` 就能把整条链串起来。
     * 短号（36 进制递增）只为可读性，不含任何用户数据。
     */
    object Trace {
        private val seq = java.util.concurrent.atomic.AtomicLong(0)
        fun next(): String = "t" + seq.incrementAndGet().toString(36)
    }

    /**
     * 1.4.3：最近一次「用户主动发起」的操作。播放器回调（onMediaItemTransition）
     * 靠它判定 initiator=USER 还是 SYSTEM——用户明确要求日志能一眼分清
     * 「我自己拖的」和「系统乱搞的」，不要事后靠猜。
     */
    object LastUserAction {
        @Volatile private var traceId: String = ""
        @Volatile private var source: String = ""
        @Volatile private var atMs: Long = 0L

        /** UI 操作入口调用：生成新 traceId 并记为最近用户操作，返回该 id。 */
        fun record(source: String): String {
            val id = Trace.next()
            traceId = id
            this.source = source
            atMs = SystemClock.elapsedRealtime()
            return id
        }

        /** 窗口内有用户操作则返回 (traceId, source)；否则 null。不清除，允许一次拖动串多帧。 */
        fun recent(windowMs: Long): Pair<String, String>? {
            val t = traceId
            if (t.isEmpty() || SystemClock.elapsedRealtime() - atMs > windowMs) return null
            return t to source
        }
    }

    fun logsDirectory(): File? = logsDir

    private fun write(level: String, tag: String, message: String) {
        Log.println(levelPriority(level), tag, message)
        val dir = logsDir ?: return
        val line = "${lineFormat.format(Date())} $level/$tag: $message\n"
        executor.execute {
            runCatching {
                if (writtenToday.get() > MaxLogBytes) return@execute
                val f = todayFile(dir)
                f.appendText(line)
                writtenToday.set(f.length())
            }
        }
    }

    private fun todayFile(dir: File): File {
        val name = "app-${dayFormat.format(Date())}.log"
        return File(dir, name)
    }

    private fun cleanup(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.name.startsWith("app-") } ?: return
            val cutoff = System.currentTimeMillis() - KeepDays * 24L * 3600_000L
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        }
    }

    private fun levelPriority(level: String): Int = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }
}

/**
 * 1.3.3：用户口语 ↔ 软件功能 ↔ 日志字段 对照表。
 *
 * 教训：1.3.2 排查时只在 `.kt` 里搜中文字面量，没查 `strings.xml` / `R.string.xxx`，
 * 搜不到就错误推理成「唱盘爆豆音没注册、默认关闭」，把「摩擦声」也当成要猜的东西。
 * 功能名是资源 id、日志字段名是英文缩写，靠搜索源码字面量必然对不上。
 * 所以把这份对照表**写进每份日志包**，复盘时以表为准，不再推理。
 */
internal object LogGlossary {
    val TEXT: String = """
==== 术语对照：用户口语 -> 软件功能 -> 日志里对应什么 ====
版本识别     -> 会话头 build=vXX-描述（来自 gradle.properties 的 music.buildLabel，与交付 zip/apk 文件名前缀一致）-> 一行 build= 判断测的是哪一版；app= 恒为 1.4.23 区分不了各轮整改
唱盘爆豆音   -> 设置项 R.string.popcorn_sound / VinylPopcornSoundController / res/raw/y_*.ogg -> State{popcorn=...}、op=爆豆音触发
打碟         -> 设置项 R.string.djing / ScratchSoundController -> State{djing=...}
摩擦声/打碟声 -> 开启打碟后旋转黑胶唱片时的刮擦采样音 -> State{djing=true} + 打碟采样日志
恶声         -> 长按唱片静止不动时刮擦采样反复重播/增益削顶（1.3.2 已修） -> ScratchSoundController 静止超时日志
插入设备自动续播 -> 设置项 R.string.plug_auto_resume / maybeAutoResumeOnPlug() -> State{plugAutoResume=...}、op=autoResume
不播放 DSD 格式音频 -> 设置项 R.string.exclude_dsd / PlaybackPolicy.excludeDsd / DsdFilter -> State{excludeDsd=...}
USB 独占     -> 设置项 R.string.usb_exclusive / UsbExclusiveAudioSink(UAC 直出) -> State{exclusive=...}、tag=Usb
解码引擎       -> BuildConfig.PLAYBACK_ENGINE / DecodeRouter -> 日志头 engine=official|direct|stream（B正式版/A系统直读/C流式，三包并排装时靠它区分）
DSP 音效     -> 设置项 R.string.audio_fx / PcmDsp(PCM 域，独占与非独占均生效) -> State{dsp=...}
不支持的音乐格式 -> ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED(3003) -> E/Player: error code=3003
卡顿/切后台  -> ERROR_CODE_TIMEOUT(1003) / Player stuck playing with no progress -> State{fg=... buffering=... transcoding=...}
唱针/进度条拖动 -> 大播放页唱针 seek 或 PlaybackTimeSeekBar -> State{op=进度条seek/唱针seek targetMs=...}
==== 1.4.0 解码链路帧（tag=Decode，grep "Decode{" 串成时间线）====
Decode{ stage=needsTranscode|route engine=official|direct|stream plan=SYSTEM_DIRECT|TRANSCODE_FLAC|STREAM_PCM ... }
  engine=official (B正式版) -> 常见格式原始Uri直读零缓存；冷门格式/USB独占回App自解码(ffmpeg→FLAC缓存)
  engine=direct   (A测试版) -> 所有格式原始Uri交系统解码链，不转码不落盘；认不出明确失败不回退
  engine=stream   (C测试版) -> 冷门格式ffmpeg边解边喂PCM不落盘；常见格式原始Uri直读零缓存
  plan=SYSTEM_DIRECT  -> App不碰PCM/不落盘/零缓存    plan=TRANSCODE_FLAC -> App自解码FLAC缓存
  plan=STREAM_PCM     -> App自解码流式管道，全程不写磁盘缓存
SelfCheck/frontend|ffmpeg|audioDecoders|containerSniff -> 启动自检结果，先看这几行再判断「播不了」的根因
==== State 快照字段说明（一条 State{...} 就是一帧完整现场）====
op     = 本次发生的操作或事件（人话）      src = 触发来源（类名/入口，区分用户操作与系统回调）
exclusive      = 当前 UAC 会话是否真独占    exclusivePref = 设置里的 USB 独占开关
plugAutoResume = 插入设备自动续播开关        djing = 打碟开关
popcorn        = 唱盘爆豆音开关               dsp = DSP 音效开关
excludeDsd   = 不播放 DSD 格式音频开关
fg             = App 是否在前台             repeat/shuffle = 循环/随机模式
playWhenReady/isPlaying/buffering/playerState = 播放器当前状态
mediaId/format = 当前曲目与源格式           transcoding/cacheHit = 是否在转码 / 缓存是否命中
==== 记录约定 ====
会话头、每个开关变化、每次插拔设备、每条自定义命令、每次播放器错误、前后台切换都要打一帧 State；
用户反馈「某操作后出问题」时，先看该时间点的 State 帧确认当时到底开了什么、在哪个状态，
再对照 op 找实际操作，禁止凭描述猜条件。
==== 1.4.3 日志可观测性：切歌到底是谁发起的 ====
initiator = USER(用户拖进度条/唱针/点下一首) | SYSTEM(播完自动接歌/队列变化/系统回调) | WATCHDOG(缓冲看门狗自愈)
traceId   = 一次操作的追踪号；UI 帧与它引起的播放器「切歌」帧共用同一个号，grep traceId=t12 即可串起整条链
  例：State{op=进度条seek src=PlaybackTimeSeekBar.onSeek initiator=USER traceId=t12 targetMs=...}
   -> State{op=切歌 src=Player.Listener.onMediaItemTransition initiator=USER traceId=t12 reason=SEEK userSrc=PlaybackTimeSeekBar.onSeek}
  窗口(1.5s)内没有匹配的用户操作时，切歌帧 initiator=SYSTEM、traceId 另起新号，
  一眼分清「我自己拖的」和「系统乱搞的」，不再靠猜。看门狗自愈帧 initiator=WATCHDOG 独立成号。
    """.trimIndent()
}
