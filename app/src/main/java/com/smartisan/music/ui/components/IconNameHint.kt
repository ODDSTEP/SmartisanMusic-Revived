package com.smartisan.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「图标按钮」（Icon Button）—— 只有图标、没有文字标签的控件，业界也常叫
 * icon-only button / IconButton。
 *
 * 这类控件靠图形表意，但不少图标并不直观；长按一下就能看到它的名字是最省事的解决办法。
 * 开关关掉时这里全部退化为空修饰符，不影响任何点击与滚动。
 */

/** 是否开启「长按图标按钮显示名称」。由设置页控制，应用根布局注入。 */
internal val LocalIconNameHintEnabled = compositionLocalOf { false }

/** 名称提示的宿主，挂在应用根布局上。 */
internal val LocalIconHintController = compositionLocalOf<IconNameHintController?> { null }

internal class IconNameHintController {
    var label: String? by mutableStateOf(null)
        private set
    var anchorOffset: IntOffset by mutableStateOf(IntOffset.Zero)
        private set
    var anchorSize: IntSize by mutableStateOf(IntSize.Zero)
        private set

    fun show(label: String, offset: IntOffset, size: IntSize) {
        this.label = label
        anchorOffset = offset
        anchorSize = size
    }

    fun hide() {
        label = null
    }
}

/**
 * 拼出图标按钮的名称提示修饰符。开关关闭或没有宿主时返回 [Modifier]（空修饰）。
 *
 * 说明：正常点击/滚动全程**不消费**事件，交互零影响；只有长按触发提示后，
 * 才会消费本次手势剩余事件（见 [awaitSilentLongPress]），防止松手误触发点击。
 */
@Composable
internal fun rememberIconNameHint(name: String): Modifier {
    val enabled = LocalIconNameHintEnabled.current
    val controller = LocalIconHintController.current
    val density = LocalDensity.current
    val slopPx = with(density) { 12.dp.toPx() }
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    if (!enabled || controller == null || name.isBlank()) return Modifier
    return remember(name, slopPx, controller) {
        Modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                offset = IntOffset(position.x.toInt(), position.y.toInt())
                size = coordinates.size
            }
            .pointerInput(name, slopPx) {
                awaitSilentLongPress(slopPx) { controller.show(name, offset, size) }
            }
    }
}

/** 名称提示图层。放在应用根布局最上层。 */
@Composable
internal fun IconNameHintLayer(
    controller: IconNameHintController,
    modifier: Modifier = Modifier,
) {
    val label = controller.label ?: return
    val anchor = controller.anchorOffset
    val anchorSize = controller.anchorSize
    LaunchedEffect(label, anchor) {
        delay(HintDurationMs)
        controller.hide()
    }
    val density = LocalDensity.current
    val screenWidthPx =
        with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
            .toInt()
    val edgeMarginPx = with(density) { 8.dp.toPx() }.toInt()
    var bubbleWidthPx by remember { mutableStateOf(0) }
    // 气泡水平位置直接钳制在屏幕内（图标贴边时文案长会超出屏幕），
    // 不能靠内层 offset 把气泡“挪”出 Popup 窗口——窗口会裁掉超界部分，表现为开头的字被切掉
    val popupX =
        (anchor.x + (anchorSize.width - bubbleWidthPx) / 2)
            .coerceIn(edgeMarginPx, (screenWidthPx - bubbleWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx))
    Popup(
        offset = IntOffset(popupX, anchor.y + anchorSize.height + 6),
        properties =
            PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        onDismissRequest = controller::hide,
    ) {
        Box(
            Modifier
                .onSizeChanged { bubbleWidthPx = it.width }
                .background(
                    // [1.4.29·R123] 浮层也跟主题走：深色底保留，只叠一层主题色相。
                    color = com.smartisan.music.ui.theme.monetTintColor(Color(0xF2333333), 0.18f),
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                // 文案再长也不能顶出屏幕：限制最大宽度，超出折行
                modifier =
                    Modifier.widthIn(
                        max = with(density) { (screenWidthPx - 2 * edgeMarginPx).toDp() },
                    ),
                style =
                    TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
            )
        }
    }
}

private const val HintDurationMs = 2200L
private const val LongPressTimeoutMs = 400L

/**
 * 长按探测：按下后 [LongPressTimeoutMs] 内既没有抬起、也没有移出 [slopPx] 才算长按。
 *
 * 长按触发后进入「拦截阶段」：在 [PointerEventPass.Initial]（父级先于子级收到事件）
 * 消费本次手势剩余的所有事件，子级 clickable 的 tap 检测看到 up 已被消费就会取消，
 * 因此松手不会触发点击；只有正常点击（未达到长按时长就抬起）才走子级的点击逻辑。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitSilentLongPress(
    slopPx: Float,
    onLongPress: (Offset) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val timedOut = withTimeoutOrNull(LongPressTimeoutMs) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                    if ((change.position - down.position).getDistance() > slopPx) break
                }
            } == null
            if (timedOut) {
                onLongPress(down.position)
                // 拦截阶段：消费直到手指离开，阻断子级 clickable 的点击
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach(PointerInputChange::consume)
                    if (event.changes.none(PointerInputChange::pressed)) break
                }
            }
        }
    }
}
