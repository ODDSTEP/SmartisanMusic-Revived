@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * 1.4.0 三条播放路线的解码前端契约。
 *
 * main 只依赖本接口；三个 flavor 在各自 `src/<flavor>/java` 里提供同名
 * object [FlavorDecodeFrontend]，由 AGP 在编译期链接进各自的 APK —— 三份**真实不同**
 * 的实现代码，而不是 1.3.9 那种「共用一份 main + BuildConfig 挂标签」。
 *
 * 三条路线共用 Media3 会话层（保住表三 11 项真功能），真实差异落在
 * 「曲目字节流由谁产生」这一层。
 */
internal enum class DecodePlan {
    /** 原始 Uri 直接交系统解码链，App 不转码、不落盘、不碰 PCM（零缓存点开即播）。 */
    SYSTEM_DIRECT,
    /** App 自解码：内置 ffmpeg 无损转 FLAC 缓存（表三第 10 项底座，保全部 PCM 域功能）。 */
    TRANSCODE_FLAC,
    /** App 自解码：ffmpeg 边解边喂 PCM，不写任何输出缓存文件（流式管道，实验）。 */
    STREAM_PCM,
}

/** 流式取流结果：一个已 open 的 [DataSource] 与它返回的字节长度。 */
internal class StreamResult(val source: DataSource, val length: Long)

internal interface DecodeFrontend {
    /** 一句话策略，写进日志会话头与诊断快照。 */
    val engineSummary: String

    /** 如实写清本版能力边界（哪些表三功能可用 / 不可用），供交付文档与会话头引用。 */
    val capabilityNote: String

    /** 冷门格式系统不认时是否自动回退到 App 解码。 */
    val fallbackOnUnsupported: Boolean

    /** 是否以系统直读为首选（会话头诊断字段）。 */
    val directReadFirst: Boolean

    /** 决定 [uri] 这一首的字节流由谁产生。三版真实不同。 */
    fun plan(context: Context, uri: Uri): DecodePlan

    /** 会话头自检：各版真实调用自己的内核一次，返回人话结论（写进日志）。 */
    fun selfProbe(context: Context, uri: Uri?): String

    /** A 版专用：系统直读每首歌的真实起播判定（去重），返回日志行；其余版本返回 null。 */
    fun probeOnOpen(context: Context, uri: Uri): String? = null

    /** C 版专用：流式 PCM 管道取流；其余版本返回 null。 */
    fun openStream(
        context: Context,
        dataSpec: DataSpec,
        upstreamFactory: DataSource.Factory,
        listeners: List<androidx.media3.datasource.TransferListener>,
    ): StreamResult? = null
}
