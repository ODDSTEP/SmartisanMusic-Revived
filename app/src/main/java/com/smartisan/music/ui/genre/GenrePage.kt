package com.smartisan.music.ui.genre

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.songs.AlphabetQuickBar
import com.smartisan.music.data.genre.GenreTagRepository
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.components.smartisanVerticalScrollbar
import com.smartisan.music.ui.folder.FolderDetailPage
import com.smartisan.music.ui.library.LibraryDivider
import com.smartisan.music.ui.library.LibraryFooter
import com.smartisan.music.ui.library.LibrarySummaryRow
import com.smartisan.music.ui.library.libraryListEntrance
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.library.rememberLibraryListEntrance
import com.smartisan.music.ui.shell.PageStackTransition
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.ui.shell.titlebar.TitleBarTransition

@Composable
internal fun GenrePage(
    active: Boolean,
    mediaItems: List<MediaItem>,
    hiddenMediaIds: Set<String>,
    libraryLoaded: Boolean,
    onClose: (() -> Unit)?,
    onTrackMoreClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val browser = LocalPlaybackBrowser.current
    val repository =
        remember(context.applicationContext) {
            GenreTagRepository(context.applicationContext)
        }
    val genreTitle = stringResource(R.string.tab_style)
    val unknownGenreTitle = stringResource(R.string.unknown_style)
    val visibleItems =
        remember(mediaItems, hiddenMediaIds) {
            mediaItems.filterNot { it.mediaId in hiddenMediaIds }
        }
    var genreMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var genreTagsLoaded by remember { mutableStateOf(false) }
    var selectedGenreId by remember { mutableStateOf<String?>(null) }
    val genres =
        remember(visibleItems, genreMap, genreTagsLoaded, unknownGenreTitle) {
            if (genreTagsLoaded) {
                buildGenreSummaries(
                    mediaItems = visibleItems,
                    genreMap = genreMap,
                    unknownGenreTitle = unknownGenreTitle,
                )
            } else {
                emptyList()
            }
        }
    val selectedGenre = genres.firstOrNull { it.id == selectedGenreId }

    LaunchedEffect(active, libraryLoaded, visibleItems) {
        if (!active) {
            return@LaunchedEffect
        }
        if (!libraryLoaded) {
            genreTagsLoaded = false
            genreMap = emptyMap()
            selectedGenreId = null
            return@LaunchedEffect
        }
        genreTagsLoaded = false
        genreMap = repository.loadGenres(visibleItems)
        genreTagsLoaded = true
    }
    LaunchedEffect(selectedGenreId, genres) {
        if (selectedGenreId != null && selectedGenre == null && genreTagsLoaded) {
            selectedGenreId = null
        }
    }

    BackHandler(enabled = active && selectedGenre != null) {
        selectedGenreId = null
    }
    if (onClose != null) {
        BackHandler(enabled = active && selectedGenre == null) {
            onClose()
        }
    }

    val titleAreaHeight =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            dimensionResource(R.dimen.title_bar_height)
    val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)

    Box(modifier = modifier.fillMaxSize().libraryTexture()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TitleBarTransition(
                secondaryKey = selectedGenre,
                modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                label = "genre title stack",
                primaryContent = {
                    GenreTitleBar(
                        modifier = Modifier.fillMaxSize(),
                        title = genreTitle,
                        onBack = onClose,
                        onSearchClick = onSearchClick,
                    )
                },
                secondaryContent = { genre ->
                    GenreTitleBar(
                        modifier = Modifier.fillMaxSize(),
                        title = genre.name,
                        onBack = { selectedGenreId = null },
                        onSearchClick = null,
                    )
                },
            )
            PageStackTransition(
                secondaryKey = selectedGenre,
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = "genre detail stack",
                primaryContent = {
                    GenreRootPage(
                        active = active,
                        genres = genres,
                        libraryLoaded = libraryLoaded && genreTagsLoaded,
                        onGenreSelected = { selectedGenreId = it.id },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                secondaryContent = { genre ->
                    FolderDetailPage(
                        active = active && selectedGenre == genre,
                        tracks = genre.songs,
                        browser = browser,
                        onTrackMoreClick = onTrackMoreClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
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
}

@Composable
private fun GenreTitleBar(
    title: String,
    onBack: (() -> Unit)?,
    onSearchClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SmartisanTitleBar(
        title,
        modifier,
        showShadow = false,
        navigationIcon =
            onBack?.let {
                SmartisanTitleBarAction(
                    R.drawable.standard_icon_back_selector,
                    stringResource(R.string.back),
                    it,
                )
            },
        action =
            onSearchClick?.let {
                SmartisanTitleBarAction(
                    R.drawable.search_btn_selector,
                    stringResource(R.string.tab_local_search),
                    it,
                )
            },
    )
}

@Composable
private fun GenreRootPage(
    active: Boolean,
    genres: List<GenreSummary>,
    libraryLoaded: Boolean,
    onGenreSelected: (GenreSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val entrance =
        rememberLibraryListEntrance(genres, active) { list.layoutInfo.visibleItemsInfo.size }
    // 风格页此前用 Box + fillMaxSize() 包裹 LazyColumn，且列表项挂了 graphicsLayer（入场动画）。
    // 这种组合会让 Compose 在首帧把 LazyColumn 的滚动位置误算到最底部（其它页面如歌曲页、
    // 喜爱的歌、播放列表均用 Column + weight(1f) 包裹，没有此问题）。改用同样稳定的结构，
    // 并在内容就绪后强制滚回顶部，确保无论从“更多”还是底部导航栏打开都是顶视图。
    LaunchedEffect(active, genres) {
        if (active && genres.isNotEmpty()) list.scrollToItem(0)
    }
    Box(modifier.fillMaxSize().libraryTexture()) {
        Column(Modifier.fillMaxSize()) {
        if (!active) return@Column
        if (libraryLoaded && genres.isEmpty()) {
            com.smartisan.music.ui.components.SmartisanEmptyHint(
                R.drawable.blank_style,
                stringResource(R.string.no_style),
                subtitle = stringResource(R.string.show_style),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = list,
                modifier =
                    Modifier.fillMaxWidth().weight(1f).smartisanVerticalScrollbar(list),
            ) {
                itemsIndexed(genres, key = { _, genre -> genre.id }) { index, genre ->
                    LibrarySummaryRow(
                        genre.name,
                        pluralStringResource(
                            R.plurals.track_count,
                            genre.trackCount,
                            genre.trackCount,
                        ),
                        modifier =
                            Modifier.libraryListEntrance(entrance) {
                                index - list.firstVisibleItemIndex
                            },
                        onClick = { onGenreSelected(genre) },
                        primarySizeRes = R.dimen.text_size_large,
                        secondarySizeRes = R.dimen.text_size_micro,
                        lineSpacing = 3.dp,
                        trailingContent = { pressed ->
                            Image(
                                rememberSmartisanDrawablePainter(R.drawable.arrow3_selector, pressed = pressed),
                                null,
                                Modifier.padding(
                                        end = dimensionResource(R.dimen.listview_items_margin_right)
                                    )
                                    .width(dimensionResource(R.dimen.playlist_right_action_width)),
                            )
                        },
                    )
                    LibraryDivider()
                }
                item(key = "genre-footer") { LibraryFooter(R.plurals.genre_count, genres.size) }
            }
        }
            }
        // [1.4.28] 删掉右侧 A–Z 字母条。
        // 旧实现用 AlphabetQuickBar(labels=...) 把中文风格名的首字符映射到 A–Z，
        // 但中文首字符永远不属于 A–Z，全部落进 "#"；列表本身也没有按首字母分组，
        // 点任何字母都不跳。它是纯装饰，删除比留着骗人好。
    }

}
