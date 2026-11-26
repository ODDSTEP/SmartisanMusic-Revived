package com.smartisan.music.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.theme.monetTitleBarSurface
import com.smartisan.music.ui.navigation.LocalProjectedNavigationTitle
import com.smartisan.music.ui.navigation.LocalSmartisanTitleMotion
import com.smartisan.music.ui.navigation.LocalSmartisanTitleVisible
import com.smartisan.music.ui.navigation.ProjectableNavigationTitle

internal data class SmartisanTitleBarAction(
    @param:DrawableRes val iconRes: Int,
    val contentDescription: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val modifier: Modifier = Modifier,
    /**
     * 可选的统一着色：用于没有 enabled/pressed 状态变体的普通 PNG 图标（如眼睛），
     * 让它与相邻的选择器图标保持同样的深浅——可用时与删除图标同深，禁用时同浅。
     */
    val tint: Color? = null,
    /** 1.3.6：图标等比放大系数（仅用于歌曲页三角形按钮，1f = 原尺寸）。 */
    val iconScale: Float = 1f,
    /**
     * [1.4.24·R21] 自定义矢量图标，优先于 [iconRes]。
     *
     * 歌曲页顶栏三角从 v19 的 PNG 位图改成矢量绘制：原 PNG 是 alpha=110 的 28×16
     * 软边位图，1.7 倍放大后边缘发虚（用户说的"像素密度不够"）。矢量绘制在任意
     * DPI / 缩放下都是清晰的，且能拿到 pressed 状态做明暗反馈。
     *
     * 参数：绘制区域 Modifier、是否按下。
     */
    val customIcon: (@Composable (Modifier, Boolean) -> Unit)? = null,
)

/** 禁用态的整体透明度：与 titlebar_icon_delete_dis / _nor 的透明度比例一致。 */
internal const val TintedIconDisabledAlpha = 0.3f

/** 无状态变体图标的统一着色灰：与滑动/标题栏删除图标的视觉深浅一致。 */
internal val StatefulIconTint = Color(0xFF989898)

