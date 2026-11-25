package com.smartisan.music.diagnostics

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import com.smartisan.music.BuildConfig
import com.smartisan.music.playback.DecodeRouter
import com.smartisan.music.playback.FlavorDecodeFrontend
import java.io.File

/**
 * 1.4.0 诊断层：三个包并排装在同一台手机上，导出的日志必须能一眼分清
 * 是哪个 App、哪条解码链、卡在哪一步。四件事：
 *  1. [sessionHeader] —— 会话头补引擎标识 / 包名 / 能力边界；
 *  2. [trace]          —— 播放链路每一跳一帧结构化 `Decode{...}`；
 *  3. [selfCheck]      —— 启动自检（ffmpeg / 系统解码器 / 容器 sniff / 各版内核真实调用）；
 *  4. [advise] / [snapshot] —— 错误码人话 + 诊断快照。
 *
 * 1.3.9 的「libVLC 反射探测 / KernelProbe 三内核探测」是纯日志空壳，已随表一删除。
 */
internal object DecodeDiagnostics {

    private const val TAG = "Decode"

    fun trace(stage: String, vararg fields: Pair<String, Any?>) {
        AppLog.state(
            TAG,
            op = "解码链路",
            source = stage,
            fields =
                buildMap {
                    put("engine", DecodeRouter.engineId)
                    fields.forEach { (k, v) -> put(k, v) }
                }.toSortedMap(),
        )
    }

    fun sessionHeader(context: Context) {
        AppLog.i(
            TAG,
            "==== 引擎会话头 ====\n" +
                "engine=${DecodeRouter.engineId} label=${DecodeRouter.engineLabel}\n" +
                "summary=${DecodeRouter.engineSummary}\n" +
                "capability=${DecodeRouter.capabilityNote}\n" +
                "pkg=${context.packageName} version=${BuildConfig.ENGINE_LABEL}\n" +
                "fallbackOnUnsupported=${DecodeRouter.fallbackOnUnsupported}" +
                " directReadFirst=${DecodeRouter.directReadFirst}",
        )
        selfCheck(context)
    }

    fun selfCheck(context: Context) {
        Thread({
            runCatching {
                val app = context.applicationContext
                val nativeDir = app.applicationInfo.nativeLibraryDir
                val ff = File(nativeDir, "libffmpegbin.so")
                AppLog.i(
                    TAG,
                    "SelfCheck/ffmpeg: exists=${ff.exists()} canExec=${ff.canExecute()}" +
                        " size=${ff.length()} path=${ff.absolutePath}",
                )
                // 各版真实调用自己的内核一次（A 真起播判定 / B 查 ffmpeg 底座 / C 探管道）。
                AppLog.i(TAG, "SelfCheck/frontend: ${FlavorDecodeFrontend.selfProbe(app, null)}")
                val codecs =
                    runCatching {
                        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                            .filter { !it.isEncoder }
                            .mapNotNull { info ->
                                val types = runCatching { info.supportedTypes }.getOrDefault(emptyArray())
                                val audio = types.filter { it.startsWith("audio/") }
                                if (audio.isEmpty()) null else "${info.name}{${audio.joinToString(",")}}"
                            }
                    }.getOrDefault(emptyList())
                AppLog.i(TAG, "SelfCheck/audioDecoders(${codecs.size}): ${codecs.joinToString(" | ").take(2000)}")
                AppLog.i(TAG, "SelfCheck/containerSniff: ${sniffContainerSupport()}")
                AppLog.i(
                    TAG,
                    "SelfCheck/device: ${Build.MANUFACTURER} ${Build.MODEL}" +
                        " sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
                )
            }.onFailure { AppLog.e(TAG, "SelfCheck 异常", it) }
        }, "decode-selfcheck").apply { isDaemon = true }.start()
    }

    /** MediaExtractor 对代表性冷门魔数的支持度探测：只查能力、不解码、不落盘。 */
    private fun sniffContainerSupport(): String {
        val probes =
            mapOf(
                "DSF" to byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), ' '.code.toByte()),
                "DFF" to byteArrayOf('F'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), '8'.code.toByte()),
                "APE" to "MAC ".toByteArray(),
            )
        return probes.entries.joinToString("; ") { (name, head) ->
            val f = runCatching {
                File.createTempFile("probe_$name", ".bin").apply {
                    writeBytes(head + ByteArray(60))
                    deleteOnExit()
                }
            }.getOrNull() ?: return@joinToString "$name=tempFail"
            val outcome = runCatching {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(f.absolutePath)
                    "$name=tracks${ex.trackCount}"
                } finally {
                    runCatching { ex.release() }
                }
            }.getOrElse {
                val m = it.message.orEmpty()
                when {
                    m.contains("Unsupported", true) || m.contains("format", true) -> "$name=unsupported"
                    else -> "$name=noFrames(${it.javaClass.simpleName})"
                }
            }
            runCatching { f.delete() }
            outcome
        }
    }

    fun advise(code: Int, uri: Uri?, fallbackTriggered: Boolean): String {
        val ext = uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val base =
            when (code) {
                3003 -> "PARSING_CONTAINER_UNSUPPORTED(3003)：系统 extractor 不认该容器（多为 DFF/DSF/APE）"
                3001 -> "PARSING_CONTAINER_MALFORMED(3001)：容器解析中途损坏（常见于 seek 取消 / SEEKTABLE 写坏）"
                3002, 3004 -> "解码格式不支持或超出能力：系统/软件解码器没有对应 codec"
                1003 -> "TIMEOUT：起播超时（多为 App 解码缓存/管道没就绪或 UAC 流停滞）"
                else -> "错误码 $code"
            }
        val hint =
            when {
                fallbackTriggered -> "已触发 App 解码自动回退；若仍失败，看 ffmpeg 日志（bin 可执行 / 输入能否读取）"
                DecodeRouter.fallbackOnUnsupported -> "本引擎允许 3003 自动回退，正常应由 onPlayerError 触发"
                else -> "本引擎不自动回退（系统直读/流式管道），属预期：系统认不出就是本版要验的结论"
            }
        return "$base | format=$ext | engine=${DecodeRouter.engineId} | $hint"
    }

    fun snapshot(context: Context): String =
        buildString {
            appendLine("引擎=${DecodeRouter.engineId} / ${DecodeRouter.engineLabel}")
            appendLine("策略=${DecodeRouter.engineSummary}")
            appendLine("能力边界=${DecodeRouter.capabilityNote}")
            appendLine("包名=${context.packageName}")
            appendLine("3003自动回退=${DecodeRouter.fallbackOnUnsupported}")
            appendLine("直读优先=${DecodeRouter.directReadFirst}")
            appendLine("已强制转码回退的曲目数=${DecodeRouter.forceTranscodeCount}")
            appendLine("版本标识=${BuildConfig.ENGINE_LABEL}")
            appendLine("说明：完整逐跳链路见日志中的 Decode{...} 帧，用 grep \"Decode{\" 串成时间线。")
        }
}
