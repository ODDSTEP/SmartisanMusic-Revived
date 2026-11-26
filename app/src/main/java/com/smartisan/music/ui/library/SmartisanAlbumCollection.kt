package com.smartisan.music.ui.library

import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.theme.themedAccent
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.songs.AlphabetQuickBar
import com.smartisan.music.ui.songs.SmartisanSwipeDeleteRow
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artwork.AlbumArtworkLoader
import com.smartisan.music.ui.components.SectionLetterHeader
import com.smartisan.music.ui.components.SectionLetterRow
import com.smartisan.music.ui.components.buildSectionLetterRows
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.headerIndices
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanCheckboxBounds
import com.smartisan.music.ui.components.smartisanCheckboxHit
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanSlideSelection
import com.smartisan.music.ui.components.smartisanVerticalScrollbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.smartisan.music.ui.components.smartisanMarquee

internal data class LibraryAlbumEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val album: AlbumSummary?,
    val songs: List<MediaItem>,
)

/** Album and artist pages share geometry and an interruptible layout-mode transition. */
@Composable
internal fun SmartisanAlbumCollection(
    entries: List<LibraryAlbumEntry>,
    active: Boolean,
    viewMode: AlbumViewMode,
    currentMediaId: String?,
    onClick: (LibraryAlbumEntry) -> Unit,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onSelectionChange: (String, Boolean) -> Unit = { _, _ -> },
    artistAlbums: Boolean = false,
    animateInitialAppearance: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    /** [1.4.30·R138] 被隐藏的专辑卡 id 集合（只影响专辑总览，不联动专辑里的歌）。 */
    hiddenIds: Set<String> = emptySet(),
    /** [1.4.30·R138] 点了行尾/封面角那颗眼睛后的回调：true=隐藏，false=恢复显示。 */
    onVisibilityChange: (String, Boolean) -> Unit = { _, _ -> },
    /** [1.4.33·验收第9点] 专辑列表右滑「删除」回调：删除整张专辑（含其歌曲），复用音乐壳的删除确认流程。 */
    onDeleteAlbum: (String) -> Unit = {},
    /** [1.4.33·隐藏场景推广] albumId -> 其后紧邻的连续隐藏专辑数 N，用于边界分界线叠层加粗。 */
    hiddenRunBelowById: Map<String, Int> = emptyMap(),
) {
    val loader = rememberAlbumArtworkLoader()
    val list = rememberLazyListState()
    val grid = rememberLazyGridState()
    // [1.4.33·验收第9点] 专辑列表右滑露出删除/隐藏图标：与歌曲页同一套 openKey 排他机制。
    var albumOpenKey by remember { mutableStateOf<String?>(null) }
    var albumSwipeActive by remember { mutableStateOf(false) }
    val entrance =
        remember(animateInitialAppearance) { AlbumCollectionEntrance(animateInitialAppearance) }
    val entryIds = remember(entries) { entries.map { it.id } }
    // 列表视图按首字母分组（拼音、'#' 置尾）；网格视图仍直接使用 entries。
    val sectionRows = remember(entries) { buildSectionLetterRows(entries) { it.title } }
    val sectionIndices = remember(sectionRows) { sectionRows.headerIndices() }
    LaunchedEffect(active, viewMode, entryIds, entrance) {
        if (entrance.phase == AlbumEntrancePhase.Complete || !active || entryIds.isEmpty())
            return@LaunchedEffect
        val keys = entryIds.toSet()
        val visibleFlow = snapshotFlow {
            if (viewMode == AlbumViewMode.Tile) {
                grid.layoutInfo.visibleItemsInfo
                    .map { it.key }
                    .filterIsInstance<String>()
                    .filter { it in keys }
            } else {
                list.layoutInfo.visibleItemsInfo
                    .map { it.key }
                    .filterIsInstance<String>()
                    // 列表视图的 item key 是 "album:${id}"（见下方 itemsIndexed 的 key），
                    // 必须去掉前缀才能与裸 entryIds 匹配；否则 visibleIds 永远为空，
                    // reveal() 永不触发，Waiting 态 alpha=0 → 专辑列表整片空白。
                    .map { it.removePrefix("album:") }
                    .filter { it in keys }
            }
        }
        // [v13·专辑空白兜底] 无论可见项收集因何种时序异常一直为空（如 key 变化、
        // 快速进出页面），最多等 600ms 就放弃入场动画、直接 finish 让所有条目可见，
        // 绝不让 Waiting 态 alpha=0 永久停在屏幕上。正常情况首帧即可拿到 visibleIds。
        val resolved = withTimeoutOrNull(600L) { visibleFlow.first { it.isNotEmpty() } }
        if (resolved != null) entrance.reveal(resolved, viewMode)
        else entrance.finish()
    }
    var displayedMode by remember { mutableStateOf(viewMode) }
    val modeTransition = updateTransition(displayedMode, label = "album layout")
    val checkboxBounds = remember { mutableMapOf<String, Rect>() }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // [字母列避让] 列表视图超过 30 项会显示右侧 38dp 字母栏（与歌曲页同宽），
    // 行尾眼睛必须往左让出它，否则会压在字母栏上。网格视图没有字母栏，inset=0。
    val density = LocalDensity.current
    val albumQuickBarEndInset =
        if (displayedMode == AlbumViewMode.List && entries.size > 30)
            with(density) {
                (38.dp.toPx() -
                    rememberSmartisanDrawablePainter(R.drawable.eye_icon_0001)
                        .intrinsicSize.width / 3
                )
                    .coerceAtLeast(0f)
                    .toDp()
            }
        else 0.dp
    LaunchedEffect(viewMode, active) {
        if (active && displayedMode != viewMode && entries.isNotEmpty()) entrance.finish()
        if (
            active &&
                displayedMode != viewMode &&
                modeTransition.currentState == modeTransition.targetState
        ) {
            val source =
                if (displayedMode == AlbumViewMode.List) {
                    list.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                } else {
                    grid.layoutInfo.visibleItemsInfo.firstOrNull()?.key
                }
            val fallback =
                if (displayedMode == AlbumViewMode.List) {
                    list.firstVisibleItemIndex
                } else grid.firstVisibleItemIndex
            val anchor =
                (if (displayedMode == AlbumViewMode.List)
                    sectionRows.indexOfFirst {
                        it is SectionLetterRow.Item && it.value.id == source
                    }
                else entries.indexOfFirst { it.id == source })
                    .takeIf { it >= 0 } ?: fallback
            val index = anchor.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            // Both layouts coexist during motion. Only position a fresh destination;
            // reversing in flight must preserve its already measured bounds.
            if (viewMode == AlbumViewMode.List) list.requestScrollToItem(index)
            else grid.requestScrollToItem(index)
        }
        displayedMode = viewMode
    }
    val sharedConfig =
        remember(modeTransition) {
            object : SharedTransitionScope.SharedContentConfig {
                override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
                    get() = modeTransition.targetState == AlbumViewMode.List
            }
        }

    SharedTransitionLayout(
        modifier.fillMaxSize().clipToBounds().libraryTexture()
    ) {
        if (!active) return@SharedTransitionLayout
        val sharedScope = this
        modeTransition.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (EnterTransition.None togetherWith ExitTransition.None).using(null)
            },
            contentAlignment = Alignment.TopStart,
        ) { layoutMode ->
            val visibilityScope = this
            val interactive = layoutMode == modeTransition.targetState && !modeTransition.isRunning
            val layoutModifier =
                Modifier.fillMaxSize()
                    .testTag("album-${layoutMode.name}")
                    .then(
                        if (layoutMode != modeTransition.targetState)
                            Modifier.clearAndSetSemantics {}
                        else Modifier
                    )
            val firstIndex by
                remember(layoutMode) {
                    derivedStateOf {
                        if (layoutMode == AlbumViewMode.List) list.firstVisibleItemIndex
                        else grid.firstVisibleItemIndex
                    }
                }
            @Composable
            fun coverMotion(id: String, index: Int): Modifier =
                with(sharedScope) {
                    Modifier.sharedBounds(
                        rememberSharedContentState(id, sharedConfig),
                        animatedVisibilityScope = visibilityScope,
                        boundsTransform = { _, _ ->
                            tween(150, delayMillis = (index - firstIndex).coerceIn(0, 20) * 10)
                        },
                        resizeMode =
                            SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillBounds),
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(150)),
                    )
                }
            if (layoutMode == AlbumViewMode.List) {
                LazyColumn(
                    state = list,
                    userScrollEnabled = interactive,
                    modifier =
                        layoutModifier
                            .onGloballyPositioned { listOrigin = it.positionInRoot() }
                            .smartisanVerticalScrollbar(list)
                            .smartisanSlideSelection(
                                enabled = editMode && interactive,
                                itemAt = { point ->
                                    list.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            point.y >= it.offset && point.y < it.offset + it.size
                                        }
                                        ?.index
                                },
                                keyAt = { (sectionRows.getOrNull(it) as? SectionLetterRow.Item)?.value?.id },
                                selectedKeys = selectedIds,
                                onSelectionChange = onSelectionChange,
                                canStart = { point ->
                                    val index =
                                        list.layoutInfo.visibleItemsInfo
                                            .firstOrNull {
                                                point.y >= it.offset &&
                                                    point.y < it.offset + it.size
                                            }
                                            ?.index
                                    val key =
                                        index?.let {
                                            (sectionRows.getOrNull(it) as? SectionLetterRow.Item)
                                                ?.value?.id
                                        }
                                    smartisanCheckboxHit(
                                        point + listOrigin,
                                        key?.let { checkboxBounds[it] },
                                        touchSlop,
                                    )
                                },
                                scrollBy = { list.scrollBy(it) },
                                edgeItemAt = { top ->
                                    list.layoutInfo.visibleItemsInfo
                                        .filter {
                                            it.index in sectionRows.indices &&
                                                sectionRows[it.index] is SectionLetterRow.Item
                                        }
                                        .let {
                                            if (top) it.firstOrNull()?.index
                                            else it.lastOrNull()?.index
                                        }
                                },
                            ),
                ) {
                    itemsIndexed(
                        sectionRows,
                        key = { _, row ->
                            when (row) {
                                is SectionLetterRow.Header -> "header:${row.letter}"
                                is SectionLetterRow.Item -> "album:${row.value.id}"
                            }
                        },
                    ) { index, row ->
                        when (row) {
                            is SectionLetterRow.Header -> SectionLetterHeader(row.letter)
                            is SectionLetterRow.Item -> {
                                val item = row.value
                                // [1.4.33·验收第9点] 专辑列表视图支持右滑露出删除/隐藏图标，
                                // 与歌曲页 SmartisanSwipeDeleteRow 完全同款：enabled 仅非编辑态，
                                // 编辑态仍走原有的行尾眼睛（LibraryAlbumListRow 内部）。
                                SmartisanSwipeDeleteRow(
                                    key = item.id,
                                    enabled = !editMode && interactive,
                                    openKey = albumOpenKey,
                                    onOpenChange = { albumOpenKey = it },
                                    onDelete = { close ->
                                        onDeleteAlbum(item.id)
                                        close()
                                    },
                                    hidden = item.id in hiddenIds,
                                    onHide = { close ->
                                        onVisibilityChange(item.id, true)
                                        close()
                                    },
                                    onSwipeActivity = { albumSwipeActive = it },
                                ) {
                                    LibraryAlbumListRow(
                                        item,
                                        loader,
                                        editMode,
                                        item.id in selectedIds,
                                        item.songs.any { it.mediaId == currentMediaId },
                                        artistAlbums,
                                        hidden = item.id in hiddenIds,
                                        onVisibilityChange = { onVisibilityChange(item.id, it) },
                                        contentEndInset = albumQuickBarEndInset,
                                        coverModifier = coverMotion(item.id, index),
                                        modifier = Modifier.albumCollectionEntrance(entrance, item.id),
                                        onCheckboxBounds = { bounds ->
                                            if (bounds == null) checkboxBounds.remove(item.id)
                                            else checkboxBounds[item.id] = bounds
                                        },
                                        onClick = {
                                            if (!interactive) return@LibraryAlbumListRow
                                            if (editMode)
                                                onSelectionChange(item.id, item.id !in selectedIds)
                                            else onClick(item)
                                        },
                                    )
                                    HiddenRunDivider(
                                        hiddenRunBelow = hiddenRunBelowById[item.id] ?: 0,
                                    )
                                }
                            }
                        }
                    }
                    if (footer != null) item(key = "album-footer") { footer() }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(integerResource(R.integer.gridview_columns)),
                    state = grid,
                    userScrollEnabled = interactive,
                    modifier =
                        layoutModifier
                            .smartisanVerticalScrollbar(grid)
                            .smartisanSlideSelection(
                                enabled = editMode && interactive,
                                itemAt = { point ->
                                    grid.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            point.x >= it.offset.x &&
                                                point.x < it.offset.x + it.size.width &&
                                                point.y >= it.offset.y &&
                                                point.y < it.offset.y + it.size.height
                                        }
                                        ?.index
                                },
                                keyAt = { entries.getOrNull(it)?.id },
                                selectedKeys = selectedIds,
                                onSelectionChange = onSelectionChange,
                                canStart = { true },
                                scrollBy = { grid.scrollBy(it) },
                                horizontalActivation = true,
                                edgeItemAt = { top ->
                                    grid.layoutInfo.visibleItemsInfo
                                        .filter { it.index in entries.indices }
                                        .let {
                                            if (top) it.firstOrNull()?.index
                                            else it.lastOrNull()?.index
                                        }
                                },
                                activationThreshold = { point ->
                                    grid.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            point.x >= it.offset.x &&
                                                point.x < it.offset.x + it.size.width &&
                                                point.y >= it.offset.y &&
                                                point.y < it.offset.y + it.size.height
                                        }
                                        ?.let { Offset(it.size.width / 2f, it.size.height / 2f) }
                                        ?: Offset.Zero
                                },
                            ),
                    contentPadding =
                        PaddingValues(
                            start = dimensionResource(R.dimen.gridview_margin),
                            end = dimensionResource(R.dimen.gridview_margin),
                            bottom =
                                if (artistAlbums) 0.dp
                                else dimensionResource(R.dimen.gridview_margin),
                        ),
                    horizontalArrangement =
                        Arrangement.spacedBy(dimensionResource(R.dimen.gridview_horizontalSpacing)),
                    verticalArrangement =
                        Arrangement.spacedBy(dimensionResource(R.dimen.gridview_verticalSpacing)),
                ) {
                    itemsIndexed(entries, key = { _, item -> item.id }) { index, item ->
                        LibraryAlbumTile(
                            item,
                            loader,
                            editMode,
                            item.id in selectedIds,
                            item.songs.any { it.mediaId == currentMediaId },
                            hidden = item.id in hiddenIds,
                            onVisibilityChange = { onVisibilityChange(item.id, it) },
                            coverModifier = coverMotion(item.id, index),
                            modifier = Modifier.albumCollectionEntrance(entrance, item.id),
                            onClick = {
                                if (!interactive) return@LibraryAlbumTile
                                if (editMode) onSelectionChange(item.id, item.id !in selectedIds)
                                else onClick(item)
                            },
                        )
                    }
                }
            }
        }
    
        // 1.3.4：专辑页复用歌曲页右侧字母栏，定位到首字母 Header 行
        if (displayedMode == AlbumViewMode.List && entries.size > 30) {
            AlphabetQuickBar(
                labelCount = entries.size,
                headerIndices = sectionIndices,
                listState = list,
                scope = androidx.compose.runtime.rememberCoroutineScope(),
            )
        }
}
}

