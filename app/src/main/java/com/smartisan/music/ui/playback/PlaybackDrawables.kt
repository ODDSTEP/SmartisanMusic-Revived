package com.smartisan.music.ui.playback

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick

@Composable
internal fun PressedDrawableButton(
    @DrawableRes normalRes: Int,
    @DrawableRes pressedRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()
    // [1.4.28] 循环/随机键的 normalRes 与 pressedRes 是同一张图（工程里没有 _down 资源），
    // 按下完全没反馈，五个键里只有这三个有反馈、那两个像没点到。
    // 同图时改用透明度反馈，不新增资源也能让按键有明确的按下态。
    val sameRes = normalRes == pressedRes

    Box(
        modifier =
            modifier
                .graphicsLayer { alpha = if (sameRes && pressed) 0.55f else 1f }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = smartisanClick(onClick),
                ),
        contentAlignment = Alignment.Center,
    ) {
        AndroidDrawableImage(
            drawableRes = if (pressed) pressedRes else normalRes,
            modifier = Modifier.matchParentSize(),
            contentDescription = contentDescription,
        )
    }
}

@Composable
internal fun AndroidDrawableImage(
    @DrawableRes drawableRes: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = rememberSmartisanDrawablePainter(drawableRes),
        contentDescription = contentDescription,
        contentScale = ContentScale.FillBounds,
        modifier = modifier,
    )
}
