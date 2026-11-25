@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.AudioAttributes as M3AudioAttributes
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.smartisan.music.diagnostics.AppLog
import kotlin.random.Random

/**
 * A 测试版（direct）专用：真·系统 MediaPlayer 出声。
 *
 * 把 [android.media.MediaPlayer] 包装成 Media3 的 [Player] 接口，交给现有
 * MediaLibrarySession（会话层完全不动）。默认链就是系统 MediaPlayer 出声：
 * setDataSource → prepareAsync → start，点开即播、零缓存、不落盘、不碰 PCM。
 * 这才是与参考 APK 同款的播放核心——不是 1.4.0 那种「旁路探测 + 主链仍是
 * Media3 extractor」的名不副实。
 *
 * 逐阶段日志（tag=DirectEngine）：setDataSource / prepare / start / isPlaying /
 * duration / seek / completion / error。失败抛明确 PlaybackException，不静默吞。
 *
 * 1.4.3 三项修复（只动 A 包，B/C 是 ExoPlayer 原生链，不碰）：
 *  - 【随机播放】自建洗牌索引表：自动接下一首、大播放页「下一首/上一首」、
 *    歌曲页「随机播放」入口全部走乱序。1.4.2 的 handleCompletion 硬编码 +1、
 *    handleSetShuffleModeEnabled 只存布尔，是「永远顺序播放」的代码级根因。
 *  - 【误报弹窗】onError 只挂起待 onPrepared 复核，不立刻 setError + invalidateState，
 *    能播就完全静默；真失败由超时兜底只提交一次错误（真只弹一次）。
 *  - 【崩溃防线】[护栏 G-09·台账登记] playerError 仍只在 STATE_IDLE 上报，保留 1.4.2 的修复，
 *    防 "Player error only allowed in STATE_IDLE" 崩溃（A 版曾点任意歌曲必闪退）。
 *  - 【误报弹窗】[护栏 G-10·台账登记] onError 挂起待复核、onPrepared 成功即静默撤销，
 *    防「A 频繁误弹不支持的音乐格式，但歌其实能播」。
 *
 * 如实边界：MediaPlayer 对 DFF/APE 的 seek、无缝切歌、长播，沙箱无真机验不了；
 * 本类只保证真实调用 + 可判定日志 + 失败明确，不宣称「已验证全功能」。
 * A 版系统 MediaPlayer 不向 App 暴露 PCM，故 USB 独占 bit-perfect / 自研 DSP /
 * 64bit 浮点 WAV 在本版默认链不可用（甲路线：开关禁用 + 如实标注，请用 B/C）。
 */
