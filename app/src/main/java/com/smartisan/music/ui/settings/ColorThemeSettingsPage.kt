package com.smartisan.music.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.components.smartisanMarquee
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.settings.MonetStore
import com.smartisan.music.ui.theme.ColorTheme
import com.smartisan.music.ui.theme.LocalColorTheme
import com.smartisan.music.data.settings.ColorThemeStore
import com.smartisan.music.ui.components.SmartisanSwitch
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.rememberSmartisanSwitchState
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.components.smartisanShadowBackground
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.components.smartisanTextSize
import com.smartisan.music.ui.theme.MonetColors
import kotlinx.coroutines.launch

/**
 * 1.3.7 色彩主题选择页。默认「彩色」= 保持现状零改动；其余 8 个传统色只把
 * 界面里能稳定染的「点缀位」统一换成主题色，背景/文字/分隔线/PNG 图片一律不动。
 *
 * [1.4.23·莫奈取色] 列表底部新增独立开关「莫奈取色」：打开后按当前色彩主题的原色做提纯
 * （[1.4.28] 早期注释写的「系统壁纸」与实现不符，实际不读壁纸）
 * 生成的动态调色板覆盖点缀位，作用于文字/图标/顶栏/底栏/阴影/大播放页背景；与所选主题
 * 叠加而非替换，关闭后完全回退到上面的传统主题逻辑。
 */
