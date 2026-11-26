package com.smartisan.music.ui.playback

import com.smartisan.music.ui.theme.monetContentBackground
import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.colorResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.smartisan.music.R
import com.smartisan.music.playback.UsbExclusiveSinkRef
import com.smartisan.music.playback.UsbVolume
import com.smartisan.music.playback.UsbVolumeMode
import com.smartisan.music.playback.UsbVolumeModeStore
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.ui.components.SmartisanTitleBarSurface
import com.smartisan.music.ui.components.SmartisanTitleBarSurfaceStyle
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.smartisan.music.ui.components.smartisanMarquee

@Composable
internal fun PlaybackTopBar(
    title: String,
    artist: String,
    topInset: Dp,
    onCollapse: () -> Unit,
) {
    SmartisanTitleBarSurface(
        style = SmartisanTitleBarSurfaceStyle.Playback,
        modifier = Modifier.fillMaxWidth().height(topInset + 48.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(top = topInset, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressedDrawableButton(
                normalRes = R.drawable.btn_playing_back,
                pressedRes = R.drawable.btn_playing_back_down,
                contentDescription = stringResource(R.string.collapse_player),
                modifier = Modifier.width(40.dp).height(30.dp),
                onClick = onCollapse,
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // [1.4.25·R44] 大播放页歌名/歌手同样走滚动，长标题不会只剩半句。
                Text(
                    text = title,
                    style = PlaybackTitleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.smartisanMarquee(),
                )
                Text(
                    text = artist,
                    style = PlaybackArtistStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(top = 1.dp).smartisanMarquee(),
                )
            }
            Box(modifier = Modifier.width(40.dp).height(30.dp))
        }
    }
}

@Composable
internal fun PlaybackTimeSeekBar(
    durationMs: Long,
    currentPositionMs: Long,
    @DrawableRes thumbRes: Int,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val currentFraction =
        if (duration > 0L) {
                currentPositionMs.toFloat() / duration.toFloat()
            } else {
                0f
            }
            .coerceIn(0f, 1f)
    var trackWidthPx by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val shownFraction = if (dragFraction.isNaN()) currentFraction else dragFraction.coerceIn(0f, 1f)
    val shownPosition =
        if (duration > 0L) {
            (shownFraction * duration.toFloat()).roundToLong()
        } else {
            0L
        }
    val trackProgress = (shownFraction * PlaybackSeekBarProgressMax.toFloat()).roundToInt()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(PlaybackSeekBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackTime(shownPosition),
                style = PlaybackTimeStyle.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                modifier = Modifier.width(PlaybackSeekBarHorizontalPadding),
            )
            Box(
                modifier =
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .progressSemantics(shownFraction)
                        .onSizeChanged { trackWidthPx = it.width }
                        .pointerInput(duration) {
                            // [1.4.12·S20] 顶部进度条拖动节流：
                            // 旧实现 onDrag 每个指针事件都写 dragFraction → 每帧重组整个
                            // PlaybackTimeSeekBar（时间文本 + seekbar_progress drawable level 重建），
                            // 高刷屏上拖动明显卡顿。这里做「最小位移 + 最小间隔」双节流，显示更新
                            // 降到合理频率；但 lastDragX 每次都记真值，松手落点仍精确不丢帧。
                            var lastEmitMs = 0L
                            var lastEmitX = -1f
                            var lastDragX = -1f
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (trackWidthPx > 0) {
                                        lastDragX = offset.x
                                        lastEmitX = offset.x
                                        dragFraction = fractionFromPosition(offset.x, trackWidthPx)
                                    }
                                },
                                onDrag = { change, _ ->
                                    if (trackWidthPx > 0) {
                                        val x = change.position.x
                                        lastDragX = x  // 落点真值，始终记录、不受节流影响
                                        val nowMs = change.uptimeMillis
                                        val moved =
                                            lastEmitX < 0f || abs(x - lastEmitX) >= SeekDragMinDxPx
                                        val tick = nowMs - lastEmitMs >= SeekDragMinIntervalMs
                                        if (moved && tick) {
                                            dragFraction = fractionFromPosition(x, trackWidthPx)
                                            lastEmitX = x
                                            lastEmitMs = nowMs
                                        }
                                        change.consume()
                                    }
                                },
                                onDragEnd = {
                                    // 落点用最后一次真实 X 换算，不只是节流后的显示值 → 不丢最后一帧。
                                    val finalFraction =
                                        if (lastDragX >= 0f && trackWidthPx > 0) {
                                            fractionFromPosition(lastDragX, trackWidthPx)
                                        } else {
                                            dragFraction.takeUnless { it.isNaN() } ?: currentFraction
                                        }
                                    if (duration > 0L) {
                                        onSeek(
                                            (finalFraction.coerceIn(0f, 1f) * duration.toFloat())
                                                .roundToLong()
                                        )
                                    }
                                    dragFraction = Float.NaN
                                },
                                onDragCancel = {
                                    dragFraction = Float.NaN
                                },
                            )
                        }
            ) {
                Image(
                    painter =
                        rememberSmartisanDrawablePainter(
                            R.drawable.seekbar_progress,
                            progressLevel = trackProgress,
                        ),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(PlaybackSeekTrackDrawableHeight)
                            .align(Alignment.Center),
                )
                Image(
                    painter = painterResource(thumbRes),
                    contentDescription = null,
                    modifier =
                        Modifier.width(PlaybackSeekThumbWidth)
                            .height(PlaybackSeekThumbHeight)
                            .align(Alignment.CenterStart)
                            .offset {
                                val thumbWidthPx =
                                    with(density) { PlaybackSeekThumbWidth.roundToPx() }
                                IntOffset(
                                    x =
                                        ((trackWidthPx * shownFraction) - (thumbWidthPx / 2f))
                                            .roundToInt(),
                                    y = 0,
                                )
                            },
                )
            }
            Text(
                text = "-${formatPlaybackTime((duration - shownPosition).coerceAtLeast(0L))}",
                style = PlaybackTimeStyle.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                modifier = Modifier.width(PlaybackSeekBarHorizontalPadding),
            )
        }
        AndroidDrawableImage(
            drawableRes = R.drawable.playing_progress_bar_line,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(PlaybackSeekBarDividerHeight),
        )
    }
}

