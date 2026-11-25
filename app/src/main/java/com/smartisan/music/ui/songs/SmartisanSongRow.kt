package com.smartisan.music.ui.songs

import android.widget.Toast
import com.smartisan.music.ui.components.smartisanMarquee
import com.smartisan.music.playback.isDsdItem
import com.smartisan.music.playback.PlaybackPolicy
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.isPlaybackActiveForUi
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.theme.themedListRowBackground

internal data class SongPlaybackState(val mediaId: String?, val playing: Boolean)

@Composable
internal fun rememberSongPlaybackState(
    browser: Player? = LocalPlaybackBrowser.current
): SongPlaybackState {
    var state by
        remember(browser) {
            mutableStateOf(
                SongPlaybackState(
                    browser?.currentMediaItem?.mediaId,
                    browser?.isPlaybackActiveForUi() == true,
                )
            )
        }
    DisposableEffect(browser) {
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    state =
                        SongPlaybackState(
                            player.currentMediaItem?.mediaId,
                            player.isPlaybackActiveForUi(),
                        )
                }
            }
        browser?.addListener(listener)
        onDispose { browser?.removeListener(listener) }
    }
    return state
}

/** One row contract for song destinations; the service remains the playback state owner. */
@Composable
internal fun SmartisanSongRow(
    item: MediaItem,
    onClick: () -> Unit,
    onMoreClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    selected: Boolean = false,
    onSelectionChange: (Boolean) -> Unit = {},
    displayMode: SongSortDisplayMode = SongSortDisplayMode.Name,
    durationText: String? = null,
    contentEndInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** 1.2.23：实时播放次数（来自 Room 流）。非空时优先于 MediaItem extras 的静态快照，使「按播放次数」即时刷新。 */
    livePlayCount: Long? = null,
    subtitle: String? = null,
    playback: SongPlaybackState = rememberSongPlaybackState(),
    onCheckboxBoundsChanged: (Rect?) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val progress by
        animateFloatAsState(
            if (editMode) 1f else 0f,
            tween(200, easing = SmartisanEaseInOut),
            label = "songEdit",
        )
    val checkbox =
        rememberSmartisanDrawablePainter(R.drawable.check_box_selector, checked = selected)
    val density = LocalDensity.current
    val checkWidth = with(density) { checkbox.intrinsicSize.width.toDp() }
    val checkInset = dimensionResource(R.dimen.check_box_margin_left)
    val metadata = item.mediaMetadata
    val title =
        metadata.displayTitle?.toString()
            ?: metadata.title?.toString()
            ?: stringResource(R.string.unknown_song_title)
    val artist =
        metadata.artist?.toString()
            ?: metadata.subtitle?.toString()
            ?: stringResource(R.string.unknown_artist)
    val album = metadata.albumTitle?.toString()?.takeIf(String::isNotBlank)
    val secondary =
        subtitle
            ?: if (album == null) artist else "$artist - $album"
    val textDirection =
        if (
            androidx.compose.ui.platform.LocalLayoutDirection.current ==
                androidx.compose.ui.unit.LayoutDirection.Rtl
        )
            androidx.compose.ui.text.style.TextDirection.ContentOrRtl
        else androidx.compose.ui.text.style.TextDirection.ContentOrLtr
    val playing = !editMode && item.mediaId == playback.mediaId
    // 1.3.5 实验性功能：不播放 DSD 格式音频时，该行变灰且不可点击
    val excludeDsd by PlaybackPolicy.excludeDsdFlow.collectAsState()
    val dsdFiltered = excludeDsd && item.isDsdItem()
    // 1.3.6：点击被过滤的 DSD 行时弹出与「播放失败 / 不支持的音乐格式」同款的悬浮提示
    val context = androidx.compose.ui.platform.LocalContext.current
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
                    enabled = true,
                    role = if (editMode) Role.Checkbox else Role.Button,
                    onClick =
                        smartisanClick {
                            when {
                                // 1.3.7：编辑模式优先——DSD 行也要能直接勾选，不弹播放提示
                                editMode -> onSelectionChange(!selected)
                                dsdFiltered ->
                                    Toast.makeText(
                                        context,
                                        R.string.dsd_filtered_click_hint,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                else -> onClick()
                            }
                        },
                )
                .padding(end = contentEndInset)
                // 1.3.7：非编辑模式整行维持半透明；编辑模式整行恢复正常不透明
                .alpha(if (dsdFiltered && !editMode) 0.35f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width((checkWidth + checkInset) * progress).fillMaxHeight()) {
                if (progress > 0f)
                    Image(
                        checkbox,
                        null,
                        Modifier.align(Alignment.CenterEnd)
                            .requiredWidth(checkWidth)
                            .graphicsLayer { alpha = progress }
                            .smartisanCheckboxBounds(onCheckboxBoundsChanged),
                    )
            }
            Spacer(Modifier.width(dimensionResource(R.dimen.listview_items_margin_left)))
            Column(Modifier.weight(1f)) {
                SmartisanPlayingTitle(
                    title,
                    playing,
                    playback.playing,
                    smartisanTextSize(R.dimen.text_size_large),
                    textDirection = textDirection,
                    pressed = pressed,
                )
                Row(
                    Modifier.padding(
                        top = dimensionResource(R.dimen.listview_item_line_two_paddingtop)
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.qualityBadge()?.let { badge ->
                        Image(
                            rememberSmartisanDrawablePainter(badge),
                            null,
                            Modifier.padding(
                                end = dimensionResource(R.dimen.album_song_index_margin_left)
                            )
                                // 1.3.7：编辑模式整行恢复不透明后，只让 DSD 徽章保持灰
                                .alpha(if (dsdFiltered && editMode) 0.35f else 1f),
                        )
                    }
                    BasicText(
                        secondary,
                        style =
                            TextStyle(
                                textDirection = textDirection,
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
            }
            if (displayMode == SongSortDisplayMode.PlayCount) {
                BasicText(
                    (livePlayCount ?: item.songPlayCount()).toString(),
                    Modifier.padding(start = 18.dp),
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
            } else if (displayMode == SongSortDisplayMode.Quality) {
                val summary = songQualitySummary(item)
                if (summary != null) {
                    BasicText(
                        summary,
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
                        maxLines = 1,
                    )
                }
            } else if (displayMode == SongSortDisplayMode.AddedTime) {
                // 按添加时间排序时，行尾给出日期（文件写入手机的时间），便于核对排序结果
                val addedText = item.songAddedTimeText()
                if (addedText.isNotEmpty()) {
                    BasicText(
                        addedText,
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
                        maxLines = 1,
                    )
                }
            }
            if (durationText != null)
                BasicText(
                    durationText,
                    Modifier.padding(horizontal = 4.dp),
                    style =
                        TextStyle(
                            color =
                                smartisanPressedTextColor(
                                    colorResource(R.color.list_text_color_small),
                                    pressed,
                                ),
                            fontSize = smartisanTextSize(R.dimen.text_size_micro),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
            if (progress < 1f) {
                val moreInteraction = remember { MutableInteractionSource() }
                val morePressed by moreInteraction.collectSmartisanPressedAsState()
                Image(
                    rememberSmartisanDrawablePainter(
                        R.drawable.btn_more_selector,
                        pressed = pressed || morePressed,
                    ),
                    stringResource(R.string.tab_more),
                    Modifier.graphicsLayer { alpha = 1f - progress }
                        .clickable(
                            moreInteraction,
                            null,
                            enabled = !editMode,
                            role = Role.Button,
                            onClick = smartisanClick { onMoreClick(item) },
                        ),
                )
            }
        }
    }
}


internal fun MediaItem.qualityBadge(): Int? =
    when (mediaMetadata.extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey)) {
        "flac" -> R.drawable.audio_quality_flac
        "ape" -> R.drawable.audio_quality_ape
        "wav" -> R.drawable.audio_quality_wav
        "aiff" -> R.drawable.audio_quality_aiff
        "alac" -> R.drawable.audio_quality_alac
        "cue" -> R.drawable.audio_quality_cue
        "dsd" -> R.drawable.audio_quality_dsd
        else -> null
    }

@Composable
internal fun SmartisanPlayingTitle(
    title: String,
    playing: Boolean,
    isPlaying: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    includeFontPadding: Boolean = true,
    textDirection: androidx.compose.ui.text.style.TextDirection =
        androidx.compose.ui.text.style.TextDirection.Content,
    pressed: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            title,
            Modifier.weight(1f, fill = false).smartisanMarquee(),
            style =
                TextStyle(
                    textDirection = textDirection,
                    color =
                        smartisanPressedTextColor(
                            colorResource(R.color.setting_item_text_color),
                            pressed,
                        ),
                    fontSize = fontSize,
                    platformStyle = PlatformTextStyle(includeFontPadding = includeFontPadding),
                ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (playing)
            Image(
                rememberSmartisanDrawablePainter(
                    if (isPlaying) R.drawable.playing_blueplay2_selector
                    else R.drawable.playing_bluepause_selector,
                    pressed = pressed,
                ),
                null,
                Modifier.padding(start = 10.dp),
            )
    }
}

internal fun songQualitySummary(item: androidx.media3.common.MediaItem): String? {
    val hz = item.songSampleRateHz()
    if (hz <= 0 && item.songBitrateBps() <= 0) return null
    return com.smartisan.music.platform.media.AudioQualityTiers.summary(
        sampleRateHz = hz,
        bitsPerSample = item.songBitsPerSample(),
        bitrateBps = item.songBitrateBps(),
        container = "",
    )
}
