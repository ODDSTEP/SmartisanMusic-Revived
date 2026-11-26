package com.smartisan.music.ui.theme

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.smartisan.music.R
import com.smartisan.music.data.settings.MonetStore
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground

/**
 * 1.3.8 色彩主题「统一深度」调色板。
 *
 * 用户定的口径：
 *  - 组 1（surface）：按键 / 底栏选中按钮的主题色背景 / 被按下后一整行的高亮显示；
 *    编辑模式下的选中高亮用更浅的 surfaceSoft。
 *  - 组 2（ink）：设置开关开启后的蓝色小圆点、设置项里的蓝色文字。
 *  - 其余点缀沿用 ink；文字的深/浅档位不变，只换色相（onSurfaceTint 是浅档带主题色相）。
 *  - 8 个主题共用同一套深度参数，只取各自色相，避免浅色主题看不清、深色主题刺眼。
 *  - 默认「彩色」一律返回 null，所有调用点走原逻辑，零改动。
 */
internal data class ThemePalette(
    val ink: Color,
    // [v16·谷歌 M3 语义角色] surface / surfaceSoft 定位为**近中性背景**（只轻微带色相，
    // 对应 M3 neutral-variant surface），供「背景莫奈化」的白色内容渐变与大播放页晕染使用；
    // 原「底栏选中 / 按钮 / 按下高亮」这类 accent 容器色迁移到 primaryContainer 系列。
    val surface: Color,
    val surfaceSoft: Color,
    val onSurface: Color,
    val onSurfaceTint: Color,
    val onSurfaceSoft: Color,
    // [v16] 中性变体语义角色（从种子色派生，低饱和、带轻微色相）：
    val outlineVariant: Color, // 列表分割细线（M3 outlineVariant）
    val onSurfaceVariant: Color, // 次级文字：右侧字母栏、列表副标题（M3 onSurfaceVariant）
    val surfaceContainer: Color, // 首字母/分组灰栏容器（M3 surfaceContainer）
    // [v16] accent 容器：底栏选中、顶栏方块、播放列表/随机按钮、按下/选中高亮（原 surface/surfaceSoft）。
    val primaryContainer: Color,
    val primaryContainerSoft: Color,
    // [v17·M3] 加号/新建播放列表圆盘的 tertiary 点睛色（M3 第三色）。默认值=accent 容器，
    // 保证非 M3 主题（传统/彩色莫奈）零改动；只有 M3 三色模式才填入真正的 tertiary。
    val tertiaryAccent: Color = primaryContainer,
    val tertiaryContainerAccent: Color = primaryContainer,
    // [v18·M3整改] M3 官方 onPrimaryContainer（深色前景，配淡色 primaryContainer 容器）。
    // 修 v17 bug：底栏选中图标曾把极淡的 primaryContainer(tone90) 当图标色落灰底→看不清。
    // 谷歌规范=淡容器配深色前景。默认=primaryContainer（非 M3 主题零改动），M3 下=官方深色前景。
    val onPrimaryContainer: Color = primaryContainer,
    // [v18·M3整改] 底栏选中态胶囊底=M3 secondaryContainer（谷歌 Navigation Bar 规范），
    // 前景 onSecondaryContainer。默认=primaryContainer（非 M3 零改动）。
    val secondaryContainer: Color = primaryContainer,
    val onSecondaryContainer: Color = primaryContainer,
)

private fun Color.toHsv(): FloatArray {
    val out = FloatArray(3)
    AndroidColor.RGBToHSV(
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255),
        out,
    )
    return out
}

private fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color(AndroidColor.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))

/**
 * 1.4.0 U1：不再「一刀切 V=0.58 / S∈[0.30,0.62]」把 8 个主题压成同一片黄褐。
 * 改用 §8-P1 实算的 8 组专用点缀色（保留各自色相、按主题分档明度/饱和度）；
 * surface / surfaceSoft / onSurfaceTint 全部由该 ink 派生，保证 20 处点缀位、
 * 底栏选中色、顶栏最右方块背景、按下高亮都随主题明显区分且同源。
 */