private const val PlaybackSeekBarProgressMax = 10_000
// [1.4.12·S20] 进度条拖动节流阈值：位移 ≥2px 且间隔 ≥12ms 才刷新显示（约 80fps 上限）
private const val SeekDragMinDxPx = 2f
private const val SeekDragMinIntervalMs = 12L

@Composable
internal fun PlaybackControlButtons(
    isPlaying: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    controlWidth: Dp,
    entranceTimeMillis: Float,
    onRepeatClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onShuffleClick: () -> Unit,
) {
    val buttonMetrics = playbackControlButtonMetrics(controlWidth)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = PlaybackControlButtonsTopPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val repeatIconRes = playbackRepeatButtonRes(repeatMode)
        PressedDrawableButton(
            normalRes = repeatIconRes,
            pressedRes = repeatIconRes,
            contentDescription = stringResource(repeatContentDescriptionRes(repeatMode)),
            modifier =
                Modifier.width(buttonMetrics.outerWidth)
                    .height(buttonMetrics.height)
                    // [1.4.10·G-55] 五键 B 案真正落地：循环键可见中心实测比中间三键高约 4dp，
                    // 下移 4dp 使其与播放/上一首/下一首可见中心齐平（1.4.9 声称下移但未实现）。
                    .offset(y = 4.dp)
                    .then(
                        Modifier.playbackControlEntrance(
                            timeMillis = entranceTimeMillis,
                            delayMillis = PlaybackOuterButtonAlphaDelayMillis,
                            durationMillis = PlaybackOuterButtonAlphaDurationMillis,
                            offsetY = PlaybackControlEntranceOffset,
                            animateY = false,
                        )
                    ),
            onClick = onRepeatClick,
        )
        // [护栏 G-38·1.4.8 落地] 可见中心等距：prev 向中心收 Δ=(play−outer)/4
        PressedDrawableButton(
            normalRes = R.drawable.btn_playing_prev,
            pressedRes = R.drawable.btn_playing_prev_down,
            contentDescription = stringResource(R.string.previous_song),
            modifier =
                Modifier.offset(x = buttonMetrics.centerEqualizeDelta)
                    .width(buttonMetrics.sideWidth)
                    .height(buttonMetrics.height)
                    .then(
                        Modifier.playbackControlEntrance(
                            timeMillis = entranceTimeMillis,
                            delayMillis = PlaybackSideButtonEntranceDelayMillis,
                            durationMillis = PlaybackControlEntranceDurationMillis,
                            offsetY = PlaybackControlEntranceOffset,
                        )
                    ),
            onClick = onPreviousClick,
        )
        PressedDrawableButton(
            normalRes =
                if (isPlaying) {
                    R.drawable.btn_playing_pause
                } else {
                    R.drawable.btn_playing_play
                },
            pressedRes =
                if (isPlaying) {
                    R.drawable.btn_playing_pause_down
                } else {
                    R.drawable.btn_playing_play_down
                },
            contentDescription =
                if (isPlaying) {
                    stringResource(R.string.pause)
                } else {
                    stringResource(R.string.play)
                },
            modifier =
                Modifier.width(buttonMetrics.playWidth)
                    .height(buttonMetrics.height)
                    .then(
                        Modifier.playbackControlEntrance(
                            timeMillis = entranceTimeMillis,
                            delayMillis = PlaybackPlayButtonEntranceDelayMillis,
                            durationMillis = PlaybackControlEntranceDurationMillis,
                            offsetY = PlaybackControlEntranceOffset,
                        )
                    ),
            onClick = onPlayPauseClick,
        )
        // [护栏 G-38·1.4.8 落地] 可见中心等距：next 向中心收 Δ=(play−outer)/4
        PressedDrawableButton(
            normalRes = R.drawable.btn_playing_next,
            pressedRes = R.drawable.btn_playing_next_down,
            contentDescription = stringResource(R.string.next_song),
            modifier =
                Modifier.offset(x = -buttonMetrics.centerEqualizeDelta)
                    .width(buttonMetrics.sideWidth)
                    .height(buttonMetrics.height)
                    .then(
                        Modifier.playbackControlEntrance(
                            timeMillis = entranceTimeMillis,
                            delayMillis = PlaybackSideButtonEntranceDelayMillis,
                            durationMillis = PlaybackControlEntranceDurationMillis,
                            offsetY = PlaybackControlEntranceOffset,
                        )
                    ),
            onClick = onNextClick,
        )
        PressedDrawableButton(
            normalRes =
                if (shuffleEnabled) {
                    R.drawable.btn_playing_shuffle_on
                } else {
                    R.drawable.btn_playing_shuffle_off
                },
            pressedRes =
                if (shuffleEnabled) {
                    R.drawable.btn_playing_shuffle_on
                } else {
                    R.drawable.btn_playing_shuffle_off
                },
            contentDescription = stringResource(R.string.shuffle),
            modifier =
                Modifier.width(buttonMetrics.outerWidth)
                    .height(buttonMetrics.height)
                    // [1.4.10·G-55] 五键 B 案真正落地：随机键可见中心实测比中间三键高约 2.7dp，
                    // 下移 2.7dp 使其齐平。
                    .offset(y = 2.7.dp)
                    .then(
                        Modifier.playbackControlEntrance(
                            timeMillis = entranceTimeMillis,
                            delayMillis = PlaybackOuterButtonAlphaDelayMillis,
                            durationMillis = PlaybackOuterButtonAlphaDurationMillis,
                            offsetY = PlaybackControlEntranceOffset,
                            animateY = false,
                        )
                    ),
            onClick = onShuffleClick,
        )
    }
}