@Composable
private fun LibraryAlbumListRow(
    item: LibraryAlbumEntry,
    loader: AlbumArtworkLoader,
    editMode: Boolean,
    selected: Boolean,
    playing: Boolean,
    artistAlbums: Boolean,
    onClick: () -> Unit,
    onCheckboxBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    hidden: Boolean = false,
    onVisibilityChange: (Boolean) -> Unit = {},
    /** 行尾眼睛的右侧避让量：列表视图有字母栏时 >0，避免压在字母栏上。 */
    contentEndInset: Dp = 0.dp,
) {
    val checkbox =
        rememberSmartisanDrawablePainter(R.drawable.check_box_selector, checked = selected)
    val checkboxWidth = with(LocalDensity.current) { checkbox.intrinsicSize.width.toDp() }
    val editWidth by
        animateDpAsState(
            if (editMode) checkboxWidth + dimensionResource(R.dimen.check_box_margin_left)
            else 0.dp,
            tween(200),
            label = "Album selection inset",
        )
    // [1.4.33·验收第19点] 置灰与眼睛同步渐变、背景不变（照抄文件夹页样板）。
    // pendingHidden 在点击瞬间置位、驱动封面+文字 300ms 渐变，与眼睛 16 帧眨眼同长；
    // 写库（hidden 翻转）在眨眼后到达，但 pendingHidden 优先，渐变不会被时序掐断。
    // 灰度只落在封面+文字，行背景不参与（图书馆纹理/卡片底不变），行尾眼睛保持原色。
    var pendingHidden by remember(item.id) { mutableStateOf<Boolean?>(null) }
    val eyeTime = remember(item.id) { Animatable(300f) }
    LaunchedEffect(hidden) { if (pendingHidden == hidden) pendingHidden = null }
    val displayedHidden = pendingHidden ?: hidden
    val contentAlpha by
        animateFloatAsState(
            if (editMode && displayedHidden) .3f else 1f,
            tween(300),
            label = "Album visibility",
        )
    // 进/出编辑：行尾眼睛与勾选框同一节奏（200ms），从右往左滑入。
    val editFraction by
        animateFloatAsState(if (editMode) 1f else 0f, tween(200), label = "Album eye slide")
    LibrarySummaryRow(
        item.title,
        item.subtitle,
        onClick,
        modifier = modifier.semantics { if (editMode) this.selected = selected },
        selected = editMode && selected,
        contentAlpha = contentAlpha,
        titleColor =
            if (playing) themedAccent(colorResource(R.color.playing_red))
            else colorResource(R.color.setting_item_text_color),
        leadingContent = {
            Box(Modifier.width(editWidth).clipToBounds(), contentAlignment = Alignment.CenterEnd) {
                if (editWidth > 0.dp) {
                    Image(
                        checkbox,
                        null,
                        Modifier.width(checkboxWidth).smartisanCheckboxBounds(onCheckboxBounds),
                    )
                }
            }
            LibraryAlbumCover(
                item,
                loader,
                tile = false,
                modifier =
                    Modifier.padding(start = dimensionResource(R.dimen.listview_items_margin_left))
                        .then(coverModifier)
                        .graphicsLayer { alpha = contentAlpha }
                        .size(dimensionResource(R.dimen.album_list_item_image_width)),
            )
        },
        textInset =
            dimensionResource(
                if (artistAlbums) R.dimen.listview_items_margin_left
                else R.dimen.common_padding_left
            ),
        trailingContent = {
            if (editFraction > 0f) {
                Box(
                    Modifier.padding(end = contentEndInset)
                        .graphicsLayer {
                            alpha = editFraction
                            translationX = size.width * (1 - editFraction)
                        }
                ) {
                    AlbumVisibilityEye(
                        displayedHidden = displayedHidden,
                        pendingHidden = pendingHidden,
                        eyeTime = eyeTime,
                        onPendingChange = { pendingHidden = it },
                        onVisibilityChange = onVisibilityChange,
                    )
                }
            }
        },
    )
}

