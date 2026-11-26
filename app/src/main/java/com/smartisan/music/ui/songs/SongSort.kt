package com.smartisan.music.ui.songs

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.smartisan.music.platform.media.AudioQualityTiers
import com.smartisan.music.platform.text.HanLatinTransliterator
import com.smartisan.music.playback.LocalAudioLibrary
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** 歌曲页排序选择：主排序 + 方向 */
internal data class SongSortSelection(
    val main: Int = 0,
    val ascending: Boolean = true,
)

/** 主排序下标：0 歌曲名 / 1 音质 / 2 播放次数 / 3 添加时间 */
internal const val SongSortMainName = 0
internal const val SongSortMainQuality = 1
internal const val SongSortMainPlayCount = 2
internal const val SongSortMainAddedTime = 3

internal fun List<MediaItem>.sortedForSongSort(
    selection: SongSortSelection,
    playCountOf: (MediaItem) -> Long = { it.songPlayCount() },
): List<MediaItem> {
    return when (selection.main) {
        SongSortMainQuality -> {
            // 1.2.23：按「科学音质评分」排序（采样率 / 位深 / 码率 / 格式家族 / DSD 母带加权），
            // 同档无损也能区分 44.1/16 与 192/24，DSD 永远最高。详见 songQualityScore()。
            val score: (MediaItem) -> Long = { item -> item.songQualityScore() }
            if (selection.ascending) {
                sortedWith(
                    compareBy(score)
                        .thenBy { item -> item.songSortKey() }
                        .thenBy { item -> item.mediaId },
                )
            } else {
                sortedWith(
                    compareByDescending(score)
                        .thenBy { item -> item.songSortKey() }
                        .thenBy { item -> item.mediaId },
                )
            }
        }
        SongSortMainPlayCount ->
            applyDirection(selection) { playCountOf(it) }
        // 添加时间 = 音频文件被写入手机存储的时间（MediaStore DATE_ADDED），
        // 不是被播放器扫描入库的时间。默认降序（最新下载的排最前）。
        SongSortMainAddedTime ->
            applyDirection(selection) { it.songDateAddedSecs() }
        else ->
            applyDirectionByText(selection) { it.songSortBucket() + "\u0000" + it.songSortKey() }
    }
}

private fun List<MediaItem>.applyDirection(
    selection: SongSortSelection,
    key: (MediaItem) -> Long,
): List<MediaItem> =
    if (selection.ascending) {
        sortedWith(
            compareBy(key)
                .thenBy { item -> item.songSortKey() }
                .thenBy { item -> item.mediaId },
        )
    } else {
        sortedWith(
            compareByDescending(key)
                .thenBy { item -> item.songSortKey() }
                .thenBy { item -> item.mediaId },
        )
    }

/** 文本键排序：始终按升序比较次级键，避免整体反转导致同键歌曲顺序抖动 */
private fun List<MediaItem>.applyDirectionByText(
    selection: SongSortSelection,
    key: (MediaItem) -> String,
): List<MediaItem> {
    val titleKey: (MediaItem) -> String = { item -> item.songSortKey() }
    val idKey: (MediaItem) -> String = { item -> item.mediaId }
    return if (selection.ascending) {
        sortedWith(compareBy(key).thenBy(titleKey).thenBy(idKey))
    } else {
        sortedWith(compareByDescending(key).thenBy(titleKey).thenBy(idKey))
    }
}

internal fun MediaItem.songSampleRateHz(): Int {
    return mediaMetadata.extras?.getInt(LocalAudioLibrary.SampleRateHzExtraKey, 0) ?: 0
}

internal fun MediaItem.songBitrateBps(): Int {
    return mediaMetadata.extras?.getInt(LocalAudioLibrary.BitrateBpsExtraKey, 0) ?: 0
}

internal fun MediaItem.songBitsPerSample(): Int {
    return mediaMetadata.extras?.getInt(LocalAudioLibrary.BitsPerSampleExtraKey, 0) ?: 0
}

internal fun MediaItem.songFormat(): String {
    val extra = mediaMetadata.extras?.getString(LocalAudioLibrary.FormatExtraKey)
    if (!extra.isNullOrBlank()) return extra
    // 兜底：从音频质量徽标（如 "FLAC 44.1kHz"）或展示名后缀推断
    val badge = mediaMetadata.extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey)
    badge?.takeIf { it.isNotBlank() }?.let { badgeValue ->
        val token = badgeValue.trim().split(' ', '\t').firstOrNull()
        if (!token.isNullOrBlank()) return token
    }
    return ""
}