private data class PlaybackControlButtonMetrics(
    val outerWidth: Dp,
    val sideWidth: Dp,
    val playWidth: Dp,
    val height: Dp,
) {
    // [护栏 G-38·1.4.8] 五键可见中心严格等距所需的侧键内收量 Δ=(playWidth−outerWidth)/4。
    // 推导：原中心距为 (outer+side)/2、(side+play)/2、(play+side)/2、(side+outer)/2；
    // 令 prev 右移 Δ、next 左移 Δ 后四段中心距相等 ⇒ Δ=(playWidth−outerWidth)/4。
    // drawable 水平可见中心偏移≈0，故框中心即视觉中心；播放键仍为最大圆主导。
    val centerEqualizeDelta: Dp get() = (playWidth - outerWidth) / 4f
}

internal val PlaybackBottomControlsMinimumWidth =
    (OriginalTurntableBaseWidthDp * PlaybackMinimumTouchTargetSize.value /
            PlaybackControlOuterButtonBaseWidthDp)
        .dp

private fun playbackControlButtonMetrics(controlWidth: Dp): PlaybackControlButtonMetrics {
    val width = controlWidth.value
    return when {
        width >= 432f ->
            PlaybackControlButtonMetrics(
                outerWidth = 77.dp,
                sideWidth = 84.dp,
                playWidth = 102.3.dp,
                height = 104.5.dp,
            )
        width >= 411f ->
            PlaybackControlButtonMetrics(
                outerWidth = 77.dp,
                sideWidth = 80.1.dp,
                playWidth = 98.dp,
                height = 99.5.dp,
            )
        else -> {
            val scale = width / OriginalTurntableBaseWidthDp
            PlaybackControlButtonMetrics(
                outerWidth = PlaybackControlOuterButtonBaseWidthDp.dp * scale,
                sideWidth = PlaybackControlSideButtonBaseWidthDp.dp * scale,
                playWidth = PlaybackControlPlayButtonBaseWidthDp.dp * scale,
                height = PlaybackControlButtonBaseHeightDp.dp * scale,
            )
        }
    }
}

