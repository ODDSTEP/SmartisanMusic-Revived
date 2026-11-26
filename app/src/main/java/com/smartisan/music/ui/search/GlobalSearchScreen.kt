package com.smartisan.music.ui.search

import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.theme.themedListRowBackground
import com.smartisan.music.ui.theme.themedAccent
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.components.smartisanPressedTextColor
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.search.SearchHistoryStore
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.await
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.songs.SongSortPreference
import com.smartisan.music.ui.songs.sortedForSongSort
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.artist.ArtistSummary
import com.smartisan.music.ui.components.GlobalPlaybackBar
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import com.smartisan.music.ui.components.hasAudioPermission
import com.smartisan.music.ui.components.loadArtworkThumbnail
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import kotlinx.coroutines.launch

private val SearchPageBackground: Color
    @Composable get() = colorResource(R.color.page_background)
private val SearchFieldTextColor: Color
    @Composable get() = colorResource(R.color.text_primary)
private val SearchSectionTitleColor: Color
    @Composable get() = colorResource(R.color.text_tertiary)
private val SearchDividerColor: Color
    @Composable get() = colorResource(R.color.divider_soft)
private val SearchSongTitleColor: Color
    @Composable get() = colorResource(R.color.text_primary)
private val SearchSongPlayingColor: Color
    @Composable get() = themedAccent(colorResource(R.color.playing_red))
private val SearchResultHighlightColor: Color
    @Composable get() = themedAccent(colorResource(R.color.highlight_red))
private val SearchSubtitleColor: Color
    @Composable get() = colorResource(R.color.text_tertiary)
private val SearchEmptyTextColor: Color
    @Composable get() = colorResource(R.color.text_placeholder)

private val SearchFieldTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            color = SearchFieldTextColor,
        )
private val SearchSectionTitleStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            color = SearchSongTitleColor,
        )
private val SearchPrimaryTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = SearchSongTitleColor,
        )
private val SearchSecondaryTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 13.sp,
            color = SearchSubtitleColor,
        )

private val SearchTopBarHeight = 50.dp
private val SearchFieldHeight = 32.dp
private val SearchTopBarItemSpacing = 12.dp
private val SearchCancelButtonSize = 36.dp
private val SearchCancelIconSize = 36.dp
private val SearchClearButtonSize = 30.dp
private val SearchClearIconSize = 30.dp
private val SearchFieldInnerEdgePadding = 6.dp
private val SearchLeftIconWidth = 24.dp
private val SearchLeftIconHeight = 30.dp
private val SearchTextStartPadding = 36.dp
private val SearchHistoryTopPadding = 19.dp
private val SearchSectionHorizontalPadding = 21.dp
private val SearchHistoryRowSpacing = 10.dp
private val SearchHistoryChipHeight = 30.dp
// [R146·2026-11-26] 用户 11-25 23:55 推翻 1.4.29 的"只统一颜色不改尺寸"：
// "不仅颜色统一，尺寸也要统一" —— 搜索页分组头对齐歌曲页 SectionLetterHeader（26dp / 13.5sp）。
private val SearchSectionHeaderHeight = 26.dp
private val SearchSectionHeaderStartPadding = 11.dp
private val SearchResultRowHeight = 60.dp
private val SearchResultArtworkFrameWidth = 48.dp
private val SearchResultArtworkSize = 38.dp
private val SearchResultActionWidth = 34.dp
private val SearchResultLocateIconSize = 30.dp
private val SearchPlaybackBarReservedHeight = 61.dp
private val SearchTopHorizontalPadding = 6.dp
private val SearchNoResultTopPadding = 85.dp
private val SearchNoResultArtworkSize = 140.dp
private val SearchArtworkDecodeSize = Size(128, 128)

