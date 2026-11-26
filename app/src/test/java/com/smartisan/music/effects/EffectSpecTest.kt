package com.smartisan.music.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [效果验证] 参考包效果规格断言。
 *
 * 这不是截图对比，而是把"效果对不对"变成**可机器判定的数值规则**：
 * 置灰到 0.3（不是透明 0）、背景不参与、眨眼 16 帧、分界线随隐藏数严格递增且不封顶。
 * 任何实现想声称"做了这个效果"，必须拿这些断言去比对，而不是 grep 到字段就算数。
 */
class EffectSpecTest {

    @Test
    fun `置灰的终点是0点3不是透明0 —— 证明是置灰不是透明`() {
        // 用户反复强调：闭眼是"置灰"（浅→最深灰），不是"透明"（alpha→0 透出底层竖条纹）。
        // 参考包终点 0.3；任何做成 0 或作用在整行背景上的实现都不符合本规格。
        assertTrue(
            "闭眼 alpha 必须 > 0，否则是透明不是置灰",
            ReferenceEffectSpec.DIM_ALPHA_CLOSED > 0f,
        )
        assertEquals(0.3f, ReferenceEffectSpec.DIM_ALPHA_CLOSED, 0.0001f)
        assertTrue(
            "睁眼→闭眼 alpha 必须单调下降",
            ReferenceEffectSpec.DIM_ALPHA_CLOSED < ReferenceEffectSpec.DIM_ALPHA_OPEN,
        )
    }

    @Test
    fun `置灰只作用文字层 背景alpha恒为1 —— 证明背景不透出`() {
        // 参考包 brightAlpha 只挂在文字 Column（FolderPage.kt:609），背景 Row 不动。
        // 当前歌曲页 SmartisanSwipeDeleteRow.kt:267 把 alpha 挂在整行 Box（含背景），
        // 导致闭眼后整行透出 libraryTexture 竖条纹 —— 违反本规格。
        assertEquals(
            "背景 alpha 必须恒为 1.0，置灰不能透出底层",
            1.0f,
            ReferenceEffectSpec.BACKGROUND_ALPHA,
            0.0001f,
        )
    }

    @Test
    fun `置灰渐变与眨眼动画同长 —— 证明状态同步`() {
        assertEquals(300, ReferenceEffectSpec.BLINK_DURATION_MS)
    }

    @Test
    fun `眨眼是16帧 —— 睁眼0001到闭眼0016`() {
        assertEquals(16, ReferenceEffectSpec.EYE_FRAME_COUNT)
    }

    @Test
    fun `分界线随隐藏数严格递增 且忠实参考包不封顶`() {
        val base = 0.67f // listview_dividerHeight
        // 隐藏 0/1/2/3/10/30 行，线宽必须一直变粗，不能在某个数封顶。
        for (n in 0..30) {
            assertEquals(base * (n + 1), ReferenceEffectSpec.dividerThickness(base, n), 0.0001f)
        }
        assertTrue("分界线必须严格递增，不允许封顶后不变", ReferenceEffectSpec.isStrictlyIncreasing(base, 30))
        // 忠实参考包：隐藏 30 行的线宽应是基准的 31 倍（当前 R137 封顶 4 倍，违反此规格）
        assertEquals(base * 31, ReferenceEffectSpec.dividerThickness(base, 30), 0.0001f)
    }
}
