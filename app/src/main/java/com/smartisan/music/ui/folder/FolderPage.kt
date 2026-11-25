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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
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
        // 批量隐藏/显示选中文件夹：全部未隐藏才隐藏；全部已隐藏或混合（既有显示又有隐藏）→ 统一恢复显示
        val toggleHideSelected = {
            val entries = allDirectories.filter { it.key in selectedDirectoryKeys }
            if (entries.isNotEmpty()) {
                val hidden = entries.none { it.hidden }
                setDirectoriesHidden(entries, hidden)
                Toast.makeText(
                    context.applicationContext,
                    context.getString(if (hidden) R.string.hiden_dir else R.string.shown_dir),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        // 选中文件夹中是否包含已隐藏项（含任意隐藏项 → 标题栏闭眼态，与歌曲页规则一致）
        val selectedDirectoriesContainHidden =
            allDirectories.any { it.key in selectedDirectoryKeys && it.hidden }
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
                        selectedContainHidden = selectedDirectoriesContainHidden,
                        onBack = handleBack,
                        showRootBack = onClose != null,
                        onEnterEdit = enterEdit,
                        onDeleteSelected = deleteSelected,
                        onToggleHideSelected = toggleHideSelected,
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
                        onToggleHideSelected = toggleHideSelected,
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
    /** 编辑模式下，当前选中文件夹中是否包含已隐藏项（含任意隐藏项 → 闭眼态） */
    selectedContainHidden: Boolean = false,
    onBack: () -> Unit,
    showRootBack: Boolean,
    onEnterEdit: () -> Unit,
    onDeleteSelected: () -> Unit,
    onToggleHideSelected: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    // 状态眼睛：全部未隐藏（含无选中）→ 睁眼，点击后全部隐藏（动画：睁→闭）；
    // 全部已隐藏或混合 → 闭眼，点击后统一恢复显示（动画：闭→睁）。与歌曲页编辑模式同一套。
    var pendingEyeHidden by remember { mutableStateOf<Boolean?>(null) }
    // 点击后立即记录眼睛的“目标静止态”，不再依赖滞后更新的 selectedContainHidden：
    // 文件夹隐藏要等 setDirectoriesHidden 的 delay 之后才改写 exclusions，动画结束瞬间仍是旧值，
    // 眼睛就会先播完动画再闪回旧态。本地态在动画期间保持稳定，动画结束后无缝停在正确的睁/闭眼。
    var localEyeHidden by remember { mutableStateOf(selectedContainHidden) }
    // 只在真实状态变化时同步（动画结束时置 pendingEyeHidden=null 不应触发本副作用：
    // 那一刻 setDirectoriesHidden 的 delay 还没走完，selectedContainHidden 仍是旧值，
    // 同步会把眼睛闪回旧态——这正是“播完动画再闪几下”的根源）
    LaunchedEffect(selectedContainHidden) {
        if (pendingEyeHidden == null) localEyeHidden = selectedContainHidden
    }
    val eyeTime = remember { Animatable(300f) }
    LaunchedEffect(pendingEyeHidden) {
        if (pendingEyeHidden != null) {
            eyeTime.snapTo(0f)
            eyeTime.animateTo(300f, tween(300, easing = LinearEasing))
            pendingEyeHidden = null
        }
    }
    val eyeFrame =
        if (pendingEyeHidden != null && eyeTime.value < 160f) {
            val index = (eyeTime.value / 10).toInt().coerceIn(0, 15)
            FolderEyeFrames[if (pendingEyeHidden == true) index else 15 - index]
        } else if (localEyeHidden) R.drawable.eye_icon_0016
        else R.drawable.eye_icon_0001
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
                        // 隐藏/显示选中文件夹（原行内眼睛移到这里，占原「重新扫描」的位置）：
                        // 图标随选中内容的三态规则切换，并带眨眼过渡动画
                        SmartisanTitleBarAction(
                            eyeFrame,
                            stringResource(R.string.show_hide_songs),
                            {
                                if (pendingEyeHidden == null) {
                                    pendingEyeHidden = !selectedContainHidden
                                    localEyeHidden = !selectedContainHidden
                                    onToggleHideSelected()
                                }
                            },
                            enabled = selectedCount > 0 && pendingEyeHidden == null,
                            tint = StatefulIconTint,
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
                Modifier.padding(end = dimensionResource(R.dimen.listview_items_margin_right)),
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
                    // 行内眼睛已移至标题栏（编辑模式右上角、删除图标旁），
                    // 这里保留空位与箭头动画，行布局保持稳定。
                }
            }
        }
        if (showDivider) LibraryDivider(Modifier.align(Alignment.BottomCenter))
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
