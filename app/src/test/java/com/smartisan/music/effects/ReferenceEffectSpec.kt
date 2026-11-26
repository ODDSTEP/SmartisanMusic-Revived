package com.smartisan.music.effects

/**
 * [效果验证] 参考包 0.3.0 的视觉规格 —— "什么才叫做对了"的唯一判定基准。
 *
 * 来源：0.3.0 参考包源码 FolderPage.kt（用户 11-25 原话 + 逐行核对 + 视频抽帧三方确认）。
 *
 * 这个文件存在的意义：以前核查只验"代码里有没有 alpha / dividerExtra 这几个字"，
 * 于是"把置灰写成整行透明"也能被打勾说做了。现在把**参考包的真实数值规则**写成
 * 可执行断言 + 可视化 baseline，任何实现都要拿这些规格去比对，字段存在不再算数。
 */
object ReferenceEffectSpec {

    // ---- 置灰（睁眼→闭眼）----
    /** 睁眼：文字完全不透明 */
    const val DIM_ALPHA_OPEN = 1.0f

    /**
     * 闭眼：文字 alpha 降到 0.3 —— 注意是 0.3 不是 0，是"置灰"不是"透明"。
     * 参考包 FolderPage.kt:546-551 `animateFloatAsState(if (hidden) .3f else 1f)`。
     */
    const val DIM_ALPHA_CLOSED = 0.3f

    /**
     * 背景层 alpha 恒为 1.0：参考包 brightAlpha 只作用在文字 Column（FolderPage.kt:609），
     * 背景 Row 的 smartisanPainterBackground 完全不动 —— 所以置灰后背景还在，不会透出底层。
     */
    const val BACKGROUND_ALPHA = 1.0f

    /** 置灰渐变与 16 帧眨眼同长（参考包 tween(300)） */
    const val BLINK_DURATION_MS = 300

    // ---- 眨眼 ----
    /** 眼睛动画帧数：睁眼 eye_icon_0001 → 闭眼 eye_icon_0016 */
    const val EYE_FRAME_COUNT = 16

    // ---- 分界线加粗 ----
    /**
     * 连续隐藏 N 行，收起后分界线 = (N+1) 条标准线叠在一起的粗细。
     * 用户第 2 点明确要求"完全忠实参考包"= 无上限（隐藏越多越粗，不封顶）。
     * 当前产品实现 R137 加了 coerceAtMost(4) 封顶 —— 与本规格不符，属待整改 GAP。
     */
    fun dividerThickness(base: Float, hiddenRun: Int): Float = base * (hiddenRun + 1)

    /** 分界线必须严格随隐藏数递增（越隐藏越粗），不允许封顶后保持不变 */
    fun isStrictlyIncreasing(base: Float, maxRun: Int): Boolean {
        if (base <= 0f || maxRun < 1) return false
        for (n in 1..maxRun) {
            if (dividerThickness(base, n) <= dividerThickness(base, n - 1)) return false
        }
        return true
    }
}
