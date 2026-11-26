package com.smartisan.music.platform.media

import android.content.Context
import com.smartisan.music.diagnostics.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音质探测结果缓存：按 uri + 时长 命中，避免每次扫描都重新解文件头。
 * 落盘为 filesDir/audio_format_cache.json。
 */
object FormatCache {

    private const val FileName = "audio_format_cache.json"
    // 1.2.20：Entry 增加 channelCount → v4（旧缓存自动失效，探测会重跑）
    private const val Version = 4

    private const val KeyVersion = "v"
    private const val KeyEntries = "e"
    private const val KeyKey = "k"
    private const val KeySampleRate = "sr"
    private const val KeyBitrate = "br"
    private const val KeyBitDepth = "bd"
    private const val KeyCodec = "c"
    private const val KeyDuration = "du"
    private const val KeyChannels = "ch"

    private data class Entry(
        val sampleRateHz: Int = 0,
        val bitrateBps: Int = 0,
        val bitsPerSample: Int = 0,
        val codec: String = "",
        val durationMs: Long = 0,
        val channelCount: Int = 0,
    )


    private val map = ConcurrentHashMap<String, Entry>()
    private val dirty = AtomicBoolean(false)

    fun load(context: Context) {
        runCatching {
            val file = File(context.filesDir, FileName)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            if (root.optInt(KeyVersion, -1) != Version) return
            val entries = root.optJSONArray(KeyEntries) ?: return
            val loaded = HashMap<String, Entry>(entries.length())
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val key = item.optString(KeyKey)
                if (key.isBlank()) continue
                loaded[key] =
                    Entry(
                        sampleRateHz = item.optInt(KeySampleRate),
                        bitrateBps = item.optInt(KeyBitrate),
                        bitsPerSample = item.optInt(KeyBitDepth),
                        codec = item.optString(KeyCodec),
                        durationMs = item.optLong(KeyDuration),
                        channelCount = item.optInt(KeyChannels),
                    )
            }
            map.clear()
            map.putAll(loaded)
            AppLog.i("FormatCache", "loaded ${map.size} entries")
        }.onFailure {
            AppLog.w("FormatCache", "load failed: ${it.message}")
        }
    }

    fun save(context: Context) {
        if (!dirty.compareAndSet(true, false)) return
        runCatching {
            val entries = JSONArray()
            map.forEach { (key, entry) ->
                entries.put(
                    JSONObject()
                        .put(KeyKey, key)
                        .put(KeySampleRate, entry.sampleRateHz)
                        .put(KeyBitrate, entry.bitrateBps)
                        .put(KeyBitDepth, entry.bitsPerSample)
                        .put(KeyCodec, entry.codec)
                        .put(KeyDuration, entry.durationMs)
                        .put(KeyChannels, entry.channelCount),
                )
            }
            val root = JSONObject().put(KeyVersion, Version).put(KeyEntries, entries)
            File(context.filesDir, FileName).writeText(root.toString())
            AppLog.i("FormatCache", "saved ${map.size} entries")
        }.onFailure {
            AppLog.w("FormatCache", "save failed: ${it.message}")
        }
    }

    private fun key(uri: String, durationMs: Long) = "$durationMs|$uri"

    fun get(uri: String, durationMs: Long): AudioFormatProbe.Result? {
        val entry = map[key(uri, durationMs)] ?: return null
        return AudioFormatProbe.Result(
            sampleRateHz = entry.sampleRateHz,
            bitrateBps = entry.bitrateBps,
            bitsPerSample = entry.bitsPerSample,
            codec = entry.codec,
            durationMs = entry.durationMs,
            channelCount = entry.channelCount,
        )
    }

    fun put(uri: String, durationMs: Long, result: AudioFormatProbe.Result) {
        if (result.sampleRateHz == 0 && result.bitrateBps == 0 && result.channelCount == 0) return
        map[key(uri, durationMs)] = Entry(
            sampleRateHz = result.sampleRateHz,
            bitrateBps = result.bitrateBps,
            bitsPerSample = result.bitsPerSample,
            codec = result.codec,
            durationMs = result.durationMs,
            channelCount = result.channelCount,
        )
        dirty.set(true)
    }

    fun coverage(): Int = map.size

    /** 后台探测全部未命中的条目，返回探测数量 */
    fun probeAll(
        context: Context,
        items: List<ProbeInput>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        var done = 0
        var probed = 0
        val total = items.size
        for (input in items) {
            if (get(input.uri, input.durationMs) == null) {
                runCatching {
                    val result = AudioFormatProbe.probe(
                        context = context,
                        uri = android.net.Uri.parse(input.uri),
                        filePath = input.filePath,
                        fileSize = input.fileSize,
                        durationMs = input.durationMs,
                        mimeHint = input.mimeType,
                        container = input.container
                    )
                    put(input.uri, input.durationMs, result)
                    probed++
                }
            }
            done++
            if (done % 25 == 0 || done == total) onProgress(done, total)
        }
        save(context)
        AppLog.i("FormatCache", "probeAll done total=$total probed=$probed")
        return probed
    }

    data class ProbeInput(
        val uri: String,
        val filePath: String?,
        val fileSize: Long,
        val durationMs: Long,
        val mimeType: String?,
        val container: String,
    )
}

