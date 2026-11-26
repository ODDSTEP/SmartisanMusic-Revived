package com.smartisan.music.ui.album

import androidx.media3.common.MediaItem
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.ui.artist.artistNormalizedKey
import com.smartisan.music.ui.artist.toArtistDisplayNames
import com.smartisan.music.ui.artist.toArtistDisplayText
import java.util.Locale

enum class AlbumViewMode {
    List,
    Tile,
}

internal data class AlbumSummary(
    val id: String,
    val title: String,
    val artist: String,
    val songs: List<MediaItem>,
    val year: Int?,
) {
    val representative: MediaItem = songs.first()
    val trackCount: Int = songs.size
    val durationMs: Long = songs.sumOf { it.mediaMetadata.durationMs ?: 0L }
}

/**
 * 专辑分组 id：有 MediaStore albumId 时用 "album:<id>"，否则用「专辑名|专辑艺术家」。
 *
 * 1.2.19：抽成唯一实现，供专辑页分组与「查看专辑」跳转共用。此前两处各写一套
 * （一处 normalizedKey、一处 lowercase；一处取 albumArtist、一处取 artist），
 * 无 albumId 的补录行（DFF/DSF/WV…）两边算出的 key 不一致 → 点「查看专辑」进错分组，
 * 专辑页显示一堆不相干的歌。
 */
/**
 * [1.4.24·R28] 无专辑信息（或用户把专辑名清空）的歌，统一归到这一张卡。
 *
 * 旧实现直接 `return null`，调用方各自兜底：专辑页用「未知专辑」标题但仍带 `album:<id>`，
 * 于是每首无专辑的歌按 id 分裂成多张同名卡；「查看专辑」则因为拿到 null 直接置灰。
 * 现在改成**固定分组键**：所有空专辑合并成一张，跳转也能落到这张卡上。
 */
internal const val UnknownAlbumGroupingId = "album:__unknown__"

internal fun albumGroupingId(
    albumId: Long?,
    albumTitle: String?,
    albumArtist: String? = null,
): String {
    val title = albumTitle?.takeIf { it.isNotBlank() } ?: return UnknownAlbumGroupingId
    return albumId?.takeIf { it > 0L }?.let { "album:$it" }
        ?: "${title.normalizedKey()}|${albumArtist?.artistNormalizedKey().orEmpty()}"
}

/**
 * [1.4.28] 单个媒体项的专辑分组键。
 *
 * 专辑批量删除要按「用户在专辑页看到的那张卡」去收集歌曲，必须与 [buildAlbumSummaries]
 * 用完全相同的口径（同一个 albumGroupingId + 同一套空专辑兜底），否则会删错专辑或漏歌。
 * 此前 AlbumIdExtraKey 是 private、这段映射只在 buildAlbumSummaries 内部，
 * 外部想复用只能复制一份，正是「同一规则两处实现」的来源。
 */
internal fun albumGroupingIdOf(item: MediaItem): String {
    val metadata = item.mediaMetadata
    val albumId = metadata.extras?.getLong(AlbumIdExtraKey)?.takeIf { it > 0L }
    return albumGroupingId(
        albumId = albumId,
        albumTitle = metadata.albumTitle.toArtistDisplayText(),
        albumArtist = metadata.albumArtist.toArtistDisplayText(),
    )
}

internal fun buildAlbumSummaries(
    mediaItems: List<MediaItem>,
    unknownAlbumTitle: String,
    multipleArtistsTitle: String,
    artistSettings: ArtistSettings = ArtistSettings(),
): List<AlbumSummary> {
    val groups = linkedMapOf<String, MutableAlbumGroup>()

    mediaItems.forEach { item ->
        val metadata = item.mediaMetadata
        // [1.4.24·R28] 保留「原始是否为空」的判断：空专辑不能再按 albumId 分组，
        // 否则同一个「未知专辑」会按每首歌的 id 裂成很多张。分组键由 albumGroupingId
        // 统一返回 UnknownAlbumGroupingId，标题仍是本地化的「未知专辑」。
        val rawAlbumTitle = metadata.albumTitle.toArtistDisplayText()
        val albumTitle = rawAlbumTitle ?: unknownAlbumTitle
        val albumArtist = metadata.albumArtist.toArtistDisplayText()
        val albumId = metadata.extras?.getLong(AlbumIdExtraKey)?.takeIf { it > 0L }
        val groupingKey = albumGroupingId(albumId, rawAlbumTitle, albumArtist)
        val group = groups.getOrPut(groupingKey) {
            MutableAlbumGroup(
                id = groupingKey,
                title = albumTitle,
                albumArtist = albumArtist,
            )
        }
        group.songs += item
    }

    return groups.values
        .map { group ->
            val sortedSongs = group.songs.sortedWith(
                compareBy<MediaItem> { it.mediaMetadata.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.mediaMetadata.title?.toString().orEmpty().normalizedKey() },
            )
            AlbumSummary(
                id = group.id,
                title = group.title,
                artist = group.albumArtist ?: group.songs.distinctArtistName(
                    multipleArtistsTitle = multipleArtistsTitle,
                    artistSettings = artistSettings,
                ),
                songs = sortedSongs,
                year = sortedSongs.firstNotNullOfOrNull { item ->
                    item.mediaMetadata.releaseYear ?: item.mediaMetadata.recordingYear
                },
            )
        }
        .sortedBy { it.title.normalizedKey() }
}

internal fun MediaItem.displayTrackNumber(): String {
    val rawTrackNumber = mediaMetadata.trackNumber ?: return MissingAlbumTrackNumberSymbol
    val trackNumber = rawTrackNumber % 1000
    return (trackNumber.takeIf { it > 0 } ?: rawTrackNumber).toString()
}

private data class MutableAlbumGroup(
    val id: String,
    val title: String,
    val albumArtist: String?,
    val songs: MutableList<MediaItem> = mutableListOf(),
)

private fun List<MediaItem>.distinctArtistName(
    multipleArtistsTitle: String,
    artistSettings: ArtistSettings,
): String {
    val artists = flatMap { item ->
        item.mediaMetadata.artist.toArtistDisplayNames(
            artistSettings = artistSettings,
            unknownArtistTitle = "",
        )
    }
        .filter { it.isNotBlank() }
        .distinctBy { it.artistNormalizedKey() }
    return artists.singleOrNull() ?: multipleArtistsTitle
}

private fun String.normalizedKey(): String {
    return lowercase(Locale.ROOT)
}

private const val AlbumIdExtraKey = "com.smartisan.music.extra.ALBUM_ID"
internal const val MissingAlbumTrackNumberSymbol = "•"
