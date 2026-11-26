package com.smartisan.music.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import com.smartisan.music.data.settings.SmartCrossfadeStore
import com.smartisan.music.diagnostics.AppLog
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
            AppLog.i("BassXfade", "XFD ev=skip-disabled src=userSkip → 直接切（开关未开）")
            skipAction()
            return
        }
        // [1.4.28] BASS 真双曲混音已经接管时，包络必须完全让位：
        // 再淡出一次会把 mixer 的淡化曲线压成两段，切歌后新曲还会被从 0 音量重拉一遍。
        if (BassCrossfadeQueue.realMixActive()) {
            AppLog.i("BassXfade", "XFD ev=skip-real-mix src=userSkip → 真双曲混音接管，包络让位")
            cancel(player, "realMixActive")
            skipAction()
            return
        }
        // [R152·2026-11-26·用户最痛] 暂停 / 未就绪时不跑淡出，直接切。
        //
        // 旧实现无条件先跑 halfFadeMs（默认 3 秒）淡出，淡出完成才执行 skipAction()。
        // 这 3 秒里任何一次 cancel（暂停时 onPlayWhenReadyChanged、用户 seek、第二次点按）
        // 都会让协程 isActive=false，skipAction() 永不执行 → 点了下一首却卡住不切。
        // 日志铁证：所有 lastUser=onNextClick 的记录 op 都是"播放意图->播放/暂停"，
        // 没有一条真正的切歌 transition；而上一首（seekToPrevious 未套淡出）一直正常。
        if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
            cancel(player, "userSkipNotPlaying")
            if (player.volume <= 0f) player.volume = 1f
            AppLog.i("BassXfade", "用户切歌：非播放态(st=${player.playbackState} playing=${player.isPlaying})，跳过淡出直接切（R152）")
            skipAction()
            return
        }
        skipHandledByUserSkip = true
        cancel(player, "userSkipRestart")
        // 若恰逢「恢复播放淡入」把音量压在 0，先归一再淡出，避免切歌全程静音。
        if (player.volume <= 0f) player.volume = 1f
        fadeJob = scope.launch {
            val id = player.currentMediaItem?.mediaId ?: "-"
            AppLog.i("BassXfade", "XFD ev=fade-out-start chain=envelope src=userSkip id=$id ms=$halfFadeMs")
            fade(player, player.volume.coerceIn(0f, 1f), 0f, halfFadeMs)
            if (!currentCoroutineContext().isActive) {
                AppLog.i("BassXfade", "XFD ev=fade-out-abort chain=envelope src=userSkip id=$id")
                return@launch
            }
            AppLog.i("BassXfade", "XFD ev=fade-out-done chain=envelope src=userSkip id=$id → 切歌")
            skipAction()
            AppLog.i("BassXfade", "XFD ev=fade-in-start chain=envelope src=userSkip ms=$halfFadeMs")
            fade(player, 0f, 1f, halfFadeMs)
            AppLog.i("BassXfade", "XFD ev=fade-in-done chain=envelope src=userSkip")
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
            if (fadingOutMediaId != null) cancel(player, "tickRealMix")
            return
        }
        val item = player.currentMediaItem
        if (item == null) {
            if (fadingOutMediaId != null) cancel(player, "tickNoItem")
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
            if (!player.isPlaying || remaining > halfFadeMs || remaining <= 0L) {
                cancel(player, "tickWindowLeft")
            }
            return
        }
        if (fadeJob?.isActive == true || !player.isPlaying) return
        if (remaining <= 0L || remaining > halfFadeMs) return
        fadingOutMediaId = item.mediaId
        AppLog.i(
            "BassXfade",
            "XFD ev=fade-out-start chain=envelope src=autoEnd id=${item.mediaId}" +
                " remaining=$remaining ms=${remaining.coerceAtMost(halfFadeMs)}" +
                " dur=${player.duration} vol=${player.volume}",
        )
        fadeJob = scope.launch {
            fade(player, player.volume.coerceIn(0f, 1f), 0f, remaining.coerceAtMost(halfFadeMs))
            // 不主动切歌，交给播放器自然过渡到下一首。
            fadeJob = null
        }
    }

    /** 自动切歌发生（onMediaItemTransition AUTO）：若上一首走过淡出，则淡入新曲。 */
    fun onAutoTransition(player: Player) {
        if (!SmartCrossfadeStore.currentEnabled()) return
        // [1.4.33·验收第14点] 真混音已负责这一曲开头时，包络绝不二次淡入。
        // 原 realMixJustFinished() 只认"3 秒内 finish"，但用户拖进度条 A 曲被重新 open、
        // 真混音被 abort 后 A 曲继续播到结尾，切歌时早已超窗 → 包络误跑 fade-in，
        // 把真混音播过的 B 曲开头从 0 音量重拉 = "开头突然断掉"听感。
        // 用 isIntroOwnedByMediaId 精确按"这首曲的开头是否真混音负责"判断，直到消费掉。
        if (BassCrossfadeQueue.realMixJustFinished() ||
            BassCrossfadeQueue.isIntroOwnedByMediaId(player.currentMediaItem?.mediaId)
        ) {
            AppLog.i("BassXfade", "XFD ev=auto-transition-real-mix → 混音已从 0 淡入过，包络不重复拉")
            BassCrossfadeQueue.clearIntroOwner()
            return
        }
        if (skipHandledByUserSkip) skipHandledByUserSkip = false
        if (fadingOutMediaId == null) {
            AppLog.i("BassXfade", "XFD ev=auto-transition-skip 上一首没走淡出（末尾没到窗口），不做淡入")
            return
        }
        val fadedId = fadingOutMediaId
        fadingOutMediaId = null
        fadeJob?.cancel()
        AppLog.i(
            "BassXfade",
            "XFD ev=fade-in-start chain=envelope src=autoTransition from=$fadedId" +
                " to=${player.currentMediaItem?.mediaId ?: "-"} ms=$halfFadeMs",
        )
        fadeJob = scope.launch {
            player.volume = 0f
            fade(player, 0f, 1f, halfFadeMs)
            AppLog.i("BassXfade", "XFD ev=fade-in-done chain=envelope src=autoTransition")
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
        // [1.4.33] 追加 isIntroOwnedByMediaId：真混音被拖进度条 abort 后仍认得这首曲，
        // 补上 realMixJustFinished 时间窗失效的漏洞（详见 onAutoTransition 注释）。
        if (BassCrossfadeQueue.realMixJustFinished() ||
            BassCrossfadeQueue.isIntroOwnedByMediaId(newMediaId)
        ) {
            BassCrossfadeQueue.clearIntroOwner()
            return
        }
        if (skipHandledByUserSkip) {
            skipHandledByUserSkip = false
            return
        }
        if (newMediaId.isNullOrBlank() || newMediaId == lastFadedInMediaId) {
            AppLog.i(
                "BassXfade",
                "XFD ev=fade-in-skip src=transition id=$newMediaId" +
                    " reason=${if (newMediaId.isNullOrBlank()) "no-id" else "already-faded"}",
            )
            return
        }
        // 由 onAutoTransition 主导淡入时，fadingOutMediaId 已被清；避免与主动淡化叠加。
        if (fadeJob?.isActive == true) {
            AppLog.i("BassXfade", "XFD ev=fade-in-skip src=transition id=$newMediaId reason=fade-job-active")
            return
        }
        lastFadedInMediaId = newMediaId
        fadeJob?.cancel()
        AppLog.i(
            "BassXfade",
            "XFD ev=fade-in-start chain=envelope src=transition id=$newMediaId ms=$halfFadeMs",
        )
        fadeJob = scope.launch {
            player.volume = 0f
            fade(player, 0f, 1f, halfFadeMs)
            AppLog.i("BassXfade", "XFD ev=fade-in-done chain=envelope src=transition id=$newMediaId")
            fadeJob = null
        }
    }

    /** 取消当前的淡入/淡出并恢复满音量（暂停、引擎重建、关闭开关时调用）。 */
    fun cancel(player: Player?, reason: String = "unspecified") {
        val hadJob = fadeJob?.isActive == true
        fadeJob?.cancel()
        fadeJob = null
        val restored = player != null && player.volume < 1f
        fadingOutMediaId = null
        if (player != null && player.volume < 1f) player.volume = 1f
        // [1.4.30·R140] 取消是最容易"静音卡住"的路径：没这条日志就只能看到
        // "音量莫名变 1"或"莫名变 0"，查不出是谁取消的。
        if (hadJob || restored) {
            AppLog.i("BassXfade", "XFD ev=cancel reason=$reason hadJob=$hadJob restoredVol=$restored")
        }
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
