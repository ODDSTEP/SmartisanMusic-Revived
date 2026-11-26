package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.smartisan.music.diagnostics.AppLog

/**
 * C 测试版（stream）·流式 PCM 管道 —— 只编进「锤子音乐·流式解码」APK。
 *
 * 与 B 正式版的**唯一但真实**差别：冷门格式不再「ffmpeg 整首转成 FLAC 文件落盘」，
 * 改为 ffmpeg 从 stdin 吃原始字节、把解码后的 PCM(WAV) 写到 stdout，App 建一个
 * [StreamingPcmDataSource] 边解边喂给 Media3，全程**不写任何磁盘缓存文件**。
 *
 * 路线分派（plan）：
 *  - 冷门格式 / USB 独占 → STREAM_PCM（不落盘）；
 *  - 常见格式 → SYSTEM_DIRECT（原始 Uri 直读，零缓存）；
 *  - 3003 回退标记 → STREAM_PCM（本版不自动回退，集合通常为空）。
 *
 * 如实边界（不粉饰）：流式 WAV 的长度字段未知，Media3 的 WavExtractor 能否在
 * 不可 seek 的管道上稳定解析、seek 时的竞态能否压住，沙箱无真机验不了。本版只保证
 * 真实调用 ffmpeg 管道 + 逐阶段日志（缓冲字节数/首帧/进程退出码/seek 重开）+ 失败明确，
 * 不宣称「已验证能播」。1.2.27 曾因 seek 竞态删掉该管道，这次作为独立测试版重新做出来验。
 */
internal object StreamDecodeFrontend : DecodeFrontend {

    override val engineSummary =
        "C测试版·流式PCM: 冷门格式由ffmpeg边解边喂PCM(不写任何磁盘缓存文件); 常见格式原始Uri直读零缓存; 逐阶段日志写明缓冲与seek竞态"

    override val capabilityNote =
        "本版验证「不落盘的流式解码能不能压住seek竞态」。如实边界: 流式WAV长度未知, Media3在不可seek管道上的解析与seek竞态沙箱无真机验不了, 只保证真实调用+逐阶段日志+失败明确, 不宣称已验证能播。USB独占bit-perfect在此版未验证; 表三全功能以B正式版为准。"

    override val fallbackOnUnsupported = false
    override val directReadFirst = false

    override fun plan(context: Context, uri: Uri): DecodePlan {
        if (DecodeRouter.isForceTranscode(uri)) return DecodePlan.STREAM_PCM
        if (PlaybackPolicy.usbExclusiveActive) return DecodePlan.STREAM_PCM
        return if (ApeTranscoder.detectColdFormat(context, uri)) {
            // [1.4.25·STREAM闪退+DSD不支持双修] 之前把高解析/DSD 踢回 SYSTEM_DIRECT 防闪退，
            // 但系统 extractor 认不出 DSD/APE → 3003「不支持的音乐格式」，等于用"不支持"
            // 代替闪退。正解：冷门格式一律走 STREAM_PCM（ffmpeg 流式管道），在 openStream 内
            // 用 isBassUnsafe 闸门跳过 BASS native、只走 ffmpeg——既不闪退也能播。
            DecodePlan.STREAM_PCM
        } else {
            DecodePlan.SYSTEM_DIRECT
        }
    }

    override fun openStream(
        context: Context,
        dataSpec: DataSpec,
        upstreamFactory: DataSource.Factory,
        listeners: List<androidx.media3.datasource.TransferListener>,
    ): StreamResult? {
        // [1.4.20] BASS 优先（DSD/APE/FLAC 插件解码、保真 88.2k、BASS_FX EQ/混响），
        // 失败一律回退既有 ffmpeg 管道，绝不因新引擎让用户没声音。
        // [1.4.25] 二次防护：凡 isBassUnsafe（DSD/APE/高解析/冷格式）一律跳过 BASS native，
        // 直接走 ffmpeg 流式管道——libbassape/libbassdsd 解这些格式会 SIGSEGV 闪退。
        val bassUnsafe = OfficialDecodeFrontend.isBassUnsafe(context, dataSpec.uri)
        if (!bassUnsafe && BassEngine.isReady) {
            val bass = BassPcmDataSource(context, dataSpec.uri)
            listeners.forEach { bass.addTransferListener(it) }
            try {
                val length = bass.open(dataSpec)
                AppLog.i("Bass", "BASS 解码链路生效（流式引擎）uri=${dataSpec.uri.toString().takeLast(40)}")
                DecodeRoute.record("BASS_FLOAT32_STREAM", dataSpec.uri.toString().takeLast(40))
                return StreamResult(bass, length)
            } catch (t: Throwable) {
                AppLog.w("Bass", "BASS 解码失败，回退 ffmpeg: ${t.message}")
                runCatching { bass.close() }
            }
        }
        val source = StreamingPcmDataSource(context, dataSpec.uri)
        listeners.forEach { source.addTransferListener(it) }
        val length = source.open(dataSpec)
        DecodeRoute.record("FFMPEG_S16_STREAM", dataSpec.uri.toString().takeLast(40))
        return StreamResult(source, length)
    }

    override fun selfProbe(context: Context, uri: Uri?): String {
        val bin = java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpegbin.so")
        return "C流式版自检: 管道=ffmpeg(stdin原始字节→stdout PCM/WAV, 不落盘), " +
            "二进制=" + (if (bin.exists() && bin.canExecute()) "就位(size=${bin.length()})" else "缺失") +
            "; 常见格式仍走系统直读零缓存; seek竞态待实机验证"
    }
}
