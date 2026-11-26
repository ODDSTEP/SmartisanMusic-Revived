@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 1.4.0 缓冲策略 [LoadControl]：删除 1.3.9 遗留的「极速播放 instant」死分支
 * （`instantPlayback` 恒 false，那套 120ms 起播的 DefaultLoadControl 永不生效）。
 *
 * 保留真保护 [relaxedForTranscode]（表三第 10 项）：当前曲目需要 App 解码
 * （B 版冷门格式转码 / C 版流式管道）时，切到更宽松的缓冲阈值，避免后台/USB 独占下
 * CPU 一抖就凑不够缓冲、触发 ExoPlayer 10 秒无进度看门狗（ERROR_CODE_TIMEOUT/1003）。
 *
 * 两套 [DefaultLoadControl] 共用同一个 [DefaultAllocator]（ExoPlayer 只认
 * getAllocator() 返回的那一个，换实例会让内存池对不上直接崩）。生命周期回调两套都转发。
 */
internal class TunableLoadControl(
    private val sharedAllocator: DefaultAllocator,
) : LoadControl {

    private val normal: DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(sharedAllocator)
            .setTargetBufferBytes(TargetBufferBytes)
            .build()

    private val relaxed: DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(sharedAllocator)
            .setTargetBufferBytes(TargetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBufferDurationsMs(
                /* minBufferMs = */ 4000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1000,
                /* bufferForPlaybackAfterRebufferMs = */ 2000,
            )
            .build()

    /** 当前曲目是否需要 App 解码（转码/流式），由 PlaybackService 按切歌事件设置。 */
    @Volatile
    var relaxedForTranscode: Boolean = false

    private val active: DefaultLoadControl
        get() = if (relaxedForTranscode) relaxed else normal

    override fun onPrepared(playerId: PlayerId) {
        normal.onPrepared(playerId)
        relaxed.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) {
        normal.onTracksSelected(parameters, trackGroups, trackSelections)
        relaxed.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped(playerId: PlayerId) {
        normal.onStopped(playerId)
        relaxed.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        normal.onReleased(playerId)
        relaxed.onReleased(playerId)
    }

    override fun getAllocator(playerId: PlayerId): Allocator = sharedAllocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        active.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        active.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        active.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        active.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean =
        active.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
    ): Boolean = bufferedDurationUs < MaxBufferUs

    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetBufferBytes: Long,
    ): Boolean {
        val threshold = if (rebuffering) RebufferUs else StartUs
        return bufferedDurationUs >= threshold
    }

    private companion object {
        const val TargetBufferBytes = 4 * 1024 * 1024
        const val StartUs = 2_500_000L
        const val RebufferUs = 5_000_000L
        const val MaxBufferUs = 50_000_000L
    }
}
