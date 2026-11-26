package com.smartisan.music.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.smartisan.music.data.settings.MonetStore

/**
 * [v18·逐元素取色·实验] → [v19 修正] 独立实验性引擎：每个 UI 元素用它自己的原色独立取色，各取各色。
 *
 * 用户拍板（2026-11-23）：
 *  - 这是**独立实验引擎**，不专为彩色主题，必须与当前色彩主题挂钩、不写死源色；
 *  - 切主题，逐元素结果必须跟着变（脱钩是 bug，已修）；
 *  - 近中性灰（顶栏/底栏/列表/灰栏/细线，色度<5）保留各自原生色值与层次，**不提纯不塌缩**
 *    ——谷歌规范：种子色 CAM16 色度≥5 才有效，中性灰无色相可提，不当种子；
 *  - 点缀色（蓝点/蓝字/按钮/正红）保留原色相，只按用途做合规加深/提亮，不压成灰红暗红；
 *  - 深色模式读**深色原生原色**（values-night），不把浅色原色强行压暗。
 *
 * 开关关闭时零改动回退原稳定路径；开关独立，可与 M3/莫奈叠加，随时关掉回退。
 */
internal object PerElementColors {

    /**
     * 点缀元素在「默认彩色主题」下的原色（ARGB）。仅彩色主题用这组；
     * 传统 8 主题改用该主题自己的 themedInk 色相（见 [accentSource]）。
     */
    private val COLORFUL_ACCENT = 0xFF5C89F2.toInt()       // 开关蓝点 btn_text_color_blue
    private val COLORFUL_ACCENT_TEXT = 0xFF4A69B3.toInt()  // 蓝色文字 panel_action_text
    private val COLORFUL_BUTTON = 0xFFFBE6E5.toInt()       // 按钮淡红底 btn_red_bg_normal
    private val COLORFUL_PLAYING_RED = 0xFFE64040.toInt()  // 正在播放红 playing_red

    /**
     * 近中性背景元素的**原生色值**（浅 / 深），取自
     * values/colors.xml、values/smartisan_tokens.xml、drawable/smartlist_header_bg.png 及 night 同名资源。
     * 这些色度 < 5，逐元素模式下原样保留，不提纯、不塌缩，保持层次。
     */
    private data class NeutralSource(
        val bottomBar: Int,   // bottom_bar_surface
        val titleBar: Int,    // title_bar_background
        val listCard: Int,    // surface_card
        val separator: Int,   // smartlist_header_bg（灰栏）
        val divider: Int,     // nav_list_line（细线）
    )

    private val NEUTRAL_LIGHT = NeutralSource(
        bottomBar = 0xFFF7F7F7.toInt(),
        titleBar = 0xFFFFFFFF.toInt(),
        listCard = 0xFFFFFFFF.toInt(),
        separator = 0xFFF6F6F6.toInt(),
        divider = 0xFFEBEBEB.toInt(),
    )
    private val NEUTRAL_DARK = NeutralSource(
        bottomBar = 0xFF282B30.toInt(),
        titleBar = 0xFF292C31.toInt(),
        listCard = 0xFF34373C.toInt(),
        separator = 0xFF292C31.toInt(),
        divider = 0xFF3A3D42.toInt(),
    )

    private fun argbToCompose(argb: Int): Color = Color(argb)

    /**
     * 当前主题下某个「点缀元素」的源色。
     * - 默认彩色：用上面那组彩色原色（蓝点/蓝字/按钮淡红/正红各不相同）。
     * - 传统 8 主题：用该主题自己的点缀色 themedInk（各主题色相不同 → 切主题结果不同）；
     *   按钮底另取该主题的淡容器色相，正红/蓝点/蓝字同用 themedInk 色相。
     */
    private fun accentSource(theme: ColorTheme, key: String): Int {
        if (theme.isColorful) {
            return when (key) {
                "accent" -> COLORFUL_ACCENT
                "accentText" -> COLORFUL_ACCENT_TEXT
                "button" -> COLORFUL_BUTTON
                "playingRed" -> COLORFUL_PLAYING_RED
                else -> COLORFUL_ACCENT
            }
        }
        // 传统主题：主题深档点缀色（themedInk）为源，保证跟随主题；按钮取其同色相淡底。
        val ink = (theme.themedInk ?: theme.accent!!).toArgbIntCompat()
        return ink
    }