/**
 * [1.4.30·R138] 专辑卡上的"眼睛"（列表行尾 / 网格封面角共用一份实现）。
 *
 * 与文件夹页、歌曲页右滑用的是同一组 16 帧眨眼素材和同一条 300ms 时间轴：
 * 睁眼(0001)=显示中，闭眼(0016)=已隐藏；点击后按眨眼方向播完才写库，
 * 列表里那张卡才变灰/滑走，做到"眼睛眨完 → 卡收起"。
 *
 * 刻意**不传 tint、不传 enabled**：顶栏版眼睛就是被这两样坑过——
 * tint 让图标颜色跟状态跳、enabled 翻转会让 painter 切图造成透明度硬跳，
 * 和逐帧眨眼叠在一起就是用户说的"闪一下"。点击闸门放在 onClick 里。
 */
@Composable
private fun AlbumVisibilityEye(
    displayedHidden: Boolean,
    pendingHidden: Boolean?,
    eyeTime: Animatable<Float, AnimationVector1D>,
    onPendingChange: (Boolean?) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val frame =
        if (pendingHidden != null && eyeTime.value < 160f) {
            val index = (eyeTime.value / 10).toInt().coerceIn(0, 15)
            AlbumEyeFrames[if (pendingHidden == true) index else 15 - index]
        } else if (displayedHidden) R.drawable.eye_icon_0016
        else R.drawable.eye_icon_0001
    LibraryIconButton(
        frame,
        stringResource(R.string.show_hide_albums),
        onClick = {
            if (pendingHidden == null) {
                val target = !displayedHidden
                // [1.4.33] pendingHidden 提升到父级：点击瞬间封面+文字开始渐变，与眨眼同步。
                onPendingChange(target)
                android.widget.Toast.makeText(
                        context.applicationContext,
                        context.getString(
                            if (target) R.string.hiden_dir else R.string.shown_dir,
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    .show()
                // 写库等 16 帧眨眼播完，pendingHidden 优先直到 hidden 追上，渐变不被掐断。
                scope.launch {
                    eyeTime.snapTo(0f)
                    eyeTime.animateTo(300f, tween(300, easing = LinearEasing))
                    onVisibilityChange(target)
                }
            }
        },
        modifier = modifier,
    )
}

/** 眨眼帧：与文件夹页/歌曲页共用同一组资源，顺序即"睁 → 闭"。 */
private val AlbumEyeFrames =
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

@Composable
private fun LibraryAlbumTile(
    item: LibraryAlbumEntry,
    loader: AlbumArtworkLoader,
    editMode: Boolean,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    hidden: Boolean = false,
    onVisibilityChange: (Boolean) -> Unit = {},
) {
    // [1.4.33·验收第19点] 网格卡置灰同样随眼睛 300ms 渐变（照抄文件夹页/列表行）。
    var pendingHidden by remember(item.id) { mutableStateOf<Boolean?>(null) }
    val eyeTime = remember(item.id) { Animatable(300f) }
    LaunchedEffect(hidden) { if (pendingHidden == hidden) pendingHidden = null }
    val displayedHidden = pendingHidden ?: hidden
    val contentAlpha by
        animateFloatAsState(
            if (editMode && displayedHidden) .3f else 1f,
            tween(300),
            label = "Album tile visibility",
        )
    val editFraction by
        animateFloatAsState(if (editMode) 1f else 0f, tween(200), label = "Album tile eye")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val tileSize = dimensionResource(R.dimen.gridview_item_ccontainer_height)
    Column(
        modifier
            .padding(top = dimensionResource(R.dimen.gridview_padding_top2))
            .clickable(
                interaction,
                null,
                role = if (editMode) Role.Checkbox else Role.Button,
                onClick = smartisanClick(onClick),
            )
            .semantics { if (editMode) this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(coverModifier.size(tileSize)) {
            LibraryAlbumCover(
                item,
                loader,
                tile = true,
                pressed = pressed,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha },
            )
            // [1.4.30·R138] 网格卡没有行尾，眼睛放封面右上角（参考包同位置习惯），
            // 编辑模式下随 editFraction 从右侧滑入；隐藏卡的眼睛保持满亮度，
            // 免得在 0.35 的灰卡上找不到"恢复显示"的入口。
            if (editFraction > 0f) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(2.dp)
                        .graphicsLayer {
                            alpha = editFraction
                            translationX = size.width * (1 - editFraction)
                        }
                ) {
                    AlbumVisibilityEye(
                        displayedHidden = displayedHidden,
                        pendingHidden = pendingHidden,
                        eyeTime = eyeTime,
                        onPendingChange = { pendingHidden = it },
                        onVisibilityChange = onVisibilityChange,
                    )
                }
            }
            if (editMode) {
                Image(
                    rememberSmartisanDrawablePainter(R.drawable.albums_selected_large_empty),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                if (selected)
                    Image(
                        rememberSmartisanDrawablePainter(R.drawable.albums_selected_large),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
            }
        }
        BasicText(
            item.title,
            // [1.4.25·R44] 网格里的专辑名超宽滚动，不省略。
            Modifier.width(tileSize).smartisanMarquee().graphicsLayer { alpha = contentAlpha },
            style =
                TextStyle(
                    color =
                        if (playing) themedAccent(colorResource(R.color.playing_red))
                        else colorResource(R.color.text_emphasis),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun LibraryAlbumCover(
    item: LibraryAlbumEntry,
    loader: AlbumArtworkLoader,
    tile: Boolean,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
) {
    val padding = if (tile) dimensionResource(R.dimen.gridview_padding) else 0.dp
    val size =
        dimensionResource(
            if (tile) R.dimen.gridview_item_ccontainer_height
            else R.dimen.album_list_item_image_width
        )
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    Box(modifier) {
        SmartisanAlbumArtwork(
            item.album,
            sizePx,
            if (item.album == null) R.drawable.noalbumcover_all_songs2
            else if (tile) R.drawable.noalbumcover_220 else R.drawable.noalbumcover_120,
            Modifier.fillMaxSize().padding(padding),
            loader,
        )
        val mask =
            if (tile) {
                if (item.album == null) R.drawable.no_mask_albumcover_tile_selector
                else R.drawable.mask_albumcover_tile_selector
            } else if (item.album == null) null else R.drawable.mask_albumcover_list
        if (mask != null)
            Image(
                rememberSmartisanDrawablePainter(mask, pressed = pressed),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
    }
}
