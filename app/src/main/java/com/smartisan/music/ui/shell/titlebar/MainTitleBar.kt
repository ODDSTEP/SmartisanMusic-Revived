package com.smartisan.music.ui.shell.titlebar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.smartisan.music.R
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.ArtistTarget
import com.smartisan.music.ui.artist.showsAlbumSwitch
import com.smartisan.music.ui.navigation.MusicDestination

@Composable
internal fun MainTitleBar(
    destination: MusicDestination,
    songsEditMode: Boolean,
    selectedSongCount: Int,
    albumEditMode: Boolean,
    selectedAlbumCount: Int,
    albumDetailTitle: String?,
    /**
     * [1.4.29·R131] 详情层是否激活，必须由「是否选中了专辑」这种对象状态决定，
     * 不能用标题字符串是否为空来判断——「未知专辑」的标题为空，会被误判成根态，
     * 结果顶栏画多选图标、没有返回箭头，内容区却已经是详情页。
     */
    albumDetailActive: Boolean = albumDetailTitle != null,
    albumViewMode: AlbumViewMode,
    artistTarget: ArtistTarget?,
    artistAlbumViewMode: AlbumViewMode,
    onEnterSongsEditMode: () -> Unit,
    onExitSongsEditMode: () -> Unit,
    onRequestDeleteSelected: () -> Unit,
    /** [1.4.28] 专辑多选态的删除：此前是空 lambda，按钮点了完全没反应。 */
    onRequestDeleteSelectedAlbums: () -> Unit = {},
    onEnterAlbumEditMode: () -> Unit,
    onExitAlbumEditMode: () -> Unit,
    onToggleAlbumViewMode: () -> Unit,
    onAlbumDetailBack: () -> Unit,
    onArtistBack: () -> Unit,
    onToggleArtistAlbumViewMode: () -> Unit,
    onRootBack: (() -> Unit)?,
    onSearchClick: () -> Unit,
    songsChromeCollapsed: Boolean = false,
    onToggleSongsChrome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val backLabel = resources.getString(R.string.back)
    val title = albumDetailTitle ?: artistTarget?.title ?: resources.getString(destination.labelRes)
    fun icon(
        resource: Int,
        description: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        checked: Boolean? = null,
    ) =
        com.smartisan.music.ui.components.SmartisanTitleBarAction(
            resource,
            description,
            onClick,
            enabled,
            checked,
        )
    val left = mutableListOf<com.smartisan.music.ui.components.SmartisanTitleBarAction>()
    val right = mutableListOf<com.smartisan.music.ui.components.SmartisanTitleBarAction>()
    when {
        destination == MusicDestination.Album && albumDetailActive ->
            left += icon(R.drawable.standard_icon_back_selector, backLabel, onAlbumDetailBack)
        destination == MusicDestination.Artist && artistTarget != null -> {
            left += icon(R.drawable.standard_icon_back_selector, backLabel, onArtistBack)
            if (artistTarget.showsAlbumSwitch)
                right +=
                    icon(
                        R.drawable.album_switch_selector,
                        resources.getString(R.string.view_mode),
                        onToggleArtistAlbumViewMode,
                        checked = artistAlbumViewMode == AlbumViewMode.List,
                    )
        }
        destination == MusicDestination.Songs && songsEditMode -> {
            left +=
                icon(
                    R.drawable.standard_icon_cancel_selector,
                    resources.getString(R.string.cancel),
                    onExitSongsEditMode,
                )
            right +=
                icon(
                    R.drawable.titlebar_btn_delete_selector,
                    resources.getString(R.string.delete),
                    onRequestDeleteSelected,
                    selectedSongCount > 0,
                )
            // [显示隐藏入口改造] 顶栏批量眼睛已删除，隐藏/恢复改为每行行尾常驻眼睛
            // （SmartisanSongRow 内），与专辑页、文件夹页同一套位置与动画。
        }
        destination == MusicDestination.Album && albumEditMode -> {
            left +=
                icon(
                    R.drawable.standard_icon_cancel_selector,
                    resources.getString(R.string.cancel),
                    onExitAlbumEditMode,
                )
            right +=
                icon(
                    R.drawable.titlebar_btn_delete_selector,
                    resources.getString(R.string.delete),
                    onRequestDeleteSelectedAlbums,
                    selectedAlbumCount > 0,
                )
        }
        else -> {
            when {
                onRootBack != null ->
                    left += icon(R.drawable.standard_icon_back_selector, backLabel, onRootBack)
                destination == MusicDestination.More ->
                    left +=
                        icon(
                            R.drawable.standard_icon_settings_selector,
                            resources.getString(R.string.setting),
                            {},
                        )
                destination != MusicDestination.Artist ->
                    left +=
                        icon(
                            R.drawable.standard_icon_multi_select_selector,
                            resources.getString(R.string.edit),
                            if (destination == MusicDestination.Album) onEnterAlbumEditMode
                            else onEnterSongsEditMode,
                        )
            }
            // 从「更多」进入的歌曲 / 专辑页：多选模式紧跟在返回键右侧，
            // 而不是放在右上角。这样左右两组各两个控件，中间才留得下"专辑"标题，
            // 与把「专辑」钉在底部导航栏时的顶栏完全一致。
            if (
                onRootBack != null &&
                    (destination == MusicDestination.Songs || destination == MusicDestination.Album)
            ) {
                left +=
                    icon(
                        R.drawable.standard_icon_multi_select_selector,
                        resources.getString(R.string.edit),
                        if (destination == MusicDestination.Album) onEnterAlbumEditMode
                        else onEnterSongsEditMode,
                    )
            }
            // 搜索图标任何情况下在最右上（1.2.16 统一规范）：注意右侧动作倒序渲染，
            // search 必须是 right 列表第一项；切换视图放其后（视觉上在搜索左侧）
            right +=
                icon(
                    R.drawable.search_btn_selector,
                    resources.getString(R.string.tab_local_search),
                    onSearchClick,
                )
            // 1.3.7：歌曲页顶栏搜索图标左边的收起按钮，只负责「排序栏」的收起/展开。
            // right 列表倒序渲染，放在 search 之后即视觉上位于搜索左边。
            // 去掉 1.3.6 的双击 + 深/浅色：不额外着色，颜色/透明度跟搜索、编辑图标
            // 完全一致（都用 selector 自带状态色）；只保留 1.3.6 的 1.7 倍等比放大。
            if (destination == MusicDestination.Songs && !songsEditMode) {
                right +=
                    com.smartisan.music.ui.components.SmartisanTitleBarAction(
                        iconRes =
                            if (songsChromeCollapsed)
                                R.drawable.btn_songs_chrome_expand_selector
                            else R.drawable.btn_songs_chrome_collapse_selector,
                        contentDescription =
                            resources.getString(
                                if (songsChromeCollapsed) R.string.songs_chrome_expand
                                else R.string.songs_chrome_collapse
                            ),
                        onClick = onToggleSongsChrome,
                        iconScale = 1.7f,
                        // [1.4.24·R21] PNG → 矢量圆角灰三角。
                        // 形状/视觉尺寸与 v19 原素材一致（宽 9.33dp × 1.7），
                        // 但不再有位图放大后的软边；按下时透明度 0.43→0.62 给反馈。
                        customIcon = { m, pressed ->
                            com.smartisan.music.ui.components.SmartisanCaret(
                                pointingDown = songsChromeCollapsed,
                                contentWidth =
                                    com.smartisan.music.ui.components.SongsChromeCaretWidth,
                                modifier = m,
                                color =
                                    androidx.compose.material3.MaterialTheme.colorScheme
                                        .onSurface.copy(alpha = if (pressed) 0.62f else 0.43f),
                            )
                        },
                    )
            }
            if (destination == MusicDestination.Album)
                right +=
                    icon(
                        R.drawable.album_switch_selector,
                        resources.getString(R.string.view_mode),
                        onToggleAlbumViewMode,
                        checked = albumViewMode == AlbumViewMode.List,
                    )
        }
    }
    com.smartisan.music.ui.components.SmartisanTitleBar(
        title,
        modifier,
        navigationActions = left,
        actions = right,
        showShadow = false,
    )
}

