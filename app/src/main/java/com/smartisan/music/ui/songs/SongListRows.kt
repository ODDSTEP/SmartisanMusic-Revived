package com.smartisan.music.ui.songs

import android.content.Context
import androidx.media3.common.MediaItem
import com.smartisan.music.R

internal sealed class SongListRow {
    data class Header(val key: SongHeaderKey) : SongListRow()

    data class Song(
        val mediaItem: MediaItem,
        val songIndex: Int,
    ) : SongListRow()
}

internal sealed class SongHeaderKey {
    data class Name(val letter: String) : SongHeaderKey()

    fun title(context: Context): String {
        return (this as? Name)?.letter.orEmpty()
    }
}

internal fun buildSongRows(
    mediaItems: List<MediaItem>,
    sectionMode: SongSectionMode,
): List<SongListRow> {
    if (sectionMode == SongSectionMode.None) {
        return mediaItems.mapIndexed { index, mediaItem ->
            SongListRow.Song(mediaItem, index)
        }
    }
    val rows = mutableListOf<SongListRow>()
    var previousKey: SongHeaderKey? = null
    mediaItems.forEachIndexed { index, mediaItem ->
        val key =
            when (sectionMode) {
                SongSectionMode.Name -> SongHeaderKey.Name(mediaItem.songSectionLetter())
                SongSectionMode.None -> null
            }
        if (key != null && key != previousKey) {
            rows += SongListRow.Header(key)
            previousKey = key
        }
        rows += SongListRow.Song(mediaItem, index)
    }
    return rows
}
