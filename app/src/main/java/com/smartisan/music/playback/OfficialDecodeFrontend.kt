package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.smartisan.music.diagnostics.AppLog

/**
 * B 正式版（official）·双路切换 —— 只编进「锤子音乐」正式版 APK。
 *
 * 路线分派（plan）：
 *  - USB 独占：恒 TRANSCODE_FLAC。UAC 直出必须 App 自己解码拿 PCM 直灌 DAC，
 *    这是表三第 1 项 bit-perfect 的既定事实，与引擎无关。
 *  - 冷门格式（DFF/DSF/DSD/APE/TTA/WavPack/TAK/WMA/DTS…）：TRANSCODE_FLAC。
 *    系统 extractor 认不出这些容器，直接丢给系统只会 3003；走内置 ffmpeg 无损
 *    FLAC 转码缓存，保住「默认设置、不开任何开关也能播冷门格式」（表三第 10 项底座）。
 *  - 常见格式（MP3/FLAC/WAV/AAC/OGG…）：SYSTEM_DIRECT —— 原始 Uri 直接交系统
 *    解码链，不转码、不落盘、不碰 PCM，零缓存点开即播。
 *  - 3003 回退标记命中：强制 TRANSCODE_FLAC。
 *
 * 这就是「双路」：系统直读（常见格式，零缓存） / App 自解码（冷门格式与独占，全功能）。
 * 两条路共用 Media3 会话层，表三 11 项真功能一项不落。
 */
internal object OfficialDecodeFrontend : DecodeFrontend {

    override val engineSummary =
        "B正式版·双路切换：常见格式原始Uri直读(零缓存点开即播)；冷门格式/USB独占回App自解码(ffmpeg→无损FLAC缓存，表三功能全保)"

    override val capabilityNote =
        "表三11项真功能全部可用：USB独占+bit-perfect、EQ/BassBoost、PCM域DSP、爆豆音/唱针、64bit浮点WAV、FLAC seek table、音质排序、DSD过滤、色彩主题、ffmpeg转码底座、播放队列/会话/日志。"

    override val fallbackOnUnsupported = true
    override val directReadFirst = false

    override fun plan(context: Context, uri: Uri): DecodePlan {
        if (DecodeRouter.isForceTranscode(uri)) return DecodePlan.TRANSCODE_FLAC
        // USB 独占 / 冷门格式维持既有稳定的 TRANSCODE_FLAC 路径不动（DSD 怪声已在 USB sink
        // 的 negotiateUsbRate 层根治），避免动最脆弱的独占/DSD 地基引入回归。
        if (PlaybackPolicy.usbExclusiveActive) return DecodePlan.TRANSCODE_FLAC
        if (ApeTranscoder.detectColdFormat(context, uri)) return DecodePlan.TRANSCODE_FLAC
        // [1.4.23·闪退根治] 分层解码范围收窄：v4 起系统 sink（PcmDspAudioProcessor）已覆盖
        // 所有格式的全部常规 DSP（EQ/压限/音乐厅/V3/声道平衡/ReplayGain/增益监测），这些
        // 效果在系统直读下就能生效，绝不再把它们赶去 STREAM_PCM。只有 BASS 专有、系统
        // sink 实现不了的「倒放/变速」与「智能过渡真交叉淡化」才需要 BASS 流式解码。
        //
        // 关键：高解析（≥96kHz）或 DSD 转码曲即使开了倒放/变速/过渡，也禁止走 BASS——
        // BASS 流式管道处理 192kHz/24bit/超高码率时 native 崩溃（真机反复闪退铁证）。
        // 这些曲目的倒放/变速在此回退为无效（保留播放不崩），常规音效仍全部生效。
        val hiResOrDsd = isHiResOrDsd(context, uri)
        val needBass = !hiResOrDsd &&
            (com.smartisan.music.data.settings.SmartCrossfadeStore.currentEnabled() ||
                PcmDsp.needsPcmReverseOrSpeed())
        if (needBass && BassEngine.isReady) {
            AppLog.i("Decode", "分层解码：需 BASS 专有能力(过渡/倒放/变速)，主流格式走 BASS 流式解码 uri=${uri.toString().takeLast(40)}")
            return DecodePlan.STREAM_PCM
        }
        if (hiResOrDsd && (com.smartisan.music.data.settings.SmartCrossfadeStore.currentEnabled() ||
                PcmDsp.needsPcmReverseOrSpeed())) {
            AppLog.i("Decode", "高解析/DSD 跳过 BASS(防native闪退)，回系统直读 uri=${uri.toString().takeLast(40)}")
        }
        return DecodePlan.SYSTEM_DIRECT
    }

