package com.smartisan.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放次数追踪（1.2.20 重写：**累计式记账**）。
 *
 * 旧实现（1.2.16~1.2.19）维护单个「当前 ActivePlayback」对象，依赖
 * transition/onEvents 事件对齐；队列重建、UI 刷新、seek 抖动都会把它
 * 重置或清空，实测一首播了 2.5 分钟的歌一次都没计上（用户日志实测）。
 *
 * 新实现只做一件事：**按 mediaId 累计真实播放时长，永不丢账**——
 * - 周期 tick（5s）：player.isPlaying 时把 delta 加进「按 mediaId」的累计表；
 * - 任一 mediaId 的累计时长达到阈值（30s 或歌曲一半取小）→ 立刻计数一次；
 * - 换歌/队列重建/进程内任何事件都**不会清零**已累计时长；
 * - 心跳日志：每 60s 输出当前累计，方便日志诊断（1.2.19 的教训）。
 */
internal class PlaybackPlayCountTracker(
    private val player: Player,
    private val repository: PlaybackStatsRepository,
    private val scope: CoroutineScope,
    private val onPlayCountChanged: () -> Unit,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {

    /** mediaId → 已累计的真实播放毫秒数（跨 transition/队列重建保留）。 */
    private val accruedMsByMediaId = ConcurrentHashMap<String, Long>()

    /** mediaId → 是否已计数（每进程每首歌只计一次）。 */
    private val countedMediaIds = ConcurrentHashMap.newKeySet<String>()

    /** mediaId → 歌曲时长（用于阈值），条目缺失时用 30s 默认阈值。 */
    private val durationMsByMediaId = ConcurrentHashMap<String, Long>()

    private var lastClockMs: Long? = null
    private var wasPlaying = false
    private var started = false
    private var accrualJob: Job? = null
    private var heartbeatCount = 0

    /**
     * 当前这一轮「播放实例」已经即时计过数的 mediaId。用于保证一首歌在一次连续播放里
     * 只即时 +1 一次；换歌 / 重播会让该值失效，从而再次点击播放时重新 +1。
     */
    private var lastImmediateCountedActiveMediaId: String? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
            ) {
                syncPlaybackClock()
                rememberDuration(player.currentMediaItem)
                if (player.isPlaying) {
                    markPlayStart()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncPlaybackClock()
            rememberDuration(mediaItem)
            // 进入新歌（或同一首歌被重播）都重置即时计数守卫，使本次播放重新 +1
            lastImmediateCountedActiveMediaId = null
            if (player.isPlaying) {
                markPlayStart()
            }
        }
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        rememberDuration(player.currentMediaItem)
        wasPlaying = player.isPlaying
        lastClockMs = elapsedRealtime().takeIf { wasPlaying }
        if (wasPlaying) {
            markPlayStart()
        }
        player.addListener(listener)
        accrualJob =
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(AccrualIntervalMs)
                    try {
                        accrueAndCount()
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "播放计数累计异常: ${t.message}")
                    }
                }
            }
    }

    suspend fun stopAndFlush() {
        // [1.4.29·R127] 先摘监听再做最后一次结账：切引擎时 player 马上要 release，
        // 尽早停止收事件，避免结账过程中又被回调改状态。
        player.removeListener(listener)
        syncPlaybackClock()
        // 1.2.26：这里是「服务即将销毁」的最后一次落库，必须**真正写完**。
        // 旧版走 countAllEligible() → launchCount() → scope.launch(IO)，
        // 紧接着 onDestroy 的 serviceScope.cancel() 会把这个 IO 协程直接掐掉，
        // 于是最后一首的播放次数与最后 30s 时长悄悄丢掉（runBlocking 只是等了个寂寞）。
        flushCountsSynchronously()
        accrualJob?.cancel()
        accrualJob = null
        started = false
        wasPlaying = false
        lastClockMs = null
        lastImmediateCountedActiveMediaId = null
    }

    /** 串行写完所有达标项（不并发，保证 onDestroy 的 runBlocking 真的等到结果）。 */
    private suspend fun flushCountsSynchronously() {
        val pending =
            accruedMsByMediaId.entries.filter { (mediaId, accrued) ->
                mediaId !in countedMediaIds && accrued >= thresholdOf(mediaId)
            }
        for ((mediaId, accrued) in pending) {
            countedMediaIds += mediaId
            val updated =
                runCatching { repository.incrementPlayCount(mediaId) }
                    .onFailure {
                        countedMediaIds -= mediaId
                        AppLog.w(TAG, "退出前写入播放次数失败 mediaId=$mediaId: ${it.message}")
                    }
                    .getOrNull()
            AppLog.i(TAG, "退出前补记播放次数 mediaId=$mediaId 时长=${accrued}ms → count=$updated")
        }
    }

    /** 每个 tick：把 isPlaying 的时长记到当前 mediaId 名下，再把所有达标者计数。 */
    private fun accrueAndCount() {
        syncPlaybackClock()
        rememberDuration(player.currentMediaItem)
        countAllEligible()
        if (++heartbeatCount % HeartbeatTicks == 0 && player.isPlaying) {
            val current = player.currentMediaItem?.mediaId
            AppLog.d(
                TAG,
                "累计心跳: current=$current accrued=${current?.let { accruedMsByMediaId[it] }}ms " +
                    "counted=${current?.let { countedMediaIds.contains(it) }}",
            )
        }
    }

    private fun syncPlaybackClock() {
        val now = elapsedRealtime()
        val mediaId = player.currentMediaItem?.mediaId?.trim().takeUnless { it.isNullOrEmpty() }
        if (wasPlaying && mediaId != null) {
            val last = lastClockMs
            if (last != null) {
                val delta = (now - last).coerceAtLeast(0L)
                // 时长异常大（进程被冻结后恢复）时不记账，防止虚增
                if (delta <= AccrualIntervalMs * 4L) {
                    accruedMsByMediaId.merge(mediaId, delta, Long::plus)
                }
            }
        }
        wasPlaying = player.isPlaying
        lastClockMs = now.takeIf { wasPlaying }
    }

    private fun rememberDuration(mediaItem: MediaItem?) {
        val resolved = mediaItem?.takeIf { it.mediaId.isNotBlank() } ?: return
        val mediaId = resolved.mediaId.trim()
        if (durationMsByMediaId.containsKey(mediaId)) return
        val durationMs = resolved.playbackDurationMs()
        if (durationMs > 0L) {
            durationMsByMediaId[mediaId] = durationMs
        }
    }

    private fun countAllEligible(notifyAfterWrite: Boolean = true) {
        for ((mediaId, accrued) in accruedMsByMediaId) {
            if (mediaId !in countedMediaIds && accrued >= thresholdOf(mediaId)) {
                countedMediaIds += mediaId
                launchCount(mediaId, accrued, notifyAfterWrite)
            }
        }
    }

    /**
     * 点击播放即 +1：当前 mediaId 正在播放、且这一轮播放实例还没即时计过数时，立刻写入一次
     * 播放计数（不再等 30s 累计阈值）。与累计路径共用 [countedMediaIds] 去重，防止重复 +1。
     */
    private fun markPlayStart() {
        val mediaId = player.currentMediaItem?.mediaId?.trim()?.takeUnless { it.isEmpty() }
            ?: return
        if (!player.isPlaying) return
        if (mediaId == lastImmediateCountedActiveMediaId) return
        lastImmediateCountedActiveMediaId = mediaId
        countedMediaIds += mediaId
        launchCount(mediaId, 0L, notifyAfterWrite = true)
    }

    private fun thresholdOf(mediaId: String): Long {
        val duration = durationMsByMediaId[mediaId] ?: return PlaybackCountThresholdMs
        return if (duration > 0L) {
            minOf(PlaybackCountThresholdMs, maxOf(PlaybackCountMinimumThresholdMs, duration / 2L))
        } else {
            PlaybackCountThresholdMs
        }
    }

    private fun launchCount(mediaId: String, accruedMs: Long, notifyAfterWrite: Boolean) {
        scope.launch(Dispatchers.IO) {
            val updated = runCatching { repository.incrementPlayCount(mediaId) }
                .onFailure {
                    // 计数失败允许下次重试
                    countedMediaIds -= mediaId
                    AppLog.w(TAG, "播放次数写入失败 mediaId=$mediaId: ${it.message}")
                }
                .getOrNull()
            AppLog.i(TAG, "计入播放次数 mediaId=$mediaId 时长=${accruedMs}ms → count=$updated")
            if (updated != null && notifyAfterWrite) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    onPlayCountChanged()
                }
            }
        }
    }

    private fun MediaItem.playbackDurationMs(): Long {
        val metadataDurationMs = mediaMetadata.durationMs ?: C.TIME_UNSET
        return metadataDurationMs.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    private companion object {
        private const val TAG = "PlayCount"
        private const val PlaybackCountThresholdMs = 30_000L
        private const val PlaybackCountMinimumThresholdMs = 1_000L
        private const val AccrualIntervalMs = 5_000L
        private const val HeartbeatTicks = 12
    }
}
