package com.smartisan.music.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import com.smartisan.music.data.settings.SmartCrossfadeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [1.4.19·S07] 智能过渡（自动混音）控制器 —— 方案B：音量包络淡入淡出。
 *
 * 单 Player 无法同时播两首歌做真双曲叠加。这里用现有 `player.volume`（Media3 的 PCM/系统
 * 增益，工程里 [PlaybackStartFadeController] 已验证可用）在切歌前施加淡出、切歌后施加淡入，
 * 听感是平滑过渡；不碰 DSP 核心、不影响稳定性。默认关闭，开了才生效。
 *
 * 只作用于「用户主动下一曲」与「自动播到结尾切下一曲」：
 *  - 主动：由 [PlaybackStartFadePlayer.seekToNext] 调用 [crossfadeOnUserSkip]；
 *  - 自动：由进度 ticker [onProgressTick] 提前淡出 + [onAutoTransition] 切歌后淡入。
 * seek / 暂停 / 点选单曲不经过这些入口；且在暂停、seek 拉回、引擎重建时会 [cancel] 恢复音量，
 * 不会卡在半淡出状态。
 */
internal class SmartCrossfadeController(
    private val scope: CoroutineScope,
) {
    private var fadeJob: Job? = null
    /** 自动淡出针对的 mediaId；非空表示「上一首已淡出」，用于切歌后判定是否需要淡入。 */
    private var fadingOutMediaId: String? = null

    /** [1.4.23] 最近一次已做过淡入的 mediaId，避免同一首被重复淡入。 */
    private var lastFadedInMediaId: String? = null
    /** [1.4.23] 由 seekToNext 主动淡化接管，onTransitionFadeIn 此帧跳过，避免双重淡入。 */
    @Volatile private var skipHandledByUserSkip = false

    /** 淡出/淡入各自的时长基准（毫秒）= 配置秒数的一半。 */
    private val halfFadeMs: Long
        get() = SmartCrossfadeStore.currentDurationSec() * 500L

    /** 用户主动切下一曲：淡出当前曲 → 切歌 → 淡入下一曲。 */
    fun crossfadeOnUserSkip(player: Player, skipAction: () -> Unit) {
        if (!SmartCrossfadeStore.currentEnabled()) {
            skipAction()
            return
        }
        // [1.4.28] BASS 真双曲混音已经接管时，包络必须完全让位：
        // 再淡出一次会把 mixer 的淡化曲线压成两段，切歌后新曲还会被从 0 音量重拉一遍。
        if (BassCrossfadeQueue.realMixActive()) {
            cancel(player)
            skipAction()
            return
        }
        skipHandledByUserSkip = true
        cancel(player)
        // 若恰逢「恢复播放淡入」把音量压在 0，先归一再淡出，避免切歌全程静音。
        if (player.volume <= 0f) player.volume = 1f
        fadeJob = scope.launch {
            fade(player, player.volume.coerceIn(0f, 1f), 0f, halfFadeMs)
            if (!currentCoroutineContext().isActive) return@launch
            skipAction()
            fade(player, 0f, 1f, halfFadeMs)
            fadeJob = null
        }
    }

    /**
     * 播放进度 ticker（每 250ms）：歌曲临近结尾（剩余 ≤ 淡出基准）时自动淡出。
     * 若用户在淡出途中把进度拉回或暂停，则撤销淡出并恢复音量，不留下半截静音。
     */
    fun onProgressTick(player: Player) {
        if (!SmartCrossfadeStore.currentEnabled()) return
        // [1.4.28] 真双曲混音进行中：淡出由 BassCrossfadeMixer 负责，这里不再压音量。
        if (BassCrossfadeQueue.realMixActive()) {
            if (fadingOutMediaId != null) cancel(player)
            return
        }
        val item = player.currentMediaItem
        if (item == null) {
            if (fadingOutMediaId != null) cancel(player)
            return
        }
        val duration = player.duration
        val remaining = if (duration > 0L && duration != C.TIME_UNSET) {
            duration - player.currentPosition
        } else {
            Long.MAX_VALUE
        }
        if (fadingOutMediaId == item.mediaId) {
            // 已淡出过：进度被拉回窗口外或已暂停 → 撤销
            if (!player.isPlaying || remaining > halfFadeMs || remaining <= 0L) cancel(player)
            return
        }
        if (fadeJob?.isActive == true || !player.isPlaying) return
        if (remaining <= 0L || remaining > halfFadeMs) return
        fadingOutMediaId = item.mediaId
        fadeJob = scope.launch {
            fade(player, player.volume.coerceIn(0f, 1f), 0f, remaining.coerceAtMost(halfFadeMs))
            // 不主动切歌，交给播放器自然过渡到下一首。
            fadeJob = null
        }
    }

    /** 自动切歌发生（onMediaItemTransition AUTO）：若上一首走过淡出，则淡入新曲。 */
    fun onAutoTransition(player: Player) {
        if (!SmartCrossfadeStore.currentEnabled()) return
        if (BassCrossfadeQueue.realMixJustFinished()) return
        if (skipHandledByUserSkip) skipHandledByUserSkip = false
        if (fadingOutMediaId == null) return
        fadingOutMediaId = null
        fadeJob?.cancel()
        fadeJob = scope.launch {
            player.volume = 0f
            fade(player, 0f, 1f, halfFadeMs)
            fadeJob = null
        }
    }

    /**
     * [1.4.23·过渡生效兜底] 任何真实换歌（含 PLAYLIST_CHANGED / SEEK 等非 AUTO reason）都补一次淡入。
     *
     * 真机日志铁证：声音库的切歌 reason 几乎全是 PLAYLIST_CHANGED / SEEK，AUTO 极少，
     * 而旧逻辑只在 AUTO 时淡入 → 用户开了过渡却"跟没开一样"。这里对所有换歌兜底淡入，
     * 并做三重去重：① seekToNext 已主动淡化的跳过；② 同一 mediaId 不重复；③ 首曲不淡入。
     */
    fun onTransitionFadeIn(player: Player, newMediaId: String?) {
        if (!SmartCrossfadeStore.currentEnabled()) return
        // [1.4.28] 刚刚结束真混音：下一曲开头已经由 mixer 从 0 淡入并播过一段，
        // 这里再 player.volume=0 重拉一遍会让新曲开头音量掉下去再爬起来。
        if (BassCrossfadeQueue.realMixJustFinished()) return
        if (skipHandledByUserSkip) {
            skipHandledByUserSkip = false
            return
        }
        if (newMediaId.isNullOrBlank() || newMediaId == lastFadedInMediaId) return
        // 由 onAutoTransition 主导淡入时，fadingOutMediaId 已被清；避免与主动淡化叠加。
        if (fadeJob?.isActive == true) return
        lastFadedInMediaId = newMediaId
        fadeJob?.cancel()
        fadeJob = scope.launch {
            player.volume = 0f
            fade(player, 0f, 1f, halfFadeMs)
            fadeJob = null
        }
    }

    /** 取消当前的淡入/淡出并恢复满音量（暂停、引擎重建、关闭开关时调用）。 */
    fun cancel(player: Player?) {
        fadeJob?.cancel()
        fadeJob = null
        fadingOutMediaId = null
        if (player != null && player.volume < 1f) player.volume = 1f
    }

    private suspend fun fade(
        player: Player,
        from: Float,
        to: Float,
        durationMs: Long,
    ) {
        val steps = 18
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        repeat(steps) { step ->
            if (!currentCoroutineContext().isActive) return
            val frac = (step + 1).toFloat() / steps
            player.volume = (from + (to - from) * frac).coerceIn(0f, 1f)
            delay(stepDelay)
        }
        if (currentCoroutineContext().isActive) player.volume = to
    }
}
