package com.smartisan.music.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.smartisan.music.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [1.4.24·免 ADB/Root 崩溃取证] 把系统记录的「本次进程上一次是怎么死的」完整落盘。
 *
 * 背景：native 崩溃（SIGSEGV/SIGABRT，BASS 解 APE/DSD、UAC 直写时最常见）不会走
 * Java 的 UncaughtExceptionHandler，进程被内核直接杀掉，App 内一行栈都留不下。
 * 之前只能让用户接电脑跑 `adb bugreport` / 抓 tombstone，用户明确说"需要 root 或电脑
 * 我可能做不到"。
 *
 * Android 11（API 30）起，系统通过 [ApplicationExitInfo] 向应用**自己的进程**开放
 * 退出原因与 tombstone 文本（[ApplicationExitInfo.getTraceInputStream]），**不需要 root、
 * 不需要 READ_LOGS、不需要连电脑**。本类只做三件事：
 *  1. 每次启动扫描最近的退出记录；
 *  2. 把崩溃/ANR/低内存的完整 trace 落盘到 logs/exit-*.txt；
 *  3. 用时间水位去重，同一条记录不重复写。
 *
 * 兼容：API < 30 直接跳过（系统没这个能力），日志里写明原因，不假装抓到了。
 */
internal object ExitInfoCapture {

    private const val TAG = "ExitInfo"
    private const val PREFS = "exit_info_capture"
    private const val KEY_LAST_CAPTURED_TS = "last_captured_ts"
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val MAX_RECORDS_PER_LAUNCH = 6

    /** 启动时调用（后台线程）——扫一遍历史退出，把没落过盘的崩溃现场补进 logs/。 */
    fun capturePreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AppLog.i(TAG, "跳过：API ${Build.VERSION.SDK_INT} < 30，系统不提供 ApplicationExitInfo")
            return
        }
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val watermark = prefs.getLong(KEY_LAST_CAPTURED_TS, 0L)
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val records = am.getHistoricalProcessExitReasons(null, 0, 16).orEmpty()
            if (records.isEmpty()) {
                AppLog.i(TAG, "系统未返回任何历史退出记录")
                return
            }
            var written = 0
            var newestTs = watermark
            for (info in records) {
                newestTs = maxOf(newestTs, info.timestamp)
                val interesting = isInteresting(info.reason)
                val fresh = info.timestamp > watermark
                AppLog.i(
                    TAG,
                    "exitInfo: reason=${reasonName(info.reason)}(${info.reason}) status=${info.status} " +
                        "importance=${info.importance} pid=${info.pid} ts=${info.timestamp} " +
                        "proc=${info.processName} desc=${info.description?.take(200)} " +
                        "fresh=$fresh interesting=$interesting",
                )
                if (!fresh || !interesting) continue
                if (written >= MAX_RECORDS_PER_LAUNCH) continue
                if (writeRecord(app, info)) written++
            }
            prefs.edit().putLong(KEY_LAST_CAPTURED_TS, newestTs).commit()
            AppLog.i(TAG, "本轮落盘 $written 条退出记录（watermark=$watermark -> $newestTs）")
        }.onFailure { AppLog.w(TAG, "capturePreviousExits failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    private fun isInteresting(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        -> true
        else -> false
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "R$reason"
    }

    private fun writeRecord(context: Context, info: ApplicationExitInfo): Boolean {
        val dir = AppLog.logsDirectory() ?: return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(info.timestamp))
        val file = File(dir, "exit-$stamp-r${info.reason}.txt")
        if (file.exists()) return false
        val trace = runCatching { readTrace(info) }.getOrNull()
        return runCatching {
            file.writeText(
                buildString {
                    appendLine("==== 进程退出取证（系统 ApplicationExitInfo，无需 root/ADB） ====")
                    appendLine("capturedAt=${Date()}")
                    appendLine("appVersion=${appVersion(context)} build=${BuildConfig.BUILD_LABEL}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) sdk=${Build.VERSION.SDK_INT}")
                    appendLine("processName=${info.processName} pid=${info.pid}")
                    appendLine("exitTime=${Date(info.timestamp)} (${info.timestamp})")
                    appendLine("reason=${reasonName(info.reason)}(${info.reason})")
                    appendLine("status=${info.status} importance=${info.importance}")
                    appendLine("description=${info.description}")
                    appendLine("---- trace (tombstone / ANR trace) ----")
                    if (trace.isNullOrBlank()) {
                        appendLine("(系统未提供 trace：可能已被清理、或该 reason 无 trace)")
                    } else {
                        appendLine(trace)
                    }
                },
            )
            AppLog.w(
                TAG,
                "已落盘崩溃现场 ${file.name} (${file.length()} bytes, trace=${trace?.length ?: 0} chars, " +
                    "reason=${reasonName(info.reason)})",
            )
            true
        }.getOrDefault(false)
    }

    /** 读取 trace 并截断到上限，避免日志包膨胀到几百 MB。 */
    private fun readTrace(info: ApplicationExitInfo): String? {
        val stream = info.traceInputStream ?: return null
        return stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            val out = StringBuilder()
            var total = 0
            while (total < MAX_TRACE_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.append(String(buffer, 0, read, Charsets.UTF_8))
                total += read
            }
            if (total >= MAX_TRACE_BYTES) out.append("\n...(trace 超过 ${MAX_TRACE_BYTES / 1024}KB，已截断)")
            out.toString()
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "-"
}