private enum class SearchEntityAction {
    /** 歌曲行右侧动作：自绘瞄准图标，点击定位到歌曲页该歌曲位置（1.2.16 定案，替代原"更多"菜单）。 */
    Locate,
}
// [1.4.30·R144] 原来的 `Source`（灰圆白勾）**已删除**。
// 它是"这条结果来自本地"的静态标识，本身没有点击行为，但长成了按钮的样子，
// 用户点了没反应（原话"点了没反应，感觉是残留"）。已拍板：删掉，不留"看着能点、
// 点了没事发生"的元素。艺术家行/专辑行右侧现在是空的，整行点击仍正常进入对应页面。

@Composable
fun GlobalSearchScreen(
    query: String,
    modifier: Modifier = Modifier,
    libraryRefreshVersion: Int = 0,
    /** 1.2.26：用户手动隐藏的歌曲。旧版搜索页没接这个参数，隐藏过的歌仍能被搜到、能播、还会混进队列。 */
    hiddenMediaIds: Set<String> = emptySet(),
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenPlayback: () -> Unit,
    onTrackLocateClick: (MediaItem) -> Unit = {},
    onAlbumClick: (String, String) -> Unit,
    onArtistClick: (String, String) -> Unit,
    artistSettings: ArtistSettings = ArtistSettings(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackBrowser = LocalPlaybackBrowser.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val historyStore =
        remember(context.applicationContext) {
            SearchHistoryStore(context.applicationContext)
        }
    val exclusionsStore =
        remember(context.applicationContext) {
            LibraryExclusionsStore(context.applicationContext)
        }
    val history by historyStore.history.collectAsState(initial = emptyList())
    val libraryRevision by exclusionsStore.revision.collectAsState(initial = 0)
    val hasPermission = hasAudioPermission(context)
    var permissionVersion by remember { mutableIntStateOf(0) }
    var songs by remember(playbackBrowser) { mutableStateOf(emptyList<MediaItem>()) }
    // 1.2.25：搜索点歌要以「歌曲页当前顺序」的全库列表为队列——旧版直接用媒体库索引顺序，
    // 和歌曲页排序不一致，用户看到的就是「从搜索播完，播放列表内容/顺序变了」。
    val librarySortSelection = remember { SongSortPreference.load(context) }
    val playCountRepository =
        remember { PlaybackStatsRepository.getInstance(context.applicationContext) }
    val livePlayCounts by playCountRepository.statsFlow.collectAsState(initial = emptyMap())
    val orderedSongs =
        remember(songs, librarySortSelection, livePlayCounts) {
            songs.sortedForSongSort(librarySortSelection) { item ->
                livePlayCounts[item.mediaId] ?: 0L
            }
        }
    var currentMediaId by
        remember(playbackBrowser) {
            mutableStateOf(playbackBrowser?.currentMediaItem?.mediaId)
        }
    val dismissSearch by
        rememberUpdatedState(
            newValue = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onDismiss()
            }
        )
    val clearSearchInputFocus by
        rememberUpdatedState(
            newValue = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            }
        )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionVersion += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playbackBrowser) {
        val browser = playbackBrowser ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    currentMediaId = player.currentMediaItem?.mediaId
                }
            }
        browser.addListener(listener)
        currentMediaId = browser.currentMediaItem?.mediaId
        onDispose {
            browser.removeListener(listener)
        }
    }

    LaunchedEffect(
        playbackBrowser,
        permissionVersion,
        libraryRevision,
        libraryRefreshVersion,
        hasPermission,
        hiddenMediaIds,
    ) {
        val browser =
            playbackBrowser
                ?: run {
                    songs = emptyList()
                    return@LaunchedEffect
                }
        if (!hasPermission) {
            songs = emptyList()
            return@LaunchedEffect
        }
        val rootResult = browser.getLibraryRoot(null).await(context)
        val rootItem =
            rootResult.value
                ?: run {
                    songs = emptyList()
                    return@LaunchedEffect
                }
        val childrenResult =
            browser.getChildren(rootItem.mediaId, 0, Int.MAX_VALUE, null).await(context)
        // 与歌曲页/专辑页保持同一口径：手动隐藏的歌曲不参与搜索、不进队列
        songs = childrenResult.value?.toList().orEmpty()
            .filterNot { item -> item.mediaId in hiddenMediaIds }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val unknownAlbumTitle = stringResource(R.string.unknown_album)
    val unknownArtistTitle = stringResource(R.string.unknown_artist)
    val multipleArtistsTitle = stringResource(R.string.many_artist)
    val results =
        remember(
            query,
            songs,
            unknownAlbumTitle,
            unknownArtistTitle,
            multipleArtistsTitle,
            artistSettings,
        ) {
            buildSearchResults(
                query = query,
                songs = songs,
                unknownAlbumTitle = unknownAlbumTitle,
                unknownArtistTitle = unknownArtistTitle,
                multipleArtistsTitle = multipleArtistsTitle,
                artistSettings = artistSettings,
            )
        }
    val showPlaybackBar = currentMediaId != null

    BackHandler(onBack = dismissSearch)

    Box(modifier = modifier.fillMaxSize().background(SearchPageBackground).imePadding().libraryTexture()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchTopBar(
                query = query,
                focusRequester = focusRequester,
                onQueryChange = onQueryChange,
                onSearch = {
                    scope.launch {
                        historyStore.record(query)
                    }
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onDismiss = dismissSearch,
            )
            if (query.isBlank()) {
                SearchHistoryPage(
                    history = history,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onHistoryClick = { entry ->
                        onQueryChange(entry)
                        scope.launch {
                            historyStore.record(entry)
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            historyStore.clear()
                        }
                    },
                )
            } else {
                SearchResultsPage(
                    results = results,
                    currentMediaId = currentMediaId,
                    showPlaybackBar = showPlaybackBar,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSongClick = { item ->
                        // 1.2.24：搜索结果页只展示前 2 首「建议」，若把 matched 列表当队列，
                        // 只命中 1 首时队列就只剩 1 首，点播后无法切上/下曲。
                        // 1.2.25：改为「与歌曲页完全同序的全库列表」，定位到所点歌曲；
                        // 该歌不在全库（极端情况）才回退到匹配列表。
                        val libraryIndex =
                            orderedSongs.indexOfFirst { it.mediaId == item.mediaId }
                        val queue: List<MediaItem>
                        val startIndex: Int
                        if (libraryIndex >= 0) {
                            queue = orderedSongs
                            startIndex = libraryIndex
                        } else {
                            queue = results.songs
                            startIndex = results.songs.indexOfFirst { it.mediaId == item.mediaId }
                        }
                        if (startIndex >= 0 && queue.isNotEmpty()) {
                            // 点单曲保留当前「随机播放」状态（旧版这里会把随机关掉）
                            playbackBrowser.replaceQueueAndPlay(
                                mediaItems = queue,
                                startIndex = startIndex,
                                keepShuffle = true,
                            )
                            scope.launch {
                                historyStore.record(query)
                            }
                        }
                        // 点歌后立刻收起输入法并清除搜索框焦点：否则焦点残留在输入框上，
                        // 进大播放页后键盘仍挂着，甚至回前台还会自动弹出键盘并选中文字
                        clearSearchInputFocus()
                    },
                    onSongLocateClick = { item ->
                        clearSearchInputFocus()
                        onTrackLocateClick(item)
                    },
                    onAlbumClick = { album ->
                        scope.launch {
                            historyStore.record(query)
                        }
                        clearSearchInputFocus()
                        onAlbumClick(album.id, album.title)
                    },
                    onArtistClick = { artist ->
                        scope.launch {
                            historyStore.record(query)
                        }
                        clearSearchInputFocus()
                        onArtistClick(artist.id, artist.name)
                    },
                )
            }
        }
        if (showPlaybackBar) {
            GlobalPlaybackBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenPlayback = {
                    // 进入大播放页前收起输入法、清掉搜索框焦点，杜绝键盘残留
                    clearSearchInputFocus()
                    onOpenPlayback()
                },
            )
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val topInset =
        with(density) {
            WindowInsets.safeDrawing.getTop(this).toDp()
        }
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(SearchTopBarHeight + topInset)
                .background(colorResource(R.color.title_bar_background))
                .zIndex(1f)
    ) {
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .padding(top = topInset)
                    .padding(horizontal = SearchTopHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SearchTopBarItemSpacing),
        ) {
            SearchField(
                value = query,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
                onValueChange = onQueryChange,
                onSearch = onSearch,
            )
            SearchCancelButton(onDismiss = onDismiss)
        }
        TitleBarShadow(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .offset(y = shadowHeight)
                    .fillMaxWidth()
                    .height(shadowHeight)
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = SearchFieldTextStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSearch() }),
        modifier = modifier.height(SearchFieldHeight).focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            val clearInteractionSource = remember { MutableInteractionSource() }
            val clearPressed by clearInteractionSource.collectSmartisanPressedAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                SmartisanDrawableBackground(
                    drawableRes = R.drawable.search_field,
                    modifier = Modifier.matchParentSize(),
                )
                Image(
                    painter = painterResource(R.drawable.search_bar_left_icon),
                    contentDescription = null,
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .padding(start = SearchFieldInnerEdgePadding)
                            .width(SearchLeftIconWidth)
                            .height(SearchLeftIconHeight),
                )
                Box(
                    modifier =
                        Modifier.matchParentSize()
                            .padding(
                                start = SearchTextStartPadding,
                                end = if (value.isNotEmpty()) SearchClearButtonSize else 12.dp,
                            ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Box(
                        modifier =
                            Modifier.size(SearchClearButtonSize)
                                .align(Alignment.CenterEnd)
                                .clickable(
                                    interactionSource = clearInteractionSource,
                                    indication = null,
                                    onClick = { onValueChange("") },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter =
                                painterResource(
                                    if (clearPressed) {
                                        R.drawable.text_clear_btn_pressed
                                    } else {
                                        R.drawable.text_clear_btn
                                    }
                                ),
                            contentDescription = stringResource(R.string.clear_search_text),
                            modifier = Modifier.size(SearchClearIconSize),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchCancelButton(onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.size(SearchCancelButtonSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter =
                painterResource(
                    if (pressed) {
                        R.drawable.standard_icon_cancel_pressed
                    } else {
                        R.drawable.standard_icon_cancel
                    }
                ),
            contentDescription = stringResource(R.string.cancel),
            modifier = Modifier.size(SearchCancelIconSize),
        )
    }
}

@Composable
private fun SearchHistoryPage(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().libraryTexture())
        return
    }

    Box(modifier = modifier.fillMaxSize().libraryTexture()) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = SearchSectionHorizontalPadding,
                        top = SearchHistoryTopPadding,
                        end = 20.dp,
                    )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.search_history),
                    style = SearchSectionTitleStyle,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Image(
                    painter = painterResource(R.drawable.search_clear),
                    contentDescription = stringResource(R.string.clear_history),
                    modifier =
                        Modifier.size(20.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClearHistory,
                            ),
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(SearchHistoryRowSpacing),
                verticalArrangement = Arrangement.spacedBy(SearchHistoryRowSpacing),
            ) {
                history.forEach { entry ->
                    SearchHistoryChip(
                        text = entry,
                        onClick = { onHistoryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsPage(
    results: SearchResults,
    currentMediaId: String?,
    showPlaybackBar: Boolean,
    onSongClick: (MediaItem) -> Unit,
    onSongLocateClick: (MediaItem) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().libraryTexture()) {
        if (!results.hasResults) {
            SearchNoResultState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        bottom =
                            if (showPlaybackBar) SearchPlaybackBarReservedHeight + 16.dp else 16.dp
                    ),
            ) {
                appendSuggestedResults(
                    songs = results.songs.take(2),
                    currentMediaId = currentMediaId,
                    onSongClick = onSongClick,
                    onSongLocateClick = onSongLocateClick,
                )
                appendAlbumResults(
                    albums = results.albums,
                    onAlbumClick = onAlbumClick,
                )
                appendArtistResults(
                    artists = results.artists,
                    onArtistClick = onArtistClick,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendSuggestedResults(
    songs: List<MediaItem>,
    currentMediaId: String?,
    onSongClick: (MediaItem) -> Unit,
    onSongLocateClick: (MediaItem) -> Unit,
) {
    if (songs.isEmpty()) return
    item(key = "suggested-header") { SearchSectionHeader(title = R.string.suggestion) }
    items(
        items = songs,
        key = { item -> "suggested-${item.mediaId}" },
    ) { item ->
        SearchSongRow(
            mediaItem = item,
            selected = item.mediaId == currentMediaId,
            onClick = { onSongClick(item) },
            onLocateClick = { onSongLocateClick(item) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendAlbumResults(
    albums: List<AlbumSummary>,
    onAlbumClick: (AlbumSummary) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "albums-header") { SearchSectionHeader(title = R.string.search_tab_albums) }
    items(
        items = albums,
        key = { album -> "album-${album.id}" },
    ) { album ->
        SearchEntityRow(
            title = album.title,
            subtitle = album.artist,
            representative = album.representative,
            titleColor = SearchResultHighlightColor,
            subtitleColor = SearchResultHighlightColor,
            action = null,
            onClick = { onAlbumClick(album) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendArtistResults(
    artists: List<ArtistSummary>,
    onArtistClick: (ArtistSummary) -> Unit,
) {
    if (artists.isEmpty()) return
    item(key = "artists-header") { SearchSectionHeader(title = R.string.search_tab_artists) }
    items(
        items = artists,
        key = { artist -> "artist-${artist.id}" },
    ) { artist ->
        SearchEntityRow(
            title = artist.name,
            subtitle = null,
            representative = artist.representative,
            titleColor = SearchResultHighlightColor,
            action = null,
            onClick = { onArtistClick(artist) },
        )
    }
}

@Composable
private fun SearchSectionHeader(title: Int) {
    // [1.4.29·R121/R123] 分组头只统一颜色，不改尺寸（保持 45dp / 15sp）。
    // 分隔栏莫奈化开启 → 铺 surfaceContainer 色块；关闭 → 原厂图，零改动。
    val containerColor = com.smartisan.music.ui.theme.monetSurfaceContainer()
    val letterColor =
        com.smartisan.music.ui.theme.monetSeparatorLetterColor(
            containerColor ?: SearchPageBackground
        )
    Box(modifier = Modifier.fillMaxWidth().height(SearchSectionHeaderHeight)) {
        if (containerColor != null) {
            Box(Modifier.matchParentSize().background(containerColor))
        } else {
            SmartisanDrawableBackground(
                drawableRes = R.drawable.home_recommend_title_noline_bg,
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(
            text = stringResource(title),
            style = TextStyle(fontSize = 13.5.sp, color = letterColor ?: SearchSectionTitleColor),
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .padding(start = SearchSectionHeaderStartPadding),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SearchDividerColor)
        )
    }
}

@Composable
private fun SearchSongRow(
    mediaItem: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLocateClick: () -> Unit = {},
) {
    SearchEntityRow(
        title =
            mediaItem.mediaMetadata.title?.toString()
                ?: mediaItem.mediaMetadata.displayTitle?.toString()
                ?: stringResource(R.string.unknown_song_title),
        subtitle =
            mediaItem.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
        representative = mediaItem,
        titleColor = if (selected) SearchSongPlayingColor else SearchSongTitleColor,
        onClick = onClick,
        onActionClick = onLocateClick,
    )
}

@Composable
private fun SearchEntityRow(
    title: String,
    subtitle: String?,
    representative: MediaItem,
    onClick: () -> Unit,
    titleColor: Color = SearchSongTitleColor,
    subtitleColor: Color = SearchSubtitleColor,
    action: SearchEntityAction? = SearchEntityAction.Locate,
    // 非空时右侧按钮自己消费点击（如歌曲行的"定位到歌曲页"），不再触发行点击（播放）
    onActionClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()
    val actionInteractionSource = remember { MutableInteractionSource() }
    val actionPressed by actionInteractionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(SearchResultRowHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
    ) {
        // [R143·2026-11-26] 行背景接入莫奈：默认彩色逐字保持原 PNG；主题/莫奈下走
        // themedListRowBackground（白底 + 莫奈渐变 + 按下主题色），不再用不透明 PNG
        // 把页面级 libraryTexture 整块盖住——这就是"开了全莫奈化这里还是死白"的原因。
        if (currentThemePalette() == null) {
            SmartisanDrawableBackground(
                drawableRes =
                    if (pressed) R.drawable.list_item_bgwithoutphoto_down else R.color.surface_card,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // [1.4.32·验收图1] 补回原 PNG 自带的分隔线（主题态纯色 drawRect 没有）。
            Box(Modifier.matchParentSize().themedListRowBackground(pressed = pressed, drawDivider = true))
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.width(SearchResultArtworkFrameWidth)
                        .height(SearchResultRowHeight)
                        .padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SearchArtwork(
                    mediaItem = representative,
                    modifier = Modifier.size(SearchResultArtworkSize),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = SearchPrimaryTextStyle.copy(color = smartisanPressedTextColor(titleColor, pressed)),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = SearchSecondaryTextStyle.copy(color = smartisanPressedTextColor(subtitleColor, pressed)),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            // action == null：这一行右侧没有动作（R144 删掉来源标识后的艺术家行/专辑行），
            // 连占位 Box 都不画——否则会白留一条右侧空隙，标题看起来莫名偏左。
            if (action == null) return@Box
            val iconRes =
                when (action) {
                    SearchEntityAction.Locate ->
                        if (pressed || actionPressed) {
                            R.drawable.search_row_locate_white
                        } else {
                            R.drawable.search_row_locate
                        }
                    null -> return@Box
                }
            Box(
                modifier =
                    Modifier.width(SearchResultActionWidth).height(SearchResultRowHeight).let {
                        if (onActionClick != null) {
                            // 独立 clickable：消费整个右侧区域的点击，避免落回整行的"点击播放"
                            it.clickable(
                                interactionSource = actionInteractionSource,
                                indication = null,
                                onClick = onActionClick,
                            )
                        } else {
                            it
                        }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    // action 只剩 Locate 一种（Source 已按 R144 删除），尺寸直接给定位图标。
                    modifier = Modifier.size(SearchResultLocateIconSize),
                )
            }
        }
    }
    // [R145] 按压态不画行末分隔线：浅色 divider_soft 压在暗红高亮底上会变成一条"白线"。
    if (!pressed) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SearchDividerColor))
    }
}

@Composable
private fun SearchHistoryChip(
    text: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.height(SearchHistoryChipHeight)
                .defaultMinSize(minWidth = 48.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        SmartisanDrawableBackground(
            drawableRes =
                if (pressed) R.drawable.search_badge_grey_p else R.drawable.search_badge_grey,
            modifier = Modifier.matchParentSize(),
        )
        Text(
            text = text,
            style =
                TextStyle(
                    color = colorResource(R.color.text_tertiary),
                    fontSize = 13.5.sp,
                ),
            modifier = Modifier.padding(horizontal = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun SearchNoResultState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .libraryTexture()
                .padding(top = SearchNoResultTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.blank_search),
            contentDescription = null,
            modifier = Modifier.size(SearchNoResultArtworkSize),
        )
        Text(
            text = stringResource(R.string.search_no_result),
            style = TextStyle(fontSize = 23.sp, color = SearchEmptyTextColor),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(top = 25.dp),
        )
    }
}

@Composable
private fun SearchArtwork(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkRequestKey = mediaItem.artworkRequestKey()
    val artwork by
        produceState<ImageBitmap?>(
            initialValue = null,
            artworkRequestKey,
        ) {
            value = loadArtworkThumbnail(context, mediaItem, SearchArtworkDecodeSize)
        }

    if (artwork != null) {
        Box(modifier = modifier) {
            Image(
                bitmap = artwork!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.mask_albumcover_list),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
        }
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.noalbumcover_120),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.mask_albumcover_list),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
