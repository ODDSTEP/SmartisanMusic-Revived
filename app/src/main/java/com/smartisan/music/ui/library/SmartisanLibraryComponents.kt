package com.smartisan.music.ui.library

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.components.smartisanPressedTextColor
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.artwork.AlbumArtworkLoader
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.theme.monetSurface
import com.smartisan.music.ui.theme.monetContentBackground
import com.smartisan.music.ui.theme.rememberMonetBlankTint
import com.smartisan.music.ui.theme.rememberMonetContentTint
import com.smartisan.music.ui.theme.monetContentBrush
import com.smartisan.music.ui.theme.MonetContentGlowRadius
import com.smartisan.music.ui.theme.monetOutlineVariant
import com.smartisan.music.ui.theme.monetOnSurfaceVariant
import com.smartisan.music.ui.theme.themedInk
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.components.smartisanTextSize
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.ui.theme.themedListRowBackground
import com.smartisan.music.ui.components.smartisanMarquee

@Composable
internal fun rememberAlbumArtworkLoader(): AlbumArtworkLoader {
    val context = LocalContext.current
    val loader =
        remember(context.applicationContext) { AlbumArtworkLoader(context.applicationContext) }
    DisposableEffect(loader) { onDispose { loader.clear() } }
    return loader
}

@Composable
internal fun SmartisanMediaArtwork(
    mediaItem: MediaItem?,
    sizePx: Int,
    @DrawableRes fallbackRes: Int,
    modifier: Modifier = Modifier,
    loader: AlbumArtworkLoader = rememberAlbumArtworkLoader(),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by
        key(loader, mediaItem, sizePx) {
            produceState(mediaItem?.let(loader::cached)) {
                value = mediaItem?.let { loader.load(it, sizePx.coerceAtLeast(1)) }
            }
        }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Image(
            rememberSmartisanDrawablePainter(fallbackRes),
            null,
            modifier,
            contentScale = contentScale,
        )
    }
}

@Composable
internal fun SmartisanAlbumArtwork(
    album: AlbumSummary?,
    sizePx: Int,
    @DrawableRes fallbackRes: Int,
    modifier: Modifier = Modifier,
    loader: AlbumArtworkLoader = rememberAlbumArtworkLoader(),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by
        key(loader, album, sizePx) {
            produceState(album?.let(loader::cached)) {
                value = album?.let { loader.load(it, sizePx.coerceAtLeast(1)) }
            }
        }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), null, modifier, contentScale = contentScale)
    } else {
        Image(
            rememberSmartisanDrawablePainter(fallbackRes),
            null,
            modifier,
            contentScale = contentScale,
        )
    }
}

/**
 * [1.4.29·R122/R123] 库页背景：原厂竖纹纹理 + 莫奈色雾。
 *
 * 用户拍板：纹理图保留，不换纯色渐变；「空白区」就是被纹理覆盖的区域。
 * 背景莫奈化关闭时 [rememberMonetBlankTint] 返回 null，这里只铺原厂纹理，零改动。
 * [pageDeep] = true 用于更多页 / 设置页的空白区（暗一档）。
 */
@Composable
internal fun Modifier.libraryTexture(
    @DrawableRes resource: Int = R.drawable.account_background,
    pageDeep: Boolean = false,
): Modifier {
    val painter = rememberSmartisanDrawablePainter(resource)
    val blankTint = rememberMonetBlankTint(pageDeep)
    if (blankTint == null) return this.smartisanPainterBackground(painter)
    return this.drawBehind {
        with(painter) { draw(size = size) }
        drawRect(blankTint)
    }
}

/**
 * [1.4.29·R123] 卡片图 + 莫奈淡渐变（弹窗 / 浮层用）。
 *
 * 与列表行同一套 [rememberMonetContentTint]：保留原图的白底、圆角与阴影，
 * 只在其上叠一层低透明度主题色雾。背景莫奈化关闭时只铺原图，零改动。
 * 用在 bottom_sheet_title_bar_bg / menu_dialog_background 这类卡片图上，
 * 避免「标题栏还是死白、卡片主体已经变色」的两截色。
 */
@Composable
internal fun Modifier.cardTextureMonet(@DrawableRes resource: Int): Modifier {
    val painter = rememberSmartisanDrawablePainter(resource)
    val tint = rememberMonetContentTint()
    if (tint == null) return this.smartisanPainterBackground(painter)
    return this.drawBehind {
        with(painter) { draw(size = size) }
        drawRect(monetContentBrush(tint, MonetContentGlowRadius))
    }
}

@Composable
internal fun LibraryDivider(modifier: Modifier = Modifier) {
    // [v16·谷歌 M3] 分割细线取 outlineVariant：莫奈取色开启时是带主题色相的中性灰，
    // 关闭时回退官方 listview_divider_color，零改动。
    val monetDivider = monetOutlineVariant()
    Spacer(
        modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.listview_dividerHeight))
            .background(monetDivider ?: colorResource(R.color.listview_divider_color))
    )
}

