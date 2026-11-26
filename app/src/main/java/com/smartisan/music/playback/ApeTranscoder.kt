@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.smartisan.music.diagnostics.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import java.util.Locale

/**
 * 「ExoPlayer 播不了的音频格式」统一转码网关（1.2.14 起从 APE 扩展到全格式）。
 *
 * Android / ExoPlayer / Media3 原生不支持 APE、DTS、DSD、WavPack、WMA、TAK 等一大票格式，
 * 这里采用**无损转码**方案：把文件用内置的 ffmpeg（libffmpegbin.so，含 470+ 解码器）转码为
 * FLAC 缓存文件，再交给本就稳定可用的 FLAC 播放链路（含 USB 独占 bit-perfect）。
 * - 无损源（APE/DTS-CD/Tak/WavPack…）→ FLAC：逐样本无损，音质不降级；
 * - 有损源（WMA/AC-3/DTS 有损核心…）→ FLAC：封存解码后的 PCM，不再引入二次损失；
 * - 结果按「源标识 + 文件大小」缓存，二次播放即时、不重复转码。
 *
 * 1.2.16 架构调整（治「点播放死机闪退 / 存储暴涨 1.41GB」）：
 * - **拦截点下沉到 [DataSource]**：旧实现挂在 `MediaSource.Factory.createMediaSource`，
 *   该方法在**应用线程**同步执行，播放队列装载时会逐首转码、把主线程堵死 40s+；
 *   现在只在 `DataSource.open()`（ExoPlayer 加载线程）里转码，队列装载零阻塞，
 *   且 ExoPlayer 只为「正在播放 / 结束前 30s 预载」的曲目建加载期 → 天然只转当前曲目（方案 B）。
 * - **缓存迁到 `cacheDir`**（方案 A）：系统可随时回收 + 总容量 1GB 硬上限 + LRU 自动淘汰，
 *   不再无界膨胀 filesDir（1.41GB 元凶）；旧 filesDir 残留启动时自动清理。
 * - **ffmpeg `-compression_level 5`**（方案 C）：FLAC 压缩等级 8→5，转码速度显著提升、体积略增。
 * - 缓存统计/清理接口见 [cacheSizeBytes] / [clearCache]（设置页「转码缓存」，方案 D）。
 *
 * 接入点见 [TranscodingDataSourceFactory]：对上层完全透明，不改动既有音频输出管线。
 */
internal object ApeTranscoder {

    private const val TAG = "ApeTranscoder"
    private const val FF_BIN_NAME = "ffmpeg"
    private const val NATIVE_LIB = "libffmpegbin.so"

    /** 转码缓存目录（cacheDir 下，系统空间紧张时可回收，配合 1GB 上限双重保险）。 */
    private const val CACHE_DIR = "ape_cache"

    /** content:// 落地临时目录（cacheDir 下）。 */
    private const val TMP_DIR = "ape_tmp"

    /**
     * 转码缓存总容量硬上限：512MB。
     *
     * 1.2.28：原来是 1GB，日志实测用户设备可用空间被缓存吃到只剩 434MB，于是
     * `startOrJoinJob` 判「空间不足」把整个缓存目录清空——连**正在播放的那个文件**一起删了，
     * 直接造成 `IO_READ_POSITION_OUT_OF_RANGE` → 自动跳歌 → 下一首 DFF 又要转 → 连锁跳过
     * 所有 DFF，直到第一首 FLAC。容量压到 512MB 后不会再触发这条路径。
     */
    private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    private const val TRIM_TARGET_BYTES = MAX_CACHE_BYTES * 8 / 10

    /** 无论缓存多大，都要给系统留这么多可用空间（转码是写盘大户）。 */
    private const val MIN_FREE_BYTES = 400L * 1024L * 1024L

    /** 最近 60 秒被 touch 过的缓存视为「正在用 / 刚转好」，淘汰时必须跳过。 */
    private const val RECENT_CACHE_PROTECT_MS = 60_000L

    private const val TRANSCODE_TIMEOUT_SECONDS = 900L

    /** ffmpeg 因「用户切歌」被杀掉时的退出码（区别于真实失败，日志与收尾要分开处理）。 */
    private const val CancelledExitCode = -3

    /**
     * DSD(DSF/DFF) 转 PCM 的目标采样率（1.2.20 起 88.2kHz/24bit）：
     * DSD64 的标准 PCM 化目标是 176.4k，但 FLAC 体积高达源文件 1.3 倍（实测 261MB/首，
     * 用户反馈「播几首歌缓存就 1GB」）；88.2k/24bit 仍远超 CD 音质，FLAC 体积反而
     * 只有源文件一半左右（≈110~130MB/首），转码耗时也更短。
     */
    private const val DSD_PCM_SAMPLE_RATE_HZ = 88_200

    /**
     * 转码前预留的安全空间。
     * 1.2.28：原为 256MB，配合严重高估的产物体积（1.4 倍源文件）动不动就判「空间不足」。
     * 实测 DFF→88.2k/24bit FLAC 只有源文件的 ~0.5 倍，64MB 余量足够。
     */
    private const val SPACE_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L

    /** APE 文件头魔数 "MAC "（0x4D 0x41 0x43 0x20）。 */
    private val APE_MAGIC = byteArrayOf(0x4D, 0x41, 0x43, 0x20)

    /** 供 ffmpeg 直读的探测头大小（WAV 需要走到 data 块判断是否内嵌 DTS）。 */
    private const val PEEK_SIZE = 64 * 1024

    /**
     * 需要转码的扩展名清单（file:// 或带文件名的 content uri 走快速路径）。
     * 覆盖：APE / DTS / DSD(DSF,DFF) / TTA / WavPack / TAK / Shorten / WMA(ASF) /
     * AC-3 / E-AC-3 / MLP,TrueHD / Musepack / VQF / AIFF,AU,CAF 等系统播放链不支持的容器。
     */
    private val TranscodeExtensions = setOf(
        "ape", "apl", "mac",
        "dts", "dtshd", "dtshdma",
        "dsf", "dff", "dsd",
        "tta",
        "wv", "wvc",
        "tak", "shn",
        "wma", "asf",
        "ac3", "eac3", "ddp", "dd+", "a52",
        "mlp", "thd", "truehd",
        "mpc", "mp+", "mpp",
        "vqf",
        "aiff", "aif", "aifc", "au", "caf", "w64", "voc", "wsd", "msf",
    )

    /** ExoPlayer/平台原生支持的常见格式扩展名：无需探测，直接走原链路。
     *  注意：wav 故意不在清单里——DTS-CD 镜像就是 .wav 外壳，必须深扫文件头。
     *  1.4.11 精准修：m4a/m4b/mp4/m4r 移出——这些扩展名可能是 AAC（系统可直读）
     *  也可能是 ALAC 无损（本机无 ALAC 解码器，必须转 FLAC）。不能靠扩展名信任，
     *  必须走 needsTranscodeByMagic 深扫 stsd 采样条目区分。AAC 仍保持系统直读零缓存。 */
    private val NativeSupportedExtensions = setOf(
        "mp3", "flac", "ogg", "oga", "opus",
        "3gp", "amr", "mid", "midi", "smf", "xmf", "rtttl", "imy", "mxmf",
    )

    @Volatile
    private var ffmpegPath: String? = null
    private val initLock = Any()

    /** 已探明容器类型的 Uri 缓存，避免重复读取文件头。 */
    private val transcodeGuessCache = ConcurrentHashMap<String, Boolean>()

    /** 同一缓存键的转码互斥锁（当前曲 + 预载曲可能在两个加载线程同时转）。 */
    private val transcodeLocks = ConcurrentHashMap<String, Any>()

    /** filesDir 旧残留（1.41GB 元凶）只清一次。 */
    private val legacyCleaned = AtomicBoolean(false)

    /**
     * 纯格式探测：判断 [uri] 是否为「冷门容器」（ExoPlayer 自带 extractor 播不了、
     * 会当噪声或直接 3003 的 DSD/DSF/DFF/APE/DTS-in-WAV/TTA/WavPack/TAK/WMA…）。
     * 优先级：路径扩展名 → 读取文件头魔数（含 WAV 内嵌 DTS/非 PCM 的深扫）。
     * 1.3.8：从 needsTranscode 抽出，让「格式判定」与「引擎策略」解耦。
     */
    internal fun detectColdFormat(context: Context, uri: Uri): Boolean {
        val seg = uri.lastPathSegment ?: ""
        if (seg.contains('.')) {
            val ext = seg.substringAfterLast('.', "").lowercase()
            if (ext in TranscodeExtensions) return true
            // 明确属于系统直读的常见格式，直接放行（省一次文件头读取）
            if (ext in NativeSupportedExtensions) return false
        }
        val cached = transcodeGuessCache[uri.toString()]
        if (cached != null) return cached
        val result = peekNeedsTranscode(context, uri)
        // 探测异常（如无「所有文件访问」权限时读 Files 表 uri 会被拒）不缓存，
        // 否则授权后仍永远回退原始 uri、格式永远播不了
        if (result != null) {
            transcodeGuessCache[uri.toString()] = result
        }
        return result ?: false
    }