@Composable
internal fun SearchDetailTitleBar(
    destination: MusicDestination,
    albumDetailTitle: String?,
    artistTarget: ArtistTarget?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    artistAlbumViewMode: AlbumViewMode = AlbumViewMode.List,
    onToggleArtistAlbumViewMode: () -> Unit = {},
) {
    MainTitleBar(
        destination = destination,
        songsEditMode = false,
        selectedSongCount = 0,
        albumEditMode = false,
        selectedAlbumCount = 0,
        albumDetailTitle = albumDetailTitle,
        // [1.4.29·R131] 搜索详情本身就是详情层，标题为空（未知专辑）也必须有返回箭头。
        albumDetailActive = true,
        albumViewMode = AlbumViewMode.List,
        artistTarget = artistTarget,
        artistAlbumViewMode = artistAlbumViewMode,
        onEnterSongsEditMode = {},
        onExitSongsEditMode = {},
        onRequestDeleteSelected = {},
        onEnterAlbumEditMode = {},
        onExitAlbumEditMode = {},
        onToggleAlbumViewMode = {},
        onAlbumDetailBack = onBack,
        onArtistBack = onBack,
        onToggleArtistAlbumViewMode = onToggleArtistAlbumViewMode,
        onRootBack = null,
        onSearchClick = {},
        modifier = modifier,
    )
}