internal fun ColorTheme.palette(isDark: Boolean): ThemePalette? {
    val base = accent ?: return null
    val inkLight = themedInk ?: base
    val hsv = inkLight.toHsv()
    val h = hsv[0]
    val s = hsv[1]
    val v = hsv[2]
    return if (isDark) {
        ThemePalette(
            // [R149] 深色底上的点缀色（更多页右侧小字等）必须够 4.5:1：
            // 实测苏芳 2.17、落栗 2.27、鸠羽紫 3.04 都不达标（暗底 + 暗点缀）。
            // 这里按实际 surface 亮度自动提亮，保持色相不冲淡。
            ink = ensureContrastOn(
                hsvColor(h, s, (v + 0.18f).coerceAtMost(0.94f)),
                hsvColor(h, s * 0.18f, 0.22f),
                4.5f,
            ),
            // [v16] surface 近中性化：饱和度从 0.92 降到 ~0.18，只轻微带色相（谷歌 M3 surface）。
            surface = hsvColor(h, s * 0.18f, 0.22f),
            surfaceSoft = hsvColor(h, s * 0.14f, 0.18f),
            // [1.4.26·R58 字母变白根因] 旧值 #F5F5F5 是浅色，落在深色分隔栏上勉强可见，
            // 但同一角色在浅色模式被写成纯白（见下方）——M3 规范 onSurface 是
            // 浅色 tone10 近黑 / 深色 tone90 近白。这里按规范改成深色近白。
            onSurface = Color(0xFFE6E1E5),
            onSurfaceTint = hsvColor(h, s * 0.30f, 0.95f),
            onSurfaceSoft = Color(0xFFF2F2F2),
            outlineVariant = hsvColor(h, s * 0.10f, 0.30f),
            onSurfaceVariant = hsvColor(h, s * 0.10f, 0.68f),
            surfaceContainer = hsvColor(h, s * 0.10f, 0.20f),
            // accent 容器保留原 surface 的高饱和色相（底栏选中/按钮/高亮）。
            primaryContainer = hsvColor(h, s * 0.92f, (v * 0.62f).coerceIn(0.30f, 0.58f)),
            primaryContainerSoft = hsvColor(h, s * 0.42f, 0.30f),
            // [1.4.28·底栏可读性] 深色底栏（#282b30）上的选中图标/文字必须是亮色。
            // 旧默认值 = primaryContainer（暗容器色），实测对比度只有 1.4~2.5:1，几乎看不见。
            // [R147] 底栏深色背景约 #282B30：选中前景按对比度自动提亮（保色相），
            // 目标 4.5:1（文字/小字标准），不再是"看起来亮、实测 1.25:1"。
            // [1.4.32·验收23] 用户拍板：可读性第一优先，达标前提下尽量加深、体现主题色。
            // 旧实现把饱和度砍到 s*0.60、明度顶到 0.95（近白）→ 苏芳变淡粉、丢主题辨识度。
            // 改法：饱和度保留主题原值 s，明度从主题原色 v 起步，让 ensureContrastOn 逐步
            // 提亮到「刚好达 4.5:1」——得到最饱和、刚好可读的主题红，可读性仍优先。
            onPrimaryContainer = ensureContrastOn(
                hsvColor(h, s, v.coerceIn(0.55f, 0.80f)),
                Color(0xFF282B30),
                4.5f,
            ),
        )
    } else {
        ThemePalette(
            ink = inkLight,
            surface = hsvColor(h, s * 0.18f, 0.98f),
            surfaceSoft = hsvColor(h, s * 0.14f, 0.96f),
            // [1.4.26·R58 字母变白根因] 旧值是纯白，导致「分隔栏字母更清晰」开关
            // 打开后在浅色分隔栏上打出白字，完全看不见。M3 规范 onSurface 浅色
            // 是 tone10 近黑（落在 surface 容器上的主文字色），这里按规范修正。
            onSurface = Color(0xFF1B1B1F),
            onSurfaceTint = hsvColor(h, s * 0.28f, 0.97f),
            onSurfaceSoft = Color(0xFF2B2B2B),
            outlineVariant = hsvColor(h, s * 0.12f, 0.90f),
            onSurfaceVariant = hsvColor(h, s * 0.12f, 0.55f),
            surfaceContainer = hsvColor(h, s * 0.10f, 0.96f),
            primaryContainer = hsvColor(h, s * 0.92f, (v * 0.90f).coerceIn(0.30f, 0.90f)),
            primaryContainerSoft = hsvColor(h, s * 0.45f, (v + 0.10f).coerceAtMost(0.95f)),
            // [1.4.28·底栏可读性] 浅色底栏上的选中图标/文字必须是深色。
            onPrimaryContainer = hsvColor(h, s * 0.75f, v.coerceAtMost(0.55f)),
        )
    }
}