/** Resource-based title bar with physical left/right action groups and a symmetric center. */
@Composable
internal fun SmartisanTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: SmartisanTitleBarAction? = null,
    action: SmartisanTitleBarAction? = null,
    includeStatusBar: Boolean = true,
    showShadow: Boolean = true,
    navigationActions: List<SmartisanTitleBarAction> = emptyList(),
    actions: List<SmartisanTitleBarAction> = emptyList(),
    centerContent: (@Composable () -> Unit)? = null,
    contentHeight: androidx.compose.ui.unit.Dp = dimensionResource(R.dimen.title_bar_height),
) {
    if (!LocalSmartisanTitleVisible.current) return
    if (LocalProjectedNavigationTitle.current != null) {
        val height =
            contentHeight +
                if (includeStatusBar)
                    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                else 0.dp
        ProjectableNavigationTitle(modifier, height) { titleModifier ->
            SmartisanTitleBar(
                title = title,
                modifier = titleModifier,
                navigationIcon = navigationIcon,
                action = action,
                includeStatusBar = includeStatusBar,
                showShadow = showShadow,
                navigationActions = navigationActions,
                actions = actions,
                centerContent = centerContent,
                contentHeight = contentHeight,
            )
        }
        return
    }
    val titleMotion = LocalSmartisanTitleMotion.current
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
    val iconSize = dimensionResource(R.dimen.standard_icon_size)
    val edgeMargin = dimensionResource(R.dimen.bar_margin_edge)
    val leftActions = listOfNotNull(navigationIcon) + navigationActions
    val rightActions = listOfNotNull(action) + actions
    val gap = dimensionResource(R.dimen.title_bar_margin_view)
    val widestCount = maxOf(leftActions.size, rightActions.size)
    val titleInset =
        if (widestCount > 0) edgeMargin + iconSize * widestCount + gap * (widestCount - 1) else 0.dp
    val centerVisible =
        widestCount == 0 || titleInset + gap <= dimensionResource(R.dimen.title_bar_center_limite)
    val contentAlpha = titleMotion?.contentAlpha ?: 1f
    val leftAlpha = titleMotion?.leftAlpha ?: 1f
    val leftSlide = with(LocalDensity.current) { (iconSize + edgeMargin * 2).toPx() }
    Column(
        modifier
            .then(if (showShadow) Modifier.zIndex(1f) else Modifier)
            .fillMaxWidth()
            .then(
                if (titleMotion == null)
                    // [v12] 顶栏向底栏看齐：莫奈开=底栏同款色，莫奈关=官方原色零改动。
                    Modifier.background(monetTitleBarSurface())
                else Modifier
            )
    ) {
        if (includeStatusBar) {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        }
        Box(Modifier.fillMaxWidth().height(contentHeight)) {
            if (centerVisible && contentAlpha > 0f) {
                if (centerContent == null) {
                    SmartisanTitleText(
                        title,
                        titleInset,
                        Modifier.matchParentSize().graphicsLayer { alpha = contentAlpha },
                    )
                } else {
                    Box(
                        Modifier.matchParentSize().padding(horizontal = titleInset).graphicsLayer {
                            alpha = contentAlpha
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        centerContent()
                    }
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (leftAlpha > 0f)
                    Row(
                        Modifier.align(AbsoluteAlignment.CenterLeft)
                            .absolutePadding(left = edgeMargin)
                            .graphicsLayer {
                                alpha = leftAlpha
                                translationX = (titleMotion?.leftOffset ?: 0f) * leftSlide
                            },
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        leftActions.forEach { TitleBarIcon(it) }
                    }
                if (contentAlpha > 0f)
                    Row(
                        Modifier.align(AbsoluteAlignment.CenterRight)
                            .absolutePadding(right = edgeMargin)
                            .graphicsLayer { alpha = contentAlpha },
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        rightActions.asReversed().forEachIndexed { index, act ->
                            // asReversed 后最后一个渲染的即视觉最右按键
                            TitleBarIcon(act, isRightmost = index == rightActions.size - 1)
                        }
                    }
            }
            if (showShadow && (titleMotion == null || !titleMotion.detail)) {
                SmartisanDrawableBackground(
                    R.drawable.title_bar_shadow,
                    Modifier.align(Alignment.BottomCenter)
                        .offset(y = shadowHeight)
                        .fillMaxWidth()
                        .height(shadowHeight),
                )
            }
        }
    }
}

@Composable
private fun TitleBarIcon(
    action: SmartisanTitleBarAction,
    modifier: Modifier = Modifier,
    isRightmost: Boolean = false,
) {
    val motionInteractive = LocalSmartisanTitleMotion.current?.interactive ?: true
    // [护栏 G-12·台账登记] 顶栏不对整张双色 PNG 做 SrcIn 压平 / 不镂空 / 不挖 / 不重绘；
    // 防「完成按钮变棕框 / 蓝底丢失 / 丑圆角方块 / 浅色镂空没挖透」。删此护栏会复发病。
    // 1.3.9：撤销 1.3.8 的「整张 PNG SrcIn 单色压平 + 纯色 6dp 圆角正方形」——
    // 那是「完成按钮变棕色圆角空框 / 对勾蓝底丢失」和「丑的圆角正方形」的共同根因。
    // 完成/确认按钮一律保持原样（图3 淡蓝圆角方块 + 蓝对勾）；其余最右按键在非默认
    // 主题下改用「图3 完成按钮同款淡蓝实心圆角方块形状」作主题色背景。
    val palette = currentThemePalette()
    val isConfirmButton =
        action.iconRes == R.drawable.standard_icon_hignlight_confirm_selector ||
            action.iconRes == R.drawable.standard_icon_complete_selector
    val useThemedBlock = isRightmost && palette != null && !isConfirmButton
    // [回退 v11·搜索图标染色] 搜索放大镜恢复 v10 原样：不单独染色，走 forceWhite。
    // 1.4.2：顶栏最右图标不再按深浅色分流两种背景。浅色/深色一律用「主题色实心
    // 圆角方块」（同款 ConfirmBlock），图标本身由 forceWhite 染白（浅色实心纯白 /
    // 深色白 40%，逻辑在 painter 内按 uiMode 处理）。不镂空、不挖、不重绘。
    val iconSize = dimensionResource(R.dimen.standard_icon_size)
    val viewConfiguration = LocalViewConfiguration.current
    val iconViewConfiguration =
        remember(viewConfiguration, iconSize) {
            object : ViewConfiguration by viewConfiguration {
                override val minimumTouchTargetSize = DpSize(iconSize, iconSize)
            }
        }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) TitleBarPressedScale else 1f,
            animationSpec =
                spring(
                    dampingRatio = TitleBarDampingRatio,
                    stiffness = TitleBarStiffness,
                    visibilityThreshold = TitleBarVisibilityThreshold,
                ),
            label = "Smartisan title icon press",
        )
    // Preserve this shim button's 36dp hit bounds and transform input with the whole icon.
    // [1.4.26·R54 重做] 删掉搜索图标的单独染色分支。原实现有两套取色路径：
    // 搜索图标在开莫奈时染 monetizedPalette().onSurfaceTint，而"重新扫描"图标走
    // forceWhite——两者不是同一个颜色，且未开莫奈时本来就同色，所以用户看不到变化。
    // 现在所有落在主题色块上的最右图标统一走 forceWhite，搜索与重新扫描逐字节同色。
    CompositionLocalProvider(LocalViewConfiguration provides iconViewConfiguration) {
        Box(
            modifier
                .then(action.modifier)
                .then(rememberIconNameHint(action.contentDescription))
                .size(iconSize)
                .then(
                    if (useThemedBlock)
                        Modifier.smartisanPainterBackground(
                            rememberThemedConfirmBlockPainter(palette!!)
                        )
                    else Modifier
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    enabled = action.enabled && motionInteractive,
                    onClick = smartisanClick(action.onClick),
                ),
            contentAlignment = Alignment.Center,
        ) {
            val iconModifier = Modifier.graphicsLayer {
                scaleX = action.iconScale
                scaleY = action.iconScale
                if (!action.enabled && action.tint != null) {
                    alpha = TintedIconDisabledAlpha
                }
            }.size(iconSize)
            if (action.customIcon != null) {
                // [1.4.24·R21] 矢量图标：不经过位图采样，pressed 时给一点加深反馈。
                action.customIcon(iconModifier, pressed)
            } else {
                Image(
                    painter =
                        rememberSmartisanDrawablePainter(
                            action.iconRes,
                            enabled = action.enabled,
                            pressed = pressed,
                            focused = focused,
                            checked = action.checked == true,
                            // 1.4.0 U3/Q4：只给顶栏最右一个图标染纯白；其余图标一个不动。
                            forceWhite = useThemedBlock,
                        ),
                    contentDescription = action.contentDescription,
                    contentScale = ContentScale.None,
                    // 1.3.9：绝不再对整张双色 PNG 做 SrcIn 压平，完成按钮恢复原样。
                    // [1.4.26·R54] 搜索图标无例外：与重新扫描等所有最右图标走同一条 forceWhite。
                    colorFilter =
                        if (useThemedBlock) null
                        else action.tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) },
                    modifier = iconModifier,
                )
            }
        }
    }
}

// TitleBar.TitleBarIconScaleTouchListener, retained from the calibrated 8.1.0 shim.
private const val TitleBarPressedScale = 1.33f
private const val TitleBarDampingRatio = 0.55f
private const val TitleBarStiffness = 800f
// DynamicAnimation 1.1.0: MIN_VISIBLE_CHANGE_SCALE (1/500) * THRESHOLD_MULTIPLIER (0.75).
private const val TitleBarVisibilityThreshold = 0.0015f

@Preview(name = "Title / light", widthDp = 360)
@Preview(name = "Title / dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Title / large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun SmartisanTitleBarPreview() {
    SmartisanTitleBar(
        title = stringResource(R.string.setting),
        navigationIcon =
            SmartisanTitleBarAction(
                R.drawable.standard_icon_back_selector,
                stringResource(R.string.back),
                onClick = {},
            ),
        includeStatusBar = false,
    )
}
