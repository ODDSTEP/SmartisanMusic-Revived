package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog

/**
 * [1.4.30·R183①] 切歌聚合帧（`TR`）的"当前曲目 + 解码路径"粘合层。
 *
 * 为什么需要它：ExoPlayer 的解码在 `onMediaItemTransition` **之后**才开始。
 * 若把 `dec=`（解码器路径）硬塞进 transition 那一帧，只能拿到上一首的路径，等于撒谎。
 * 所以拆成两帧、同一 `id=` 可 grep 对齐：
 *
 * ```
 * TR id=123 dec=PENDING src=24/96000/4608000 ext=flac reason=AUTO ... sink 快照
 * TR id=123 dec=FFMPEG_S16 src=24/96000/4608000 ext=flac reason=AUTO ... sink 快照
 * ```
 *
 * 没有第二条就说明"这首根本没走自研解码"（ExoPlayer 原生直读），本身就是结论，
 * 不需要再编一个 `dec=NATIVE` 去猜。
 */
internal object DecodeRoute {

    @Volatile
    private var mediaId: String = "-"

    @Volatile
    private var src: String = "-"

    @Volatile
    private var reason: String = "-"

    /** transition 时登记本首的 id / 源摘要 / 切歌原因，并打第一帧（dec=PENDING）。 */
    fun begin(id: String, srcSummary: String, transitionReason: String) {
        mediaId = id
        src = srcSummary
        reason = transitionReason
        emit("PENDING", "-")
    }

    /** 解码链路确定时打第二帧；`uriTail` 只取末尾若干字符，避免日志被长路径撑爆。 */
    fun record(path: String, uriTail: String = "-") {
        emit(path, uriTail)
    }

    /** 停止播放时清空，避免下一条无关日志带上旧 id。 */
    fun reset() {
        mediaId = "-"
        src = "-"
        reason = "-"
    }

    private fun emit(path: String, uriTail: String) {
        val usb =
            runCatching { UsbExclusiveSinkRef.current?.heartbeat() }.getOrNull() ?: "route=SHARED"
        val tag =
            buildString {
                append("TR id=").append(mediaId)
                append(" dec=").append(path)
                append(" src=").append(src)
                append(" reason=").append(reason)
                if (uriTail != "-" && uriTail.isNotEmpty()) append(" uri=…").append(uriTail)
                append(' ').append(usb)
            }
        AppLog.i("TR", tag)
    }
}