/**
 * 当前主题的原色 palette（不读任何开关）。默认「彩色」用品牌基准色构造，保证取色有位可取。
 * 供 [monetizedPalette] / [currentThemePalette] 复用，避免重复计算深浅模式与原色。
 */
@Composable
internal fun currentBasePalette(): ThemePalette {
    val context = LocalContext.current
    val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val theme = LocalColorTheme.current
    return remember(theme, dark) {
        theme.palette(dark) ?: colorfulBasePalette(dark)
    }
}

/**
 * 莫奈化后的 palette（不读开关，纯计算）。供「背景莫奈化 / 文字莫奈化」两个独立
 * 开关的取色使用——这样即使「莫奈取色」总开关未开，文字/背景开关也能各自独立取到
 * 莫奈色相，互不串扰。
 */
@Composable
internal fun monetizedPalette(): ThemePalette {
    val base = currentBasePalette()
    return remember(base) { MonetColors.monetize(base) }
}

/**
 * [v17·M3 三色] 严格按谷歌官方 HCT 生成的 M3 palette（不读开关，纯计算）。
 * 种子：传统主题=themedInk，默认彩色=品牌蓝 0xFF5C89F2（用户拍板）。
 */
/**
 * [v18] 当前主题的 M3/HCT 种子：传统主题=themedInk，默认彩色=品牌蓝 0xFF5C89F2（用户拍板）。
 * m3Palette 与分隔栏三档取色共用同一口径，保证档位色与主 palette 同源。
 */
@Composable
internal fun currentThemeSeed(): Int {
    val theme = LocalColorTheme.current
    return remember(theme) {
        if (theme.isColorful) 0xFF5C89F2.toInt()
        else (theme.themedInk ?: theme.accent!!).toArgbInt()
    }
}

@Composable
internal fun m3Palette(): ThemePalette {
    val context = LocalContext.current
    val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val seed = currentThemeSeed()
    return remember(seed, dark) {
        M3Palette.fromSeed(seed, dark)
    }
}

/**
 * [v18] 深色顶栏搜索放大镜目标灰：用户实测拍板去掉「严格同灰/自适应清晰灰」，
 * 深色模式统一固定「明亮统一灰」#b8bbc0（联网核对：图标对比度底线 3:1，该亮灰在深色
 * 方块上达标，全主题看得清）。保留旧参数签名仅为源码兼容，值被忽略。
 */
internal fun searchGrayFor(@Suppress("UNUSED_PARAMETER") mode: Int = 0, @Suppress("UNUSED_PARAMETER") blockColor: Color = Color.Unspecified): Color =
    Color(0xFFB8BBC0)

/** Compose Color → ARGB int，供 HCT 种子使用。 */
private fun Color.toArgbInt(): Int =
    ((red * 255f).toInt().coerceIn(0, 255) shl 24) or
        ((green * 255f).toInt().coerceIn(0, 255) shl 16) or
        ((blue * 255f).toInt().coerceIn(0, 255) shl 8) or
        (alpha * 255f).toInt().coerceIn(0, 255)


/**
 * 当前主题调色板。深浅色跟随 AppCompatDelegate 的 uiMode。
 *
 * [1.4.23·莫奈取色 v2] 种子是「当前色彩主题下每个 role 自己的原色」，不是壁纸：
 *  1) 先解析出当前主题的原色 palette（默认「彩色」用品牌基准色构造一份）；
 *  2) 莫奈开关打开时，逐 role 提纯后返回——每个 role 用自己原色，各算各的；
 *  3) 关闭时完全回退原逻辑（默认彩色返回 null = 零改动）。
 * 这样选不同色彩主题，莫奈结果因原色色相不同而不同，不再千篇一律。
 *
 * [v14] 本函数只由「莫奈取色」总开关 [MonetStore.enabled] 驱动，供底栏选中、顶栏方块、
 * 列表按下高亮、播放列表/随机播放按钮、搜索方块等原有角色使用；背景/文字两个新开关
 * 改走 [monetizedPalette]，不再依赖本函数，避免只开子开关就误触发这些原有角色。
 */
