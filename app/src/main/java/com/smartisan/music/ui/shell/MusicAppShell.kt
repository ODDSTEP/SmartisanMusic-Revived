package com.smartisan.music.ui.shell

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.smartisan.music.ExternalAudioLaunchRequest
import com.smartisan.music.ShortcutDestinationRequest
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.library.normalizedDirectoryKey
import com.smartisan.music.data.playlist.PlaylistCreateResult
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.FileScanSettings
import com.smartisan.music.data.settings.FileScanSettingsStore
import com.smartisan.music.data.settings.isHiddenByFileScan
import com.smartisan.music.data.settings.ArtistSettingsStore
import com.smartisan.music.data.settings.LibraryDisplaySettings
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.data.settings.UiSettingsStore
import com.smartisan.music.data.settings.restoredDestination
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.PlaybackIntentAudit
import com.smartisan.music.playback.ProvidePlaybackController
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.await
import com.smartisan.music.playback.deduplicateQueueCandidates
import com.smartisan.music.playback.invalidateLibrary
import com.smartisan.music.playback.refreshLibrary
import com.smartisan.music.playback.removeMediaItemsByMediaIds
import com.smartisan.music.playback.updateQueuedTrackMetadata
import com.smartisan.music.playback.withPlaybackRating
import com.smartisan.music.resolveExternalAudioArtist
import android.provider.MediaStore
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.resolveExternalAudioMediaStoreIds
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.playback.tryShareAudio
import java.util.Locale
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.album.albumGroupingIdOf
import com.smartisan.music.ui.artist.ArtistTarget
import com.smartisan.music.ui.artist.ArtistTitleStack
import com.smartisan.music.ui.artist.artistNormalizedKey
import com.smartisan.music.ui.artist.parentTarget
import com.smartisan.music.ui.components.AudioOutputOverlay
import com.smartisan.music.ui.components.IconNameHintController
import com.smartisan.music.ui.components.IconNameHintLayer
import com.smartisan.music.ui.components.LocalIconHintController
import com.smartisan.music.ui.components.LocalIconNameHintEnabled
import com.smartisan.music.ui.components.MediaStoreDeleteItem
import com.smartisan.music.ui.components.TrackActionItem
import com.smartisan.music.ui.components.TrackActionsOverlay
import com.smartisan.music.ui.components.TrackInfoOverlay
import com.smartisan.music.ui.components.SavedTrackTags
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import com.smartisan.music.ui.components.withSelection
import com.smartisan.music.ui.library.rememberLibraryMediaState
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.playlist.PlaybackPlaylistPickerOverlay
import com.smartisan.music.ui.playlist.PlaylistNameDialogOverlay
import com.smartisan.music.ui.playlist.PlaylistNameDialogRequest
import com.smartisan.music.ui.search.SearchDrilldownTarget
import com.smartisan.music.ui.search.SearchOverlay
import com.smartisan.music.ui.shell.dialogs.SongDeleteConfirmOverlay
import com.smartisan.music.ui.shell.playback.PlaybackBar
import com.smartisan.music.ui.shell.playback.PlaybackBarSnapshot
import com.smartisan.music.ui.shell.playback.loadArtworkBitmap
import com.smartisan.music.ui.shell.playback.peekArtworkBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import com.smartisan.music.ui.shell.playback.playbackBarSnapshot
import com.smartisan.music.ui.shell.playback.toExternalAudioMediaItem
import com.smartisan.music.ui.shell.tabs.MusicBottomBar
import com.smartisan.music.ui.shell.tabs.MusicTabContent
import com.smartisan.music.ui.shell.tabs.NavigationEditorOverlay
import com.smartisan.music.ui.shell.titlebar.MainTitleBar
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.ui.shell.titlebar.TitleBarTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TrackActionSource {
    Library,
    Loved,
    Playlist,
}

@Composable
fun MusicAppShell(
    modifier: Modifier = Modifier,
    playbackLaunchRequest: Int = 0,
    externalAudioLaunchRequest: ExternalAudioLaunchRequest? = null,
    onExternalAudioLaunchConsumed: (Int) -> Unit = {},
    shortcutDestinationRequest: ShortcutDestinationRequest? = null,
    onShortcutDestinationConsumed: (Int) -> Unit = {},
    onStartupReady: () -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    ProvidePlaybackController {
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val uiSettingsStore = remember(appContext) { UiSettingsStore(appContext) }
        val iconNameHintEnabled by
            uiSettingsStore.iconNameHintEnabled.collectAsState(initial = true)
        val iconHintController = remember { IconNameHintController() }
        CompositionLocalProvider(
            LocalIconNameHintEnabled provides iconNameHintEnabled,
            LocalIconHintController provides iconHintController,
        ) {
            MusicAppShellContent(
                playbackLaunchRequest = playbackLaunchRequest,
                externalAudioLaunchRequest = externalAudioLaunchRequest,
                onExternalAudioLaunchConsumed = onExternalAudioLaunchConsumed,
                shortcutDestinationRequest = shortcutDestinationRequest,
                onShortcutDestinationConsumed = onShortcutDestinationConsumed,
                onStartupReady = onStartupReady,
                onThemeModeChange = onThemeModeChange,
                modifier = modifier,
            )
            // 气泡弹在自己的窗口里，不占布局
            IconNameHintLayer(iconHintController)
        }
    }
}