@Composable
internal fun ColorThemeSettingsPage(
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentTheme by ColorThemeStore.theme.collectAsState()
    val monetEnabled by MonetStore.enabled.collectAsState()
    val backgroundEnabled by MonetStore.backgroundEnabled.collectAsState()
    val deepBackgroundEnabled by MonetStore.deepBackgroundEnabled.collectAsState()
    val separatorEnabled by MonetStore.separatorEnabled.collectAsState()
    val playbackTransition by MonetStore.playbackTransition.collectAsState()
    val perElementEnabled by MonetStore.perElementEnabled.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize().libraryTexture(pageDeep = true)) {
        if (active) {
            SmartisanTitleBar(
                title = stringResource(R.string.color_theme),
                navigationIcon =
                    SmartisanTitleBarAction(
                        R.drawable.standard_icon_back_selector,
                        stringResource(R.string.back),
                        onClose,
                    ),
            )
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .libraryTexture(pageDeep = true)
                        .verticalScroll(scrollState)
                        .padding(
                            horizontal = dimensionResource(R.dimen.list_item_left_right_margin),
                            vertical = dimensionResource(R.dimen.list_item_vertical_gap),
                        )
            ) {
                // 主题单选列表
                Column(Modifier.selectableGroup()) {
                    ColorTheme.entries.forEachIndexed { index, theme ->
                        ColorThemeChoiceRow(
                            theme = theme,
                            selected = theme == currentTheme,
                            isFirst = index == 0,
                            isLast = index == ColorTheme.entries.lastIndex,
                            onClick = { ColorThemeStore.set(context, theme) },
                        )
                    }
                }
                // [v21] M3 三色开关已删除：M3 样式改为 4 个独立固化主题（见上方主题列表末尾）。
                // 选中 M3 固化主题即得到原 M3 效果，无需开关控制。
                // [v16·莫奈开关] 莫奈取色（主）+ 背景莫奈化 / 分隔栏莫奈化（子，依赖主）
                // + 播放栏渐变过渡（独立，不依赖主）。子开关禁用态显示小字说明。
                // [1.4.24·R23] M3 固化主题下整套配色由种子 HCT 决定，莫奈取色/背景/分隔栏
                // 三开关完全不参与运算（MonetSurface 里 m3Active() 直接短路）。之前开关仍可点，
                // 用户点完发现界面没任何变化，以为功能坏了。现在三项统一置灰，并在开关左侧
                // 用小字写明原因（不加高行）。
                val isM3FixedTheme = LocalColorTheme.current.isM3Fixed
                val monetDisabledHint =
                    if (isM3FixedTheme) R.string.monet_m3_fixed_hint else null
                MonetSwitchRow(
                    labelRes = R.string.monet_color,
                    checked = monetEnabled,
                    isLast = false,
                    enabled = !isM3FixedTheme,
                    hintRes = monetDisabledHint,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setEnabled(context, enabled) }
                        if (enabled) MonetColors.refresh(context)
                    },
                )
                MonetSwitchRow(
                    labelRes = R.string.monet_background,
                    checked = backgroundEnabled,
                    isLast = false,
                    enabled = !isM3FixedTheme && monetEnabled,
                    hintRes = monetDisabledHint ?: R.string.monet_sub_switch_hint,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setBackground(context, enabled) }
                        if (enabled) MonetColors.refresh(context)
                    },
                )
                // [1.4.29·R125] 「背景莫奈化」的子选项：全局所有页面的空白区统一用暗档。
                // 默认关 = 只有更多页与设置页暗一档；开启 = 全 App 统一暗档。
                MonetSwitchRow(
                    labelRes = R.string.monet_deep_background,
                    checked = deepBackgroundEnabled,
                    isLast = false,
                    enabled = !isM3FixedTheme && monetEnabled && backgroundEnabled,
                    hintRes = monetDisabledHint ?: R.string.monet_deep_background_hint,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setDeepBackground(context, enabled) }
                    },
                )
                MonetSwitchRow(
                    labelRes = R.string.monet_separator,
                    checked = separatorEnabled,
                    isLast = false,
                    // [1.4.26·R58] 删掉「独立控制」这层：分隔栏莫奈化自己就是独立开关，
                    // 开就生效、关就回原厂灰，不再看莫奈主开关，也不再有第二个开关。
                    enabled = !isM3FixedTheme,
                    hintRes = monetDisabledHint ?: R.string.monet_sub_switch_hint,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setSeparator(context, enabled) }
                        if (enabled) MonetColors.refresh(context)
                    },
                )
                // [1.4.28] 配色方案只保留「同色深浅」，删掉「同色+细线」「淡渐变」两项，
                // 因此不再需要方案选择器；字母颜色改为始终按对比度自动选择，开关一并删除。
                MonetSwitchRow(
                    labelRes = R.string.monet_playback_transition,
                    checked = playbackTransition,
                    // [1.4.25·R43] 改为 mid，让下面的「逐元素取色」成为整张卡的唯一 bottom 收口，
                    // 避免两张 bottom 卡之间不衔接。
                    isLast = false,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setPlaybackTransition(context, enabled) }
                    },
                )
                // [v19] 分隔栏三档已移除：用户实测浅色三档肉眼无区别，固定走最深档。
                // 逐元素取色实验开关（独立引擎，与主题挂钩）。
                MonetSwitchRow(
                    labelRes = R.string.per_element_color,
                    checked = perElementEnabled,
                    isLast = true,
                    hintRes = R.string.per_element_color_hint,
                    onCheckedChange = { enabled ->
                        scope.launch { MonetStore.setPerElementEnabled(context, enabled) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorThemeChoiceRow(
    theme: ColorTheme,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (background, shadow) =
        when {
            // [1.4.25·R43] 首行用 top 圆角（卡片顶端与其它页面一致）；
            // 其余行用 mid 衔接，末行后面还有莫奈开关，不再单独 bottom 收口。
            isFirst -> R.drawable.group_list_item_bg_top to R.drawable.list_content_item_top_shadow
            else -> R.drawable.group_list_item_bg_mid to R.drawable.list_content_item_middle_shadow
        }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.list_item_min_height))
                .smartisanShadowBackground(background, shadow, pressed = pressed, focused = focused)
                .selectable(
                    selected = selected,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = smartisanClick(onClick),
                )
    ) {
        BasicText(
            text = stringResource(theme.labelRes),
            modifier =
                Modifier.align(AbsoluteAlignment.CenterLeft)
                    .fillMaxWidth()
                    .absolutePadding(
                        left = ThemeChoiceTextStart,
                        right = ColorThemeChipSize + ColorThemeChipEnd + ColorThemeTextEnd,
                    ),
            style =
                TextStyle(
                    color =
                        smartisanStateColor(
                            R.color.setting_item_text_colorlist,
                            pressed = pressed,
                            focused = focused,
                        ),
                    fontSize = smartisanTextSize(R.dimen.primary_text_size),
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        // 色点：彩色用多色渐变示意「五颜六色」；9 个 M3 主题用各自的 primaryContainer
        // 代表色（R15 已拍板）；传统主题仍用对应传统色。
        val m3Dot = m3DotColors(theme)
        Box(
            modifier =
                Modifier.align(AbsoluteAlignment.CenterRight)
                    .absolutePadding(right = ColorThemeChipEnd)
                    .size(ColorThemeChipSize)
                    .background(
                        brush =
                            if (theme.isColorful) ColorfulChipBrush
                            else if (m3Dot != null) Brush.linearGradient(listOf(m3Dot.first, m3Dot.first))
                            else Brush.linearGradient(listOf(theme.accent!!, theme.accent!!)),
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                val checkColor =
                    if (theme.isColorful) Color(0xFF2B2B2B)
                    else if (m3Dot != null) m3Dot.second
                    else theme.onAccent
                Canvas(Modifier.size(ColorThemeCheckSize)) {
                    val w = size.width
                    val h = size.height
                    val path =
                        Path().apply {
                            moveTo(w * 0.18f, h * 0.52f)
                            lineTo(w * 0.42f, h * 0.74f)
                            lineTo(w * 0.84f, h * 0.26f)
                        }
                    drawPath(
                        path,
                        checkColor,
                        style =
                            Stroke(
                                width = 2.6.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * [v24·R15] 9 个 M3 固化主题的列表色点取代表色（浅色档 primaryContainer），
 * 选中勾用 onPrimaryContainer（对比度实测全部 ≥7.2:1，淡色圆点上清晰可见）。
 * 返回 (圆点色, 勾色)；非 M3 主题返回 null 走原来的 theme.accent 逻辑。
 * 色值来自 v24 规格第 5 节官方 fromSeed 实算，见 docs/v24-M3主题扩充至全主题规格。
 */
private fun m3DotColors(theme: ColorTheme): Pair<Color, Color>? = when (theme) {
    ColorTheme.M3HatobaMurasaki -> Color(0xFFF1DAFF) to Color(0xFF573A70)
    ColorTheme.M3Ochikuri -> Color(0xFFFFDDB8) to Color(0xFF653E00)
    ColorTheme.M3YanagiBaiChiku -> Color(0xFFE0E995) to Color(0xFF434B05)
    ColorTheme.M3Suou -> Color(0xFFFFDAD6) to Color(0xFF73332F)
    ColorTheme.M3Colorful -> Color(0xFFDAE2FF) to Color(0xFF304578)
    ColorTheme.M3EnshuNezumi -> Color(0xFFFFDDB5) to Color(0xFF633F00)
    ColorTheme.M3Sekichiku -> Color(0xFFFFDAD6) to Color(0xFF73332E)
    ColorTheme.M3Karegusa -> Color(0xFFFFDEA4) to Color(0xFF5D4200)
    ColorTheme.M3SabiSeiji -> Color(0xFFA3F2D8) to Color(0xFF005140)
    else -> null
}

/**
 * [1.4.25·R58] 选项行（点按循环切换），只用于「分隔栏配色方案」三选一。
 * 右侧显示当前方案名 + 箭头，和设置页其它 choice 行观感一致。
 */
@Composable
private fun MonetChoiceRow(
    labelRes: Int,
    valueRes: Int,
    isLast: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (bgRes, shadowRes) =
        if (isLast)
            R.drawable.group_list_item_bg_bottom to R.drawable.list_content_item_bottom_shadow
        else R.drawable.group_list_item_bg_mid to R.drawable.list_content_item_middle_shadow
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.list_item_min_height))
                .smartisanShadowBackground(bgRes, shadowRes, pressed = pressed, focused = focused)
                .clickable(
                    interaction,
                    null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = smartisanClick(onClick),
                )
                .absolutePadding(left = ThemeChoiceTextStart, right = SwitchEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(labelRes),
            modifier = Modifier.weight(1f),
            style =
                TextStyle(
                    color =
                        smartisanStateColor(
                            R.color.setting_item_text_colorlist,
                            pressed = pressed,
                            focused = focused,
                        ),
                    fontSize = smartisanTextSize(R.dimen.primary_text_size),
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        BasicText(
            text = stringResource(valueRes),
            Modifier.padding(end = 2.dp),
            style =
                TextStyle(
                    color =
                        if (enabled) {
                            smartisanStateColor(
                                R.color.blue_btn_text_color_selector,
                                pressed = pressed,
                                focused = focused,
                            )
                        } else {
                            colorResource(R.color.text_disabled_gray)
                        },
                    fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Image(
            rememberSmartisanDrawablePainter(
                R.drawable.selector_list_content_item_arrow,
                enabled = enabled,
                pressed = pressed,
                focused = focused,
            ),
            null,
            contentScale = ContentScale.Inside,
        )
    }
}

/** [回退到 v12 + 重做] 单个开关行，样式与主题列表一致；isLast 决定是否用底部圆角。 */
@Composable
private fun MonetSwitchRow(
    labelRes: Int,
    checked: Boolean,
    isLast: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    hintRes: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val switchState = rememberSmartisanSwitchState(checked, enabled, onCheckedChange)
    // [1.4.25·R44] 文字滚动总开关：开=超宽滚动（无省略号）；关=直接裁切（也无省略号）。
    val localContext = LocalContext.current
    val uiStore = remember(localContext) { com.smartisan.music.data.settings.UiSettingsStore(localContext) }
    val textMarquee by uiStore.textMarquee.collectAsState(initial = true)
    val (bgRes, shadowRes) =
        if (isLast)
            R.drawable.group_list_item_bg_bottom to R.drawable.list_content_item_bottom_shadow
        else R.drawable.group_list_item_bg_mid to R.drawable.list_content_item_middle_shadow
    // [1.4.24·R26 设计规范] 置灰原因（小字）必须放在**开关左边**，且**绝不加高行**。
    // 旧实现把提示塞在标题下方、行高 +20dp，导致同一张卡片里行高参差——用户明确点名。
    val showHint = hintRes != null && !enabled
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.list_item_min_height))
                .smartisanShadowBackground(bgRes, shadowRes, pressed = pressed, focused = focused)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = { switchState.toggle() },
                )
                .absolutePadding(
                    left = ThemeChoiceTextStart,
                    right = SwitchEndPadding,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = stringResource(labelRes),
            // [1.4.25·R44] 开=滚动（smartisanMarquee 全局统一参数）；关=原样不滚动。
            modifier = Modifier.weight(1f).smartisanMarquee(enabled = textMarquee),
            style =
                TextStyle(
                    color =
                        smartisanStateColor(
                            R.color.setting_item_text_colorlist,
                            pressed = pressed,
                            focused = focused,
                        ),
                    fontSize = smartisanTextSize(R.dimen.primary_text_size),
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            softWrap = false,
            // [1.4.25·R44] 用户铁律：不要省略号，超宽直接裁切（滚动开启时靠滚动显示完整）。
            overflow = TextOverflow.Clip,
        )
        if (showHint) {
            BasicText(
                text = stringResource(hintRes),
                modifier = Modifier.absolutePadding(left = 8.dp, right = 8.dp)
                    .smartisanMarquee(enabled = textMarquee),
                style =
                    TextStyle(
                        color = colorResource(R.color.text_disabled_gray),
                        fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        // [1.4.28] 第四参是 enabled，旧实现写死 true：M3 固定主题下三个莫奈开关
        // 实际上拨不动（switchState 内部拦了），但外观完全不置灰，用户判定"开关坏了"。
        SmartisanSwitch(checked, onCheckedChange, Modifier, enabled, switchState)
    }
}

/** 「彩色」主题的示意渐变：取当前界面里几个既有品牌点缀色。 */
private val ColorfulChipBrush =
    Brush.sweepGradient(
        listOf(
            Color(0xFFE64040),
            Color(0xFFEDD094),
            Color(0xFFB8D3CA),
            Color(0xFF8A7E94),
            Color(0xFF4A69B3),
            Color(0xFFE64040),
        )
    )

private val ThemeChoiceTextStart = 18.dp
private val ColorThemeTextEnd = 10.dp
private val ColorThemeChipEnd = 14.dp
private val ColorThemeChipSize = 26.dp
private val ColorThemeCheckSize = 18.dp
private val SwitchEndPadding = 14.dp

