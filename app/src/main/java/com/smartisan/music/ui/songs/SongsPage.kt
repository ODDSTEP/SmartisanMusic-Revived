package com.smartisan.music.ui.songs

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.shell.ImmersiveScrollBus
import com.smartisan.music.ui.shell.ImmersiveLocateEffect
import com.smartisan.music.ui.shell.ImmersiveActionRowVisibility
import com.smartisan.music.ui.shell.ChromeSortBarVisibility
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.components.SectionLetterHeader
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanCheckboxHit
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.components.smartisanSlideSelection
import com.smartisan.music.ui.components.smartisanVerticalScrollbar
import com.smartisan.music.ui.library.HiddenRunDivider
import com.smartisan.music.ui.library.LibraryDivider
import com.smartisan.music.ui.library.LibraryFooter
import com.smartisan.music.ui.library.LibraryPlayActions
import com.smartisan.music.ui.library.LibraryTopCard
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.data.playback.PlaybackStatsRepository
import kotlinx.coroutines.launch

/**
 * 歌曲页顶部卡片：承载分类排序栏与播放操作栏，底部投影落在列表之上，
 * 与专辑 / 风格 / 播放列表等页面「顶栏自成一张」的观感保持一致。
 */
@Composable
private fun SongsTopCard(content: @Composable ColumnScope.() -> Unit) {
    // 1.2.33：实现已抽到公共组件 LibraryTopCard，与专辑页共用同一份投影分界，
    // 避免「歌曲页有分界、专辑页没有」。此处保留函数签名，歌曲页行为不变。
    LibraryTopCard(content = content)
}