    /**
     * 点缀色提纯：保留源色相 H，只把明度压到合规可读档；饱和度仅做轻度提升，
     * 不把淡红压成灰红、不把鲜红压成暗红。中性色（色度<0.06）直接返回原色。
     *
     * 合规口径：浅色点缀 v≈0.62（比旧 0.52 亮，避免过暗）、深色 v≈0.90；
     * 按钮底浅色保留高明度 v≈0.92、深色 v≈0.42（配浅字），保证对比度有余量。
     */
    private fun purifyAccent(sourceArgb: Int, key: String, dark: Boolean): Color {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            (sourceArgb ushr 16) and 0xFF,
            (sourceArgb ushr 8) and 0xFF,
            sourceArgb and 0xFF,
            hsv,
        )
        val h = hsv[0]
        val s = if (hsv[1] < 0.06f) 0f else (hsv[1] * 1.15f).coerceAtMost(0.80f)
        val v = when (key) {
            "button" -> if (dark) 0.42f else 0.92f       // 按钮底：浅色保留淡色相、深色加深配浅字
            "playingRed" ->
                if (dark) 0.90f else 0.78f                // 正红：保留鲜红色相，不压成暗红 #851D1D
            "accent", "accentText" ->
                if (dark) 0.90f else 0.72f                // 蓝点/蓝字：保留鲜明色相
            else -> if (dark) 0.90f else 0.72f
        }
        return Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }

    /**
     * 生成「逐元素取色」palette：点缀色按当前主题各取各色，中性背景保留各自原生层次。
     */
    fun palette(theme: ColorTheme, dark: Boolean): ThemePalette {
        // ——点缀色：按当前主题取源，保色相合规加深/提亮——
        val accent = purifyAccent(accentSource(theme, "accent"), "accent", dark)
        val accentText = purifyAccent(accentSource(theme, "accentText"), "accentText", dark)
        val button = purifyAccent(accentSource(theme, "button"), "button", dark)
        val playingRed = purifyAccent(accentSource(theme, "playingRed"), "playingRed", dark)

        // ——近中性背景：原生色，原样保留、不提纯、不塌缩——
        val neutral = if (dark) NEUTRAL_DARK else NEUTRAL_LIGHT
        val surface = argbToCompose(neutral.titleBar)
        val surfaceSoft = argbToCompose(neutral.bottomBar)
        val separator = argbToCompose(neutral.separator)
        val divider = argbToCompose(neutral.divider)
        val listCard = argbToCompose(neutral.listCard)

        // 次级文字（字母栏/副标题）：取主题色相但保持合规对比度；中性场景回退稳定深/浅灰。
        val onSurfaceVariant =
            if (theme.isColorful) {
                if (dark) Color(0xFFC7CAD0) else Color(0xFF5F636B)
            } else {
                if (dark) Color(0xFFB9BBC2) else Color(0xFF565960)
            }

        // 按钮前景：淡底配深字、深底配浅字，保证对比度有余量。
        val onPrimaryContainer = if (dark) Color(0xFFFFFFFF) else Color(0xFF1F2124)

        return ThemePalette(
            ink = accent,
            surface = surface,
            surfaceSoft = surfaceSoft,
            onSurface = if (dark) Color(0xFFF5F5F5) else Color(0xFF1A1A1A),
            onSurfaceTint = accentText,
            onSurfaceSoft = if (dark) Color(0xFFF2F2F2) else Color(0xFF2B2B2B),
            outlineVariant = divider,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = separator,
            primaryContainer = button,
            primaryContainerSoft = button,
            tertiaryAccent = playingRed,
            tertiaryContainerAccent = playingRed,
            onPrimaryContainer = onPrimaryContainer,
            // [v19] 底栏选中态容器/前景：用主题点缀色相，保证选中态可辨、与按钮同色系。
            secondaryContainer = button,
            onSecondaryContainer = onPrimaryContainer,
        )
    }
}

/** Compose Color → ARGB int（本文件内复用，避免跨文件可见性冲突）。 */
private fun Color.toArgbIntCompat(): Int =
    ((red * 255f).toInt().coerceIn(0, 255) shl 24) or
        ((green * 255f).toInt().coerceIn(0, 255) shl 16) or
        ((blue * 255f).toInt().coerceIn(0, 255) shl 8) or
        ((alpha * 255f).toInt().coerceIn(0, 255))

/** [v18·逐元素取色] Composable 包装：开关开启时返回逐元素 palette，否则 null（走原稳定路径）。 */
@Composable
internal fun perElementPaletteOrNull(): ThemePalette? {
    val enabled = MonetStore.perElementEnabled.collectAsState().value
    if (!enabled) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    val theme = LocalColorTheme.current
    return remember(theme, dark) { PerElementColors.palette(theme, dark) }
}