    /**
     * 1.4.0：决定 [uri] 是否需要 App 自解码前端。
     *
     * 决策已下放到**编译期 flavor 专属源集**的 [FlavorDecodeFrontend.plan]，三版各返回
     * 不同的 [DecodePlan]（SYSTEM_DIRECT / TRANSCODE_FLAC / STREAM_PCM）。这里只做一件事：
     * SYSTEM_DIRECT 返回 false（原始 Uri 直读、零缓存），其余两路返回 true（需 App 解码）。
     * 每一步都写 Decode{...} 帧，日志可串成完整时间线复盘。
     */
    fun needsTranscode(context: Context, uri: Uri): Boolean {
        val shortUri = uri.toString().takeLast(48)
        val plan = FlavorDecodeFrontend.plan(context, uri)
        com.smartisan.music.diagnostics.DecodeDiagnostics.trace(
            "needsTranscode", "uri" to shortUri,
            "engine" to DecodeRouter.engineId,
            "plan" to plan.name,
        )
        return plan != DecodePlan.SYSTEM_DIRECT
    }

    /**
     * 读文件头判断是否需要转码。返回 null 表示探测失败（读不到文件）——
     * 调用方不缓存该结果，且 [transcodeIfNeeded] 侧会打日志方便诊断权限问题。
     */
    private fun peekNeedsTranscode(context: Context, uri: Uri): Boolean? = runCatching {
        // [1.4.28] 打不开流必须返回 null（未知），不能当 false（不需要转码）：
        // 旧写法 `?.use {} ?: false` 把权限缺失/文件暂不可读当成「探测结论」并写入缓存，
        // 权限恢复后该文件仍被判「无需转码」，冷门格式永久 3003。
        val ins = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        ins.use { stream ->
            val head = ByteArray(PEEK_SIZE)
            var read = 0
            while (read < head.size) {
                val n = stream.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < 16) return@use null
            head.needsTranscodeByMagic(read)
        }
    }.onFailure {
        AppLog.e(TAG, "探测文件头失败（权限/文件不可读？）uri=$uri", it)
    }.getOrNull()

    /** 基于文件头魔数判断。 */
    private fun ByteArray.needsTranscodeByMagic(size: Int): Boolean {
        if (size >= 4 && this.contentEquals(0, APE_MAGIC)) return true
        // DSD：DSF "DSD " / DFF "FRM8"
        if (size >= 4 && get(0) == 'D'.code.toByte() && get(1) == 'S'.code.toByte() &&
            get(2) == 'D'.code.toByte() && get(3) == ' '.code.toByte()
        ) {
            return true
        }
        if (size >= 4 && get(0) == 'F'.code.toByte() && get(1) == 'R'.code.toByte() &&
            get(2) == 'M'.code.toByte() && get(3) == '8'.code.toByte()
        ) {
            return true
        }
        if (size >= 4 && asciiAt(0) == "TTA1") return true
        if (size >= 4 && asciiAt(0) == "wvpk") return true
        if (size >= 4 && asciiAt(0) == "tBaK") return true
        if (size >= 4 && asciiAt(0) == "ajkg") return true
        if (size >= 4 && asciiAt(0) == "MPCK") return true
        // ASF / WMA：{0x30,0x26,0xB2,0x75,0x8E,0x66,0xCF,0x11}
        if (size >= 8 &&
            get(0) == 0x30.toByte() && get(1) == 0x26.toByte() &&
            get(2) == 0xB2.toByte() && get(3) == 0x75.toByte() &&
            get(4) == 0x8E.toByte() && get(5) == 0x66.toByte() &&
            get(6) == 0xCF.toByte() && get(7) == 0x11.toByte()
        ) {
            return true
        }
        // DTS 裸流：16bit 同步 0x7FFE8001 / 14bit 同步 0xFF1F00E8
        if (size >= 4 && isDtsSync(0)) return true
        // TrueHD / MLP 同步字 F8 72 6E / F8 72 6F
        if (size >= 4 && get(0) == 0xF8.toByte() && get(1) == 0x72.toByte() &&
            (get(2) == 0x6E.toByte() || get(2) == 0x6F.toByte())
        ) {
            return true
        }
        // RIFF 容器：WAV 里内嵌 DTS / 非 PCM 编码（AC-3、MP3-in-wav…）系统会当噪声播
        if (size >= 12 && asciiAt(0) == "RIFF" && asciiAt(8) == "WAVE") {
            return riffWavNeedsTranscode(size)
        }
        // 1.4.11 精准修：ISO-BMFF（MP4/M4A/M4B）容器。
        // 这类文件扩展名不再被免探白名单短路，走到这里深扫 stsd 采样条目：
        //  - "alac"：Apple Lossless，本机通常无 ALAC 解码器 → 必须转 FLAC；
        //  - "mp4a"/其他：普通 AAC，系统解码器可直读 → 放行（保零缓存点开即播）。
        // 只在识别出 alac 时返回 true，避免误伤海量普通 AAC m4a。
        if (isIsoBmff(size)) {
            return mp4NeedsTranscode(size)
        }
        return false
    }

    /** ISO-BMFF 容器：偏移 4 处是 "ftyp"（普遍位于文件头），或首块是 "moov"/"mdat"。 */
    private fun ByteArray.isIsoBmff(size: Int): Boolean {
        if (size >= 8 && asciiAt(4) == "ftyp") return true
        if (size >= 4 && (asciiAt(0) == "moov" || asciiAt(0) == "mdat")) return true
        return false
    }

    /** 在 MP4 的采样描述表里找音频条目格式码：alac→需转码，其余（mp4a 等）→直读。 */
    private fun ByteArray.mp4NeedsTranscode(size: Int): Boolean {
        val stsd = indexOfAscii("stsd", 0, size)
        if (stsd < 0 || stsd + 16 > size) return false
        // stsd 布局(相对 box 起点)：size(4)+type(4)+version/flags(4)+entry_count(4)
        // = +16 处是第一个采样条目；条目 size(4)+format(4) → format 在 +20。
        val formatOffset = stsd + 20
        if (formatOffset + 4 > size) return false
        val format = String(this, formatOffset, 4, Charsets.US_ASCII)
        if (format == "alac") return true
        // 有些文件 stsd 之后还有 avc1 视频轨在前，音频条目靠后——扫描若干候选。
        var off = stsd + 16
        val end = minOf(size - 4, stsd + 8192)
        while (off + 8 <= end) {
            val entryFormat = String(this, off + 4, 4, Charsets.US_ASCII)
            if (entryFormat == "alac") return true
            if (entryFormat == "mp4a") return false
            val entrySize = readBeInt32(off)
            if (entrySize < 8) break
            off += entrySize
        }
        return false
    }

