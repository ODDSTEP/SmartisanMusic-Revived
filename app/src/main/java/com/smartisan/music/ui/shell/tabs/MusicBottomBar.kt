package com.smartisan.music.ui.shell.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import com.smartisan.music.R
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.theme.monetSurface
import com.smartisan.music.ui.shell.BottomChromeShadow

@Composable
internal fun MusicBottomBar(
    currentDestination: MusicDestination,
    destinations: List<MusicDestination>,
    onDestinationSelected: (MusicDestination) -> Unit,
    onEditRequested: () -> Unit,
    modifier: Modifier = Modifier,
    topChromeVisible: Boolean = true,
) {
    // 底栏由两层构成：外层纯色一直延伸到导航栏底部（含手势条区域），
    // 内容区固定高度并复刻原背景图的纵向渐变。这样无论系统导航栏多高，
    // 底栏内容的位置与大小都完全一致，不再随导航栏高度拉伸背景图。
    Box(
        modifier
            .fillMaxWidth()
            .background(monetSurface(R.color.bottom_bar_surface, 0.12f))
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.smartisan_tabswitch_tabbar_height))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            monetSurface(R.color.bottom_bar_gradient_top, 0.12f),
                            monetSurface(R.color.bottom_bar_surface, 0.12f),
                        )
                    )
                )
        ) {
            destinations.forEach { destination ->
                SmartisanBottomTabItem(
                    destination,
                    destination == currentDestination,
                    onClick = {
                        if (destination != currentDestination) onDestinationSelected(destination)
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onLongClick = onEditRequested,
                )
            }
        }
        if (topChromeVisible) {
            // [1.4.25·R49-②] 与小播放栏共用同一份 C 类投影实现（尺寸/翻转/偏移一处维护）。
            BottomChromeShadow(Modifier.align(Alignment.TopCenter))
            Spacer(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.nav_divider_height))
                    .background(colorResource(R.color.nav_list_line))
            )
        }
    }
}
