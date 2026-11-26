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
    private var rollbackFrames = 0

    fun accept(next: Long, currentMediaId: String?, seeking: Boolean): Long {
        if (seeking) {
            mediaId = currentMediaId
            floorMs = next
            rollbackFrames = 0
            return next
        }
        if (mediaId != currentMediaId) {
            mediaId = currentMediaId
            floorMs = 0L
            rollbackFrames = 0
        }
        // [1.4.32·验收13] 同一首歌自然播完后重播：服务端 seekToDefaultPosition() 把位置
        // 拉回 0，但 mediaId 没变、UI 侧也没进 seek 保护期，单调门会把 reported≈0 用满格
        // floorMs 顶住 → 「从头播了但进度条不动」。判据：真值比门低超过阈值且连续多帧
        // （排除后端偶发回吐一帧），认定为外部重置，把门 reseat 到真值。
        if (floorMs - next > RestartRollbackThresholdMs) {
            rollbackFrames++
            if (rollbackFrames >= RestartRollbackFrames) {
                floorMs = next
                rollbackFrames = 0
                return next
            }
        } else {
            rollbackFrames = 0
        }
        val value = if (next > floorMs) next else floorMs
        floorMs = value
        return value
    }

    fun reseat(value: Long) {
        floorMs = value
        rollbackFrames = 0
    }

    private companion object {
        /** 回退超过 2 秒才算「重置」而非抖动。 */
        const val RestartRollbackThresholdMs = 2_000L
        /** 连续 3 帧（约 0.3~3 秒）都回退才 reseat，避免偶发回吐误伤。 */
        const val RestartRollbackFrames = 3
    }
}
