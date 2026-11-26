package com.smartisan.music.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.smartisan.music.R
import com.smartisan.music.data.settings.MonetStore

/**
 * [1.4.23·莫奈取色] 表面色 / 背景晕染 helper（回退到 v12 后重做）。
 *
 * 设计原则（用户明确要求）：莫奈取色**只改颜色，不改任何布局参数**。所有 helper 在
 * 对应开关关闭时返回资源原色 / 不绘制任何东西，保证默认状态与原 1.4.23 字节级一致。
 *
 * 开关归属（用户拍板）：
 *  - 「莫奈取色」主开关 [enabled]：底栏选中、顶栏方块、播放列表/随机按钮、搜索方块、
 *    列表按下高亮、大播放页背景晕染、顶栏/底栏表面色。
 *  - 「背景莫奈化」子开关 [backgroundEnabled]（依赖主开关）：只管白色内容背景铺莫奈渐变。
 *
 * 已删除 v13/v14 的「文字莫奈化」开关与全部语义角色取色 token。
 */

/**
 * [v21] M3 模式是否生效：改为「当前选中的主题是不是 M3 固化主题」。
 * 原来由 M3 开关驱动，开关删除后由主题属性决定——选 M3 主题即等于原 M3 开关全开效果。
 */
@Composable
private fun m3Active(): Boolean {
    return LocalColorTheme.current.isM3Fixed
}

/**
 * [v19·逐元素·修正6] 逐元素取色实验开关是否生效。
 * 用于给原本被「莫奈主开关 / 背景莫奈化」门控挡回的 helper 加一条放行通道：
 * 只开逐元素（不开莫奈/M3）时，细线/次级文字/背景渐变/顶栏底栏表面也要吃到逐元素色。
 */
@Composable
private fun perElementActive(): Boolean =
    MonetStore.perElementEnabled.collectAsState().value

/**
 * [v17] 当前"生效取色引擎"的 palette：M3 模式用官方 HCT palette，否则用莫奈提纯 palette。
 * 所有取色 helper 统一走这里，保证 M3 与莫奈两套引擎只认一个数据源、互斥不串。
 */
@Composable
internal fun activePalette(): ThemePalette {
    // [v18·逐元素取色] 开关开启时，背景/灰栏也走逐元素原色 palette。
    val perElement = perElementPaletteOrNull()
    if (perElement != null) return perElement
    // [v21] M3 固化主题返回写死的真实 M3 色板（原 m3Palette() 实时算法结果）。
    if (m3Active()) {
        val context = LocalContext.current
        val dark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return M3Palette.fixedPalette(LocalColorTheme.current, dark)
    }
    return monetizedPalette()
}

/** 莫奈取色总开关（原本就有，控制整体调色板莫奈化）。M3 模式下视为开启。 */
@Composable
fun monetEnabled(): Boolean {
    val enabled by MonetStore.enabled.collectAsState()
    return enabled || m3Active()
}

/**
 * 背景莫奈化开关：只管白色内容背景铺莫奈渐变。
 * 依赖主开关——只有「莫奈取色」开启时才生效；主开关关则恒 false。
 * [v17] M3 模式下子开关收起、内部默认全铺，故视为开启。
 */
@Composable
fun monetBackgroundEnabled(): Boolean {
    val enabled by MonetStore.enabled.collectAsState()
    val background by MonetStore.backgroundEnabled.collectAsState()
    return m3Active() || (enabled && background)
}

/**
 * [v16·分隔栏莫奈化] 首字母/分组灰栏是否铺莫奈 surfaceContainer 色。
 * 独立子开关，依赖主开关；默认关（保持官方灰）。
 * [v17] M3 模式下子开关收起、内部默认全铺，故视为开启（灰栏用 M3 surfaceContainer）。
 */
@Composable
fun monetSeparatorEnabled(): Boolean {
    val separator by MonetStore.separatorEnabled.collectAsState()
    // [1.4.26·R58 重做] 删掉「独立控制」这层多余的条件。
    // 旧实现 m3Active() || (separator && (enabled || independent))：independent 默认 true，
    // 于是这个开关常年开、关掉后只要莫奈主开关还开着仍无变化——用户根本感知不到。
    // 现在「分隔栏莫奈化」自己就是一个独立开关：开就生效、关就回原厂灰图，不再看主开关。
    return m3Active() || separator
}