    /** 在 [from, to) 范围找 ASCII 子串，返回起始偏移，未找到返回 -1。 */
    private fun ByteArray.indexOfAscii(needle: String, from: Int, to: Int): Int {
        if (needle.isEmpty()) return -1
        val nb = needle.toByteArray(Charsets.US_ASCII)
        var i = maxOf(0, from)
        val limit = minOf(size - nb.size, to - nb.size)
        while (i <= limit) {
            var ok = true
            for (j in nb.indices) {
                if (this[i + j] != nb[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    /** 大端 32 位整数（MP4 box size / 采样条目 size）。 */
    private fun ByteArray.readBeInt32(offset: Int): Int {
        if (offset + 4 > size) return -1
        return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.asciiAt(offset: Int): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) sb.append((get(offset + i).toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun ByteArray.contentEquals(offset: Int, other: ByteArray): Boolean {
        if (size - offset < other.size) return false
        for (i in other.indices) {
            if (get(offset + i) != other[i]) return false
        }
        return true
    }

    /** 16bit DTS 同步字 0x7FFE8001；14bit：FF 1F 00 E8（前 4 字节足够区分）。 */
    private fun ByteArray.isDtsSync(offset: Int): Boolean {
        if (size - offset < 4) return false
        val b0 = get(offset).toInt() and 0xFF
        val b1 = get(offset + 1).toInt() and 0xFF
        val b2 = get(offset + 2).toInt() and 0xFF
        val b3 = get(offset + 3).toInt() and 0xFF
        if (b0 == 0x7F && b1 == 0xFE && b2 == 0x80 && b3 == 0x01) return true
        if (b0 == 0xFF && b1 == 0x1F && b2 == 0x00 && b3 == 0xE8) return true
        return false
    }

    /**
     * 解析 RIFF 块表到 data 块，判断 WAV 内的音频编码：
     * - fmt 块的 format tag 不是 PCM(1)/浮点(3)/EXTENSIBLE(0xFFFE) → 系统播不了 → 转码；
     * - fmt 是 PCM 但 data 起始是 DTS 同步字（DTS-CD 镜像）→ 系统会当噪声播 → 转码。
     */
    private fun ByteArray.riffWavNeedsTranscode(size: Int): Boolean {
        var off = 12
        var formatTag = -1
        while (off + 8 <= size) {
            val id = asciiAt(off)
            val chunkSize =
                ((get(off + 4).toInt() and 0xFF) or
                    ((get(off + 5).toInt() and 0xFF) shl 8) or
                    ((get(off + 6).toInt() and 0xFF) shl 16) or
                    ((get(off + 7).toInt() and 0xFF) shl 24))
            when (id) {
                "fmt " -> {
                    if (off + 10 <= size) {
                        formatTag = (get(off + 8).toInt() and 0xFF) or
                            ((get(off + 9).toInt() and 0xFF) shl 8)
                    }
                }
                "data" -> {
                    val dataStart = off + 8
                    if (formatTag != 1 && formatTag != 3 && formatTag != 0xFFFE) return true
                    if (dataStart + 4 <= size && isDtsSync(dataStart)) return true
                    return false
                }
            }
            off += 8 + chunkSize + (chunkSize and 1)
        }
        return false
    }

    /**
     * 确保内置 ffmpeg 可执行文件就绪，返回其可执行路径。
     *
     * 优先级说明（重要）：
     * 1. 首选 [nativeLibraryDir]/libffmpegbin.so **原地直接执行**——构建已开启
     *    useLegacyPackaging，.so 会在安装时解压落盘到该目录；它是系统信任的可执行目录，
     *    targetSdk>=29 的 W^X 限制（禁止 exec app 数据目录内二进制）对它不生效。
     *    无需拷贝、无需 chmod，直接把路径交给 ProcessBuilder。
     * 2. 兜底：从 APK 自身（applicationInfo.sourceDir）抽取到私有 ffbin 目录再执行——
     *    仅对未启用 W^X 检查的旧设备/旧系统有效。
     */
    private fun ensureBinary(context: Context) {
        if (ffmpegPath != null) return
        synchronized(initLock) {
            if (ffmpegPath != null) return

            // 1) nativeLibraryDir 原地执行（W^X 下唯一可靠路径）
            runCatching {
                val nativeBin = File(context.applicationInfo.nativeLibraryDir, NATIVE_LIB)
                if (nativeBin.exists() && nativeBin.length() > 500_000L) {
                    ffmpegPath = nativeBin.absolutePath
                    AppLog.i(TAG, "ffmpeg 就绪（nativeLibraryDir 原地）: ${nativeBin.absolutePath} (${nativeBin.length()}B) canExec=${nativeBin.canExecute()}")
                    return
                }
                AppLog.i(TAG, "nativeLibraryDir 无 $NATIVE_LIB 或大小异常: exists=${nativeBin.exists()} size=${nativeBin.length()} dir=${context.applicationInfo.nativeLibraryDir}")
            }.onFailure { AppLog.e(TAG, "探测 nativeLibraryDir 失败", it) }

            // 2) 兜底：私有目录副本（旧设备可用；W^X 设备上 exec 会失败并留下明确日志）
            val dir = context.getDir("ffbin", Context.MODE_PRIVATE)
            val exe = File(dir, FF_BIN_NAME)
            if (exe.exists() && exe.canExecute() && exe.length() > 500_000L) {
                ffmpegPath = exe.absolutePath
                AppLog.i(TAG, "ffmpeg 已就绪（复用缓存副本）: ${exe.absolutePath} (${exe.length()}B)")
                return
            }
            var extracted = false
            runCatching { extracted = extractFromApk(context, exe) }
                .onFailure { AppLog.e(TAG, "从 APK 抽取 ffmpeg 失败", it) }
            if (!extracted) {
                runCatching {
                    val nativeDir = context.applicationInfo.nativeLibraryDir
                    val src = File(nativeDir, NATIVE_LIB)
                    if (src.exists()) {
                        src.inputStream().use { ins -> exe.outputStream().use { outs -> ins.copyTo(outs) } }
                        AppLog.i(TAG, "从 nativeLibraryDir 抽取 ffmpeg: $nativeDir")
                        extracted = true
                    }
                }.onFailure { AppLog.e(TAG, "从 nativeLibraryDir 抽取 ffmpeg 失败", it) }
            }
            if (extracted) {
                exe.setExecutable(true, false)
                val ok = exe.exists() && exe.canExecute() && exe.length() > 500_000L
                ffmpegPath = if (ok) {
                    AppLog.i(TAG, "ffmpeg 解压成功: ${exe.absolutePath} (${exe.length()}B)")
                    exe.absolutePath
                } else {
                    AppLog.e(TAG, "ffmpeg 解压后不可用: exists=${exe.exists()} canExec=${exe.canExecute()} size=${exe.length()}")
                    null
                }
            } else {
                ffmpegPath = null
                AppLog.e(TAG, "ffmpeg 不可用：$NATIVE_LIB 在 APK 与 nativeLibraryDir 均未找到，转码播放不可用")
            }
        }
    }

    /** 从 APK（applicationInfo.sourceDir）的 lib/<abi>/ 目录抽取 ffmpeg 可执行文件。 */
    private fun extractFromApk(context: Context, dest: File): Boolean {
        val apk = context.applicationInfo.sourceDir ?: run {
            AppLog.e(TAG, "applicationInfo.sourceDir 为空，无法从 APK 抽取 ffmpeg")
            return false
        }
        val apkFile = File(apk)
        if (!apkFile.exists()) {
            AppLog.e(TAG, "APK 文件不存在: $apk")
            return false
        }
        val candidates = (Build.SUPPORTED_ABIS.toList() + "arm64-v8a")
            .distinct().map { "lib/$it/$NATIVE_LIB" }
        ZipFile(apkFile).use { zip ->
            for (entryName in candidates) {
                val entry = zip.getEntry(entryName) ?: continue
                AppLog.i(TAG, "从 APK 抽取: $entryName (${entry.size}B) <- $apk")
                zip.getInputStream(entry).use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
                return true
            }
        }
        AppLog.i(TAG, "APK 内未找到 $NATIVE_LIB（已尝试 ${candidates.joinToString()}）")
        return false
    }

    // ------------------------------------------------------------------
    // 缓存管理（方案 A/D）
    // ------------------------------------------------------------------

    private fun cacheDirOf(context: Context): File =
        File(context.applicationContext.cacheDir, CACHE_DIR).also { it.mkdirs() }

    private fun tmpDirOf(context: Context): File =
        File(context.applicationContext.cacheDir, TMP_DIR).also { it.mkdirs() }

    /**
     * 清理 1.2.15 之前落在 filesDir 的转码残留（曾膨胀到 1.41GB 的元凶）。
     * filesDir 不受系统空间回收管理，必须主动清；进程内只跑一次。
     */
    private fun cleanLegacyIfNeeded(context: Context) {
        if (legacyCleaned.getAndSet(true)) return
        Thread {
            runCatching {
                val appCtx = context.applicationContext
                var freed = 0L
                for (name in listOf(CACHE_DIR, TMP_DIR)) {
                    val legacy = File(appCtx.filesDir, name)
                    if (!legacy.exists()) continue
                    legacy.walkBottomUp().forEach { freed += it.length(); it.delete() }
                }
                if (freed > 0) AppLog.i(TAG, "清理 filesDir 旧转码残留: ${freed / 1024 / 1024}MB")
            }.onFailure { AppLog.e(TAG, "清理旧转码残留失败", it) }
        }.start()
    }

    /** 当前转码缓存占用字节数（设置页展示，方案 D）。 */
    fun cacheSizeBytes(context: Context): Long {
        cleanLegacyIfNeeded(context)
        val appCtx = context.applicationContext
        val cache = File(appCtx.cacheDir, CACHE_DIR)
        val tmp = File(appCtx.cacheDir, TMP_DIR)
        val cacheSize = cache.listFiles()?.sumOf { it.length() } ?: 0L
        val tmpSize = tmp.listFiles()?.sumOf { it.length() } ?: 0L
        return cacheSize + tmpSize
    }

    /** 一键清空转码缓存（设置页按钮，方案 D）。 */
    fun clearCache(context: Context) {
        runCatching {
            val appCtx = context.applicationContext
            File(appCtx.cacheDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
            File(appCtx.cacheDir, TMP_DIR).listFiles()?.forEach { it.delete() }
            AppLog.i(TAG, "转码缓存已清空")
        }.onFailure { AppLog.e(TAG, "清空转码缓存失败", it) }
    }

    /**
     * 定向 LRU 淘汰（1.2.28 重写）。
     *
     * 旧版「空间不足 → `cacheDir.listFiles()?.forEach { it.delete() }`」是 1.2.27 最严重的
     * 回归：它把**正在被播放器读取的那个 FLAC** 也删了，读取立刻越界
     * （`ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`）→ 判曲目失败 → 自动跳到下一首 DFF →
     * 又要转码 → 又清缓存，连锁跳过所有 DFF 直到第一首 FLAC；刚转好的缓存被删又导致
     * 「百分比很高突然归零、从头再转一次」。
     *
     * 现在只按 lastModified 从老到新删，且**跳过最近 [RECENT_CACHE_PROTECT_MS] 内被 touch
     * 过的文件**（正在播/刚转好的一定会在这段时间里被 touch），删到容量与可用空间都达标为止。
     */
    private fun evictCache(cacheDir: File, targetFreeBytes: Long, reason: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val candidates =
                cacheDir.listFiles { f ->
                    f.isFile && f.extension == "flac" && !f.name.endsWith(".part.flac")
                }?.sortedBy { it.lastModified() }.orEmpty()
            var total = candidates.sumOf { it.length() }
            var deleted = 0
            var freed = 0L
            var protectedCount = 0
            for (f in candidates) {
                val free = usableBytes(cacheDir)
                if (total <= TRIM_TARGET_BYTES && free >= targetFreeBytes) break
                if (now - f.lastModified() < RECENT_CACHE_PROTECT_MS) {
                    protectedCount++
                    continue
                }
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    freed += len
                    deleted++
                    AppLog.i(TAG, "LRU 淘汰[$reason]: ${f.name} (${len / 1024}KB)")
                }
            }
            AppLog.i(
                TAG,
                "缓存淘汰完成[$reason]: 删除=$deleted 释放=${freed / 1024 / 1024}MB" +
                    " 保护中=$protectedCount 剩余=${total / 1024 / 1024}MB" +
                    " 可用=${usableBytes(cacheDir) / 1024 / 1024}MB 目标可用=${targetFreeBytes / 1024 / 1024}MB",
            )
        }
    }

    /** 转码完成后立即做一次水位/可用空间检查，避免缓存慢慢把磁盘吃满。 */
    private fun trimCacheIfNeeded(cacheDir: File) {
        evictCache(cacheDir, MIN_FREE_BYTES, "转码完成水位检查")
    }

    // ------------------------------------------------------------------
    // 转码（1.2.27 起改为「先转完、再播放」：播放器只读完整文件）
    // ------------------------------------------------------------------

    /**
     * 1.2.27 架构调整————彻底放弃「边转边播」。
     *
     * 1.2.20~1.2.26 的做法是让播放器直接读 ffmpeg 正在写的 `.part.flac`（增长文件）。
     * 这条路的三个死结：
     * 1. 文件头里的 `total_samples` 在写盘时是 0，转完才回填 → 播放器拿不到时长；
     * 2. 没有 `SEEKTABLE` 时 `FlacExtractor` 给出 `Unseekable`，拖动被直接忽略 → 松手弹回；
     * 3. 拖动/重开会在另一个线程 `close()` 读取器，正在读的数据被截断，Media3 把
     *    「读被取消」归类成 `ERROR_CODE_PARSING_CONTAINER_MALFORMED` → 直接判曲目失败。
     *
     * 现在改成：**转码必须在播放器打开文件之前完成**。播放器看到的是最终文件
     * （`STREAMINFO.total_samples` 已回填、`SEEKTABLE` 已补好），时长、拖动、打碟
     * 全部走 ExoPlayer 原生能力，不再需要「重载当前项」「等转码完成」这些补丁。
     * 代价是首次播放要等转码（实测 ffmpeg 约为实时的 30~50 倍，5 分钟的歌约 6~10 秒），
     * 期间由转码任务通过 TranscodeProgressBus 上报百分比给 UI 显示「正在转换」。
     */
    internal class TranscodeJob internal constructor(
        val key: String,
        val sourceUri: android.net.Uri,
        val partFile: File,
        val outFile: File,
        val estimatedBytes: Long,
    ) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val success = java.util.concurrent.atomic.AtomicBoolean(false)
        val isRunning: Boolean get() = !done.get()

        @Volatile
        private var process: java.lang.Process? = null

        @Volatile
        var cancelRequested = false
            private set

        fun attachProcess(proc: java.lang.Process) {
            process = proc
            if (cancelRequested) runCatching { proc.destroy() }
        }

        /** 只有「存储真的要写满了」才主动取消，切歌/关播放器都不再打断转码。 */
        fun requestCancel() {
            if (done.get()) return
            cancelRequested = true
            runCatching { process?.destroy() }
        }

        /** 已写字节 / 预估总字节，仅用于进度显示。 */
        fun progressPercent(): Int {
            if (estimatedBytes <= 0L) return 0
            val written = runCatching { partFile.length() }.getOrDefault(0L)
            if (written <= 0L) return 0
            return ((written * 100L) / estimatedBytes).toInt().coerceIn(1, 99)
        }
    }

    /**
     * 转码线程池。
     *
     * 1.2.27：不再「切歌就取消」，所以同一时刻排队的任务会自然跑完并进缓存；
     * 3 个线程既能吃掉连续点歌，又不至于把 CPU 抢到播放卡顿。
     */
    private val transcodeExecutor = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "ape-transcode").apply { isDaemon = true }
    }

    /**
     * 进度上报调度器（1.2.28）。
     * 进度由「转码任务」发布，而不是「DataSource.open」——后者每次 seek 都会重来一遍，
     * 正是用户看到的「百分比到很高突然归零、又从头转换」的来源。
     */
    private val progressScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ape-progress").apply { isDaemon = true }
        }

    /** 等转码完成的上限。DSD 来源动辄 300MB，放宽到 90 秒仍属异常。 */
    private const val WaitTimeoutMs = 90_000L

    /**
     * 1.3.3：转码期间持有 PARTIAL_WAKE_LOCK 的最长时长。
     * 用户反馈「切后台后正在缓冲的歌很久不播 / 说不支持格式 / 播放卡顿，切回前台马上正常」，
     * 日志里同时出现 wakelock released 与转码失败。ffmpeg 跑在普通线程池、无锁无优先级，
     * 灭屏后 CPU 被挂起会显著拖慢甚至打断转码。这里在整段转码期间自己持锁。
     */
    private const val TranscodeWakeLockTimeoutMs = 10L * 60L * 1000L

    /**
     * 1.3.3：DataSource 发现「需转码但缓存还没就绪」时，等待缓存落地的窗口。
     * 超过窗口就抛明确的 IOException，绝不把原始 DFF/APE 容器交给 Media3。
     */
    internal const val TranscodeReadyWaitTimeoutMs = 20_000L

    /**
     * 补索引（同步）。
     *
     * 1.2.27 起只有两条调用路径，都在转码线程或加载线程上同步跑：
     * 转码刚完成时补一次、缓存命中但缺索引时补一次。1.2.25 那个「后台单线程补历史缓存」
     * 的分支已随「边转边播」一起删除——现在没有「边播边补」的场景了。
     */
    private fun applySeekTable(file: File) {
        runCatching { FlacSeekTablePatcher.ensureSeekTable(file) }
    }

    /** 进行中的转码任务：key → job。 */
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, TranscodeJob>()

    /**
     * [1.4.24·缓冲卡死根治] 最近一次转码失败的任务标记（key → 失败原因）。
     * 失败的 job 会被从 runningJobs 移除，open() 的等待循环原本无从区分"还在转"和"已失败"，
     * 会空等满 20s 再抛错；Media3 重试 open 又等 20s，宏观表现为"一直缓冲没结果"。
     * 这里记录终态失败，等待循环检测到立即放弃，不再空等。key 存在即视为失败终态。
     */
    private val failedTranscodeKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 供 TranscodingDataSource 等待循环查询：该 key 最近是否转码失败（入参时间戳之后失败的才算）。 */
    internal fun transcodeFailedAfter(context: Context, uri: Uri, sinceMs: Long): Boolean {
        val key = runCatching { cacheKey(context.applicationContext, uri) }.getOrNull() ?: return false
        val failedAt = failedTranscodeKeys[key] ?: return false
        return failedAt >= sinceMs
    }

    /** 1.2.30：补索引是就地写文件，同一 key 的多个 DataSource（当前项/预加载/seek 重开）必须串行。 */
    private val cacheRepairLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(key: String): Any {
        val existing = cacheRepairLocks[key]
        if (existing != null) return existing
        val created = Any()
        return cacheRepairLocks.putIfAbsent(key, created) ?: created
    }

    /**
     * 播放前准备：把源文件转成**完整**、带索引的 FLAC 并返回它。
     *
     * - 缓存命中且校验通过 → 立即返回（毫秒级）；
     * - 缓存存在但链尾损坏（1.2.25/1.2.26 的补索引 bug）→ 删掉重转，自愈；
     * - 否则启动（或加入）转码，**等待完成**后再返回，进度由转码任务自行上报。
     *
     * @return 可直接交给 ExoPlayer 的本地文件；null 表示无需转码，或转码失败（调用方回退原始 Uri）
     */
    fun prepareForPlayback(context: Context, uri: Uri): File? {
        cleanLegacyIfNeeded(context)
        if (!needsTranscode(context, uri)) return null
        val appCtx = context.applicationContext
        ensureBinary(appCtx)
        val bin = ffmpegPath
        if (bin == null) {
            AppLog.e(TAG, "ffmpeg 不可用，回退原始 Uri（该格式无法播放）")
            return null
        }
        val cacheDir = cacheDirOf(appCtx)
        val key = cacheKey(appCtx, uri)
        val outFile = File(cacheDir, "$key.flac")

        // [1.4.5·清单 4·G-18] 前后台切换窗口(3s)内，转码缓存已落地就直接复用，
        // 跳过下面整套「索引校验 / 就地补索引 / 链损坏删除重建 / 重新起转码」判定。
        // 10 号页老病灶：切后台瞬间 ExoPlayer 反复 open，缓存刚落地那一瞬又走一遍完整
        // 判定，甚至被 isChainBroken 误判删掉重转 44 秒 → 后台「转码缓存反复未就绪」(144 次)。
        // 收紧：只在窗口内 + 文件确实落地(exists>0)时走快速通道；窗口外完全沿用原逻辑，
        // G-11 节流 / 索引自愈 / 删除重建一条不删、不放宽。
        // outFile 只在 ffmpeg rc==0 且 rename 成功后才存在，故「存在」即产物完整可播
        //（最多暂不能精确拖动，窗口外下一次 open 会走完整校验补齐索引）。
        val sinceFgChange =
            android.os.SystemClock.elapsedRealtime() -
                com.smartisan.music.MusicApplication.lastForegroundChangeAtMs
        if (com.smartisan.music.MusicApplication.lastForegroundChangeAtMs > 0L &&
            sinceFgChange in 0L..3_000L &&
            outFile.exists() && outFile.length() > 0L
        ) {
            AppLog.i(
                TAG,
                "1.4.5(G-18) 后台窗口内缓存就绪复用：跳过重新判定/转码 " +
                    "outFile=${outFile.name} size=${outFile.length() / 1024}KB " +
                    "sinceFgChange=${sinceFgChange}ms",
            )
            return outFile
        }

        // 1) 缓存命中：只读、只 touch，绝不发布进度。
        //    1.2.27 在这里 publish(100) 再由调用方 finally clear()，一次 seek 就会闪一次
        //    「正在转换」，多 DataSource 并发时还会互相清状态。
        if (outFile.exists() && outFile.length() > 0L) {
            // 1.2.30：同一首歌可能被「当前项 + 预加载下一项 + seek 重开」同时 open，
            // 补索引是就地写 143MB 文件的元数据区，不串行就会互相覆盖成半截表。
            synchronized(lockFor(key)) {
                if (FlacSeekTablePatcher.isValidSeekableFlac(outFile)) {
                    outFile.setLastModified(System.currentTimeMillis()) // LRU touch + 淘汰保护
                    return outFile
                }
                if (outFile.exists() && outFile.length() > 0L) {
                    val chainBroken = FlacSeekTablePatcher.isChainBroken(outFile)
                    if (!chainBroken) {
                        // 1.2.30：链完整（第一帧同步码在）就**绝不删文件重转**。
                        // 1.2.29 在这里一律「删除重建」，而 ensureSeekTable 又因 verify
                        // 读错位置永远失败，于是同一首歌每隔几秒从头转一次、进度归零重来。
                        // 现在先尝试就地补索引，补不上也直接用（最多不能精确拖动）。
                        val patched = FlacSeekTablePatcher.ensureSeekTable(outFile)
                        val valid = FlacSeekTablePatcher.isValidSeekableFlac(outFile)
                        AppLog.i(
                            TAG,
                            "缓存命中（不重转）: ${outFile.name} chainOk=true" +
                                " patched=$patched seekable=$valid" +
                                " ${FlacSeekTablePatcher.diagnose(outFile)}",
                        )
                        outFile.setLastModified(System.currentTimeMillis())
                        return outFile
                    }
                    AppLog.w(
                        TAG,
                        "缓存元数据链损坏，删除重建: ${outFile.name}" +
                            " ${FlacSeekTablePatcher.diagnose(outFile)}",
                    )
                    runCatching { outFile.delete() }
                }
            }
        }

        // 2) 启动或加入同键任务；若另一个线程刚好转完，直接用产物
        val job = startOrJoinJob(appCtx, uri, key, outFile, bin)
            ?: return outFile.takeIf { it.exists() && it.length() > 0L }

        // 3) 等它转完（进度由任务线程发布，这里不再碰 Bus）
        val startedAt = android.os.SystemClock.elapsedRealtime()
        if (job.isRunning) {
            AppLog.i(
                TAG,
                "等待转码完成再播: ${outFile.name} 预估≈${job.estimatedBytes / 1024 / 1024}MB",
            )
        }
        while (job.isRunning) {
            // 取消信号只认「加载线程被中断」——Media3 取消加载时一定会 interrupt。
            // 1.2.27 用 DataSource 上的 aborted 标志，seek 的 close 与新 open 并发时会把
            // 新的一次加载也判成取消，直接抛异常 → 播放失败。
            if (Thread.currentThread().isInterrupted) {
                AppLog.i(TAG, "加载线程被中断，放弃等待（转码继续在后台完成）: ${outFile.name}")
                return null
            }
            if (android.os.SystemClock.elapsedRealtime() - startedAt > WaitTimeoutMs) {
                AppLog.e(TAG, "等待转码超时(${WaitTimeoutMs}ms): ${outFile.name}")
                return null
            }
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        if (!job.success.get()) {
            AppLog.w(TAG, "转码未成功，回退原始 Uri: ${outFile.name}")
            return null
        }
        val ready = outFile.takeIf { it.exists() && it.length() > 0L }
        if (ready != null) {
            AppLog.i(
                TAG,
                "转码就绪: ${ready.name} (${ready.length() / 1024}KB, " +
                    "耗时 ${android.os.SystemClock.elapsedRealtime() - startedAt}ms)",
            )
        }
        return ready
    }

    /**
     * 1.2.28 排障：记录 DataSource **实际**交给播放器的文件。
     *
     * 之前失败日志里的 `head=` 读的是 MediaItem 的原始 URI（永远是 `FRM8` / `MAC `），
     * 会让人误以为「播放器读的是原始 .dff」；实际上也可能已经换成了转码缓存。
     * 有这行就能一眼区分「没换成功」还是「换了但文件坏了」。
     */
    @Volatile
    private var lastOpenDescription: String = "none"

    internal fun recordLastOpen(
        seq: Long,
        requestedUri: Uri?,
        actualUri: Uri?,
        position: Long,
        length: Long,
    ) {
        lastOpenDescription =
            "seq=$seq requested=${requestedUri?.toString()?.takeLast(48)}" +
            " actual=${actualUri?.toString()?.takeLast(64)}" +
            " pos=$position len=$length"
    }

    internal fun lastOpen(): String = lastOpenDescription

    /** 启动时打一条缓存概览，便于判断「是不是缓存被清了/写满了」。 */
    fun logCacheSummary(context: Context) {
        runCatching {
            val dir = cacheDirOf(context.applicationContext)
            val files = dir.listFiles()
            var count = 0
            var total = 0L
            if (files != null) {
                for (f in files) {
                    if (!f.isFile) continue
                    if (f.extension != "flac") continue
                    count++
                    total += f.length()
                }
            }
            AppLog.i(TAG, "转码缓存: $count 个, ${total / 1024 / 1024}MB, dir=${dir.absolutePath}")
        }
    }

    /**
     * 排障用：一句话说清「这个 uri 现在处在转码链路的哪一步」。
     *
     * 之前日志只写「转码失败/等待超时」，看不出「是缓存坏了、还在转、还是根本没开始」，
     * 每次都要靠用户复现才能猜。现在失败时会连同这段快照一起落盘。
     */
    fun describe(context: Context, uri: Uri?): String {
        if (uri == null) return "uri=null"
        val needs = runCatching { needsTranscode(context, uri) }.getOrDefault(false)
        if (!needs) return "needsTranscode=false"
        return runCatching {
            val appCtx = context.applicationContext
            val key = cacheKey(appCtx, uri)
            val outFile = File(cacheDirOf(appCtx), "$key.flac")
            val exists = outFile.exists() && outFile.length() > 0L
            val job = runningJobs[key]
            "needsTranscode=true cache=${outFile.name} exists=$exists" +
                " size=${outFile.length() / 1024}KB" +
                " seekable=${exists && FlacSeekTablePatcher.isValidSeekableFlac(outFile)}" +
                "\n  索引诊断(实际读取的缓存 FLAC)=" +
                (if (exists) FlacSeekTablePatcher.diagnose(outFile) else "cache=missing") +
                " job=" +
                (job?.let { "running=${it.isRunning} pct=${it.progressPercent()}" } ?: "none")
        }.getOrElse { "describe failed: ${it.message}" }
    }

    /**
     * 1.3.3：只查「已经完整落地的转码缓存」，不启动转码、不等待、不补索引。
     * 供 TranscodingDataSource 在 prepareForPlayback 返回 null 时轮询，避免把
     * 需转码的原始容器（FRM8...DSD / MAC ）交给 Media3 而误报「不支持的音乐格式」。
     */
    internal fun cachedTranscodeFile(context: Context, uri: Uri): File? {
        if (!needsTranscode(context, uri)) return null
        return runCatching {
            val appCtx = context.applicationContext
            val key = cacheKey(appCtx, uri)
            File(cacheDirOf(appCtx), "$key.flac")
                .takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    /** 1.3.3：转码期间持锁，防止灭屏/后台把 ffmpeg 线程饿死。 */
    private fun acquireTranscodeWakeLock(context: Context): PowerManager.WakeLock? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm == null) {
                null
            } else {
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChuiziMusic:Transcode")
                    .apply {
                        setReferenceCounted(false)
                        acquire(TranscodeWakeLockTimeoutMs)
                    }
            }
        } catch (error: Throwable) {
            AppLog.w(TAG, "转码 wakelock 获取失败: ${error.message}")
            null
        }
    }

    private fun releaseTranscodeWakeLock(lock: PowerManager.WakeLock?) {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    /** 启动同键转码；已有在跑的则直接返回它（并发安全）。 */
    private fun startOrJoinJob(
        appCtx: Context,
        uri: Uri,
        key: String,
        outFile: File,
        bin: String,
    ): TranscodeJob? {
        val cacheDir = cacheDirOf(appCtx)
        runningJobs[key]?.let { job ->
            if (job.isRunning) return job
            runningJobs.remove(key, job)
        }
        // [1.4.24] 新一轮转码开始，清除上一轮的失败终态标记（允许重新尝试）。
        failedTranscodeKeys.remove(key)
        // 转码前空间预检：只做定向淘汰，绝不无差别清空缓存、绝不取消在跑的任务。
        val sourceSize = fileSize(appCtx, uri).coerceAtLeast(0L)
        val needBytes = estimatedOutputBytes(sourceSize) + SPACE_SAFETY_MARGIN_BYTES
        var usable = usableBytes(cacheDir)
        if (usable < needBytes) {
            AppLog.w(
                TAG,
                "空间不足，定向淘汰缓存: 可用=${usable / 1024 / 1024}MB 需要≈${needBytes / 1024 / 1024}MB",
            )
            evictCache(cacheDir, needBytes, "转码前预检")
            usable = usableBytes(cacheDir)
        }
        if (usable < needBytes) {
            AppLog.e(
                TAG,
                "放弃转码：存储空间不足（可用=${usable / 1024 / 1024}MB，" +
                    "需要≈${needBytes / 1024 / 1024}MB）；不清缓存，避免删掉正在播放的文件",
            )
            // [1.4.25] 失败终态也要标记：否则等待循环空等 20s，Media3 再试又等 20s = 无限转圈。
            failedTranscodeKeys[key] = System.currentTimeMillis()
            return null
        }
        // 另一个线程可能刚好转完并落盘；这时直接用它，别再起一次转码。
        if (outFile.exists()) return null

        val lock = transcodeLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            runningJobs[key]?.let { job -> if (job.isRunning) return job }
            val input =
                prepareInput(appCtx, uri, key)
                    ?: run {
                        AppLog.e(TAG, "无法准备输入文件（content 落地失败或 file 不存在）")
                        failedTranscodeKeys[key] = System.currentTimeMillis()
                        return null
                    }
            val dsd = isDsdFile(input)
            // 临时文件必须保留 .flac 后缀（ffmpeg 靠扩展名推断格式，见 1.2.15 坑）
            val partFile = File(cacheDir, "$key.part.flac")
            runCatching { partFile.delete() }
            val job =
                TranscodeJob(
                    key = key,
                    sourceUri = uri,
                    partFile = partFile,
                    outFile = outFile,
                    estimatedBytes = estimatedOutputBytes(input.length()),
                )
            runningJobs[key] = job
            AppLog.i(TAG, "开始 ffmpeg 转码: in=${input.absolutePath} (${input.length()}B)")
            val inputIsTmp = input.absolutePath.startsWith(appCtx.cacheDir.absolutePath)
            val downsampleHz = if (dsd) DSD_PCM_SAMPLE_RATE_HZ else 0
            transcodeExecutor.execute {
                // 1.3.3：转码跑在普通线程池里，没有 wakelock；切后台灭屏后 CPU 被挂起，
                // ffmpeg 会明显变慢甚至被打断，用户看到的就是「缓冲很久不播 / 过一会
                // 说不支持格式 / 后台卡顿而切回前台马上正常」。整段转码期间自己持锁。
                val transcodeWakeLock = acquireTranscodeWakeLock(appCtx)
                AppLog.i(TAG, "转码 wakelock acquired=${transcodeWakeLock?.isHeld == true}")
                val progressTask =
                    progressScheduler.scheduleAtFixedRate(
                        {
                            if (job.isRunning) {
                                TranscodeProgressBus.publish(
                                    key, outFile.name, job.progressPercent(),
                                )
                            }
                        },
                        0L, 250L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                try {
                    val rc =
                        runFfmpeg(
                            bin,
                            input.absolutePath,
                            partFile.absolutePath,
                            downsampleToHz = downsampleHz,
                            job = job,
                        )
                    if (inputIsTmp) runCatching { input.delete() }
                    if (rc == 0 && partFile.exists() && partFile.length() > 0L) {
                        runCatching { outFile.delete() }
                        val renamed = partFile.renameTo(outFile)
                        if (renamed) {
                            outFile.setLastModified(System.currentTimeMillis())
                            // 顺序很重要：先补索引、再校验、最后才置 success，
                            // 否则等待方可能在「还没补索引」时就把文件拿去播。
                            applySeekTable(outFile)
                            val seekable = FlacSeekTablePatcher.isValidSeekableFlac(outFile)
                            if (!seekable) {
                                AppLog.w(
                                    TAG,
                                    "转码产物不可 seek（索引缺失/校验失败），" +
                                        "本曲只能播放不能拖动: ${outFile.name}",
                                )
                            }
                            job.success.set(true)
                            AppLog.i(
                                TAG,
                                "转码成功: ${outFile.name} (${outFile.length() / 1024}KB, " +
                                    "seekable=$seekable, rc=$rc)",
                            )
                            trimCacheIfNeeded(cacheDir)
                        } else {
                            AppLog.e(TAG, "缓存改名失败: ${partFile.absolutePath} -> ${outFile.absolutePath}")
                            failedTranscodeKeys[key] = System.currentTimeMillis()
                            runCatching { partFile.delete() }
                        }
                    } else {
                        if (rc == CancelledExitCode) {
                            AppLog.i(TAG, "转码已取消，丢弃半成品: ${partFile.name}")
                        } else {
                            // [1.4.24] 记录失败终态：等待循环据此立即放弃，不再空等 20s 反复重试。
                            failedTranscodeKeys[key] = System.currentTimeMillis()
                            // 1.3.9「失败可解释」：一次日志就能看清 退出码 / 输入文件 / 容器识别 / 缓存快照，
                            // 不再只留一句「转码失败」让用户反复复现猜原因。
                            AppLog.e(
                                TAG,
                                "转码失败 rc=$rc: ${partFile.name}" +
                                    "\n  输入=${input.absolutePath} size=${input.length()}B dsd=$dsd" +
                                    "\n  容器/缓存快照=${describe(appCtx, uri)}" +
                                    "\n  ffmpeg 进程尾部日志见上一条 [ffmpeg 失败 rc=] 记录",
                            )
                        }
                        runCatching { partFile.delete() }
                    }
                } catch (error: Exception) {
                    AppLog.e(TAG, "转码线程异常: ${error.javaClass.simpleName} ${error.message}")
                    failedTranscodeKeys[key] = System.currentTimeMillis()
                    runCatching { partFile.delete() }
                } finally {
                    releaseTranscodeWakeLock(transcodeWakeLock)
                    progressTask.cancel(false)
                    TranscodeProgressBus.clear(key)
                    job.success.compareAndSet(true, true)
                    job.done.set(true)
                    runningJobs.remove(key, job)
                    transcodeLocks.remove(key)
                }
            }
            return job
        }
    }

    private fun cacheKey(context: Context, uri: Uri): String {
        val size = fileSize(context, uri)
        val raw = uri.toString() + " " + size
        return Integer.toHexString(raw.hashCode()) + "_" + size
    }

    private fun fileSize(context: Context, uri: Uri): Long = runCatching {
        when (uri.scheme) {
            "file" -> File(uri.path ?: return@runCatching 0L).length()
            "content" -> context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
            else -> 0L
        }
    }.getOrDefault(0L)

    /**
     * content:// 需要先落地为临时文件供 ffmpeg 按路径读取；file:// 直接用路径。
     *
     * 1.2.19：优先反查 MediaStore 的真实路径并直接读取——一首 DSD 动辄 200~300MB，
     * 复制进私有目录既慢又双倍占空间（这是「缓冲好久」的一半耗时来源）。
     * 拿不到路径（无「所有文件访问」权限）时再退回落地复制。
     */
    private fun prepareInput(context: Context, uri: Uri, key: String): File? = runCatching {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return@runCatching null)
            if (f.exists()) f else null
        } else {
            val direct = resolveRealPath(context, uri)
            if (direct != null) {
                AppLog.i(TAG, "直读原文件（免复制）: ${direct.absolutePath}")
                return@runCatching direct
            }
            val tmp = File(tmpDirOf(context), "in_" + key + ".tmp")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            }
            tmp
        }
    }.getOrNull()