    /**
     * [1.4.23·闪退根治]  Uri 是否为高解析（采样率≥96kHz）或 DSD 系格式。
     * 这类曲目禁止走 STREAM_PCM（BASS 流式管道处理高码率会 native 崩）。
     * 优先读 FormatCache 已探测的采样率；读不到时按扩展名兜底判 DSD，
     * 并保守认为常见 FLAC/WAV 可能是高解析（交给上层 decide 时已确认 safe）。
     */
    internal fun isHiResOrDsd(context: Context, uri: Uri): Boolean {
        val seg = uri.lastPathSegment?.lowercase().orEmpty()
        val ext = seg.substringAfterLast('.', "")
        if (ext in setOf("dsf", "dff", "dsd", "dst")) return true
        // 从 FormatCache 读已探测采样率（探测在媒体库扫描时完成，零额外开销）
        val probe = runCatching {
            com.smartisan.music.platform.media.FormatCache.get(uri.toString(), 0L)
        }.getOrNull()
        if (probe != null && probe.sampleRateHz >= 96000) return true
        return false
    }

    /**
     * [1.4.25·BASS native 闪退根治] 该 Uri 是否禁止进入 BASS native 解码链。
     *
     * BASS 的 libbassape / libbassdsd 插件在解 APE/DSD 及 ≥96kHz 高码率时稳定 SIGSEGV
     * （真机反复闪退；Java try/catch 抓不到 native 信号）。凡是"需要 App 自解码"的冷门
     * 格式，一律不许走 BASS，必须走 ffmpeg 流式/转码管道。
     *
     * 判定口径（满足任一即为 unsafe）：
     *  1. DSD 系扩展名 dsf/dff/dsd/dst，或采样率 ≥96kHz（复用 [isHiResOrDsd]）；
     *  2. APE 系扩展名 ape/apl/mac（libbassape 崩溃源）；
     *  3. ApeTranscoder 认定的其它冷格式（DTS/TTA/WavPack/TAK/WMA/…）——这些系统也认不出，
     *     BASS 未必有对应插件，统一走 ffmpeg 最安全。
     *
     * 只要 detectColdFormat=true 就视为 unsafe：需要 App 解码的格式，ffmpeg 都能处理，
     * 而 BASS 插件覆盖不全且高码率必崩，因此统一走 ffmpeg。
     */
    internal fun isBassUnsafe(context: Context, uri: Uri): Boolean {
        if (isHiResOrDsd(context, uri)) return true
        return com.smartisan.music.playback.ApeTranscoder.detectColdFormat(context, uri)
    }

