package com.smartisan.music.data.memory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private val Context.listeningMemoryDataStore by preferencesDataStore(name = "listening_memory")

/** 单曲记忆。 */
data class TrackMemory(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val playCount: Int,
    val lastPlayedAt: Long,
    val highResolution: Boolean,
)

/** 听歌记忆全量。 */
data class ListeningMemory(
    val totalPlays: Long = 0L,
    val totalPlayedMs: Long = 0L,
    val highResolutionPlays: Long = 0L,
    val hourHistogram: List<Int> = List(24) { 0 },
    val tracks: List<TrackMemory> = emptyList(),
) {
    val favouriteHour: Int
        get() = hourHistogram.indices.maxByOrNull { hourHistogram[it] } ?: -1

    val highResolutionRatio: Float
        get() = if (totalPlays <= 0L) 0f else highResolutionPlays.toFloat() / totalPlays
}

/**
 * App 的「理解记忆」。
 *
 * 与统计库（播放次数 / 评分）不同，这里只记录**用来理解用户**的最小集合：
 * 每首歌的身份与规格、在一天里的哪个时段被播放、累计听了多久。
 * 这些是"你常听谁 / 什么时候听 / 听得多讲究"的基础，也是记忆页展示的全部依据。
 */
class ListeningMemoryStore(private val context: Context) {

    val memory: Flow<ListeningMemory> = context.listeningMemoryDataStore.data.map { preferences ->
        val hours = MutableList(24) { 0 }
        parseHistogram(preferences[HoursKey]).forEachIndexed { index, value ->
            if (index in hours.indices) hours[index] = value
        }
        ListeningMemory(
            totalPlays = preferences[TotalPlaysKey] ?: 0L,
            totalPlayedMs = preferences[TotalPlayedMsKey] ?: 0L,
            highResolutionPlays = preferences[HiResPlaysKey] ?: 0L,
            hourHistogram = hours,
            tracks = parseTracks(preferences[TracksKey]),
        )
    }

    suspend fun recordPlay(track: TrackMemory, hourOfDay: Int) {
        context.listeningMemoryDataStore.edit { preferences ->
            val tracks = parseTracks(preferences[TracksKey]).toMutableList()
            val index = tracks.indexOfFirst { it.mediaId == track.mediaId }
            if (index >= 0) {
                tracks[index] = tracks[index].copy(
                    playCount = tracks[index].playCount + 1,
                    lastPlayedAt = track.lastPlayedAt,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    highResolution = track.highResolution,
                )
            } else {
                tracks += track
            }
            val trimmed = tracks
                .sortedWith(compareByDescending<TrackMemory> { it.playCount }
                    .thenByDescending { it.lastPlayedAt })
                .take(MaxTrackedTracks)
            preferences[TracksKey] = serializeTracks(trimmed)

            val hours = MutableList(24) { 0 }
            parseHistogram(preferences[HoursKey]).forEachIndexed { hour, value ->
                if (hour in hours.indices) hours[hour] = value
            }
            val safeHour = hourOfDay.coerceIn(0, 23)
            hours[safeHour] = hours[safeHour] + 1
            preferences[HoursKey] = hours.joinToString(",")

            preferences[TotalPlaysKey] = (preferences[TotalPlaysKey] ?: 0L) + 1L
            if (track.highResolution) {
                preferences[HiResPlaysKey] = (preferences[HiResPlaysKey] ?: 0L) + 1L
            }
        }
    }

    suspend fun addPlayedTime(durationMs: Long) {
        if (durationMs <= 0L) return
        context.listeningMemoryDataStore.edit { preferences ->
            preferences[TotalPlayedMsKey] = (preferences[TotalPlayedMsKey] ?: 0L) + durationMs
        }
    }

    suspend fun clear() {
        context.listeningMemoryDataStore.edit { preferences -> preferences.clear() }
    }

    companion object {
        fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        internal const val MaxTrackedTracks = 300
    }
}

private val TotalPlaysKey = longPreferencesKey("total_plays")
private val TotalPlayedMsKey = longPreferencesKey("total_played_ms")
private val HiResPlaysKey = longPreferencesKey("hi_res_plays")
private val HoursKey = stringPreferencesKey("hour_histogram")
private val TracksKey = stringPreferencesKey("tracks")

private fun parseHistogram(value: String?): List<Int> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(",").map { item -> item.trim().toIntOrNull() ?: 0 }
}

private fun parseTracks(value: String?): List<TrackMemory> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(value)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    TrackMemory(
                        mediaId = item.optString("id"),
                        title = item.optString("t"),
                        artist = item.optString("a"),
                        album = item.optString("b"),
                        playCount = item.optInt("n"),
                        lastPlayedAt = item.optLong("u"),
                        highResolution = item.optBoolean("h"),
                    ),
                )
            }
        }.filter { it.mediaId.isNotBlank() }
    }.getOrDefault(emptyList())
}

private fun serializeTracks(tracks: List<TrackMemory>): String {
    val array = JSONArray()
    tracks.forEach { track ->
        array.put(
            JSONObject().apply {
                put("id", track.mediaId)
                put("t", track.title)
                put("a", track.artist)
                put("b", track.album)
                put("n", track.playCount)
                put("u", track.lastPlayedAt)
                put("h", track.highResolution)
            },
        )
    }
    return array.toString()
}
