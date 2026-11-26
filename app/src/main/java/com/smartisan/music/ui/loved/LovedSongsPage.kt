package com.smartisan.music.ui.loved

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.library.cardTextureMonet
import com.smartisan.music.ui.shell.ImmersiveScrollBus
import com.smartisan.music.ui.shell.ImmersiveLocateEffect
import com.smartisan.music.ui.shell.ImmersiveActionRowVisibility
import com.smartisan.music.ui.shell.ChromeSortBarVisibility
import com.smartisan.music.data.favorite.FavoriteSongRecord
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.artwork.AlbumArtworkLoader
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.components.smartisanCheckboxBounds
import com.smartisan.music.ui.components.smartisanCheckboxHit
import com.smartisan.music.ui.components.smartisanVerticalScrollbar
import com.smartisan.music.ui.library.LibraryDivider
import com.smartisan.music.ui.library.LibraryFooter
import com.smartisan.music.ui.library.LibraryPlayActions
import com.smartisan.music.ui.library.LibraryTopCard
import com.smartisan.music.ui.library.SmartisanMediaArtwork
import com.smartisan.music.ui.library.libraryListEntrance
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.library.rememberAlbumArtworkLoader
import com.smartisan.music.ui.library.rememberLibraryListEntrance
import com.smartisan.music.ui.songs.SmartisanPlayingTitle
import com.smartisan.music.ui.songs.SmartisanSongsSortHeader
import com.smartisan.music.ui.songs.SongPlaybackState
import com.smartisan.music.ui.songs.SongSortMainPlayCount
import com.smartisan.music.ui.songs.SongSortPreference
import com.smartisan.music.ui.songs.SongSortSelection
import com.smartisan.music.ui.songs.qualityBadge
import com.smartisan.music.ui.songs.rememberSongPlaybackState
import com.smartisan.music.ui.songs.sortedForSongSort
import com.smartisan.music.ui.songs.songPlayCount
import com.smartisan.music.ui.theme.themedListRowBackground