@Composable
internal fun currentThemePalette(strictAccent: Boolean = false): ThemePalette? {
    val monetEnabled by MonetStore.enabled.collectAsState()
    val theme = LocalColorTheme.current
    // [v18·逐元素取色] 实验开关优先：各元素从自己的原色独立提纯（与 M3/莫奈并列，可随时关回退）。
    val perElement = perElementPaletteOrNull()
    if (perElement != null) return perElement
    // [v21] M3 固化主题：选它就等于原来「开了 M3 三色开关」，返回写死的真实 M3 色板。
    // 与莫奈互斥（二者不叠加）；原先由 MonetStore.m3Enabled 驱动，现改由主题自身属性决定。
    if (theme.isM3Fixed) {
        val context = LocalContext.current
        val dark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return M3Palette.fixedPalette(theme, dark)
    }
    if (monetEnabled) return monetizedPalette()
    // 未开莫奈：默认「彩色」必须零改动返回 null。
    if (theme.isColorful) return null
    // [1.4.25·R51] strictAccent=true 时，传统 8 色主题也不再染这些"强调按钮"
    // （歌曲页/我喜欢的歌曲页顶栏的播放列表 / 随机播放）。用户新口径：
    // 不开莫奈一律保持原 PNG 红底白字；只有开莫奈、M3 固化、逐元素取色才染色。
    if (strictAccent) return null
    return currentBasePalette()
}

/**
 * 默认「彩色」主题开启莫奈时的基准原色：全 role 统一取品牌蓝点缀 0xFF5C89F2 色相，
 * 让彩色主题下开莫奈时按钮与顶栏/底栏取色一致；关闭莫奈时本函数结果不会被使用（彩色返回 null）。
 */
private fun colorfulBasePalette(dark: Boolean): ThemePalette {
    // [1.4.24] 彩色主题开莫奈时，surface/surfaceSoft/onSurfaceTint 必须与顶栏/底栏
    // 的淡蓝紫取色（ink=0xFF5C89F2）同色相，避免淡粉底压蓝字。口径与 8 个传统
    // 主题一致：同一色相 H，背景走深档/文字走淡档。未开莫奈时本函数结果不被使用
    // （currentThemePalette 对彩色直接返回 null），原红底红字保持不变。
    val blue = Color(0xFF5C89F2)
    val hsv = blue.toHsv()
    val h = hsv[0]
    val s = hsv[1]
    val v = hsv[2]
    return if (dark) {
        ThemePalette(
            // [R149] 彩色主题深色档（实测 2.18 最差）：按实际深底自动提亮到 4.5:1
            ink = ensureContrastOn(hsvColor(h, s, 0.86f), hsvColor(h, s * 0.18f, 0.22f), 4.5f),
            surface = hsvColor(h, s * 0.18f, 0.22f),
            surfaceSoft = hsvColor(h, s * 0.14f, 0.18f),
            // [1.4.26·R58 字母变白根因] 旧值 #F5F5F5 是浅色，落在深色分隔栏上勉强可见，
            // 但同一角色在浅色模式被写成纯白（见下方）——M3 规范 onSurface 是
            // 浅色 tone10 近黑 / 深色 tone90 近白。这里按规范改成深色近白。
            onSurface = Color(0xFFE6E1E5),
            onSurfaceTint = hsvColor(h, s * 0.30f, 0.95f),
            onSurfaceSoft = Color(0xFFF2F2F2),
            outlineVariant = hsvColor(h, s * 0.10f, 0.30f),
            onSurfaceVariant = hsvColor(h, s * 0.10f, 0.68f),
            surfaceContainer = hsvColor(h, s * 0.10f, 0.20f),
            primaryContainer = hsvColor(h, s * 0.92f, (v * 0.62f).coerceIn(0.30f, 0.58f)),
            primaryContainerSoft = hsvColor(h, s * 0.42f, 0.30f),
            // [1.4.28·底栏可读性] 深色底栏上的选中图标/文字必须是亮色。
            // [R147] 再加一层：按底栏实际底色算到 4.5:1（保色相提亮）。
            onPrimaryContainer = ensureContrastOn(
                hsvColor(h, s * 0.60f, 0.95f),
                Color(0xFF282B30),
                4.5f,
            ),
        )
    } else {
        ThemePalette(
            ink = blue,
            surface = hsvColor(h, s * 0.18f, 0.98f),
            surfaceSoft = hsvColor(h, s * 0.14f, 0.96f),
            // [1.4.26·R58 字母变白根因] 旧值是纯白，导致「分隔栏字母更清晰」开关
            // 打开后在浅色分隔栏上打出白字，完全看不见。M3 规范 onSurface 浅色
            // 是 tone10 近黑（落在 surface 容器上的主文字色），这里按规范修正。
            onSurface = Color(0xFF1B1B1F),
            onSurfaceTint = hsvColor(h, s * 0.28f, 0.97f),
            onSurfaceSoft = Color(0xFF2B2B2B),
            outlineVariant = hsvColor(h, s * 0.12f, 0.90f),
            onSurfaceVariant = hsvColor(h, s * 0.12f, 0.55f),
            surfaceContainer = hsvColor(h, s * 0.10f, 0.96f),
            primaryContainer = hsvColor(h, s * 0.92f, (v * 0.90f).coerceIn(0.30f, 0.90f)),
            primaryContainerSoft = hsvColor(h, s * 0.45f, (v + 0.10f).coerceAtMost(0.95f)),            // [1.4.28·底栏可读性] 浅色底栏上的选中图标/文字必须是深色。
            onPrimaryContainer = hsvColor(h, s * 0.75f, v.coerceAtMost(0.55f)),
        )
    }
}