internal fun MediaItem.songDurationMs(): Long {
    return mediaMetadata.durationMs ?: 0L
}

internal fun MediaItem.songQualityTier(): Int {
    val stored = mediaMetadata.extras?.getInt(LocalAudioLibrary.QualityTierExtraKey, 0) ?: 0
    if (stored > 0) return stored
    // 兜底：探测档位缺失（未探测 / 探测失败）时，由「科学音质评分」反推档位，
    // 保证排序与档位口径一致（DSD=Hi-Res；24bit/高采样无损=Hi-Res；其余无损=无损；有损按码率）。
    val score = songQualityScore()
    return when {
        score >= 5_000_000L -> AudioQualityTiers.Tier.HiRes.rank          // DSD 母带
        score >= 2_000_000L + 24_000L -> AudioQualityTiers.Tier.HiRes.rank // 无损且位深≥24bit
        score >= 2_000_000L -> AudioQualityTiers.Tier.Lossless.rank      // 其余无损
        score >= 320L -> AudioQualityTiers.Tier.High.rank                // 高码率有损
        score > 0L -> AudioQualityTiers.Tier.Normal.rank                 // 标准有损
        else -> 0
    }
}

/**
 * 科学音质评分（1.2.23 新增）：用于「按音质排序」，把抽象档位拆成可比较的连续分值。
 *
 * 设计原则（与业界「Hi-Res Audio」口径一致，并与「理解记忆·高规格音源占比」对齐）：
 *  - DSD/DFF/DSF 母带等效极高频宽，给最高基础分，并按 DSD 倍速（DSD64≈2.82MHz）加权；
 *  - 无损格式（FLAC/APE/WAV/AIFF/ALAC/CUE/WV 等）基础分远高于有损；
 *  - 同档内按 采样率 + 位深×1000 + 码率/1000 累加，使 192/24 明显高于 44.1/16；
 *  - 有损格式几乎没有基础分，仅按码率排（320k > 256k > 128k …），永远低于任何无损。
 *
 * 返回分值越大代表音质越高；比较时直接 compareBy 即可，无需再分档。
 */
internal fun MediaItem.songQualityScore(): Long {
    val sr = songSampleRateHz()
    val bits = songBitsPerSample()
    val br = songBitrateBps()
    val badge = mediaMetadata.extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey) ?: ""
    val format = songFormat().lowercase()
    val isDsd = badge == "dsd" || format in setOf("dsd", "dsf", "dff")
    if (isDsd) {
        // 基础 500 万分 + 按 DSD 采样率（DSD64≈2822、DSD128≈5645）加权，稳居榜首
        return 5_000_000L + (sr / 1_000L).coerceAtLeast(0L).coerceAtMost(8_000L)
    }
    val lossless = badge in setOf(
        "flac", "ape", "wav", "aiff", "alac", "cue",
    ) || format in setOf(
        "flac", "ape", "wav", "wave", "aiff", "aif", "alac", "wv", "tta", "tak", "wvpk", "dsd", "dsf", "dff",
    )
    if (lossless) {
        var score = 2_000_000L
        score += sr.toLong().coerceAtLeast(0L)           // 44.1→44 … 384→384
        score += bits.toLong().coerceAtLeast(0L) * 1_000L // 16→16000，24→24000
        if (br > 0) score += br / 1_000L                 // 叠加码率
        return score
    }
    // 有损：仅按码率排名，基础分极低（<1000），永远排在无损之后
    return if (br > 0) (br / 1_000L).coerceAtLeast(1L) else 0L
}

/**
 * 歌曲文件被写入手机存储的时间（Unix 秒）。取值 MediaStore.Audio.Media.DATE_ADDED，
 * 即文件下载 / 拷贝到手机的时间点；播放器自身的扫描入库时间不参与此排序。
 * 取不到时返回 0，配合降序会自然沉到列表末尾。
 */
