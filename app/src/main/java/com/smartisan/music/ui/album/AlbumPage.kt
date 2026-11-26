package com.smartisan.music.ui.album

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.ui.shell.ImmersiveActionRowVisibility
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.components.SectionLetterRow
import com.smartisan.music.ui.components.buildSectionLetterRows
import com.smartisan.music.ui.library.LibraryAlbumEntry
import com.smartisan.music.ui.library.LibraryFooter
import com.smartisan.music.ui.library.LibraryPlayActions
import com.smartisan.music.ui.library.LibraryTopCard
import com.smartisan.music.ui.library.SmartisanAlbumCollection
import com.smartisan.music.ui.shell.PageStackTransition

@Composable
internal fun AlbumPage(
    mediaItems: List<MediaItem>,
    active: Boolean,
    viewMode: AlbumViewMode,
    editMode: Boolean,
    selectedAlbumId: String?,
    selectedAlbumIds: Set<String>,
    modifier: Modifier = Modifier,
    hiddenMediaIds: Set<String>,
    /** [1.4.30·R138] 被隐藏的专辑卡（键为 albumGroupingId）。只影响专辑总览的展示。 */
    hiddenAlbumIds: Set<String> = emptySet(),
    /** [1.4.30·R138] 专辑卡眼睛点击后回写：true=隐藏，false=恢复显示。 */
    onAlbumVisibilityChange: (String, Boolean) -> Unit = { _, _ -> },
    /** [1.4.33·验收第9点] 专辑列表右滑删除整张专辑的回调。 */
    onRequestAlbumDelete: (String) -> Unit,
    onAlbumSelected: (String, String) -> Unit,
    onAlbumSelectionChange: (String, Boolean) -> Unit,
    onRequestAddToPlaylist: (List<MediaItem>) -> Unit,
    onRequestAddToQueue: (List<MediaItem>) -> Unit,
    onTrackMoreClick: (MediaItem) -> Unit,
    artistSettings: ArtistSettings = ArtistSettings(),
) {
    val browser = LocalPlaybackBrowser.current
    val visibleSongs =
        remember(mediaItems, hiddenMediaIds) {
            mediaItems.filterNot { mediaItem -> mediaItem.mediaId in hiddenMediaIds }
        }
    val unknownAlbumTitle = stringResource(R.string.unknown_album)
    val multipleArtistsTitle = stringResource(R.string.many_artist)
    val albums =
        remember(visibleSongs, unknownAlbumTitle, multipleArtistsTitle, artistSettings) {
            buildAlbumSummaries(
                mediaItems = visibleSongs,
                unknownAlbumTitle = unknownAlbumTitle,
                multipleArtistsTitle = multipleArtistsTitle,
                artistSettings = artistSettings,
            )
        }
    val selectedAlbum =
        remember(albums, selectedAlbumId) {
            albums.firstOrNull { album -> album.id == selectedAlbumId }
        }
    var currentMediaId by
        remember(browser) {
            mutableStateOf(browser?.currentMediaItem?.mediaId)
        }

    DisposableEffect(browser) {
        val playbackBrowser = browser ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    currentMediaId = player.currentMediaItem?.mediaId
                }
            }
        playbackBrowser.addListener(listener)
        onDispose {
            playbackBrowser.removeListener(listener)
        }
    }

    PageStackTransition(
        secondaryKey = selectedAlbum,
        modifier = modifier,
        label = "album detail transition",
        primaryContent = {
            AlbumOverviewPage(
                active = active,
                albums = albums,
                hiddenAlbumIds = hiddenAlbumIds,
                onAlbumVisibilityChange = onAlbumVisibilityChange,
                onRequestAlbumDelete = onRequestAlbumDelete,
                currentMediaId = currentMediaId,
                browser = browser,
                viewMode = viewMode,
                editMode = editMode,
                selectedAlbumIds = selectedAlbumIds,
                onAlbumSelected = onAlbumSelected,
                onAlbumSelectionChange = onAlbumSelectionChange,
                modifier = Modifier.fillMaxSize(),
            )
        },
        secondaryContent = { album ->
            AlbumDetailPage(
                album = album,
                onRequestAddToPlaylist = onRequestAddToPlaylist,
                onRequestAddToQueue = onRequestAddToQueue,
                onTrackMoreClick = onTrackMoreClick,
                artistSettings = artistSettings,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
private fun AlbumOverviewPage(
    active: Boolean,
    albums: List<AlbumSummary>,
    hiddenAlbumIds: Set<String>,
    onAlbumVisibilityChange: (String, Boolean) -> Unit,
    onRequestAlbumDelete: (String) -> Unit,
    currentMediaId: String?,
    browser: Player?,
    viewMode: AlbumViewMode,
    editMode: Boolean,
    selectedAlbumIds: Set<String>,
    onAlbumSelected: (String, String) -> Unit,
    onAlbumSelectionChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // [1.4.24·R36] 专辑页播放栏显示开关（设置 → 界面 → 外观，默认开）。
    // 关闭时不渲染 LibraryTopCard，列表整体上移一行；只影响专辑页，不动歌曲/文件夹页。
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val uiSettings =
        androidx.compose.runtime.remember(appContext) {
            com.smartisan.music.data.settings.UiSettingsStore(appContext)
        }
    val showPlayBar by
        uiSettings.albumPlayActionsVisible.collectAsState(initial = true)
    // [1.4.30·R138] 普通模式过滤掉被隐藏的专辑卡；编辑模式下它们重新出现并变灰，
    // 由行尾/封面角的眼睛恢复显示——与歌曲页 `if (editMode) items else filterNot hidden`
    // 完全同构。这里只过滤"专辑卡"本身，不动 albums 里的歌单，
    // 所以"查看专辑"（走 albumGroupingId 匹配）和歌曲页都不受影响。
    val shownAlbums =
        remember(albums, hiddenAlbumIds, editMode) {
            if (editMode) albums else albums.filterNot { it.id in hiddenAlbumIds }
        }
    // [1.4.33·隐藏场景推广] 每个可见专辑行下面紧邻了多少张被隐藏的专辑卡 N，
    // 用于边界分界线叠层加粗（编辑态隐藏卡展开，恒为 0）。用完整 albums 按同样排序
    // 展开成有序 id 序列后计算，保证与 SmartisanAlbumCollection 的字母分组顺序一致。
    val hiddenRunBelowById =
        remember(albums, hiddenAlbumIds, editMode) {
            if (editMode || hiddenAlbumIds.isEmpty()) emptyMap()
            else {
                val orderedIds =
                    buildSectionLetterRows(albums) { it.title }
                        .mapNotNull { (it as? SectionLetterRow.Item<AlbumSummary>)?.value?.id }
                val result = mutableMapOf<String, Int>()
                for (i in orderedIds.indices) {
                    val id = orderedIds[i]
                    if (id in hiddenAlbumIds) continue
                    var run = 0
                    var j = i + 1
                    while (j < orderedIds.size && orderedIds[j] in hiddenAlbumIds) {
                        run++
                        j++
                    }
                    if (run > 0) result[id] = run
                }
                result
            }
        }
    val entries =
        remember(shownAlbums) {
            shownAlbums.map { LibraryAlbumEntry(it.id, it.title, it.artist, it, it.songs) }
        }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (active && albums.isNotEmpty() && showPlayBar)
                // [1.4.24·R30 阴影被裁] 顺序必须「LibraryTopCard 在外、ImmersiveActionRowVisibility 在内」：
                // 卡片的 14dp 投影画在内容底边之外（不占布局高度），一旦被 AnimatedVisibility
                // 包在里面就会被它的裁剪吃掉，专辑页因此完全没有播放栏下沿阴影。
                LibraryTopCard {
                    ImmersiveActionRowVisibility {
                        LibraryPlayActions(
                            enabled = !editMode,
                            // 隐藏的专辑卡都看不到，就别再把它塞进"播放全部/随机"的队列里，
                            // 否则用户会听到一张页面上根本不存在的专辑。
                            onPlay = {
                                browser.replaceQueueAndPlay(shownAlbums.flatMap(AlbumSummary::songs))
                            },
                            onShuffle = {
                                browser.replaceQueueAndPlayShuffled(
                                    shownAlbums.flatMap(AlbumSummary::songs)
                                )
                            },
                            containerColor = colorResource(R.color.surface_card),
                            modifier = Modifier.graphicsLayer { alpha = if (editMode) .22f else 1f },
                        )
                    }
                }
            SmartisanAlbumCollection(
                entries = entries,
                active = active,
                viewMode = viewMode,
                currentMediaId = currentMediaId,
                onClick = { onAlbumSelected(it.id, it.title) },
                editMode = editMode,
                selectedIds = selectedAlbumIds,
                onSelectionChange = onAlbumSelectionChange,
                animateInitialAppearance = true,
                modifier = Modifier.fillMaxWidth().weight(1f),
                footer = { LibraryFooter(R.plurals.library_album_count, shownAlbums.size) },
                hiddenIds = hiddenAlbumIds,
                onVisibilityChange = onAlbumVisibilityChange,
                onDeleteAlbum = onRequestAlbumDelete,
                hiddenRunBelowById = hiddenRunBelowById,
            )
        }
        // [1.4.25·R47] 关掉专辑页播放栏后，顶栏下面原本靠 LibraryTopCard 提供的那道投影
        // 一起消失了，列表直接贴着顶栏、没有任何分界。这里补回同款 title_bar_shadow：
        // 画在页面最顶端、向下渐隐，视觉上就是顶栏底沿的投影（与文件夹页/风格页同款）。
        // 只在"没有播放栏"时补，避免和卡片自带的投影叠成双影。
        if (active && albums.isNotEmpty() && !showPlayBar) {
            com.smartisan.music.ui.shell.titlebar.TitleBarShadow(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.title_bar_shadow_height))
                    .zIndex(1f)
            )
        }
    }
}