/** 组 2：开关蓝点 / 蓝色文字 / 原有红色点缀；默认彩色原样返回。 */
@Composable
fun themedInk(base: Color): Color = currentThemePalette()?.ink ?: base

/**
 * 按资源 id 直接取色时使用：只对两个「蓝色点缀」资源生效，其余资源原样返回。
 * 供 `colorResource(if (selected) R.color.btn_text_color_blue else ...)` 这类条件取色点使用。
 */
@Composable
fun themedInkRes(@androidx.annotation.ColorRes resId: Int): Color {
    val base = colorResource(resId)
    return if (resId == R.color.btn_text_color_blue || resId == R.color.panel_action_text)
        themedInk(base)
    else base
}

/**
 * 按下高亮条上的文字。
 *
 * [1.4.28·对比度修正] 旧实现取 `onSurfaceTint`（浅色恒为 V=0.97 的近白），
 * 而按下底色是 `primaryContainer`（浅色 V=0.90 的高饱和亮色）——白字压亮底，
 * 实测对比度只有 1.6~2.0:1，按住任意设置项/歌曲行标题就看不见。
 * 改用 `onSurface`：浅色近黑、深色近白，与亮/暗两种高亮底都成立。
 */
@Composable
fun themedPressedTextColor(normalColor: Color, pressed: Boolean): Color {
    if (!pressed) return normalColor
    val palette = currentThemePalette()
        ?: return colorResource(R.color.setting_item_text_color_highlight)
    // [R142·2026-11-26] 按**按压底色**算对比度，而不是无条件用 onSurface。
    //
    // 用户实测（苏芳 + 全莫奈化）：按下是暗红底，图标变白能看清，但设置项名称/右侧小字/
    // 歌曲名仍是黑色 → 黑字压暗红底，看不清。根因是 1.4.28 把文字从"跟随高亮底的白色"
    // 改成"跟随主题 onSurface"，而 onSurface 与 primaryContainer 并没有配对保证。
    // M3 的正确口径是容器色配对应的 on-color：高亮底是 primaryContainer 就该用
    // onPrimaryContainer / 按实际底色算 WCAG 后取近黑或近白。
    val bg = palette.primaryContainer
    val on = palette.onSurface
    return when {
        contrastRatioOf(on, bg) >= 4.5f -> on
        bg.luminance() > 0.5f -> Color(0xFF1B1B1F)
        else -> Color(0xFFE6E1E5)
    }
}

/**
 * [R147/R149·2026-11-26] 把前景色**提亮**到与背景的对比度达标（保持色相与饱和度）。
 *
 * 用户拍板原话："把按下态变亮——提明度、保色相，不是冲淡成浅色"。
 * 做法不是手动给每个主题调一个色值（9 主题 × 深浅 × 3 个 role 容易漏），
 * 而是按实际背景亮度逐次 +0.05 明度，直到 WCAG 达标；色相与饱和度原样保留，
 * 所以视觉上是"同一颜色更亮"，不会变成灰粉。
 */
