package com.smartisan.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.smartisan.music.playback.LocalAudioLibrary

private const val FileScanSettingsStoreName = "file_scan_settings"

private val Context.fileScanSettingsDataStore by preferencesDataStore(
    name = FileScanSettingsStoreName,
)

data class FileScanSettings(
    val filterNonSong: Boolean = false,
    val skipSmallFiles: Boolean = false,
    val skipShortSongs: Boolean = false,
) {
    val anyEnabled: Boolean
        get() = filterNonSong || skipSmallFiles || skipShortSongs
}

class FileScanSettingsStore(private val context: Context) {

    val settings: Flow<FileScanSettings> = context.fileScanSettingsDataStore.data
        .map { preferences ->
            FileScanSettings(
                filterNonSong = preferences[FilterNonSongKey] ?: false,
                skipSmallFiles = preferences[SkipSmallFilesKey] ?: false,
                skipShortSongs = preferences[SkipShortSongsKey] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setFilterNonSong(enabled: Boolean) {
        context.fileScanSettingsDataStore.edit { it[FilterNonSongKey] = enabled }
    }

    suspend fun setSkipSmallFiles(enabled: Boolean) {
        context.fileScanSettingsDataStore.edit { it[SkipSmallFilesKey] = enabled }
    }

    suspend fun setSkipShortSongs(enabled: Boolean) {
        context.fileScanSettingsDataStore.edit { it[SkipShortSongsKey] = enabled }
    }

    private companion object {
        val FilterNonSongKey = booleanPreferencesKey("filter_non_song")
        val SkipSmallFilesKey = booleanPreferencesKey("skip_small_files")
        val SkipShortSongsKey = booleanPreferencesKey("skip_short_songs")
    }
}

/** 典型的非音乐内容目录（录音 / 铃声 / 有声书等），仅隐藏不删除 */
private val NonSongFolderMarkers =
    listOf(
        "record", "recording", "voicenote", "voice", "call",
        "ringtone", "alarm", "notification", "audiobook", "podcast",
        "\u5f55\u97f3", "\u94c3\u58f0",
    )

private const val SkipSmallFilesMinBytes = 500L * 1024L
private const val SkipShortSongsMinMs = 60_000L

/** 按文件扫描设置判断歌曲是否应被隐藏（只隐藏，不动原文件） */
fun MediaItem.isHiddenByFileScan(settings: FileScanSettings): Boolean {
    if (!settings.anyEnabled) {
        return false
    }
    val extras = mediaMetadata.extras
    if (settings.filterNonSong) {
        val mimeType = extras?.getString(LocalAudioLibrary.MimeTypeExtraKey)
        if (mimeType.isNullOrEmpty() || !mimeType.startsWith("audio/", ignoreCase = true)) {
            return true
        }
        val folder = extras?.getString(LocalAudioLibrary.RelativePathExtraKey).orEmpty()
        if (NonSongFolderMarkers.any { folder.contains(it, ignoreCase = true) }) {
            return true
        }
    }
    if (settings.skipSmallFiles) {
        val fileSize = (extras?.get(LocalAudioLibrary.FileSizeExtraKey) as? Number)?.toLong() ?: 0L
        if (fileSize in 1 until SkipSmallFilesMinBytes) {
            return true
        }
    }
    if (settings.skipShortSongs) {
        val durationMs = mediaMetadata.durationMs ?: 0L
        if (durationMs in 1 until SkipShortSongsMinMs) {
            return true
        }
    }
    return false
}
