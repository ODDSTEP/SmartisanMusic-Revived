@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.smartisan.music.diagnostics.AppLog

/**
 * [护栏 G-30·1.4.6 落地] 只做对口卡顿 3 项、默认开可关、如实标「部分机型可能无效·待实机」；
 * 严禁做成「锁频/绑大核/绕温控」的假承诺，也严禁排在修 G-20/G-21 死锁之前。
 * [1.4.6·借鉴海贝 / 飞傲·独占性能优先] 范围收敛后只保留对口「卡顿」的 3 项叠加保障
 * （17 号页定案）：
 *  - X01 USB Audio 性能模式：音频线程优先级 THREAD_PRIORITY_URGENT_AUDIO + ADPF
 *    PerformanceHintManager 尽力提示（API 31+，用反射避免 minSdk 27 硬引用；不支持/失败
 *    静默降级并如实记日志，不假装提频）。
 *  - X02 保持后台活动：前台服务（PlaybackService 本就是 MediaLibraryService 前台服务）+ 已
 *    持有的 PARTIAL_WAKE_LOCK / KeepAlive + 忽略电池优化**引导**（App 无权静默授权，只发 Intent）。
 *  - X03 播放后释放 USB 带宽：切歌 / rebuild 窗口由 releaseUsb() 关闭 UAC 会话实现，
 *    这里补一条可 grep 的「释放带宽」取证帧，不新造会 0 次触发的自愈函数。
 *
 * 如实边界（16 号页第 5 卡 / 17 号页）：连海贝官方都写「对某些手机可能无任何作用」；
 * 普通 App 做不到锁 CPU 频率 / 绑大核 / 绕温控。本对象**只减 OEM 后台限频的叠加抖动**，
 * 不是 G-20/G-21 丢帧死锁的解药 —— 死锁由 UsbExclusiveAudioSink 门槛自愈 + PlaybackService
 * 重建窗口补对齐根治，本开关排在其后。全部效果标「待实机」。
 *
 * 默认开启（enabled=true）：用户要的是「保证流畅」，不该再要一个得记得去开的开关。
 * 用户可在「设置 → 音频输出 → 独占性能优先」关闭（会略省电，海贝图 2 弹窗同款说明）。
 */
internal object UsbExclusivePerformance {

    private const val PREF = "usb_exclusive_perf"
    private const val KEY_ENABLED = "perf_priority_enabled"

