package com.smartisan.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.smartisan.music.R

/**
 * [1.4.25·R49-③] 列表上下边缘的「滚动渐隐」。
 *
 * 它**不是投影**，别跟顶栏/底栏那套 14dp 投影混为一谈：
 *  - 投影表达「这块界面浮在内容之上」（有实体、有层级）；
 *  - 渐隐表达「列表还有内容，继续滚」（纯提示，高度由图片自带，不共用
 *    `title_bar_shadow_height`）。
 * 之前代码里两者都叫 shadow、都直接吃 `shadow_top` / `shadow_bottom`，
 * 后人很容易按投影规格去调它。这里给一组语义化入口，专门管这两张图。
 *
 * 使用点：播放队列（[com.smartisan.music.ui.playback.PlaybackQueue]）、
 * 播放列表详情（[com.smartisan.music.ui.playlist.PlaylistTrackPages]）。
 */
internal object ScrollFadeEdge {

    /** 列表顶部渐隐（内容向上滚出时的淡出边）。 */
    @Composable
    fun topPainter(): Painter = rememberSmartisanDrawablePainter(R.drawable.shadow_top)

    /** 列表底部渐隐（内容向下滚出时的淡出边）。 */
    @Composable
    fun bottomPainter(): Painter = rememberSmartisanDrawablePainter(R.drawable.shadow_bottom)
}
