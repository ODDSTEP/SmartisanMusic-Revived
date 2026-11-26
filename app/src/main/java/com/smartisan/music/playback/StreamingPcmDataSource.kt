@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.smartisan.music.diagnostics.AppLog
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * C 测试版专用：ffmpeg 流式 PCM 管道 [DataSource]（只编进 stream flavor）。
 *
 * 1.4.1 硬修（针对 1.4.0 实机三症状——冷门格式无声 / 假时长 / 拖进度条循环）：
 *  - 输入改「可 seek 原文件」：优先 MediaStore DATA 真实路径，其次 openFileDescriptor
 *    的 /proc/self/fd/N，**不再走 pipe:0**（DFF/APE 尾目录可 seek 后能正常解码）。
 *  - 输出仍 `-f wav pipe:1`，但用 ffmpeg 探测到的真实时长**预构造正确 WAV 头**，
 *    open() 返回真实总字节长度，替换 ffmpeg 写进 pipe 的 0xFFFFFFFF「未知长度」哨兵，
 *    进度条不再按假时长 6.76h 走。
 *  - seek：DataSpec.position 换算成目标秒数 → `-ss` 重开管道，从头读才发 WAV 头。
 *  - 首帧判定：close() 只在「连一帧 PCM 都没读出」时判失败，杜绝 `code=0` 假成功。
 *    seek/切歌时 Media3 会提前关闭 DataSource，已读字节必然小于整曲期望，属正常的
 *    流式截断，不再误报「字节校验失败/疑似空壳」（1.4.1 实机日志误导已修）。
 *
 * 逐阶段日志（tag=StreamPcm）：路径解析 / 时长探测 / 管道启动 / WAV 头注入 / stdout
 * 累计 / seek 重开 / 字节校验 / 进程退出码。失败抛明确 IOException，不静默吞。
 *
 * 如实边界：沙箱无真机，seek 竞态与 WavExtractor 对注入头的解析无法实测；本类只保证
 * 真实调用 + 可判定日志 + 失败明确，不宣称「已验证能播」。
 */