@Composable
internal fun LovedSongsPage(
    active: Boolean,
    mediaItems: List<MediaItem>,
    favoriteRecords: List<FavoriteSongRecord>,
    hiddenMediaIds: Set<String>,
    libraryLoaded: Boolean,
    onClose: (() -> Unit)?,
    onSearchClick: () -> Unit = {},
    onTrackMoreClick: (MediaItem) -> Unit,
    onRemoveFavoriteMediaIds: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val browser = LocalPlaybackBrowser.current
    val artwork = rememberAlbumArtworkLoader()
    val playback = rememberSongPlaybackState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 与歌曲页同一套排序（歌曲名/音质/播放次数/添加时间），并且**读写同一份持久化偏好**（1.2.26）。
    // 旧版用的是本地 remember 默认值：每次进页面都回到「歌曲名」，与歌曲页不一致。
    var sortSelection by remember(context) {
        mutableStateOf(SongSortPreference.load(context))
    }
    // 1.2.26：播放次数改读 Room 实时值。旧版用扫描时写进 extras 的静态快照，
    // 于是同一首歌在歌曲页与「我喜欢」显示的数字不一样，按次数排序结果也不同。
    val playCountRepository =
        remember(context) {
            com.smartisan.music.data.playback.PlaybackStatsRepository.getInstance(
                context.applicationContext,
            )
        }
    val livePlayCounts by playCountRepository.statsFlow.collectAsState(initial = emptyMap())
    // [1.4.25·R53] 排序栏 / 播放栏上下对调开关（与歌曲页读同一个 key，默认关）。
    val sortPlayBarSwap by
        remember(context) { com.smartisan.music.data.settings.UiSettingsStore(context) }
            .sortPlayBarSwap
            .collectAsState(initial = false)
    var editMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var removeConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(sortSelection) {
        SongSortPreference.save(context, sortSelection)
    }
    val entries =
        remember(favoriteRecords, mediaItems, hiddenMediaIds, sortSelection, livePlayCounts) {
            val built =
                buildLovedSongEntries(
                    favoriteRecords,
                    mediaItems.filterNot { it.mediaId in hiddenMediaIds },
                )
            // 对 mediaItem 用歌曲页排序逻辑排序，保持收藏记录（likedAt）关联
            val sortedItems =
                built.map { it.mediaItem }.sortedForSongSort(sortSelection) { item ->
                    livePlayCounts[item.mediaId] ?: 0L
                }
            val byId = built.associateBy { it.mediaItem.mediaId }
            sortedItems.mapNotNull { byId[it.mediaId] }
        }
    val list = rememberLazyListState()
    ImmersiveLocateEffect(list) {
        entries.indexOfFirst { it.mediaItem.mediaId == ImmersiveScrollBus.targetMediaId }
    }
    val entrance =
        rememberLibraryListEntrance(Unit, active && entries.isNotEmpty()) {
            list.layoutInfo.visibleItemsInfo.size
        }
    LaunchedEffect(entries) {
        selectedIds = selectedIds.intersect(entries.map { it.mediaItem.mediaId }.toSet())
        if (entries.isEmpty()) editMode = false
    }
    // 1.2.26：只有切换排序时才滚回顶部。旧版挂在 entries 上，取消一首收藏就会把列表弹回第一首。
    LaunchedEffect(sortSelection) {
        list.scrollToItem(0)
    }
    fun exitEdit() {
        editMode = false
        selectedIds = emptySet()
    }
    fun select(key: String, selected: Boolean) {
        selectedIds = if (selected) selectedIds + key else selectedIds - key
    }
    BackHandler(active && editMode) { exitEdit() }
    if (onClose != null) BackHandler(active && !editMode) { onClose() }
    if (!active) return
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val checkboxBounds = remember { mutableMapOf<String, Rect>() }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxSize().libraryTexture()) {
        val editAction =
            SmartisanTitleBarAction(
                R.drawable.standard_icon_multi_select_selector,
                stringResource(R.string.edit),
                onClick = {
                    editMode = true
                    selectedIds = emptySet()
                },
                enabled = entries.isNotEmpty(),
            )
        val actions =
            listOfNotNull(
                if (editMode) {
                    SmartisanTitleBarAction(
                        R.drawable.titlebar_btn_delete_selector,
                        stringResource(R.string.delete),
                        onClick = { removeConfirm = true },
                        enabled = selectedIds.isNotEmpty(),
                    )
                } else {
                    // 排序已移到顶栏下方的排序条（与歌曲页统一）；右上角改搜索图标
                    SmartisanTitleBarAction(
                        R.drawable.search_btn_selector,
                        stringResource(R.string.tab_local_search),
                        onClick = onSearchClick,
                    )
                },
            )
        SmartisanTitleBar(
            stringResource(R.string.collect_music),
            navigationIcon =
                if (editMode)
                    SmartisanTitleBarAction(
                        R.drawable.standard_icon_cancel_selector,
                        stringResource(R.string.cancel),
                        onClick = { exitEdit() },
                    )
                else if (onClose != null)
                    SmartisanTitleBarAction(
                        R.drawable.standard_icon_back_selector,
                        stringResource(R.string.back),
                        onClick = { onClose.invoke() },
                    )
                else null,
            // 编辑（多选）按钮放左上方、紧跟返回键右侧（与更多里的歌曲/专辑页统一，1.2.16）
            navigationActions = if (editMode) emptyList() else listOf(editAction),
            actions = actions,
        )
        Column(Modifier.weight(1f).libraryTexture()) {
            if (entries.isNotEmpty()) {
                // 1.2.33：与歌曲页/专辑页共用同一个 LibraryTopCard，底部 14dp 投影
                // 落在列表之上。之前这里只是裸的 background(surface_card)，没有投影分界。
                // 1.3.7：排序栏只受收起/展开开关控制，播放操作行只受沉浸式控制，互不门禁
                LibraryTopCard {
                    // [1.4.25·R53] 与歌曲页同一套对调规则：只换渲染顺序，
                    // 两栏各自的门禁（收起/展开 vs 沉浸式）完全不动，分隔线始终夹在中间。
                    val sortBar: @Composable () -> Unit = {
                        ChromeSortBarVisibility {
                            SmartisanSongsSortHeader(sortSelection, { sortSelection = it })
                        }
                    }
                    val playBar: @Composable () -> Unit = {
                        ImmersiveActionRowVisibility {
                            Column {
                                if (!sortPlayBarSwap) LibraryDivider()
                                Column(Modifier.graphicsLayer { alpha = if (editMode) .22f else 1f }) {
                                    LibraryPlayActions(
                                        !editMode,
                                        {
                                            buildLovedSongsPlayRequest(entries)?.let {
                                                browser.replaceQueueAndPlay(it.mediaItems, it.startIndex, keepShuffle = true)
                                            }
                                        },
                                        {
                                            buildLovedSongsShuffleRequest(entries)?.let {
                                                browser.replaceQueueAndPlayShuffled(it.mediaItems)
                                            }
                                        },
                                        containerColor = colorResource(R.color.surface_card),
                                    )
                                }
                                // [1.4.28] 删除重复分割线：LibraryPlayActions 内部末尾已有一条。
                            }
                        }
                    }
                    if (sortPlayBarSwap) {
                        playBar()
                        sortBar()
                    } else {
                        sortBar()
                        playBar()
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { listOrigin = it.positionInRoot() }
                        .smartisanVerticalScrollbar(list)
                        .smartisanSlideSelection(
                            editMode,
                            itemAt = { point ->
                                list.layoutInfo.visibleItemsInfo
                                    .firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                    ?.index
                            },
                            keyAt = { entries.getOrNull(it)?.mediaItem?.mediaId },
                            selectedKeys = selectedIds,
                            onSelectionChange = ::select,
                            canStart = { point ->
                                val row =
                                    list.layoutInfo.visibleItemsInfo.firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                val mediaId =
                                    entries.getOrNull(row?.index ?: -1)?.mediaItem?.mediaId
                                smartisanCheckboxHit(
                                    point + listOrigin,
                                    checkboxBounds[mediaId],
                                    touchSlop,
                                )
                            },
                            scrollBy = { list.scrollBy(it) },
                            edgeItemAt = { top ->
                                val visible =
                                    list.layoutInfo.visibleItemsInfo.filter {
                                        it.index in entries.indices
                                    }
                                (if (top) visible.firstOrNull() else visible.lastOrNull())?.index
                            },
                        ),
                    state = list,
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.mediaItem.mediaId }) {
                        index,
                        entry ->
                        val item = entry.mediaItem
                        LovedSongRow(
                            item,
                            editMode,
                            item.mediaId in selectedIds,
                            artwork,
                            playback,
                            showPlayCount = sortSelection.main == SongSortMainPlayCount,
                            onClick = {
                                if (editMode) select(item.mediaId, item.mediaId !in selectedIds)
                                else
                                    buildLovedSongsPlayRequest(entries, item.mediaId)?.let {
                                        browser.replaceQueueAndPlay(it.mediaItems, it.startIndex, keepShuffle = true)
                                    }
                            },
                            onMore = { onTrackMoreClick(item) },
                            onCheckboxBoundsChanged = { bounds ->
                                if (bounds == null) checkboxBounds.remove(item.mediaId)
                                else checkboxBounds[item.mediaId] = bounds
                            },
                            modifier =
                                Modifier.libraryListEntrance(entrance) {
                                    index - list.firstVisibleItemIndex
                                },
                        )
                        LibraryDivider()
                    }
                    item("footer") { LibraryFooter(R.plurals.track_count, entries.size) }
                }
            } else if (libraryLoaded)
                SmartisanEmptyHint(
                    R.drawable.blank_playlist,
                    stringResource(R.string.no_saved_song),
                )
        }
    }
    if (removeConfirm)
        SmartisanModal({ removeConfirm = false }, Modifier.fillMaxWidth(), bottom = true) {
            SmartisanMenuTitleBar(
                stringResource(R.string.uncollect_song_dialog_title),
                { removeConfirm = false },
            )
            Column(
                Modifier.fillMaxWidth()
                    .cardTextureMonet(R.drawable.menu_dialog_background)
                    .padding(
                        horizontal = dimensionResource(R.dimen.menu_dialog_horizontal_distance)
                    )
                    .padding(
                        top = dimensionResource(R.dimen.menu_dialog_btn_margin_view),
                        bottom = 24.dp,
                    )
            ) {
                SmartisanDialogButton(
                    stringResource(R.string.uncollect_song_confirm),
                    {
                        val ids = selectedIds
                        removeConfirm = false
                        exitEdit()
                        onRemoveFavoriteMediaIds(ids)
                    },
                    Modifier.fillMaxWidth(),
                )
            }
        }
}