    /** 反查 content://media/... 的真实文件路径；不可读/无权限时返回 null。 */
    private fun resolveRealPath(context: Context, uri: Uri): File? = runCatching {
        val path = context.contentResolver
            .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (path.isNullOrBlank()) return@runCatching null
        val file = File(path)
        if (file.exists() && file.canRead() && file.length() > 0L) file else null
    }.getOrNull()

    /** DSD 容器头魔数：DSF "DSD " / DSDIFF "FRM8"。 */
    private fun isDsdFile(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val head = ByteArray(4)
            if (ins.read(head) < 4) return false
            val tag = String(head, Charsets.US_ASCII)
            tag == "DSD " || tag == "FRM8"
        }
    }.getOrDefault(false)

    /**
     * 转码输出的体积估算。
     *
     * 1.2.28：旧版按 1.4 倍源文件估算（注释还写「DSD 约 1.3 倍」，与 88.2k 降采样后的
     * 事实完全不符）。日志里 212MB 的 DFF 被估成 284MB，实际产物只有 113MB；
     * 277MB → 143MB、256MB → 126MB、185MB → 91MB，稳定在 **0.5 倍**左右。
     * 高估一倍直接把「可用 434MB」误判成「不足 503MB」，触发了清空缓存的灾难分支。
     */
    private fun estimatedOutputBytes(sourceBytes: Long): Long =
        (sourceBytes * 6L / 10L).coerceAtLeast(8L * 1024 * 1024)

    private fun usableBytes(dir: File): Long = runCatching {
        val stat = android.os.StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)

    private fun runFfmpeg(
        bin: String,
        input: String,
        output: String,
        downsampleToHz: Int = 0,
        job: TranscodeJob? = null,
    ): Int {
        val resample = if (downsampleToHz > 0) arrayOf("-ar", downsampleToHz.toString()) else emptyArray()
        val cmd = arrayOf(
            bin, "-y", "-i", input,
            // [v12·MP3内嵌封面图转码失败根治] 大量 MP3 内嵌 mjpeg 专辑封面(Stream #0:1 attached pic)。
            // ffmpeg 转 FLAC 时若不禁视频流，会尝试为封面流选编码器，而本构建只编进 flac/pcm
            // 编码器(禁用 png/mjpeg)→ "Default encoder for format flac (codec png) is probably
            // disabled" → rc=8 "Encoder not found"，整首歌永远转码失败→无限缓冲。
            // -map 0:a:0 只取第一条音频流、-vn 丢弃所有封面/视频流，封面图对听歌无意义。
            "-map", "0:a:0", "-vn",
            // 显式 -f flac：不依赖输出扩展名推断（.part 临时名曾致推断失败）
            // compression_level 1：DSD/APE 一首数百 MB，编码耗时与体积都要压下来；
            // FLAC 是无损格式，压缩等级只影响体积与速度，不影响音质。
            "-c:a", "flac", "-compression_level", "1", *resample, "-f", "flac",
            output,
        )
        return execFfmpeg(cmd, input, job)
    }

    /**
     * 打碟兜底解码（1.2.23）：把 [uri] 的 [startMs]~[endMs] 区间用内置 ffmpeg 解码成裸 PCM
     * （s16le / 双声道 / [sampleRateHz]Hz），写入 [outFile]。供 [ScratchSoundController] 在
     * MediaCodec 不支持的格式（DSD/DSF/DFF、APE、DTS、TTA、WavPack、TAK、WMA…）上也能打碟出声。
     *
     * - 复用本对象的 ffmpeg 二进制发现、输入落地（content:// 反查真实路径/落地复制）逻辑，避免重复造轮子；
     * - 失败（ffmpeg 不可用 / 输入不可读 / 解码异常）返回 false，由调用方优雅降级（该格式打碟不出声，但不崩）。
     */
    internal fun decodeToRawPcm(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int,
        outFile: File,
    ): Boolean = runCatching {
        val appCtx = context.applicationContext
        ensureBinary(appCtx)
        val bin = ffmpegPath ?: run {
            AppLog.e(TAG, "ffmpeg 不可用，打碟 ffmpeg 兜底跳过（该格式打碟不出声）")
            return@runCatching false
        }
        // 1.2.29：日志里每次拖进度条都会出现一条
        // `ffmpeg -ss <目标秒> -t 30 -f wav scratch_pcm_*.raw.wav`，而且 -i 指向的是
        // 原始 200~300MB 的 DFF/APE。连续拖动时这些解码任务把 3 线程池占满
        // （日志 23:17:37 出现「ffmpeg 执行被中断」），主转码/DataSource open 被拖慢。
        // 就地取材：优先用已经转好、带正确索引的缓存 FLAC 当输入，FLAC 定位与解码都快得多。
        val key = cacheKey(appCtx, uri)
        val cached = File(cacheDirOf(appCtx), "$key.flac")
        // 1.3.4 / 问题 17：主转码尚未落地时不再直读 200MB 原始 DFF 另起一个 ffmpeg，
        // 先短暂等缓存就绪（最多 3s），避免两个 ffmpeg 抢线程池把播放器卡在 buffering。
        if (!cached.exists() || cached.length() <= 0L ||
            !FlacSeekTablePatcher.isValidSeekableFlac(cached)
        ) {
            repeat(10) {
                if (cached.exists() && cached.length() > 0L &&
                    FlacSeekTablePatcher.isValidSeekableFlac(cached)
                ) return@repeat
                Thread.sleep(300L)
            }
        }
        val input: File
        val inputFromCache: Boolean
        if (cached.exists() && cached.length() > 0L &&
            FlacSeekTablePatcher.isValidSeekableFlac(cached)
        ) {
            input = cached
            inputFromCache = true
        } else {
            input = prepareInput(
                appCtx,
                uri,
                "scratch_" + uri.toString().hashCode().toString(16),
            ) ?: run {
                AppLog.e(TAG, "打碟 ffmpeg 兜底：无法准备输入文件 uri=$uri")
                return@runCatching false
            }
            inputFromCache = false
        }
        AppLog.i(
            TAG,
            "打碟采样输入=${if (inputFromCache) "转码缓存FLAC" else "原始文件"}" +
                " file=${input.name} start=${startMs}ms dur=${endMs - startMs}ms",
        )
        val startSec = startMs / 1000.0
        val durSec = maxOf((endMs - startMs) / 1000.0, 0.1)
        // 1.2.24：内置 ffmpeg 只编译了 flac/wav 两个 muxer（--disable-muxers --enable-muxer='flac,wav'），
        // 旧的 `-f s16le` 裸 PCM 输出必然 rc=234 失败（打碟全程无声）。改为输出 WAV（muxer 可用）再剥离头部取 PCM。
        val wavFile = File(outFile.parentFile ?: appCtx.cacheDir, outFile.name + ".wav")
        val rc = runFfmpegRaw(bin, input.absolutePath, wavFile.absolutePath, startSec, durSec, sampleRateHz)
        // 只有「落地复制出来的临时输入」才能删；转码缓存 FLAC 位于 cacheDir 下，
        // 若沿用旧判断会被当成临时文件删掉，等于把正在播的缓存文件销毁。
        if (!inputFromCache && input.absolutePath.startsWith(appCtx.cacheDir.absolutePath)) {
            runCatching { input.delete() }
        }
        if (rc != 0) {
            runCatching { wavFile.delete() }
            return@runCatching false
        }
        val stripped = stripWavHeaderToPcm(wavFile, outFile)
        runCatching { wavFile.delete() }
        stripped && outFile.exists() && outFile.length() > 0L
    }.getOrDefault(false)

    /** ffmpeg 解码指定时间区间为 WAV（pcm_s16le / 双声道 / [rate]Hz），由调用方剥离头部取裸 PCM。 */
    private fun runFfmpegRaw(
        bin: String,
        input: String,
        output: String,
        startSec: Double,
        durSec: Double,
        rate: Int,
    ): Int {
        val cmd = arrayOf(
            bin, "-y",
            "-ss", String.format(Locale.US, "%.3f", startSec),
            "-i", input,
            // [v12] 同 runFfmpeg：丢弃内嵌封面图流，避免为 mjpeg 选编码器失败 rc=8。
            "-map", "0:a:0", "-vn",
            "-t", String.format(Locale.US, "%.3f", durSec),
            "-ac", "2", "-ar", rate.toString(),
            "-c:a", "pcm_s16le", "-f", "wav",
            output,
        )
        return execFfmpeg(cmd, input)
    }

    /**
     * 把 ffmpeg 输出的 WAV 去掉 RIFF/`fmt `/`data` 头，只留纯 PCM 写入 [pcmFile]。
     * 走通用 chunk 遍历（不假设固定 44 字节头），兼容 ffmpeg 写入的扩展 fmt 块。
     */
    private fun stripWavHeaderToPcm(wavFile: File, pcmFile: File): Boolean {
        return runCatching {
            if (!wavFile.exists() || wavFile.length() < 12L) return@runCatching false
            RandomAccessFile(wavFile, "r").use { raf ->
                val len = raf.length()
                val header = ByteArray(12)
                raf.readFully(header)
                if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte()
                ) {
                    return@use false
                }
                var pos = 12L
                val chunkHeader = ByteArray(8)
                while (pos + 8L <= len) {
                    raf.seek(pos)
                    raf.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val size =
                        (chunkHeader[4].toLong() and 0xFF) or
                            ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                            ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                            ((chunkHeader[7].toLong() and 0xFF) shl 24)
                    val dataStart = pos + 8L
                    if (id == "data") {
                        val dataLen = minOf(size, len - dataStart).coerceAtLeast(0L)
                        if (dataLen <= 0L) return@use false
                        raf.seek(dataStart)
                        pcmFile.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = dataLen
                            while (remaining > 0L) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val n = raf.read(buf, 0, toRead)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        return@use pcmFile.length() > 0L
                    }
                    // chunk 大小按 2 字节对齐后跳到下一个 chunk
                    pos = dataStart + size + (size and 1L)
                }
                false
            }
        }.getOrDefault(false)
    }

    /** 通用 ffmpeg 执行：启动进程、收集尾部日志、超时强杀、返回退出码。 */
    private fun execFfmpeg(cmd: Array<String>, input: String, job: TranscodeJob? = null): Int {
        return runCatching {
            AppLog.i(TAG, "ffmpeg 命令: " + cmd.joinToString(" "))
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            job?.attachProcess(proc)
            val tail = ArrayDeque<String>()
            proc.inputStream.bufferedReader().use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    tail.addLast(line!!)
                    if (tail.size > 40) tail.removeFirst()
                }
            }
            // 被取消时 destroy() 会让进程提前退出，这里统一按「取消」上报，不算转码失败
            if (job?.cancelRequested == true) {
                AppLog.i(TAG, "ffmpeg 因切歌被取消: $input")
                return -3
            }
            if (!proc.waitFor(TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                AppLog.e(TAG, "ffmpeg 执行超时，输入=" + input)
                return -2
            }
            val rc = proc.exitValue()
            if (rc != 0) {
                AppLog.e(TAG, "ffmpeg 失败 rc=" + rc + " 输入=" + input + "\n" + tail.joinToString("\n").takeLast(3000))
            } else {
                AppLog.i(TAG, "ffmpeg 退出 0，输出=" + cmd.last())
            }
            rc
        }.onFailure {
            // 关键：把启动/执行异常（如 W^X 的 SecurityException: exec permission denied）记入日志
            if (it is InterruptedException) {
                AppLog.w(TAG, "ffmpeg 执行被中断: $input")
            } else {
                AppLog.e(TAG, "ffmpeg 进程异常（start/exec 失败？）bin=" + cmd.firstOrNull() + " 输入=" + input, it)
            }
        }.getOrDefault(-1)
    }
}