    /**
     * [1.4.20·拍板1] official 引擎的流式取流实现：BASS 优先、ffmpeg 兜底。
     * 仅当 [plan] 返回 STREAM_PCM（智能过渡开 / 冷门格式 / 独占）时被调用。
     */
    override fun openStream(
        context: Context,
        dataSpec: DataSpec,
        upstreamFactory: androidx.media3.datasource.DataSource.Factory,
        listeners: List<androidx.media3.datasource.TransferListener>,
    ): StreamResult? {
        // [1.4.26·R79] 解码主次对调：ffmpeg 主、BASS 兜底。
        // 旧实现 BASS 优先，只要 BASS 初始化成功就先走 native；而 BASS 插件在
        // APE/DSD/≥96kHz 上稳定 SIGSEGV，Java 抓不到 native 信号，表现就是整机闪退。
        // ffmpeg 是独立子进程，崩了也只是本曲失败、不影响播放服务；格式覆盖还更全。
        //
        // [1.4.26·R79-b 修正] 但「ffmpeg 一律优先」会把 BASS 专有能力弄失效：
        // plan() 判定需要交叉淡化/倒放/变速时返回 STREAM_PCM，若 openStream 仍先开
        // ffmpeg，ffmpeg 一次成功就再也不会落到 BASS——用户开了智能过渡却听不到淡化。
        // 所以按「本首是否需要 BASS 专有能力」分两种主次，其余一律 ffmpeg 优先。
        val needsBassCapability = BassEngine.isReady && !isBassUnsafe(context, dataSpec.uri) &&
            (com.smartisan.music.data.settings.SmartCrossfadeStore.currentEnabled() ||
                PcmDsp.needsPcmReverseOrSpeed())
        if (needsBassCapability) {
            val bass = BassPcmDataSource(context, dataSpec.uri)
            listeners.forEach { bass.addTransferListener(it) }
            try {
                val length = bass.open(dataSpec)
                AppLog.i(
                    "Decode",
                    "BASS 专有能力解码生效（交叉淡化/倒放/变速）uri=${dataSpec.uri.toString().takeLast(40)}",
                )
                return StreamResult(bass, length)
            } catch (t: Throwable) {
                AppLog.w("Decode", "BASS 专有能力解码失败，回退 ffmpeg: ${t.message}")
                runCatching { bass.close() }
            }
        }
        val source = StreamingPcmDataSource(context, dataSpec.uri)
        listeners.forEach { source.addTransferListener(it) }
        var ffmpegFailure: Throwable? = null
        try {
            val length = source.open(dataSpec)
            // [1.4.26·R79] 显式 DSP 所有权：ffmpeg 只解码，DSP 交给 PcmDspAudioProcessor。
            PcmDsp.dspOwner = PcmDsp.DspOwner.PCM
            AppLog.i("Decode", "ffmpeg 解码链路生效（主路径，DSP 归自研 Processor）uri=${dataSpec.uri.toString().takeLast(40)}")
            return StreamResult(source, length)
        } catch (t: Throwable) {
            ffmpegFailure = t
            AppLog.w("Decode", "ffmpeg 解码失败，尝试 BASS 兜底: ${t.message}")
            runCatching { source.close() }
        }
        // BASS 只对"安全格式"兜底：DSD/APE/高码率一律不许进 native（isBassUnsafe）。
        if (BassEngine.isReady && !isBassUnsafe(context, dataSpec.uri)) {
            val bass = BassPcmDataSource(context, dataSpec.uri)
            listeners.forEach { bass.addTransferListener(it) }
            try {
                val length = bass.open(dataSpec)
                AppLog.i("Decode", "BASS 兜底解码链路生效 uri=${dataSpec.uri.toString().takeLast(40)}")
                return StreamResult(bass, length)
            } catch (t: Throwable) {
                AppLog.w("Decode", "BASS 兜底也失败: ${t.message}")
                runCatching { bass.close() }
            }
        }
        // 两条路都失败：把 ffmpeg 的真实失败抛给上层，不让用户只看到"无法播放"四个字。
        throw java.io.IOException(
            "ffmpeg 与 BASS 均无法解码 uri=${dataSpec.uri.toString().takeLast(48)}: ${ffmpegFailure?.message}",
            ffmpegFailure,
        )
    }

    override fun selfProbe(context: Context, uri: Uri?): String {
        val bin = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpegbin.so")
        val ff = if (bin.exists() && bin.canExecute()) "就位(size=${bin.length()})" else "缺失"
        return "B正式版自检: 会话层=Media3/ExoPlayer(表三功能全保), ffmpeg自解码底座=$ff, 常见格式=系统直读零缓存"
    }
}
