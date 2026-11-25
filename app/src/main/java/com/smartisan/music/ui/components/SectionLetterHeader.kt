package com.smartisan.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.theme.monetSeparatorLetterColor
import com.smartisan.music.ui.theme.monetSurfaceContainer

/**
 * 歌曲页按歌名排序时使用的首字母分隔栏。
 *
 * [1.4.28] 用户拍板：配色方案只保留「同色深浅」一种。
 * 已删除「同色+细线」「淡渐变」两套方案：
 *  - M3 的 Divider 规范只有 1dp outlineVariant 线，分组标题没有渐变这回事；
 *  - 渐变是弱化边界的手法，与「要明显分隔」的目标相反；
 *  - 真正让分隔栏被看见的是色差本身（浅色 8.2% / 深色约 7%），不是线也不是渐变。
 * 字母颜色由 [monetSeparatorLetterColor] 按底色对比度自动决定，没有开关。
 */
@Composable
internal fun SectionLetterHeader(letter: String, modifier: Modifier = Modifier) {
    val containerColor = monetSurfaceContainer()
    // [1.4.29·R121] 传入本处真实底色再算对比度。
    val letterColor = monetSeparatorLetterColor(containerColor)
    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .then(
                if (containerColor == null) {
                    Modifier.smartisanPainterBackground(
                        rememberSmartisanDrawablePainter(R.drawable.smartlist_header_bg)
                    )
                } else {
                    Modifier.background(containerColor)
                }
            )
            .padding(start = dimensionResource(R.dimen.listview_items_margin_left)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            letter,
            style =
                TextStyle(
                    color = letterColor ?: colorResource(R.color.title_text_color),
                    fontSize = 13.5.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
        )
    }
}