@Composable
private fun LovedSongRow(
    item: MediaItem,
    editMode: Boolean,
    selected: Boolean,
    artwork: AlbumArtworkLoader,
    playback: SongPlaybackState,
    showPlayCount: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckboxBoundsChanged: (Rect?) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val edit by
        animateFloatAsState(
            if (editMode) 1f else 0f,
            tween(200, easing = SmartisanEaseInOut),
            label = "lovedEdit",
        )
    val checkbox =
        rememberSmartisanDrawablePainter(R.drawable.check_box_selector, checked = selected)
    val density = LocalDensity.current
    val checkWidth = with(density) { checkbox.intrinsicSize.width.toDp() }
    val checkMargin = dimensionResource(R.dimen.check_box_margin_left)
    val imageSize = dimensionResource(R.dimen.listview_item_image_width)
    val title =
        item.mediaMetadata.displayTitle?.toString()
            ?: item.mediaMetadata.title?.toString()
            ?: stringResource(R.string.unknown_song_title)
    val artist =
        item.mediaMetadata.artist?.toString()
            ?: item.mediaMetadata.subtitle?.toString()
            ?: stringResource(R.string.unknown_artist)
    val album = item.mediaMetadata.albumTitle?.toString()?.takeIf(String::isNotBlank)
    val textDirection =
        if (
            androidx.compose.ui.platform.LocalLayoutDirection.current ==
                androidx.compose.ui.unit.LayoutDirection.Rtl
        )
            androidx.compose.ui.text.style.TextDirection.ContentOrRtl
        else androidx.compose.ui.text.style.TextDirection.ContentOrLtr
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Ltr
    ) {
        Row(
            modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.listview_item_height))
                .clipToBounds()
                .themedListRowBackground(pressed = pressed, activated = editMode && selected)
                .semantics { if (editMode) this.selected = selected }
                .clickable(
                    interaction,
                    null,
                    role = if (editMode) Role.Checkbox else Role.Button,
                    onClick = smartisanClick(onClick),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width((checkWidth + checkMargin) * edit).fillMaxHeight()) {
                if (edit > 0f)
                    Image(
                        checkbox,
                        null,
                        Modifier.align(Alignment.CenterEnd)
                            .requiredWidth(checkWidth)
                            .graphicsLayer { alpha = edit }
                            .smartisanCheckboxBounds(onCheckboxBoundsChanged),
                    )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.saved_songs_items_margin_left)))
            Box(Modifier.size(imageSize)) {
                SmartisanMediaArtwork(
                    item,
                    with(density) { imageSize.roundToPx() },
                    R.drawable.noalbumcover_120,
                    Modifier.fillMaxSize(),
                    artwork,
                )
                Image(
                    rememberSmartisanDrawablePainter(R.drawable.mask_albumcover_list),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Column(
                Modifier.weight(1f).padding(start = dimensionResource(R.dimen.common_padding_left))
            ) {
                SmartisanPlayingTitle(
                    title,
                    !editMode && item.mediaId == playback.mediaId,
                    playback.playing,
                    smartisanTextSize(R.dimen.text_size_medium),
                    Modifier.padding(top = dimensionResource(R.dimen.collect_text_padding_top)),
                    textDirection = textDirection,
                    pressed = pressed,
                )
                Row(
                    Modifier.padding(top = dimensionResource(R.dimen.listview_items_margin_top1)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.qualityBadge()?.let {
                        Image(
                            rememberSmartisanDrawablePainter(it),
                            null,
                            Modifier.padding(
                                end = dimensionResource(R.dimen.album_song_index_margin_left)
                            ),
                        )
                    }
                    BasicText(
                        if (album == null) artist else "$artist - $album",
                        style =
                            TextStyle(
                                textDirection = textDirection,
                                color =
                                    smartisanPressedTextColor(
                                        colorResource(R.color.list_text_color_small),
                                        pressed,
                                    ),
                                fontSize = smartisanTextSize(R.dimen.text_size_small),
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            if (showPlayCount && !editMode) {
                // 播放次数排序模式下显示次数（与歌曲页一致，让排序结果可见）
                BasicText(
                    item.songPlayCount().toString(),
                    Modifier.padding(start = 10.dp),
                    style =
                        TextStyle(
                            color =
                                smartisanPressedTextColor(
                                    colorResource(R.color.list_text_color_small),
                                    pressed,
                                ),
                            fontSize = smartisanTextSize(R.dimen.text_size_micro),
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
            }
            if (!editMode) {
                val moreInteraction = remember { MutableInteractionSource() }
                val morePressed by moreInteraction.collectSmartisanPressedAsState()
                Image(
                    rememberSmartisanDrawablePainter(
                        R.drawable.btn_more_selector,
                        pressed = morePressed || pressed,
                    ),
                    stringResource(R.string.tab_more),
                    Modifier.padding(end = dimensionResource(R.dimen.listview_items_margin_right))
                        .size(dimensionResource(R.dimen.listview_item_height))
                        .clickable(
                            moreInteraction,
                            null,
                            role = Role.Button,
                            onClick = smartisanClick(onMore),
                        ),
                    contentScale = ContentScale.None,
                )
            }
        }
    }
}