internal fun ensureContrastOn(foreground: Color, background: Color, minRatio: Float): Color {
    if (contrastRatioOf(foreground, background) >= minRatio) return foreground
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (foreground.red * 255f).toInt().coerceIn(0, 255),
        (foreground.green * 255f).toInt().coerceIn(0, 255),
        (foreground.blue * 255f).toInt().coerceIn(0, 255),
        hsv,
    )
    var v = hsv[2]
    var best = foreground
    repeat(14) {
        v = (v + 0.05f).coerceAtMost(0.99f)
        val c = Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], v)))
        best = c
        if (contrastRatioOf(c, background) >= minRatio) return c
    }
    return best
}

/** WCAG 相对亮度对比度（与 MonetSurface 里的私有实现同口径）。 */
private fun contrastRatioOf(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 列表行背景统一入口。
 * 默认彩色：逐字沿用原 listview_selector（PNG 蓝底 + 浅蓝选中底），零改动。
 * 非默认主题：按下整行高亮用组 1 的 surface；选中/编辑模式高亮用更浅的 surfaceSoft。
 *
 * [1.4.32·验收图1] [drawDivider] 供「原本靠 PNG 自带底部分隔线」的页面（更多页/搜索页）
 * 在主题/莫奈态补画那条线——换用本函数后 drawRect 纯色不再有 PNG 自带的分隔线，
 * 用户实测「开莫奈后这几行分界线没了」。歌曲页/专辑页另有独立 LibraryDivider，保持默认 false。
 */
@Composable
fun Modifier.themedListRowBackground(
    pressed: Boolean,
    activated: Boolean = false,
    focused: Boolean = false,
    drawDivider: Boolean = false,
): Modifier {
    val palette = currentThemePalette()
    // [v11] 莫奈开启时列表行也用大播放页同款渐变；放大半径让相邻行落在同一色段不割裂。
    val monetTint = rememberMonetContentTint()
    // [1.4.32·验收图1] 分隔线高度(px)与回退色，仅 drawDivider=true 时用。
    val dividerHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.res.dimensionResource(R.dimen.listview_dividerHeight).toPx()
    }
    val dividerFallbackColor = colorResource(R.color.listview_divider_color)
    return if (palette == null) {
        this.smartisanPainterBackground(
            rememberSmartisanDrawablePainter(
                R.drawable.listview_selector,
                pressed = pressed,
                focused = focused,
                activated = activated,
            )
        )
    } else {
        val cardColor = colorResource(R.color.surface_card)
        // [1.4.32·验收图1] 分隔线颜色须在 @Composable 上下文取值：
        // monetOutlineVariant() 是 @Composable，不能放进 drawBehind 的 DrawScope。
        val dividerColor = monetOutlineVariant() ?: dividerFallbackColor
        this.drawBehind {
            // 1.3.9：非默认主题必须保留列表行的常态白底。1.3.8 只在按下/激活时叠色，
            // normal 态什么都不画，整片列表露出 page_background 的浅灰 —— 这就是用户实机
            // 截图里「主题态列表白底丢失」。先铺 surface_card，再叠按下/激活主题色，
            // 与默认 listview_selector 的 normal 项完全一致。
            drawRect(cardColor)
            if (monetTint != null)
                drawRect(monetContentBrush(monetTint, MonetContentGlowRadius))
            when {
                pressed || focused -> drawRect(palette.primaryContainer)
                activated -> drawRect(palette.primaryContainerSoft)
            }
            // [1.4.32·验收图1] 补回被换掉的 PNG 自带底部分隔线（更多页/搜索页）。
            // 颜色与 LibraryDivider 同款：开莫奈=带主题色相的中性灰，否则回退官方 divider 色。
            if (drawDivider) {
                val lineH = dividerHeightPx
                if (lineH > 0f) {
                    drawRect(
                        color = dividerColor,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - lineH),
                        size = androidx.compose.ui.geometry.Size(size.width, lineH),
                    )
                }
            }
        }
    }
}

