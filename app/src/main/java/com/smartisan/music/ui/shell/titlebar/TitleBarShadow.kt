package com.smartisan.music.ui.shell.titlebar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.smartisan.music.R
import com.smartisan.music.ui.components.SmartisanDrawableBackground

/**
 * A 类投影：**顶栏（上沿固定件）向内容方向的 14dp 投影**，全 App 唯一入口。
 *
 * 用法：画在顶栏正下方、且位于内容层之上的位置，例如
 * ```
 * TitleBarShadow(
 *     Modifier.align(Alignment.TopCenter)
 *         .offset(y = titleAreaHeight)
 *         .fillMaxWidth()
 *         .height(titleShadowHeight)
 *         .zIndex(1f)
 * )
 * ```
 *
 * 规格（与 `now_playing_bar_shadow` / `tab_bar_shadow` 是同一张图、同一档 14dp）：
 *  - 素材 `title_bar_shadow.9.png`，三张图 md5 相同，本就是同一标准件；
 *  - 高度统一取 `R.dimen.title_bar_shadow_height`，不许各页面自己写 dp。
 *
 * ⚠️ **硬规则（[1.4.25·R49-⑤]，R30 血案不许复发）**：
 * 投影必须画在宿主**裁剪边界之外**，或者宿主显式 `clipToBounds = false`。具体说：
 *  1. 不要把投影塞进 `AnimatedVisibility` / `clip(shape)` / `clipToBounds = true`
 *     的容器里 —— 它会被裁掉，表现为"这一页没有阴影"；
 *  2. 卡片类（B 类）投影要用 `drawBehind` 画到**内容下界之外**，不能画在卡片内部；
 *  3. 下沿固定件（C 类）请用 `BottomChromeShadow`，别在这里另写一份。
 */
@Composable
internal fun TitleBarShadow(modifier: Modifier = Modifier) {
    SmartisanDrawableBackground(R.drawable.title_bar_shadow, modifier)
}
