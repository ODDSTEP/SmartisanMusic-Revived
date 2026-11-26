package com.smartisan.music.playback

import androidx.media3.common.MediaItem

/**
 * 1.3.5 实验性功能「不播放 DSD 格式音频」统一的 DSD 判定入口。
 * 主依据是音质徽章（DFF/DSF/DSD 在 LocalAudioLibrary 里一律写成 "dsd"），
 * 另用文件名 / 格式字段兜底补录行与索引尚未回填的情况。
 */
private val DsdExtensions = setOf("dff", "dsf", "dsd", "dst")
private val DsdFormatNames = setOf("dsd", "dff", "dsf", "audio/dsd")

internal fun MediaItem.isDsdItem(): Boolean {
    val extras = mediaMetadata.extras ?: return false
    if (extras.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey) == "dsd") return true
    val format = extras.getString(LocalAudioLibrary.FormatExtraKey).orEmpty().lowercase()
    if (format in DsdFormatNames) return true
    val name = extras.getString(LocalAudioLibrary.DisplayNameExtraKey).orEmpty().lowercase()
    return name.substringAfterLast('.', "") in DsdExtensions
}