/**
 * [v16·谷歌 M3] 列表分割细线色（outlineVariant）。归「莫奈取色」主开关；
 * 关闭 / 取不到色返回 null，调用点回退官方 listview_divider_color。
 */
@Composable
fun monetOutlineVariant(): Color? {
    // [v19·逐元素·修正6] 逐元素开启时也放行（原被莫奈主开关挡回）。
    if (!monetEnabled() && !perElementActive()) return null
    return activePalette().outlineVariant
}

/**
 * [v16·谷歌 M3] 次级文字色（右侧字母栏、列表副标题，onSurfaceVariant）。
 * 归「莫奈取色」主开关；关闭返回 null，调用点回退官方资源色。
 */
@Composable
fun monetOnSurfaceVariant(): Color? {
    // [v19·逐元素·修正6] 逐元素开启时也放行（原被莫奈主开关挡回）。
    if (!monetEnabled() && !perElementActive()) return null
    return activePalette().onSurfaceVariant
}

/**
 * [v16·谷歌 M3] 首字母/分组灰栏容器色（surfaceContainer）。
 * 归「分隔栏莫奈化」独立子开关；关闭返回 null，调用点回退官方 smartlist_header_bg。
 *
 * [v18] 曾做三档（tone94/92/90），用户实测浅色三档色差仅 ΔE2、肉眼无区别，
 * [v19] 统一固定最深档；[1.4.28] 三档字段与 setter 已彻底删除。
 *
 * [v19·逐元素] 逐元素开关开启时：灰栏走逐元素引擎（保留原生灰层次），纳入 engine。
 */
@Composable
fun monetSurfaceContainer(): Color? {
    // [v19·逐元素·修正5] 逐元素开启 → 灰栏走逐元素引擎的原生灰栏色（有层次、不塌缩）。
    val perElement = perElementPaletteOrNull()
    if (perElement != null) return perElement.surfaceContainer
    if (!monetSeparatorEnabled()) return null
    val context = LocalContext.current
    val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val theme = LocalColorTheme.current
    // [v21] M3 固化主题：分隔栏用该主题固化的最深档色；其余主题仍走官方 HCT 精确取色。
    if (theme.isM3Fixed) return M3Palette.fixedSeparatorColor(theme, dark)
    // [1.4.26·R58 取色公式重做] 旧实现返回 palette.surfaceContainer：浅色 V0.96、
    // 深色 V0.20，与列表背景/原厂灰只差 1.5%/5%，开了等于没开；而且三个方案
    // 返回同一个颜色（if (style==2) A else A）。这里改为在 surface 上叠主题色相，
    // 做出相当于 M3 tone90/tone22 的明显色差，且色相与列表背景同源。
    val palette = activePalette()
    return remember(palette.surface, palette.ink, dark) {
        // 实测：浅色 22% 混色 → 亮度 90.2%→82.1%（差 8.2%），深色 20% → 20.3%→27%（差约 7%）。
        // 相当于 M3 tone98→tone90 的层级差，肉眼能明确看出边界。
        blend(palette.surface, palette.ink, if (dark) 0.20f else 0.22f)
    }
}

/**
 * 分隔栏字母色：**始终按分隔栏实际底色算对比度**，不再受任何开关控制。
 *
 * 用户拍板（1.4.28）：字母更清晰不需要手动设置，必须始终清晰。
 * M3 规定 onSurface 是「表面上的正文色」（浅 tone10 / 深 tone90），但分隔栏底色是
 * 在 surface 上叠了 20~22% 的 ink，比 surface 深，极端主题下 onSurface 的对比度可能不足。
 * 所以这里先量对比度：≥ 4.5:1 直接用 onSurface（保持与全局文字同一色）；
 * 不足则按底色亮暗取近黑 / 近白，保证任何主题、任何明暗模式下都看得清。
 *
 * [1.4.29·R121] 必须由调用方传入「该处真实底色」：
 * 原来函数内部自己调 monetSurfaceContainer()，但搜索页与播放队列的分组头底色
 * 是原厂图、并不换莫奈，拿莫奈色算对比度会算在错的底色上。
 */
