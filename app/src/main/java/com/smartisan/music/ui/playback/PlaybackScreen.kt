package com.smartisan.music.ui.playback

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.PlaybackIntentAudit
import com.smartisan.music.playback.NowPlayingLyricsRepository
import com.smartisan.music.playback.PlaybackFormatBus
import com.smartisan.music.playback.TranscodeProgressBus
import com.smartisan.music.playback.PlaybackSleepTimer
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.await
import com.smartisan.music.playback.cancelSleepTimer
import com.smartisan.music.playback.extractEmbeddedLyrics
import com.smartisan.music.playback.invalidateLibrary
import com.smartisan.music.playback.removeMediaItemsByMediaIds
import com.smartisan.music.playback.setScratchSeekModeEnabled
import com.smartisan.music.playback.startSleepTimer
import androidx.compose.ui.zIndex
import com.smartisan.music.ui.components.AudioOutputOverlay
import com.smartisan.music.ui.components.MediaStoreDeleteItem
import com.smartisan.music.ui.components.SmartisanTouchShield
import com.smartisan.music.ui.theme.MonetPlaybackGlow
import com.smartisan.music.ui.components.loadEmbeddedArtwork
import com.smartisan.music.ui.components.peekArtworkThumbnail
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class PlaybackCoverPageState(
    val dragMode: CoverDragMode = CoverDragMode.None,
    val previewPositionMs: Long? = null,
    val resumePlaybackAfterDrag: Boolean = false,
    val needlePreviewRotationDegrees: Float? = null,
    val needleSettlingPositionMs: Long? = null,
    val needleParkedOutside: Boolean = false,
)