    /** 会话是否已应用性能保障（防重复 start / 重复建 hint session）。 */
    @Volatile private var sessionActive = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        AppLog.i("UsbPerf", "1.4.6(X01/X02) 独占性能优先开关 -> $enabled（默认开，关后省电但可能更易后台抖动）")
        if (!enabled) releaseHintSession()
    }

    /** 独占 UAC 会话建立后调用（UsbExclusiveAudioSink.configure 末尾）。 */
    fun onExclusiveSessionStarted(context: Context) {
        if (sessionActive) return
        sessionActive = true
        if (!isEnabled(context)) {
            AppLog.i("UsbPerf", "1.4.6(X01) 独占会话建立：性能优先已关闭，跳过优先级/性能提示")
            return
        }
        // X01-a 音频线程优先级：configure 运行在 ExoPlayer 播放线程，尽力提优先级。
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            AppLog.i("UsbPerf", "1.4.6(X01) 音频线程优先级 -> THREAD_PRIORITY_URGENT_AUDIO（尽力，部分机型无效）")
        } catch (t: Throwable) {
            AppLog.w("UsbPerf", "1.4.6(X01) 线程优先级提升不可用: ${t.message}")
        }
        // X01-b ADPF 性能提示：API 31+ 用反射创建 hint session，失败如实降级，不假装提频。
        startHintSession()
        // X02 前台服务 / 电池优化白名单状态取证（引导由设置页按钮发起，绝不静默授权）。
        val ignoring = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        AppLog.i(
            "UsbPerf",
            "1.4.6(X02) 后台保活：前台服务=MediaLibraryService(已在) WakeLock/KeepAlive=已在 " +
                "忽略电池优化=$ignoring（未授权可在设置→音频输出点引导）",
        )
    }

    /** 独占 UAC 会话释放时调用（UsbExclusiveAudioSink.releaseUsb）。 */
    fun onExclusiveSessionStopped(context: Context) {
        if (!sessionActive) return
        sessionActive = false
        releaseHintSession()
        // X03 播放后 / 切歌 / rebuild 释放 USB 带宽：UAC 会话已由 releaseUsb 关闭，这里留取证帧。
        AppLog.i("UsbPerf", "1.4.6(X03) 独占会话释放 -> USB 带宽已释放（切歌/rebuild 窗口，防声卡掉线）")
    }

    /** 供设置页「忽略电池优化」引导按钮发起（App 不能静默授权）。 */
    fun buildBatteryOptimizationIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        }.getOrNull()
    }

    // ---------------- ADPF PerformanceHintManager（反射，API 31+，尽力） ----------------

    @Volatile private var hintSession: Any? = null
    @Volatile private var hintManager: Any? = null
    @Volatile private var hintUnavailableLogged = false

    private fun startHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (!hintUnavailableLogged) {
                hintUnavailableLogged = true
                AppLog.i("UsbPerf", "1.4.6(X01) ADPF：系统 < API31，无 PerformanceHintManager，跳过（不假装提频）")
            }
            return
        }
        try {
            // 反射：PerformanceHintManager 从 Context.getSystemService 取；
            // createHintSession(int[] tids, long initialTargetWorkDurationNanos)。
            // 用当前播放线程 tid 建会话，reportActualWorkDuration 每次写入后尽力上报。
            val ctx = appContextRef ?: return
            // 用字符串取服务，避免引用 API31 才有的 Context.PERFORMANCE_HINT_SERVICE 常量
            // 让 minSdk 27 也不触发 NewApi 硬引用；反射全程 try/catch。
            val manager = ctx.getSystemService("performance_hint")
            if (manager == null) {
                AppLog.i("UsbPerf", "1.4.6(X01) ADPF：本设备无 performance_hint 服务，跳过（海贝也注明部分机型无效）")
                return
            }
            val tid = Process.myTid()
            val tids = intArrayOf(tid)
            // 官方签名：createHintSession(int[] tids, long initialTargetWorkDurationNanos)
            val create = manager.javaClass.getMethod(
                "createHintSession", IntArray::class.java, java.lang.Long.TYPE,
            )
            val session = create.invoke(manager, tids, 16_666_666L)
            if (session != null) {
                hintManager = manager
                hintSession = session
                AppLog.i("UsbPerf", "1.4.6(X01) ADPF hint session 已创建 tid=$tid（尽力提示，不保证提频/绑大核）")
            } else {
                AppLog.i("UsbPerf", "1.4.6(X01) ADPF createHintSession 返回 null，跳过")
            }
        } catch (t: Throwable) {
            AppLog.w("UsbPerf", "1.4.6(X01) ADPF 不可用（反射失败，如实降级）: ${t.message}")
        }
    }

    /**
     * 每成功写一帧 PCM 后尽力上报实际工作时长（真实触发、非 0 次空壳）。
     * 用传入的纳秒时长，reportActualWorkDuration(long) 反射调用，失败静默。
     */
    fun reportWorkDuration(workNanos: Long) {
        val session = hintSession ?: return
        try {
            // 官方签名：reportActualWorkDuration(long actualWorkDurationNanos)
            val m = session.javaClass.getMethod("reportActualWorkDuration", java.lang.Long.TYPE)
            m.invoke(session, workNanos)
        } catch (t: Throwable) {
            // 不刷屏：只关掉会话避免反复失败
            hintSession = null
        }
    }

    private fun releaseHintSession() {
        val session = hintSession ?: return
        try {
            session.javaClass.getMethod("close").invoke(session)
        } catch (_: Throwable) {
            // Session implements AutoCloseable on newer APIs; older ones have no close -> ignore
        }
        hintSession = null
        hintManager = null
    }

    /** 由 sink 在会话建立时注入 applicationContext（避免持有 Activity）。 */
    @Volatile private var appContextRef: Context? = null
    fun bindAppContext(context: Context) {
        appContextRef = context.applicationContext
    }
}