@Composable
fun monetSeparatorLetterColor(background: Color?): Color? {
    if (!monetSeparatorEnabled()) return null
    val palette = activePalette()
    val bg = background ?: return palette.onSurface
    return remember(palette.onSurface, bg) {
        if (contrastRatio(palette.onSurface, bg) >= 4.5f) {
            palette.onSurface
        } else if (bg.luminance() > 0.5f) {
            Color(0xFF1B1B1F)
        } else {
            Color(0xFFE6E1E5)
        }
    }
}

/** WCAG 对比度：(亮者 + 0.05) / (暗者 + 0.05)。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * [v18·衔接细线] 小播放栏与底栏衔接分界线的加深 accent 色。
 * 用户拍板：仅「背景莫奈化开 + 播放栏渐变过渡关」（此时播放栏与底栏连成一片）时，
 * 把这条细线按主题色相加深、饱和度拉到规范允许的最高档，作为明显分界；渐变过渡开时不生效。
 * 返回 null 表示走官方 nav_list_line（零改动）。
 */
@Composable
fun monetAccentDivider(): Color? {
    val enabled by MonetStore.enabled.collectAsState()
    val background by MonetStore.backgroundEnabled.collectAsState()
    val transition by MonetStore.playbackTransition.collectAsState()
    val on = (enabled || m3Active()) && background && !transition
    if (!on) return null
    // [v19·衔接细线] 用户拍板：颜色改成与底部导航栏「被选中状态图标」的颜色一致。
    // 底栏选中图标取色 = currentThemePalette()?.onPrimaryContainer（见 SmartisanBottomTabItem）。
    // 这里取同一来源同一角色，保证细线与选中图标逐字节同色；取不到色回退 null 走官方 nav_list_line。
    return currentThemePalette()?.onPrimaryContainer
}

/**
 * [1.4.29·R123] 任意底色叠一层主题色相，保留原 alpha。供硬编码底色的浮层（名称提示气泡等）
 * 接入莫奈；开关关闭时原样返回。
 */
@Composable
fun monetTintColor(base: Color, amount: Float = 0.12f): Color {
    if (!monetEnabled() && !perElementActive()) return base
    val palette = activePalette()
    return blend(base, palette.primaryContainer, amount)
}

/** 线性混色：c = base*(1-t) + overlay*t。 */
private fun blend(base: Color, overlay: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1f - k) + overlay.red * k,
        green = base.green * (1f - k) + overlay.green * k,
        blue = base.blue * (1f - k) + overlay.blue * k,
        alpha = base.alpha,
    )
}

/**
 * 顶栏 / 底栏等「表面」底色：莫奈取色开启时给原色叠一层极淡的莫奈主色相；
 * 关闭时返回原资源色（零改动）。归「莫奈取色」主开关。
 */
@Composable
fun monetSurface(baseRes: Int, amount: Float = 0.10f): Color {
    val base = colorResource(baseRes)
    // [v19·逐元素·修正6] 逐元素开启时也放行顶栏/底栏表面色（原被莫奈主开关挡回）。
    if (!monetEnabled() && !perElementActive()) return base
    val palette = currentThemePalette() ?: return base
    return blend(base, palette.ink, amount)
}

/**
 * [v19·小播放栏归属] 小播放栏背景专用取色，归「背景莫奈化」子开关（不是莫奈主开关）。
 *
 * 用户拍板：只有「开莫奈取色 + 开背景莫奈化」小播放栏才变色；只开莫奈主开关不开背景莫奈化
 * 时保持官方原色。逐元素开启时也放行（独立引擎，与主题挂钩）。
 * 混色算法与 [monetSurface] 一致（在 base 上叠一层主题色相），只改开关归属。
 */
