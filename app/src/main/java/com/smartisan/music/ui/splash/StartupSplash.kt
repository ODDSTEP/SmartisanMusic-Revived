package com.smartisan.music.ui.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.theme.monetBackgroundEnabled
import com.smartisan.music.ui.theme.monetizedPalette
import kotlinx.coroutines.delay

/**
 * 1.4.3 开屏定稿「方案①：左对齐 + 轻淡入 + 名称苏芳色」；1.4.4 增加深色版（N-02）。
 *
 * 用户拍板：内容整体左对齐、图标在上 / 名称 / slogan 依次错峰淡入
 * （轻淡入：图标 0.05s、名称 0.20s、slogan 0.34s 起，各 0.42s，带 9px 上移），
 * 名称取色彩主题里「苏芳」主色 [SuouColorLight] = #B35E59，
 * slogan「东半球最好用的音乐播放器」。
 *
 * 1.4.4：[isDark] 按**当前生效的深浅模式**选配色（等价「浅→深播深色版、深→浅播浅色版」，
 * 不必记录从哪来，也不引入跨 Activity 重建的状态机）：
 *  - 浅色版：白底 / 苏芳色名称 / 深黑 slogan —— **1.4.3 定稿一个字不动**；
 *  - 深色版：底色沿用深色主题页底 #25282D（对齐 values-night/colors.xml page_background），
 *    名称用提亮一档的苏芳色 #C97A75（深色底上 #B35E59 对比不足），slogan 浅灰 #C9C9CC。
 *  版式、左对齐、错峰轻淡入两侧完全共用，只有配色分支。
 *
 * 这是纯展示层，不读设置、不碰播放、不改任何默认彩色主题；动画播完自行淡出，
 * 由 [onFinished] 通知宿主移除，不阻塞主界面加载。
 */
// 浅色版（1.4.3 原样，一个字节不动）
private val SuouColorLight = Color(0xFFB35E59)
private val SloganColorLight = Color(0xFF141414)
private val BackgroundLight = Color.White
// 深色版（1.4.4 新增）
private val SuouColorDark = Color(0xFFC97A75)
private val SloganColorDark = Color(0xFFC9C9CC)
private val BackgroundDark = Color(0xFF25282D)

private const val SplashVisibleMs = 1000L
private const val FadeOutMs = 280

// [护栏 G-33·1.4.7 落地] 开屏和谐版（20 号页定稿）：两段间距相等、三元素左对齐、
// 三者当一个整体按上黄金点定位。组内字号/字重/字距按层级自定；图案/苏芳色/文案/错峰轻淡入
// /深浅配色/默认彩色主题一律一字不动。W=360 / H=780 为示意基准，实机不同屏按同一比例换算，观感待实机。
private const val SplashGroupCenterFraction = 0.382f
private val SplashGroupEstimatedHeightDp = 203.dp // 96+24+40+24+19 版式估算，非实测真值

@Composable
fun StartupSplash(
    onFinished: () -> Unit,
    isDark: Boolean = false, // 默认浅色，保证旧调用点行为不变
) {
    val backgroundColor = if (isDark) BackgroundDark else BackgroundLight
    val nameColor = if (isDark) SuouColorDark else SuouColorLight
    val sloganColor = if (isDark) SloganColorDark else SloganColorLight
    // [v16] 背景莫奈化开启时：背景铺近中性莫奈渐变、名字跟随主题色 ink、slogan 用中性正文色；
    // 关闭时完全回退原静态配色（浅色苏芳 / 深色提亮苏芳），零改动。取不到色（彩色主题未开莫奈）
    // 时同样回退原色。名字用 ink（accent）压近中性 surface 背景，深浅色均满足对比易读。
    val monetPalette = if (monetBackgroundEnabled()) monetizedPalette() else null
    val effectiveNameColor = monetPalette?.ink ?: nameColor
    val effectiveSloganColor = monetPalette?.onSurfaceSoft ?: sloganColor
    val backgroundBrush =
        monetPalette?.let { Brush.verticalGradient(listOf(it.surface, it.surfaceSoft)) }
    var fadingOut by remember { mutableStateOf(false) }
    val overallAlpha by animateFloatAsState(
        targetValue = if (fadingOut) 0f else 1f,
        animationSpec = tween(durationMillis = FadeOutMs),
    )
    LaunchedEffect(Unit) {
        delay(SplashVisibleMs)
        fadingOut = true
    }
    LaunchedEffect(overallAlpha, fadingOut) {
        if (fadingOut && overallAlpha <= 0.001f) onFinished()
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 1.4.3：alpha 必须放在 background 之前，否则淡出时只有文字/图标淡出、
            // 底色仍硬盖着主界面，会有「内容消失一块色闪一下」的割裂感。
            .alpha(overallAlpha)
            .then(
                if (backgroundBrush != null) Modifier.background(backgroundBrush)
                else Modifier.background(backgroundColor)
            ),
    ) {
        // [护栏 G-33] 三者当一个整体：组顶 = 上黄金点 − 组高/2（H=780dp 时 ≈196dp）。
        // 组高按 96+24+40+24+19≈203dp 版式估算，实机不同屏按同一比例换算；观感「和谐优雅」待实机。
        val groupTop =
            (maxHeight * SplashGroupCenterFraction - SplashGroupEstimatedHeightDp / 2f)
                .coerceAtLeast(0.dp)
        Column(Modifier.padding(start = 34.dp, top = groupTop, end = 24.dp)) {
            FadeInItem(delayMs = 50L) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            FadeInItem(delayMs = 200L) {
                BasicText(
                    text = stringResource(R.string.app_name),
                    style = TextStyle(
                        color = effectiveNameColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                )
            }
            Spacer(Modifier.height(24.dp))
            FadeInItem(delayMs = 340L) {
                BasicText(
                    text = stringResource(R.string.splash_slogan),
                    style = TextStyle(
                        color = effectiveSloganColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.5.sp,
                    ),
                )
            }
        }
    }
}

/** 单个元素的轻淡入：延迟 delayMs 后，alpha 0→1 + translateY 9px→0，各 0.42s。 */
@Composable
private fun FadeInItem(delayMs: Long, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        shown = true
    }
    val itemAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
    )
    val itemOffset by animateFloatAsState(
        targetValue = if (shown) 0f else 9f,
        animationSpec = tween(durationMillis = 420),
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = itemAlpha
            translationY = itemOffset
        },
    ) {
        content()
    }
}
