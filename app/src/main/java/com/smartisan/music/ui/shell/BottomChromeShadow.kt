package com.smartisan.music.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import com.smartisan.music.R
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground

/**
 * [1.4.25·R49-②] C 类投影：**下沿固定件（小播放栏 / 底部导航）向页面方向的投影**。
 *
 * 原来是两段各自手写的代码：
 *  - 小播放栏 `PlaybackBar`：Image + height + offset(-h) + scaleY(-1) + zIndex(2)；
 *  - 底部导航 `MusicBottomBar`：Spacer + 同款 height/offset/翻转 + painter 背景。
 * 两处参数完全一致（都是 `title_bar_shadow_height` = 14dp、都翻转 180°、都画在栏体
 * 上界之外），只是写法不同 —— 改一处忘另一处的风险极高，故收口到这里。
 *
 * 使用方式：把它作为栏体内部 Box 的第一个子元素，传 `Modifier.align(TopCenter)`，
 * 需要盖住上方内容时再加 `zIndex`。
 *
 * 关键约束（R30 复现过的坑）：`offset(y = -h)` 把它挪到父容器边界**之外**，
 * 所以宿主容器绝不能带 `clipToBounds = true` / `clip(shape)`，否则投影被裁掉、
 * 看起来"没有阴影"。见 [TitleBarShadow] 顶部同一条硬规则。
 *
 * 说明：两栏上下相邻时两者都存在，但**只有最上面那一层可见**（下面那层被栏体挡住），
 * 所以不必担心叠加变黑。
 */
@Composable
internal fun BottomChromeShadow(modifier: Modifier = Modifier) {
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
    androidx.compose.foundation.layout.Spacer(
        modifier
            .fillMaxWidth()
            .height(shadowHeight)
            .offset(y = -shadowHeight)
            // 阴影图本身「上深下浅」；画在栏体上方时需上下翻转，让贴着栏体的一侧深、
            // 向上渐淡。只翻转绘制，不改 9-patch 资源本身。
            .graphicsLayer { scaleY = -1f }
            .smartisanPainterBackground(rememberSmartisanDrawablePainter(R.drawable.now_playing_bar_shadow))
    )
}