internal class StreamingPcmDataSource(
    private val context: Context,
    private val sourceUri: Uri,
) : DataSource {

    private val listeners = CopyOnWriteArrayList<TransferListener>()
    private val closed = AtomicBoolean(false)
    private val stdoutBytes = AtomicLong(0L)
    private val firstFrameLogged = AtomicBoolean(false)

    private var process: Process? = null
    private var input: InputStream? = null
    private var pfdHolder: android.os.ParcelFileDescriptor? = null

    private var totalLength: Long = C.LENGTH_UNSET.toLong()

    /** [R162] 同一文件的时长只探测一次：每次 open() 都同步跑一遍 ffmpeg 会多花 2~4 秒
     *  并多占一个 pipe，seek 时尤其明显（日志里 DS open→close 同毫秒 ×5 就是这么来的）。 */
    private companion object {
        private val durationCache = object : LinkedHashMap<String, Long>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 8
        }
    }
    private var wavHeader: ByteArray = ByteArray(0)
    private var headerRemaining: Int = 0
    private var headerOffset: Int = 0
    private var ffmpegHeaderSkipped: Boolean = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val bin = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpegbin.so")
        if (!bin.exists() || !bin.canExecute()) {
            throw java.io.IOException("流式管道不可用：ffmpeg 二进制缺失或不可执行 (${bin.absolutePath})")
        }
        val path = resolvePath(sourceUri)
            ?: throw java.io.IOException("C包不支持该文件：无法反查可 seek 的本地路径（云盘/虚拟文件），不落盘、不假装能播 uri=${sourceUri.toString().takeLast(48)}")
        AppLog.i("StreamPcm", "路径解析OK path=${path.takeLast(48)} uri=${sourceUri.toString().takeLast(48)}")

        val cachedMs = synchronized(durationCache) { durationCache[path] }
        val durationMs = cachedMs ?: probeDuration(bin, path)
        if (durationMs <= 0L) {
            throw java.io.IOException("C包无法探测时长，可能是不支持的文件 uri=${sourceUri.toString().takeLast(48)}")
        }
        if (cachedMs == null) synchronized(durationCache) { durationCache[path] = durationMs }
        val bytesPerSec = 44100L * 2 * 2 // 44.1k 16bit stereo
        val dataLength = durationMs * bytesPerSec / 1000
        totalLength = 44L + dataLength
        wavHeader = buildWavHeader(dataLength)
        AppLog.i(
            "StreamPcm",
            "时长探测OK durationMs=$durationMs dataLength=$dataLength totalLength=$totalLength",
        )

        val fromStart = dataSpec.position <= 0L
        headerRemaining = if (fromStart) wavHeader.size else 0
        headerOffset = 0
        ffmpegHeaderSkipped = false
        stdoutBytes.set(0L)

        // [R165] 旧实现是整数除法（bytesPerSec=176400），最多偏 1 秒；改成小数秒，
        // ffmpeg 的 -ss 本来就接受 103.969 这种写法。
        val seekSeconds: Double = if (dataSpec.position > 44L) {
            (dataSpec.position - 44L).toDouble() / bytesPerSec.toDouble()
        } else {
            0.0
        }
        // [R162·用户最痛的一条] 从 -ss 起转后，pipe 里只剩 (整曲 - 已跳过) 的数据；
        // 若仍按整曲长度返回，EXO 会读到 pipe 的 -1 并判「流提前结束」→ EOFException
        // → Source error 2000 → 输出重建 → 独占会话被释放。
        // seek 后一律返回「未知长度」，让 EXO 以 pipe 的 EOF 为准。
        if (seekSeconds > 0.0) totalLength = C.LENGTH_UNSET.toLong()
        val cmd = buildCommand(bin, path, seekSeconds)
        val proc = try {
            ProcessBuilder(*cmd).start()
        } catch (e: Exception) {
            throw java.io.IOException("流式管道启动失败: ${e.message}", e)
        }
        process = proc
        AppLog.i(
            "StreamPcm",
            "管道已启动 seekSec=$seekSeconds totalLength=$totalLength " +
                "cmd=ffmpeg -ss $seekSeconds -i <path> -f wav pipe:1",
        )

        // 后台把 ffmpeg stderr 抽干，避免错误日志填满管道缓冲区卡死进程。
        Thread({
            runCatching { proc.errorStream.use { it.readBytes() } }
        }, "stream-pcm-stderr").apply { isDaemon = true }.start()

        input = BufferedInputStream(proc.inputStream, 64 * 1024)
        return totalLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        // 1) 从头读时先发预构造的正确 WAV 头。
        if (headerRemaining > 0) {
            val n = minOf(headerRemaining, length)
            System.arraycopy(wavHeader, headerOffset, buffer, offset, n)
            headerOffset += n
            headerRemaining -= n
            return n
        }
        val ins = input ?: throw java.io.IOException("StreamingPcmDataSource 未 open 就 read")
        // 2) 跳过 ffmpeg 自己写进 pipe 的 WAV 头（含 0xFFFFFFFF 哨兵，弃用）。
        if (!ffmpegHeaderSkipped) {
            ffmpegHeaderSkipped = true
            var skipRemaining = 44
            val tmp = ByteArray(64)
            while (skipRemaining > 0) {
                val skipped = ins.read(tmp, 0, minOf(64, skipRemaining))
                if (skipped < 0) return C.RESULT_END_OF_INPUT
                skipRemaining -= skipped
            }
        }
        val n = try {
            ins.read(buffer, offset, length)
        } catch (e: Exception) {
            throw java.io.IOException("流式管道读取失败: ${e.message}", e)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        stdoutBytes.addAndGet(n.toLong())
        if (firstFrameLogged.compareAndSet(false, true)) {
            AppLog.i("StreamPcm", "stdout首帧已读出 bytes=$n uri=${sourceUri.toString().takeLast(48)}")
        }
        return n
    }

    override fun getUri(): Uri? = sourceUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val read = stdoutBytes.get()
        val expected = (totalLength - 44L).coerceAtLeast(0L)
        // [护栏 G-08·台账登记] 判失败的唯一依据：连一帧 PCM 都没读出来（真正空壳/无法解码）。
        // 防「提前 seek/切歌关闭管道 → 误报字节校验失败/疑似空壳」（1.4.1 实机误导已修）。
        // 判失败的唯一依据：连一帧 PCM 都没读出来（真正空壳 / 无法解码）。
        // seek/切歌时 Media3 提前 close 本 DataSource，管道被截断是正常流式行为，
        // 已读字节小于整曲期望值绝不代表失败——1.4.1 曾据此误报「字节校验失败/
        // 疑似空壳」，实际 AudioOut 已在正常出声、C 版实机验证成功。
        val noFrameAtAll = read <= 0L
        if (noFrameAtAll) {
            AppLog.w(
                "StreamPcm",
                "疑似空壳/解码失败：管道关闭时未读出任何 PCM 首帧 stdout=${read}B 期望数据=$expected",
            )
        } else {
            AppLog.i(
                "StreamPcm",
                "管道关闭（正常结束或 seek/切歌提前关闭，均属正常）: 已输出PCM=${read}B 整曲期望数据=$expected",
            )
        }
        runCatching { input?.close() }
        runCatching { pfdHolder?.close() }
        pfdHolder = null
        val proc = process
        process = null
        if (proc != null) {
            Thread({
                try {
                    val code = proc.waitFor()
                    AppLog.i("StreamPcm", "ffmpeg进程退出 code=$code 首帧校验=${if (noFrameAtAll) "无首帧(判失败)" else "已出声(正常)"}")
                } catch (e: InterruptedException) {
                    proc.destroyForcibly()
                    AppLog.w("StreamPcm", "ffmpeg进程回收被中断，已强制结束")
                }
            }, "stream-pcm-reap").apply { isDaemon = true }.start()
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun resolvePath(uri: Uri): String? {
        // 1) MediaStore DATA 真实路径（覆盖绝大多数本地音乐）。
        runCatching {
            val path = context.contentResolver
                .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists() && file.canRead() && file.length() > 0L) return path
            }
        }
        // 2) openFileDescriptor → /proc/self/fd/N（兜底，持有 fd 直到 close）。
        return runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            pfdHolder = pfd
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val file = File(fdPath)
            if (file.exists() && file.canRead()) fdPath else null
        }.getOrNull()
    }

    private fun probeDuration(bin: File, path: String): Long {
        val proc = runCatching {
            ProcessBuilder(bin.absolutePath, "-hide_banner", "-i", path)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return 0L }
        val text = runCatching {
            val bytes = proc.inputStream.use { it.readBytes() }
            proc.waitFor()
            bytes.decodeToString()
        }.getOrElse { "" }
        runCatching { proc.destroyForcibly() }
        val m = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(text) ?: return 0L
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toDouble()
        return (h * 3600_000L + min * 60_000L + (sec * 1000).toLong())
    }

    private fun buildCommand(bin: File, path: String, seekSeconds: Double): Array<String> {
        val base = mutableListOf(
            bin.absolutePath,
            "-hide_banner", "-loglevel", "error",
        )
        if (seekSeconds > 0.0) {
            base.add("-ss")
            base.add(String.format(java.util.Locale.US, "%.3f", seekSeconds))
        }
        base.add("-i")
        base.add(path)
        base.addAll(
            listOf(
                "-map", "0:a:0",
                "-c:a", "pcm_s16le", "-ar", "44100", "-ac", "2",
                "-f", "wav", "pipe:1",
            ),
        )
        return base.toTypedArray()
    }

    private fun buildWavHeader(dataLength: Long): ByteArray {
        val sampleRate = 44100
        val channels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffSize = 36L + dataLength
        val out = java.io.ByteArrayOutputStream(44)
        val w = { s: String -> out.write(s.toByteArray(Charsets.US_ASCII)) }
        val w32 = { v: Long -> out.write(((v and 0xFF).toInt())); out.write(((v shr 8) and 0xFF).toInt()); out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt()) }
        val w16 = { v: Int -> out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        w("RIFF"); w32(riffSize); w("WAVE")
        w("fmt "); w32(16L); w16(1); w16(channels)
        w32(sampleRate.toLong()); w32(byteRate.toLong()); w16(blockAlign); w16(bitsPerSample)
        w("data"); w32(dataLength)
        return out.toByteArray()
    }
}
