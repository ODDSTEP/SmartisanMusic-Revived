package com.smartisan.music.ui.search

import androidx.media3.common.MediaItem
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.album.buildAlbumSummaries
import com.smartisan.music.ui.artist.ArtistSummary
import com.smartisan.music.ui.artist.buildArtistSummaries
import com.smartisan.music.ui.artist.toArtistDisplayNames
import java.util.Locale

internal data class SearchResults(
    val query: String,
    val songs: List<MediaItem>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
) {
    val hasResults: Boolean = songs.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty()
}

internal fun buildSearchResults(
    query: String,
    songs: List<MediaItem>,
    unknownAlbumTitle: String,
    unknownArtistTitle: String,
    multipleArtistsTitle: String,
    artistSettings: ArtistSettings = ArtistSettings(),
): SearchResults {
    val normalizedQuery = normalizeSearchQuery(query)
    if (normalizedQuery.isEmpty()) {
        return SearchResults(
            query = normalizedQuery,
            songs = emptyList(),
            albums = emptyList(),
            artists = emptyList(),
        )
    }

    val matchedSongs = songs.filter { item ->
        item.searchableSongFields().any { field ->
            field.contains(normalizedQuery)
        }
    }
    val matchedAlbums = buildAlbumSummaries(
        mediaItems = songs,
        unknownAlbumTitle = unknownAlbumTitle,
        multipleArtistsTitle = multipleArtistsTitle,
        artistSettings = artistSettings,
    ).filter { album ->
        album.searchableAlbumFields(
            artistSettings = artistSettings,
            unknownArtistTitle = unknownArtistTitle,
        ).any { field ->
            field.contains(normalizedQuery)
        }
    }
    val matchedArtists = buildArtistSummaries(
        mediaItems = songs,
        unknownArtistTitle = unknownArtistTitle,
        unknownAlbumTitle = unknownAlbumTitle,
        artistSettings = artistSettings,
    ).filter { artist ->
        artist.searchableArtistFields().any { field ->
            field.contains(normalizedQuery)
        }
    }

    return SearchResults(
        query = normalizedQuery,
        songs = matchedSongs,
        albums = matchedAlbums,
        artists = matchedArtists,
    )
}

internal fun normalizeSearchQuery(query: String): String {
    return query.trim().lowercase(Locale.ROOT)
}

private fun MediaItem.searchableSongFields(): List<String> {
    val metadata = mediaMetadata
    val extras = metadata.extras
    val displayName = extras?.getString(LocalAudioLibrary.DisplayNameExtraKey)
    val relativePath = extras?.getString(LocalAudioLibrary.RelativePathExtraKey)
    return buildList {
        add(metadata.title?.toString())
        add(metadata.displayTitle?.toString())
        add(metadata.artist?.toString())
        add(metadata.albumTitle?.toString())
        add(metadata.albumArtist?.toString())
        // 文件名（含后缀）与后缀单独可搜：搜 "ape" 列出全部 APE、搜文件名也能命中
        add(displayName)
        displayName?.substringBeforeLast('.')?.takeIf { it != displayName }?.let(::add)
        displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.let(::add)
        // 1.2.19：容器/编码/音质档位也参与匹配——搜 "dff" 列出全部 DFF、
        // 搜 "dsd" 列出全部 DSD（此前只能靠文件名后缀，补录行拿不到后缀时就搜不到）
        add(extras?.getString(LocalAudioLibrary.FormatExtraKey))
        add(extras?.getString(LocalAudioLibrary.MimeTypeExtraKey))
        add(extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey))
        add(
            extras?.getString(LocalAudioLibrary.FormatExtraKey)
                ?.substringAfter('/', "")
                ?.takeIf { it.isNotBlank() }
        )
        add(relativePath)
    }.filterNotNull().map(::normalizeSearchQuery)
}

private fun AlbumSummary.searchableAlbumFields(
    artistSettings: ArtistSettings,
    unknownArtistTitle: String,
): List<String> {
    if (artistSettings.separators.isEmpty()) {
        return listOf(title, artist).map(::normalizeSearchQuery)
    }
    val songArtistNames = songs.flatMap { item ->
        item.mediaMetadata.artist.toArtistDisplayNames(
            artistSettings = artistSettings,
            unknownArtistTitle = unknownArtistTitle,
        )
    }
    return (listOf(title, artist) + songArtistNames)
        .distinctBy(::normalizeSearchQuery)
        .map(::normalizeSearchQuery)
}

private fun ArtistSummary.searchableArtistFields(): List<String> {
    return listOf(name).map(::normalizeSearchQuery)
}
