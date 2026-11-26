package com.smartisan.music.ui.more

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.theme.themedListRowBackground
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.components.smartisanTextSize
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.settings.SettingsSecondaryPage
import com.smartisan.music.ui.theme.monetSeparatorLetterColor
import com.smartisan.music.ui.theme.monetSurfaceContainer
import kotlin.math.roundToInt

@Composable
internal fun SmartisanMoreRootPage(
    active: Boolean,
    destinations: List<MusicDestination>,
    onDestinationSelected: (MusicDestination) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onShortcutClick: (SettingsSecondaryPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep scroll ownership outside the visible subtree: inactive tabs must have no
    // focus, touch targets or accessibility nodes, but return to the same position.
    val listState = rememberLazyListState()
    val entrance = remember { Animatable(0f) }
    val isPreview = LocalInspectionMode.current
    var entranceFinished by remember { mutableStateOf(isPreview) }
    val fadeDuration = integerResource(R.integer.item_flip)
    val staggerMillis = (fadeDuration * MoreRowEntranceStaggerFraction).toInt()
    var entranceDuration by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active || entranceFinished) return@LaunchedEffect
        if (entranceDuration == 0) {
            entranceDuration =
                fadeDuration + (destinations.size - 1).coerceAtLeast(0) * staggerMillis
        }
        // Hidden tabs do not spend their entrance animation. A quick tab switch
        // cancels this effect; returning resumes only the remaining visible time.
        entrance.animateTo(
            targetValue = entranceDuration.toFloat(),
            animationSpec =
                tween(
                    durationMillis =
                        (entranceDuration - entrance.value).roundToInt().coerceAtLeast(1),
                    easing = LinearEasing,
                ),
        )
        entranceFinished = true
    }

    if (!active) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    // [v19·更多页状态小字] 收集 4 个设置入口的当前状态值，与设置页同款主题色小字。
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorTheme by
        com.smartisan.music.data.settings.ColorThemeStore.theme.collectAsState()
    val engineMode by
        com.smartisan.music.data.settings.EngineModeStore.mode.collectAsState()
    val themeModeLabelRes =
        com.smartisan.music.data.settings.ThemeSettingsStore(context).currentMode().labelRes
    val playbackStore =
        remember { com.smartisan.music.data.settings.PlaybackSettingsStore(context) }
    val playbackSettings by playbackStore.settings.collectAsState(
        initial = com.smartisan.music.data.settings.PlaybackSettings()
    )
    val eqPresetLabel = audioFxPresetLabel(playbackSettings.audioFxPreset)

    Column(modifier = modifier.fillMaxSize().libraryTexture(pageDeep = true)) {
        MoreTitleBar(onSettingsClick, onSearchClick)
        LazyColumn(
            state = listState,
            modifier =
                Modifier.fillMaxWidth()
                    .weight(1f)
                    // [1.4.29·R123/R124] 更多页空白区：原厂竖纹 + 莫奈色雾，暗一档。
                    .libraryTexture(pageDeep = true),
        ) {
            // [1.4.26·更多页分组] 分组标题直接复用歌曲页首字母排序的灰色分隔栏
            // （smartlist_header_bg + 左内边距 + CenterStart），与全局列表语言统一。
            // 一组「设置」= 4 个从设置页扒来的入口；一组「页面」= 3 个被挤出底部导航的目的地。
            item(key = "section_settings") {
                MoreSectionHeader(stringResource(R.string.setting))
            }
            item(key = "settings_shortcuts") {
                SmartisanMoreShortcutRow(
                    iconRes = R.drawable.more_eq_selector,
                    label = stringResource(R.string.audio_fx),
                    value = eqPresetLabel,
                    onClick = { onShortcutClick(SettingsSecondaryPage.AudioFx) },
                )
                SmartisanMoreShortcutRow(
                    iconRes = R.drawable.more_engine_selector,
                    label = stringResource(R.string.playback_engine),
                    value = engineMode.label,
                    onClick = { onShortcutClick(SettingsSecondaryPage.Engine) },
                )
                SmartisanMoreShortcutRow(
                    iconRes = R.drawable.more_moon_selector,
                    label = stringResource(R.string.theme_settings),
                    value = stringResource(themeModeLabelRes),
                    onClick = { onShortcutClick(SettingsSecondaryPage.Theme) },
                )
                SmartisanMoreShortcutRow(
                    iconRes = R.drawable.more_palette_selector,
                    label = stringResource(R.string.color_theme),
                    value = stringResource(colorTheme.labelRes),
                    onClick = { onShortcutClick(SettingsSecondaryPage.ColorTheme) },
                )
            }
            item(key = "section_pages") {
                MoreSectionHeader(stringResource(R.string.more_section_pages))
            }
            itemsIndexed(destinations, key = { _, destination -> destination.route }) {
                index,
                destination ->
                SmartisanMoreDestinationRow(
                    destination = destination,
                    onClick = { onDestinationSelected(destination) },
                    modifier =
                        Modifier.graphicsLayer {
                            // list_anim_layout.xml: 20% stagger; fade_in.xml uses the
                            // platform AccelerateInterpolator's default quadratic curve.
                            val fraction =
                                if (entranceFinished) {
                                    1f
                                } else {
                                    ((entrance.value - index * staggerMillis) / fadeDuration)
                                        .coerceIn(0f, 1f)
                                }
                            alpha = fraction * fraction
                        },
                )
            }
        }
    }
}

