package com.smartisan.music.ui.songs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.components.*

/** 主排序按钮文案：歌曲名 / 音质 / 播放次数 / 添加时间 */
private val MainSortLabels =
    listOf(
        R.string.sort_by_song_name,
        R.string.sort_by_quality,
        R.string.sort_by_song_play_time,
        R.string.sort_by_added_time,
    )

/** 选中项在文案后追加升降序箭头 */
private fun sortLabel(text: String, activated: Boolean, ascending: Boolean): String {
    if (!activated) return text
    return text + if (ascending) " ↑" else " ↓"
}

/**
 * 歌曲页顶部的分类栏：歌曲名 / 音质 / 播放次数 / 添加时间四项均分整行。
 * 高度与专辑 / 风格等页面顶栏一致；卡片背景与投影由外层统一绘制。
 */
@Composable
internal fun SmartisanSongsSortHeader(
    selection: SongSortSelection,
    onSelectionChange: (SongSortSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectionNow by rememberUpdatedState(selection)
    val onSelectionChangeNow by rememberUpdatedState(onSelectionChange)
    Row(
        modifier
            .fillMaxWidth()
            // 与下方「全部播放 / 随机播放」按钮同一左右边距（6dp），保持两边对齐；
            // 之前满宽铺开，筛选按钮自带的阴影会顶出屏幕边缘
            .padding(horizontal = 6.dp)
            .height(dimensionResource(R.dimen.title_bar_height)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainSortLabels.forEachIndexed { index, text ->
            val activated = selectionNow.main == index
            val resource =
                when (index) {
                    0 -> R.drawable.selector_small_btn_filter_left
                    MainSortLabels.lastIndex -> R.drawable.selector_small_btn_filter_right
                    else -> R.drawable.selector_small_btn_filter_middle
                }
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .smartisanPainterBackground(
                        rememberSmartisanDrawablePainter(resource, activated = activated)
                    )
                    .semantics { this.selected = activated }
                    .clickable(
                        remember { MutableInteractionSource() },
                        null,
                        role = Role.Tab,
                        onClick =
                            smartisanClick {
                                onSelectionChangeNow(
                                    if (selectionNow.main == index) {
                                        // 再次点击同一项：切换升序 / 降序
                                        selectionNow.copy(ascending = !selectionNow.ascending)
                                    } else {
                                        selectionNow.copy(
                                            main = index,
                                            // 歌曲名默认升序，音质 / 播放次数 / 添加时间默认降序
                                            ascending = index == SongSortMainName,
                                        )
                                    },
                                )
                            },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = sortLabel(stringResource(text), activated, selectionNow.ascending),
                    style =
                        TextStyle(
                            fontSize = 13.5.sp,
                            color =
                                smartisanStateColor(
                                    R.color.filter_button_text_color,
                                    activated = activated,
                                ),
                            shadow =
                                Shadow(
                                    smartisanStateColor(
                                        R.color.filter_button_text_shadow_colors,
                                        activated = activated,
                                    ),
                                    Offset(0f, -2f),
                                    .1f,
                                ),
                            textAlign = TextAlign.Center,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                    maxLines = 1,
                )
            }
        }
    }
}
