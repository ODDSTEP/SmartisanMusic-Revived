package com.smartisan.music.playback

import android.content.Context
import android.app.ActivityManager
import android.os.Build
import com.smartisan.music.diagnostics.AppLog

/**
 * [1.4.24·崩溃后不再自动加载崩溃曲] 崩溃恢复标记。
 *
 * 用户痛点：切 STREAM 引擎播放高解析曲 native 崩溃后，App 重启仍按"上次播放快照"
 * 自动加载（哪怕暂停态，prepare 也会触发解码）同一首歌 → 再次崩溃 → 死循环，
 * 用户甚至来不及进设置把引擎换回来。
 *
 * 方案：崩溃 handler 用 SharedPreferences.commit() **同步**写标记（DataStore 异步不可靠，
 * 崩溃瞬间来不及落盘）；下次启动恢复前检测标记，命中则跳过恢复并清空快照，随后清标记。
 * 这样崩溃后重开是干净的空队列，用户可从容改设置。
 */
internal object CrashRecoveryFlag {
    private const val TAG = "CrashRecovery"
    private const val PREFS = "crash_recovery_flag"
    private const val KEY_PENDING = "crash_pending"
    private const val KEY_LAST_START_MS = "last_start_ms"

    /** 崩溃瞬间同步落标记，必须 commit（不能用 apply，进程马上就死）。 */
    fun markCrash(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, true)
                .commit()
        }
    }

    /** 读取并清除标记（原子性读取即清，避免重复消费）。 */
    fun consumeCrashPending(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_PENDING, false)
        if (pending) {
            runCatching { prefs.edit().remove(KEY_PENDING).commit() }
        }
        return pending
    }

    /**
     * [1.4.25·native 崩溃兜底] 检测"上次进程退出"是否为崩溃，覆盖 Java UncaughtExceptionHandler
     * 抓不到的 native 崩溃（SIGSEGV/SIGABRT —— BASS 解 APE/DSD 时直接被内核信号杀死，
     * Java 层的 handler 根本不会回调，导致 markCrash 从未执行，崩溃曲被反复重放）。
     *
     * 用 API 30+ 的 ApplicationExitInfo 读历史退出原因；用"上次启动时间"作为水位，
     * 只认发生在上次启动之后的崩溃，避免陈年历史崩溃记录反复触发清队列。
     * 命中则补写 [markCrash]，随后刷新本次启动时间供下次判定。
     *
     * @return 是否检测到上次是崩溃退出。
     */
    fun detectPreviousNativeCrash(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        var detected = false
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastStartMs = prefs.getLong(KEY_LAST_START_MS, 0L)
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val reasons = am?.getHistoricalProcessExitReasons(null, 0, 8).orEmpty()
            for (info in reasons) {
                val isCrash =
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        info.reason == android.app.ApplicationExitInfo.REASON_CRASH ||
                        info.reason == android.app.ApplicationExitInfo.REASON_SIGNALED
                // [v19·USB日志补全·D崩溃现场] 记录每次历史退出的全字段，定位 native SIGSEGV 的
                // reason/status/importance/timestamp/description 与进程名（联网核对：ApplicationExitInfo
                // 是 native 崩溃唯一可取证来源，Java handler 抓不到）。
                runCatching {
                    AppLog.i(
                        TAG,
                        "exitInfo: reason=${info.reason} status=${info.status} importance=${info.importance} " +
                            "pid=${info.pid} ts=${info.timestamp} proc=${info.processName} " +
                            "desc=${info.description?.take(200)} isCrash=$isCrash afterLastStart=${info.timestamp > lastStartMs}",
                    )
                }
                if (isCrash && info.timestamp > lastStartMs) {
                    detected = true
                    break
                }
            }
            if (detected) markCrash(context)
            // 无论是否崩溃都刷新水位；用 commit 保证下次启动读得到。
            prefs.edit().putLong(KEY_LAST_START_MS, System.currentTimeMillis()).commit()
        }
        return detected
    }
}
