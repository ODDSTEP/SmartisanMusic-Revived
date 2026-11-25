package com.smartisan.music.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.ui.theme.CaretAspect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [1.4.24·R21/R22/R40] 全 App 统一的「圆角灰三角」，用矢量绘制，不用位图。
 *
 * 为什么换掉 PNG：v19 原素材是 28×16px（xxhdpi 下 9.33×5.33dp）+ alpha 110 的软边位图，
 * 顶栏再放大 1.7 倍后边缘必然发虚（用户说的"像素密度不够"）。矢量在任意 DPI 下都是
 * 设备像素级清晰，且能按 pressed 状态给出明暗反馈。
 *
 * 形状严格保持 v19 的比例 [CaretAspect]=1.75 与三个圆润顶点：
 * 用 [PathEffect.cornerPathEffect] 把三个尖角统一倒圆，半径取高度的 18%
 * （16dp 高时约 2.9dp，与原素材的圆钝感一致）。
 *
 * ## R40 三处返工（用户实机反馈）
 *
 * 1. **去掉外圈那一圈深色描边**。
 *    旧实现把「同色 fill」和「同色 stroke」各画一遍，而颜色本身带 alpha
 *    （顶栏是 `onSurface.copy(alpha = 0.43f)`）。两层半透明叠在一起，边缘处的
 *    实际不透明度变成 `1 - (1-0.43)² ≈ 0.67`，比中间的 0.43 深一档 —— 这就是
 *    用户说的「外圈一圈颜色变深、比中间浅色部分深」。
 *    现在**只画一层 fill**，颜色用「不透明 RGB + paint.alpha」表达透明度，
 *    视觉与 v19 原素材（alpha 110 软边实心三角）完全一致，深色圈消失。
 *
 * 2. **正/倒三角的视觉位置对齐**。
 *    等腰三角形的重心在「距底边 h/3」处，所以几何外接框相同的正三角（重心 2h/3）
 *    和倒三角（重心 h/3）看着不在同一个位置 —— 用户反馈「倒三角形视觉上偏上」。
 *    现在两种朝向都以**同一个重心锚点 g** 为基准绘制（正三角顶点在 g-2h/3，
 *    倒三角顶点在 g+2h/3），翻转时重心恒定不动，视觉位置一致。
 *    代价：外接框需要 4h/3 的高度才能两态都不越界，调用方要留够空间
 *    （顶栏图标盒 36dp 天然够；设置页三角显式给 h*4/3）。
 *
 * 3. **切换动画不再突兀**。
 *    用 `animateFloatAsState` 把 scaleY 从 +1 走到 -1（绕重心 g 翻转，像翻一张纸片），
 *    穿越 0 的那一瞬间三角形高度塌陷，再叠加一段 alpha 淡出淡入，
 *    整个过程是一条连续的曲线，不会"啪"地一下换形状。
 *
 * @param pointingDown true=▼（排序栏收起/卡片展开），false=▲
 * @param contentWidth 三角的视觉宽度（不是画布宽度）；高度按 [CaretAspect] 自动算
 * @param color 填充色；调用方负责透明度（顶栏用 0.43 复刻原素材）
 */
@Composable
fun SmartisanCaret(
    pointingDown: Boolean,
    contentWidth: Dp,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF989898),
    cornerRatio: Float = 0.18f,
) {
    // +1 = ▲，-1 = ▼。目标值直接带符号，来回切换时动画天然连续可逆。
    val flip by animateFloatAsState(
        targetValue = if (pointingDown) -1f else 1f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "SmartisanCaretFlip",
    )
    Canvas(modifier) {
        val w = contentWidth.toPx().coerceAtMost(size.width)
        val h = w / CaretAspect
        val cx = size.width / 2f
        // 重心锚点：正/倒两态共用。两态外接框并集高 4h/3，画布不够时退化为几何居中。
        val minAnchor = 2f * h / 3f
        val maxAnchor = size.height - 2f * h / 3f
        val anchor =
            if (minAnchor <= maxAnchor) {
                (size.height / 2f).coerceIn(minAnchor, maxAnchor)
            } else {
                size.height / 2f
            }
        // 基准形状取 ▲：顶点在 anchor-2h/3，底边在 anchor+h/3（重心恰为 anchor）。
        val path =
            android.graphics.Path().apply {
                moveTo(cx, anchor - 2f * h / 3f)
                lineTo(cx + w / 2f, anchor + h / 3f)
                lineTo(cx - w / 2f, anchor + h / 3f)
                close()
            }
        // 单层填充：颜色用不透明 RGB，透明度交给 paint.alpha，
        // 避免 fill/stroke 同色叠加在边缘产生深色描边圈。
        val paint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color.copy(alpha = 1f).toArgb()
                style = android.graphics.Paint.Style.FILL
                pathEffect = android.graphics.CornerPathEffect(h * cornerRatio)
            }
        // 翻转穿越 0 时形状会塌成一条线，用 alpha 淡出淡入把这段"看不见"做柔和。
        val fade = 0.30f + 0.70f * abs(flip)
        paint.alpha = (color.alpha * fade * 255f).roundToInt().coerceIn(0, 255)
        scale(scaleX = 1f, scaleY = flip, pivot = Offset(cx, anchor)) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawPath(path, paint)
            }
        }
    }
}

/** 顶栏三角的视觉宽度：v19 原 PNG 28px @xxhdpi = 9.33dp，1.7 倍后约 15.9dp。 */
internal val SongsChromeCaretWidth: Dp = (28f / 3f).dp