/** 依据采样率/位深/码率推断音质档位，与本地 UI 徽章一致 */
object AudioQualityTiers {

    private val LosslessContainers = setOf(
        "flac", "wav", "wave", "ape", "alac", "aiff", "aif", "dsf", "dff", "wv"
    )
    private val LosslessCodecs = setOf(
        "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/ape",
        "audio/alac", "audio/aiff", "audio/dsf",
        // 1.2.19：DSD(DFF/DSF) 是无损原生流，此前漏登记导致 DSD 被判成「高码率」有损档，
        // 按音质排序时排在 16bit FLAC 后面
        "audio/dsd"
    )

    enum class Tier(val badge: String, val rank: Int) {
        HiRes("Hi-Res", 4),
        Lossless("无损", 3),
        High("高码率", 2),
        Normal("标准", 1),
        Unknown("未知", 0)
    }

    fun tierOf(container: String, codec: String, sampleRateHz: Int, bitsPerSample: Int, bitrateBps: Int): Tier {
        val lossless = container.lowercase() in LosslessContainers || codec.lowercase() in LosslessCodecs
        return when {
            lossless && bitsPerSample >= 24 && sampleRateHz >= 88200 -> Tier.HiRes
            lossless && bitsPerSample >= 24 && sampleRateHz >= 48000 -> Tier.HiRes
            lossless && bitsPerSample == 0 && sampleRateHz >= 96000 -> Tier.HiRes
            lossless -> Tier.Lossless
            bitrateBps >= 320_000 -> Tier.High
            bitrateBps > 0 -> Tier.Normal
            else -> Tier.Unknown
        }
    }

    /** 汇总一行描述，如 "44.1kHz · 16bit · 320kbps" */
    fun summary(sampleRateHz: Int, bitsPerSample: Int, bitrateBps: Int, container: String): String {
        val parts = ArrayList<String>(4)
        if (sampleRateHz > 0) parts += "${fmtKhz(sampleRateHz)}kHz"
        if (bitsPerSample > 0) parts += "${bitsPerSample}bit"
        if (bitrateBps > 0) parts += "${bitrateBps / 1000}kbps"
        val c = container.uppercase()
        if (c.isNotBlank()) parts += c
        return parts.joinToString(" · ").ifBlank { "未知" }
    }

    private fun fmtKhz(hz: Int): String {
        val v = hz / 1000f
        return if (v >= 100) v.toInt().toString() else (max(1, Math.round(v * 10)) / 10f).toString()
    }
}