private const val PlaybackControlOuterButtonBaseWidthDp = 67.3f
private const val PlaybackControlSideButtonBaseWidthDp = 70f
private const val PlaybackControlPlayButtonBaseWidthDp = 85.3f
private const val PlaybackControlButtonBaseHeightDp = 87f

@Composable
private fun Modifier.playbackControlEntrance(
    timeMillis: Float,
    delayMillis: Int,
    durationMillis: Int,
    offsetY: Dp,
    animateY: Boolean = true,
): Modifier {
    val density = LocalDensity.current
    val progress =
        playbackEntranceProgress(
            timeMillis = timeMillis,
            delayMillis = delayMillis,
            durationMillis = durationMillis,
        )
    val offsetYPx =
        with(density) {
            offsetY.roundToPx().toFloat()
        }
    return graphicsLayer {
        if (animateY) {
            translationY = (1f - progress) * offsetYPx
        }
        alpha = if (animateY) 1f else progress
    }
}

// ---------------------------------------------------------------------------
// [1.4.5·清单 6] 音量交互甲+丙解耦
//  - 拖动过程内部连续浮点「视觉位置」(无级)，不再量化到 200 整数档；
//  - 等响/对数曲线：视觉位置 -> 系统音量 fraction，低音量区拉宽，缓解「加一格过大」；
//  - 速度阻尼：慢拖细分(0.35)、中速(0.65)、快拖粗调(1.0)，缓解大播放页手抖多跨格；
//  - 松手吸附回 0..200 视觉刻度；
//  - ± 逐格微调按钮：不拖也能精确 ±1 格。
//  - 默认彩色主题、音量条 drawable / 配色 / level 0..10000 口径一律不动；
//    仅因新增 ± 按钮使可拖行程两侧各少 40dp，音量条本体绘制逻辑不变。
//  - [1.4.17·V3] 视觉段数翻倍：200 → 400，拖动/等响曲线更细腻（用户拍板）。
//  - 如实边界：最终写入的仍是系统 STREAM_MUSIC 离散档位（各 ROM 常见 15 档），
//    这一步是系统限制、不是 App 实现选择；翻倍只提升拖动手感与吸附精度，
//    实际音量档数不变。
// ---------------------------------------------------------------------------
private const val VolumeVisualSteps = 400
private const val VolumeCurveK = 2.2f
private val VolumeCurveDenom = kotlin.math.exp(VolumeCurveK) - 1f

// [1.4.12·S01] 音量地基重构常量：连按加速 + 低档一竞速静音。
// [1.4.20·音量±键统一一格一格] 下面 4 个加速/跳 0 常量已删除：用户实测「跨度不一致」，
// 明确要求大播放页加减键一格一格地调。静音靠逐格减到 0，不再有连按加速与「≤5 档一击静音」。

/** 视觉位置(0..1) -> 系统音量 fraction(0..1)；单调递增、可逆，端点精确 0/1。 */
internal fun volumeVisualToSystem(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    return ((kotlin.math.exp(VolumeCurveK * x) - 1f) / VolumeCurveDenom).coerceIn(0f, 1f)
}

/** 反函数：系统音量 fraction -> 视觉位置（把系统实际音量回显到条上）。 */
internal fun volumeSystemToVisual(v: Float): Float {
    val x = v.coerceIn(0f, 1f)
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    return (kotlin.math.ln(1f + x * VolumeCurveDenom) / VolumeCurveK).coerceIn(0f, 1f)
}

/** 慢拖细分 / 快拖粗调：按单次指针位移(px)给增量乘一个阻尼系数。 */
internal fun volumeDragDamping(speedPx: Float): Float =
    when {
        speedPx < 2f -> 0.35f
        speedPx < 8f -> 0.65f
        else -> 1f
    }

// [护栏 G-28·1.4.6 落地] ±键 indication=null 去 ripple 灰盘 + 顶栏同款弹簧缩放，不新造样式
// [1.4.6·±键动效] 与顶栏最右按钮完全同一套按压语言（SmartisanTitleBar.kt L234-272）：
// indication=null（压根没有 ripple 变暗圈）+ collectSmartisanPressedAsState + 弹簧缩放。
// 参数与顶栏常量一致，不新造样式；clip(CircleShape) 保留命中区但不画任何底色，
// 源码里本来就没有 background，那个「浅灰实心圆盘」只是默认 ripple 被裁剪后的按下态。
private const val VolumeStepPressedScale = 1.33f
private const val VolumeStepDampingRatio = 0.55f
private const val VolumeStepStiffness = 800f
private const val VolumeStepVisibilityThreshold = 0.0015f