/**
 * [DataSource.Factory] 层转码拦截（1.2.16 起，替代旧 ApeTranscodingMediaSourceFactory）。
 *
 * [DataSource.open] 运行在 **ExoPlayer 加载线程**：转码只发生在「即将播 / 30s 预载」的
 * 曲目上，播放队列装载与主线程完全不被阻塞（方案 B 治本）。转码完成后把 DataSpec 的
 * Uri 重定向到本地 FLAC 缓存，交回上层 [DataSource] 正常读取。
 */
@UnstableApi
internal class TranscodingDataSourceFactory(
    private val context: Context,
    private val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = TranscodingDataSource(context, upstream)
}

@UnstableApi
internal class TranscodingDataSource(
    private val context: Context,
    private val upstreamFactory: DataSource.Factory,
) : DataSource {

    /** 每次 open 新建一路独立上游；close 只关自己这一路，不影响下一次 open。 */
    @Volatile
    private var delegate: DataSource? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<TransferListener>()

    @Volatile
    private var lastEffectiveSpec: DataSpec? = null

    private val openSeq = java.util.concurrent.atomic.AtomicLong(0)

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        delegate?.addTransferListener(transferListener)
    }

    /**
     * 1.2.28：只做「把 Uri 换成已完成的 FLAC」，**不发布进度、不检查自定义取消标志**。
     *
     * 1.2.27 的两个致命竞态都在这里：
     * 1. `close()` 把 `delegate = null`，`read()` 兜底返回 `C.RESULT_END_OF_INPUT`。
     *    seek 时 Media3 cancel 当前加载并 close，读取线程恰好又调一次 read 就拿到 -1，
     *    FlacExtractor 认为容器提前结束 → `contentIsMalformed` →
     *    `ERROR_CODE_PARSING_CONTAINER_MALFORMED`。这就是「拖一下就播放失败」。
     * 2. `aborted` + `isCancelled` 让 seek 后的**新** open 也可能被判成取消，直接抛异常。
     *
     * 现在：取消只认「加载线程被中断」（Media3 的标准信号）；每次 open 用全新的上游
     * 实例，close 只关旧的，两路读取互不干扰。
     */
    override fun open(dataSpec: DataSpec): Long {
        val seq = openSeq.incrementAndGet()
        val uri = dataSpec.uri
        val plan = FlavorDecodeFrontend.plan(context, uri)
        com.smartisan.music.diagnostics.DecodeDiagnostics.trace(
            "route", "uri" to uri.toString().takeLast(48),
            "engine" to DecodeRouter.engineId, "plan" to plan.name,
        )

        // A 系统直读版：每首歌额外真调一次系统 MediaPlayer 起播判定（去重，写日志）。
        if (plan == DecodePlan.SYSTEM_DIRECT) {
            FlavorDecodeFrontend.probeOnOpen(context, uri)
        }

        // C 流式解码版：冷门格式走 ffmpeg 边解边喂的 PCM 管道，全程不写磁盘缓存文件。
        if (plan == DecodePlan.STREAM_PCM) {
            val result = FlavorDecodeFrontend.openStream(
                context, dataSpec, upstreamFactory, listeners.toList(),
            ) ?: throw java.io.IOException("流式 PCM 管道不可用（openStream 返回 null）")
            lastEffectiveSpec = dataSpec
            ApeTranscoder.recordLastOpen(
                seq, uri, dataSpec.uri, dataSpec.position, dataSpec.length,
            )
            AppLog.i(
                "ApeTranscoder",
                "DS open [流式PCM/不落盘]: ${uri.toString().takeLast(48)} plan=STREAM_PCM",
            )
            delegate = result.source
            return result.length
        }

        // 1.3.3：先记住这个 uri 是否属于「必须转码才能播」的格式。
        // 旧实现在 prepareForPlayback 返回 null（后台转码慢 / 加载线程被中断 / 转码失败）
        // 时把原始 DFF/APE 的 content:// 原样交给 Media3；Media3 嗅到 FRM8...DSD
        // 或 "MAC " 后没有任何 extractor 能读，直接报 3003
        // ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED —— 这正是用户反馈的「正在缓冲的歌
        // 挂后台，过一会说不支持的音乐格式」。这类格式**绝不回退原始 Uri**。
        val needs = ApeTranscoder.needsTranscode(context, uri)
        var target = ApeTranscoder.prepareForPlayback(context, uri)
        if (target == null && needs) {
            // [1.4.24] 记录本次等待起点：只认"本轮开始后才失败"的终态，避免上一轮残留误伤。
            val waitStartedMs = System.currentTimeMillis()
            val deadline =
                android.os.SystemClock.elapsedRealtime() +
                    ApeTranscoder.TranscodeReadyWaitTimeoutMs
            var waitedMs = 0L
            AppLog.w(
                "ApeTranscoder",
                "1.4.5(G-18) 转码缓存未就绪，等待后台任务落地（不回退原始 Uri，避免 3003 误报）: " +
                    "${uri.toString().takeLast(48)}\n  ${ApeTranscoder.describe(context, uri)}",
            )
            while (target == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (Thread.currentThread().isInterrupted) {
                    throw java.io.InterruptedIOException("加载已取消（等待转码缓存）: $uri")
                }
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("加载已取消（等待转码缓存）: $uri")
                }
                waitedMs += 200L
                target = ApeTranscoder.cachedTranscodeFile(context, uri)
                if (target == null) {
                    // [1.4.24] 转码任务已失败落地（ffmpeg rc≠0 / 线程异常）→ 立即放弃，
                    // 不再空等到 20s 超时。Media3 重试也会立刻失败上报，宏观上不再"一直缓冲"。
                    if (ApeTranscoder.transcodeFailedAfter(context, uri, waitStartedMs)) {
                        AppLog.e(
                            "ApeTranscoder",
                            "转码任务已失败，提前结束等待（${waitedMs}ms）: ${ApeTranscoder.describe(context, uri)}",
                        )
                        throw java.io.IOException("转码任务失败（非格式不支持）: $uri")
                    }
                    if (waitedMs % 5_000L == 0L) {
                        AppLog.i(
                            "ApeTranscoder",
                            "已等待转码缓存 ${waitedMs}ms: ${ApeTranscoder.describe(context, uri)}",
                        )
                    }
                }
            }
        }
        if (target == null && needs) {
            // 抛明确的 IO 错误，而不是让 Media3 把原始容器误判成「格式不支持」；
            // 日志里同时留下 describe() 的缓存诊断，能一眼看出是缓存没就绪。
            AppLog.e(
                "ApeTranscoder",
                "转码缓存始终未就绪，放弃本次 open（不是格式不支持）: " +
                    "${ApeTranscoder.describe(context, uri)}",
            )
            throw java.io.IOException("转码缓存未就绪（不是格式不支持）: $uri")
        }
        if (Thread.currentThread().isInterrupted) {
            throw java.io.InterruptedIOException("加载已取消: $uri")
        }
        val effectiveSpec =
            if (target != null) {
                dataSpec.buildUpon().setUri(Uri.fromFile(target)).build()
            } else {
                // 走到这里一定是「无需转码」的原生格式（MP3/FLAC/WAV…），回退是安全的。
                dataSpec
            }
        lastEffectiveSpec = effectiveSpec
        ApeTranscoder.recordLastOpen(
            seq, uri, effectiveSpec.uri, dataSpec.position, dataSpec.length,
        )
        AppLog.i(
            "ApeTranscoder",
            "DS open#$seq: ${uri.toString().takeLast(48)} -> " +
                "${effectiveSpec.uri.toString().takeLast(64)} " +
                "pos=${dataSpec.position} len=${dataSpec.length} " +
                "needsTranscode=$needs cacheReady=${target != null}",
        )
        val source = upstreamFactory.createDataSource()
        listeners.forEach { source.addTransferListener(it) }
        delegate = source
        return source.open(effectiveSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // 绝不能用 `?: C.RESULT_END_OF_INPUT` 兜底：那个 -1 会被 FlacExtractor 当成
        // 「文件正常读完」，把一次普通的 seek 取消伪造成容器损坏。
        val d = delegate
            ?: throw java.io.IOException("DataSource 未 open 就被 read")
        return d.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate?.uri ?: lastEffectiveSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        AppLog.i("ApeTranscoder", "DS close: actual=${lastEffectiveSpec?.uri?.toString()?.takeLast(64)}")
        // 只关这一路上游；保留引用，让被取消的加载线程读到的是「已关闭」而不是「EOF」。
        runCatching { delegate?.close() }
    }
}
