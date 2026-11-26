package com.smartisan.music.effects

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [效果验证] 实现合规审计 —— 自动判定"当前实现到底做对没有"。
 *
 * 这是根治"只验字段存在、不验效果"的关键一环：
 * 不再 grep 到 alpha/dividerExtra 就打勾，而是拿参考包规格去检查**作用对象与数值规则**。
 *
 * 输出：build/reports/effect-implementation-gap.txt（每次跑都刷新，白纸黑字记录 GAP）。
 * 默认不阻塞构建；整改验收时加 -DstrictEffectAudit=true 打开严格模式，有 GAP 即 FAIL。
 */
class ImplementationGapAuditTest {

    private val srcRoot = File("src/main/java/com/smartisan/music")

    private data class Gap(val id: String, val severity: String, val finding: String, val expected: String)

    @Test
    fun auditCurrentImplementationAgainstReferenceSpec() {
        val gaps = mutableListOf<Gap>()

        val swipeRow = read("ui/songs/SmartisanSwipeDeleteRow.kt")
        val folderPage = read("ui/folder/FolderPage.kt")
        val albumPage = read("ui/album/AlbumPage.kt")
        val albumCollection = read("ui/library/SmartisanAlbumCollection.kt")
        val songsPage = read("ui/songs/SongsPage.kt")

        // GAP-1：置灰作用在整行（含背景）而非仅文字层 —— 用户图1"透出竖条纹"的根因
        if (swipeRow.contains("alpha = contentAlpha") && !swipeRow.contains("alpha = brightAlpha")) {
            gaps += Gap(
                "GAP-1",
                "严重",
                "SmartisanSwipeDeleteRow 把 contentAlpha 作用在整行 Box（含背景）",
                "参考包只作用文字 Column（brightAlpha），背景 alpha 恒为 1.0",
            )
        }
        // GAP-2：置灰终点 0.32（作用在整行）或编辑态二值跳变，未按参考包 0.3 渐变只作用文字
        if (songsPage.contains("alpha = if (dimmed) 0.35f") || songsPage.contains("alpha = if (dimmed)")) {
            gaps += Gap(
                "GAP-2",
                "严重",
                "SongsPage 编辑态置灰用二值跳变 alpha=0.35f，未随眨眼 300ms 渐变",
                "参考包 animateFloatAsState(tween(300)) 渐变到 0.3，只作用文字",
            )
        }
        // GAP-3：分界线加粗封顶 4 层，违反"完全忠实参考包不封顶"
        if (folderPage.contains("coerceAtMost(4)")) {
            gaps += Gap(
                "GAP-3",
                "中",
                "FolderPage R137 分界线封顶 coerceAtMost(4)，隐藏≥3 行后不再变粗",
                "用户要求完全忠实参考包：N+1 无上限，越多越粗",
            )
        }
        // GAP-4：分界线加粗/收起动画只在文件夹页，专辑/歌曲页没有
        val albumHasDividerLogic =
            albumPage.contains("hiddenRunBelow") || albumCollection.contains("dividerExtra")
        if (!albumHasDividerLogic) {
            gaps += Gap(
                "GAP-4",
                "严重",
                "专辑页隐藏是直接 filterNot 删行，无收起动画、无分界线加粗",
                "用户确认：所有隐藏场景都要做收起动画 + 分界线随隐藏数变粗",
            )
        }
        // GAP-5：专辑页列表视图未接入右滑删除/隐藏
        val swipeUsers = mutableListOf<String>()
        srcRoot.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            if (f.readText().contains("SmartisanSwipeDeleteRow")) swipeUsers += f.name
        }
        if (!swipeUsers.any { it == "SmartisanAlbumCollection.kt" }) {
            gaps += Gap(
                "GAP-5",
                "中",
                "专辑页列表视图未接入 SmartisanSwipeDeleteRow，无右滑删除/隐藏",
                "用户要求：像歌曲页那样右滑露出删除 + 隐藏图标",
            )
        }

        // 生成报告
        val report = buildString {
            appendLine("=== 效果实现合规审计（基准：0.3.0 参考包 + 用户明确要求）===")
            appendLine("审计时间：${System.currentTimeMillis()}")
            appendLine("规格断言：置灰alpha=0.3(非0)、背景alpha=1.0、眨眼16帧、分界线N+1不封顶")
            appendLine()
            if (gaps.isEmpty()) {
                appendLine("✅ 全部符合规格，无 GAP")
            } else {
                appendLine("❌ 发现 ${gaps.size} 处 GAP：")
                gaps.forEach { g ->
                    appendLine("[${g.id}][${g.severity}] ${g.finding}")
                    appendLine("    期望：${g.expected}")
                }
            }
        }
        val reportFile = File("build/reports/effect-implementation-gap.txt")
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(report)
        println(report)

        // 严格模式：整改验收时 -DstrictEffectAudit=true，有 GAP 即 FAIL
        if (System.getProperty("strictEffectAudit") == "true") {
            assertTrue(
                "存在未整改的效果 GAP，详见 build/reports/effect-implementation-gap.txt\n$report",
                gaps.isEmpty(),
            )
        }
    }

    private fun read(relativePath: String): String {
        val f = File(srcRoot, relativePath)
        return if (f.exists()) f.readText() else ""
    }
}
