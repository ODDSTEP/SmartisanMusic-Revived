package com.smartisan.music.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.smartisan.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出：把 logs 目录、崩溃现场、环境快照、尽力抓取的 logcat 一起打包成 zip，
 * 经 FileProvider 分享出去。**全程在手机上完成，不需要 ADB、不需要 root、不需要电脑。**
 *
 * 1.4.24 起包内固定包含：
 *  - app-YYYYMMDD.log     应用内全量日志（含 State 帧 / 调用来源 / UAC 全链路）
 *  - crash-*.log          Java 未捕获异常（含 cause 链 + 崩溃前 30s 用户操作）
 *  - exit-*.txt           native 崩溃 / ANR / 低内存现场（ApplicationExitInfo + tombstone trace）
 *  - env-*.txt            导出瞬间的设备、音频设备、USB、音量、存储快照
 *  - logcat-*.txt         本进程 logcat（尽力而为；被 ROM 限制时写明原因，不静默缺失）
 *  - manifest.json        版本、构建标识、导出时间、文件清单与大小
 */
object LogExporter {

    /**
     * [1.4.25·R71] 导出互斥锁：正在导出时重复点击直接忽略。
     * 原来连点会并发跑多份「环境快照 + logcat 4s 等待 + zip 打包」，全部堆在主线程 → ANR。
     */
    private val exporting = AtomicBoolean(false)

    /** 当前是否正在导出（UI 可据此禁用按钮/提示"导出中"）。 */
    val isExporting: Boolean get() = exporting.get()