/**
 * 位图染色，供「PNG 蓝色点缀」在非默认主题下换色相：
 *  - blueOnly=true：只替换偏蓝像素（开关蓝点、加号图标），保留其余灰/白细节；
 *  - blueOnly=false：所有非透明像素按原亮度映射到目标色（编辑模式勾选框），保留明暗层次。
 * 两种模式都保留原 alpha，抗锯齿不丢。
 */
internal fun tintBitmap(src: Bitmap, target: Color, blueOnly: Boolean): Bitmap {
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val tr = target.red * 255f
    val tg = target.green * 255f
    val tb = target.blue * 255f
    for (i in px.indices) {
        val c = px[i]
        val a = (c ushr 24) and 0xff
        if (a < 24) continue
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        if (blueOnly && !(b > r + 18 && b > g + 8 && b > 70)) continue
        val k =
            if (blueOnly) (b / 255f).coerceIn(0.35f, 1f)
            else (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        val nr = (tr * k).toInt().coerceIn(0, 255)
        val ng = (tg * k).toInt().coerceIn(0, 255)
        val nb = (tb * k).toInt().coerceIn(0, 255)
        px[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

internal fun tintImageBitmap(src: ImageBitmap, target: Color, blueOnly: Boolean): ImageBitmap =
    tintBitmap(src.asAndroidBitmap(), target, blueOnly).asImageBitmap()

// [v24·R18-N3/F2] v20 的 relativeLuminanceOf/contrastRatioOf/legibleForeground 已随误改按钮回退删除。

/**
 * 1.3.9：把「PNG 点缀染色」从一刀切的 blueOnly / 亮度映射细分成几类，
 * 避免再出现 1.3.8 那种把整张双色 PNG 用 SrcIn 压成单色空框的问题。
 */
internal enum class ThemeTintMode {    /** 只替换偏蓝像素，保留其余灰/白细节（设置开关蓝点、正在播放指示）。 */
    BLUE_ONLY,
    /** 所有非透明像素按亮度映射到目标色（编辑模式勾选框）。 */
    LUMINANCE,
    /** 只替换偏红像素（底栏选中态 tabbar_*_down），未选中的灰色态原样保留。 */
    RED_ONLY,
    /** 新建播放列表圆盘：圆盘染主题色，蓝色加号笔画挖成透明（露出白底）。 */
    ADD_PLUS_TRANSPARENT,
    /**
     * 1.4.0 U3 主题按键背景：**只取**「完成」按钮 PNG 的大小与圆角形状——
     * 所有非透明像素统一填成主题纯色（保留 alpha），对勾/圆环因同色而消失，
     * 得到纯色圆角方块，方块内不含任何图案。不再把整张双色 PNG 染色后叠图标。
     */
    CONFIRM_BLOCK,
    /**
     * 1.4.0 U5 列表/卡片行按下高亮：只把「偏蓝高亮底」像素精确填成
     * palette.surface（与已改的 9 处 themedListRowBackground 同一色值），
     * 保留原 alpha 与圆角；常态白底不含蓝像素，原样保留。
     */
    ROW_PRESSED,
    /**
     * 1.4.2 顶栏最右图标：用户改口「不挖、不镂空、不重绘，只把图标颜色改成白色」。
     * 所有描边像素统一填不透明纯白（RGB 255,255,255 / alpha 255），落在主题色实心方块上。
     * [v16] 深色同样改为实心纯白：半透明（原 WHITE_TRANSLUCENT）会从方块透出底色、
     * 把图标"染"成带主题色相的灰；实心白彻底盖住方块，深浅色统一、不串色。
     */
    WHITE_SOLID,
    /**
     * [v17] 深色顶栏搜索放大镜灰方案：只把描边像素换色相为目标灰，**完整保留原 alpha 渐变**
     * （抗锯齿边缘不拉满，消除 v16 锯齿）。目标灰由调用方按 searchGrayMode + 方块底色算出。
     */
    SEARCH_GRAY,
    // [v24·R18-N3/F2] SOLID_FOREGROUND / SOLID_NEUTRAL 已随误改按钮回退删除。
}

/** 按细分模式给位图上色；保留原 alpha，抗锯齿不丢。 */
internal fun tintBitmapByMode(src: Bitmap, target: Color, mode: ThemeTintMode): Bitmap {
    if (mode == ThemeTintMode.BLUE_ONLY) return tintBitmap(src, target, true)
    if (mode == ThemeTintMode.LUMINANCE) return tintBitmap(src, target, false)
    val w = src.width
    val h = src.height
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(w * h)
    out.getPixels(px, 0, w, 0, 0, w, h)
    val tr = target.red * 255f
    val tg = target.green * 255f
    val tb = target.blue * 255f
    // [v24·R18-N3/F2] SOLID 模式的 maxAlpha 归一化已随误改按钮回退删除。
    for (i in px.indices) {
        val c = px[i]
        val a = (c ushr 24) and 0xff
        if (a < 24) continue
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        when (mode) {
            ThemeTintMode.RED_ONLY -> {
                // 选中态 tabbar_*_down 是纯红 (230,64,64)；未选中灰色 PNG 不满足此条件。
                if (!(r > 120 && r > g + 40 && r > b + 40)) continue
                val k = (r / 255f).coerceIn(0.40f, 1f)
                px[i] = (a shl 24) or
                    ((tr * k).toInt().coerceIn(0, 255) shl 16) or
                    ((tg * k).toInt().coerceIn(0, 255) shl 8) or
                    (tb * k).toInt().coerceIn(0, 255)
            }
            ThemeTintMode.ADD_PLUS_TRANSPARENT -> {
                // 深蓝加号 (92,137,242) -> 挖成透明；淡蓝/白圆盘 -> 染主题色。
                val isPlus = r < 160 && b > 180 && (b - r) > 60
                if (isPlus) {
                    px[i] = 0
                    continue
                }
                val k = ((0.299f * r + 0.587f * g + 0.114f * b) / 255f)
                    .coerceIn(0.35f, 1f)
                px[i] = (a shl 24) or
                    ((tr * k).toInt().coerceIn(0, 255) shl 16) or
                    ((tg * k).toInt().coerceIn(0, 255) shl 8) or
                    (tb * k).toInt().coerceIn(0, 255)
            }
            ThemeTintMode.CONFIRM_BLOCK -> {
                // 1.4.0 U3：只取 PNG 的大小与圆角形状。所有非透明像素统一填成主题纯色、
                // 保留原 alpha（圆角/抗锯齿不丢）；对勾与方块同色，图案彻底消失，
                // 不再有「挖掉对勾留下对勾形空洞」或「亮度缩放导致色值不精确」。
                if (a >= 24) {
                    px[i] = (a shl 24) or
                        (tr.toInt() shl 16) or (tg.toInt() shl 8) or tb.toInt()
                }
            }
            ThemeTintMode.ROW_PRESSED -> {
                // 1.4.0 U5：按下态 PNG 的蓝色高亮底 -> 精确换成 palette.surface。
                // 用「精确 RGB + 保留 alpha」，与 themedListRowBackground 的
                // drawRect(palette.surface) 是同一个十六进制值（验收口径）。
                val isHighlight = b > r + 18 && b > g + 8 && b > 70
                if (isHighlight) {
                    px[i] = (a shl 24) or
                        (tr.toInt() shl 16) or (tg.toInt() shl 8) or tb.toInt()
                }
            }
            ThemeTintMode.WHITE_SOLID -> {
                // 1.4.2 顶栏最右图标：描边像素实心纯白，alpha 拉满 255。
                // 与预览 4 的 solid() 一致（alpha>阈值→RGB 换白 + alpha=255），
                // 不镂空、不挖、不重绘；半透明抗锯齿边缘也一并填实，保证清晰。
                // [v16] 深浅色统一用实心白（深色半透明会从主题方块透出底色串色）。
                if (a >= 24) {
                    px[i] = (255 shl 24) or (255 shl 16) or (255 shl 8) or 255
                }
            }
            ThemeTintMode.SEARCH_GRAY -> {
                // [v17] 搜索放大镜灰：只换色相(RGB→target)，**完整保留原 alpha**，
                // 抗锯齿边缘仍是半透明渐变 → 消除 v16 把边缘拉满实心导致的锯齿。
                if (a >= 8) {
                    px[i] = (a shl 24) or
                        (tr.toInt() shl 16) or (tg.toInt() shl 8) or tb.toInt()
                }
            }
            else -> Unit
        }
    }
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}