@Composable
fun monetPlaybackSurface(baseRes: Int, amount: Float = 0.12f): Color {
    val base = colorResource(baseRes)
    if (!monetBackgroundEnabled() && !perElementActive()) return base
    val palette = currentThemePalette() ?: return base
    return blend(base, palette.ink, amount)
}

/**
 * [v20·播放栏渐变收口色] 方案 A（渐变过渡开启）专用的收口色，按「莫奈主开关 / 背景莫奈化 /
 * 逐元素」任一放行。
 *
 * 为什么必须单独一个函数：方案 A 的渐变是把播放栏底部收口到**底栏色**，而底栏走的是
 * [monetSurface]（主开关放行）。v19 把整个播放栏都归给「背景莫奈化」后，只开莫奈主开关时
 * 收口色是官方灰、底栏却是莫奈色 → 渐变既对不上底栏也不显色，用户看不到过渡。
 * 方案 B 的纯色底仍走 [monetPlaybackSurface]（只归背景莫奈化），两者边界清晰、互不串。
 */
@Composable
fun monetPlaybackGradientEnd(baseRes: Int, amount: Float = 0.12f): Color {
    val base = colorResource(baseRes)
    if (!monetEnabled() && !monetBackgroundEnabled() && !perElementActive()) return base
    val palette = currentThemePalette() ?: return base
    return blend(base, palette.ink, amount)
}

/**
 * [v12·顶栏向底栏看齐 + 默认态零改动] 顶栏背景专用取色，归「莫奈取色」主开关。
 *  - 关闭：返回官方 title_bar_background 原色（零改动）；
 *  - 开启：返回底栏同款 bottom_bar_surface + 0.12f，与底栏口径统一。
 */
@Composable
fun monetTitleBarSurface(): Color =
    if (!monetEnabled() && !perElementActive()) colorResource(R.color.title_bar_background)
    else monetSurface(R.color.bottom_bar_surface, 0.12f)

/**
 * [v11] 大播放页与列表页共用的莫奈渐变色组。三色与透明度逐字沿用大播放页
 * MonetPlaybackGlow（surface→surfaceSoft→onSurfaceTint），保证「同款取色、色相连贯」。
 */
internal data class MonetContentTint(val inner: Color, val middle: Color, val outer: Color)

/**
 * [背景莫奈化·子开关] 白色内容背景铺莫奈渐变专用色组。
 * 背景莫奈化关闭 / 无可取色时返回 null，调用点回退原色零改动。
 */
@Composable
internal fun rememberMonetContentTint(): MonetContentTint? {
    // [v19·逐元素·修正6] 逐元素开启时也放行背景渐变（原被背景莫奈化子开关挡回）。
    if (!monetBackgroundEnabled() && !perElementActive()) return null
    val palette = activePalette()
    return MonetContentTint(
        palette.surface.copy(alpha = 0.34f),
        palette.surfaceSoft.copy(alpha = 0.26f),
        palette.onSurfaceTint.copy(alpha = 0.18f),
    )
}

/**
 * [莫奈取色·主开关] 大播放页背景晕染专用色组（用户拍板：归「莫奈取色」主开关）。
 * 与 [rememberMonetContentTint] 同色组，但开关归属不同。
 */
@Composable
private fun rememberMonetPlaybackGlowTint(): MonetContentTint? {
    // [v19·逐元素·修正6] 逐元素开启时也放行大播放页晕染（原被莫奈主开关挡回）。
    if (!monetEnabled() && !perElementActive()) return null
    val palette = currentThemePalette() ?: return null
    return MonetContentTint(
        palette.surface.copy(alpha = 0.34f),
        palette.surfaceSoft.copy(alpha = 0.26f),
        palette.onSurfaceTint.copy(alpha = 0.18f),
    )
}

/** 共享渐变 Brush；radius 越大，各表面各自绘制时取样越集中在同一色段，相邻处不割裂。 */
internal fun monetContentBrush(tint: MonetContentTint, radius: Float): Brush =
    Brush.radialGradient(
        colors = listOf(tint.inner, tint.middle, tint.outer, Color.Transparent),
        radius = radius,
    )

