package com.smartisan.music.data.memory

import kotlin.math.roundToInt

/** 由记忆推导出的"理解"结论。全部只读，不改数据。 */
data class ListeningInsight(
    val totalPlays: Long,
    val totalPlayedMs: Long,
    val favouriteHourStart: Int,
    val favouriteHourEnd: Int,
    val highResolutionPercent: Int,
    val topArtists: List<Pair<String, Int>>,
    val topAlbums: List<Pair<String, Int>>,
    val recentTracks: List<TrackMemory>,
    val favouriteTracks: List<TrackMemory>,
)

private val UnknownLabel = "—"

fun ListeningMemory.insight(): ListeningInsight {
    val hour = favouriteHour
    val totalPlays = totalPlays
    val namedArtists = tracks
        .filter { it.artist.isNotBlank() && it.artist != UnknownLabel }
        .groupBy { it.artist }
        .map { (artist, items) -> artist to items.sumOf { it.playCount } }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(3)
    val namedAlbums = tracks
        .filter { it.album.isNotBlank() && it.album != UnknownLabel }
        .groupBy { it.album }
        .map { (album, items) -> album to items.sumOf { it.playCount } }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        .take(3)
    return ListeningInsight(
        totalPlays = totalPlays,
        totalPlayedMs = totalPlayedMs,
        favouriteHourStart = if (hour < 0) -1 else hour,
        favouriteHourEnd = if (hour < 0) -1 else (hour + 2) % 24,
        highResolutionPercent = (highResolutionRatio * 100f).roundToInt().coerceIn(0, 100),
        topArtists = namedArtists,
        topAlbums = namedAlbums,
        recentTracks = tracks
            .sortedWith(compareByDescending<TrackMemory> { it.lastPlayedAt }
                .thenByDescending { it.playCount })
            .take(5),
        favouriteTracks = tracks
            .sortedWith(compareByDescending<TrackMemory> { it.playCount }
                .thenByDescending { it.lastPlayedAt })
            .take(5),
    )
}

/** 把毫秒说成"约 3 小时 20 分"。 */
fun formatListenedDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0 分钟"
    val minutes = durationMs / 60_000L
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        hours <= 0L -> "$restMinutes 分钟"
        restMinutes == 0L -> "约 $hours 小时"
        else -> "约 $hours 小时 $restMinutes 分"
    }
}
