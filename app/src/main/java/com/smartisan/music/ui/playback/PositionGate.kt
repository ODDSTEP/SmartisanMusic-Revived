package com.smartisan.music.ui.playback

/**
 * 1.3.4 / 问题 13：进度条显示位置单调门。
 * 后端状态快照偶尔会回吐一个比上一帧更小的 currentPositionMs（转码就绪、
 * 队列重建、异步 seek 落地时），进度条就会「自己往回跳一下」。
 * 这里保证同一首歌、非 seek 期间显示位置只前进不后退；切歌时自动归零，
 * seek 落地时用 [reseat] 把基准放到新位置（所以手动往回拖依然正常）。
 */
internal class PositionGate {
    private var mediaId: String? = null
    private var floorMs = 0L

    fun accept(next: Long, currentMediaId: String?, seeking: Boolean): Long {
        if (seeking) {
            mediaId = currentMediaId
            floorMs = next
            return next
        }
        if (mediaId != currentMediaId) {
            mediaId = currentMediaId
            floorMs = 0L
        }
        val value = if (next > floorMs) next else floorMs
        floorMs = value
        return value
    }

    fun reseat(value: Long) {
        floorMs = value
    }
}