/**
 * [1.4.29·R122~R125] 页面「空白区」底色渐变：原厂竖纹纹理之上叠一层莫奈色雾。
 *
 * 用户 2026-11-25 拍板：
 *  - 原厂竖纹纹理图保留，采用「纹理 + 叠渐变」，不换纯色渐变；
 *  - 两档浓度：更多页 / 设置页的空白区单独暗一档（[pageDeep] = true），其余页面正常档；
 *  - 全局开关「空白区统一加深」打开后，所有页面统一用暗档；
 *  - 「空白区」= 被竖纹纹理图覆盖的区域；白色卡片与列表行不算。
 *
 * 旧实现（rememberMonetMoreBackgroundTint）用 primaryContainer@16% + surfaceContainer@14%
 * + onSurfaceTint@10% 叠在 #F2F2F2 上，亮度差不到 1%，且 radial 到边缘衰减成透明，
 * 只有屏幕中间一小块隐约有色——等于没做。这里改成线性渐变、首尾都有色，
 * 浅色使页面底比纯白暗 10~12%（暗档 14~16%），以肉眼可辨且不压过白卡片为准。
 *
 * 返回 null 表示「背景莫奈化」未开，调用方铺原厂纹理、零改动。
 */
@Composable
internal fun rememberMonetBlankTint(pageDeep: Boolean = false): Brush? {
    if (!monetBackgroundEnabled() && !perElementActive()) return null
    val globalDeep by MonetStore.deepBackgroundEnabled.collectAsState()
    val deep = pageDeep || globalDeep
    val context = LocalContext.current
    val dark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val palette = activePalette()
    return remember(palette, deep, dark) {
        if (dark) {
            // 深色：在 #25282D 上叠一层主题色雾（提亮），暗档提亮更少 → 相对更暗。
            // 深色下眼睛对 3~5% 的亮度变化很敏感，不再加码。
            val fog = blend(palette.primaryContainer, palette.onSurface, 0.30f)
            val a = if (deep) 0.05f else 0.08f
            Brush.linearGradient(
                listOf(
                    fog.copy(alpha = a),
                    fog.copy(alpha = a * 0.88f),
                    fog.copy(alpha = a * 0.74f),
                )
            )
        } else {
            // 浅色：主题色相 + 压深。原底 #F2F2F2 与纯白只差 5%，必须明确再加一档。
            val shade = blend(palette.primaryContainer, palette.ink, 0.42f)
            val a = if (deep) 0.20f else 0.12f
            Brush.linearGradient(
                listOf(
                    shade.copy(alpha = a),
                    shade.copy(alpha = a * 0.86f),
                    shade.copy(alpha = a * 0.72f),
                )
            )
        }
    }
}

internal const val MonetPlaybackGlowRadius = 1400f
/** 列表页多表面各自绘制：放大半径让排序栏/列表/播放栏落在同一色段，保证连贯。 */
internal const val MonetContentGlowRadius = 2600f

/**
 * [v11] 白色内容背景：背景莫奈化开启时铺白底 + 与大播放页同色的莫奈渐变；关闭时只铺原白色。
 * 供排序栏白条 / 歌曲列表行 / 小播放栏等复用，色相与大播放页一致、相邻不割裂。
 * 归「背景莫奈化」子开关。
 */
@Composable
internal fun Modifier.monetContentBackground(base: Color): Modifier {
    val tint = rememberMonetContentTint() ?: return this.background(base)
    return this.drawBehind {
        drawRect(base)
        drawRect(monetContentBrush(tint, MonetContentGlowRadius))
    }
}

/**
 * [1.4.23·莫奈取色] 大播放页「朦胧晶莹剔透」背景晕染。
 * 只负责在**已有的背景之上**叠一层莫奈三色低透明度柔和渐变，不改动背景本身、
 * 不改动任何子布局的尺寸/位置。归「莫奈取色」主开关，关闭时不绘制任何东西。
 */
@Composable
fun MonetPlaybackGlow(modifier: Modifier = Modifier) {
    val tint = rememberMonetPlaybackGlowTint() ?: return
    Box(modifier.background(monetContentBrush(tint, MonetPlaybackGlowRadius)))
}
