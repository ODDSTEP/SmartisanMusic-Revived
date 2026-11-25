package com.smartisan.music.ui.components

import com.smartisan.music.ui.songs.SongTitleNormalizer
import com.smartisan.music.ui.songs.sectionLetterFromSortKey

/**
 * 按首字母分组的通用行模型。把扁平列表渲染成 [Header] / [Item] 交错的行，
 * 不改成 Map、不改成 stickyHeader，以免打乱既有 QuickBar 与 LazyList 的索引对应关系。
 */
internal sealed interface SectionLetterRow<out T> {
    data class Header(val letter: String) : SectionLetterRow<Nothing>

    data class Item<out T>(val value: T) : SectionLetterRow<T>
}

/** 与歌曲页 songSortKey 同源：HanLatinTransliterator 转拼音后归一化，中文按拼音取首字母。 */
internal fun sectionSortKeyForTitle(title: String): String = SongTitleNormalizer.normalize(title)


private data class SectionLetterEntry<T>(
    val value: T,
    val letter: String,
    val sortKey: String,
)

/** '#' 类归入 ZZZ 桶置尾，与歌曲页 songSortBucket 一致。 */
private fun String.sectionBucket(): String = if (this == "#") "ZZZ" else this

/**
 * 按 (bucket, sortKey) 稳定排序（同组内保持原有相对顺序），并在首字母变化时插入一个 Header。
 */
internal fun <T> buildSectionLetterRows(
    items: List<T>,
    titleOf: (T) -> String,
): List<SectionLetterRow<T>> {
    val entries =
        items.map { item ->
            val sortKey = sectionSortKeyForTitle(titleOf(item))
            SectionLetterEntry(item, sortKey.sectionLetterFromSortKey(), sortKey)
        }
    val sorted =
        entries.sortedWith(
            compareBy<SectionLetterEntry<T>> { it.letter.sectionBucket() }
                .thenBy { it.sortKey }
        )
    val rows = mutableListOf<SectionLetterRow<T>>()
    var previous: String? = null
    sorted.forEach { entry ->
        if (entry.letter != previous) {
            rows += SectionLetterRow.Header(entry.letter)
            previous = entry.letter
        }
        rows += SectionLetterRow.Item(entry.value)
    }
    return rows
}

/** 字母 -> Header 所在行索引，供 AlphabetQuickBar 直接定位。 */
internal fun <T> List<SectionLetterRow<T>>.headerIndices(): Map<String, Int> {
    val indices = mutableMapOf<String, Int>()
    forEachIndexed { index, row ->
        if (row is SectionLetterRow.Header) indices[row.letter] = index
    }
    return indices
}
