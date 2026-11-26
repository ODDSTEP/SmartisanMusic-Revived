package com.smartisan.music.ui.folder

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.ui.theme.monetOutlineVariant
import com.smartisan.music.ui.shell.ImmersiveScrollBus
import com.smartisan.music.ui.shell.ImmersiveLocateEffect
import com.smartisan.music.ui.shell.ImmersiveActionRowVisibility
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.settings.FileScanSettings
import com.smartisan.music.data.settings.FileScanSettingsStore
import com.smartisan.music.data.settings.isHiddenByFileScan
import com.smartisan.music.platform.text.HanLatinTransliterator
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.withSelection
import com.smartisan.music.ui.library.LibraryBlank
import com.smartisan.music.ui.library.LibraryDivider
import com.smartisan.music.ui.library.LibraryFooter
import com.smartisan.music.ui.library.LibraryIconButton
import com.smartisan.music.ui.library.LibraryPlayActions
import com.smartisan.music.ui.library.LibraryTopCard
import com.smartisan.music.ui.library.libraryDuration
import com.smartisan.music.ui.library.libraryListEntrance
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.library.rememberLibraryListEntrance
import com.smartisan.music.ui.shell.PageStackTransition
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.ui.shell.titlebar.TitleBarTransition
import com.smartisan.music.ui.songs.SmartisanSongRow
import com.smartisan.music.ui.songs.rememberSongPlaybackState
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.smartisan.music.ui.theme.themedListRowBackground

private const val FolderStorageLabel = "Phone Storage"
private const val FolderVisibilityAnimationMillis = 300L

private data class FolderTarget(
    val key: String,
    val title: String,
)

