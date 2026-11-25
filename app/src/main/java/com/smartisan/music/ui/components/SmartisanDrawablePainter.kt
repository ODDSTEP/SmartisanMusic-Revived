package com.smartisan.music.ui.components

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.AnyRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.smartisan.music.R
import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.theme.themedInk
import com.smartisan.music.ui.theme.ThemePalette
import com.smartisan.music.ui.theme.ThemeTintMode
import com.smartisan.music.ui.theme.tintBitmap
import com.smartisan.music.ui.theme.tintBitmapByMode
import com.smartisan.music.ui.theme.searchGrayFor
import com.smartisan.music.data.settings.MonetStore
import com.smartisan.music.data.settings.ColorThemeStore
import kotlin.math.roundToInt

/** 读取搜索灰方案（remember 块内非 composable，直接读 StateFlow 当前值）。[v18] 恒为 0。 */
private fun searchGrayModeNow(): Int = MonetStore.searchGrayMode.value

/** [v21] 是否 M3 模式：选中 M3 固化主题即生效（remember 块内直接读全局单例当前值）。 */
private fun m3Now(): Boolean = ColorThemeStore.current().isM3Fixed

/** Keeps Android selectors and nine-patch stretch regions intact without hosting a View. */
@Composable
internal fun rememberSmartisanDrawablePainter(
    @DrawableRes drawableRes: Int,
    enabled: Boolean = true,
    pressed: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
    checked: Boolean = false,
    progressLevel: Int? = null,
    activated: Boolean = false,
    /** 1.4.0 U3：顶栏最右图标染纯白（只取方块大小/形状，图标本身纯白）。 */
    forceWhite: Boolean = false,
): Painter {
    // 1.3.8：非默认色彩主题下，把 PNG 型「蓝色点缀」统一换成组 2 主题色。
    // 只在白名单内拦截，其余 drawable（含全部 selector / nine-patch）完全走原逻辑。
    val palette = currentThemePalette()
    val mode = TINTABLE_DRAWABLES[drawableRes]
    if (palette != null && (mode != null || forceWhite)) {
        return rememberThemedBitmapPainter(
            drawableRes,
            palette,
            mode,
            enabled,
            pressed,
            selected,
            focused,
            checked,
            activated,
            forceWhite,
        )
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val painter =
        remember(context, configuration, drawableRes) {
                DrawablePainterOwner(
                    requireNotNull(AppCompatResources.getDrawable(context, drawableRes)).mutate()
                )
            }
            .painter
    UpdateDrawableState(painter, enabled, pressed, selected, focused, checked, activated)
    SideEffect {
        progressLevel?.let { level ->
            (painter.drawable as? LayerDrawable)?.apply {
                findDrawableByLayerId(android.R.id.progress)?.level = level.coerceIn(0, 10_000)
                findDrawableByLayerId(android.R.id.secondaryProgress)?.level = 0
            }
        }
    }
    return painter
}

/**
 * 1.3.8 色彩主题：这些 PNG 图标原本是蓝色点缀，1.3.7 因「不动 PNG」原则没跟着变，
 * 用户实机点名要求改。value=true 表示只染蓝色像素，false 表示按亮度整体换色相。
 * 默认「彩色」不会走到这里（[TINTABLE_DRAWABLES] 只在 currentThemePalette 非空时生效）。
 */
private val TINTABLE_DRAWABLES: Map<Int, ThemeTintMode> =
    mapOf(
        R.drawable.check_box_selector to ThemeTintMode.LUMINANCE, // 编辑模式圆形勾选框
        // 1.3.9：只染淡蓝圆盘、把蓝色加号挖成透明，加号不再消失。
        R.drawable.add_icon_selector to ThemeTintMode.ADD_PLUS_TRANSPARENT,
        R.drawable.playing_blueplay2_selector to ThemeTintMode.BLUE_ONLY, // 正在播放指示
        R.drawable.playing_bluepause_selector to ThemeTintMode.BLUE_ONLY,
        // 1.3.9 底栏：仅把选中态红色图标换成主题色，未选中灰色态完全保持原样。
        R.drawable.tabbar_album_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_artist_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_cloud_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_folder_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_like_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_more_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_playlist_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_song_selector to ThemeTintMode.RED_ONLY,
        R.drawable.tabbar_style_selector to ThemeTintMode.RED_ONLY,
        // 1.4.0 U5：列表行 / 卡片行按下高亮，举一反三一处接入全覆盖
        // （更多页 B1 / 设置页 B2 / 新建播放列表 B3 / 搜索结果行 B4）。
        R.drawable.group_list_item_bg_top to ThemeTintMode.ROW_PRESSED,
        R.drawable.group_list_item_bg_mid to ThemeTintMode.ROW_PRESSED,
        R.drawable.group_list_item_bg_bottom to ThemeTintMode.ROW_PRESSED,
        R.drawable.group_list_item_bg_single to ThemeTintMode.ROW_PRESSED,
        R.drawable.list_header_selector to ThemeTintMode.ROW_PRESSED,
        R.drawable.list_item_bgwithoutphoto_down to ThemeTintMode.ROW_PRESSED,
        // 1.4.1：大播放页播放列表行按下高亮（selector 壳，pressed 态指向
        // list_item_bgwithoutphoto_down），与其余列表行同源 surface 色。
        R.drawable.playing_queue_item_selector to ThemeTintMode.ROW_PRESSED,
        // [v24·R18-N3/F2] 移除 v20 误改的大播放页 3 个按钮 SOLID 染色：
        // btn_current_playing_check_selector / btn_playing_shuffle_on / btn_playing_shuffle_off
        // 用户从没要求改这三个（用户说的"播放列表/随机按钮"指歌曲页/专辑页，那几个用户已自行解决），
        // 回退为 PNG 原样。SOLID_FOREGROUND/SOLID_NEUTRAL 及 legibleForeground 一并清理。
    )

@Composable
private fun rememberThemedBitmapPainter(
    drawableRes: Int,
    palette: com.smartisan.music.ui.theme.ThemePalette,
    mode: ThemeTintMode?,
    enabled: Boolean,
    pressed: Boolean,
    selected: Boolean,
    focused: Boolean,
    checked: Boolean,
    activated: Boolean,
    forceWhite: Boolean = false,
): Painter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(
        context,
        configuration,
        drawableRes,
        palette,
        mode,
        enabled,
        pressed,
        selected,
        focused,
        checked,
        activated,
        forceWhite,
        m3Now(),
    ) {
        val drawable =
            requireNotNull(AppCompatResources.getDrawable(context, drawableRes)).mutate()
        drawable.state = drawableState(enabled, pressed, selected, focused, checked, activated)
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        if (forceWhite) {
            // 1.4.2 顶栏最右图标：图标落在主题色方块（底色=palette.primaryContainer）上。
            // [v18·M3整改] 分三种情况，全部用 SEARCH_GRAY（只换色相、保留 alpha 抗锯齿）：
            //  - 深色：固定明亮统一灰 #b8bbc0（用户拍板去掉三模式）。
            //  - 浅色 + M3：方块是极淡 primaryContainer(tone90)，纯白图标对比仅1.29:1看不清，
            //    改用深色前景 onPrimaryContainer（谷歌"淡容器+深色前景"，对比7.2:1）。
            //  - 浅色 + 非M3（传统/彩色莫奈）：方块是中等深度实色，保持实心纯白（原样零改动）。
            val isDark =
                (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val bmp =
                if (isDark) {
                    val gray = searchGrayFor(searchGrayModeNow(), palette.primaryContainer)
                    tintBitmapByMode(bitmap, gray, ThemeTintMode.SEARCH_GRAY)
                } else if (m3Now()) {
                    tintBitmapByMode(
                        bitmap,
                        palette.onPrimaryContainer,
                        ThemeTintMode.SEARCH_GRAY,
                    )
                } else {
                    // [v24·R18-N1] 放大镜锯齿根治：WHITE_SOLID 会把半透明抗锯齿边缘的 alpha
                    // 一律拉满 255 → 硬边锯齿。改用 SEARCH_GRAY（只换色相、完整保留原 alpha），
                    // 与深色分支及 M3 分支一致；目标色仍为白色。
                    tintBitmapByMode(bitmap, Color.White, ThemeTintMode.SEARCH_GRAY)
                }
            BitmapPainter(bmp.asImageBitmap())
        } else {
            val effectiveMode = mode ?: ThemeTintMode.LUMINANCE
            val tintTarget =
                when (effectiveMode) {
                    // [v18·M3整改] 底栏选中图标(RED_ONLY)在 M3 模式下必须用深色前景，
                    // 否则极淡的 primaryContainer(tone90) 图标落在灰底栏上完全看不清。
                    // onPrimaryContainer 默认=onSurface（非 M3 主题零改动），M3 下=官方深色前景。
                    // ROW_PRESSED（列表按下高亮底）仍用 primaryContainer，保持淡容器观感。
                    ThemeTintMode.RED_ONLY -> palette.onPrimaryContainer
                    ThemeTintMode.ROW_PRESSED -> palette.primaryContainer
                    // [v17·M3] 新建播放列表加号/圆盘 = tertiary 点睛色；非 M3 主题默认值=primaryContainer，零改动。
                    ThemeTintMode.ADD_PLUS_TRANSPARENT -> palette.tertiaryContainerAccent
                    // [v24·R18-N3/F2] SOLID_FOREGROUND/SOLID_NEUTRAL 已随误改按钮回退一并删除。
                    else -> palette.ink
                }
            BitmapPainter(tintBitmapByMode(bitmap, tintTarget, effectiveMode).asImageBitmap())
        }
    }
}

/** Projects the original group-row shadow outside its bounds, as the View background did. */
@Composable
internal fun Modifier.smartisanShadowBackground(
    @DrawableRes backgroundRes: Int,
    @DrawableRes shadowRes: Int,
    enabled: Boolean = true,
    pressed: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
): Modifier {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val palette = currentThemePalette()
    val painter =
        remember(
            context, configuration, backgroundRes, shadowRes,
            palette, enabled, pressed, selected, focused,
        ) {
            val target =
                requireNotNull(AppCompatResources.getDrawable(context, backgroundRes)).mutate()
            val shadow =
                requireNotNull(AppCompatResources.getDrawable(context, shadowRes)).mutate()
            val padding = Rect()
            shadow.getPadding(padding)
            // 1.4.0 U5 / B5 总根因：本函数原本完全没接 currentThemePalette()，
            // 所以设置页 4 种 RowShape 的按下态一直是默认蓝。这里在非默认主题 +
            // 按下时，把 PNG 蓝色高亮底精确换成 palette.surface，保留原 alpha 与
            // 圆角形状；常态仍走原 selector + 阴影，零改动。
            // 1.4.1：不再把 9-patch 栅格化成 BitmapDrawable——栅格化会丢失 stretch
            // 分区，那个 74px 小圆角被等比拉成跨整行的椭圆弧（用户看到的「球体」），
            // 且 ROW_PRESSED 只染偏蓝像素、圆角外透明区原样留空（露出底纹）。
            // 改为 ColorFilter SRC_IN：pressed 态 9-patch 蓝底精确染成 palette.surface，
            // 9-patch 拉伸与圆角 alpha 全保留，与默认变色一个观感（整块实心 + 原圆角）。
            val effectiveTarget: Drawable =
                if (palette != null && pressed) {
                    target.state = drawableState(enabled, pressed, selected, focused)
                    target.colorFilter =
                        PorterDuffColorFilter(
                            palette.primaryContainer.toArgb(),
                            PorterDuff.Mode.SRC_IN,
                        )
                    target
                } else {
                    target
                }
            DrawablePainterOwner(
                ShadowDrawable(shadow, effectiveTarget, padding.left, padding.top)
            )
        }
            .painter
    UpdateDrawableState(painter, enabled, pressed, selected, focused)
    return smartisanPainterBackground(painter)
}

/** Unlike colorResource, this preserves enabled, pressed and keyboard-focus colors. */
@Composable
@SuppressLint("LocalContextGetResourceValueCall")
internal fun smartisanStateColor(
    @AnyRes colorRes: Int,
    enabled: Boolean = true,
    pressed: Boolean = false,
    selected: Boolean = false,
    focused: Boolean = false,
    activated: Boolean = false,
): Color {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val colors = remember(context, configuration, colorRes) { context.getColorStateList(colorRes) }
    val base =
        Color(
            colors.getColorForState(
                drawableState(enabled, pressed, selected, focused, activated = activated),
                colors.defaultColor,
            )
        )
    // 1.3.8：设置项 / 弹窗里的蓝色文字统一换成组 2 主题色；默认「彩色」原样返回。
    // 按下/聚焦时的高亮文字仍是浅色档，但带上主题色相（与主题色高亮条配套）。
    val highlighted = pressed || focused
    return when {
        colorRes == R.color.blue_btn_text_color_selector -> themedInk(base)
        highlighted &&
            (colorRes == R.color.setting_item_text_colorlist ||
                colorRes == R.color.setting_item_summary_text_colorlist) ->
            com.smartisan.music.ui.theme.themedPressedTextColor(base, true)
        else -> base
    }
}

@Composable
internal fun smartisanTextSize(@DimenRes dimensionRes: Int): TextUnit {
    val pixels = LocalResources.current.getDimension(dimensionRes)
    // XML contains both dp and sp text dimensions. Convert the resolved pixels instead of
    // treating every resource as sp, which would apply font scaling twice to sp values.
    return with(LocalDensity.current) { pixels.toSp() }
}

@Composable
private fun UpdateDrawableState(
    painter: SmartisanDrawablePainter,
    enabled: Boolean,
    pressed: Boolean,
    selected: Boolean,
    focused: Boolean,
    checked: Boolean = false,
    activated: Boolean = false,
) {
    val direction = LocalLayoutDirection.current
    SideEffect {
        painter.drawable.state =
            drawableState(enabled, pressed, selected, focused, checked, activated)
        painter.drawable.layoutDirection =
            if (direction == LayoutDirection.Rtl) {
                View.LAYOUT_DIRECTION_RTL
            } else {
                View.LAYOUT_DIRECTION_LTR
            }
    }
}

private fun drawableState(
    enabled: Boolean,
    pressed: Boolean,
    selected: Boolean,
    focused: Boolean,
    checked: Boolean = false,
    activated: Boolean = false,
): IntArray =
    intArrayOf(
        if (enabled) android.R.attr.state_enabled else -android.R.attr.state_enabled,
        if (pressed) android.R.attr.state_pressed else -android.R.attr.state_pressed,
        if (selected) android.R.attr.state_selected else -android.R.attr.state_selected,
        if (focused) android.R.attr.state_focused else -android.R.attr.state_focused,
        if (checked) android.R.attr.state_checked else -android.R.attr.state_checked,
        if (activated) android.R.attr.state_activated else -android.R.attr.state_activated,
    )

// Keep the observer private to its original remember scope: a caller remembering the
// returned Painter must not acquire a second, shorter lifetime for its drawable callbacks.
private class DrawablePainterOwner(drawable: Drawable) : RememberObserver {
    val painter = SmartisanDrawablePainter(drawable)

    override fun onRemembered() = painter.attach()

    override fun onForgotten() = painter.detach()

    override fun onAbandoned() = painter.detach()
}

private class SmartisanDrawablePainter(val drawable: Drawable) : Painter(), Drawable.Callback {
    private var invalidation by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    override val intrinsicSize: Size
        get() =
            if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
            } else {
                Size.Unspecified
            }

    override fun DrawScope.onDraw() {
        // Read during drawing so drawable callbacks invalidate only this draw scope.
        @Suppress("UNUSED_VARIABLE") val generation = invalidation
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

    fun attach() {
        drawable.callback = this
        drawable.setVisible(true, true)
    }

    fun detach() {
        drawable.setVisible(false, false)
        drawable.callback = null
        handler.removeCallbacksAndMessages(drawable)
    }

    override fun invalidateDrawable(who: Drawable) {
        invalidation++
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        handler.postAtTime(what, drawable, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        handler.removeCallbacks(what, drawable)
    }
}


/**
 * 1.3.9「主题按键背景」：用户拍板采用图3「完成」按钮那种淡蓝实心圆角方块形状，
 * 不用 1.3.8 的纯色 6dp 圆角正方形，也不用三角 PNG 自带的低透明圆角框。
 * 1.4.0 U3：这里**只借用** standard_icon_hignlight_confirm.png 的大小与圆角形状
 * （alpha 蒙版），所有非透明像素统一填 palette.surface 纯色，对勾/圆环因同色消失，
 * 得到纯色圆角方块；顶栏最右图标另染纯白。不取原图淡蓝背景、不叠对勾、不压平 PNG。
 */
@Composable
internal fun rememberThemedConfirmBlockPainter(
    palette: ThemePalette,
): Painter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, palette) {
        val drawable = requireNotNull(
            AppCompatResources.getDrawable(
                context,
                R.drawable.standard_icon_hignlight_confirm,
            )
        ).mutate()
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        BitmapPainter(
            tintBitmapByMode(bitmap, palette.primaryContainer, ThemeTintMode.CONFIRM_BLOCK)
                .asImageBitmap()
        )
    }
}

// [护栏 G-13·台账登记] launcher icon 用无损透明原图 1:1 缩放、移除 mipmap-anydpi-v26
// adaptive 遮罩；防「图标被 OEM 切成规则圆 / 被加白底 / 被重绘」。资源在 res/mipmap-*。
// 1.4.2：删除 1.4.1 的 rememberThemedCarvedBlockPainter（DST_OUT 镂空方块）。
// 用户改口「镂空没挖透就别挖了、不要镂空、只把图标颜色改成白色」，
// 顶栏最右浅色/深色一律改用「主题色实心方块 + 图标染白」，不再需要镂空 painter。
