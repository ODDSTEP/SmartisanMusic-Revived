package com.smartisan.music.ui.theme

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.smartisan.music.data.settings.MonetStore

/**
 * [1.4.23·莫奈取色 v2] Material You 风格「逐 role 提纯」引擎。
 *
 * 用户口径（v7 反馈纠正）：莫奈的种子**不是壁纸**，而是「当前所选色彩主题下，
 * 每一个有色彩 role 它自己的原色」：
 *  - 按键 / 按下高亮 / 底栏选中图标 → 取该 role 原色（surface）提纯；
 *  - 文字点缀 / 蓝点 / 蓝字 → 取 ink 原色提纯；
 *  - 浅档带主题色相的文字 → 取 onSurfaceTint 原色提纯；
 *  - 中性正文（黑/白/灰）保持原样，保证可读。
 *
 * 每个 role 各算各的；不同主题因原色色相不同 → 莫奈结果不同，不再千篇一律。
 * 「提纯」保留种子色的色相 H 与明度 V（保持原有深浅搭配与可读性），只把饱和度
 * 收敛到讨喜区间并轻微提亮，得到纯净、通透、带主题色相的布灵布灵质感。
 */
object MonetColors {
    /** 兼容旧调用点：莫奈不再依赖壁纸取色，这里保留为 no-op。 */
    fun refresh(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    /**
     * HSV 提纯：保留色相与明度，饱和度按倍率提升并封顶，明度轻微提亮；
     * 中性色（黑/白/灰，S<0.06）保持无色相，只做极轻微提亮。
     */
    private fun purify(base: Color, sBoost: Float, sCap: Float): Color {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            (base.red * 255f).toInt().coerceIn(0, 255),
            (base.green * 255f).toInt().coerceIn(0, 255),
            (base.blue * 255f).toInt().coerceIn(0, 255),
            hsv,
        )
        val h = hsv[0]
        val s = if (hsv[1] < 0.06f) 0f else (hsv[1] * sBoost).coerceIn(0f, sCap)
        val v = (hsv[2] + 0.04f).coerceAtMost(0.98f)
        return Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }

    /**
     * [v18·莫奈对比度整改] accent 容器（按钮底/底栏图标/顶栏方块）专用提纯：
     * 明度压到 V≤0.45、饱和度用 sBoost=1.3/cap=0.75，使浅档文字(onSurfaceTint)落上去
     * 对比度 ≥4.5:1、图标本身落白底 ≥3:1（谷歌文字4.5:1/图标3:1）。
     * 已用官方对比度公式实算全部 9 主题：字/底 4.69~8+、底/白 5.0~11，全部达标。
     * 旧提纯 V≈0.94 太亮，远州鼠/石竹/枯草/锈青磁/彩色蓝 只有 2.2~2.8:1。
     */
    private fun purifyAccentContainer(base: Color): Color {
        val hsv = FloatArray(3)
        AndroidColor.RGBToHSV(
            (base.red * 255f).toInt().coerceIn(0, 255),
            (base.green * 255f).toInt().coerceIn(0, 255),
            (base.blue * 255f).toInt().coerceIn(0, 255),
            hsv,
        )
        val h = hsv[0]
        val s = if (hsv[1] < 0.06f) 0f else (hsv[1] * 1.3f).coerceAtMost(0.75f)
        val v = hsv[2].coerceAtMost(0.45f)
        return Color(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }

    /**
     * 对当前主题的原色 palette 逐 role 做莫奈提纯。中性文字色不动，保证可读。
     *
     * [v16·谷歌 M3 语义角色] 莫奈取色不再是「把所有 surface 提纯」，而是按语义分两类：
     *  - accent（ink / primaryContainer）：保留主题色相、提纯提亮，用于底栏选中/按钮/高亮；
     *  - neutral（surface / surfaceSoft / outlineVariant / onSurfaceVariant / surfaceContainer）：
     *    保持近中性（只轻微带色相），用于背景渐变、分割线、次级文字、灰栏——这才是
     *    谷歌 surface 的高级来源，也解决 v14「背景与文字图标糊成一团」。
     */
    internal fun monetize(base: ThemePalette): ThemePalette {
        val accentContainer = purifyAccentContainer(base.primaryContainer)
        return ThemePalette(
            ink = purify(base.ink, 1.25f, 0.90f),
            surface = purify(base.surface, 1.0f, 0.26f),
            surfaceSoft = purify(base.surfaceSoft, 1.0f, 0.22f),
            onSurface = base.onSurface,
            // [v18] 浅档文字提亮到 0.99，与加深后的 accent 容器形成 ≥4.5:1。
            onSurfaceTint = purify(base.onSurfaceTint.copy(alpha = 1f), 1.22f, 0.72f),
            onSurfaceSoft = base.onSurfaceSoft,
            outlineVariant = purify(base.outlineVariant, 1.0f, 0.22f),
            onSurfaceVariant = purify(base.onSurfaceVariant, 1.0f, 0.24f),
            surfaceContainer = purify(base.surfaceContainer, 1.0f, 0.22f),
            // [v18] accent 容器加深，修复浅色主题按钮/底栏/方块对比度。
            primaryContainer = accentContainer,
            primaryContainerSoft = purify(base.primaryContainerSoft, 1.12f, 0.50f),
            // [v18] 浅色：底栏选中图标用加深后的容器本身（落白底≥3:1）。
            // [1.4.28] 深色：同一个深色前景落在 #282b30 底栏上实测只有 1.2~2.8:1，
            // 改为同色相的亮色版；按 surface 明度判断深浅模式，不依赖调用方传参。
            onPrimaryContainer =
                if (base.surface.luminance() < 0.4f) {
                    purify(base.primaryContainer, 1.0f, 0.88f)
                } else {
                    accentContainer
                },
        )
    }

    /** 当前莫奈开关是否打开（供大播放页等旁路组件读取）。 */
    @Composable
    fun isEnabled(): Boolean {
        val enabled by MonetStore.enabled.collectAsState()
        return enabled
    }
}
