package com.smartisan.music.ui.loved

import androidx.media3.common.MediaItem
import com.smartisan.music.data.favorite.FavoriteSongRecord

/**
 * [补实现·参考包0.3.0] 收藏页「按收藏时间 / 按歌名」排序模式。
 *
 * 说明：当前项目 1.2.26 已把收藏页排序升级为与歌曲页统一的 4 档排序
 * （SongSortSelection，见 LovedSongsPage.kt），页面并未使用本枚举。
 * 这里按用户「补实现」决定从 0.3.0 参考包补回这两个工具函数，让
 * LovedSongsModelsTest 有真实实现可测；**不改动页面现有统一排序**，避免功能倒退。
 */
enum class LovedSongsSortMode {
    Time,
    SongName,
}

data class LovedSongEntry(
    val mediaItem: MediaItem,
    val likedAt: Long,
)

data class LovedSongsPlaybackRequest(
    val mediaItems: List<MediaItem>,
    val startIndex: Int,
)

internal fun buildLovedSongEntries(
    favorites: List<FavoriteSongRecord>,
    visibleSongs: List<MediaItem>,
): List<LovedSongEntry> {
    val songsById = visibleSongs.associateBy(MediaItem::mediaId)
    return favorites.mapNotNull { favorite ->
        songsById[favorite.mediaId]?.let { mediaItem ->
            LovedSongEntry(
                mediaItem = mediaItem,
                likedAt = favorite.likedAt,
            )
        }
    }
}

/**
 * [补实现·参考包0.3.0] 按收藏时间倒序 / 按歌名排序收藏条目。
 * 时间档以 likedAt 倒序、mediaId 兜底；歌名档用传入的 titleComparator，
 * 同名再按 likedAt 倒序、mediaId 兜底。
 */
internal fun sortLovedSongEntries(
    entries: List<LovedSongEntry>,
    sortMode: LovedSongsSortMode,
    titleComparator: Comparator<String>,
): List<LovedSongEntry> {
    return when (sortMode) {
        LovedSongsSortMode.Time -> entries.sortedWith(
            compareByDescending<LovedSongEntry> { it.likedAt }
                .thenBy { it.mediaItem.mediaId },
        )

        LovedSongsSortMode.SongName -> entries.sortedWith { left, right ->
            val titleResult = titleComparator.compare(
                left.mediaItem.lovedSongTitleKey(),
                right.mediaItem.lovedSongTitleKey(),
            )
            when {
                titleResult != 0 -> titleResult
                left.likedAt != right.likedAt -> right.likedAt.compareTo(left.likedAt)
                else -> left.mediaItem.mediaId.compareTo(right.mediaItem.mediaId)
            }
        }
    }
}

internal fun buildLovedSongsPlayRequest(
    entries: List<LovedSongEntry>,
    startMediaId: String? = null,
): LovedSongsPlaybackRequest? {
    if (entries.isEmpty()) {
        return null
    }
    val mediaItems = entries.map(LovedSongEntry::mediaItem)
    val startIndex = startMediaId
        ?.let { mediaId -> mediaItems.indexOfFirst { it.mediaId == mediaId } }
        ?.takeIf { it >= 0 }
        ?: 0
    return LovedSongsPlaybackRequest(
        mediaItems = mediaItems,
        startIndex = startIndex,
    )
}

internal fun buildLovedSongsShuffleRequest(entries: List<LovedSongEntry>): LovedSongsPlaybackRequest? {
    if (entries.isEmpty()) {
        return null
    }
    return LovedSongsPlaybackRequest(
        mediaItems = entries.map(LovedSongEntry::mediaItem),
        startIndex = 0,
    )
}

/** [补实现·参考包0.3.0] 收藏排序用的标题键：优先 displayTitle，回退 title */
internal fun MediaItem.lovedSongTitleKey(): String {
    return mediaMetadata.displayTitle?.toString()
        ?: mediaMetadata.title?.toString()
        ?: ""
}
