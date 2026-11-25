package com.smartisan.music

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.smartisan.music.data.settings.ColorThemeStore
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.platform.media.FormatCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MusicApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 1.3.3：全局前后台状态。用户反馈「切后台后缓冲很久不播 / 说不支持格式 / 播放卡顿，
     * 切回前台马上正常」，但旧日志里完全没有 fg 字段，事后无法判断问题发生时 App
     * 到底在前台还是后台。这里用 ActivityLifecycleCallbacks 记一份全局状态，
     * 播放服务的 State 快照会带上它。
     */
    companion object {
        @Volatile
        @JvmStatic
        var isForeground: Boolean = false
            private set

        /**
         * 1.4.4 清单 5：最近一次前后台切换时刻（elapsedRealtime）。
         * 播放服务用它判断「切换窗口内」冻结自动重建请求，避免切后台瞬间
         * stop/prepare 反复打断转码；窗口外（后台稳定播放）不冻结，保证掉线自救仍可用。
         */
        @Volatile
        @JvmStatic
        var lastForegroundChangeAtMs: Long = 0L
            private set

        @JvmStatic
        fun noteForegroundChange(foreground: Boolean) {
            isForeground = foreground
            lastForegroundChangeAtMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // 1.4.11 S12：引擎模式必须最先载入——DecodeRouter/门面在后续初始化里会读当前引擎。
        com.smartisan.music.data.settings.EngineModeStore.initialize(this)
        // [1.4.19·S03] USB 独占引擎选择（默认旧引擎；新开源引擎需实机验证）。
        com.smartisan.music.data.settings.UsbEngineStore.initialize(this)
        // [v20·零损失] USB bit-perfect 开关，冷启动同步载入并写入 sink 引用。
        com.smartisan.music.data.settings.UsbZeroLossStore.initialize(this)
        // [v22·音量] USB 独占音量模式（自动/数字/DAC 硬件）与 TPDF 抖动开关。
        com.smartisan.music.playback.UsbVolumeModeStore.initialize(this)
        // [1.4.19·S07] 智能过渡（自动混音）开关与时长，默认关闭，冷启动同步载入。
        com.smartisan.music.data.settings.SmartCrossfadeStore.initialize(this)
        // [1.4.20] BASS 引擎（解码+DSP+BPM）：进程级一次，加载 native so 与 DSD/APE/FLAC 插件。
        // 放在后台线程，避免阻塞冷启动；解码链路用时若尚未就绪会走既有 ffmpeg 回退。
        Thread {
            com.smartisan.music.playback.BassEngine.init(this)
        }.apply { isDaemon = true; name = "bass-init" }.start()
        // 1.3.7：载入上次选择的色彩主题（默认「彩色」= 保持现状零改动）。
        ColorThemeStore.initialize(this)
        // [1.4.23·莫奈取色] 载入独立开关。[1.4.28] 取色不走壁纸，refresh() 为 no-op。
        com.smartisan.music.data.settings.MonetStore.initialize(this)
        if (com.smartisan.music.data.settings.MonetStore.current()) {
            Thread { com.smartisan.music.ui.theme.MonetColors.refresh(this) }
                .apply { isDaemon = true; name = "monet-extract" }
                .start()
        }
        com.smartisan.music.data.settings.StatusBarStyleStore.initialize(this)
        // 1.4.11 · S16 记忆式首页开关（默认开）+ 滚动位置存储初始化，冷启动首帧同步载入。
        com.smartisan.music.data.settings.SongListMemoryStore.initialize(this)
        AppLog.sessionStart(this)
        // 1.4.0：写引擎会话头 + 启动自检（各版解码前端 / ffmpeg / 系统解码器能力）
        com.smartisan.music.diagnostics.DecodeDiagnostics.sessionHeader(this)
        installCrashLogger()
        // [1.4.24·免 ADB/Root 崩溃取证] 后台扫描系统给的进程退出记录，把 native 崩溃 / ANR
        // 的 tombstone trace 落进 logs/exit-*.txt。不阻塞冷启动；API<30 会自己写"不支持"。
        Thread {
            com.smartisan.music.diagnostics.ExitInfoCapture.capturePreviousExits(this)
        }.apply { isDaemon = true; name = "exit-info-capture" }.start()
        // [1.4.25·native 崩溃兜底] Java handler 抓不到 BASS 解 APE/DSD 时的 SIGSEGV，
        // 这里用 ApplicationExitInfo 检查"上次退出是否崩溃"，命中则补写恢复标记，
        // 让本次启动跳过崩溃曲快照，避免反复重放同一首歌。
        val nativeCrashDetected =
            com.smartisan.music.playback.CrashRecoveryFlag.detectPreviousNativeCrash(this)
        if (nativeCrashDetected) {
            AppLog.w("Boot", "检测到上次 native 崩溃(ApplicationExitInfo)，已置崩溃恢复标记")
            // [v11·独占死循环根治] native 崩溃（多为 USB 独占 UAC 直写 SIGSEGV）后，
            // 独占开关设置仍是开的，若不在解码前持久化禁用，下次开歌会再次冲进坏通道崩溃。
            // 这里同步落盘熔断闸，PlaybackService 启动时读到即强制共享输出；用户手动
            // 「关→开」开关才解除。同时做一次 USB 复位，把崩溃残留的半占用声卡还给内核。
            com.smartisan.music.playback.UsbExclusiveGuard.disableAfterNativeCrash(this)
            // [v12·native 崩溃后半占用根治] native SIGSEGV 杀进程时 UAC 端点 claim 未释放，
            // 内核侧声卡卡在半占用（权限未授予/麦克风失效/媒体声全无，必须物理插拔才恢复）。
            // 这里在后台做一次独立的强制 USB reset（不依赖进程内 active sink），把声卡干净
            // 还给内核。延迟 800ms 等 UsbManager 状态/权限稳定后再做，避免冷启动竞态。
            appScope.launch {
                kotlinx.coroutines.delay(800)
                com.smartisan.music.playback.UsbDac.resetOrphanedDevice(this@MusicApplication)
            }
        }
        installForegroundTracker()
        appScope.launch {
            runCatching { FormatCache.load(this@MusicApplication) }
                .onFailure { AppLog.w("App", "FormatCache load failed: ${it.message}") }
        }
        AppLog.i("App", "application started")
    }

    private fun installForegroundTracker() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (startedActivities == 0) {
                        noteForegroundChange(true)
                        AppLog.state(
                            "App",
                            op = "切到前台",
                            source = "ActivityLifecycleCallbacks.onActivityStarted",
                            fields = mapOf("fg" to true),
                        )
                    }
                    startedActivities++
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) {
                        noteForegroundChange(false)
                        AppLog.state(
                            "App",
                            op = "切到后台",
                            source = "ActivityLifecycleCallbacks.onActivityStopped",
                            fields = mapOf("fg" to false),
                        )
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /**
     * 全局崩溃记录器（1.2.20）：未捕获异常写入 logs/crash-YYYYMMDD.log，
     * 随 [LogExporter] 一起打包导出。1.2.19 用户反馈「多点几下死机闪退」
     * 但日志里没有任何栈，就是因为崩溃前进程直接死了——这次必须抓到现场。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // [1.4.24·崩溃后不自动重放崩溃曲] 第一时间同步写恢复标记，
                // 下次启动的会话恢复会据此跳过"上次播放快照"，避免加载同曲再崩。
                com.smartisan.music.playback.CrashRecoveryFlag.markCrash(applicationContext)
                val dir = AppLog.logsDirectory()
                if (dir != null) {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    java.io.File(dir, "crash-$stamp.log").writeText(
                        buildString {
                            appendLine("thread=${thread.name} time=${java.util.Date()}")
                            appendLine("version=${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}")
                            appendLine("build=${com.smartisan.music.BuildConfig.BUILD_LABEL}")
                            // [1.4.24·操作审计] 崩溃前 30s 内的最近用户操作，便于从日志反推"我做了什么导致崩溃"。
                            val recent = com.smartisan.music.diagnostics.AppLog.LastUserAction.recent(30_000L)
                            appendLine("lastUserAction=${recent?.let { "traceId=${it.first} source=${it.second}" } ?: "none(30s内无用户操作)"}")
                            appendLine(throwable.stackTraceToString())
                            // 关键：也记录 cause 链，异常根因往往在 cause 里
                            var cause = throwable.cause
                            var depth = 0
                            while (cause != null && depth < 5) {
                                appendLine("Caused by:")
                                appendLine(cause.stackTraceToString())
                                cause = cause.cause
                                depth++
                            }
                        },
                    )
                    AppLog.e("Crash", "未捕获异常 thread=${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
        AppLog.i("Boot", "crash logger installed")
    }
}