@Composable
internal fun FolderPage(
    active: Boolean,
    libraryRefreshVersion: Int,
    libraryRefreshing: Boolean,
    onClose: (() -> Unit)?,
    onRefreshLibrary: () -> Unit,
    onMediaIdsHidden: (Set<String>) -> Unit,
    onRequestDeleteMediaIds: (Set<String>) -> Unit,
    onTrackMoreClick: (MediaItem) -> Unit,
    /** 外部「查看文件夹」注入的目录键（normalizedDirectoryKey）；非空时钻入该文件夹 */
    externalTargetKey: String? = null,
    onExternalTargetConsumed: () -> Unit = {},
    /** 从外部跳转进来后按返回：回到来源页（而非文件夹首页） */
    onJumpBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browser = LocalPlaybackBrowser.current
    val audioLibrary =
        remember(context.applicationContext) {
            LocalAudioLibrary(context.applicationContext)
        }
    val exclusionsStore =
        remember(context.applicationContext) {
            LibraryExclusionsStore(context.applicationContext)
        }
    val directoryTitle = stringResource(R.string.tab_directory)
    val exclusions by exclusionsStore.exclusions.collectAsState(initial = LibraryExclusions())
    val fileScanSettings by
        remember(context.applicationContext) {
            FileScanSettingsStore(context.applicationContext)
        }
            .settings
            .collectAsState(initial = FileScanSettings())
    val hasPermission =
        remember(context) {
            hasAudioPermission(context)
        }
    var mediaItems by remember(audioLibrary) { mutableStateOf(emptyList<MediaItem>()) }
    var target by remember { mutableStateOf<FolderTarget?>(null) }
    /** 本次 target 是否由外部「查看文件夹」跳转进来（决定返回键回来源页） */
    var jumpedFromExternal by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var selectedDirectoryKeys by remember { mutableStateOf(emptySet<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(active, hasPermission, libraryRefreshVersion, audioLibrary) {
        if (!active || !hasPermission) {
            mediaItems = emptyList()
            return@LaunchedEffect
        }
        mediaItems =
            withContext(Dispatchers.IO) {
                audioLibrary.getAudioItems(forceRefresh = libraryRefreshVersion > 0)
            }
    }

    // 文件扫描开关命中的内容一律「当作从未扫描到」：文件夹页同样在数据源头排除，
    // 这些文件不参与目录统计、也不出现在文件夹详情与编辑模式里
    val visibleMediaItems =
        remember(mediaItems, fileScanSettings) {
            mediaItems.filterNot { it.isHiddenByFileScan(fileScanSettings) }
        }

    val allDirectories =
        remember(visibleMediaItems, exclusions) {
            buildDirectoryEntries(
                mediaItems = visibleMediaItems,
                exclusions = exclusions,
                storageLabel = FolderStorageLabel,
            )
        }

    // 外部「查看文件夹」定位（1.2.16）：目录数据就绪后钻入目标文件夹
    LaunchedEffect(externalTargetKey, allDirectories) {
        val key = externalTargetKey ?: return@LaunchedEffect
        val entry = allDirectories.find { it.key == key }
        if (entry != null) {
            target = FolderTarget(key = entry.key, title = entry.name)
            jumpedFromExternal = true
            onExternalTargetConsumed()
        } else if (allDirectories.isNotEmpty()) {
            // 目录已就绪但找不到目标（被过滤等）→ 停在文件夹根
            onExternalTargetConsumed()
        }
    }

    BackHandler(enabled = active && target != null) {
        target = null
        // 跳转进来的目标页：返回键回来源页（而非文件夹首页）
        if (jumpedFromExternal) {
            jumpedFromExternal = false
            onJumpBack()
        }
    }
    BackHandler(enabled = active && target == null && editMode) {
        editMode = false
        selectedDirectoryKeys = emptySet()
    }
    if (onClose != null) {
        BackHandler(enabled = active && target == null && !editMode) {
            onClose()
        }
    }

    Box(modifier = modifier.fillMaxSize().libraryTexture()) {
        val titleAreaHeight =
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                dimensionResource(R.dimen.title_bar_height)
        val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
        val handleBack: () -> Unit = {
            when {
                target != null -> {
                    target = null
                    // 跳转进来的目标页：标题栏返回同样回来源页（而非文件夹首页）
                    if (jumpedFromExternal) {
                        jumpedFromExternal = false
                        onJumpBack()
                    }
                }
                editMode -> {
                    editMode = false
                    selectedDirectoryKeys = emptySet()
                }
                else -> {
                    onClose?.invoke()
                }
            }
        }
        val enterEdit = {
            editMode = true
            selectedDirectoryKeys = emptySet()
        }
        val deleteSelected = {
            if (selectedDirectoryKeys.isNotEmpty()) {
                showDeleteConfirm = true
            }
        }
        // 统一的文件夹隐藏入口：行内与标题栏批量操作共用
        fun setDirectoriesHidden(entries: List<DirectoryEntry>, hidden: Boolean) {
            val affectedMediaIds =
                if (hidden) {
                    entries
                        .flatMap { mediaIdsInDirectory(visibleMediaItems, it.key) }
                        .toSet()
                } else {
                    emptySet()
                }
            scope.launch {
                delay(FolderVisibilityAnimationMillis)
                exclusionsStore.setDirectoryKeysHidden(
                    directoryKeys = entries.map { it.key }.toSet(),
                    hidden = hidden,
                )
                if (affectedMediaIds.isNotEmpty()) {
                    onMediaIdsHidden(affectedMediaIds)
                }
            }
        }
        // [1.4.30·R137 B 方案] 顶栏"眼睛"删除，隐藏改回逐行入口（行内眼睛）。
        // setDirectoriesHidden 保留：行内眼睛与将来任何批量入口共用同一条写库路径，
        // 避免两份隐藏逻辑（200ms 延迟对齐动画、mediaIds 同步）各写一遍走偏。
        Column(modifier = Modifier.fillMaxSize()) {
            TitleBarTransition(
                secondaryKey = target,
                modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                label = "folder title stack",
                primaryContent = {
                    FolderTitleBar(
                        modifier = Modifier.fillMaxSize(),
                        title = directoryTitle,
                        editMode = editMode,
                        selectedCount = selectedDirectoryKeys.size,
                        libraryRefreshing = libraryRefreshing,
                        onBack = handleBack,
                        showRootBack = onClose != null,
                        onEnterEdit = enterEdit,
                        onDeleteSelected = deleteSelected,
                        onRefreshLibrary = onRefreshLibrary,
                    )
                },
                secondaryContent = { folderTarget ->
                    FolderTitleBar(
                        modifier = Modifier.fillMaxSize(),
                        title = folderTarget.title,
                        editMode = false,
                        selectedCount = 0,
                        libraryRefreshing = libraryRefreshing,
                        showRightActions = false,
                        onBack = handleBack,
                        showRootBack = true,
                        onEnterEdit = enterEdit,
                        onDeleteSelected = deleteSelected,
                        onRefreshLibrary = onRefreshLibrary,
                    )
                },
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PageStackTransition(
                    secondaryKey = target,
                    modifier = Modifier.fillMaxSize(),
                    label = "folder detail stack",
                    primaryContent = {
                        FolderRootPage(
                            active = active,
                            directories = allDirectories,
                            editMode = editMode,
                            selectedDirectoryKeys = selectedDirectoryKeys,
                            onDirectoryClick = { entry ->
                                if (editMode) {
                                    selectedDirectoryKeys = selectedDirectoryKeys.toggle(entry.key)
                                } else {
                                    target =
                                        FolderTarget(
                                            key = entry.key,
                                            title = entry.name,
                                        )
                                }
                            },
                            onDirectorySelectionChange = { directoryKey, selected ->
                                selectedDirectoryKeys =
                                    selectedDirectoryKeys.withSelection(directoryKey, selected)
                            },
                            onDirectoryVisibilityChange = { entry, hidden ->
                                setDirectoriesHidden(listOf(entry), hidden)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    secondaryContent = { folderTarget ->
                        val songs =
                            remember(visibleMediaItems, exclusions, folderTarget.key) {
                                filterMediaItemsForDirectory(
                                        mediaItems = visibleMediaItems,
                                        directoryKey = folderTarget.key,
                                        exclusions = exclusions,
                                    )
                                    .sortedForFolder()
                            }
                        FolderDetailPage(
                            active = active && target == folderTarget,
                            tracks = songs,
                            browser = browser,
                            onTrackMoreClick = onTrackMoreClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
        TitleBarShadow(
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .offset(y = titleAreaHeight)
                    .fillMaxWidth()
                    .height(titleShadowHeight)
                    .zIndex(1f)
        )
    }

    if (showDeleteConfirm)
        SmartisanDeleteConfirmation(
            title = stringResource(R.string.dialog_remove_song_by_dor),
            onDismiss = {
                showDeleteConfirm = false
            },
            onConfirm = {
                val keys = selectedDirectoryKeys
                if (keys.isEmpty()) {
                    showDeleteConfirm = false
                    return@SmartisanDeleteConfirmation
                }
                val affectedMediaIds =
                    keys
                        .flatMap { key ->
                            mediaIdsInDirectory(mediaItems = mediaItems, directoryKey = key)
                        }
                        .toSet()
                onRequestDeleteMediaIds(affectedMediaIds)
                selectedDirectoryKeys = emptySet()
                editMode = false
                showDeleteConfirm = false
            },
        )
}

private fun List<MediaItem>.sortedForFolder(): List<MediaItem> {
    return sortedWith(
        compareBy<MediaItem> { item ->
                item.folderSortBucket()
            }
            .thenBy { item ->
                item.folderSortKey()
            }
    )
}

private fun MediaItem.folderSortTitle(): String {
    return mediaMetadata.displayTitle?.toString() ?: mediaMetadata.title?.toString() ?: ""
}

private fun MediaItem.folderSortKey(): String {
    return FolderTitleNormalizer.normalize(folderSortTitle())
}

private fun MediaItem.folderSortBucket(): String {
    val letter = folderSectionLetter()
    return if (letter == "#") "ZZZ" else letter
}

private fun MediaItem.folderSectionLetter(): String {
    val firstLetter =
        folderSortKey().firstOrNull { char ->
            char.isLetterOrDigit()
        } ?: return "#"
    val upper = firstLetter.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else "#"
}

private object FolderTitleNormalizer {
    private val combiningMarks = "\\p{Mn}+".toRegex()

    fun normalize(title: String): String {
        val trimmed = title.trim()
        val transliterated = HanLatinTransliterator.transliterate(trimmed)
        return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

@Composable
private fun FolderTitleBar(
    title: String,
    editMode: Boolean,
    selectedCount: Int,
    libraryRefreshing: Boolean,
    modifier: Modifier = Modifier,
    showRightActions: Boolean = true,
    onBack: () -> Unit,
    showRootBack: Boolean,
    onEnterEdit: () -> Unit,
    onDeleteSelected: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    // [1.4.30·R137 B 方案] 顶栏那颗"眼睛"整个删掉，隐藏入口回到每一行行内
    // （FolderDirectoryRow）。原实现把眼睛放顶栏，有三处抹不掉的问题：
    //  ①顶栏 action 自带按下放大 spring，和逐帧眨眼叠加，"闪一下"；
    //  ②批量隐藏的选中态在三态之间跳，`enabled` 翻转会让整颗图标透明度硬跳；
    //  ③用户要的是"逐行决定藏不藏"，顶栏只能批处理，语义不对。
    SmartisanTitleBar(
        title,
        modifier,
        showShadow = false,
        navigationIcon =
            if (editMode || showRootBack)
                SmartisanTitleBarAction(
                    if (editMode) R.drawable.standard_icon_cancel_selector
                    else R.drawable.standard_icon_back_selector,
                    stringResource(if (editMode) R.string.cancel else R.string.back),
                    onBack,
                )
            else null,
        // 编辑（多选）按钮放左上方、紧跟返回键右侧（顶栏统一规范，1.2.16）
        navigationActions =
            if (!editMode && showRightActions)
                listOf(
                    SmartisanTitleBarAction(
                        R.drawable.standard_icon_multi_select_selector,
                        stringResource(R.string.edit),
                        onEnterEdit,
                    )
                )
            else emptyList(),
        actions =
            when {
                editMode ->
                    listOf(
                        SmartisanTitleBarAction(
                            R.drawable.titlebar_btn_delete_selector,
                            stringResource(R.string.dialog_delete_conform),
                            onDeleteSelected,
                            enabled = selectedCount > 0,
                        ),
                    )
                showRightActions ->
                    listOf(
                        SmartisanTitleBarAction(
                            R.drawable.standard_icon_refresh_selector,
                            stringResource(R.string.library_rescan_full),
                            onRefreshLibrary,
                            enabled = !libraryRefreshing,
                        ),
                    )
                else -> emptyList()
            },
    )
}

@Composable
private fun FolderRootPage(
    active: Boolean,
    directories: List<DirectoryEntry>,
    editMode: Boolean,
    selectedDirectoryKeys: Set<String>,
    onDirectoryClick: (DirectoryEntry) -> Unit,
    onDirectorySelectionChange: (String, Boolean) -> Unit,
    onDirectoryVisibilityChange: (DirectoryEntry, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val display =
        remember(directories) { filterDirectoryEntriesForDisplay(directories, editMode = true) }
    val expanded = editMode
    val visibleCount = if (editMode) display.size else display.count { !it.hidden }
    val checkboxBounds = remember { mutableMapOf<String, Rect>() }
    var listOrigin by remember { mutableStateOf(Offset.Zero) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Box(modifier.fillMaxSize().libraryTexture()) {
        if (!active) return@Box
        if (visibleCount == 0) {
            LibraryBlank(
                R.drawable.blank_folder,
                stringResource(R.string.no_folder),
                stringResource(R.string.show_folder),
            )
        } else {
            LazyColumn(
                state = list,
                modifier =
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { listOrigin = it.positionInRoot() }
                        .smartisanSlideSelection(
                            enabled = editMode,
                            itemAt = { point ->
                                list.layoutInfo.visibleItemsInfo
                                    .firstOrNull {
                                        point.y >= it.offset && point.y < it.offset + it.size
                                    }
                                    ?.index
                            },
                            keyAt = { display.getOrNull(it)?.key },
                            selectedKeys = selectedDirectoryKeys,
                            onSelectionChange = onDirectorySelectionChange,
                            canStart = { point ->
                                val index =
                                    list.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            point.y >= it.offset && point.y < it.offset + it.size
                                        }
                                        ?.index
                                val key = index?.let { display.getOrNull(it)?.key }
                                smartisanCheckboxHit(
                                    point + listOrigin,
                                    key?.let { checkboxBounds[it] },
                                    touchSlop,
                                )
                            },
                            scrollBy = { list.scrollBy(it) },
                            edgeItemAt = { top ->
                                list.layoutInfo.visibleItemsInfo
                                    .filter { it.index in display.indices && it.size > 0 }
                                    .let {
                                        if (top) it.firstOrNull()?.index else it.lastOrNull()?.index
                                    }
                            },
                        ),
            ) {
                itemsIndexed(display, key = { _, entry -> entry.key }) { index, entry ->
                    // 本行下面"连续收起"的隐藏行数：决定边界分界线叠几层（不封顶，见行内实现）。
                    // 编辑态 expanded=true，隐藏行全部展开，不存在叠层，直接给 0。
                    val hiddenRunBelow =
                        if (expanded) 0
                        else display.drop(index + 1).takeWhile { it.hidden }.size
                    FolderDirectoryRow(
                        entry,
                        editMode,
                        expanded,
                        entry.key in selectedDirectoryKeys,
                        showDivider = display.drop(index + 1).any { expanded || !it.hidden },
                        onClick = { onDirectoryClick(entry) },
                        onCheckboxBounds = { bounds ->
                            if (bounds == null) checkboxBounds.remove(entry.key)
                            else checkboxBounds[entry.key] = bounds
                        },
                        onVisibilityChange = { hidden ->
                            onDirectoryVisibilityChange(entry, hidden)
                        },
                        // [1.4.30·R137] 行尾避让接口：与歌曲页 SmartisanQuickBar 同一套思路——
                        // 有字母快滑条就把行尾图标整体左移出它的宽度，没有就是 0。
                        // 文件夹页当前没有字母列（字母列只在歌曲页、按名排序且 >30 首时出现），
                        // 所以这里恒传 0；参数化的目的是将来把这个行组件复用到歌曲页时不用改结构。
                        endInset = 0.dp,
                        hiddenRunBelow = hiddenRunBelow,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderDirectoryRow(
    entry: DirectoryEntry,
    editMode: Boolean,
    expanded: Boolean,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onCheckboxBounds: (Rect?) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
    /** 行尾要额外让出的宽度（右侧字母快滑条）。见 [FolderRootPage] 的说明。 */
    endInset: Dp = 0.dp,
    /** 本行下面紧邻的、已经收起（高度 0）的隐藏行数量，用于把分界线按叠层数加粗。 */
    hiddenRunBelow: Int = 0,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val rowHeight = dimensionResource(R.dimen.listview_item_height)
    val height by
        animateDpAsState(
            if (entry.hidden && !expanded) 0.dp else rowHeight,
            tween(200),
            label = "Hidden directory height",
        )
    val editFraction by
        animateFloatAsState(if (editMode) 1f else 0f, tween(200), label = "Directory edit controls")
    var pendingHidden by remember(entry.key) { mutableStateOf<Boolean?>(null) }
    val eyeTime = remember(entry.key) { Animatable(300f) }
    LaunchedEffect(entry.hidden) { if (pendingHidden == entry.hidden) pendingHidden = null }
    val displayedHidden = pendingHidden ?: entry.hidden
    val brightAlpha by
        animateFloatAsState(
            if ((editMode || expanded) && displayedHidden) .3f else 1f,
            tween(300),
            label = "Directory visibility",
        )
    // [1.4.33·验收] 分界线加粗：本行下面连续收起了 N 行，边界线就是 N 条标准线叠在一起。
    // 参考包是靠多条标准线叠在同一 y 上得到粗细，隐藏越多越粗、不封顶——用户明确要求
    // "完全忠实参考包、不封顶"，故删掉 1.4.30 的层数上限，隐藏越多越粗。
    val dividerBase = dimensionResource(R.dimen.listview_dividerHeight)
    val dividerExtra by
        animateDpAsState(
            targetValue = dividerBase * hiddenRunBelow.toFloat(),
            animationSpec = tween(200),
            label = "Hidden divider thickness",
        )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val checkbox =
        rememberSmartisanDrawablePainter(
            R.drawable.selector_check_box_red,
            checked = selected,
            pressed = pressed,
        )
    val checkboxWidth = with(LocalDensity.current) { checkbox.intrinsicSize.width.toDp() }
    val count = if (editMode || expanded) entry.totalCount else entry.visibleCount
    val countText = pluralStringResource(R.plurals.album_track_count, count, count)
    Box(
        Modifier.fillMaxWidth()
            .height(height)
            .clipToBounds()
            .then(if (height == 0.dp) Modifier.clearAndSetSemantics {} else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .requiredHeight(rowHeight)
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
            Box(
                Modifier.width(
                        (checkboxWidth + dimensionResource(R.dimen.check_box_margin_left)) *
                            editFraction
                    )
                    .clipToBounds(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (editFraction > 0) {
                    Image(
                        checkbox,
                        null,
                        Modifier.requiredWidth(checkboxWidth)
                            .smartisanCheckboxBounds(onCheckboxBounds)
                            .graphicsLayer { alpha = editFraction },
                    )
                }
            }
            Column(
                Modifier.weight(1f)
                    .padding(start = dimensionResource(R.dimen.listview_items_margin_left))
                    .graphicsLayer { alpha = brightAlpha }
            ) {
                BasicText(
                    entry.name,
                    style =
                        TextStyle(
                            color =
                                smartisanPressedTextColor(
                                    colorResource(R.color.setting_item_text_color),
                                    pressed,
                                ),
                            fontSize = smartisanTextSize(R.dimen.text_size_large),
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                BasicText(
                    "$countText  ${entry.displayPath}",
                    Modifier.padding(top = 3.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            Box(
                Modifier.padding(
                    end = dimensionResource(R.dimen.listview_items_margin_right) + endInset,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (editFraction < 1)
                    Image(
                        rememberSmartisanDrawablePainter(
                            R.drawable.arrow3_selector,
                            pressed = pressed,
                        ),
                        null,
                        Modifier.graphicsLayer {
                            alpha = 1 - editFraction
                            translationX = size.width * editFraction
                        },
                    )
                if (editFraction > 0) {
                    // [1.4.30·R137 B 方案] 隐藏入口回到行内（参考包 0.3.0 同款位置）。
                    // 与顶栏那版的三个关键差别：
                    //  ①不传 tint —— 顶栏 action 会被 StatefulIconTint 染色，图标本体颜色跟着状态跳；
                    //  ②不传 enabled —— enabled 翻转会让 painter 切到 disabled 图/透明度，
                    //    与逐帧眨眼叠在一起就是用户说的"闪一下"。点击闸门放 onClick 里；
                    //  ③滑入位移用 editFraction 联动，与勾选框、箭头的 200ms 完全同步。
                    val frame =
                        if (pendingHidden != null && eyeTime.value < 160f) {
                            val index = (eyeTime.value / 10).toInt().coerceIn(0, 15)
                            FolderEyeFrames[if (pendingHidden == true) index else 15 - index]
                        } else if (displayedHidden) R.drawable.eye_icon_0016
                        else R.drawable.eye_icon_0001
                    LibraryIconButton(
                        frame,
                        stringResource(R.string.hide_dir_content_description),
                        onClick = {
                            // 眨眼期间不接受第二次点击：一次点击对应一次状态跃迁，
                            // 否则连点会让帧序列从头重播、看起来像抽搐。
                            if (pendingHidden == null) {
                                val hidden = !displayedHidden
                                pendingHidden = hidden
                                Toast.makeText(
                                        context.applicationContext,
                                        resources.getString(
                                            if (hidden) R.string.hiden_dir else R.string.shown_dir,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                                onVisibilityChange(hidden)
                                scope.launch {
                                    eyeTime.snapTo(0f)
                                    eyeTime.animateTo(300f, tween(300, easing = LinearEasing))
                                }
                            }
                        },
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = editFraction
                                translationX = size.width * (1 - editFraction)
                            },
                    )
                }
            }
        }
        if (showDivider) {
            // [1.4.30·R137] 连续隐藏行收起后，边界线按"收起了几行"加粗（有上限）。
            // 参考包是靠多条 1px 标准线叠在同一 y 上意外得到的；我们显式给高度，
            // 粗细可控、跟收起动画同一条 animateDp 过渡（见 FolderRootPage 的调用处）。
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                LibraryDivider()
                if (dividerExtra > 0.dp) {
                    Spacer(
                        Modifier.fillMaxWidth()
                            .height(dividerExtra)
                            .background(
                                monetOutlineVariant()
                                    ?: colorResource(R.color.listview_divider_color),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun FolderDetailPage(
    active: Boolean,
    tracks: List<MediaItem>,
    browser: Player?,
    onTrackMoreClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    ImmersiveLocateEffect(list) {
        tracks.indexOfFirst { it.mediaId == ImmersiveScrollBus.targetMediaId }
    }
    val playback = rememberSongPlaybackState(browser)
    val entrance =
        rememberLibraryListEntrance(tracks, active) { list.layoutInfo.visibleItemsInfo.size }
    Column(modifier.fillMaxSize().libraryTexture()) {
        if (!active) return@Column
        // [1.4.24·R30 阴影被裁] LibraryTopCard 必须在外层：它的投影画在内容底边之外，
        // 被 AnimatedVisibility 包住就会被裁掉，文件夹页因此没有播放栏下沿阴影。
        LibraryTopCard {
            ImmersiveActionRowVisibility {
                LibraryPlayActions(
                    tracks.isNotEmpty(),
                    { browser.replaceQueueAndPlay(tracks) },
                    { browser.replaceQueueAndPlayShuffled(tracks) },
                    containerColor = colorResource(R.color.surface_card),
                )
            }
        }
        if (tracks.isEmpty()) {
            LibraryBlank(
                R.drawable.blank_song,
                stringResource(R.string.no_song),
                stringResource(R.string.show_song),
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(tracks, key = { index, item -> "${item.mediaId}:$index" }) {
                    index,
                    item ->
                    SmartisanSongRow(
                        item,
                        modifier =
                            Modifier.libraryListEntrance(entrance) {
                                index - list.firstVisibleItemIndex
                            },
                        onClick = { browser.replaceQueueAndPlay(tracks, index, keepShuffle = true) },
                        onMoreClick = onTrackMoreClick,
                        subtitle =
                            item.mediaMetadata.artist?.toString()
                                ?: item.mediaMetadata.subtitle?.toString()
                                ?: stringResource(R.string.unknown_artist),
                        durationText =
                            item.mediaMetadata.durationMs?.let(::libraryDuration).orEmpty(),
                        playback = playback,
                    )
                    LibraryDivider()
                }
                item(key = "folder-track-footer") {
                    LibraryFooter(R.plurals.track_count, tracks.size)
                }
            }
        }
    }
}

private val FolderEyeFrames =
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
