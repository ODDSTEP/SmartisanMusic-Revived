package com.smartisan.music.ui.songs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.smartisan.music.R
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.library.libraryTexture
import kotlinx.coroutines.launch

@Composable
internal fun SmartisanSwipeDeleteRow(
    key: String,
    enabled: Boolean,
    openKey: String?,
    onOpenChange: (String?) -> Unit,
    onDelete: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** 当前歌曲是否处于隐藏态（睁眼=显示中 / 闭眼=已隐藏，与文件夹页一致） */
    hidden: Boolean = false,
    /** 可选的「隐藏」动作：非 null 时滑动露出区会在删除图标右侧多一个眼睛图标 */
    onHide: (((() -> Unit) -> Unit))? = null,
    onSwipeActivity: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val shift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val resources = LocalResources.current
    val deleteImage = rememberSmartisanDrawablePainter(R.drawable.compose_quicktext_delete)
    val hideEnabled = onHide != null
    val slot =
        deleteImage.intrinsicSize.width +
            resources.getInteger(R.integer.delete_button_padding_left) * 2
    val width = slot * if (hideEnabled) 2 else 1
    val shadow = with(density) { dimensionResource(R.dimen.lv_item_shadow_width).roundToPx() }
    val currentOpen by rememberUpdatedState(openKey)
    val openChange by rememberUpdatedState(onOpenChange)
    val activity by rememberUpdatedState(onSwipeActivity)
    // 眼睛状态与眨眼动画：与文件夹页编辑模式的小眼睛同一套图标与动画
    var pendingHidden by remember(key) { mutableStateOf<Boolean?>(null) }
    val eyeTime = remember(key) { Animatable(300f) }
    LaunchedEffect(hidden) { if (pendingHidden == hidden) pendingHidden = null }
    val displayedHidden = pendingHidden ?: hidden
    fun close() {
        onOpenChange(null)
        onSwipeActivity(false)
    }
    LaunchedEffect(openKey, enabled) {
        if (!enabled || openKey != key)
            shift.animateTo(
                0f,
                tween(200, easing = com.smartisan.music.ui.components.SmartisanEaseInOut),
            )
    }
    Box(
        modifier.fillMaxWidth().clipToBounds().libraryTexture().pointerInput(key, enabled, width) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (currentOpen != null) {
                    if (currentOpen != key || down.position.x > width) {
                        down.consume()
                        openChange(null)
                        activity(false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                    return@awaitEachGesture
                }
                var gesture = SongSwipeDeleteMotion.Gesture.PENDING
                var distance = 0f
                var finished = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val delta = change.position - down.position
                        gesture =
                            SongSwipeDeleteMotion.resolve(
                                gesture,
                                delta.x,
                                delta.y,
                                viewConfiguration.touchSlop,
                            )
                        if (gesture == SongSwipeDeleteMotion.Gesture.LIST_SCROLL) break
                        if (gesture == SongSwipeDeleteMotion.Gesture.SWIPE_DELETE) {
                            change.consume()
                            activity(true)
                            distance = delta.x.coerceAtLeast(0f)
                            val resisted =
                                when {
                                    distance < width -> distance
                                    distance <= width * 8 -> width + (distance - width) / 7
                                    else -> width + width * 8 / 7
                                }
                            scope.launch { shift.snapTo(resisted + shadow) }
                        }
                        if (!change.pressed) {
                            if (gesture == SongSwipeDeleteMotion.Gesture.SWIPE_DELETE) {
                                val opened = distance > width / 4
                                openChange(if (opened) key else null)
                                activity(opened)
                                scope.launch {
                                    shift.animateTo(
                                        if (opened) width + shadow else 0f,
                                        tween(
                                            if (!opened || distance > width) 200 else 100,
                                            easing =
                                                com.smartisan.music.ui.components
                                                    .SmartisanEaseInOut,
                                        ),
                                    )
                                }
                            }
                            finished = true
                            break
                        }
                    }
                } finally {
                    if (!finished && gesture == SongSwipeDeleteMotion.Gesture.SWIPE_DELETE) {
                        openChange(null)
                        activity(false)
                        scope.launch {
                            shift.animateTo(
                                0f,
                                tween(
                                    200,
                                    easing = com.smartisan.music.ui.components.SmartisanEaseInOut,
                                ),
                            )
                        }
                    }
                }
            }
        }
    ) {
        if (shift.value > 0f) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectSmartisanPressedAsState()
            Box(Modifier.matchParentSize()) {
                Row(
                    Modifier.align(androidx.compose.ui.AbsoluteAlignment.CenterLeft)
                        .width(with(density) { width.toDp() })
                        .fillMaxHeight(),
                ) {
                    Box(
                        Modifier.width(with(density) { slot.toDp() })
                            .fillMaxHeight()
                            .clickable(
                                interaction,
                                null,
                                enabled = openKey == key,
                                role = Role.Button,
                                onClick = smartisanClick { onDelete(::close) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            rememberSmartisanDrawablePainter(
                                R.drawable.remove_playlist_selector,
                                pressed = pressed,
                            ),
                            stringResource(R.string.delete),
                        )
                    }
                    if (hideEnabled) {
                        // 状态眼睛与文件夹页同一套图标与动画：显示中=睁眼(0001)，已隐藏=闭眼(0016)；
                        // 点击后播放 16 帧眨眼过渡动画（隐藏：睁→闭；显示：闭→睁），动画结束才执行切换
                        val eyeFrame =
                            if (pendingHidden != null && eyeTime.value < 160f) {
                                val index = (eyeTime.value / 10).toInt().coerceIn(0, 15)
                                SongEyeFrames[if (pendingHidden == true) index else 15 - index]
                            } else if (displayedHidden) R.drawable.eye_icon_0016
                            else R.drawable.eye_icon_0001
                        Box(
                            Modifier.width(with(density) { slot.toDp() })
                                .fillMaxHeight()
                                .clickable(
                                    interaction,
                                    null,
                                    enabled = openKey == key && pendingHidden == null,
                                    role = Role.Button,
                                    onClick = smartisanClick {
                                        pendingHidden = !displayedHidden
                                        scope.launch {
                                            eyeTime.snapTo(0f)
                                            eyeTime.animateTo(
                                                300f,
                                                tween(300, easing = LinearEasing),
                                            )
                                            onHide?.invoke(::close)
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                rememberSmartisanDrawablePainter(
                                    eyeFrame,
                                    pressed = pressed,
                                ),
                                stringResource(R.string.show_hide_songs),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    com.smartisan.music.ui.components.StatefulIconTint,
                                    androidx.compose.ui.graphics.BlendMode.SrcIn,
                                ),
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.layout { measurable, constraints ->
                    val child =
                        measurable.measure(
                            constraints.copy(
                                minWidth = constraints.minWidth + shadow,
                                maxWidth = constraints.maxWidth + shadow,
                            )
                        )
                    layout(constraints.maxWidth, child.height) { child.place(-shadow, 0) }
                }
                .graphicsLayer { translationX = shift.value }
        ) {
            content()
        }
    }
}

/** 眨眼动画帧（睁眼 0001 → 闭眼 0016），与文件夹页的眼睛动画共用同一组图标资源 */
internal val SongEyeFrames =
    intArrayOf(
        R.drawable.eye_icon_0001,
        R.drawable.eye_icon_0002,
        R.drawable.eye_icon_0003,
        R.drawable.eye_icon_0004,
        R.drawable.eye_icon_0005,
        R.drawable.eye_icon_0006,
        R.drawable.eye_icon_0007,
        R.drawable.eye_icon_0008,
        R.drawable.eye_icon_0009,
        R.drawable.eye_icon_0010,
        R.drawable.eye_icon_0011,
        R.drawable.eye_icon_0012,
        R.drawable.eye_icon_0013,
        R.drawable.eye_icon_0014,
        R.drawable.eye_icon_0015,
        R.drawable.eye_icon_0016,
    )