@Composable
private fun SmartisanMoreDestinationRow(
    destination: MusicDestination,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectSmartisanPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val iconArea = dimensionResource(R.dimen.left_icon_area_width)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // more_item.xml / list_content_item_layout.xml. The old ListView uses
    // AbsListView.LayoutParams, so the style's horizontal margins were not applied.
    // The XML uses physical left/right placement even in RTL; only text follows locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = dimensionResource(R.dimen.list_item_min_height))
                    // [R143·2026-11-26] 更多页行背景原本是不透明 PNG（group_list_item_bg_mid），
                    // 把页面级 libraryTexture 的莫奈渐变整块盖住——这就是"开了全莫奈化，
                    // 这里还是死白"的原因。主题/莫奈下改用与歌曲页同一套 themedListRowBackground
                    // （白底 + 莫奈渐变 + 按下主题色）；默认彩色逐字保留原 PNG，零变化。
                    .then(
                        if (currentThemePalette() == null) {
                            Modifier.smartisanPainterBackground(
                                painter =
                                    rememberSmartisanDrawablePainter(
                                        R.drawable.group_list_item_bg_mid,
                                        pressed = pressed,
                                        focused = focused,
                                    )
                            )
                        } else {
                            // [1.4.32·验收图1] 补回原 PNG 自带的分隔线（主题态纯色 drawRect 没有）。
                            Modifier.themedListRowBackground(
                                pressed = pressed,
                                focused = focused,
                                drawDivider = true,
                            )
                        }
                    )
                    .clickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = smartisanClick(onClick),
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(iconArea),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter =
                        rememberSmartisanDrawablePainter(
                            destination.overflowIconRes,
                            pressed = pressed,
                            focused = focused,
                        ),
                    contentDescription = null,
                    modifier =
                        Modifier.sizeIn(
                            maxWidth = MoreIconMaximumSize,
                            maxHeight = MoreIconMaximumSize,
                        ),
                )
            }
            BasicText(
                text = stringResource(destination.labelRes),
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            vertical = dimensionResource(R.dimen.mid_container_top_bottom_padding)
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
                        textDirection =
                            if (isRtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
                        textAlign = if (isRtl) TextAlign.Right else TextAlign.Left,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Image(
                painter =
                    rememberSmartisanDrawablePainter(
                        R.drawable.selector_list_content_item_arrow,
                        pressed = pressed,
                        focused = focused,
                    ),
                contentDescription = null,
                modifier =
                    Modifier.padding(
                        start = dimensionResource(R.dimen.flexible_space),
                        end = dimensionResource(R.dimen.right_container_margin),
                    ),
            )
        }
    }
}