@Composable
private fun MusicAppShellContent(
    playbackLaunchRequest: Int,
    externalAudioLaunchRequest: ExternalAudioLaunchRequest?,
    onExternalAudioLaunchConsumed: (Int) -> Unit,
    shortcutDestinationRequest: ShortcutDestinationRequest?,
    onShortcutDestinationConsumed: (Int) -> Unit,
    onStartupReady: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = LocalPlaybackController.current
    val scope = rememberCoroutineScope()
    val favoriteRepository =
        remember(context.applicationContext) {
            FavoriteSongsRepository.getInstance(context.applicationContext)
        }
    val playlistRepository =
        remember(context.applicationContext) {
            PlaylistRepository.getInstance(context.applicationContext)
        }
    val libraryExclusionsStore =
        remember(context.applicationContext) {
            LibraryExclusionsStore(context.applicationContext)
        }
    val playbackSettingsStore =
        remember(context.applicationContext) {
            PlaybackSettingsStore(context.applicationContext)
        }
    val artistSettingsStore =
        remember(context.applicationContext) {
            ArtistSettingsStore(context.applicationContext)
        }
    val libraryDisplaySettingsStore =
        remember(context.applicationContext) {
            LibraryDisplaySettingsStore(context.applicationContext)
        }
    val navigationSettingsStore =
        remember(context.applicationContext) {
            NavigationSettingsStore(context.applicationContext)
        }
    val themeSettingsStore =
        remember(context.applicationContext) {
            ThemeSettingsStore(context.applicationContext)
        }
    val fileScanSettingsStore =
        remember(context.applicationContext) {
            FileScanSettingsStore(context.applicationContext)
        }
    val favoriteIds by favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val libraryExclusions by
        libraryExclusionsStore.exclusions.collectAsState(initial = LibraryExclusions())
    val playbackSettings by
        playbackSettingsStore.settings.collectAsState(initial = PlaybackSettings())
    val artistSettings by artistSettingsStore.settings.collectAsState(initial = ArtistSettings())
    val libraryDisplaySettings by
        libraryDisplaySettingsStore.settings.collectAsState(initial = LibraryDisplaySettings())
    val fileScanSettings by
        fileScanSettingsStore.settings.collectAsState(initial = FileScanSettings())
    val persistedNavigationSettings: NavigationSettings? by
        navigationSettingsStore.settings.collectAsState(initial = null)
    val navigationSettings = persistedNavigationSettings ?: NavigationSettings()
    val themeMode = remember(themeSettingsStore) { themeSettingsStore.currentMode() }
    val albumViewMode = libraryDisplaySettings.albumViewMode
    val artistAlbumViewMode = libraryDisplaySettings.artistAlbumViewMode
    val unknownSongTitle = stringResource(R.string.unknown_song_title)
    val favoriteRecords by
        favoriteRepository.observeFavorites().collectAsState(initial = emptyList())
    val playlists by playlistRepository.playlists.collectAsState(initial = emptyList())
    var playbackVisible by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchDrilldownTarget by remember { mutableStateOf<SearchDrilldownTarget?>(null) }
    var currentDestination by remember { mutableStateOf(MusicDestination.Playlist) }
    var presentedFromMore by remember { mutableStateOf(false) }
    var playlistAddModeActive by remember { mutableStateOf(false) }
    var moreSettingsPageActive by remember { mutableStateOf(false) }
    var navigationEditorVisible by remember { mutableStateOf(false) }
    var navigationLayoutInitialized by remember { mutableStateOf(false) }
    var navigationStateRestored by remember { mutableStateOf(false) }

    val navigationLayout = navigationSettings.layout
    // 加歌模式只临时替换底栏末位，不污染用户保存的导航布局。
    val bottomDestinations =
        remember(navigationLayout, playlistAddModeActive) {
            navigationLayout.bottomDestinationsEnsuring(
                MusicDestination.Songs.takeIf { playlistAddModeActive }
            )
        }
    val overflowDestinations = navigationLayout.overflowDestinations

    // 冷启动恢复上次一级板块；详情页和弹窗状态不进入持久化恢复范围。
    LaunchedEffect(persistedNavigationSettings, currentDestination) {
        val persistedLayout = persistedNavigationSettings?.layout ?: return@LaunchedEffect
        if (!navigationLayoutInitialized) {
            navigationLayoutInitialized = true
            val (restoredDestination, restoredFromMore) =
                persistedNavigationSettings?.restoredDestination()
                    ?: (persistedLayout.bottomDestinations.first() to false)
            currentDestination = restoredDestination
            presentedFromMore = restoredFromMore
            navigationStateRestored = true
        } else if (
            currentDestination != MusicDestination.More &&
                !persistedLayout.isPinned(currentDestination)
        ) {
            presentedFromMore = true
        }
    }

    LaunchedEffect(currentDestination, presentedFromMore, navigationStateRestored) {
        if (navigationStateRestored) {
            navigationSettingsStore.setLastDestination(currentDestination, presentedFromMore)
        }
    }
    var songsEditMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(emptySet<String>()) }
    var albumEditMode by remember { mutableStateOf(false) }
    var selectedAlbumIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedAlbumTitle by remember { mutableStateOf<String?>(null) }
    // 「查看专辑 / 查看歌手」跨页跳转的来源页：返回键应回到来源页而不是当前目的地首页
    var albumJumpOrigin by remember { mutableStateOf<MusicDestination?>(null) }
    var artistJumpOrigin by remember { mutableStateOf<MusicDestination?>(null) }
    // 「查看文件夹」跨页跳转：来源页 + 待定位的目录键（normalizedDirectoryKey）
    var folderJumpOrigin by remember { mutableStateOf<MusicDestination?>(null) }
    var pendingFolderTargetKey by remember { mutableStateOf<String?>(null) }
    var selectedArtistTarget by remember { mutableStateOf<ArtistTarget?>(null) }
    var libraryRefreshVersion by remember { mutableStateOf(0) }
    var libraryRefreshing by remember { mutableStateOf(false) }
    var showSongDeleteConfirm by remember { mutableStateOf(false) }
    var pendingSongDeleteMediaIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingSongDeleteDismissAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPlaylistPickerMediaItems by remember { mutableStateOf<List<MediaItem>?>(null) }
    var pendingTrackActionItem by remember { mutableStateOf<MediaItem?>(null) }
    var pendingTrackActionSource by remember { mutableStateOf(TrackActionSource.Library) }
    var pendingTrackInfoItem by remember { mutableStateOf<MediaItem?>(null) }
    var playbackPlaylistCreateRequest by remember {
        mutableStateOf<PlaylistNameDialogRequest.Create?>(null)
    }
    var ratingOverrides by remember { mutableStateOf(emptyMap<String, Int>()) }
    var snapshot by
        remember(controller) {
            mutableStateOf(controller.playbackBarSnapshot())
        }
    var playbackBarContentSnapshot by
        remember(controller) {
            mutableStateOf(snapshot)
        }
    val library = rememberLibraryMediaState(libraryRefreshVersion = libraryRefreshVersion)
    val libraryItems =
        remember(library.items, ratingOverrides) {
            library.items.withRatingOverrides(ratingOverrides)
        }
    val visibleLibraryItems =
        remember(libraryItems, fileScanSettings) {
            // 文件扫描开关命中的内容一律「当作从未扫描到」：在数据源头直接排除，
            // 不进入任何页面（含编辑模式），也不会被标成「已隐藏」让用户误操作
            libraryItems.filterNot { it.isHiddenByFileScan(fileScanSettings) }
        }
    // 只包含用户手动隐藏的歌曲；文件扫描排除项不属于「隐藏」，避免编辑模式里出现
    // 用户没有隐藏过、却又恢复不了的幽灵条目
    val allHiddenMediaIds = libraryExclusions.hiddenMediaIds
    // 文件扫描开关变化后重取列表树，让「当作从未扫描到」立即对全部页面生效
    var fileScanSettingsInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(fileScanSettings) {
        if (!fileScanSettingsInitialized) {
            fileScanSettingsInitialized = true
            return@LaunchedEffect
        }
        libraryRefreshVersion += 1
        runCatching { controller?.invalidateLibrary()?.await(context) }
    }
    val currentOnStartupReady by rememberUpdatedState(onStartupReady)
    LaunchedEffect(navigationStateRestored, library.loaded, controller) {
        if (!navigationStateRestored || !library.loaded || controller == null) {
            return@LaunchedEffect
        }
        // 资料库状态发布后再隐藏两个布局帧，让标题栏、专辑网格和首屏封面开始绑定。
        withFrameNanos {}
        withFrameNanos {}
        currentOnStartupReady()
    }
    val playbackBarMediaItem = playbackBarContentSnapshot.mediaItem
    val artworkRequestKey = playbackBarMediaItem?.artworkRequestKey()
    val artworkBitmap by
        produceState<Bitmap?>(
            initialValue = playbackBarMediaItem?.let(::peekArtworkBitmap),
            artworkRequestKey,
        ) {
            val mediaItem = playbackBarContentSnapshot.mediaItem
            if (mediaItem == null) {
                value = null
                return@produceState
            }
            value = peekArtworkBitmap(mediaItem) ?: value
            value = loadArtworkBitmap(context.applicationContext, mediaItem)
        }
    val playbackBarRequestedVisible = snapshot.mediaItem != null
    val playbackBarHeight = 61.dp
    var playbackBarComposed by remember { mutableStateOf(false) }
    // 沉浸式浏览：播放中上滑收起播放操作行与排序栏，下滑恢复（底部导航栏保持常驻）
    var immersiveCollapsed by remember { mutableStateOf(false) }
    // 1.3.7：歌曲页顶栏手动收起，只管排序栏，与沉浸式彻底解耦
    var songsChromeManuallyCollapsed by remember { mutableStateOf(false) }
    var immersivePlaying by remember { mutableStateOf(false) }
    // 1.3.7：设置→界面「沉浸模式」开关，默认开。关闭后滚动不再收起播放操作行。
    val shellUiSettingsStore = remember(context) { UiSettingsStore(context) }
    val immersiveModeEnabled by
        shellUiSettingsStore.immersiveModeEnabled.collectAsState(initial = true)
    val immersiveCollapseAccumPx = remember { mutableFloatStateOf(0f) }
    val immersiveCollapseThresholdPx =
        with(LocalDensity.current) { 52.dp.toPx() }
    val openSearchOverlay = {
        searchQuery = ""
        searchDrilldownTarget = null
        searchVisible = true
    }
    fun openCurrentSearch() {
        openSearchOverlay()
    }
    val closeSearchOverlay = {
        searchVisible = false
        searchDrilldownTarget = null
    }

    // 1.4.1 桌面长按 shortcut：直达歌曲/专辑/喜欢/搜索。delay 让冷启动的导航恢复先跑完再覆盖。
    LaunchedEffect(shortcutDestinationRequest) {
        val req = shortcutDestinationRequest ?: return@LaunchedEffect
        delay(120L)
        when (req.destination) {
            "songs" -> currentDestination = MusicDestination.Songs
            "album" -> currentDestination = MusicDestination.Album
            "loved_songs" -> currentDestination = MusicDestination.LovedSongs
            "search" -> openSearchOverlay()
        }
        onShortcutDestinationConsumed(req.requestId)
    }

    fun closeAlbumDetail() {
        selectedAlbumId = null
        selectedAlbumTitle = null
        albumJumpOrigin = null
    }

    /**
     * [1.4.28] 专辑详情的「返回上一层」唯一实现。
     *
     * 旧实现有两套：系统返回键会认 `albumJumpOrigin` 回来源页，标题栏返回箭头直接
     * `closeAlbumDetail()` —— 从歌曲页「查看专辑」进来后，按标题栏返回会落到专辑列表，
     * 按系统返回才回歌曲页。更糟的是标题栏路径不清 origin，残留后下次再进任意专辑、
     * 按系统返回会被弹回上次的来源页。
     *
     * 现在两处都调这里：有 origin 就回来源页并清空详情状态；没有就退回专辑列表。
     */
    fun backFromAlbumDetail() {
        val origin = albumJumpOrigin
        albumJumpOrigin = null
        if (origin != null) {
            selectedAlbumId = null
            selectedAlbumTitle = null
            currentDestination = origin
        } else {
            closeAlbumDetail()
        }
    }

    fun closeArtistDetail() {
        selectedArtistTarget = selectedArtistTarget?.parentTarget()
        if (selectedArtistTarget == null) artistJumpOrigin = null
    }

    /** [1.4.28] 艺术家详情返回：多层艺术家先退一层，顶层才按来源页返回。与专辑同理统一两处。 */
    fun backFromArtistDetail() {
        if (selectedArtistTarget?.parentTarget() != null) {
            closeArtistDetail()
            return
        }
        val origin = artistJumpOrigin
        artistJumpOrigin = null
        if (origin != null) {
            selectedArtistTarget = null
            currentDestination = origin
        } else {
            closeArtistDetail()
        }
    }

    fun returnToMore() {
        presentedFromMore = false
        currentDestination = MusicDestination.More
    }

    DisposableEffect(controller) {
        if (controller == null) {
            snapshot = PlaybackBarSnapshot()
            return@DisposableEffect onDispose {}
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    immersivePlaying = player.isPlaying
                    val nextSnapshot = player.playbackBarSnapshot()
                    snapshot = nextSnapshot
                    if (nextSnapshot.mediaItem != null) {
                        playbackBarContentSnapshot = nextSnapshot
                    }
                }
            }
        controller.addListener(listener)
        val initialSnapshot = controller.playbackBarSnapshot()
        immersivePlaying = controller.isPlaying
        snapshot = initialSnapshot
        if (initialSnapshot.mediaItem != null) {
            playbackBarContentSnapshot = initialSnapshot
        }
        onDispose {
            controller.removeListener(listener)
        }
    }

    LaunchedEffect(playbackBarRequestedVisible) {
        if (playbackBarRequestedVisible) {
            playbackBarComposed = true
        }
    }

    fun cleanupDeletedSongs(mediaIds: Set<String>, hideFromLibrary: Boolean) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
        scope.launch {
            if (hideFromLibrary) {
                libraryExclusionsStore.hideMediaIds(mediaIds)
            }
            favoriteRepository.removeAll(mediaIds)
            playlistRepository.removeMediaIdsFromAll(mediaIds)
            runCatching {
                controller?.invalidateLibrary()?.await(context)
            }
            libraryRefreshVersion += 1
        }
    }

    fun reclaimHiddenMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
    }

    /** 隐藏歌曲：不删本地文件，仅从资料库与播放队列中隐去（复用资料库排除存储） */
    fun hideSongMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
        scope.launch {
            libraryExclusionsStore.hideMediaIds(mediaIds)
            runCatching {
                controller?.invalidateLibrary()?.await(context)
            }
            libraryRefreshVersion += 1
            Toast.makeText(context, R.string.hiden_dir, Toast.LENGTH_SHORT).show()
        }
    }

    /** 恢复显示已隐藏的歌曲 */
    fun showSongMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        scope.launch {
            libraryExclusionsStore.showMediaIds(mediaIds)
            runCatching {
                controller?.invalidateLibrary()?.await(context)
            }
            libraryRefreshVersion += 1
            Toast.makeText(context, R.string.shown_dir, Toast.LENGTH_SHORT).show()
        }
    }

    fun enqueueResolvedMediaItems(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        if (controller?.repeatMode == Player.REPEAT_MODE_ONE) {
            Toast.makeText(context, R.string.can_not_add_to_queue_single_repeat, Toast.LENGTH_SHORT)
                .show()
        } else {
            val deduplicatedItems = controller?.deduplicateQueueCandidates(items).orEmpty()
            if (deduplicatedItems.isNotEmpty()) {
                controller?.addMediaItems(deduplicatedItems)
                Toast.makeText(context, R.string.add_to_queue_success, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun enqueueMediaItems(items: List<MediaItem>) {
        enqueueResolvedMediaItems(items)
    }

    fun requestAddToPlaylist(items: List<MediaItem>) {
        val candidates = items.filter { item ->
            item.mediaId.isNotBlank() && !item.isExternalAudioLaunchItem()
        }
        if (candidates.isNotEmpty()) {
            pendingPlaylistPickerMediaItems = candidates
        }
    }

    fun toggleFavorite(mediaItem: MediaItem) {
        if (mediaItem.isExternalAudioLaunchItem()) {
            return
        }
        val mediaId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        scope.launch {
            favoriteRepository.toggle(mediaId)
        }
    }

    fun showTrackActions(
        item: MediaItem,
        source: TrackActionSource,
    ) {
        if (item.mediaId.isBlank()) {
            return
        }
        pendingTrackActionItem = item
        pendingTrackActionSource = source
    }

    fun dismissTrackActions() {
        pendingTrackActionItem = null
    }

    fun dismissSongDeleteConfirmation() {
        val dismissAction = pendingSongDeleteDismissAction
        showSongDeleteConfirm = false
        pendingSongDeleteMediaIds = emptySet()
        pendingSongDeleteDismissAction = null
        dismissAction?.invoke()
    }

    fun requestSongDeleteConfirmation(
        mediaIds: Set<String>,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (mediaIds.isEmpty()) {
            return
        }
        pendingSongDeleteMediaIds = mediaIds
        pendingSongDeleteDismissAction = onDismiss
        showSongDeleteConfirm = true
    }

    /**
     * [1.4.28] 专辑多选删除。
     *
     * 旧实现里标题栏的删除按钮是空 lambda，点了一点反应都没有，也没有任何专辑删除链路。
     * 这里按 [albumGroupingIdOf]（与专辑页分组同一口径）把选中专辑下的全部可见歌曲收集起来，
     * 复用歌曲删除的确认框与系统删除链路——用户会先看到「删除 N 首歌」确认，
     * 再由 MediaStore 弹系统级确认，不存在静默删文件。
     */
    fun requestAlbumDeleteConfirmation(albumIds: Set<String>) {
        if (albumIds.isEmpty()) return
        val mediaIds =
            visibleLibraryItems.asSequence()
                .filter { albumGroupingIdOf(it) in albumIds }
                .map { it.mediaId }
                .filter { it.isNotBlank() }
                .toSet()
        if (mediaIds.isEmpty()) return
        requestSongDeleteConfirmation(mediaIds)
    }

    fun removeFavoriteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        scope.launch {
            if (mediaIds.size == 1) {
                favoriteRepository.remove(mediaIds.first())
            } else {
                favoriteRepository.removeAll(mediaIds)
            }
        }
    }

    fun refreshLibrary() {
        if (libraryRefreshing) {
            return
        }
        val playbackController = controller
        if (playbackController == null) {
            Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            return
        }
        libraryRefreshing = true
        scope.launch {
            val result = runCatching {
                playbackController.refreshLibrary().await(context)
            }
                .getOrNull()
            libraryRefreshing = false
            if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                libraryRefreshVersion += 1
                Toast.makeText(context, R.string.library_refresh_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val deleteCoordinator =
        rememberMediaStoreDeleteCoordinator(
            onDeleted = { mediaIds ->
                cleanupDeletedSongs(mediaIds, hideFromLibrary = false)
            },
            onNotDeleted = {
                Toast.makeText(context, R.string.playback_delete_failed, Toast.LENGTH_SHORT).show()
            },
        )

    fun requestSystemDeleteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        val deleteItems = mediaIds.mapNotNull { mediaId ->
            val mediaStoreId = mediaId.trim().toLongOrNull() ?: return@mapNotNull null
            MediaStoreDeleteItem(
                mediaId = mediaId,
                uri = audioMediaItemUri(mediaStoreId),
            )
        }
        val deleteItemIds = deleteItems.mapTo(linkedSetOf(), MediaStoreDeleteItem::mediaId)
        val invalidMediaIds = mediaIds - deleteItemIds
        if (invalidMediaIds.isNotEmpty()) {
            cleanupDeletedSongs(invalidMediaIds, hideFromLibrary = true)
        }
        if (deleteItems.isEmpty()) {
            return
        }
        deleteCoordinator.delete(deleteItems)
    }

    LaunchedEffect(playbackLaunchRequest) {
        if (playbackLaunchRequest > 0) {
            playbackVisible = true
        }
    }

    LaunchedEffect(currentDestination) {
        if (currentDestination != MusicDestination.Songs) {
            songsEditMode = false
            selectedSongIds = emptySet()
            showSongDeleteConfirm = false
            pendingSongDeleteMediaIds = emptySet()
        }
        if (currentDestination != MusicDestination.Album) {
            albumEditMode = false
            selectedAlbumIds = emptySet()
            selectedAlbumId = null
            selectedAlbumTitle = null
            albumJumpOrigin = null
        }
        if (currentDestination != MusicDestination.Artist) {
            selectedArtistTarget = null
            artistJumpOrigin = null
        }
        if (currentDestination != MusicDestination.Playlist) {
            playlistAddModeActive = false
        }
        if (currentDestination != MusicDestination.Folder) {
            folderJumpOrigin = null
        }
        dismissTrackActions()
    }

    // ---- 返回标准：一级一级退。多选（编辑）模式是栈上的一层，返回先退出多选，再返回上一层 ----
    fun exitSongsEditMode() {
        songsEditMode = false
        selectedSongIds = emptySet()
        showSongDeleteConfirm = false
        pendingSongDeleteMediaIds = emptySet()
        pendingSongDeleteDismissAction = null
    }

    fun exitAlbumEditMode() {
        albumEditMode = false
        selectedAlbumIds = emptySet()
    }

    BackHandler(enabled = currentDestination == MusicDestination.Album && selectedAlbumId != null) {
        // 跨页跳转进来的回来源页，专辑页内部进入的退回列表；两处行为统一（见 backFromAlbumDetail）。
        backFromAlbumDetail()
    }

    BackHandler(enabled = currentDestination == MusicDestination.Songs && songsEditMode) {
        exitSongsEditMode()
    }

    BackHandler(enabled = currentDestination == MusicDestination.Album && albumEditMode) {
        exitAlbumEditMode()
    }

    val selectedArtistParentTarget = selectedArtistTarget?.parentTarget()
    BackHandler(
        enabled =
            currentDestination == MusicDestination.Artist &&
                selectedArtistTarget != null &&
                selectedArtistParentTarget == null
    ) {
        // 与「查看专辑」同理：跨页跳转进来的直接回到来源页（见 backFromArtistDetail）。
        backFromArtistDetail()
    }

    BackHandler(
        enabled =
            presentedFromMore &&
                when (currentDestination) {
                    MusicDestination.Songs -> !songsEditMode
                    MusicDestination.Album -> selectedAlbumId == null && !albumEditMode
                    MusicDestination.Artist -> selectedArtistTarget == null
                    else -> false
                }
    ) {
        returnToMore()
    }
    BackHandler(
        enabled =
            currentDestination == MusicDestination.Artist &&
                selectedArtistTarget != null &&
                selectedArtistParentTarget != null
    ) {
        closeArtistDetail()
    }

    LaunchedEffect(externalAudioLaunchRequest, controller) {
        val request = externalAudioLaunchRequest ?: return@LaunchedEffect
        playbackVisible = true
        val playbackController = controller ?: return@LaunchedEffect
        val (artist, mediaStoreIds) =
            withContext(Dispatchers.IO) {
                request.resolveExternalAudioArtist(context.applicationContext) to
                    request.resolveExternalAudioMediaStoreIds(context.applicationContext)
            }
        val mediaItem =
            request.toExternalAudioMediaItem(
                fallbackTitle = unknownSongTitle,
                artist = artist,
                mediaStoreId = mediaStoreIds.mediaStoreId,
                albumId = mediaStoreIds.albumId,
            )
        playbackController.setMediaItem(mediaItem)
        playbackController.prepare()
        playbackController.play()
        onExternalAudioLaunchConsumed(request.requestId)
    }

    val bottomNavigationHeight =
        dimensionResource(R.dimen.realtabcontent_margin_bottom) +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val realTabContentBottomMargin =
        if (playbackBarComposed) {
            bottomNavigationHeight
        } else {
            bottomNavigationHeight
        }
    val playbackBarOverlayHeight = if (playbackBarComposed) playbackBarHeight else 0.dp
    val hideBottomChrome = currentDestination == MusicDestination.More && moreSettingsPageActive

    LaunchedEffect(currentDestination) {
        if (currentDestination != MusicDestination.More) {
            moreSettingsPageActive = false
        }
    }

    LaunchedEffect(currentDestination, playbackVisible, playlistAddModeActive) {
        immersiveCollapsed = false
    }
    LaunchedEffect(immersivePlaying) {
        if (!immersivePlaying) {
            immersiveCollapsed = false
        }
    }
    // 1.3.7：沉浸模式与收起/展开开关彻底解耦——不再互相禁用、不再互相清零滚动状态，
    // 深浅色模式已整组移除。沉浸式只由「有播放 + 沉浸开关」决定。
    val manualCollapsedActive =
        currentDestination == MusicDestination.Songs && songsChromeManuallyCollapsed
    val immersiveEnabled = immersiveModeEnabled && immersivePlaying
    // 顶栏三角形只跟随「排序栏有没有被手动收起」，不再被沉浸滚动带着变。
    val effectiveChromeCollapsed = manualCollapsedActive
    val immersiveConnection =
        remember {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset {
                    if (!immersiveModeEnabled || !immersivePlaying) {
                        return androidx.compose.ui.geometry.Offset.Zero
                    }
                    val dy = available.y
                    val previous = immersiveCollapseAccumPx.floatValue
                    immersiveCollapseAccumPx.floatValue =
                        if (previous != 0f && kotlin.math.sign(dy) != kotlin.math.sign(previous)) dy
                        else previous + dy
                    when {
                        immersiveCollapseAccumPx.floatValue < -immersiveCollapseThresholdPx -> {
                            immersiveCollapsed = true
                            immersiveCollapseAccumPx.floatValue = 0f
                        }
                        immersiveCollapseAccumPx.floatValue > immersiveCollapseThresholdPx -> {
                            immersiveCollapsed = false
                            immersiveCollapseAccumPx.floatValue = 0f
                        }
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }
            }
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .nestedScroll(immersiveConnection)
    ) {
        // [1.4.29·R123] 根背景也走「竖纹 + 莫奈色雾」，任何没有自铺背景的页面/浮层
        // 都不会露出未莫奈化的原厂灰底。
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().libraryTexture()
        )
        val titleContentHeight = dimensionResource(R.dimen.title_bar_height)
        val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
        val titleAreaHeight =
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + titleContentHeight
        val destinationSurface: @Composable (MusicDestination, Boolean) -> Unit =
            { destination, fromMore ->
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(
                                bottom = if (hideBottomChrome) 0.dp else realTabContentBottomMargin
                            )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val titleBarContent:
                            @Composable
                            (String?, ArtistTarget?, Modifier) -> Unit =
                            { albumDetailTitle, artistTarget, titleModifier ->
                                MainTitleBar(
                                    destination = destination,
                                    songsEditMode =
                                        destination == MusicDestination.Songs && songsEditMode,
                                    selectedSongCount = selectedSongIds.size,
                                    albumEditMode =
                                        destination == MusicDestination.Album && albumEditMode,
                                    selectedAlbumCount = selectedAlbumIds.size,
                                    albumDetailTitle = albumDetailTitle,
                                    // [1.4.29·R131] 详情层用「有没有选中专辑」判断，
                                    // 不用标题是否为空——「未知专辑」标题为空会丢返回箭头。
                                    albumDetailActive =
                                        destination == MusicDestination.Album &&
                                            selectedAlbumId != null,
                                    albumViewMode = albumViewMode,
                                    artistTarget = artistTarget,
                                    artistAlbumViewMode = artistAlbumViewMode,
                                    onEnterSongsEditMode = {
                                        songsEditMode = true
                                        selectedSongIds = emptySet()
                                    },
                                    onExitSongsEditMode = {
                                        exitSongsEditMode()
                                    },
                                    onRequestDeleteSelected = {
                                        if (selectedSongIds.isNotEmpty()) {
                                            requestSongDeleteConfirmation(selectedSongIds)
                                        }
                                    },
                                    onRequestDeleteSelectedAlbums = {
                                        requestAlbumDeleteConfirmation(selectedAlbumIds)
                                    },
                                    onHideSongsSelected = {
                                        if (selectedSongIds.isNotEmpty()) {
                                            // 选中项全部未隐藏 → 隐藏；含已隐藏项（全部隐藏或混合）→ 全部恢复显示
                                            val noneHidden =
                                                selectedSongIds.none { it in allHiddenMediaIds }
                                            if (noneHidden) {
                                                hideSongMediaIds(selectedSongIds)
                                            } else {
                                                showSongMediaIds(selectedSongIds)
                                            }
                                        }
                                    },
                                    selectedSongsContainHidden =
                                        selectedSongIds.any { it in allHiddenMediaIds },
                                    onEnterAlbumEditMode = {
                                        albumEditMode = true
                                        selectedAlbumIds = emptySet()
                                    },
                                    onExitAlbumEditMode = {
                                        exitAlbumEditMode()
                                    },
                                    onToggleAlbumViewMode = {
                                        val nextMode =
                                            if (albumViewMode == AlbumViewMode.List) {
                                                AlbumViewMode.Tile
                                            } else {
                                                AlbumViewMode.List
                                            }
                                        scope.launch {
                                            libraryDisplaySettingsStore.setAlbumViewMode(nextMode)
                                        }
                                    },
                                    onAlbumDetailBack = {
                                        backFromAlbumDetail()
                                    },
                                    onArtistBack = {
                                        backFromArtistDetail()
                                    },
                                    onToggleArtistAlbumViewMode = {
                                        val nextMode =
                                            if (artistAlbumViewMode == AlbumViewMode.List) {
                                                AlbumViewMode.Tile
                                            } else {
                                                AlbumViewMode.List
                                            }
                                        scope.launch {
                                            libraryDisplaySettingsStore.setArtistAlbumViewMode(
                                                nextMode
                                            )
                                        }
                                    },
                                    onRootBack = ::returnToMore.takeIf { fromMore },
                                    onSearchClick = ::openCurrentSearch,
                                    songsChromeCollapsed = effectiveChromeCollapsed,
                                    onToggleSongsChrome = {
                                        songsChromeManuallyCollapsed =
                                            !songsChromeManuallyCollapsed
                                    },
                                    modifier = titleModifier,
                                )
                            }
                        if (destination !in DestinationsWithOwnedTitleBar) {
                            when (destination) {
                                MusicDestination.Album ->
                                    TitleBarTransition(
                                        // [1.4.29·R131] 层级改用选中专辑 id 判断（对象状态），
                                        // 与文件夹/设置/播放列表/风格/歌手的写法一致；
                                        // 原来传标题字符串，空标题=无详情层，直接丢箭头。
                                        secondaryKey = selectedAlbumId,
                                        modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                                        label = "album title transition",
                                        primaryContent = {
                                            titleBarContent(null, null, Modifier.fillMaxSize())
                                        },
                                        secondaryContent = {
                                            titleBarContent(
                                                selectedAlbumTitle,
                                                null,
                                                Modifier.fillMaxSize(),
                                            )
                                        },
                                    )
                                MusicDestination.Artist ->
                                    ArtistTitleStack(
                                        selectedTarget = selectedArtistTarget,
                                        modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                                    ) { artistTarget, titleModifier ->
                                        titleBarContent(null, artistTarget, titleModifier)
                                    }
                                else -> titleBarContent(null, null, Modifier.fillMaxWidth())
                            }
                        }
                        MusicTabContent(
                            destination = destination,
                            presentedFromMore = fromMore,
                            overflowDestinations = overflowDestinations,
                            mediaItems = visibleLibraryItems,
                            favoriteRecords = favoriteRecords,
                            libraryLoaded = library.loaded,
                            songsEditMode = destination == MusicDestination.Songs && songsEditMode,
                            selectedSongIds = selectedSongIds,
                            albumViewMode = albumViewMode,
                            albumEditMode = destination == MusicDestination.Album && albumEditMode,
                            selectedAlbumId = selectedAlbumId,
                            selectedAlbumIds = selectedAlbumIds,
                            artistAlbumViewMode = artistAlbumViewMode,
                            selectedArtistTarget = selectedArtistTarget,
                            playbackBarOverlayHeight =
                                if (hideBottomChrome) 0.dp else playbackBarOverlayHeight,
                            hiddenMediaIds = allHiddenMediaIds,
                            libraryRefreshVersion = libraryRefreshVersion,
                            libraryRefreshing = libraryRefreshing,
                            playbackSettings = playbackSettings,
                            artistSettings = artistSettings,
                            onRefreshLibrary = ::refreshLibrary,
                            onRequestAddToPlaylist = ::requestAddToPlaylist,
                            onRequestAddToQueue = ::enqueueMediaItems,
                            onScratchEnabledChange = { enabled ->
                                scope.launch {
                                    playbackSettingsStore.setScratchEnabled(enabled)
                                }
                            },
                            onHidePlayerAxisEnabledChange = { enabled ->
                                scope.launch {
                                    playbackSettingsStore.setHidePlayerAxisEnabled(enabled)
                                }
                            },
                            onPopcornSoundEnabledChange = { enabled ->
                                scope.launch {
                                    playbackSettingsStore.setPopcornSoundEnabled(enabled)
                                }
                                // [v18·#114] 开关从关→开立即试听一次爆豆音，让用户确认生效。
                                if (enabled) {
                                    com.smartisan.music.ui.playback.playPopcornPreview(context)
                                }
                            },
                            onAudioFxEnabledChange = { enabled ->
                                scope.launch {
                                    playbackSettingsStore.setAudioFxEnabled(enabled)
                                }
                            },
                            onAudioFxPresetChange = { preset ->
                                scope.launch {
                                    playbackSettingsStore.setAudioFxPreset(preset)
                                }
                            },
                            onAudioFxCustomGainDbPointsChange = { gains ->
                                scope.launch {
                                    playbackSettingsStore.setAudioFxCustomGainDbPoints(gains)
                                }
                            },
                            onArtistSeparatorsChange = { separators ->
                                scope.launch {
                                    artistSettingsStore.setSeparators(separators)
                                }
                                selectedArtistTarget = null
                                searchDrilldownTarget = null
                            },
                            navigationSettings = navigationSettings,
                            themeMode = themeMode,
                            onTabPinnedChange = { route, pinned ->
                                scope.launch {
                                    navigationSettingsStore.setTabPinned(route, pinned)
                                }
                            },
                            onThemeModeChange = onThemeModeChange,
                            onOverflowDestinationSelected = { destination ->
                                presentedFromMore = true
                                currentDestination = destination
                            },
                            onReturnToMore = ::returnToMore,
                            onMediaIdsHidden = ::reclaimHiddenMediaIds,
                            onRequestDeleteMediaIds = ::requestSystemDeleteMediaIds,
                            onRequestSongDeleteConfirmation = { mediaIds, onDismiss ->
                                requestSongDeleteConfirmation(mediaIds, onDismiss)
                            },
                            onRequestSongHide = { mediaIds, onDismiss ->
                                hideSongMediaIds(mediaIds)
                                onDismiss?.invoke()
                            },
                            onLibraryTrackMoreClick = { item ->
                                showTrackActions(item, TrackActionSource.Library)
                            },
                            onLovedSongsTrackMoreClick = { item ->
                                showTrackActions(item, TrackActionSource.Loved)
                            },
                            onPlaylistTrackMoreClick = { item ->
                                showTrackActions(item, TrackActionSource.Playlist)
                            },
                            folderTargetKey = pendingFolderTargetKey,
                            onFolderTargetConsumed = { pendingFolderTargetKey = null },
                            onFolderTargetJumpBack = {
                                val origin = folderJumpOrigin
                                folderJumpOrigin = null
                                if (origin != null) currentDestination = origin
                            },
                            onRemoveFavoriteMediaIds = ::removeFavoriteMediaIds,
                            onMoreSettingsPageActiveChanged = { active ->
                                moreSettingsPageActive = active
                            },
                            onSongSelectionChange = { mediaId, selected ->
                                selectedSongIds = selectedSongIds.withSelection(mediaId, selected)
                            },
                            onAlbumSelectionChange = { albumId, selected ->
                                selectedAlbumIds = selectedAlbumIds.withSelection(albumId, selected)
                            },
                            onAlbumSelected = { albumId, albumTitle ->
                                albumEditMode = false
                                selectedAlbumIds = emptySet()
                                selectedAlbumId = albumId
                                selectedAlbumTitle = albumTitle
                            },
                            onArtistTargetChanged = { target ->
                                selectedArtistTarget = target
                            },
                            onPlaylistAddModeActiveChanged = { active ->
                                playlistAddModeActive = active
                            },
                            onSearchClick = ::openCurrentSearch,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                    if (
                        destination == MusicDestination.Artist ||
                            destination == MusicDestination.Album ||
                            destination == MusicDestination.Songs
                    ) {
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
            }
        CompositionLocalProvider(
            LocalImmersiveEnabled provides immersiveEnabled,
            LocalImmersiveCollapsed provides (immersiveEnabled && immersiveCollapsed),
            LocalChromeManuallyCollapsed provides manualCollapsedActive,
        ) {
        PageStackTransition(
            secondaryKey = currentDestination.takeIf { presentedFromMore },
            modifier = Modifier.fillMaxSize(),
            label = "more destination stack",
            projectTitles = !playlistAddModeActive,
            titleProjectionEnabled = !moreSettingsPageActive,
            primaryContent = {
                destinationSurface(
                    if (presentedFromMore) MusicDestination.More else currentDestination,
                    false,
                )
            },
            secondaryContent = { destination ->
                destinationSurface(destination, true)
            },
        )
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    // 1.2.20 回滚 1.2.19 的 zIndex(20f)：一刀切抬到最上层会把
                    // 选择操作菜单/播放页等页面自身元素盖住（用户实测截图）。
                    // 「搜索进专辑页看不到播放栏」的真正根因是当时存储写满导致播放失败，
                    // 转码修复后播放栏能正常出现，无需全局抬层。
                    .retainedChromeVisibility(!hideBottomChrome)
        ) {
            if (playbackBarComposed) {
                PlaybackBar(
                    snapshot = playbackBarContentSnapshot,
                    shown = playbackBarRequestedVisible,
                    favoriteIds = favoriteIds,
                    artworkBitmap = artworkBitmap,
                    onHidden = {
                        if (!playbackBarRequestedVisible) {
                            playbackBarComposed = false
                        }
                    },
                    onOpenPlayback = {
                        playbackVisible = true
                    },
                    onToggleFavorite = { mediaItem ->
                        toggleFavorite(mediaItem)
                    },
                    onPrevious = {
                        // 1.3.2：直接切上一首；seekToPrevious() 播过 3 秒只会倒回当前曲开头
                        controller?.seekToPreviousMediaItem()
                    },
                    onPlayPause = {
                        if (snapshot.isPlaybackActive) {
                            PlaybackIntentAudit.pause(controller, "底栏播放条·播放/暂停按钮")?.pause()
                        } else {
                            PlaybackIntentAudit.play(controller, "底栏播放条·播放/暂停按钮")?.play()
                        }
                    },
                    onNext = {
                        controller?.seekToNext()
                    },
                    modifier = Modifier.fillMaxWidth().height(playbackBarHeight),
                    bottomDividerVisible = true,
                )
            }
            AnimatedVisibility(
                visible = !hideBottomChrome,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                MusicBottomBar(
                    currentDestination =
                        when {
                            playlistAddModeActive -> MusicDestination.Songs
                            presentedFromMore -> MusicDestination.More
                            else -> currentDestination
                        },
                    destinations = bottomDestinations,
                    onDestinationSelected = { destination ->
                        presentedFromMore = false
                        currentDestination = destination
                    },
                    onEditRequested = {
                        if (!playlistAddModeActive) {
                            navigationEditorVisible = true
                        }
                    },
                    topChromeVisible = !playbackBarComposed,
                )
            }
        }
        val trackActionItems =
            pendingTrackActionItem
                ?.let { actionItem ->
                    val mediaId = actionItem.mediaId
                    val isFavorite = mediaId in favoriteIds
                    val canAddToPlaylist =
                        mediaId.isNotBlank() && !actionItem.isExternalAudioLaunchItem()
                    val canFavorite =
                        mediaId.isNotBlank() && !actionItem.isExternalAudioLaunchItem()
                    val canNavigate =
                        mediaId.isNotBlank() && !actionItem.isExternalAudioLaunchItem()
                    val albumIdExtra =
                        actionItem.mediaMetadata.extras
                            ?.getLong(LocalAudioLibrary.AlbumIdExtraKey)
                            ?.takeIf { it > 0L }
                    val albumTitleText =
                        actionItem.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                    val artistName =
                        actionItem.mediaMetadata.artist?.toString()?.takeIf {
                            it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
                        }
                    val albumArtistName =
                        actionItem.mediaMetadata.albumArtist?.toString()?.takeIf {
                            it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
                        }
                    // 1.2.19：与专辑页分组共用同一算法，避免「查看专辑」跳进错误分组
                    val albumGroupingId =
                        com.smartisan.music.ui.album.albumGroupingId(
                            albumIdExtra,
                            albumTitleText,
                            albumArtistName,
                        )
                    val actions =
                        mutableListOf(
                            TrackActionItem(
                                labelRes = R.string.add_to_playlist,
                                iconRes = R.drawable.more_select_icon_addlist,
                                pressedIconRes = R.drawable.more_select_icon_addlist_down,
                                enabled = canAddToPlaylist,
                                onClick = {
                                    dismissTrackActions()
                                    requestAddToPlaylist(listOf(actionItem))
                                },
                            ),
                            TrackActionItem(
                                labelRes = R.string.add_to_queue,
                                iconRes = R.drawable.more_select_icon_addplay,
                                pressedIconRes = R.drawable.more_select_icon_addplay_down,
                                onClick = {
                                    dismissTrackActions()
                                    enqueueMediaItems(listOf(actionItem))
                                },
                            ),
                            TrackActionItem(
                                labelRes = R.string.action_view_album,
                                iconRes = R.drawable.more_select_icon_album,
                                // [1.4.24·R28] albumGroupingId 现在对空专辑返回固定的
                                // UnknownAlbumGroupingId，不再为 null，所以「查看专辑」始终可用，
                                // 空专辑会跳到合并后的「未知专辑」卡。
                                enabled = canNavigate,
                                onClick = {
                                    dismissTrackActions()
                                    val groupingId = albumGroupingId
                                    // 记录来源页：从歌曲页等跳进专辑详情后，返回键直接回到来源页
                                    albumJumpOrigin =
                                        currentDestination.takeIf { it != MusicDestination.Album }
                                    currentDestination = MusicDestination.Album
                                    selectedAlbumId = groupingId
                                    // [1.4.29·R131] 与 buildAlbumSummaries 同口径兜底：
                                    // 专辑名为空时标题也必须是「未知专辑」，否则顶栏判空丢详情层。
                                    selectedAlbumTitle =
                                        albumTitleText ?: context.getString(R.string.unknown_album)
                                },
                            ),
                            TrackActionItem(
                                labelRes = R.string.action_view_artist,
                                iconRes = R.drawable.more_select_icon_artist,
                                enabled = canNavigate && artistName != null,
                                onClick = {
                                    dismissTrackActions()
                                    val name = artistName ?: return@TrackActionItem
                                    artistJumpOrigin =
                                        currentDestination.takeIf { it != MusicDestination.Artist }
                                    currentDestination = MusicDestination.Artist
                                    selectedArtistTarget =
                                        ArtistTarget.Albums(
                                            artistId = "artist:${name.artistNormalizedKey()}",
                                            artistName = name,
                                        )
                                },
                            ),
                            TrackActionItem(
                                labelRes = R.string.action_song_info,
                                iconRes = R.drawable.more_select_icon_song_info,
                                enabled = canNavigate,
                                onClick = {
                                    dismissTrackActions()
                                    pendingTrackInfoItem = actionItem
                                },
                            ),
                            TrackActionItem(
                                labelRes = R.string.action_share,
                                iconRes = R.drawable.more_select_icon_share,
                                pressedIconRes = R.drawable.more_select_icon_share_down,
                                enabled = canNavigate,
                                onClick = {
                                    dismissTrackActions()
                                    if (!context.tryShareAudio(actionItem)) {
                                        Toast.makeText(
                                                context,
                                                R.string.can_not_share_song,
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }
                                },
                            ),
                            TrackActionItem(
                                labelRes = if (isFavorite) R.string.cancel_love else R.string.love,
                                iconRes =
                                    if (isFavorite) {
                                        R.drawable.more_select_icon_favorite_cancel
                                    } else {
                                        R.drawable.more_select_icon_favorite_add
                                    },
                                pressedIconRes =
                                    if (isFavorite) {
                                        R.drawable.more_select_icon_favorite_cancel_down
                                    } else {
                                        R.drawable.more_select_icon_favorite_add_down
                                    },
                                enabled = canFavorite,
                                selected = isFavorite,
                                onClick = {
                                    dismissTrackActions()
                                    if (canFavorite) {
                                        toggleFavorite(actionItem)
                                    }
                                },
                            ),
                        )
                    if (pendingTrackActionSource == TrackActionSource.Library) {
                        actions +=
                            TrackActionItem(
                                labelRes = R.string.delete,
                                iconRes = R.drawable.more_select_icon_delete,
                                onClick = {
                                    dismissTrackActions()
                                    requestSongDeleteConfirmation(setOf(mediaId))
                                },
                            )
                    } else {
                        // 收藏/播放列表来源的第 8 格（1.2.16）：查看文件夹——
                        // 跳到歌曲所在文件夹，返回键回来源页（folderJumpOrigin 仿 albumJumpOrigin）
                        val folderKey =
                            actionItem.mediaMetadata.extras
                                ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                                ?.normalizedDirectoryKey()
                        actions +=
                            TrackActionItem(
                                labelRes = R.string.action_view_folder,
                                iconRes = R.drawable.more_select_icon_folder,
                                enabled = canNavigate && folderKey != null,
                                onClick = {
                                    dismissTrackActions()
                                    val key = folderKey ?: return@TrackActionItem
                                    folderJumpOrigin =
                                        currentDestination.takeIf { it != MusicDestination.Folder }
                                    pendingFolderTargetKey = key
                                    currentDestination = MusicDestination.Folder
                                },
                            )
                    }
                    actions
                }
                .orEmpty()
        // 1.2.20：TrackActionsOverlay / TrackInfoOverlay 移到浮层栈最末尾（见文件末尾）——
        // Compose 的 BackHandler 按「后注册先响应」分发，此前菜单先于 SearchOverlay/
        // PlaybackOverlay 组合，返回键被它们抢先消费：从搜索进专辑开菜单按返回，
        // 直接退页而菜单挂着（用户实测）。移到最后，返回键先关菜单再逐层退出。
        PlaybackOverlay(
            visible = playbackVisible,
            playbackSettings = playbackSettings,
            ratingOverrides = ratingOverrides,
            onRequestAddToPlaylist = ::requestAddToPlaylist,
            onScratchEnabledChange = { enabled ->
                scope.launch {
                    playbackSettingsStore.setScratchEnabled(enabled)
                }
            },
            onTrackRatingChanged = { mediaId, score ->
                ratingOverrides = ratingOverrides + (mediaId to score.coerceIn(0, 5))
            },
            onFavoriteToggle = ::toggleFavorite,
            onCollapse = {
                playbackVisible = false
            },
            modifier = Modifier.zIndex(3f),
        )
        SearchOverlay(
            visible = searchVisible,
            query = searchQuery,
            mediaItems = visibleLibraryItems,
            hiddenMediaIds = allHiddenMediaIds,
            drilldownTarget = searchDrilldownTarget,
            libraryRefreshVersion = libraryRefreshVersion,
            artistAlbumViewMode = artistAlbumViewMode,
            artistSettings = artistSettings,
            onQueryChange = { value ->
                searchQuery = value
            },
            onDismiss = closeSearchOverlay,
            onOpenPlayback = {
                playbackVisible = true
            },
            onRequestAddToPlaylist = ::requestAddToPlaylist,
            onRequestAddToQueue = ::enqueueMediaItems,
            onTrackMoreClick = { item ->
                showTrackActions(item, TrackActionSource.Library)
            },
            // 搜索结果行「瞄准」图标：关闭搜索 → 跳歌曲页 → 滚动定位到该歌曲（1.2.16 定案）
            onTrackLocateClick = { item ->
                closeSearchOverlay()
                currentDestination = MusicDestination.Songs
                ImmersiveScrollBus.requestScroll(item.mediaId)
            },
            onDrilldownTargetChanged = { target ->
                searchDrilldownTarget = target
            },
            onAlbumClick = { albumId, albumTitle ->
                searchDrilldownTarget =
                    SearchDrilldownTarget.Album(
                        albumId = albumId,
                        albumTitle = albumTitle,
                    )
            },
            onArtistClick = { artistId, artistName ->
                searchDrilldownTarget =
                    SearchDrilldownTarget.Artist(
                        target =
                            ArtistTarget.Albums(
                                artistId = artistId,
                                artistName = artistName,
                            )
                    )
            },
            onToggleArtistAlbumViewMode = {
                val nextMode =
                    if (artistAlbumViewMode == AlbumViewMode.List) {
                        AlbumViewMode.Tile
                    } else {
                        AlbumViewMode.List
                    }
                scope.launch {
                    libraryDisplaySettingsStore.setArtistAlbumViewMode(nextMode)
                }
            },
            modifier = Modifier.fillMaxSize().zIndex(2f),
        )
        PlaybackPlaylistPickerOverlay(
            visible =
                pendingPlaylistPickerMediaItems != null && playbackPlaylistCreateRequest == null,
            playlists = playlists,
            onDismiss = {
                pendingPlaylistPickerMediaItems = null
            },
            onCreateNewPlaylist = {
                scope.launch {
                    playbackPlaylistCreateRequest =
                        PlaylistNameDialogRequest.Create(
                            initialName = playlistRepository.suggestNextUntitledName()
                        )
                }
            },
            onPlaylistSelected = { playlistId ->
                val mediaIds = pendingPlaylistPickerMediaItems?.map(MediaItem::mediaId).orEmpty()
                scope.launch {
                    val result = playlistRepository.addMediaIds(playlistId, mediaIds)
                    when {
                        result.addedCount > 0 -> {
                            Toast.makeText(context, R.string.playlist_added, Toast.LENGTH_SHORT)
                                .show()
                        }
                        result.duplicateCount > 0 -> {
                            Toast.makeText(
                                    context,
                                    R.string.playlist_song_exists,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                    pendingPlaylistPickerMediaItems = null
                }
            },
            modifier = Modifier.fillMaxSize().zIndex(4f),
        )
        PlaylistNameDialogOverlay(
            request = playbackPlaylistCreateRequest,
            onDismiss = {
                playbackPlaylistCreateRequest = null
            },
            onConfirm = { _, input ->
                val mediaIds = pendingPlaylistPickerMediaItems?.map(MediaItem::mediaId).orEmpty()
                scope.launch {
                    when (playlistRepository.createPlaylist(input, mediaIds)) {
                        PlaylistCreateResult.EmptyName -> {
                            Toast.makeText(
                                    context,
                                    R.string.playlist_create_failed,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        PlaylistCreateResult.DuplicateName -> {
                            Toast.makeText(
                                    context,
                                    R.string.playlist_duplicate_name,
                                    Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                        is PlaylistCreateResult.Success -> {
                            playbackPlaylistCreateRequest = null
                            pendingPlaylistPickerMediaItems = null
                            Toast.makeText(context, R.string.playlist_added, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            },
        )
        if (showSongDeleteConfirm) {
            SongDeleteConfirmOverlay(
                onDismiss = {
                    dismissSongDeleteConfirmation()
                },
                onConfirm = {
                    val mediaIds = pendingSongDeleteMediaIds
                    val dismissAction = pendingSongDeleteDismissAction
                    if (mediaIds.isEmpty()) {
                        dismissSongDeleteConfirmation()
                        return@SongDeleteConfirmOverlay
                    }
                    showSongDeleteConfirm = false
                    pendingSongDeleteMediaIds = emptySet()
                    pendingSongDeleteDismissAction = null
                    songsEditMode = false
                    selectedSongIds = emptySet()
                    requestSystemDeleteMediaIds(mediaIds)
                    dismissAction?.invoke()
                },
                modifier = Modifier.fillMaxSize().zIndex(2f),
            )
        }
        NavigationEditorOverlay(
            visible = navigationEditorVisible,
            layout = navigationLayout,
            selectedDestination =
                if (presentedFromMore) MusicDestination.More else currentDestination,
            onDismissRequest = {
                navigationEditorVisible = false
            },
            onCommit = { layout ->
                navigationEditorVisible = false
                scope.launch {
                    navigationSettingsStore.commitLayout(layout)
                }
            },
            modifier = Modifier.fillMaxSize().zIndex(5f),
        )
        // —— 浮层栈最末尾：选择操作菜单 + 歌曲信息 ——
        // 组合顺序最后 = BackHandler 最后注册 = 返回键最高优先级（先关菜单再退页面）
        TrackActionsOverlay(
            visible = pendingTrackActionItem != null,
            actions = trackActionItems,
            onDismissRequest = ::dismissTrackActions,
            modifier = Modifier.fillMaxSize().zIndex(6f),
        )
        TrackInfoOverlay(
            visible = pendingTrackInfoItem != null,
            item = pendingTrackInfoItem,
            onDismissRequest = { pendingTrackInfoItem = null },
            // 标签写回文件后：同步播放队列元数据（小播放条/大播放页立即改名）+
            // 服务端失效媒体库缓存并通知重建（配合 TagOverrideStore 覆盖层），
            // 列表立即刷新，不再等媒体扫描器；静默刷新，不弹 toast
            onSaved = { saved ->
                controller?.updateQueuedTrackMetadata(
                    mediaId = saved.mediaId,
                    title = saved.title,
                    artist = saved.artist,
                    album = saved.album,
                )
                libraryRefreshVersion += 1
            },
            modifier = Modifier.fillMaxSize().zIndex(6f),
        )
    }
}

private val DestinationsWithOwnedTitleBar =
    setOf(
        MusicDestination.Playlist,
        MusicDestination.More,
        MusicDestination.Genre,
        MusicDestination.LovedSongs,
        MusicDestination.Folder,
    )

private fun List<MediaItem>.withRatingOverrides(
    ratingOverrides: Map<String, Int>
): List<MediaItem> {
    if (isEmpty() || ratingOverrides.isEmpty()) {
        return this
    }
    return map { item ->
        val score = ratingOverrides[item.mediaId] ?: return@map item
        item.withPlaybackRating(score)
    }
}