internal fun MediaItem.songDateAddedSecs(): Long {
    val extras = mediaMetadata.extras ?: return 0L
    val value = extras.getLong(LocalAudioLibrary.DateAddedExtraKey, Long.MIN_VALUE)
    if (value != Long.MIN_VALUE) return value
    return extras.getInt(LocalAudioLibrary.DateAddedExtraKey, 0).toLong()
}

/** 添加时间的紧凑展示文本（如 2025/8/12）；取不到时间时返回空串 */
internal fun MediaItem.songAddedTimeText(): String {
    val secs = songDateAddedSecs()
    if (secs <= 0L) return ""
    val formatter = AddedTimeFormatter.get()
    return runCatching { formatter?.format(java.util.Date(secs * 1000L)).orEmpty() }
        .getOrDefault("")
}

/** SimpleDateFormat 非线程安全，按线程缓存实例 */
private val AddedTimeFormatter: ThreadLocal<java.text.SimpleDateFormat> =
    object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue(): java.text.SimpleDateFormat =
            java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.getDefault())
    }

internal enum class SongSortDisplayMode {
    Name,
    Quality,
    PlayCount,
    AddedTime,
}

internal enum class SongSectionMode {
    None,
    Name,
}

internal fun Int.toSongSortDisplayMode(): SongSortDisplayMode {
    return when (this) {
        SongSortMainQuality -> SongSortDisplayMode.Quality
        SongSortMainPlayCount -> SongSortDisplayMode.PlayCount
        SongSortMainAddedTime -> SongSortDisplayMode.AddedTime
        else -> SongSortDisplayMode.Name
    }
}

internal fun SongSortDisplayMode.toSectionMode(): SongSectionMode {
    return if (this == SongSortDisplayMode.Name) SongSectionMode.Name else SongSectionMode.None
}

internal fun MediaItem.songSortTitle(): String {
    return mediaMetadata.displayTitle?.toString() ?: mediaMetadata.title?.toString() ?: ""
}

internal fun MediaItem.songSortKey(): String {
    return mediaMetadata.extras?.getString(LocalAudioLibrary.TitleSortKeyExtraKey)?.takeIf {
        it.isNotBlank()
    } ?: SongTitleNormalizer.normalize(songSortTitle())
}

internal fun MediaItem.songSortBucket(): String {
    val letter = songSectionLetter()
    return if (letter == "#") {
        "ZZZ"
    } else {
        letter
    }
}

internal fun MediaItem.songSectionLetter(): String {
    return mediaMetadata.extras?.getString(LocalAudioLibrary.TitleSectionExtraKey)?.takeIf {
        it.isNotBlank()
    } ?: songSortKey().sectionLetterFromSortKey()
}

internal fun String.sectionLetterFromSortKey(): String {
    val firstLetter =
        firstOrNull { char ->
            char.isLetterOrDigit()
        } ?: return "#"
    val upper = firstLetter.uppercaseChar()
    return if (upper in 'A'..'Z') {
        upper.toString()
    } else {
        "#"
    }
}

internal fun MediaItem.songRating(): Long {
    return mediaMetadata.extras
        ?.extraLong(
            LocalAudioLibrary.RatingExtraKey,
            "star",
            "score",
            "rating",
            "play_score",
        )
        ?.coerceIn(0L, 5L) ?: 0L
}

internal fun MediaItem.songPlayCount(): Long {
    return mediaMetadata.extras?.extraLong(
        LocalAudioLibrary.PlayCountExtraKey,
        "play_count",
        "playCount",
        "play_count_all",
    ) ?: 0L
}

private fun Bundle.extraLong(vararg keys: String): Long {
    keys.forEach { key ->
        if (!containsKey(key)) {
            return@forEach
        }
        val longValue = getLong(key, Long.MIN_VALUE)
        if (longValue != Long.MIN_VALUE) {
            return longValue
        }
        val intValue = getInt(key, Int.MIN_VALUE)
        if (intValue != Int.MIN_VALUE) {
            return intValue.toLong()
        }
        val doubleValue = getDouble(key, Double.NaN)
        if (!doubleValue.isNaN()) {
            return doubleValue.toLong()
        }
    }
    return 0L
}

internal object SongTitleNormalizer {
    private val combiningMarks = "\\p{Mn}+".toRegex()

    fun normalize(title: String): String {
        val trimmed = title.trim()
        val transliterated = HanLatinTransliterator.transliterate(trimmed)
        return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}