@Composable
internal fun LibraryFooter(@PluralsRes resource: Int, count: Int, modifier: Modifier = Modifier) {
    if (count < 8) return
    BasicText(
        pluralStringResource(resource, count, count),
        modifier
            .fillMaxWidth()
            // [1.4.29·R123] 去掉纯白条：背景透明，让页面竖纹+莫奈色雾透出来。
            // 旧实现铺 page_background 纯色，在莫奈渐变页脚形成一条死白，割裂明显。
            .padding(vertical = dimensionResource(R.dimen.footer_padding)),
        style =
            TextStyle(
                color = colorResource(R.color.footer_text_color),
                fontSize = smartisanTextSize(R.dimen.footer_text_size),
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
    )
}

@Composable
internal fun LibraryBlank(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().libraryTexture(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            rememberSmartisanDrawablePainter(icon),
            null,
            Modifier.graphicsLayer { alpha = .42f }.padding(bottom = 16.dp),
        )
        BasicText(
            title,
            style =
                TextStyle(
                    color = colorResource(R.color.text_disabled_gray),
                    fontSize = 25.sp,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
        )
        if (subtitle.isNotBlank())
            BasicText(
                subtitle,
                Modifier.padding(top = 10.dp),
                style =
                    TextStyle(
                        color = colorResource(R.color.text_disabled_gray),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
            )
    }
}

@Composable
internal fun LibrarySummaryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showArrow: Boolean = false,
    primarySizeRes: Int = R.dimen.text_size_medium,
    secondarySizeRes: Int = R.dimen.text_size_small,
    lineSpacing: androidx.compose.ui.unit.Dp =
        dimensionResource(R.dimen.listview_items_margin_top1),
    titleColor: Color = colorResource(R.color.setting_item_text_color),
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.(pressed: Boolean) -> Unit)? = null,
    textInset: androidx.compose.ui.unit.Dp = dimensionResource(R.dimen.listview_items_margin_left),
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(dimensionResource(R.dimen.listview_item_height))
            .themedListRowBackground(pressed = pressed, focused = focused, activated = selected)
            .clickable(interaction, null, role = Role.Button, onClick = smartisanClick(onClick)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingContent?.invoke(this)
        Column(
            Modifier.weight(1f)
                .padding(
                    start = textInset,
                    end = dimensionResource(R.dimen.listview_items_padding_left_left1),
                )
        ) {
            BasicText(
                title,
                // [1.4.25·R44] 列表主标题：超宽滚动显示完整，不省略号、不硬切。
                Modifier.fillMaxWidth().smartisanMarquee(),
                style =
                    TextStyle(
                        color = smartisanPressedTextColor(titleColor, pressed),
                        fontSize = smartisanTextSize(primarySizeRes),
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            // 副标题灰色小字：[v16·谷歌 M3] 莫奈取色开启时用 onSurfaceVariant（次级文字
            // 标准色，带轻微主题色相），关闭回退官方 list_text_color_small（回退 v13 文字莫奈化）。
            BasicText(
                subtitle,
                Modifier.padding(top = lineSpacing),
                style =
                    TextStyle(
                        color =
                            smartisanPressedTextColor(
                                monetOnSurfaceVariant()
                                    ?: colorResource(R.color.list_text_color_small),
                                pressed,
                            ),
                        fontSize = smartisanTextSize(secondarySizeRes),
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        trailingContent?.invoke(this, pressed)
        if (showArrow)
            Image(
                rememberSmartisanDrawablePainter(
                    R.drawable.selector_list_content_item_arrow,
                    pressed = pressed,
                    focused = focused,
                ),
                null,
                Modifier.padding(end = 6.dp),
            )
    }
}

/**
 * 1.2.33：列表页顶部「排序/播放操作栏」的**统一容器**——白色卡面 + 底部 14dp
 * 投影落在下方列表之上，与歌曲页原有的 SongsTopCard 逐字同构。
 *
 * 之前这套结构只是 SongsPage.kt 里的私有函数，专辑页（AlbumPage）、收藏页等
 * 都把 LibraryPlayActions 直接丢进 Column，于是「同样两个播放/随机按钮，歌曲页
 * 有分界、专辑页没有」。现在抽成公共组件，各页面共用同一份实现，杜绝再出现
 * 「同款按钮栏，有的页面有分界、有的没有」。
 */
@Composable
internal fun LibraryTopCard(content: @Composable ColumnScope.() -> Unit) {
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
    // 1.4.0 U4：沿用顶栏同款 title_bar_shadow 标准件（不改 PNG、不自造新阴影）。
    // 旧实现把阴影用 align(BottomCenter)+offset(y=shadowHeight) 挂在 Box 底部：
    // 排序栏 AnimatedVisibility(shrinkVertically) 收起后内容高度塌到 0，Box 被阴影
    // 自身撑到 shadowHeight，阴影被推到列表里，[0,shadowHeight] 露出 page_background
    // 灰底 —— 这就是用户图1 的灰带。现在阴影严格画在「内容实测底边」之下、
    // 不占布局高度；收起态(高度 0)不画这一层，由顶栏自带的同款阴影接管，
    // 展开/收起两种状态共用同一套阴影，方向一致、无空隙。
    val shadowPainter = rememberSmartisanDrawablePainter(R.drawable.title_bar_shadow)
    var contentHeightPx by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .drawBehind {
                if (contentHeightPx > 0) {
                    val h = shadowHeight.toPx()
                    translate(top = contentHeightPx.toFloat()) {
                        with(shadowPainter) { draw(size = Size(size.width, h)) }
                    }
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // [v11] 排序栏白条改为大播放页同款莫奈渐变背景；关闭莫奈时仍是 surface_card。
                .monetContentBackground(colorResource(R.color.surface_card))
                .onSizeChanged { contentHeightPx = it.height },
            content = content,
        )
    }
}

@Composable
internal fun LibraryPlayActions(
    enabled: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = colorResource(R.color.page_background),
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            // [v11] 播放/随机按钮所在白条改用大播放页同款莫奈渐变，与排序栏连贯。
            .monetContentBackground(containerColor)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryRedAction(
            stringResource(R.string.cd_play_all),
            R.drawable.btn_icon_play_selector,
            enabled,
            onPlay,
            Modifier.weight(1f),
        )
        LibraryRedAction(
            stringResource(R.string.cd_shuffle),
            R.drawable.btn_icon_shuffle_selector,
            enabled,
            onShuffle,
            Modifier.weight(1f),
        )
    }
    LibraryDivider()
}

@Composable
private fun LibraryRedAction(
    title: String,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    // [1.4.23·莫奈取色] 这两个「播放列表 / 随机播放」粉色按钮也参与主题/莫奈染色：
    // 背景换 palette.surface（按下 surfaceSoft），文字换 palette.ink（按下 onSurfaceTint）。
    // [1.4.25·R51] 口径收紧：strictAccent=true —— 只有「开莫奈 / M3 固化 / 逐元素取色」
    // 才染色；默认彩色与传统 8 色主题（哪怕没开莫奈）一律保持原 PNG 红底白字。
    // 之前传统主题会拿到 currentBasePalette() 被染成主题灰粉底淡字，用户判定为不该变。
    val palette = currentThemePalette(strictAccent = true)
    val themedBg =
        if (palette != null) {
            Modifier.drawBehind {
                drawRoundRect(
                    // [v16] accent 容器：surface 已近中性化，按钮背景改用 primaryContainer 保持色相。
                    color =
                        if (pressed) palette.primaryContainerSoft else palette.primaryContainer,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
        } else {
            Modifier.smartisanPainterBackground(
                rememberSmartisanDrawablePainter(
                    R.drawable.btn_red_bg_selector,
                    enabled = enabled,
                    pressed = pressed,
                )
            )
        }
    // [v12·播放列表/随机按钮] 文字/图标 = palette.onSurfaceTint（深底淡字）。
    // palette 为 null（默认彩色且未开莫奈）时回退原红底白字零改动。
    val themedTextColor =
        if (palette != null) {
            palette.onSurfaceTint
        } else {
            smartisanStateColor(
                R.color.red_btn_text_color_selector,
                enabled = enabled,
                pressed = pressed,
            )
        }
    Row(
        modifier
            .height(30.dp)
            .then(themedBg)
            .clickable(
                interaction,
                null,
                enabled = enabled,
                role = Role.Button,
                onClick = smartisanClick(onClick),
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            rememberSmartisanDrawablePainter(icon, enabled = enabled, pressed = pressed),
            null,
            Modifier.padding(end = 10.dp),
            // [v10] 主题/莫奈开启时把左侧图标染成与文字完全相同的淡色（M3 icon/label 同色），
            // 解决「背景文字都变了、唯独图标恒红」。默认彩色(palette=null)时 colorFilter=null，
            // 图标保持原 PNG 红色，零改动。
            colorFilter = if (palette != null) ColorFilter.tint(themedTextColor) else null,
        )
        BasicText(
            title,
            Modifier.padding(bottom = .67.dp),
            style =
                TextStyle(
                    color = themedTextColor,
                    fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
        )
    }
}

@Composable
internal fun LibraryIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Image(
        rememberSmartisanDrawablePainter(
            icon,
            enabled = enabled,
            pressed = pressed,
            focused = focused,
        ),
        description,
        modifier.clickable(
            interaction,
            null,
            enabled = enabled,
            role = Role.Button,
            onClick = smartisanClick(onClick),
        ),
    )
}

/**
 * [回退到 v12] 删除 v14 的 on-color 前景计算函数（不再使用，恢复 palette.onSurfaceTint）。
 */