@Composable
private fun PlaybackVolumeStepButton(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) VolumeStepPressedScale else 1f,
        animationSpec = spring(
            dampingRatio = VolumeStepDampingRatio,
            stiffness = VolumeStepStiffness,
            visibilityThreshold = VolumeStepVisibilityThreshold,
        ),
        label = "playback volume step press",
    )
    Box(
        modifier =
            modifier
                .size(PlaybackVolumeStepButtonSize)
                .clip(CircleShape)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,   // [1.4.6] 去掉默认 ripple 灰暗圈
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // [护栏 G-39·1.4.8 落地] ±键视觉规范：细线字重 + 次级色，按下才高亮为主题色；
        // 位置随 PlaybackVolumeHorizontalPadding 对齐到轨道端点，不再贴边突兀。
        // [护栏 G-39] 颜色取现有资源，不新增调色板：次级色 playing_subtitle_text，高亮色 playing_title_text。
        val stepIdleColor = colorResource(R.color.playing_subtitle_text).copy(alpha = 0.78f)
        val stepPressedColor = colorResource(R.color.playing_time_text)
        Text(
            text = label,
            style = PlaybackTimeStyle.copy(
                fontSize = 20.sp,
                fontWeight = if (pressed) FontWeight.Bold else FontWeight.Light,
                color = if (pressed) stepPressedColor else stepIdleColor,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * [v22·音量] USB 独占时的音量胶囊：显示当前实际走的是 DAC 硬件音量还是数字音量，
 * 以及对应的 dB。点按弹出快切（自动 / 数字音量 / DAC 硬件音量）。
 *
 * 不做"跳设置页"：本 App 的设置页挂在「更多」二级页下，从播放页跳过去要穿过壳层导航，
 * 风险大于收益；快切弹窗能直接把模式切了，比跳三层更顺手。
 */
@Composable
internal fun PlaybackVolumeBadge(modifier: Modifier = Modifier) {
    val hwActive by UsbVolume.hardwareActiveFlow.collectAsState()
    val currentDb by UsbVolume.currentDbFlow.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val modeName =
        stringResource(
            if (hwActive) R.string.usb_volume_mode_dac else R.string.usb_volume_mode_digital
        )
    val text =
        stringResource(
            R.string.usb_volume_current,
            modeName,
            "%.1f dB".format(java.util.Locale.US, currentDb),
        )
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colorResource(R.color.playing_subtitle_text).copy(alpha = 0.10f))
            .clickable { showDialog = true }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style =
                PlaybackTimeStyle.copy(
                    fontSize = 11.sp,
                    color = colorResource(R.color.playing_subtitle_text).copy(alpha = 0.86f),
                ),
        )
    }
    if (showDialog) {
        UsbVolumeQuickDialog(onDismiss = { showDialog = false })
    }
}

@Composable
private fun UsbVolumeQuickDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mode by UsbVolumeModeStore.mode.collectAsState()
    // [1.4.30·R170] 三态：DETECTED 不许说成"硬件可用"，零损失提示也要单独一档。
    val hwVerification by UsbVolume.hardwareVerificationFlow.collectAsState()
    val zeroLoss = UsbExclusiveSinkRef.zeroLossEnabled
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .monetContentBackground(colorResource(R.color.surface_card))
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                stringResource(R.string.usb_volume_mode),
                style =
                    PlaybackTimeStyle.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorResource(R.color.playing_title_text),
                    ),
            )
            Spacer(Modifier.height(4.dp))
            UsbVolumeQuickOption(
                R.string.usb_volume_mode_auto,
                R.string.usb_volume_mode_desc_auto,
                mode == UsbVolumeMode.AUTO,
            ) { UsbVolumeModeStore.set(context, UsbVolumeMode.AUTO); onDismiss() }
            UsbVolumeQuickOption(
                R.string.usb_volume_mode_digital,
                R.string.usb_volume_mode_desc_digital,
                mode == UsbVolumeMode.DIGITAL,
            ) { UsbVolumeModeStore.set(context, UsbVolumeMode.DIGITAL); onDismiss() }
            UsbVolumeQuickOption(
                R.string.usb_volume_mode_dac,
                R.string.usb_volume_mode_desc_hardware,
                mode == UsbVolumeMode.HARDWARE,
            ) { UsbVolumeModeStore.set(context, UsbVolumeMode.HARDWARE); onDismiss() }
            if (zeroLoss) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        when (hwVerification) {
                            UsbVolume.HardwareVerification.VERIFIED ->
                                R.string.usb_volume_mode_zero_loss_forced
                            UsbVolume.HardwareVerification.DETECTED ->
                                R.string.usb_volume_mode_zero_loss_hw_detected
                            else -> R.string.usb_volume_mode_zero_loss_no_hw
                        }
                    ),
                    style =
                        PlaybackTimeStyle.copy(
                            fontSize = 12.sp,
                            color = colorResource(R.color.playing_subtitle_text),
                        ),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.settings_desc_dialog_button),
                Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style =
                    PlaybackTimeStyle.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorResource(R.color.btn_text_color_red),
                    ),
            )
        }
    }
}