@Composable
fun PlaybackScreen(
    playbackSettings: PlaybackSettings,
    onScratchEnabledChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestAddToPlaylist: (List<MediaItem>) -> Unit = {},
    onLibraryChanged: () -> Unit = {},
    onFavoriteToggle: ((MediaItem) -> Unit)? = null,
    showTopBar: Boolean = true,
) {
    val controller = LocalPlaybackController.current
    val context = LocalContext.current
    val favoriteRepository =
        remember(context.applicationContext) {
            FavoriteSongsRepository.getInstance(context.applicationContext)
        }
    val playlistRepository =
        remember(context.applicationContext) {
            PlaylistRepository.getInstance(context.applicationContext)
        }
    val entranceTimeMillis = remember { Animatable(0f) }
    val favoriteIds by favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLibraryChanged by rememberUpdatedState(onLibraryChanged)
    val scratchSoundController =
        remember(context) {
            ScratchSoundController(context)
        }
    val popcornSoundController =
        remember(context) {
            VinylPopcornSoundController(context)
        }
    var volume by
        remember(context) {
            mutableFloatStateOf(context.musicStreamVolumeFraction())
        }
    var state by
        remember(controller) {
            mutableStateOf(controller.snapshot(volume = volume))
        }
    val latestVolume by rememberUpdatedState(volume)
    var livePositionMs by
        remember(controller) {
            mutableLongStateOf(state.currentPositionMs)
        }
    // 1.3.3：歌词 seek 同步。拖动进度条后后端 currentPosition 是**异步**到位的，
    // 轮询每 250/500ms 会用旧值把 livePositionMs 覆盖回去，歌词只能一帧一小步地挪，
    // 永远触发「相邻行平滑」而不是瞬移，看起来就是「拖了进度条歌词不动」。
    // 这里在 seek 后把目标值顶住，直到后端真的追上（或超时）才交给轮询。
    var pendingSeekTargetMs by remember { mutableLongStateOf(-1L) }
    var pendingSeekAtElapsed by remember { mutableLongStateOf(0L) }
    // 1.3.5 / 问题 17：缓冲（首次转码）期间拖过进度条，转码完成重新装载 MediaSource 时
    // ExoPlayer 会把待应用的初始位置冲掉、从 0 开始播。这里把最后一次目标位置连同 mediaId
    // 单独记下来，等 STATE_READY 时补落一次（不随 pendingSeekTargetMs 的 10s 超时清除）。
    var pendingTranscodeSeekMs by remember { mutableLongStateOf(-1L) }
    var pendingTranscodeSeekMediaId by remember { mutableStateOf<String?>(null) }
    // 1.4.11 S04：待补落 seek 记下当时的代际；READY 补落时若代际已变（用户又拖了一次），
    // 只清旧值不补落，杜绝旧 seek 在缓冲完成后跳回、覆盖新位置（G-54 注释承诺但代码漏校验）。
    var pendingTranscodeSeekGeneration by remember { mutableStateOf(-1) }
    // [1.4.24·R39] **待恢复播放意图**：打碟（旋转唱片）/ 拖唱针 会先暂停主播放器，
    // 拖完再恢复。问题是「恢复」那一刻往往播放器还在转码/缓冲/seek 中途，
    // 发出去的 play() 可能被中途的 stop()/prepare() 冲掉 → 用户表现为
    // 「拖完唱片后不响了 / 进度条不动 / 莫名暂停」。
    // 这里把「用户本来是要继续听的」这个意图记下来，等 STATE_READY 时**补一次 play()**，
    // 彻底消除竞态。null = 没有待恢复意图。
    var pendingResumePlay by remember { mutableStateOf(false) }
    // [1.4.24·R39] 记账时刻，用于超时保护。
    var pendingResumePlayAtMs by remember { mutableLongStateOf(0L) }
    // [1.4.9·G-47] seek 代际：缓冲前后连续拖动时，旧 seek 的异步回包会覆盖新位置。
    // 每次拖动自增代际；补落/回包只认当前代际，过期 seek 一律丢弃。
    var seekGeneration by remember { mutableStateOf(0) }
    // 1.3.4 / 问题 13：过滤状态快照回吐的旧位置，进度条不再自己往回跳。
    val positionGate = remember(controller) { PositionGate() }
    var showMorePanel by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerDialog by rememberSaveable { mutableStateOf(false) }
    var showAudioOutputDialog by rememberSaveable { mutableStateOf(false) }
    var currentVisualPage by rememberSaveable { mutableStateOf(PlaybackVisualPage.Cover) }
    var keepLyricsScreenAwake by rememberSaveable { mutableStateOf(false) }
    var sleepTimerWasActive by remember { mutableStateOf(false) }
    var coverPageState by
        remember(state.mediaItem?.mediaId) {
            mutableStateOf(PlaybackCoverPageState())
        }
    var scratchFlingJob by remember { mutableStateOf<Job?>(null) }
    var discManualRotationOffsetDegrees by remember { mutableFloatStateOf(0f) }
    val sleepTimerState by PlaybackSleepTimer.state.collectAsStateWithLifecycle()

    // 1.2.26：DFF/APE 的时长/规格是后台探测出来的，写进 FormatCache 后不会产生 player 事件。
    // 这里跟着探测版本号重新取一次快照，进度条与「歌曲信息」才能即时从 0:00 / 「—」变成真实值。
    val formatProbeRevision by PlaybackFormatBus.revision.collectAsStateWithLifecycle()
    LaunchedEffect(formatProbeRevision, controller) {
        val playbackController = controller ?: return@LaunchedEffect
        val nextState = playbackController.snapshot(volume = latestVolume)
        state = nextState
        // 1.3.3：seek 未落地前别让后台探测快照把歌词位置拽回旧值。
        livePositionMs =
            if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs
            else positionGate.accept(
                nextState.currentPositionMs,
                nextState.mediaItem?.mediaId,
                false,
            )
    }

    LaunchedEffect(Unit) {
        entranceTimeMillis.snapTo(0f)
        entranceTimeMillis.animateTo(
            targetValue = PlaybackEntranceTotalDurationMillis.toFloat(),
            animationSpec =
                tween(
                    durationMillis = PlaybackEntranceTotalDurationMillis,
                    easing = LinearEasing,
                ),
        )
    }

    val deleteCoordinator =
        rememberMediaStoreDeleteCoordinator(
            onDeleted = { mediaIds ->
                controller.removeMediaItemsByMediaIds(mediaIds)
                scope.launch {
                    runCatching {
                        favoriteRepository.removeAll(mediaIds)
                    }
                    runCatching {
                        playlistRepository.removeMediaIdsFromAll(mediaIds)
                    }
                    runCatching {
                        controller?.invalidateLibrary()?.await(context)
                    }
                    currentOnLibraryChanged()
                }
                context.toast(R.string.playback_delete_success)
            },
            onNotDeleted = {
                context.toast(R.string.playback_delete_failed)
            },
        )

    BackHandler {
        if (showSleepTimerDialog) {
            showSleepTimerDialog = false
        } else if (showAudioOutputDialog) {
            showAudioOutputDialog = false
        } else if (showMorePanel) {
            showMorePanel = false
        } else {
            onCollapse()
        }
    }

    DisposableEffect(controller) {
        val playbackController = controller ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // [1.4.20·问题7] 切歌瞬间把单调门的地板强制归零：新歌从头播放（player
                    // currentPosition 从0起），但 transition 回调里 snapshot 偶尔先回吐上一首
                    // 残留的大 position，positionGate.accept 会把它当新歌地板顶住 → 进度条卡在
                    // 旧进度不动。这里在切歌事件里先把门复位，跟随的 onEvents 再从0正常累加。
                    positionGate.reseat(0L)
                    pendingSeekTargetMs = -1L
                    pendingTranscodeSeekMs = -1L
                    // [1.4.24·R39] 换歌了，上一首的「待恢复播放」意图不该带过来，
                    // 否则会在新歌 READY 时冒出一声自动播放。
                    pendingResumePlay = false
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    val nextState = playbackController.snapshot(volume = latestVolume)
                    state = nextState
                    // 1.3.3：seek 异步期间顶住目标值，见 pendingSeekTargetMs 说明。
                    livePositionMs =
                        if (pendingSeekTargetMs >= 0L) {
                            pendingSeekTargetMs
                        } else {
                            positionGate.accept(
                                nextState.currentPositionMs,
                                nextState.mediaItem?.mediaId,
                                false,
                            )
                        }
                    // 1.3.5 / 问题 17：缓冲完成后把拖过的进度补落到真实播放位置
                    val transcodeSeek = pendingTranscodeSeekMs
                    val staleGeneration =
                        pendingTranscodeSeekMs >= 0L &&
                            pendingTranscodeSeekGeneration != seekGeneration
                    if (staleGeneration) {
                        // 1.4.11 S04：用户在缓冲期间又拖了一次，旧待补落已过期，直接丢弃。
                        AppLog.i(
                            "Seek",
                            "1.4.11(S04) 丢弃过期缓冲seek target=$transcodeSeek" +
                                " pendingGen=$pendingTranscodeSeekGeneration curGen=$seekGeneration",
                        )
                        pendingTranscodeSeekMs = -1L
                        pendingTranscodeSeekMediaId = null
                        pendingTranscodeSeekGeneration = -1
                    } else if (
                        transcodeSeek >= 0L &&
                            player.playbackState == Player.STATE_READY &&
                            nextState.mediaItem?.mediaId == pendingTranscodeSeekMediaId &&
                            abs(playbackController.currentPosition - transcodeSeek) > 200L
                    ) {
                        pendingTranscodeSeekMs = -1L
                        pendingTranscodeSeekMediaId = null
                        pendingTranscodeSeekGeneration = -1
                        playbackController.seekPlaybackTo(transcodeSeek)
                        livePositionMs = transcodeSeek
                        state = state.copy(currentPositionMs = transcodeSeek)
                        AppLog.i(
                            "Seek",
                            "1.4.11(S04/G-54) 缓冲完成后补落 seek target=$transcodeSeek gen=$seekGeneration" +
                                "（缓冲前后拖动串行化，丢弃过期回包）",
                        )
                    }
                    // [1.4.24·R39] 待恢复播放意图补落：打碟/拖唱针结束后用户本来是要继续听的，
                    // 但那次 play() 可能落在转码/缓冲/seek 中途被冲掉。这里在 READY 时补一次，
                    // 彻底消除「拖完唱片就不响了」的竞态。
                    if (pendingResumePlay) {
                        // 超时保护：意图最多挂 15 秒，超过就丢弃——不能让一条陈年意图
                        // 在很久以后的某个 READY 里冒出来自动播放。
                        val expired = SystemClock.elapsedRealtime() - pendingResumePlayAtMs >
                            PendingResumePlayTimeoutMs
                        if (expired) {
                            pendingResumePlay = false
                            AppLog.i(
                                "PlaybackIntent",
                                "待恢复播放意图超时丢弃（${PendingResumePlayTimeoutMs}ms 内未 READY）",
                            )
                        } else if (player.playbackState == Player.STATE_READY) {
                            pendingResumePlay = false
                            if (!nextState.isPlaybackActive) {
                                AppLog.i(
                                    "PlaybackIntent",
                                    "待恢复播放意图补落：READY 时补一次 play()（此前那次可能被转码/seek 冲掉）",
                                )
                                PlaybackIntentAudit.play(controller, "大播放页·打碟/唱针结束后恢复播放")?.play()
                                state = state.copy(isPlaying = true, playWhenReady = true)
                            }
                        }
                    }
                }
            }
        playbackController.addListener(listener)
        onDispose {
            playbackController.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                PlaybackSleepTimer.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(showSleepTimerDialog) {
        if (showSleepTimerDialog) {
            PlaybackSleepTimer.refresh()
        }
    }

    LaunchedEffect(sleepTimerState.isActive) {
        if (sleepTimerWasActive && !sleepTimerState.isActive) {
            showSleepTimerDialog = false
        }
        sleepTimerWasActive = sleepTimerState.isActive
    }

    fun resetCoverPageInteraction(resumePlayback: Boolean) {
        scratchFlingJob?.cancel()
        scratchFlingJob = null
        val shouldResumePlayback = resumePlayback && coverPageState.resumePlaybackAfterDrag
        coverPageState = PlaybackCoverPageState()
        if (shouldResumePlayback) {
            pendingResumePlay = true
            pendingResumePlayAtMs = SystemClock.elapsedRealtime()
            PlaybackIntentAudit.play(controller, "大播放页·重置唱盘交互恢复播放")?.play()
        }
        controller?.setScratchSeekModeEnabled(false)
        scratchSoundController.stop()
    }

    fun finishDiscScratch(
        positionMs: Long,
        resumePlaybackAfterDrag: Boolean,
    ) {
        coverPageState =
            coverPageState.copy(
                dragMode = CoverDragMode.None,
                previewPositionMs = positionMs,
                resumePlaybackAfterDrag = false,
                needlePreviewRotationDegrees = null,
                needleSettlingPositionMs = null,
                needleParkedOutside = false,
            )
        pendingSeekTargetMs = positionMs
        pendingSeekAtElapsed = SystemClock.elapsedRealtime()
        livePositionMs = positionMs
        // [1.4.10·G-54] 唱盘 seek 同样自增代际。
        seekGeneration++
        val holdForBuffer = state.isBuffering || controller?.isCurrentMediaItemSeekable == false
        if (holdForBuffer) {
            pendingTranscodeSeekMs = positionMs
            pendingTranscodeSeekMediaId = state.mediaItem?.mediaId
            pendingTranscodeSeekGeneration = seekGeneration  // 1.4.11 S04 代际校验
        }
        AppLog.state(
            "State",
            op = "旋转唱片调整进度",
            source = "finishDiscScratch",
            fields = mapOf(
                "targetMs" to positionMs,
                "resume" to resumePlaybackAfterDrag,
                "initiator" to "USER",
                "traceId" to AppLog.LastUserAction.record("finishDiscScratch"),
            ),
        )
        // [1.4.10·G-54] 缓冲期唱盘拖动同样只记目标、不下发 seekTo，待 STATE_READY 补落，
        // 避免 flush/rebuild 用旧锚点重建进度造成条与播放错位。
        if (!holdForBuffer) {
            controller?.seekPlaybackTo(positionMs)
        } else {
            AppLog.i(
                "Seek",
                "1.4.10(G-54) 唱盘缓冲期拖动只记目标 gen=$seekGeneration target=$positionMs，待补落",
            )
        }
        if (resumePlaybackAfterDrag) {
            // [1.4.24·R39] 立即恢复 + 记账兜底：立即那次可能被转码/seek 冲掉，
            // 记账的那份会在 STATE_READY 时补落（见 onEvents 里的待恢复补落逻辑）。
            pendingResumePlay = true
            pendingResumePlayAtMs = SystemClock.elapsedRealtime()
            PlaybackIntentAudit.play(controller, "大播放页·打碟结束恢复播放")?.play()
        }
        controller?.setScratchSeekModeEnabled(false)
        scratchSoundController.stop()
    }

    fun launchDiscScratchFling(
        startPositionMs: Long,
        initialVelocityDegreesPerSecond: Float,
        resumePlaybackAfterDrag: Boolean,
        durationMs: Long,
    ) {
        scratchFlingJob?.cancel()
        scratchFlingJob = null
        val clampedVelocity =
            initialVelocityDegreesPerSecond.coerceIn(
                -ScratchVelocityMaxDegreesPerSecond,
                ScratchVelocityMaxDegreesPerSecond,
            )
        if (abs(clampedVelocity) < ScratchFlingMinVelocityDegreesPerSecond || durationMs <= 0L) {
            finishDiscScratch(startPositionMs, resumePlaybackAfterDrag)
            return
        }

        val flingDurationMs =
            scratchFlingDurationMs(
                velocityDegreesPerSecond = clampedVelocity,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
            )
        val velocityKeyframes =
            scratchFlingVelocityKeyframes(
                velocityDegreesPerSecond = clampedVelocity,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
            )
        coverPageState =
            coverPageState.copy(
                dragMode = CoverDragMode.DiscScratch,
                previewPositionMs = startPositionMs,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                needlePreviewRotationDegrees = null,
                needleSettlingPositionMs = null,
                needleParkedOutside = false,
            )
        scratchFlingJob = scope.launch {
            var positionMs = startPositionMs.coerceIn(0L, durationMs)
            var previousFrameNanos = Long.MIN_VALUE
            var previousVelocity = velocityKeyframes.first()
            var elapsedMs = 0f
            while (isActive && elapsedMs < flingDurationMs) {
                val frameNanos = withFrameNanos { it }
                if (previousFrameNanos == Long.MIN_VALUE) {
                    previousFrameNanos = frameNanos
                    continue
                }
                val frameDeltaMs =
                    ((frameNanos - previousFrameNanos) / 1_000_000f).coerceIn(
                        1f,
                        PlaybackScratchMaxDeltaTimeMs.toFloat(),
                    )
                previousFrameNanos = frameNanos
                elapsedMs = (elapsedMs + frameDeltaMs).coerceAtMost(flingDurationMs.toFloat())

                val currentVelocity =
                    scratchFlingVelocityAt(
                        keyframes = velocityKeyframes,
                        elapsedMs = elapsedMs,
                        durationMs = flingDurationMs,
                    )
                val deltaAngle =
                    ((previousVelocity + currentVelocity) * frameDeltaMs) / ScratchFlingFrameDivisor
                previousVelocity = currentVelocity

                if (abs(deltaAngle) >= PlaybackScratchMinMotionDegrees) {
                    discManualRotationOffsetDegrees += deltaAngle
                    val targetPosition =
                        scratchPositionAfterAngle(
                            positionMs = positionMs,
                            deltaAngleDegrees = deltaAngle,
                            durationMs = durationMs,
                        )
                    scratchSoundController.onScratchMotion(targetPosition, deltaAngle)
                    if (targetPosition != positionMs) {
                        positionMs = targetPosition
                        coverPageState = coverPageState.copy(previewPositionMs = positionMs)
                    }
                }
            }
            finishDiscScratch(positionMs, resumePlaybackAfterDrag)
            scratchFlingJob = null
        }
    }

    fun setVisualPage(targetPage: PlaybackVisualPage) {
        if (currentVisualPage == targetPage) {
            return
        }
        currentVisualPage = targetPage
        if (targetPage != PlaybackVisualPage.Cover) {
            resetCoverPageInteraction(resumePlayback = true)
        }
    }

    fun toggleVisualPage() {
        setVisualPage(
            if (currentVisualPage == PlaybackVisualPage.Cover) {
                PlaybackVisualPage.Lyrics
            } else {
                PlaybackVisualPage.Cover
            }
        )
    }

    DisposableEffect(controller) {
        val playbackController = controller
        onDispose {
            playbackController?.setScratchSeekModeEnabled(false)
        }
    }

    DisposableEffect(scratchSoundController) {
        onDispose {
            scratchFlingJob?.cancel()
            scratchSoundController.release()
        }
    }

    DisposableEffect(popcornSoundController) {
        onDispose {
            popcornSoundController.release()
        }
    }

    val keepLyricsScreenOn = currentVisualPage == PlaybackVisualPage.Lyrics && keepLyricsScreenAwake
    DisposableEffect(context, keepLyricsScreenOn) {
        val window = context.findActivity()?.window
        if (keepLyricsScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(controller, state.mediaItem?.mediaId) {
        val playbackController = controller ?: return@LaunchedEffect
        // 切歌即丢弃上一首遗留的缓冲期 seek 目标，避免误落到新歌
        if (pendingTranscodeSeekMediaId != null &&
            pendingTranscodeSeekMediaId != state.mediaItem?.mediaId
        ) {
            pendingTranscodeSeekMs = -1L
            pendingTranscodeSeekMediaId = null
            pendingTranscodeSeekGeneration = -1
        }
        while (isActive) {
            val reported = playbackController.currentPosition.coerceAtLeast(0L)
            val target = pendingSeekTargetMs
            // 1.3.3：参考 SkeuoMusic ProgressGate 的「位置源头顶住目标值」思路。
            // seek 是异步的，后端还没到位时每帧都用旧值覆盖会让歌词只小步挪动；
            // 顶住目标值直到后端追上或超时，歌词才会一次性吸附到新行。
            livePositionMs =
                if (target >= 0L) {
                    val settled = abs(reported - target) <= SeekPositionSettleToleranceMs
                    val timedOut =
                        SystemClock.elapsedRealtime() - pendingSeekAtElapsed >
                            SeekPositionHoldTimeoutMs
                    if (settled || timedOut) {
                        pendingSeekTargetMs = -1L
                        positionGate.reseat(reported)
                        reported
                    } else {
                        target
                    }
                } else {
                    positionGate.accept(reported, state.mediaItem?.mediaId, false)
                }
            delay(
                if (playbackController.isPlaying) {
                    PlaybackPositionPlayingRefreshMs
                } else {
                    PlaybackPositionIdleRefreshMs
                }
            )
        }
    }

    LaunchedEffect(context) {
        while (isActive) {
            delay(PlaybackVolumeRefreshMs)
            val nextVolume = context.musicStreamVolumeFraction()
            if (abs(nextVolume - latestVolume) >= PlaybackVolumeChangeEpsilon) {
                volume = nextVolume
                state = state.copy(volume = nextVolume)
            }
        }
    }

    LaunchedEffect(controller, playbackSettings.scratchEnabled, currentVisualPage) {
        if (!playbackSettings.scratchEnabled || currentVisualPage != PlaybackVisualPage.Cover) {
            resetCoverPageInteraction(resumePlayback = true)
        }
    }

    LaunchedEffect(
        playbackSettings.popcornSoundEnabled,
        state.isPlaying,
        coverPageState.dragMode,
    ) {
        // 1.3.3：撤销 1.3.2 擅自放宽的条件，恢复原始 0.3.0 口径——
        // 「开关打开 + 正在播放 + 没有搓碟」才播唱盘爆豆音；搓碟时只由
        // ScratchSoundController 出刮擦声（用户说的「摩擦声」），两者互不混用。
        // 1.3.2 把「摩擦声没了」错误归到爆豆音条件上，属于没读源码的推理。
        if (!playbackSettings.popcornSoundEnabled ||
            !state.isPlaying ||
            coverPageState.dragMode != CoverDragMode.None
        ) {
            popcornSoundController.stop()
            return@LaunchedEffect
        }
        try {
            while (isActive) {
                popcornSoundController.playRandomPop()
                // [1.4.24·R41 轻微磨损密度] 用户实测「频率还是太高，降低一半」。
                // 日志实测 26.5 次/分 → 目标约 13 次/分（权威分档里最轻的"偶尔轻柔"档）。
                // 采样已是 150~247ms 单次爆豆，这里按三档疏密分布模拟真实唱盘：
                // 灰尘偶发(15%) / 轻微磨损主流(75%) / 轻微划痕簇(10%)。详见 nextPopcornIntervalMs()。
                delay(nextPopcornIntervalMs())
            }
        } finally {
            popcornSoundController.stop()
        }
    }

    val mediaMetadata = state.mediaItem?.mediaMetadata
    // 1.3.2：跨进程 / 队列重建后的 MediaItem 常丢 localConfiguration.uri，
    // 会让 ScratchSoundController.prepareSource(null, 0) 清空刮擦音缓冲、搓碟完全无声。
    // 按 mediaId 的确定性规则兜底还原 URI（与 LocalAudioLibrary 补录行构造规则一致）。
    val scratchSourceUri =
        state.mediaItem?.localConfiguration?.uri
            ?: scratchSourceUriForMediaId(state.mediaItem?.mediaId)
    val title =
        mediaMetadata?.displayTitle?.toString()
            ?: mediaMetadata?.title?.toString()
            ?: stringResource(R.string.unknown_song_title)
    val artist =
        mediaMetadata?.subtitle?.toString()
            ?: mediaMetadata?.artist?.toString()
            ?: stringResource(R.string.unknown_artist)
    val durationMs = state.durationMs.takeIf { it > 0L } ?: mediaMetadata?.durationMs ?: 0L
    val currentMediaItem = state.mediaItem
    val currentMediaId = currentMediaItem?.mediaId
    val currentIsExternalAudio = currentMediaItem?.isExternalAudioLaunchItem() == true
    val favoriteEnabled =
        !currentIsExternalAudio && !currentMediaId.isNullOrBlank() && currentMediaId in favoriteIds
    val coverPreviewPositionMs = coverPageState.previewPositionMs
    val boundedLivePositionMs = livePositionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val displayPositionMs =
        if (currentVisualPage == PlaybackVisualPage.Cover) {
            coverPreviewPositionMs?.coerceIn(0L, durationMs.coerceAtLeast(0L))
                ?: boundedLivePositionMs
        } else {
            boundedLivePositionMs
        }
    val noLyricsLine = stringResource(R.string.playback_more_primary_line)
    val fallbackLyricsLines =
        remember(noLyricsLine) {
            listOf(noLyricsLine)
        }
    val controllerTracks = controller?.currentTracks
    val trackLyrics =
        remember(state.mediaItem?.mediaId, controllerTracks) {
            controllerTracks?.let(::extractEmbeddedLyrics)
        }
    val embeddedLyrics by
        produceState<EmbeddedLyrics?>(
            initialValue = trackLyrics ?: state.mediaItem?.let(NowPlayingLyricsRepository::peek),
            key1 = state.mediaItem?.mediaId,
            key2 = state.mediaItem?.localConfiguration?.uri,
            key3 = trackLyrics,
        ) {
            val mediaItem = state.mediaItem
            value = trackLyrics ?: mediaItem?.let(NowPlayingLyricsRepository::peek)
            if (trackLyrics == null && mediaItem != null) {
                value = NowPlayingLyricsRepository.load(context, mediaItem)
            }
        }
    val artworkRequestKey = state.mediaItem?.artworkRequestKey()
    val albumArtwork by
        produceState<ImageBitmap?>(
            initialValue = state.mediaItem?.let(::peekArtworkThumbnail),
            artworkRequestKey,
        ) {
            val mediaItem = state.mediaItem
            if (mediaItem == null) {
                value = null
                return@produceState
            }
            value = peekArtworkThumbnail(mediaItem) ?: value
            value = loadEmbeddedArtwork(context, mediaItem)
        }

    LaunchedEffect(
        currentVisualPage,
        coverPageState.dragMode,
        coverPageState.previewPositionMs,
        boundedLivePositionMs,
    ) {
        if (currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val previewPosition = coverPageState.previewPositionMs ?: return@LaunchedEffect
        if (
            coverPageState.dragMode == CoverDragMode.None &&
                abs(boundedLivePositionMs - previewPosition) <= CoverPreviewSettleToleranceMs
        ) {
            coverPageState = coverPageState.copy(previewPositionMs = null)
        }
    }

    LaunchedEffect(
        currentVisualPage,
        coverPageState.dragMode,
        coverPageState.previewPositionMs,
    ) {
        if (currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val previewPosition = coverPageState.previewPositionMs ?: return@LaunchedEffect
        if (coverPageState.dragMode != CoverDragMode.None) {
            return@LaunchedEffect
        }
        delay(CoverPreviewTimeoutMs)
        if (
            coverPageState.dragMode == CoverDragMode.None &&
                coverPageState.previewPositionMs == previewPosition
        ) {
            coverPageState = coverPageState.copy(previewPositionMs = null)
        }
    }

    LaunchedEffect(
        currentVisualPage,
        coverPageState.dragMode,
        coverPageState.needleSettlingPositionMs,
        boundedLivePositionMs,
    ) {
        if (currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val settlingPosition = coverPageState.needleSettlingPositionMs ?: return@LaunchedEffect
        if (
            coverPageState.dragMode == CoverDragMode.None &&
                abs(boundedLivePositionMs - settlingPosition) <= CoverPreviewSettleToleranceMs
        ) {
            coverPageState =
                coverPageState.copy(
                    needlePreviewRotationDegrees = null,
                    needleSettlingPositionMs = null,
                )
        }
    }

    LaunchedEffect(
        currentVisualPage,
        coverPageState.dragMode,
        coverPageState.needleSettlingPositionMs,
    ) {
        if (currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val settlingPosition = coverPageState.needleSettlingPositionMs ?: return@LaunchedEffect
        if (coverPageState.dragMode != CoverDragMode.None) {
            return@LaunchedEffect
        }
        delay(NeedleSeekSettleHoldTimeoutMs)
        if (
            coverPageState.dragMode == CoverDragMode.None &&
                coverPageState.needleSettlingPositionMs == settlingPosition
        ) {
            coverPageState =
                coverPageState.copy(
                    needlePreviewRotationDegrees = null,
                    needleSettlingPositionMs = null,
                )
        }
    }

    val latestScratchWarmupPositionMs by rememberUpdatedState(boundedLivePositionMs)
    LaunchedEffect(scratchSourceUri, playbackSettings.scratchEnabled) {
        scratchFlingJob?.cancel()
        scratchFlingJob = null
        scratchSoundController.stop()
        if (scratchSourceUri == null || !playbackSettings.scratchEnabled) {
            scratchSoundController.prepareSource(null, 0L)
            return@LaunchedEffect
        }
        while (isActive) {
            scratchSoundController.prepareSource(
                sourceUri = scratchSourceUri,
                positionMs = latestScratchWarmupPositionMs,
            )
            delay(ScratchWarmupRefreshMs)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(PlaybackPageBackground)) {
        val density = LocalDensity.current
        val screenHeightPx =
            with(density) {
                maxHeight.roundToPx()
            }
        val topInset =
            with(density) {
                WindowInsets.safeDrawing.getTop(this).toDp()
            }
        val bottomInset =
            with(density) {
                WindowInsets.safeDrawing.getBottom(this).toDp()
            }
        var turntableWidth by
            remember(maxWidth, maxHeight) {
                mutableStateOf<Dp?>(null)
            }
        val bottomControlsMinimumWidth = PlaybackBottomControlsMinimumWidth.coerceAtMost(maxWidth)
        val bottomControlsWidth =
            turntableWidth?.coerceIn(bottomControlsMinimumWidth, maxWidth) ?: maxWidth
        val turntableEntranceProgress =
            playbackEntranceProgress(
                timeMillis = entranceTimeMillis.value,
                delayMillis = 0,
                durationMillis = PlaybackTurntableEntranceDurationMillis,
            )

        // [1.4.23·莫奈取色] 朦胧晶莹剔透的壁纸色晕染层（仅开关打开时绘制，最底层不影响布局）。
        MonetPlaybackGlow(Modifier.fillMaxSize())
        SmartisanTouchShield()
        Column(modifier = Modifier.fillMaxSize()) {
            if (showTopBar) {
                PlaybackTopBar(
                    title = title,
                    artist = artist,
                    topInset = topInset,
                    onCollapse = onCollapse,
                )
            }
            // 1.2.27：DFF/APE 首次播放要先把整首转成可拖动的 FLAC（约 6~10 秒），
            // 这里明说「正在转换 xx%」，避免用户以为点了没反应。
            val transcodeProgress by TranscodeProgressBus.state.collectAsStateWithLifecycle()
            transcodeProgress?.let { progress ->
                androidx.compose.foundation.text.BasicText(
                    text = stringResource(R.string.playback_converting, progress.percent),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    style =
                        androidx.compose.ui.text.TextStyle(
                            fontSize =
                                com.smartisan.music.ui.components.smartisanTextSize(
                                    R.dimen.text_size_small
                                ),
                            color =
                                androidx.compose.ui.res.colorResource(R.color.list_item_second_line),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                    maxLines = 1,
                )
            }
            PlaybackTimeSeekBar(
                durationMs = durationMs,
                currentPositionMs = displayPositionMs,
                thumbRes = R.drawable.playing_control_time,
                modifier = Modifier.fillMaxWidth(),
                onSeek = { positionMs ->
                    // 1.2.25：不可 seek 的曲目（首次转码中的 DFF/APE）改走「重建当前曲再定位」
                    // 1.3.3：歌词进度同步。seek 是异步的，若不立刻把显示位置推到目标值，
                    // 轮询会用后端旧值每 250ms 覆盖一次，歌词永远只小步挪、看起来「不同步」。
                    pendingSeekTargetMs = positionMs
                    pendingSeekAtElapsed = SystemClock.elapsedRealtime()
                    livePositionMs = positionMs
                    // [1.4.9·G-47] 每次拖动自增代际（UI 顶住目标值到后端追上/超时）。
                    seekGeneration++
                    if (state.isBuffering || controller?.isCurrentMediaItemSeekable == false) {
                        pendingTranscodeSeekMs = positionMs
                        pendingTranscodeSeekMediaId = currentMediaId
                        pendingTranscodeSeekGeneration = seekGeneration  // 1.4.11 S04 代际校验
                    }
                    state = state.copy(currentPositionMs = positionMs)
                    AppLog.state(
                        "State",
                        op = "进度条seek",
                        source = "PlaybackTimeSeekBar.onSeek",
                        fields = mapOf(
                            "targetMs" to positionMs,
                            "mediaId" to currentMediaId,
                            "initiator" to "USER",
                            "traceId" to AppLog.LastUserAction.record("PlaybackTimeSeekBar.onSeek"),
                        ),
                    )
                    // [1.4.10·G-54] 缓冲中/不可 seek 时只记目标，不下发 seekTo：
                    // 1.4.9 在缓冲期仍无条件 seekPlaybackTo，多次拖动触发 flush/rebuild，
                    // 进度锚点用旧 presentationTimeUs 重建 → 缓冲完成播的位置与进度条无关。
                    // 这里延迟到 STATE_READY 后由 G-47 补落一次性定位，代际号保证只认最后一次。
                    if (!state.isBuffering && controller?.isCurrentMediaItemSeekable != false) {
                        controller?.seekPlaybackTo(positionMs)
                    } else {
                        AppLog.i(
                            "Seek",
                            "1.4.10(G-54) 缓冲期拖动只记目标 gen=$seekGeneration target=$positionMs，" +
                                "不下发 seekTo，待 STATE_READY 补落（避免锚点错位）",
                        )
                    }
                },
            )
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .padding(top = PlaybackVisualStageTopPadding)
                        .graphicsLayer {
                            translationY =
                                (1f - turntableEntranceProgress) * screenHeightPx.toFloat()
                        },
                contentAlignment = Alignment.TopCenter,
            ) {
                PlaybackVisualStage(
                    modifier = Modifier.fillMaxSize(),
                    currentVisualPage = currentVisualPage,
                    coverPositionMs = displayPositionMs,
                    lyricsPositionMs = boundedLivePositionMs,
                    durationMs = durationMs,
                    scratchEnabled = playbackSettings.scratchEnabled,
                    hidePlayerAxisEnabled = playbackSettings.hidePlayerAxisEnabled,
                    albumArtwork = albumArtwork,
                    keepLyricsScreenAwake = keepLyricsScreenAwake,
                    embeddedLyrics = embeddedLyrics,
                    fallbackLyricsLines = fallbackLyricsLines,
                    hasMediaItem = state.mediaItem != null,
                    isPlaying = state.isPlaybackActive,
                    coverDragMode = coverPageState.dragMode,
                    previewPositionMs = coverPageState.previewPositionMs,
                    needlePreviewRotationDegrees = coverPageState.needlePreviewRotationDegrees,
                    needleParkedOutside = coverPageState.needleParkedOutside,
                    discManualRotationOffsetDegrees = discManualRotationOffsetDegrees,
                    mediaId = state.mediaItem?.mediaId,
                    onMoreClick = {
                        showMorePanel = true
                    },
                    onVisualPageToggle = ::toggleVisualPage,
                    onKeepLyricsScreenAwakeToggle = {
                        val enabled = !keepLyricsScreenAwake
                        keepLyricsScreenAwake = enabled
                        context.toast(
                            if (enabled) {
                                R.string.screen_light_on
                            } else {
                                R.string.screen_light_off
                            }
                        )
                    },
                    onDiscScratchStart = {
                        scratchFlingJob?.cancel()
                        scratchFlingJob = null
                        val resumePlaybackAfterDrag =
                            state.isPlaybackActive || coverPageState.resumePlaybackAfterDrag
                        coverPageState =
                            coverPageState.copy(
                                dragMode = CoverDragMode.DiscScratch,
                                previewPositionMs = boundedLivePositionMs,
                                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                                needlePreviewRotationDegrees = null,
                                needleSettlingPositionMs = null,
                                needleParkedOutside = false,
                            )
                        if (resumePlaybackAfterDrag) {
                            // [1.4.24·R39] 先记恢复意图再暂停：拖完之后一定会恢复，
                            // 万一那次 play() 被转码/seek 冲掉，READY 时会补落。
                            pendingResumePlay = true
                            pendingResumePlayAtMs = SystemClock.elapsedRealtime()
                            PlaybackIntentAudit.pause(controller, "大播放页·开始旋转唱片打碟")?.pause()
                        }
                        controller?.setScratchSeekModeEnabled(true)
                        scratchSoundController.onScratchStart(
                            sourceUri = scratchSourceUri,
                            positionMs = boundedLivePositionMs,
                        )
                    },
                    onDiscScratchMotion = { positionMs, deltaAngle ->
                        discManualRotationOffsetDegrees += deltaAngle
                        scratchSoundController.onScratchMotion(positionMs, deltaAngle)
                    },
                    onDiscScratchPositionChange = { positionMs, _ ->
                        coverPageState = coverPageState.copy(previewPositionMs = positionMs)
                    },
                    onDiscScratchEnd = { positionMs, flingVelocityDegreesPerSecond ->
                        val resumePlaybackAfterDrag = coverPageState.resumePlaybackAfterDrag
                        launchDiscScratchFling(
                            startPositionMs = positionMs,
                            initialVelocityDegreesPerSecond = flingVelocityDegreesPerSecond,
                            resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                            durationMs = durationMs,
                        )
                    },
                    onDiscScratchCancel = {
                        resetCoverPageInteraction(resumePlayback = true)
                    },
                    onNeedleSeekStart = { rotationDegrees, positionMs ->
                        PlaybackHaptics.vibrateEffect(context)
                        val resumePlaybackAfterDrag = state.isPlaybackActive
                        coverPageState =
                            coverPageState.copy(
                                dragMode = CoverDragMode.NeedleSeek,
                                previewPositionMs = positionMs ?: 0L,
                                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                                needlePreviewRotationDegrees = rotationDegrees,
                                needleSettlingPositionMs = null,
                                needleParkedOutside = false,
                            )
                        if (resumePlaybackAfterDrag) {
                            pendingResumePlay = true
                            pendingResumePlayAtMs = SystemClock.elapsedRealtime()
                            PlaybackIntentAudit.pause(controller, "大播放页·拖动唱针定位")?.pause()
                            state =
                                state.copy(
                                    isPlaying = false,
                                    playWhenReady = false,
                                    isBuffering = false,
                                )
                        }
                        // [1.4.12·S20] 唱针 seek 不切 EXACT：
                        // setScratchSeekModeEnabled(true) 会把 SeekParameters 设为 EXACT，
                        // 强制解码器 flush 到精确帧，在 USB DAC 上表现为直流跳变 →「嘟声/咔哒」。
                        // EXACT 只在真正搓碟（DiscScratch 连续 seek）时需要；唱针是一次性定位，
                        // 用 DEFAULT seek（快速、无 flush 毛刺），配合 pendingSeekTargetMs 顶住落点。
                        // 防御性恢复 DEFAULT，避免上一次搓碟残留的 EXACT 影响唱针。
                        controller?.setScratchSeekModeEnabled(false)
                    },
                    onNeedleSeekPositionChange = { rotationDegrees, positionMs ->
                        coverPageState =
                            coverPageState.copy(
                                previewPositionMs = positionMs ?: 0L,
                                needlePreviewRotationDegrees = rotationDegrees,
                                needleSettlingPositionMs = null,
                            )
                    },
                    onNeedleSeekEnd = { rotationDegrees, positionMs ->
                        PlaybackHaptics.vibrateEffect(context)
                        val resumePlaybackAfterDrag = coverPageState.resumePlaybackAfterDrag
                        if (positionMs == null) {
                            coverPageState =
                                coverPageState.copy(
                                    dragMode = CoverDragMode.None,
                                    previewPositionMs = 0L,
                                    resumePlaybackAfterDrag = false,
                                    needlePreviewRotationDegrees = null,
                                    needleSettlingPositionMs = null,
                                    needleParkedOutside = true,
                                )
                            controller?.seekPlaybackTo(0L)
                            PlaybackIntentAudit.pause(controller, "大播放页·唱针移出唱片")?.pause()
                            pendingSeekTargetMs = 0L
                            pendingSeekAtElapsed = SystemClock.elapsedRealtime()
                            livePositionMs = 0L
                            AppLog.state(
                                "State",
                                op = "唱针移出暂停到开头",
                                source = "PlaybackTurntableStage.onNeedleSeekDone(null)",
                                fields = mapOf(
                                    "targetMs" to 0L,
                                    "initiator" to "USER",
                                    "traceId" to AppLog.LastUserAction.record(
                                        "PlaybackTurntableStage.onNeedleSeekDone(null)"
                                    ),
                                ),
                            )
                            state =
                                state.copy(
                                    isPlaying = false,
                                    playWhenReady = false,
                                    isBuffering = false,
                                    currentPositionMs = 0L,
                                )
                        } else {
                            coverPageState =
                                coverPageState.copy(
                                    dragMode = CoverDragMode.None,
                                    previewPositionMs = positionMs,
                                    resumePlaybackAfterDrag = false,
                                    needlePreviewRotationDegrees = rotationDegrees,
                                    needleSettlingPositionMs = positionMs,
                                    needleParkedOutside = false,
                                )
                            controller?.seekPlaybackTo(positionMs)
                            pendingSeekTargetMs = positionMs
                            pendingSeekAtElapsed = SystemClock.elapsedRealtime()
                            livePositionMs = positionMs
                            AppLog.state(
                                "State",
                                op = "唱针seek",
                                source = "PlaybackTurntableStage.onNeedleSeekDone",
                                fields = mapOf(
                                    "targetMs" to positionMs,
                                    "initiator" to "USER",
                                    "traceId" to AppLog.LastUserAction.record(
                                        "PlaybackTurntableStage.onNeedleSeekDone"
                                    ),
                                ),
                            )
                            state =
                                state.copy(
                                    isPlaying = resumePlaybackAfterDrag,
                                    playWhenReady = resumePlaybackAfterDrag,
                                    isBuffering = false,
                                    currentPositionMs = positionMs,
                                )
                            if (resumePlaybackAfterDrag) {
                                pendingResumePlay = true
                                pendingResumePlayAtMs = SystemClock.elapsedRealtime()
                                PlaybackIntentAudit.play(controller, "大播放页·唱针拖动结束恢复播放")?.play()
                            }
                        }
                        controller?.setScratchSeekModeEnabled(false)
                        scratchSoundController.stop()
                    },
                    onNeedleSeekCancel = {
                        PlaybackHaptics.vibrateEffect(context)
                        resetCoverPageInteraction(resumePlayback = true)
                    },
                    onTurntableWidthChanged = { resolvedWidth ->
                        turntableWidth = resolvedWidth
                    },
                )
            }
            PlaybackBottomControls(
                width = bottomControlsWidth,
                bottomInset = bottomInset,
                state = state.copy(volume = volume),
                entranceTimeMillis = entranceTimeMillis.value,
                onRepeatClick = {
                    val nextRepeatMode = nextPlaybackRepeatMode(state.repeatMode)
                    controller?.repeatMode = nextRepeatMode
                    state = state.copy(repeatMode = nextRepeatMode)
                    context.toast(repeatToastRes(nextRepeatMode))
                },
                onPreviousClick = {
                    // 1.3.2：直接切上一首；seekToPrevious() 播过 3 秒只会倒回当前曲开头
                    // 1.4.3：只补日志埋点——登记一次用户操作，让随后的切歌帧 initiator=USER。
                    AppLog.LastUserAction.record("PlaybackScreen.onPreviousClick")
                    controller?.seekToPreviousMediaItem()
                },
                onPlayPauseClick = {
                    if (state.isPlaybackActive) {
                        PlaybackIntentAudit.pause(controller, "大播放页·播放/暂停按钮")?.pause()
                    } else {
                        PlaybackIntentAudit.play(controller, "大播放页·播放/暂停按钮")?.play()
                    }
                },
                onNextClick = {
                    // 1.4.3：只补日志埋点，登记用户操作以串联切歌帧（不改播放行为）。
                    AppLog.LastUserAction.record("PlaybackScreen.onNextClick")
                    controller?.seekToNext()
                },
                onShuffleClick = {
                    val shuffleEnabled = !state.shuffleEnabled
                    controller?.shuffleModeEnabled = shuffleEnabled
                    state = state.copy(shuffleEnabled = shuffleEnabled)
                    context.toast(shuffleToastRes(shuffleEnabled))
                },
                onVolumeChange = { targetVolume ->
                    context.setMusicStreamVolumeFraction(targetVolume)
                    val actualVolume = context.musicStreamVolumeFraction()
                    volume = actualVolume
                    state = state.copy(volume = actualVolume)
                },
            )
        }

        PlaybackMoreActionOverlays(
            showMorePanel = showMorePanel,
            favoriteEnabled = favoriteEnabled,
            currentVisualPage = currentVisualPage,
            scratchEnabled = playbackSettings.scratchEnabled,
            sleepTimerActive = sleepTimerState.isActive,
            addToPlaylistEnabled = !currentIsExternalAudio,
            shareEnabled = currentMediaItem?.canShareAudio() == true,
            showSleepTimerDialog = showSleepTimerDialog,
            sleepTimerState = sleepTimerState,
            bottomInsetPx = (bottomInset.value * density.density).roundToInt(),
            onAddToPlaylistClick = {
                state.mediaItem?.let { onRequestAddToPlaylist(listOf(it)) }
                showMorePanel = false
            },
            onAudioOutputClick = {
                showMorePanel = false
                showAudioOutputDialog = true
            },
            onFavoriteToggle = {
                val currentItem = state.mediaItem
                val mediaId = currentMediaId
                if (currentItem != null && !mediaId.isNullOrBlank() && !currentIsExternalAudio) {
                    if (onFavoriteToggle != null) {
                        onFavoriteToggle(currentItem)
                    } else {
                        scope.launch {
                            favoriteRepository.toggle(mediaId)
                        }
                    }
                }
                showMorePanel = false
            },
            onShareClick = {
                showMorePanel = false
                val mediaItem = state.mediaItem
                if (mediaItem == null || !context.tryShareAudio(mediaItem)) {
                    context.toast(R.string.can_not_share_song)
                }
            },
            onSleepTimerClick = {
                showMorePanel = false
                showSleepTimerDialog = true
            },
            onLyricsToggle = {
                toggleVisualPage()
                showMorePanel = false
            },
            onScratchToggle = {
                onScratchEnabledChange(!playbackSettings.scratchEnabled)
                showMorePanel = false
            },
            onDeleteClick = {
                when (
                    val result =
                        state.mediaItem?.resolveDeleteTarget()
                            ?: PlaybackDeleteTargetResult.Unavailable
                ) {
                    is PlaybackDeleteTargetResult.Available -> {
                        deleteCoordinator.delete(
                            listOf(
                                MediaStoreDeleteItem(
                                    mediaId = result.target.mediaId,
                                    uri = result.target.uri,
                                )
                            )
                        )
                    }
                    PlaybackDeleteTargetResult.CueFile -> {
                        context.toast(R.string.can_not_delete_cue_file)
                    }
                    PlaybackDeleteTargetResult.Unavailable -> {
                        context.toast(R.string.can_not_delete_song)
                    }
                }
                showMorePanel = false
            },
            onDismissMorePanel = {
                showMorePanel = false
            },
            onSleepTimerDismiss = {
                showSleepTimerDialog = false
            },
            onSleepTimerDurationSelected = { selectedDurationMs ->
                showSleepTimerDialog = false
                if (selectedDurationMs > 0L) {
                    controller?.startSleepTimer(selectedDurationMs)
                } else {
                    controller?.cancelSleepTimer()
                    if (sleepTimerState.isActive) {
                        context.toast(R.string.sleep_timer_stopped)
                    }
                }
            },
        )
        AudioOutputOverlay(
            visible = showAudioOutputDialog,
            item = state.mediaItem,
            onDismissRequest = { showAudioOutputDialog = false },
            modifier = Modifier.fillMaxSize().zIndex(9f),
        )
    }
}

/**
 * 1.3.2：按 mediaId 确定性还原可播放 URI，作为 UI 侧拿不到
 * `MediaItem.localConfiguration.uri` 时的兜底。
 *
 * mediaId 两类：Audio 表数字 id；`f` + id（DFF/DSF 等 Files 补录行）。
 * 规则与 [LocalAudioLibrary] 构造 URI 时逐字一致，避免打碟刮擦音因 uri 为空而永远静音。
 */
private fun scratchSourceUriForMediaId(mediaId: String?): Uri? {
    if (mediaId.isNullOrEmpty()) return null
    val unscanned = mediaId.startsWith(LocalAudioLibrary.UnscannedMediaIdPrefix)
    val rawId =
        if (unscanned) {
            mediaId.removePrefix(LocalAudioLibrary.UnscannedMediaIdPrefix)
        } else {
            mediaId
        }
    val id = rawId.toLongOrNull() ?: return null
    if (id <= 0L) return null
    return if (unscanned) {
        ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
    } else {
        audioMediaItemUri(id)
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private const val PlaybackPositionPlayingRefreshMs = 250L
private const val PlaybackPositionIdleRefreshMs = 500L
private const val PlaybackVolumeRefreshMs = 200L
private const val PlaybackVolumeChangeEpsilon = 0.001f
/** 1.3.3：seek 后后端位置进入这个容差就认为已落地，交还给轮询。 */
private const val SeekPositionSettleToleranceMs = 700L
/** 1.3.3：seek 目标最长顶住多久，防止后端一直不回位把进度条永久钉死。 */
private const val SeekPositionHoldTimeoutMs = 10_000L

// [1.4.24·R39] 待恢复播放意图的最长挂起时间：超过就丢弃，避免陈年意图在很久以后的
// 某个 READY 里冒出来自动播放。
private const val PendingResumePlayTimeoutMs = 15_000L

/**
 * [1.4.24·R41] 下次爆豆音的间隔（毫秒），按「次/秒」分布反算。
 *
 * ## 本轮改动（用户 2026-11-24 19:59 实机反馈）
 * 「现在触发唱盘爆豆音的频率还是太高了，把频率给我降低一半」，并明确要模拟
 * **真实环境中轻微磨损的唱盘**——不同成因（灰尘掉落 / 划痕 / 磨损）有不同触发频率。
 *
 * ## 先算数：当前到底多密？
 * 日志 `melodify-logs-20261124-195513.zip` 的 `app-20261124.log`，用户实测段
 * 19:54:12.567 → 19:55:04.729（52.2 秒）共 23 次触发 = **26.5 次/分钟**，
 * 间隔中位数 2.16 秒。降一半 = **约 13 次/分钟**。
 *
 * ## 权威依据（联网核实 EffeTune「Vinyl Artifacts」DSP 的 Pops/min 分档）
 * - 0~20 次/分：Occasional gentle pops（偶尔轻柔）
 * - 20~60 次/分：Moderate vintage character（中等复古）
 * - 60~120 次/分：Heavy wear and tear（重度磨损）
 * - 其「Subtle Vinyl Character」预设 = **20 次/分**，「Lo-Fi Ambient」= 15 次/分。
 * 用户要「轻微磨损」→ 目标 13 次/分，落在最轻的 0~20 档，与真实唱盘一致。
 *
 * ## 分布（整体频率相对上一版砍半，且保留三种成因的疏密差异）
 * 真实唱盘三类杂音的频率不同：灰尘掉落最偶发、轻微磨损居中、划痕最规律偏密。
 * 这里用三档概率分布模拟这种"疏密不均"，而不是均匀随机：
 * - 75%：灰尘/轻微磨损主流 —— 0.15~0.50 次/秒（9~30 次/分）
 * - 15%：更稀疏偶发灰尘 —— 0.10~0.15 次/秒（6~9 次/分）
 * - 10%：稍密（轻微划痕痕簇）—— 0.50~0.75 次/秒（30~45 次/分，仍远低于"重度磨损"60+）
 *
 * 采样本身已裁成 150~247ms 的单次爆豆，这里控制的就是真实听感密度。
 */
private fun nextPopcornIntervalMs(): Long {
    val roll = Random.nextFloat()
    val perSecond = when {
        roll < 0.75f -> 0.15f + Random.nextFloat() * 0.35f // 0.15~0.50 次/秒（75%，主流轻微磨损）
        roll < 0.90f -> 0.10f + Random.nextFloat() * 0.05f // 0.10~0.15 次/秒（15%，偶发灰尘）
        else -> 0.50f + Random.nextFloat() * 0.25f          // 0.50~0.75 次/秒（10%，轻微划痕簇）
    }
    return (1_000f / perSecond).toLong().coerceIn(1_333L, 10_000L)
}
