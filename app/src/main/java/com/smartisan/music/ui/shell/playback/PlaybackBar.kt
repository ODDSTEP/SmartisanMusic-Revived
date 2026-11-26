package com.smartisan.music.ui.shell.playback

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.smartisan.music.ui.components.smartisanMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.data.settings.MonetStore
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.playback.NowPlayingArtworkRepository
import com.smartisan.music.playback.TranscodeProgressBus
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberIconNameHint
import com.smartisan.music.ui.components.SmartisanTouchShield
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanTextSize
import com.smartisan.music.ui.theme.monetContentBackground
import com.smartisan.music.ui.theme.monetPlaybackGradientEnd
import com.smartisan.music.ui.theme.monetPlaybackSurface
import com.smartisan.music.ui.theme.monetAccentDivider
import com.smartisan.music.ui.shell.BottomChromeShadow

@Composable
internal fun PlaybackBar(
    snapshot: PlaybackBarSnapshot,
    shown: Boolean,
    favoriteIds: Set<String>,
    artworkBitmap: Bitmap?,
    onHidden: () -> Unit,
    onOpenPlayback: () -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    bottomDividerVisible: Boolean = true,
) {
    val offset = remember { Animatable(1f) }
    val latestOnHidden = rememberUpdatedState(onHidden)
    // A cancelling show/hide effect never delivers the preceding hide's completion callback.
    LaunchedEffect(shown) {
        if (!shown && offset.value == 1f) {
            latestOnHidden.value()
            return@LaunchedEffect
        }
        offset.animateTo(
            if (shown) 0f else 1f,
            tween(
                300,
                easing = {
                    val inverse = it - 1f
                    inverse * inverse * inverse + 1f
                },
            ),
        )
        if (!shown) latestOnHidden.value()
    }
    var retainedItem by remember { mutableStateOf(snapshot.mediaItem) }
    SideEffect { snapshot.mediaItem?.let { retainedItem = it } }
    val mediaItem = snapshot.mediaItem ?: retainedItem
    val active = shown || offset.value < 1f
    // 单击打开播放页；双击只定位当前打开列表中的这首歌，不进入大播放页
    val latestMediaItem by rememberUpdatedState(mediaItem)
    val latestOnOpenPlayback by rememberUpdatedState(onOpenPlayback)
    val barTapModifier =
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { latestOnOpenPlayback() },
                onDoubleTap = {
                    com.smartisan.music.ui.shell.ImmersiveScrollBus.requestScroll(
                        latestMediaItem?.mediaId
                    )
                },
            )
        }
    val configuration = LocalViewConfiguration.current
    val exactTargets =
        remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
    Box(modifier) {
        if (active && mediaItem != null) {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalViewConfiguration provides exactTargets,
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationY = size.height * offset.value
                    }
                ) {
                    SmartisanTouchShield()
                    Box(Modifier.fillMaxSize()) {
                        // 1.2.33：逐字恢复 1.2.25 的顶部 6dp 极淡投影，作为小播放条与
                        // 上方列表的分界（图2 老版本正是这样，上下各一条细线、紧贴无缝）。
                        // 1.2.32 误把它当成「灰条」删掉却没改容器高度：67dp 的容器里只剩
                        // 61dp 内容、Column 自上而下排 → 底部空出 6dp 透出页面底色，就是
                        // 用户看到的「小播放条与底部导航栏之间一大块灰色空隙」，同时上方
                        // 也彻底失去分界。这里加回投影，并让内容用 weight(1f) 填满剩余
                        // 高度 —— 无论宿主给多高都精确贴满，绝不再留任何空白。
                        // 1.3.4：顶部 14dp 分界阴影改为不占高度的浮层，压在上方列表底部，
                        // 与主界面顶栏底部 14dp 阴影同一套做法；内容区吃满整个 50dp。
                        // [1.4.25·R49-②] C 类投影收口到 BottomChromeShadow（与底栏共用同一份
                        // 尺寸/翻转/偏移实现）。zIndex(2) 保证它压在上方列表之上可见。
                        BottomChromeShadow(
                            Modifier.align(Alignment.TopCenter).zIndex(2f)
                        )
                        // [v16·播放栏渐变过渡] 方案 A（开）：白卡片 + 莫奈渐变，底部纵向渐变
                        // 自然收口到底栏色，与列表/底栏连成连续光谱；方案 B（关）：容器统一，
                        // 与底栏同底色同强度 tint。
                        val playbackTransition by MonetStore.playbackTransition.collectAsState()
                        // [#98·小播放栏/底栏不同色根治] 播放栏紧贴的是底栏**内容区顶部**，其色为
                        // bottom_bar_gradient_top（比 bottom_bar_surface 亮一档）。旧代码两种方案都
                        // 用 bottom_bar_surface 收口/铺底，与底栏顶部 gradient_top 对不上 → 色差分界。
                        // 改用 gradient_top：方案A 底部渐变收口到它，方案B 纯色与它同色，均无缝。
                        // [v19·小播放栏归属] 归「背景莫奈化」：只开莫奈主开关不开背景莫奈化时保持官方色。
                        // [v20·渐变放行] 但方案 A 的渐变是"收口到底栏色"，底栏吃的是莫奈主开关，
                        // 所以方案 A 的收口色改走 monetPlaybackGradientEnd（主开关即放行），
                        // 方案 B 的纯色底仍归背景莫奈化——只开主开关时也能看到从白到莫奈色的过渡。
                        val bottomBarColor =
                            if (playbackTransition) {
                                monetPlaybackGradientEnd(R.color.bottom_bar_gradient_top, 0.12f)
                            } else {
                                monetPlaybackSurface(R.color.bottom_bar_gradient_top, 0.12f)
                            }
                        Column(
                            Modifier.fillMaxSize()
                                .then(
                                    if (playbackTransition) {
                                        // [v11] 小播放栏白底改用大播放页同款莫奈渐变，与列表/排序栏连贯。
                                        Modifier
                                            .monetContentBackground(colorResource(R.color.surface_card))
                                            .drawBehind {
                                                val h = PlaybackBarTransitionHeight.toPx()
                                                drawRect(
                                                    brush =
                                                        Brush.verticalGradient(
                                                            colors =
                                                                listOf(
                                                                    Color.Transparent,
                                                                    bottomBarColor,
                                                                ),
                                                            startY = size.height - h,
                                                            endY = size.height,
                                                        )
                                                )
                                            }
                                    } else {
                                        Modifier.background(bottomBarColor)
                                    }
                                )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 1.3.5：与专辑页·列表模式同名元素对齐
                                // （封面左边距 18dp / 封面 48dp / 文字缩进 12dp），
                                // 字号本就同为 text_size_medium + text_size_small，不改动。
                                val coverPadding =
                                    dimensionResource(R.dimen.listview_items_margin_left)
                                val coverSize =
                                    dimensionResource(R.dimen.album_list_item_image_width)
                                val textInset =
                                    dimensionResource(R.dimen.common_padding_left)
                                Box(
                                    Modifier.padding(start = coverPadding)
                                        .size(coverSize)
                                        .then(barTapModifier)
                                ) {
                                    if (artworkBitmap != null) {
                                        Image(
                                            artworkBitmap.asImageBitmap(),
                                            null,
                                            Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds,
                                        )
                                    } else {
                                        Image(
                                            rememberSmartisanDrawablePainter(
                                                R.drawable.noalbumcover_220
                                            ),
                                            null,
                                            Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds,
                                        )
                                    }
                                }
                                val metadata = mediaItem.mediaMetadata
                                val title =
                                    metadata.displayTitle?.toString()
                                        ?: metadata.title?.toString()
                                        ?: stringResource(R.string.unknown_song_title)
                                // 1.2.27：DFF/APE 首次播放要先转码，这里的副标题让位给
                                // 「正在转换 xx%」——否则用户点完几秒没声音，会以为没反应。
                                val transcodeProgress by
                                    TranscodeProgressBus.state.collectAsState()
                                val converting = transcodeProgress
                                val artist =
                                    when {
                                        converting != null ->
                                            stringResource(
                                                R.string.playback_converting,
                                                converting.percent,
                                            )
                                        snapshot.isPlaybackBuffering ->
                                            stringResource(R.string.playback_buffering)
                                        else ->
                                            metadata.subtitle?.toString()
                                                ?: metadata.artist?.toString()
                                                ?: stringResource(R.string.unknown_artist)
                                    }
                                Box(
                                    Modifier.weight(1f)
                                        .padding(start = textInset)
                                        .clipToBounds()
                                        .then(barTapModifier),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Column(
                                        Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                                            .width(
                                                dimensionResource(R.dimen.playback_bar_text_width)
                                            )
                                            .padding(end = textInset)
                                    ) {
                                        BasicText(
                                            title,
                                            Modifier.fillMaxWidth().smartisanMarquee(),
                                            style =
                                                TextStyle(
                                                    fontSize =
                                                        smartisanTextSize(R.dimen.text_size_medium),
                                                    color =
                                                        colorResource(R.color.list_item_first_line),
                                                    platformStyle =
                                                        PlatformTextStyle(
                                                            includeFontPadding = true
                                                        ),
                                                ),
                                            maxLines = 1,
                                        )
                                        BasicText(
                                            artist,
                                            Modifier.fillMaxWidth()
                                                .padding(
                                                    top =
                                                        dimensionResource(
                                                            R.dimen.listview_items_margin_top1
                                                        )
                                                )
                                                // [1.4.24·R35] 副标题与标题同款滚动：
                                                // 之前只有省略号，长歌手名/转码状态被硬截，
                                                // 与设置页的跑马灯也不一致。
                                                .smartisanMarquee(),
                                            style =
                                                TextStyle(
                                                    fontSize =
                                                        smartisanTextSize(R.dimen.text_size_small),
                                                    color =
                                                        colorResource(
                                                            R.color.list_item_second_line
                                                        ),
                                                    platformStyle =
                                                        PlatformTextStyle(
                                                            includeFontPadding = true
                                                        ),
                                                ),
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                                val gap = dimensionResource(R.dimen.float_button_margin)
                                Row(
                                    Modifier.padding(end = gap).fillMaxHeight(),
                                    horizontalArrangement = Arrangement.spacedBy(gap),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val favorite =
                                        mediaItem.mediaId in favoriteIds &&
                                            !mediaItem.isExternalAudioLaunchItem()
                                    PlaybackBarButton(
                                        if (favorite) R.drawable.float_favor_cancel_selector
                                        else R.drawable.float_favor_add_selector,
                                        stringResource(
                                            if (favorite) R.string.cancel_love else R.string.love
                                        ),
                                        { onToggleFavorite(mediaItem) },
                                        enabled = !mediaItem.isExternalAudioLaunchItem(),
                                    )
                                    PlaybackBarButton(
                                        R.drawable.float_btn_prev_selector,
                                        stringResource(R.string.previous_song),
                                        onPrevious,
                                    )
                                    PlaybackBarButton(
                                        if (snapshot.isPlaybackActive)
                                            R.drawable.float_btn_pause_selector
                                        else R.drawable.float_btn_play_selector,
                                        stringResource(
                                            if (snapshot.isPlaybackActive) R.string.pause
                                            else R.string.play
                                        ),
                                        onPlayPause,
                                    )
                                    PlaybackBarButton(
                                        R.drawable.float_btn_next_selector,
                                        stringResource(R.string.next_song),
                                        onNext,
                                    )
                                }
                            }
                            if (bottomDividerVisible) {
                                // [v18·衔接细线] 仅背景莫奈化开+渐变过渡关（播放栏与底栏连片）时，
                                // 用加深的 accent 色作明显分界；其余情况保持官方 nav_list_line。
                                val accentDivider = monetAccentDivider()
                                Spacer(
                                    Modifier.fillMaxWidth()
                                        .height(dimensionResource(R.dimen.nav_divider_height))
                                        .background(accentDivider ?: colorResource(R.color.nav_list_line))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackBarButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    Image(
        rememberSmartisanDrawablePainter(icon, enabled = enabled, pressed = pressed),
        description,
        Modifier
            .then(rememberIconNameHint(description))
            .clickable(
                interaction,
                null,
                enabled = enabled,
                role = Role.Button,
                onClick = smartisanClick(onClick),
            ),
    )
}

internal data class PlaybackBarSnapshot(
    val mediaItem: MediaItem? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
) {
    val isPlaybackActive: Boolean
        get() = isPlaying || (playWhenReady && isBuffering)

    val isPlaybackBuffering: Boolean
        get() = playWhenReady && isBuffering
}

internal fun Player?.playbackBarSnapshot(): PlaybackBarSnapshot {
    val player = this ?: return PlaybackBarSnapshot()
    return PlaybackBarSnapshot(
        mediaItem = player.currentMediaItem,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
    )
}

internal suspend fun loadArtworkBitmap(
    context: android.content.Context,
    mediaItem: MediaItem,
): Bitmap? = NowPlayingArtworkRepository.load(context, mediaItem, PlaybackBarArtworkDecodeSize)

internal fun peekArtworkBitmap(mediaItem: MediaItem): Bitmap? =
    NowPlayingArtworkRepository.peek(mediaItem, PlaybackBarArtworkDecodeSize)

private val PlaybackBarArtworkDecodeSize = Size(128, 128)
/** [v16] 方案 A 中小播放栏底部过渡到底栏色的渐变高度。 */
private val PlaybackBarTransitionHeight = 14.dp