@Composable
private fun UsbVolumeQuickOption(
    title: Int,
    desc: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(title),
                style =
                    PlaybackTimeStyle.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = colorResource(R.color.playing_title_text),
                    ),
            )
            Text(
                stringResource(desc),
                style =
                    PlaybackTimeStyle.copy(
                        fontSize = 12.sp,
                        color = colorResource(R.color.playing_subtitle_text),
                    ),
            )
        }
        if (selected) {
            Text(
                "✓",
                Modifier.padding(start = 8.dp),
                style =
                    PlaybackTimeStyle.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorResource(R.color.btn_text_color_red),
                    ),
            )
        }
    }
}

@Composable
internal fun PlaybackVolumeBar(
    value: Float,
    width: Dp,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val horizontalPaddingPx = with(density) { PlaybackVolumeHorizontalPadding.roundToPx() }
    val thumbOffsetPx = with(density) { PlaybackVolumeThumbOffset.roundToPx() }
    val thumbTouchPaddingPx = with(density) { 4.dp.toPx() }
    var pressed by remember { mutableStateOf(false) }
    var dragVisual by remember { mutableFloatStateOf(0f) }
    // 拖动中用本地连续值渲染，避免系统档位回显把「无级」打回离散；松手后回到外部真值。
    val visual = if (pressed) dragVisual else volumeSystemToVisual(value.coerceIn(0f, 1f))
    // [口径不变] drawable level 仍是 0..10000，只是由连续 visual 计算、不再先量化到 200 档。
    val progressLevel = (visual * 10_000f).roundToInt().coerceIn(0, 10_000)
    val latestVisual = rememberUpdatedState(visual)
    val latestOnValueChange = rememberUpdatedState(onValueChange)
    val track =
        rememberSmartisanDrawablePainter(
            R.drawable.volume_seekbar_progress,
            pressed = pressed,
            progressLevel = progressLevel,
        )
    val thumb =
        rememberSmartisanDrawablePainter(R.drawable.playing_control_volume, pressed = pressed)
    val description = stringResource(R.string.volume)
    val stepDownDescription = stringResource(R.string.volume_step_down)
    val stepUpDescription = stringResource(R.string.volume_step_up)
    // [1.4.9·G-44] ±键自维护目标档位（-1=未初始化，首次跟随系统）。
    var lastNudgeTarget by remember { mutableIntStateOf(-1) }
    // [1.4.20] 连按加速 / 低档一击静音的时序状态已移除：加减一律一格一格，见 nudge()。

    // [护栏 G-26·1.4.6 落地] ±键按系统档位 ±1 步进，底层 STREAM_MUSIC 落点不动、不翻 1.2.6
    // [1.4.6·G-22 根治] ± 键不再走「视觉 1/400 格 → 等响曲线 → fraction → 系统档位
    // roundToInt」这条链路：1.4.5 里 volumeVisualToSystem(1/400) 极小 × 150 档后
    // roundToInt()=0，每次 ±1 都跨不过一个系统档位且回读仍从 0 起算 → ± 键全失灵
    // → roundToInt()=0，每次 ±1 都跨不过一个系统档位，且 visual 从系统回读仍是 0，
    // 下次点击又从 0 起算 → ± 键三包全失灵。改为**直接按系统 STREAM_MUSIC 档位 ±1
    // 步进**：读当前真实档位、clamp 后写回、把实际 fraction 回显。底层落点一点没动，
    // 不翻 1.2.6「音量交系统弹窗/音量键联动/无音量模式设置项」的三条决策；
    // 拖动仍走连续浮点曲线（G-19 手感不变），只有 ± 键走离散档位，分工清晰。
    fun nudge(deltaSteps: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        if (am == null) {
            AppLog.w("Volume", "1.4.16(V1) AudioManager 不可用，± 键本次跳过")
            return
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sysCur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        // [1.4.16·V1] 唯一权威基准 = App 自维护目标档位；仅首次(-1)或物理键大幅改动(偏差>1)
        // 时才跟随系统。绝不把被 UAC 弹回的脏档位回读当成下一次基准——
        // 这正是"减到某档后越按越反弹、降不到 0"的真正根因。
        val base = if (lastNudgeTarget in 0..max && abs(sysCur - lastNudgeTarget) <= 1) {
            lastNudgeTarget
        } else {
            sysCur
        }

        // [1.4.20·音量±键统一一格一格] 旧实现减键连按加速到 10 档、剩余≤5 档一击静音，
        // 与加键恒 +1 手感不一致（用户实测「跨度不一致」）。现加减一律 step=1，
        // 想静音就一格一格减到 0（底层允许写 0，见 onValueChange 的写入口）。
        val step = 1
        val targetIdx = (base + deltaSteps * step).coerceIn(0, max)
        // 目标一旦确定即锁定为权威基准，不再被写后回读覆盖。
        lastNudgeTarget = targetIdx
        val targetFraction = targetIdx.toFloat() / max.toFloat()
        AppLog.i(
            "Volume",
            "1.4.20 ±键 delta=$deltaSteps sys=$sysCur base=$base step=1" +
                " -> $targetIdx/$max fraction=$targetFraction（一格一格，无加速/无跳0）",
        )
        // [1.4.16·V1] 唯一写入口：只经 onValueChange → setMusicStreamVolumeFraction。
        // 静音/解除静音/保护窗口/系统写入全部集中在那里，杜绝此前"写两遍互相打架"。
        latestOnValueChange.value(targetFraction)
    }

    // [护栏 G-27·1.4.6 落地] 音量条本体独占完整 width，± 按钮叠两端不占宽、左右镜像
    // [1.4.6·G-23 根治] 1.4.5 把两个 40dp 按钮塞进固定宽度 Row.width(width) 内，
    // Canvas(weight 1f) 被抠掉 80dp → 音量条本体剪短、四段间距 0/81/156/45 不对称、
    // 减号贴屏幕边。改为 Box 叠加：Canvas 独占完整 width（音量条恢复原宽），
    // ± 按钮对称叠在两端 padding 区（本就是拇指命中区之外），不占 Row 宽度、左右镜像。
    Box(
        modifier = modifier.width(width).height(PlaybackVolumeBarHeight),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(PlaybackVolumeBarHeight)
                .progressSemantics(visual, steps = VolumeVisualSteps - 1)
                .semantics {
                    contentDescription = description
                    setProgress { requested ->
                        val steps =
                            (requested.coerceIn(0f, 1f) * VolumeVisualSteps)
                                .roundToInt().coerceIn(0, VolumeVisualSteps)
                        latestOnValueChange.value(
                            volumeVisualToSystem(steps / VolumeVisualSteps.toFloat())
                        )
                        true
                    }
                }
                .focusable()
                .pointerInput(horizontalPaddingPx, thumbOffsetPx, thumbTouchPaddingPx, thumb) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val available = size.width - horizontalPaddingPx * 2
                        val thumbWidth = thumb.intrinsicSize.width
                        if (
                            available <= 0 ||
                                !playbackVolumeThumbHit(
                                    down.position.x,
                                    horizontalPaddingPx.toFloat(),
                                    available.toFloat(),
                                    latestVisual.value,
                                    thumbWidth,
                                    thumbTouchPaddingPx,
                                )
                        )
                            return@awaitEachGesture
                        // [1.4.5·清单 6] 改成增量拖动：按下不跳、慢拖细分、快拖粗调。
                        // 原实现是「绝对位置 + grabOffset」，手一抖就跨好几格；增量位移
                        // 天然保持拇指与手指相对偏移，grabOffset 不再需要。
                        var lastX = down.position.x
                        var localVisual = latestVisual.value
                        var dampingSamples = 0
                        fun update(x: Float) {
                            val dx = x - lastX
                            lastX = x
                            if (dx == 0f) return
                            val damping = volumeDragDamping(abs(dx))
                            dampingSamples++
                            localVisual =
                                (localVisual + dx / available.toFloat() * damping)
                                    .coerceIn(0f, 1f)
                            dragVisual = localVisual
                            // 拖动过程连续浮点，经等响曲线写系统音量（无级增益交互层）。
                            latestOnValueChange.value(volumeVisualToSystem(localVisual))
                        }
                        down.consume()
                        dragVisual = localVisual
                        pressed = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                update(change.position.x)
                                change.consume()
                            } while (change.pressed)
                        } finally {
                            pressed = false
                            // 松手吸附到 0..200 视觉刻度，再按曲线写一次系统音量。
                            // [1.4.9·G-45] 端点锁定：拖到最右/最左后，阻尼采样不得把
                            // localVisual 从 1.0 拉回 0.985/0.995（用户实机「回弹」根因）。
                            val rawSteps =
                                (localVisual * VolumeVisualSteps).roundToInt()
                                    .coerceIn(0, VolumeVisualSteps)
                            val atMax = rawSteps >= VolumeVisualSteps - 1
                            val atMin = rawSteps <= 1
                            val snappedSteps = when {
                                atMax -> VolumeVisualSteps
                                atMin -> 0
                                else -> rawSteps
                            }
                            val snapped = snappedSteps / VolumeVisualSteps.toFloat()
                            latestOnValueChange.value(volumeVisualToSystem(snapped))
                            AppLog.i(
                                "Volume",
                                "1.4.9(G-45) 音量解耦手势结束：松手吸附 visual=$snapped" +
                                    " steps=${(snapped * VolumeVisualSteps).roundToInt()}/$VolumeVisualSteps" +
                                    " dampingSamples=$dampingSamples endpoint=${atMax || atMin}" +
                                    " -> system=${volumeVisualToSystem(snapped)}",
                            )
                        }
                    }
                }
        ) {
            val available = (size.width - horizontalPaddingPx * 2).coerceAtLeast(0f)
            val thumbSize = thumb.intrinsicSize
            // ProgressBar's unstyled maxHeight starts at 48px and grows to drawable.minimumHeight.
            val trackHeight = minOf(size.height, maxOf(48f, track.intrinsicSize.height))
            val trackTop: Int
            val thumbTop: Int
            if (thumbSize.height > trackHeight) {
                thumbTop = ((size.height - thumbSize.height) / 2).toInt()
                trackTop = thumbTop + ((thumbSize.height - trackHeight) / 2).toInt()
            } else {
                trackTop = ((size.height - trackHeight) / 2).toInt()
                thumbTop = trackTop + ((trackHeight - thumbSize.height) / 2).toInt()
            }
            translate(horizontalPaddingPx.toFloat(), trackTop.toFloat()) {
                with(track) { draw(Size(available, trackHeight)) }
            }
            val thumbLeft =
                playbackVolumeThumbLeft(
                    horizontalPaddingPx.toFloat(),
                    available,
                    thumbSize.width,
                    thumbOffsetPx.toFloat(),
                    visual,
                )
            translate(thumbLeft, thumbTop.toFloat()) { with(thumb) { draw(thumbSize) } }
        }
        // [1.4.9·G-43] ±键几何：原 CenterStart 中心落在 20dp、轨道起点 51.3dp，
        // 「中心到屏边20」≠「中心到轨道31.3」。加 5.65dp 偏移后中心=25.65dp，
        // 键左缘到屏边=键右缘到轨道=5.65dp，左右镜像对称（用户实机截图定值）。
        PlaybackVolumeStepButton(
            "-", stepDownDescription,
            Modifier.align(Alignment.CenterStart).offset(x = 5.649994.dp),
        ) { nudge(-1) }
        PlaybackVolumeStepButton(
            "+", stepUpDescription,
            Modifier.align(Alignment.CenterEnd).offset(x = (-5.649994).dp),
        ) { nudge(1) }
    }
}

internal fun playbackVolumeThumbLeft(
    padding: Float,
    availableWidth: Float,
    thumbWidth: Float,
    thumbOffset: Float,
    fraction: Float,
): Float =
    padding - thumbOffset +
        (fraction * (availableWidth - thumbWidth + 2 * thumbOffset) + 0.5f).toInt()

internal fun playbackVolumeThumbHit(
    x: Float,
    padding: Float,
    availableWidth: Float,
    fraction: Float,
    thumbWidth: Float,
    touchPadding: Float,
): Boolean = abs(x - (padding + availableWidth * fraction)) <= thumbWidth / 2 + touchPadding

internal fun playbackVolumeTouchProgress(
    x: Float,
    padding: Float,
    availableWidth: Float,
    grabOffset: Float,
): Int {
    val roundedX = x.roundToInt().toFloat()
    val fraction =
        when {
            roundedX < padding -> 0f
            roundedX > padding + availableWidth -> 1f
            else -> (roundedX - padding) / availableWidth + grabOffset
        }
    return (fraction * 200f).roundToInt().coerceIn(0, 200)
}
