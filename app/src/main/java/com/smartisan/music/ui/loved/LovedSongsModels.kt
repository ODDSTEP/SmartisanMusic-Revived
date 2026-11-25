package com.smartisan.music.ui.loved

import androidx.media3.common.MediaItem
import com.smartisan.music.data.favorite.FavoriteSongRecord

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
