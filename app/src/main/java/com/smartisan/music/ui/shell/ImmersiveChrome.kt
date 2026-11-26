package com.smartisan.music.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 沉浸式浏览：有歌曲正在播放时，用户在列表内下滑浏览内容会收起
 * 底部导航栏、播放操作行和歌曲页排序行；上滑则恢复。
 */
internal val LocalImmersiveCollapsed = staticCompositionLocalOf { false }

/** 非沉浸场景（无播放、弹窗覆盖等）下保持展开 */
internal val LocalImmersiveEnabled = staticCompositionLocalOf { false }

/**
 * 1.3.7：歌曲页顶栏倒三角只负责「排序栏」的收起/展开，与沉浸式彻底解耦，
 * 不再禁用沉浸、也不再被沉浸滚动带着变。
 */
internal val LocalChromeManuallyCollapsed = staticCompositionLocalOf { false }

/** 1.3.7：只包「播放操作行」（播放全部/随机播放），只受沉浸式滚动控制。 */
@Composable
internal fun ImmersiveActionRowVisibility(content: @Composable () -> Unit) {
    val enabled = LocalImmersiveEnabled.current
    val collapsed = LocalImmersiveCollapsed.current
    AnimatedVisibility(
        visible = !enabled || !collapsed,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = Modifier,
    ) {
        content()
    }
}

/** 1.3.7：只包「排序栏」，只受顶栏收起/展开开关控制，完全不看沉浸式脸色。 */
@Composable
internal fun ChromeSortBarVisibility(content: @Composable () -> Unit) {
    val manuallyCollapsed = LocalChromeManuallyCollapsed.current
    AnimatedVisibility(
        visible = !manuallyCollapsed,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = Modifier,
    ) {
        content()
    }
}

/** 双击播放条时向当前打开的列表广播“定位到正在播放的歌曲” */
internal object ImmersiveScrollBus {
    var requestVersion by mutableIntStateOf(0)
        private set
    var targetMediaId: String? = null
        private set

    fun requestScroll(mediaId: String?) {
        if (mediaId.isNullOrBlank()) {
            return
        }
        targetMediaId = mediaId
        requestVersion++
    }
}

/** 滚动到指定下标并尽量把该行居中显示 */
internal suspend fun LazyListState.scrollToItemCentered(index: Int) {
    if (index < 0) {
        return
    }
    val viewportHeight = layoutInfo.viewportSize.height
    if (viewportHeight <= 0) {
        animateScrollToItem(index)
        return
    }
    val itemHeight =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
    val centeredOffset =
        if (itemHeight in 1 until viewportHeight) {
            -((viewportHeight - itemHeight) / 2)
        } else {
            0
        }
    animateScrollToItem(index, scrollOffset = centeredOffset)
}

/** 当前打开的列表订阅定位广播：正在播放的歌曲在列表内则滚动并居中 */
@Composable
internal fun ImmersiveLocateEffect(
    list: LazyListState,
    indexOfMediaId: () -> Int,
) {
    val version = ImmersiveScrollBus.requestVersion
    LaunchedEffect(version) {
        if (version == 0) {
            return@LaunchedEffect
        }
        val target = ImmersiveScrollBus.targetMediaId ?: return@LaunchedEffect
        val index = indexOfMediaId()
        if (index >= 0) {
            list.scrollToItemCentered(index)
        }
    }
}