/** [1.4.23·更多页快捷入口] 复用 DestinationRow 样式的设置直达行。 */
@Composable
private fun SmartisanMoreShortcutRow(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectSmartisanPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val iconArea = dimensionResource(R.dimen.left_icon_area_width)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = dimensionResource(R.dimen.list_item_min_height))
                    .smartisanPainterBackground(
                        painter =
                            rememberSmartisanDrawablePainter(
                                R.drawable.group_list_item_bg_mid,
                                pressed = pressed,
                                focused = focused,
                            )
                    )
                    .clickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = smartisanClick(onClick),
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(iconArea),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter =
                        rememberSmartisanDrawablePainter(
                            iconRes,
                            pressed = pressed,
                            focused = focused,
                        ),
                    contentDescription = null,
                    modifier =
                        Modifier.sizeIn(
                            maxWidth = MoreIconMaximumSize,
                            maxHeight = MoreIconMaximumSize,
                        ),
                )
            }
            BasicText(
                text = label,
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            vertical = dimensionResource(R.dimen.mid_container_top_bottom_padding)
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
                        textDirection =
                            if (isRtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr,
                        textAlign = if (isRtl) TextAlign.Right else TextAlign.Left,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            // [v19·更多页状态小字] 照设置页 SettingsValueRow：主题色小字显示当前状态。
            if (!value.isNullOrEmpty()) {
                BasicText(
                    text = value,
                    modifier = Modifier.padding(end = 2.dp),
                    style =
                        TextStyle(
                            color =
                                smartisanStateColor(
                                    R.color.blue_btn_text_color_selector,
                                    pressed = pressed,
                                    focused = focused,
                                ),
                            fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Image(
                painter =
                    rememberSmartisanDrawablePainter(
                        R.drawable.selector_list_content_item_arrow,
                        pressed = pressed,
                        focused = focused,
                    ),
                contentDescription = null,
                modifier =
                    Modifier.padding(
                        // [v20·对齐返工] 原来 start = flexible_space(18dp)，把小字和箭头硬拉开；
                        // 改成设置页 SettingsValueRow 的样式：小字 2dp 后紧贴箭头，右侧留白用
                        // settings_row_accessory_margin_end(8dp)。
                        end = dimensionResource(R.dimen.settings_row_accessory_margin_end),
                    ),
            )
        }
    }
}

// list_content_left_image_view.xml and list_anim_layout.xml, respectively.
private val MoreIconMaximumSize = 36.dp
private const val MoreRowEntranceStaggerFraction = 0.2f
/** [v16] 更多页空白区暗沉晕染的渐变半径：略小于内容区半径，晕染更聚拢、层次更明显。 */

@Preview(name = "More · day", widthDp = 360, heightDp = 640)
@Preview(
    name = "More · night",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "More · large text", widthDp = 360, heightDp = 640, fontScale = 1.5f)
@Composable
private fun SmartisanMoreRootPagePreview() {
    SmartisanMoreRootPage(
        active = true,
        destinations =
            listOf(MusicDestination.Genre, MusicDestination.LovedSongs, MusicDestination.Folder),
        onDestinationSelected = {},
        onSettingsClick = {},
        onSearchClick = {},
        onShortcutClick = {},
    )
}

@Composable
internal fun MoreTitleBar(onSettingsClick: () -> Unit, onSearchClick: () -> Unit) {
    SmartisanTitleBar(
        title = stringResource(R.string.tab_more),
        navigationIcon =
            SmartisanTitleBarAction(
                iconRes = R.drawable.standard_icon_settings_selector,
                contentDescription = stringResource(R.string.setting),
                onClick = onSettingsClick,
            ),
        action =
            SmartisanTitleBarAction(
                iconRes = R.drawable.search_btn_selector,
                contentDescription = stringResource(R.string.tab_local_search),
                onClick = onSearchClick,
            ),
    )
}

/**
 * [1.4.26·更多页分组] 分组标题栏：直接复用歌曲页首字母排序的灰色分隔栏样式
 * ——整行 smartlist_header_bg 灰底 + 左内边距 + CenterStart 文字（上下自动居中），
 * 与 SongsPage 的 SongListRow.Header（ui/songs/SongsPage.kt:269）保持完全一致的视觉语言。
 */
@Composable
private fun MoreSectionHeader(title: String) {
    // [v16·分隔栏莫奈化] 灰栏铺不铺莫奈由独立开关控制：开启时用 surfaceContainer 容器色，
    // 关闭回退官方 smartlist_header_bg 灰底（零改动）。
    val containerColor = monetSurfaceContainer()
    // [1.4.29·R121] 字母/标题色按本处真实底色算对比度；未开分隔栏莫奈化时回退原厂文字色。
    val letterColor = monetSeparatorLetterColor(containerColor)
    Box(
        Modifier.fillMaxWidth()
            .height(26.dp)
            .then(
                if (containerColor != null) {
                    Modifier.background(containerColor)
                } else {
                    Modifier.smartisanPainterBackground(
                        rememberSmartisanDrawablePainter(R.drawable.smartlist_header_bg)
                    )
                }
            )
            .padding(
                start = dimensionResource(R.dimen.listview_items_margin_left)
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            title,
            style =
                TextStyle(
                    color = letterColor ?: colorResource(R.color.title_text_color),
                    fontSize = 13.5.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
        )
    }
}

/** [v19·更多页状态小字] 均衡器预设 → 当前状态文案（与设置页 labelText 同口径）。 */
@Composable
private fun audioFxPresetLabel(preset: com.smartisan.music.data.settings.AudioFxPreset): String =
    when (preset) {
        com.smartisan.music.data.settings.AudioFxPreset.Original -> stringResource(R.string.audio_fx_original)
        com.smartisan.music.data.settings.AudioFxPreset.Bass -> stringResource(R.string.audio_fx_bass)
        com.smartisan.music.data.settings.AudioFxPreset.Clear -> stringResource(R.string.audio_fx_clear)
        com.smartisan.music.data.settings.AudioFxPreset.Vocal -> stringResource(R.string.audio_fx_vocal)
        com.smartisan.music.data.settings.AudioFxPreset.Rock -> stringResource(R.string.audio_fx_rock)
        com.smartisan.music.data.settings.AudioFxPreset.Custom -> stringResource(R.string.audio_fx_custom)
        else -> preset.saltKey?.let { com.smartisan.music.data.settings.SaltEqPresets.chineseNames[it] }
            ?: preset.preferenceValue
    }
