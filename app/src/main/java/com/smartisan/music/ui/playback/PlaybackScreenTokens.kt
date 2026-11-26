package com.smartisan.music.ui.playback

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

internal val PlaybackPageBackground: Color
    @Composable get() = colorResource(R.color.page_background)
private val PlaybackTitleColor: Color
    @Composable get() = colorResource(R.color.playing_title_text)
private val PlaybackSubtitleColor: Color
    @Composable get() = colorResource(R.color.playing_subtitle_text)
private val PlaybackTimeColor: Color
    @Composable get() = colorResource(R.color.playing_time_text)

internal const val PlaybackDiscCycleDurationMs = 15_500f
internal const val ScratchCycleDurationMs = 1_800f
internal const val DiscRotationDegrees = 360f
internal const val ScratchHubDeadZoneRatio = 0.06f
internal const val PlaybackScratchMinMotionDegrees = 0.18f
internal const val PlaybackScratchMaxDeltaTimeMs = 72L
internal const val ScratchMaxAngleStepDegrees = 54f
internal const val ScratchVelocityMaxDegreesPerSecond = 1_000f
internal const val ScratchFlingMinVelocityDegreesPerSecond = 160f
internal const val ScratchFlingReleaseTimeoutMs = 120L
internal const val ScratchFlingVelocitySampleWindowMs = 120L
internal const val ScratchFlingMinVelocitySampleMs = 16L
internal const val ScratchFlingDurationMultiplier = 1.5f
internal const val ScratchFlingPlayingRewindDurationScale = 1.2f
internal const val ScratchFlingFrameDivisor = 1_700f
internal const val ScratchPixelFlingDivisor = 40f
internal const val ScratchWarmupRefreshMs = 3_000L
internal const val ScratchPlaybackVelocityDegreesPerSecond =
    DiscRotationDegrees * 1_000f / PlaybackDiscCycleDurationMs
internal const val CoverPreviewTimeoutMs = 260L
internal const val CoverPreviewSettleToleranceMs = 24L
internal const val NeedleSeekSettleHoldTimeoutMs = 900L
internal const val NeedleLiftHoldAfterReleaseMs = 250L
internal const val OriginalTurntableBaseWidthDp = 360f
internal const val OriginalLargeNeedleBreakpointScale = 411f / OriginalTurntableBaseWidthDp
internal const val OriginalNeedleTouchStartRatio = 0.8f
internal const val OriginalNeedleWidthBaseDp = 73.3f
internal const val OriginalNeedleHeightBaseDp = 310f
internal const val OriginalNeedleTopWidthBaseDp = 41f
internal const val OriginalNeedleTopMarginBaseDp = 25.5f
internal const val OriginalNeedleRightMarginDp = 2.5f
internal const val OriginalNeedleShadowRightMarginDp = 2f
internal const val OriginalNeedlePivotXDp = 48f
internal const val OriginalNeedlePivotYDp = 28f
internal const val OriginalNeedleHeightLargeDp = 354.6953f
internal const val OriginalNeedleTopMarginLargeDp = 29.199982f
internal const val OriginalNeedleRightMarginLargeDp = 2.7999878f
internal const val OriginalNeedleShadowRightMarginLargeDp = 2.2999878f
internal const val NeedleSeekOutsideActivationDistanceDp = 36f
internal const val NeedleSeekStartPositionGuardMs = 1_000L
internal const val NeedleRestRotationDegrees = 0f
internal const val NeedlePlaybackStartRotationDegrees = 12f
internal const val NeedlePlaybackSweepDegrees = 22.3f
internal const val NeedlePlaybackEndRotationDegrees =
    NeedlePlaybackStartRotationDegrees + NeedlePlaybackSweepDegrees
internal const val NeedleLiftScaleY = 0.98f
internal const val NeedleLiftShadowRotationOffsetDegrees = 4f
internal const val PlaybackAlbumArtDiameterRatio = 405f / 1080f
internal const val PlaybackTurntableAxisDiameterRatio = 62f / 1080f
internal const val PlaybackTurntableAxisSourceDiameterPx = 60

