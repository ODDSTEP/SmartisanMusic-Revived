package com.smartisan.music.ui.songs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 1.3.4：把歌曲页右侧字母栏（点击 / 按住上下滑 / 左滑展开大字母表 / 点选）
 * 复用到专辑、艺术家、风格页。分组口径与歌曲页一致：A-Z 与「#」。
 */
private fun String.quickBarLetter(): String {
    val c = firstOrNull { it.isLetterOrDigit() } ?: return "#"
    val u = c.uppercaseChar()
    return if (u in 'A'..'Z') u.toString() else "#"
}

@Composable
internal fun AlphabetQuickBar(
    labels: List<String>,
    listState: LazyListState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    AlphabetQuickBarImpl(labels, modifier) { _, index ->
        if (index >= 0) scope.launch { listState.scrollToItem(index) }
    }
}

@Composable
internal fun AlphabetQuickBar(
    labels: List<String>,
    listState: LazyListState,
    gridState: LazyGridState,
    viewIsList: Boolean,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    AlphabetQuickBarImpl(labels, modifier) { _, index ->
        if (index < 0) return@AlphabetQuickBarImpl
        scope.launch {
            if (viewIsList) listState.scrollToItem(index) else gridState.scrollToItem(index)
        }
    }
}

/**
 * 分组列表用：字母直接映射到 Header 所在行索引（首字母经拼音归一化，'#' 置尾）。
 * 供艺术家 / 专辑列表页复用，保持「超过 30 项才显示」的既有行为。
 */
@Composable
internal fun AlphabetQuickBar(
    labelCount: Int,
    headerIndices: Map<String, Int>,
    listState: LazyListState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    if (labelCount <= 30) return
    Box(modifier.fillMaxSize()) {
        SmartisanQuickBar(
            onLetter = { letter ->
                val index = headerIndices[letter]
                if (index != null && index >= 0) scope.launch { listState.scrollToItem(index) }
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun AlphabetQuickBarImpl(
    labels: List<String>,
    modifier: Modifier,
    onIndex: (String, Int) -> Unit,
) {
    if (labels.size <= 30) return
    Box(modifier.fillMaxSize()) {
        SmartisanQuickBar(
            onLetter = { letter ->
                onIndex(letter, labels.indexOfFirst { it.quickBarLetter() == letter })
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
