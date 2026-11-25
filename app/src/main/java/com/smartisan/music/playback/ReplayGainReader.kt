package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import com.smartisan.music.diagnostics.AppLog
import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [1.4.20·P1 ReplayGain] 读取音频文件的 ReplayGain 标签增益。
 *
 * 常见写法（ID3v2 TXXX / Vorbis Comment / APEv2）：
 *   REPLAYGAIN_TRACK_GAIN = +2.35 dB
 *   REPLAYGAIN_ALBUM_GAIN = -1.20 dB
 * 单位 dB，需换算成线性增益在 DSP 链路乘上去。
 *
 * 设计：
 *  - 内存 LRU 缓存（按 uri），一次扫描永久命中，避免切歌反复读盘；
 *  - 后台线程读取，失败/无标签返回 null（调用方回退到固定增益开关）；
 *  - 只读标签，绝不改文件。
 */
object ReplayGainReader {

    data class Gain(val trackDb: Float?, val albumDb: Float?)

    private data class CacheEntry(val gain: Gain?, val ts: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val MaxCache = 256

    /** 同步读取（调用方应在 IO 线程）。返回 null 表示文件无 ReplayGain 标签。 */
    fun read(context: Context, uri: Uri): Gain? {
        val key = uri.toString()
        cache[key]?.let { return it.gain }
        val gain = runCatching { readFromDisk(context, uri) }
            .onFailure { AppLog.w("ReplayGain", "读取失败 ${key.takeLast(40)}: ${it.message}") }
            .getOrNull()
        if (cache.size >= MaxCache) {
            cache.entries.minByOrNull { it.value.ts }?.let { cache.remove(it.key) }
        }
        cache[key] = CacheEntry(gain, System.currentTimeMillis())
        AppLog.i("ReplayGain", "标签 ${key.takeLast(40)} track=${gain?.trackDb} album=${gain?.albumDb}")
        return gain
    }

    private fun readFromDisk(context: Context, uri: Uri): Gain? {
        val file = resolveFile(context, uri) ?: return null
        val tag = AudioFileIO.read(file).tag ?: return null
        val track = parseDb(tag.getFirst("REPLAYGAIN_TRACK_GAIN"))
        val album = parseDb(tag.getFirst("REPLAYGAIN_ALBUM_GAIN"))
        if (track == null && album == null) return null
        return Gain(track, album)
    }

    /** "+2.35 dB" / "-1.2dB" / "2.35" → Float。失败返回 null。 */
    private fun parseDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace("dB", "", ignoreCase = true)
            .replace("db", "", ignoreCase = true)
            .trim()
        val value = cleaned.toFloatOrNull() ?: return null
        if (!value.isFinite() || value in -30f..30f == false) return null
        return value
    }

    private fun resolveFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            return f.takeIf { it.exists() && it.canRead() }
        }
        // content:// → 从 MediaStore.DATA 列拿真实路径
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(0)
                    if (!path.isNullOrBlank()) {
                        val f = File(path)
                        if (f.exists() && f.canRead()) return f
                    }
                }
            }
        }
        return null
    }

    fun clearCache() = cache.clear()
}