@Composable
internal fun SongsPage(
    mediaItems: List<MediaItem>,
    libraryLoaded: Boolean,
    active: Boolean,
    editMode: Boolean,
    selectedSongIds: Set<String>,
    hiddenMediaIds: Set<String>,
    onSongSelectionChange: (String, Boolean) -> Unit,
    onTrackMoreClick: (MediaItem) -> Unit,
    onRequestSongDeleteConfirmation: (Set<String>, (() -> Unit)?) -> Unit,
    onRequestSongHide: (Set<String>, (() -> Unit)?) -> Unit = { _, _ -> },
    /** [显示隐藏入口改造] 编辑态行尾眼睛：单曲隐藏/恢复。true=隐藏，false=恢复显示。 */
    onSongVisibilityChange: (String, Boolean) -> Unit = { _, _ -> },
    /** 播放列表「添加到列表」等复用场景关掉行尾眼睛。 */
    showVisibilityEye: Boolean = true,
    modifier: Modifier = Modifier,
    playbackBarOverlayHeight: Dp = 0.dp,
) {
    val browser = LocalPlaybackBrowser.current
    val playback = rememberSongPlaybackState()
    val context = LocalContext.current
    // 1.2.23：订阅 Room 实时播放次数流（与「理解记忆」同源）。歌曲页据此即时显示并排序播放次数，
    // 不再依赖扫描时写入 MediaItem extras 的静态快照（那个永远停在初值）。
    val playCountRepository = remember { PlaybackStatsRepository.getInstance(context.applicationContext) }
    val livePlayCounts by playCountRepository.statsFlow.collectAsState(initial = emptyMap())
    var sortSelection by remember(context) { mutableStateOf(SongSortPreference.load(context)) }
    // [1.4.33·隐藏场景推广] 完整有序列表（含隐藏项）先排好，用于计算
    // 「每个可见行下面紧邻了多少首被隐藏的歌」→ 边界分界线按这个数叠层加粗。
    // 仅在「按播放次数」排序时让计数流参与重排，避免其它排序每次播放都被触发重排。
    val allSortedSongs =
        remember(
            mediaItems,
            sortSelection,
            if (sortSelection.main == SongSortMainPlayCount) livePlayCounts else Unit,
        ) {
            mediaItems.sortedForSongSort(
                sortSelection,
                playCountOf = { mediaItem -> livePlayCounts[mediaItem.mediaId] ?: 0L },
            )
        }
    // mediaId -> 其后紧邻的连续隐藏歌曲数 N（编辑态隐藏行展开，恒为 0，不叠层）。
    val hiddenRunBelowBySong =
        remember(allSortedSongs, hiddenMediaIds, editMode) {
            if (editMode) emptyMap()
            else {
                val result = mutableMapOf<String, Int>()
                for (i in allSortedSongs.indices) {
                    val current = allSortedSongs[i]
                    if (current.mediaId in hiddenMediaIds) continue
                    var run = 0
                    var j = i + 1
                    while (j < allSortedSongs.size && allSortedSongs[j].mediaId in hiddenMediaIds) {
                        run++
                        j++
                    }
                    if (run > 0) result[current.mediaId] = run
                }
                result
            }
        }
    val sortedSongs =
        remember(allSortedSongs, hiddenMediaIds, editMode) {
            // 与文件夹页一致：普通模式不显示已隐藏歌曲；编辑模式下显示（变暗、可恢复）
            if (editMode) allSortedSongs
            else allSortedSongs.filterNot { it.mediaId in hiddenMediaIds }
        }
    val displayMode = sortSelection.main.toSongSortDisplayMode()
    val rows =
        remember(sortedSongs, displayMode) {
            buildSongRows(sortedSongs, displayMode.toSectionMode())
        }
    // 1.4.11 · S16 记忆式首页（方案 A）：记住歌曲页滚动位置，回来接着看。
    // 用 rememberLazyListState 的 firstVisibleItemIndex 持久化；开关默认开，可在设置关闭。
    val listMemoryEnabled by
        com.smartisan.music.data.settings.SongListMemoryStore.enabled.collectAsState()
    val listMemoryStore = com.smartisan.music.data.settings.SongListMemoryStore
    // [1.4.25·R53] 排序栏 / 播放栏上下对调开关（设置→界面，默认关=排序栏在上）。
    val sortPlayBarSwap by
        remember(context) { com.smartisan.music.data.settings.UiSettingsStore(context) }
            .sortPlayBarSwap
            .collectAsState(initial = false)
    val sortKey = sortSelection.main
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 本页是否已完成本次「进页恢复」。用户一旦手动滑动即置 true，之后不再自动还原，
    // 避免 LaunchedEffect 重入把用户当前位置又拉回旧位置（S16-4 手动滑动以用户为准）。
    var restoreDone by remember { mutableStateOf(false) }
    var userHasScrolled by remember { mutableStateOf(false) }
    // [1.4.28] 上一次生效的排序键。用于区分「用户主动换排序」和「首次组合」：
    // 旧实现 LaunchedEffect(sortKey) 在首次组合时就无条件 clearScrollIndex，
    // 而 LaunchedEffect(active) 随后才读位置，读到的已经被清空 → 冷启动永远回顶部，
    // 「记忆式首页」等于没做（只有从别的页切回来时才碰巧有效）。
    var lastSortKey by remember { mutableStateOf<Int?>(null) }
    var openKey by remember { mutableStateOf<String?>(null) }
    var swipeActive by remember { mutableStateOf(false) }
    ImmersiveLocateEffect(list) {
        rows.indexOfFirst {
            (it as? SongListRow.Song)?.mediaItem?.mediaId == ImmersiveScrollBus.targetMediaId
        }
    }
    // 排序**变化**：清掉该维度旧位置、回顶部（与历史行为一致），并视为已完成恢复。
    // 首次组合不清也不回顶，交给下面的进页恢复逻辑。
    LaunchedEffect(sortKey) {
        val isFirstComposition = lastSortKey == null
        lastSortKey = sortKey
        if (isFirstComposition) return@LaunchedEffect
        openKey = null
        swipeActive = false
        if (listMemoryEnabled) listMemoryStore.clearScrollIndex(context, sortKey)
        list.scrollToItem(0)
        restoreDone = true
        userHasScrolled = false
    }
    // 每次进入该页（active=true）重置恢复标志；真正的恢复放在下一个 effect，
    // 这样资料库还没加载（rows 为空）时可以等数据到了再恢复，而不是直接放弃。
    LaunchedEffect(active) {
        if (active) {
            restoreDone = false
            userHasScrolled = false
        }
    }
    // 每次进入该页（active=true）恢复一次记忆位置（S16-4）。
    // [v18·修进页下移挡字母栏] 旧逻辑直接 scrollToItem(saved)，saved 是上次离页的
    // firstVisibleItemIndex；若它落在某字母组的歌曲行上，恢复后该组字母栏刚好被顶出屏幕
    // （用户："每次从别的页转到歌曲页，都会自己下移刚好把字母 A 的字母栏挡住"）。
    // 修法：把目标位置回退到它所属分组的字母栏行（SongListRow.Header），字母栏始终可见；
    // 无分组模式（None，rows 全是 Song 无 Header）回退找不到，保持原 saved 位置不变。
    LaunchedEffect(active, rows.size) {
        if (!active || restoreDone || rows.isEmpty()) return@LaunchedEffect
        if (listMemoryEnabled) {
            val saved = listMemoryStore.scrollIndex(context, sortKey)
            if (saved > 0 && saved < rows.size) {
                var headerIdx = saved.coerceAtMost(rows.lastIndex)
                while (headerIdx >= 0 && rows.getOrNull(headerIdx) !is SongListRow.Header) {
                    headerIdx--
                }
                // 找到分组头→滚到字母栏；找不到（无分组模式）→保持原位置。
                list.scrollToItem(if (headerIdx >= 0) headerIdx else saved)
            }
        }
        restoreDone = true
    }
    // 记录用户手动滑动：一旦发生，后续不再自动还原（竞态护栏）。
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress && restoreDone) userHasScrolled = true
    }
    // 离页时保存当前滚动位置（S16-2 核心），开关关闭则不写。
    DisposableEffect(active, sortKey, listMemoryEnabled) {
        onDispose {
            if (listMemoryEnabled) {
                val idx = list.firstVisibleItemIndex
                if (idx > 0) listMemoryStore.saveScrollIndex(context, sortKey, idx)
            }
        }
    }
    LaunchedEffect(editMode, active) {
        if (editMode || !active) {
            openKey = null
            swipeActive = false
        }
    }
    if (!active) return
    // [1.4.25·R49-④] letters_bar_background_shadow 已删（R29 之后视觉上不再绘制，
    // 只剩这里被当成宽度占位）。为保证列表右侧留白与之前完全一致（零布局位移），
    // 直接写死原来的合成宽度：阴影 23px + 底图 91px = 114px @xxhdpi = 38dp。
    val quickBarWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 38.dp.toPx() }
    val moreWidth =
        rememberSmartisanDrawablePainter(R.drawable.btn_more_selector).intrinsicSize.width
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val checkboxBounds = remember { mutableMapOf<String, Rect>() }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    Column(modifier.fillMaxSize().libraryTexture()) {
        if (sortedSongs.isNotEmpty()) {
            // 「分类 + 播放」共用一张卡片，与专辑 / 风格等页面的顶栏高度、投影保持一致
            // 1.3.7：排序栏只受收起/展开开关控制，播放操作行只受沉浸式控制，互不门禁
            SongsTopCard {
                // [1.4.25·R53] 排序栏与播放栏对调：两块内容各自独立门禁（排序栏只受
                // 收起/展开控制、播放栏只受沉浸式控制），所以对调只是换渲染顺序，
                // 各自的显隐逻辑一行没动。LibraryDivider 始终夹在两栏之间。
                val sortBar: @Composable () -> Unit = {
                    ChromeSortBarVisibility {
                        SmartisanSongsSortHeader(
                            sortSelection,
                            { selection ->
                                sortSelection = selection
                                // 1.2.25：持久化，搜索页据此构造同序队列
                                SongSortPreference.save(context, selection)
                            },
                        )
                    }
                }
                val playBar: @Composable () -> Unit = {
                    ImmersiveActionRowVisibility {
                        Column {
                            if (!sortPlayBarSwap) LibraryDivider()
                            Column(Modifier.graphicsLayer { alpha = if (editMode) .22f else 1f }) {
                                LibraryPlayActions(
                                    !editMode,
                                    { browser.replaceQueueAndPlay(sortedSongs) },
                                    { browser.replaceQueueAndPlayShuffled(sortedSongs) },
                                    containerColor = colorResource(R.color.surface_card),
                                )
                            }
                            // [1.4.28] 删掉这里补的第二条 LibraryDivider：
                            // LibraryPlayActions 内部末尾已经固定画一条，对调排序栏时两条 1dp 线紧挨，
                            // 用户看到的是 2px 双线。
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
            Box(Modifier.weight(1f).padding(bottom = playbackBarOverlayHeight)) {
                LazyColumn(
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { listOrigin = it.positionInRoot() }
                        .smartisanVerticalScrollbar(list)
                        .smartisanSlideSelection(
                            enabled = editMode,
                            itemAt = { point ->
                                list.layoutInfo.visibleItemsInfo
                                    .firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                    ?.index
                            },
                            keyAt = {
                                (rows.getOrNull(it) as? SongListRow.Song)?.mediaItem?.mediaId
                            },
                            selectedKeys = selectedSongIds,
                            onSelectionChange = onSongSelectionChange,
                            canStart = { point ->
                                val row =
                                    list.layoutInfo.visibleItemsInfo.firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                val mediaId =
                                    (rows.getOrNull(row?.index ?: -1) as? SongListRow.Song)
                                        ?.mediaItem
                                        ?.mediaId
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
                                        rows.getOrNull(it.index) is SongListRow.Song
                                    }
                                (if (top) visible.firstOrNull() else visible.lastOrNull())?.index
                            },
                        ),
                    state = list,
                ) {
                    itemsIndexed(
                        rows,
                        key = { _, row ->
                            when (row) {
                                is SongListRow.Header -> "header:${row.key}"
                                is SongListRow.Song -> "song:${row.mediaItem.mediaId}"
                            }
                        },
                        contentType = { _, row ->
                            if (row is SongListRow.Header) "header" else "song"
                        },
                    ) { _, row ->
                        // 列表项统一挂接 animateItem：歌曲被隐藏/删除时淡出、其余行平滑上移补位
                        Box(Modifier.animateItem()) {
                            when (row) {
                                is SongListRow.Header ->
                                    SectionLetterHeader(row.key.title(context))
                                is SongListRow.Song -> {
                                    SmartisanSwipeDeleteRow(
                                        row.mediaItem.mediaId,
                                        !editMode,
                                        openKey,
                                        { openKey = it },
                                        onDelete = { close ->
                                            onRequestSongDeleteConfirmation(
                                                setOf(row.mediaItem.mediaId),
                                                close,
                                            )
                                        },
                                        hidden = row.mediaItem.mediaId in hiddenMediaIds,
                                        onHide = { close ->
                                            onRequestSongHide(
                                                setOf(row.mediaItem.mediaId),
                                                close,
                                            )
                                        },
                                        onSwipeActivity = { swipeActive = it },
                                    ) {
                                        // [1.4.33·验收第19点] 删掉原先包住整行的二值 alpha Box：
                                        // 它把背景一起变透明、露出底层竖条纹（用户明确反对）。
                                        // 置灰改由 SmartisanSongRow 内部的文字 brightAlpha 负责：
                                        // 只灰文字、背景不变、且随眼睛 300ms 同步渐变。
                                        SmartisanSongRow(
                                            row.mediaItem,
                                            { browser.replaceQueueAndPlay(sortedSongs, row.songIndex, keepShuffle = true) },
                                            onTrackMoreClick,
                                            editMode = editMode,
                                            selected = row.mediaItem.mediaId in selectedSongIds,
                                            onSelectionChange = {
                                                onSongSelectionChange(row.mediaItem.mediaId, it)
                                            },
                                            displayMode = displayMode,
                                            playback = playback,
                                            livePlayCount = livePlayCounts[row.mediaItem.mediaId],
                                            onCheckboxBoundsChanged = { bounds ->
                                                val id = row.mediaItem.mediaId
                                                if (bounds == null) checkboxBounds.remove(id)
                                                else checkboxBounds[id] = bounds
                                            },
                                            hidden = row.mediaItem.mediaId in hiddenMediaIds,
                                            onVisibilityChange = { hidden ->
                                                onSongVisibilityChange(row.mediaItem.mediaId, hidden)
                                            },
                                            showVisibilityEye = showVisibilityEye,
                                            contentEndInset =
                                                if (sortSelection.main == SongSortMainName && sortedSongs.size > 30)
                                                    with(density) {
                                                        (quickBarWidth - moreWidth / 3)
                                                            .coerceAtLeast(0f)
                                                            .toDp()
                                                    }
                                                else 0.dp,
                                        )
                                    }
                                    // [1.4.33·隐藏场景推广] 隐藏行上收后，边界线按紧邻隐藏数叠层加粗。
                                    HiddenRunDivider(
                                        hiddenRunBelow = hiddenRunBelowBySong[row.mediaItem.mediaId] ?: 0,
                                    )
                                }
                            }
                        }
                    }
                    item("footer") { LibraryFooter(R.plurals.track_count, sortedSongs.size) }
                }
                if (sortSelection.main == SongSortMainName && sortedSongs.size > 30 && !swipeActive) {
                    SmartisanQuickBar(
                        onLetter = { letter ->
                            val index = rows.indexOfFirst {
                                it is SongListRow.Header && it.key == SongHeaderKey.Name(letter)
                            }
                            if (index >= 0) scope.launch { list.scrollToItem(index) }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        } else if (libraryLoaded) {
            com.smartisan.music.ui.components.SmartisanEmptyHint(
                R.drawable.blank_song,
                stringResource(R.string.no_song),
                subtitle = stringResource(R.string.show_song),
            )
        }
    }
}