internal class MediaPlayerPlayer(
    private val appContext: Context,
    private val looper: Looper,
    private val media3AudioAttributes: M3AudioAttributes,
) : SimpleBasePlayer(looper) {

    private val androidAudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(media3AudioAttributes.usage)
        .setContentType(media3AudioAttributes.contentType)
        .build()

    /** 1.4.3-B：跑在播放器 looper 上，负责 onError 挂起后的「复核超时」兜底。 */
    private val errorHandler = Handler(looper)

    private var mediaPlayer: MediaPlayer? = null
    private var playlist: MutableList<MediaItemData> = mutableListOf()
    private var currentMediaItemIndex: Int = C.INDEX_UNSET
    private var playWhenReady: Boolean = false
    private var playbackState: Int = Player.STATE_IDLE
    private var repeatMode: Int = Player.REPEAT_MODE_OFF
    private var shuffleModeEnabled: Boolean = false
    private var volume: Float = 1f
    private var preparedDurationMs: Long = C.TIME_UNSET
    private var pendingSeekPositionMs: Long = C.TIME_UNSET
    private var playerError: PlaybackException? = null
    private var uidCounter: Long = 0L

    /**
     * 1.4.3-A：真洗牌索引表（playlist 下标的乱序序列）+ 当前游标。
     * SimpleBasePlayer 不像 ExoPlayer 有 DefaultShuffleOrder，setPlaylist 出去的仍是
     * 原始队列顺序；这里只改「下一首播哪个 index」，队列与 UI 列表保持一致，
     * 避免改 playlist 顺序引发 PLAYLIST_CHANGED / 连跳（1.2.30 教训）。
     */
    private var shuffleOrder: IntArray = intArrayOf()
    private var shuffleCursor: Int = -1

    /**
     * 1.4.3-B：onError 挂起待复核。系统 MediaPlayer 在 setDataSource / prepare 阶段
     * 常甩一次非致命 onError(what=-38)，随后又正常 onPrepared。1.4.2 在这里立即
     * setError + invalidateState，上层先弹「不支持的音乐格式」、随后又被 onPrepared
     * 撤销错误，就是实机「频繁弹误报」的根因。现在只暂存、绝不触发 UI；
     * onPrepared 成功即彻底丢弃（完全静默），真失败则由 [scheduleErrorCommit]
     * 超时兜底提交一次。
     */
    private var pendingError: PlaybackException? = null
    private var errorCommitRunnable: Runnable? = null

    // ------------------------------------------------------------------ getState

    override fun getState(): State {
        val mediaPlayer = mediaPlayer
        val contentPositionSupplier = if (mediaPlayer != null) {
            PositionSupplier {
                runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L)
            }
        } else {
            PositionSupplier.ZERO
        }
        val durationMs = if (preparedDurationMs == C.TIME_UNSET) {
            mediaPlayer?.let { runCatching { it.duration.toLong() }.getOrNull() } ?: C.TIME_UNSET
        } else {
            preparedDurationMs
        }
        val bufferedSupplier = PositionSupplier.getConstant(if (durationMs == C.TIME_UNSET) 0L else durationMs)

        // SimpleBasePlayer.State 硬约束：playerError 只允许与 STATE_IDLE 共存，
        // 否则 State.build() 抛 "Player error only allowed in STATE_IDLE" 崩主线程
        // （1.4.1-a 实机「点任意歌曲必闪退」根因）。这里做兜底防线：无论内部
        // 状态如何被迟到回调打乱，都只在 IDLE 挂 error，绝不让 error 与 READY 共存。
        val effectiveError = if (playbackState == Player.STATE_IDLE) playerError else null
        return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(effectiveError)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setVolume(volume)
            .setAudioAttributes(media3AudioAttributes)
            .setAudioSessionId(mediaPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(
                if (currentMediaItemIndex == C.INDEX_UNSET || playlist.isEmpty()) {
                    C.INDEX_UNSET
                } else {
                    currentMediaItemIndex.coerceIn(0, playlist.size - 1)
                },
            )
            .setContentPositionMs(contentPositionSupplier)
            .setContentBufferedPositionMs(bufferedSupplier)
            .setTotalBufferedDurationMs(bufferedSupplier)
            .build()
    }

    override fun getPlaceholderMediaItemData(mediaItem: MediaItem): MediaItemData {
        val uid = newUid()
        return MediaItemData.Builder(uid)
            .setMediaItem(mediaItem)
            .setIsPlaceholder(true)
            .setDurationUs(C.TIME_UNSET)
            .setIsSeekable(false)
            .build()
    }

    private fun newUid(): Long = uidCounter++

    // ------------------------------------------------------------------ handlers

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        val mediaPlayer = mediaPlayer
        if (mediaPlayer != null && playbackState == Player.STATE_READY) {
            if (playWhenReady) {
                runCatching { mediaPlayer.start() }
                    .onSuccess { AppLog.i(TAG, "start() 已调用 isPlaying=${mediaPlayer.isPlaying} uri=${currentUri()}") }
                    .onFailure { setError("start 失败", it) }
            } else {
                runCatching { mediaPlayer.pause() }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (playlist.isEmpty() || currentMediaItemIndex == C.INDEX_UNSET) {
            playbackState = Player.STATE_ENDED
            return Futures.immediateVoidFuture()
        }
        val mediaPlayer = mediaPlayer
        if (mediaPlayer != null && playbackState == Player.STATE_READY) {
            return Futures.immediateVoidFuture()
        }
        playbackState = Player.STATE_BUFFERING
        openCurrentAndPrepare()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        cancelPendingError()
        playbackState = Player.STATE_IDLE
        runCatching { mediaPlayer?.stop() }
        preparedDurationMs = C.TIME_UNSET
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        cancelPendingError()
        playbackState = Player.STATE_IDLE
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        releaseMediaPlayerQuietly()
        cancelPendingError()
        playerError = null
        preparedDurationMs = C.TIME_UNSET
        playlist = mediaItems.map { item ->
            MediaItemData.Builder(newUid())
                .setMediaItem(item)
                .setIsPlaceholder(false)
                .setDurationUs(C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }.toMutableList()
        currentMediaItemIndex = if (playlist.isEmpty()) C.INDEX_UNSET else startIndex.coerceIn(0, playlist.size - 1)
        pendingSeekPositionMs = if (startPositionMs > 0L) startPositionMs else C.TIME_UNSET
        playbackState = if (playlist.isEmpty()) Player.STATE_ENDED else Player.STATE_IDLE
        // 1.4.3-A：队列重建后，若当前处于随机模式，按新队列重建洗牌表。
        // 起点（歌曲页「随机播放」选中的那首，或用户点中的那首）作为锚点放首位，
        // 其余乱序，保证「点随机播放」第一首就不是原队列第 0 首。
        if (shuffleModeEnabled) {
            buildShuffleOrder(currentMediaItemIndex)
        } else {
            shuffleOrder = intArrayOf()
            shuffleCursor = -1
        }
        if (playlist.isNotEmpty()) {
            AppLog.i(TAG, "setMediaItems count=${playlist.size} startIndex=$currentMediaItemIndex positionMs=$startPositionMs shuffle=$shuffleModeEnabled orderSize=${shuffleOrder.size}")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        // 1.4.3-A：随机模式下「下一首 / 上一首」按洗牌表推进，不再用队列自然顺序。
        // 用户直接点队列里某一首（任意 index）仍是指定跳转，走下面原逻辑。
        if (shuffleModeEnabled && shuffleOrder.isNotEmpty()) {
            val delta = classifyShuffleAdvance(mediaItemIndex, seekCommand)
            if (delta != null) {
                val nextIndex = advanceShuffle(delta)
                if (nextIndex == null || nextIndex !in playlist.indices) {
                    AppLog.i(TAG, "shuffle 边界，保持当前曲目 delta=$delta cursor=$shuffleCursor/${shuffleOrder.size}")
                    return Futures.immediateVoidFuture()
                }
                cancelPendingError()
                currentMediaItemIndex = nextIndex
                pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
                releaseMediaPlayerQuietly()
                playbackState = Player.STATE_BUFFERING
                openCurrentAndPrepare()
                AppLog.i(TAG, "shuffle 切曲目 delta=$delta index=$nextIndex cursor=$shuffleCursor/${shuffleOrder.size} seekCommand=$seekCommand")
                return Futures.immediateVoidFuture()
            }
        }
        if (mediaItemIndex != currentMediaItemIndex) {
            cancelPendingError()
            currentMediaItemIndex = mediaItemIndex.coerceIn(0, playlist.size - 1)
            pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
            releaseMediaPlayerQuietly()
            playbackState = Player.STATE_BUFFERING
            openCurrentAndPrepare()
            AppLog.i(TAG, "seek 切曲目 index=$mediaItemIndex positionMs=$positionMs")
            return Futures.immediateVoidFuture()
        }
        val mediaPlayer = mediaPlayer
        if (mediaPlayer != null && playbackState == Player.STATE_READY) {
            runCatching { mediaPlayer.seekTo(positionMs.coerceAtLeast(0L).toInt()) }
                .onSuccess { AppLog.i(TAG, "seekTo positionMs=$positionMs") }
                .onFailure { setError("seek 失败", it) }
        } else {
            pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        this.shuffleModeEnabled = shuffleModeEnabled
        // 1.4.3-A：大播放页随机按钮 / 歌曲页随机播放行都会走到这里。
        // 开启即按「当前曲目为锚」重建一张新乱序表（切关再开也是新顺序），
        // 关闭则清空。此后自动接歌与 next/prev 全走 [advanceShuffle]。
        if (shuffleModeEnabled) {
            buildShuffleOrder(currentMediaItemIndex.coerceAtLeast(0))
        } else {
            shuffleOrder = intArrayOf()
            shuffleCursor = -1
        }
        AppLog.i(TAG, "setShuffleModeEnabled=$shuffleModeEnabled orderSize=${shuffleOrder.size} cursor=$shuffleCursor")
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        runCatching { mediaPlayer?.setVolume(volume, volume) }
        return Futures.immediateVoidFuture()
    }

    // ------------------------------------------------------------------ 内部

    private fun openCurrentAndPrepare() {
        // 每开一个新源都先清掉上一首遗留的「挂起错误」，避免误报复核串台。
        cancelPendingError()
        if (playlist.isEmpty() || currentMediaItemIndex == C.INDEX_UNSET) {
            playbackState = Player.STATE_ENDED
            return
        }
        val item = playlist[currentMediaItemIndex].mediaItem
        val uri = item.localConfiguration?.uri
        if (uri == null) {
            setError("无可用播放地址", IllegalStateException("localConfiguration.uri == null"))
            return
        }
        val mediaPlayer = MediaPlayer().also { mp ->
            mp.setAudioAttributes(androidAudioAttributes)
            mp.setOnPreparedListener { prepared ->
                // 只忽略「已被后续切歌替换/释放的旧实例」的迟到回调。
                if (prepared !== this.mediaPlayer) {
                    AppLog.w(TAG, "忽略旧实例迟到的 onPrepared uri=${currentUri()}")
                    invalidateState()
                    return@setOnPreparedListener
                }
                // 当前实例 prepare 成功 = 该源可播的铁证。若此前同轮挂起过一次
                // 非致命 onError(what=-38)，以 onPrepared 为准彻底丢弃错误、硬保播放，
                // 全程不触发 UI（完全静默），不让「本可以播的歌」被误判成不支持。
                if (pendingError != null || playerError != null) {
                    AppLog.i(TAG, "onPrepared 成功，丢弃挂起 onError，按可播处理（完全静默）uri=${currentUri()}")
                    cancelPendingError()
                    playerError = null
                }
                preparedDurationMs = runCatching { prepared.duration.toLong() }.getOrDefault(C.TIME_UNSET)
                playbackState = Player.STATE_READY
                pendingSeekPositionMs.let { pending ->
                    if (pending != C.TIME_UNSET && pending > 0L) {
                        runCatching { prepared.seekTo(pending.toInt()) }
                    }
                    pendingSeekPositionMs = C.TIME_UNSET
                }
                AppLog.i(
                    TAG,
                    "onPrepared isPlaying=${prepared.isPlaying} durationMs=$preparedDurationMs playWhenReady=$playWhenReady uri=${currentUri()}",
                )
                if (playWhenReady) {
                    runCatching { prepared.start() }
                        .onSuccess { AppLog.i(TAG, "start() 已调用 isPlaying=${prepared.isPlaying} uri=${currentUri()}") }
                        .onFailure { setError("start 失败", it) }
                }
                invalidateState()
            }
            mp.setOnCompletionListener { completed ->
                AppLog.i(TAG, "onCompletion uri=${currentUri()} repeat=$repeatMode shuffle=$shuffleModeEnabled index=$currentMediaItemIndex size=${playlist.size}")
                handleCompletion(completed)
            }
            mp.setOnErrorListener { errored, what, extra ->
                // 1.4.3-B：只挂起待复核，绝不在这里 setError + invalidateState。
                // 部分机型在 setDataSource/prepare 阶段甩一次非致命 onError(-38)，
                // 随后又正常 onPrepared 出有效时长——那种情况必须完全静默；
                // 真正不支持的格式不会有 onPrepared，由复核超时兜底提交一次错误。
                if (errored !== this.mediaPlayer) {
                    AppLog.w(TAG, "忽略旧实例迟到的 onError what=$what extra=$extra")
                    return@setOnErrorListener true
                }
                AppLog.i(TAG, "onError 挂起待复核 what=$what extra=$extra uri=${currentUri()}")
                pendingError = PlaybackException(
                    "系统解码错误(待 onPrepared 复核)",
                    IllegalStateException("MediaPlayer onError what=$what extra=$extra"),
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                )
                scheduleErrorCommit(mp)
                true
            }
        }
        try {
            mediaPlayer.setDataSource(appContext, uri)
        } catch (e: Exception) {
            runCatching { mediaPlayer.release() }
            setError("setDataSource 失败（系统不认该格式）", e)
            invalidateState()
            return
        }
        this.mediaPlayer = mediaPlayer
        playbackState = Player.STATE_BUFFERING
        AppLog.i(TAG, "setDataSource=OK prepareAsync 已调用 uri=${currentUri()}")
        runCatching { mediaPlayer.prepareAsync() }
            .onFailure {
                runCatching { mediaPlayer.release() }
                this.mediaPlayer = null
                setError("prepareAsync 失败", it)
                invalidateState()
            }
    }

    private fun handleCompletion(completed: MediaPlayer) {
        when {
            repeatMode == Player.REPEAT_MODE_ONE -> {
                runCatching { completed.seekTo(0) }
                if (playWhenReady) runCatching { completed.start() }
                AppLog.i(TAG, "onCompletion 单曲循环，重新起播")
                invalidateState()
            }
            playlist.isEmpty() -> {
                playbackState = Player.STATE_ENDED
                invalidateState()
            }
            else -> {
                // 1.4.3-A：随机模式走洗牌表，顺序模式才按队列自然 +1。
                val nextIndex = if (shuffleModeEnabled && shuffleOrder.isNotEmpty()) {
                    advanceShuffle(+1)
                } else {
                    when {
                        currentMediaItemIndex < playlist.size - 1 -> currentMediaItemIndex + 1
                        repeatMode == Player.REPEAT_MODE_ALL && playlist.isNotEmpty() -> 0
                        else -> null
                    }
                }
                if (nextIndex == null || nextIndex !in playlist.indices) {
                    playbackState = Player.STATE_ENDED
                    invalidateState()
                    AppLog.i(TAG, "播放结束 shuffle=$shuffleModeEnabled repeat=$repeatMode")
                } else {
                    AppLog.i(TAG, "onCompletion 自动切歌 shuffle=$shuffleModeEnabled from=$currentMediaItemIndex to=$nextIndex")
                    currentMediaItemIndex = nextIndex
                    openNextInPlaylist()
                }
            }
        }
    }

    private fun openNextInPlaylist() {
        pendingSeekPositionMs = C.TIME_UNSET
        preparedDurationMs = C.TIME_UNSET
        releaseMediaPlayerQuietly()
        playbackState = Player.STATE_BUFFERING
        openCurrentAndPrepare()
        AppLog.i(TAG, "自动切下一首 index=$currentMediaItemIndex shuffle=$shuffleModeEnabled uri=${currentUri()}")
        invalidateState()
    }

    private fun releaseMediaPlayerQuietly() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun setError(message: String, cause: Throwable) {
        // 提交正式错误时先撤掉挂起复核，避免超时兜底再提交一次（弹两次 Toast）。
        cancelPendingError()
        playerError = PlaybackException(message, cause, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
        playbackState = Player.STATE_IDLE
        AppLog.w(TAG, "$message: ${cause.javaClass.simpleName}: ${cause.message?.take(120)}")
    }

    // ---------------------------------------------------- 1.4.3-B 误报挂起复核

    private fun scheduleErrorCommit(mp: MediaPlayer) {
        cancelPendingErrorCallbacks()
        val runnable = Runnable {
            if (mp !== this.mediaPlayer) return@Runnable
            val pending = pendingError ?: return@Runnable
            if (playbackState == Player.STATE_READY) {
                // onPrepared 已到，只是没来得及清挂起；直接丢弃，完全静默。
                cancelPendingError()
                return@Runnable
            }
            AppLog.w(TAG, "onError 复核超时(${ErrorRecheckTimeoutMs}ms)仍无 onPrepared，确认真失败，提交一次错误 uri=${currentUri()}")
            setError(
                pending.message ?: "系统解码错误",
                pending.cause ?: IllegalStateException("MediaPlayer onError"),
            )
            invalidateState()
        }
        errorCommitRunnable = runnable
        errorHandler.postDelayed(runnable, ErrorRecheckTimeoutMs)
    }

    private fun cancelPendingErrorCallbacks() {
        errorCommitRunnable?.let { errorHandler.removeCallbacks(it) }
        errorCommitRunnable = null
    }

    private fun cancelPendingError() {
        pendingError = null
        cancelPendingErrorCallbacks()
    }

    // ---------------------------------------------------- 1.4.3-A 真洗牌索引表

    /** 以 anchor 为首、其余 Fisher-Yates 洗牌，重建 [shuffleOrder] 并把游标归零。 */
    private fun buildShuffleOrder(anchorIndex: Int) {
        if (playlist.isEmpty()) {
            shuffleOrder = intArrayOf()
            shuffleCursor = -1
            return
        }
        val anchor = anchorIndex.coerceIn(0, playlist.size - 1)
        val rest = (0 until playlist.size).toMutableList()
        rest.remove(anchor)
        rest.shuffle(Random.Default)
        shuffleOrder = (listOf(anchor) + rest).toIntArray()
        shuffleCursor = 0
        AppLog.i(TAG, "重建洗牌表 size=${shuffleOrder.size} anchor=$anchor head=${shuffleOrder.take(8).joinToString(",")}")
    }

    /**
     * 按方向推进洗牌游标，返回下一个要播的 playlist 下标；到边界且非列表循环返回 null。
     * 一轮播完且 REPEAT_MODE_ALL：重建一张新表（随机新锚点），顺序不重复。
     */
    private fun advanceShuffle(delta: Int): Int? {
        val nextCursor = shuffleCursor + delta
        if (nextCursor in shuffleOrder.indices) {
            shuffleCursor = nextCursor
            return shuffleOrder[nextCursor]
        }
        if (delta > 0 && repeatMode == Player.REPEAT_MODE_ALL && playlist.isNotEmpty()) {
            buildShuffleOrder(Random.Default.nextInt(playlist.size))
            return shuffleOrder.getOrNull(shuffleCursor)
        }
        return null
    }

    /** 用户直接点队列里某首：把游标对齐到该项（找不到就以它为锚重建表）。 */
    private fun syncShuffleCursorToIndex(index: Int) {
        if (index !in playlist.indices) return
        val found = shuffleOrder.indexOf(index)
        if (found >= 0) {
            shuffleCursor = found
        } else {
            buildShuffleOrder(index)
        }
    }

    /**
     * 判断本次 seek 是「下一首 / 上一首」还是「指定 index 跳歌」。
     * 优先用 Media3 的 seekCommand 常量；同时按请求 index 是否为 ±1 做兜底，
     * 避免不同 Media3 版本 seekCommand 取值差异导致乱序失效。
     */
    private fun classifyShuffleAdvance(requestedIndex: Int, seekCommand: Int): Int? {
        // [1.4.18·点歌错位根治] 日志实证：用户点列表第 6 首，seekCommand=10
        // （COMMAND_SEEK_TO_MEDIA_ITEM），却因 requestedIndex==current±1 的兜底被误判为
        // "下一首/上一首"，走了 shuffle 推进，结果播了相邻的错误曲目。
        // 修正原则：**明确的"点选/定位到某首"命令一律指定跳转，绝不按 index±1 猜 advance**。
        val explicitPick = seekCommand == Player.COMMAND_SEEK_TO_MEDIA_ITEM ||   // 10 点列表项
            seekCommand == Player.COMMAND_SEEK_TO_DEFAULT_POSITION ||              // 4 默认位置
            seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM              // 5 当前曲内 seek
        if (explicitPick) {
            syncShuffleCursorToIndex(requestedIndex)
            return null
        }
        return when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT ||
                seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> +1
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS ||
                seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> -1
            else -> {
                // seekCommand 未知（个别 ROM 传 0）时才退回 index±1 兜底。
                when {
                    requestedIndex == currentMediaItemIndex + 1 -> +1
                    requestedIndex == currentMediaItemIndex - 1 -> -1
                    else -> {
                        syncShuffleCursorToIndex(requestedIndex)
                        null
                    }
                }
            }
        }
    }

    private fun currentUri(): String =
        playlist.getOrNull(currentMediaItemIndex)?.mediaItem?.localConfiguration?.uri
            ?.toString()?.takeLast(48) ?: "?"

    private companion object {
        private const val TAG = "DirectEngine"
        /** onError 挂起后最多等多久仍无 onPrepared 就判定真失败。给足慢机型 prepare 时间。 */
        private const val ErrorRecheckTimeoutMs = 1800L
    }
}
