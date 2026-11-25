package com.smartisan.music.ui.songs

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.theme.monetOnSurfaceVariant
import kotlin.math.max
import kotlin.math.roundToInt

private val QuickBarLetters = ('A'..'Z').map(Char::toString) + "#"

/**
 * 右侧字母索引条。
 *
 * [1.4.24·R29] 按用户拍板做减法：**删掉左侧滑出的 27 宫格「大字母表」**。
 * 理由（用户原话）：「点击小字母就能跳，拉出来一个大字母表纯属脱裤子放屁」。
 *
 * [1.4.24·R40] 再修一处：原先按住字母条时会把整条 `letters_bar_background`（日间是
 * 近纯白 9-patch）铺满整条，同时把当前字母设成 `Color.White` —— 结果就是
 * **整条字母列变成一条白带，白字压在白底上完全看不见**（用户原话：「按住字母列后
 * 整个字母列都会变白，这样我就看不清字母列了」）。
 *
 * 现在按下时**不再铺任何整条背景**，只把当前字母本身加粗 + 换成主题强调色，
 * 其余字母保持原样 —— 整条字母列在任何时候都清晰可读。
 *
 * 保留的行为（用户明确要求）：
 *  - 点小字母直接跳转；
 *  - 按住小字母上下拖动，连续切换字母（gesture 内逐帧算 letterAt）；
 *  - 触摸时当前字母高亮 + 加粗（这是文字反馈，不是被删的白色块）；
 *  - 无障碍逐字母自定义动作。
 *
 * 顺带删掉字母条左侧的渐变阴影（R30 用户确认「阴影一并删」），只留条本身的背景。
 */
@Composable
internal fun SmartisanQuickBar(onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    val background = rememberSmartisanDrawablePainter(R.drawable.letters_bar_background)
    val stripWidth = background.intrinsicSize.width.roundToInt().coerceAtLeast(1)
    val density = LocalDensity.current
    val latestLetter by rememberUpdatedState(onLetter)
    var touchedLetter by remember { mutableIntStateOf(-1) }
    val description = stringResource(R.string.quickbar_description)

    Layout(
        modifier = modifier.width(with(density) { stripWidth.toDp() }),
        content = {
            QuickBarStrip(
                touched = touchedLetter,
                modifier =
                    Modifier.fillMaxHeight()
                        .pointerInput(stripWidth) {
                            awaitEachGesture {
                                val down =
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                if (down.position.x >= stripWidth) return@awaitEachGesture
                                // 按下即跳：不要求用户先"激活"字母条，和原行为一致。
                                touchedLetter =
                                    QuickBarGeometry.letterAt(down.position.y, size.height)
                                latestLetter(QuickBarLetters[touchedLetter])
                                down.consume()
                                try {
                                    while (true) {
                                        val event =
                                            awaitPointerEvent(PointerEventPass.Initial)
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                        // 按住上下拖动：逐帧按 y 重新定位字母。
                                        val index =
                                            QuickBarGeometry.letterAt(change.position.y, size.height)
                                        if (index != touchedLetter) {
                                            touchedLetter = index
                                            latestLetter(QuickBarLetters[index])
                                        }
                                        change.consume()
                                    }
                                } finally {
                                    touchedLetter = -1
                                }
                            }
                        }
                        .semantics {
                            contentDescription = description
                            role = Role.Button
                            customActions =
                                QuickBarLetters.map { letter ->
                                    CustomAccessibilityAction(letter) {
                                        latestLetter(letter)
                                        true
                                    }
                                }
                        },
            )
        },
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val strip = measurables[0].measure(Constraints.fixed(stripWidth, height))
        layout(stripWidth, height) { strip.place(0, 0) }
    }
}

@Composable
private fun QuickBarStrip(
    touched: Int,
    modifier: Modifier,
) {
    val resources = LocalResources.current
    val margin = resources.getDimensionPixelSize(R.dimen.smartisan_quickbar_letterbar_margin)
    val minimumHeight =
        resources.getDimensionPixelSize(R.dimen.letters_bar_single_letter_min_height)
    val fontSize = resources.getDimension(R.dimen.letters_bar_letter_font_size)
    // [v16·谷歌 M3] 字母栏次级文字取 onSurfaceVariant：莫奈取色开启时带主题色相的中性灰，
    // 关闭回退官方 no_chosen_letter_font_color。
    val noChosen = monetOnSurfaceVariant() ?: colorResource(R.color.no_chosen_letter_font_color)
    // [1.4.25·R45] 按下字母要有明显选中态：原厂 `letters_bar_highlight_icon`
    // （60×60px=20dp 实心圆，日间深灰 #191921 / 夜间纯白）铺在字母下面，
    // 字母再用反色画上去 —— 用户图4 要的就是「原位实心圆 + 反白字」。
    // 之前只加粗换色，深色背景下几乎看不出按到了哪个字母。
    val highlight = rememberSmartisanDrawablePainter(R.drawable.letters_bar_highlight_icon)
    val darkMode = androidx.compose.foundation.isSystemInDarkTheme()
    val onHighlightColor = if (darkMode) Color(0xFF191921) else Color.White
    val textPaint =
        remember(fontSize) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = fontSize
            }
        }
    Canvas(modifier.fillMaxSize()) {
        // [1.4.24·R40] 按下时**不再铺整条背景**。
        // 旧实现在这里 `draw(letters_bar_background)`（日间是近纯白的 9-patch），
        // 会把整条字母列刷成一条白带，同时当前字母又被设成 Color.White → 白字压白底，
        // 整条字母列等于消失（用户原话：「按住字母列后会整个字母列都会变白，
        // 这样我就看不清字母列了」）。
        // 现在只改当前字母本身的颜色与字重，其余字母一律保持常态。
        val indexes =
            QuickBarGeometry.visibleLetters(size.height.roundToInt(), margin, minimumHeight)
        val available = (size.height.roundToInt() - margin * 2).coerceAtLeast(1)
        val letterHeight = max(available.toFloat() / indexes.size, minimumHeight.toFloat())
        indexes.forEachIndexed { visibleIndex, letterIndex ->
            val centerY = margin + visibleIndex * letterHeight + letterHeight / 2f
            val isTouched = letterIndex == touched
            if (isTouched) {
                val diameter = minOf(letterHeight * 0.98f, 22.dp.toPx())
                translate(left = (size.width - diameter) / 2f, top = centerY - diameter / 2f) {
                    with(highlight) { draw(size = Size(diameter, diameter)) }
                }
            }
            textPaint.color = (if (isTouched) onHighlightColor else noChosen).toArgb()
            textPaint.isFakeBoldText = isTouched
            val metrics = textPaint.fontMetricsInt
            val baseline = centerY - (metrics.bottom - metrics.top) / 2f - metrics.top
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    QuickBarLetters[letterIndex],
                    size.width / 2f,
                    baseline,
                    textPaint,
                )
            }
        }
    }
}