internal val PlaybackVisualStageTopPadding = 16.dp
// [护栏 G-40·1.4.8 落地] 唱盘视觉组整体下移量：把唱盘中心锚到约 44% 屏高（最早版本位置），
// 用户 23 号页第 1 问拍板。28dp 为静态估算（图1 40.4% → 图2 43.8%，跨约 27dp@800dp 内容高）；
// 观感待实机，可在此单一常量微调。
internal val PlaybackVisualStageAnchorOffsetY = 28.dp
// Music 8.1.0: audio_player.xml and the source xxhdpi playback assets.
internal val PlaybackSeekBarHeight = 41.dp
internal val PlaybackSeekBarHorizontalPadding = 51.299988.dp
internal val PlaybackSeekTrackDrawableHeight = (41f / 3f).dp
internal val PlaybackSeekThumbWidth = (67f / 3f).dp
internal val PlaybackSeekThumbHeight = 41.dp
internal val PlaybackSeekBarDividerHeight = 0.7.dp
internal val PlaybackVolumeBarHeight = 60.dp
// 1.4.5 清单 6：音量条两侧 ± 逐格微调按钮尺寸（不占音量条 drawable / 配色）
internal val PlaybackVolumeStepButtonSize = 40.dp
// [护栏 G-32·1.4.7 落地] 四段等距（20 号页定稿：按 −/+ 符号笔画边界量距、m=0、四段 g 相等）。
// 几何：符号中心 = b/2 = 20dp，轨道左端 = padding；g1 = 20 − w/2，g2 = padding − 20 − w/2。
// 令 g1 = g2 ⇒ padding = 2·(m + b/2) = 40dp；符号笔画宽 w 在等式两边抵消 →
// 与量距口径无关、与动态 width 无关。音量条长 L = W − 2·padding = W − 80（W=360dp 时 L=280dp）；
// g3/g4 由左右镜像自动相等。Box/Canvas/滑块/±键/拖动/动画一律一字不动。
// thumbOffset=5dp 保持：可见滑块边缘仍与外框端重合（20 号页第 4 问；静态几何推演，观感待实机）。
// [护栏 G-39·1.4.8 落地] 音量条口径改为对齐进度条端点（23 号页第 3 问拍板）：
// padding 取与进度条相同的 51.299988dp，音量条轨道两端与进度条两端在同一垂直线上；
// G-32 的四段等距沿革保留在其注释中作为历史事实，不作删除。观感待实机。
internal val PlaybackVolumeHorizontalPadding = 51.299988.dp
internal val PlaybackVolumeThumbOffset = 5.dp
internal val PlaybackActionButtonSize = 31.dp
internal val PlaybackMinimumTouchTargetSize = 48.dp
internal val PlaybackControlEntranceOffset = 186.dp
internal val PlaybackControlButtonsTopPadding = 6.dp
internal val PlaybackBottomControlsVolumeTopPadding = 18.dp
internal val PlaybackBottomControlsContentBottomPadding = 19.dp
internal val PlaybackBottomControlsBottomSpacing = 14.dp
internal val PlaybackBottomControlsMinimumBottomSpacing = 16.dp

internal const val PlaybackEntranceTotalDurationMillis = 980
internal const val PlaybackTurntableEntranceDurationMillis = 300
internal const val PlaybackControlEntranceDurationMillis = 240
internal const val PlaybackPlayButtonEntranceDelayMillis = 100
internal const val PlaybackSideButtonEntranceDelayMillis = 150
internal const val PlaybackVolumeEntranceDelayMillis = 155
internal const val PlaybackOuterButtonAlphaDelayMillis = 500
internal const val PlaybackOuterButtonAlphaDurationMillis = 480
internal const val PlaybackVisualPageEnterDurationMillis = 240
internal const val PlaybackVisualPageExitDurationMillis = 180
internal const val PlaybackVisualPageEnterDelayMillis = 40
internal const val PlaybackLyricsActionEnterDelayMillis = 70

internal val PlaybackControlEasing = Easing { fraction ->
    val inverse = 1f - fraction.coerceIn(0f, 1f)
    1f - inverse * inverse * inverse
}

internal fun playbackEntranceProgress(
    timeMillis: Float,
    delayMillis: Int,
    durationMillis: Int,
): Float {
    if (durationMillis <= 0) {
        return 1f
    }
    val linear = ((timeMillis - delayMillis.toFloat()) / durationMillis.toFloat()).coerceIn(0f, 1f)
    val inverse = 1f - linear
    return 1f - inverse * inverse * inverse
}

internal val PlaybackTitleStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = PlaybackTitleColor,
        )
internal val PlaybackArtistStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 11.sp,
            color = PlaybackSubtitleColor,
        )
internal val PlaybackTimeStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = PlaybackTimeColor,
        )