    /**
     * [1.4.25·R71] 打包并弹出系统分享面板，返回是否成功启动。
     *
     * **suspend**：诊断快照 + logcat 捕获 + zip 打包全部在 [Dispatchers.IO] 执行，
     * 只有最后拉起系统分享面板回到主线程。UI 侧必须在协程里调用：
     * ```
     * scope.launch { LogExporter.exportAndShare(context) }
     * ```
     * 返回 true=请求已受理（已拉起分享面板，或已有导出在进行、本次点击被忽略——
     * 这两种情况都不该给用户弹"导出失败"）；false=真正失败。
     */
    suspend fun exportAndShare(context: Context): Boolean {
        if (!exporting.compareAndSet(false, true)) {
            AppLog.w("LogExporter", "已有导出在进行，忽略本次重复点击")
            return true
        }
        try {
            // 全部阻塞 IO 离开调用方线程（主线程）。
            val zip = withContext(Dispatchers.IO) {
                runCatching { export(context) }.getOrNull()
            } ?: return false
            val uri: Uri = withContext(Dispatchers.IO) {
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.logfileprovider", zip)
                }.getOrNull()
            } ?: return false
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 弹系统分享面板必须回主线程。
            return withContext(Dispatchers.Main) {
                runCatching {
                    context.startActivity(Intent.createChooser(intent, zip.name))
                    true
                }.getOrDefault(false)
            }
        } finally {
            exporting.set(false)
        }
    }

    /**
     * 仅打包，返回 zip 文件；失败返回 null。
     *
     * [1.4.25·R71] 改为 suspend 且整体跑在 [Dispatchers.IO]：环境快照写盘、logcat 捕获
     * （最长 4 秒等待）、zip 压缩都是阻塞调用，任何时候都不允许在调用方线程同步执行。
     */
    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        val dir = AppLog.logsDirectory() ?: return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        // 导出前先补齐"此刻"的现场：环境快照 + 尽力抓 logcat。
        // 顺序很重要：环境快照要写在打包之前，才能进 zip。
        runCatching { DiagnosticsSnapshot.write(context) }
        runCatching { captureLogcat(context, stamp) }

        val logs = dir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: return@withContext null
        if (logs.isEmpty()) return@withContext null
        val out = File(context.cacheDir, "melodify-logs-$stamp.zip")
        val manifest = buildManifest(context, logs, stamp)
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            logs.forEach { log ->
                zos.putNextEntry(ZipEntry(log.name))
                log.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        AppLog.i(
            "LogExporter",
            "exported ${logs.size} file(s) + manifest -> ${out.name} (${out.length()} bytes)",
        )
        out
    }

    /**
     * 尽力抓本进程 logcat。
     *
     * Android 4.1+ 普通应用执行 `logcat` 只能看到自己 uid 的日志，**不需要 root**；
     * 少数 ROM 直接禁用 logcat 或 SELinux 拒绝，此时把失败原因写进文件，避免"以为抓了其实没抓"。
     *
     * [1.4.25·R71] suspend + [Dispatchers.IO]：4 秒 waitFor 与输出读取都是阻塞调用，
     * 绝不可能落在主线程（ANR "Input dispatching timed out Waited 5001ms" 的直接来源）。
     * 同时改为「边读边等」：logcat -t 5000 的输出可能超过管道缓冲（约 64KB），
     * 只 waitFor 不读会让子进程写满管道后阻塞，waitFor 必然超时且一个字节都拿不回来。
     */
    private suspend fun captureLogcat(context: Context, stamp: String) = withContext(Dispatchers.IO) {
        val dir = AppLog.logsDirectory() ?: return@withContext
        val target = File(dir, "logcat-$stamp.txt")
        val result = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "5000", "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            // 后台线程持续抽干 stdout，主流程只负责有限等待。
            val sink = ByteArrayOutputStream(64 * 1024)
            val reader = Thread {
                runCatching { process.inputStream.use { it.copyTo(sink) } }
            }.apply {
                isDaemon = true
                name = "logcat-reader"
                start()
            }
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(1_000)
            val text = sink.toString(Charsets.UTF_8.name())
            if (text.isBlank()) {
                return@runCatching if (finished) {
                    "EMPTY: 系统返回空（本机型可能禁止应用读取 logcat）"
                } else {
                    "TIMEOUT: logcat 4 秒内未返回，已放弃（不影响其它日志）"
                }
            }
            // 只保留与本应用相关的行，避免把整机日志（含其他 App 隐私）带出去。
            val pkg = context.packageName
            val pid = android.os.Process.myPid().toString()
            val filtered = text.lineSequence()
                .filter { line ->
                    line.contains(pkg) || line.contains(" $pid ") ||
                        line.contains("Usb:") || line.contains("AudioOut:") ||
                        line.contains("Popcorn:") || line.contains("ApeTranscoder:") ||
                        line.contains("DEBUG") || line.contains("libc")
                }
                .take(4000)
                .joinToString("\n")
            if (filtered.isBlank()) {
                return@runCatching "FILTERED_EMPTY: logcat 有输出但没有本应用相关行"
            }
            val partialNote =
                if (finished) "" else "（注意：4 秒未返回，已强制结束，只保留已读到的部分）\n"
            target.writeText("==== logcat（本应用相关，最多 4000 行）====\n$partialNote$filtered\n")
            "OK: ${filtered.length} chars"
        }
        AppLog.i("LogExporter", "logcat capture -> ${result.getOrElse { "FAIL: ${it.javaClass.simpleName} ${it.message}" }}")
        if (result.isFailure || result.getOrNull()?.startsWith("OK") != true) {
            runCatching {
                target.writeText(
                    "==== logcat 不可用 ====\n" +
                        "原因：${result.getOrElse { it.message }}\n" +
                        "说明：应用内日志（app-*.log）不受影响，已完整打包；\n" +
                        "     logcat 仅作补充，缺失时不影响排障。\n" +
                        "设备：${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT}\n",
                )
            }
        }
    }

    private fun buildManifest(context: Context, logs: List<File>, stamp: String): String {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val files = logs.joinToString(",\n") { log ->
            """    {"name":"${log.name}","bytes":${log.length()}}"""
        }
        return buildString {
            appendLine("{")
            appendLine("""  "exportedAt": "${Date()}", """.trimEnd())
            appendLine("""  "stamp": "$stamp",""")
            appendLine("""  "appVersion": "${pkg?.versionName}",""")
            appendLine("""  "versionCode": ${pkg?.longVersionCode},""")
            appendLine("""  "buildLabel": "${BuildConfig.BUILD_LABEL}",""")
            appendLine("""  "engine": "${BuildConfig.PLAYBACK_ENGINE}",""")
            appendLine("""  "device": "${Build.MANUFACTURER} ${Build.MODEL}",""")
            appendLine("""  "sdk": ${Build.VERSION.SDK_INT},""")
            appendLine("""  "note": "本包含 app 日志 / Java 崩溃 / native 退出取证 / 环境快照 / logcat(尽力)。无需 ADB 或 root。",""")
            appendLine("""  "files": [""")
            appendLine(files)
            appendLine("  ]")
            appendLine("}")
        }
    }
}
