package com.smartisan.music.platform.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile

/**
 * 音频物理参数探测（采样率 / 码率 / 位深 / 编码）。
 *
 * 三条取数路径，按可靠性依次降级：
 * 1. 解析文件头 —— 覆盖 FLAC / WAV / MP3 / OGG / M4A，不依赖系统版本；
 * 2. MediaMetadataRetriever —— 权威，但相关 key 从 Android 12 才稳定支持；
 * 3. size * 8 / duration 估算平均码率 —— 兜底，对无损文件几乎等于真实码率。
 */
object AudioFormatProbe {

    private const val HeadBytes = 512 * 1024

    data class Result(
        val sampleRateHz: Int = 0,
        val bitrateBps: Int = 0,
        val bitsPerSample: Int = 0,
        val codec: String = "",
        /** 从文件头解析出的时长（MediaStore 无时长 / retriever 读不出时兜底），0=未知 */
        val durationMs: Long = 0,
        /** 声道数（1.2.20：歌曲信息「输入声道」不再写死立体声），0=未知 */
        val channelCount: Int = 0,
    )

    fun probe(
        context: Context,
        uri: Uri,
        filePath: String?,
        fileSize: Long,
        durationMs: Long,
        mimeHint: String?,
        container: String,
    ): Result {
        var codec = mimeHint ?: mimeOf(container)

        var sampleRate = 0
        var bitrateBps = 0
        var bitsPerSample = 0
        var channelCount = 0
        var parsedDurationMs = 0L

        val head = readHead(context, uri, filePath)
        if (head != null) {
            val parsed = parseHeader(head, fileSize)
            sampleRate = parsed.sampleRateHz
            bitrateBps = parsed.bitrateBps
            bitsPerSample = parsed.bitsPerSample
            channelCount = parsed.channelCount
            parsedDurationMs = parsed.durationMs
            if (codec.isBlank() && parsed.codec.isNotBlank()) codec = parsed.codec
        }

        // DSD 系统解码器根本不支持，调 MediaMetadataRetriever 只会白等几百毫秒再抛异常；
        // probeAll 扫整库时这一条会直接拖慢几分钟。
        val skipRetriever = codec == "audio/dsd"
        if (!skipRetriever && (sampleRate == 0 || bitrateBps == 0 || bitsPerSample == 0)) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    if (sampleRate == 0) {
                        sampleRate = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
                        )?.toIntOrNull() ?: 0
                    }
                    if (bitrateBps == 0) {
                        bitrateBps = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
                        )?.toIntOrNull() ?: 0
                    }
                    if (bitsPerSample == 0) {
                        bitsPerSample = retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE
                        )?.toIntOrNull() ?: 0
                    }
                } finally {
                    retriever.release()
                }
            }.onFailure {
                com.smartisan.music.diagnostics.AppLog.d(
                    "AudioFormatProbe", "retriever failed uri=$uri err=${it.message}"
                )
            }
        }

        if (bitrateBps == 0 && parsedDurationMs > 500) {
            // MediaStore 无时长（如 DFF/DSF 补录行）时用头解析时长估码率
            val bps = (fileSize * 8.0 / (parsedDurationMs / 1000.0)).toLong()
            if (bps in 16_000..20_000_000) bitrateBps = bps.toInt()
        }
        if (bitrateBps == 0 && fileSize > 0 && durationMs > 500) {
            val bps = (fileSize * 8.0 / (durationMs / 1000.0)).toLong()
            if (bps in 16_000..20_000_000) bitrateBps = bps.toInt()
        }

        if (bitsPerSample == 0 && isLossyContainer(codec, container)) bitsPerSample = 16

        return Result(sampleRate, bitrateBps, bitsPerSample, codec, parsedDurationMs, channelCount)
    }

    private fun readHead(context: Context, uri: Uri, path: String?): ByteArray? {
        if (!path.isNullOrBlank()) {
            val viaFile = runCatching {
                val file = File(path)
                if (!file.exists() || !file.canRead()) return@runCatching null
                RandomAccessFile(file, "r").use { raf ->
                    val size = minOf(raf.length(), HeadBytes.toLong()).toInt()
                    if (size <= 0) return@runCatching null
                    val buf = ByteArray(size)
                    raf.readFully(buf, 0, size)
                    buf
                }
            }.getOrNull()
            if (viaFile != null) return viaFile
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    val buf = ByteArray(HeadBytes)
                    val read = stream.read(buf, 0, HeadBytes)
                    when {
                        read <= 0 -> null
                        read == HeadBytes -> buf
                        else -> buf.copyOf(read)
                    }
                }
            }
        }.getOrNull()
    }

    private data class Parsed(
        val sampleRateHz: Int = 0,
        val bitrateBps: Int = 0,
        val bitsPerSample: Int = 0,
        val codec: String = "",
        val durationMs: Long = 0,
        val channelCount: Int = 0,
    )

    private fun parseHeader(b: ByteArray, fileSize: Long = 0L): Parsed {
        if (b.size < 16) return Parsed()
        return when {
            startsWith(b, 0, "fLaC") -> parseFlac(b)
            startsWith(b, 0, "RIFF") && startsWith(b, 8, "WAVE") -> parseWav(b)
            startsWith(b, 0, "OggS") -> parseOgg(b)
            startsWith(b, 0, "ID3") || isMpegSync(b) -> parseMp3(b, fileSize)
            // APE（Monkey's Audio）："MAC " 描述头（系统无解码器，探测需自解析）
            startsWith(b, 0, "MAC ") -> parseApe(b)
            // DSD：DSF（小端 fmt@28）与 DSDIFF/DFF（大端 FRM8 容器）
            startsWith(b, 0, "DSD ") -> parseDsf(b)
            startsWith(b, 0, "FRM8") -> parseDff(b)
            else -> parseMp4(b)
        }
    }

    /**
     * APE（Monkey's Audio）头解析。
     *
     * v3980+：APE_DESCRIPTOR(52 字节，nDescriptorBytes@8) 之后是
     * APE_HEADER(24 字节)：nCompressionLevel@0 / nFormatFlags@2 / nBlocksPerFrame@4 /
     * nFinalFrameBlocks@8 / nTotalFrames@12 / nBitsPerSample@16 / nChannels@18 / nSampleRate@20。
     *
     * 旧版（v3.97 及更早）无 descriptor，头紧随 "MAC " 之后（nVersion@4），
     * 布局：nChannels@8 / nSampleRate@10 / nTotalFrames@22 / nFinalFrameBlocks@26 /
     * nBitsPerSample@30 / nBlockPerFrame@32。此前旧版直接归零，加之系统
     * MediaMetadataRetriever 读不出 APE，导致采样率/位深/码率全显示「—」(1.2.22 修复)。
     * 所有字段均按合法范围守卫，读到越界值即回落默认值，不会产出垃圾数值。
     */
    private fun parseApe(b: ByteArray): Parsed {
        if (b.size < 36) return Parsed(codec = "audio/ape")
        val version = readLeShort(b, 4)
        val headerAt = if (version >= 3980) {
            val descriptorBytes = readLeInt(b, 8)
            descriptorBytes.takeIf { it in 32..4096 } ?: 52
        } else {
            0
        }
        if (headerAt + 24 > b.size) return Parsed(codec = "audio/ape")
        val sampleRate = if (version >= 3980) readLeInt(b, headerAt + 20) else readLeInt(b, 10)
        val bits = if (version >= 3980) readLeShort(b, headerAt + 16) else readLeShort(b, 30)
        val channels = if (version >= 3980) readLeShort(b, headerAt + 18) else readLeShort(b, 8)
        val blocksPerFrame = if (version >= 3980) readLeInt(b, headerAt + 4) else readLeInt(b, 32)
        val totalFrames = if (version >= 3980) readLeInt(b, headerAt + 12) else readLeInt(b, 22)
        if (sampleRate <= 0 || sampleRate > 768_000) return Parsed(codec = "audio/ape")
        val bitsClamped = bits.takeIf { it in 8..32 } ?: 16
        val channelsClamped = channels.takeIf { it in 1..8 } ?: 2
        // APE 头里没有码率（无损），时长可由帧数×每帧块数/采样率算出，供码率估算兜底
        val durationMs =
            if (blocksPerFrame > 0 && totalFrames > 0) {
                totalFrames.toLong() * blocksPerFrame.toLong() * 1000L / sampleRate.toLong()
            } else {
                0L
            }
        return Parsed(
            sampleRateHz = sampleRate,
            bitsPerSample = bitsClamped,
            codec = "audio/ape",
            durationMs = durationMs,
            channelCount = channelsClamped,
        )
    }

    /** DSF：'DSD ' + u64LE(28) + u64LE 文件大小 + u64LE id3 偏移；'fmt '@28（小端），data@92。 */
    private fun parseDsf(b: ByteArray): Parsed {
        if (b.size < 104) return Parsed(codec = "audio/dsd")
        if (!startsWith(b, 28, "fmt ")) return Parsed(codec = "audio/dsd")
        val fmt = 40 // 'fmt '(4) + size(8) 之后 = payload 基址
        val channels = readLeInt(b, fmt + 12).takeIf { it in 1..8 } ?: 2
        val dsdRate = readLeInt(b, fmt + 16)
        val sampleCount = readLeLong(b, fmt + 24) // 每通道 DSD bit 数
        var dataBytes = 0L
        if (b.size >= 104 && startsWith(b, 92, "data")) {
            dataBytes = (readLeLong(b, 96) - 12).coerceAtLeast(0)
        }
        val durationMs =
            when {
                dataBytes > 0 && dsdRate > 0 ->
                    (dataBytes * 8_000.0 / (channels.toLong() * dsdRate)).toLong()
                sampleCount > 0 && dsdRate > 0 -> sampleCount * 1000 / dsdRate
                else -> 0L
            }
        return Parsed(
            // 显示/分级用等效 PCM 采样率（DSD64→352.8k），位深按输出等效 24bit
            sampleRateHz = (dsdRate / 8).takeIf { it > 0 } ?: 0,
            bitrateBps = if (dsdRate > 0) (channels * dsdRate) else 0,
            bitsPerSample = 24,
            codec = "audio/dsd",
            durationMs = durationMs,
            channelCount = channels.takeIf { it in 1..8 } ?: 0,
        )
    }

    /**
     * DSDIFF/DFF：'FRM8' + u64BE + 'DSD '(form type)；chunk 链（大端，chunk id 4 字节 +
     * u64BE size + payload）：'FVER' / 'PROP'('SND ' + 'FS  ' 采样率、'CHNL' 通道数) /
     * 'DSD ' 数据块。注意 CMPR 压缩类型标记也写作 'DSD '，数据块取头部的最后一个匹配。
     */
    private fun parseDff(b: ByteArray): Parsed {
        if (b.size < 64) return Parsed(codec = "audio/dsd")
        var channels = 0
        var dsdRate = 0
        var dataBytes = 0L
        // FS/CHNL 子块在头部几百字节内，限定搜索范围防误匹配音频数据
        val searchEnd = minOf(b.size, 512)
        val fsAt = indexOfRange(b, "FS  ", 16, searchEnd)
        if (fsAt >= 0 && fsAt + 16 <= b.size) {
            dsdRate = readBeInt(b, fsAt + 12)
        }
        val chnlAt = indexOfRange(b, "CHNL", 16, searchEnd)
        if (chnlAt >= 0 && chnlAt + 14 <= b.size) {
            channels = ((b[chnlAt + 12].toInt() and 0xFF) shl 8) or (b[chnlAt + 13].toInt() and 0xFF)
        }
        val dataAt = lastIndexOfRange(b, "DSD ", 24, minOf(b.size, 4096))
        if (dataAt >= 0 && dataAt + 12 <= b.size) {
            dataBytes = readBeLong(b, dataAt + 4).coerceAtLeast(0)
        }
        if (dsdRate <= 0) return Parsed(codec = "audio/dsd")
        val durationMs =
            if (dataBytes > 0 && channels > 0) {
                (dataBytes * 8_000.0 / (channels.toLong() * dsdRate)).toLong()
            } else {
                0L
            }
        return Parsed(
            sampleRateHz = (dsdRate / 8).takeIf { it > 0 } ?: 0,
            bitrateBps = if (channels > 0) channels * dsdRate else 0,
            bitsPerSample = 24,
            codec = "audio/dsd",
            durationMs = durationMs,
            channelCount = channels.takeIf { it in 1..8 } ?: 0,
        )
    }

    private fun indexOfRange(b: ByteArray, needle: String, from: Int, to: Int): Int {
        val bytes = needle.toByteArray(Charsets.US_ASCII)
        if (to > b.size) return -1
        for (i in from..(to - bytes.size)) {
            var matched = true
            for (j in bytes.indices) {
                if (b[i + j] != bytes[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun lastIndexOfRange(b: ByteArray, needle: String, from: Int, to: Int): Int {
        val bytes = needle.toByteArray(Charsets.US_ASCII)
        if (to > b.size) return -1
        for (i in (to - bytes.size) downTo from) {
            var matched = true
            for (j in bytes.indices) {
                if (b[i + j] != bytes[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun readLeShort(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size) 0 else (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLeLong(b: ByteArray, o: Int): Long {
        if (o + 8 > b.size) return 0L
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun readBeLong(b: ByteArray, o: Int): Long {
        if (o + 8 > b.size) return 0L
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun parseFlac(b: ByteArray): Parsed {
        if (b.size < 8 + 34) return Parsed(codec = "audio/flac")
        var offset = 4
        while (offset + 4 <= b.size) {
            val lastFlag = b[offset].toInt() and 0x80 != 0
            val type = b[offset].toInt() and 0x7F
            val length = ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
            val dataAt = offset + 4
            if (type == 0) {
                if (dataAt + 34 > b.size) return Parsed(codec = "audio/flac")
                val d = dataAt
                val sampleRate = ((b[d + 10].toInt() and 0xFF) shl 12) or
                    ((b[d + 11].toInt() and 0xFF) shl 4) or
                    ((b[d + 12].toInt() and 0xFF) shr 4)
                val bits = (((b[d + 12].toInt() and 0x01) shl 4) or
                    ((b[d + 13].toInt() and 0xF0) shr 4)) + 1
                val channels = (((b[d + 12].toInt() and 0x0E) shr 1)) + 1
                return Parsed(
                    sampleRateHz = sampleRate,
                    bitsPerSample = bits,
                    codec = "audio/flac",
                    channelCount = channels.takeIf { it in 1..8 } ?: 0,
                )
            }
            if (lastFlag) break
            offset = dataAt + length
        }
        return Parsed(codec = "audio/flac")
    }

    private fun parseWav(b: ByteArray): Parsed {
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val size = readLeInt(b, p + 4)
            if (id == "fmt " && p + 8 + 16 <= b.size) {
                val d = p + 8
                val sampleRate = readLeInt(b, d + 4)
                val bits = ((b[d + 14].toInt() and 0xFF) or ((b[d + 15].toInt() and 0xFF) shl 8))
                val channels = readLeShort(b, d + 2)
                return Parsed(
                    sampleRateHz = sampleRate,
                    bitsPerSample = bits,
                    codec = "audio/wav",
                    channelCount = channels.takeIf { it in 1..8 } ?: 0,
                )
            }
            if (size <= 0) break
            p += 8 + size + (if (size % 2 == 1) 1 else 0)
        }
        return Parsed(codec = "audio/wav")
    }

    private fun parseOgg(b: ByteArray): Parsed {
        val opus = indexOf(b, "OpusHead")
        if (opus >= 0 && opus + 12 < b.size) {
            val rate = readLeInt(b, opus + 12)
            return Parsed(sampleRateHz = rate, bitsPerSample = 16, codec = "audio/opus")
        }
        val vorbis = indexOf(b, "\u0001vorbis".toByteArray(Charsets.ISO_8859_1))
        if (vorbis >= 0 && vorbis + 20 < b.size) {
            val rate = readLeInt(b, vorbis + 12)
            return Parsed(sampleRateHz = rate, bitsPerSample = 16, codec = "audio/vorbis")
        }
        return Parsed(codec = "audio/ogg")
    }

    private fun parseMp3(b: ByteArray, fileSize: Long = 0L): Parsed {
        var offset = 0
        if (startsWith(b, 0, "ID3")) {
            if (b.size < 10) return Parsed(codec = "audio/mpeg")
            val tagSize = ((b[6].toInt() and 0x7F) shl 21) or
                ((b[7].toInt() and 0x7F) shl 14) or
                ((b[8].toInt() and 0x7F) shl 7) or
                (b[9].toInt() and 0x7F)
            offset = 10 + tagSize
        }
        var i = offset
        var guard = 0
        while (i + 4 <= b.size && guard++ < 200_000) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                val parsed = parseMp3Frame(b, i, fileSize)
                if (parsed != null) return parsed
            }
            i++
        }
        return Parsed(codec = "audio/mpeg")
    }

    private fun parseMp3Frame(b: ByteArray, o: Int, fileSize: Long = 0L): Parsed? {
        val versionBits = (b[o + 1].toInt() shr 3) and 0x03
        val version = when (versionBits) {
            3 -> 1
            2 -> 2
            0 -> 25
            else -> return null
        }
        val layerBits = (b[o + 1].toInt() shr 1) and 0x03
        val layer = 4 - layerBits
        if (layerBits == 0 || layer !in 1..3) return null

        val rateIndex = (b[o + 2].toInt() shr 2) and 0x03
        if (rateIndex == 3) return null
        val sampleRate = when (version) {
            1 -> intArrayOf(44100, 48000, 32000)[rateIndex]
            2 -> intArrayOf(22050, 24000, 16000)[rateIndex]
            else -> intArrayOf(11025, 12000, 8000)[rateIndex]
        }

        val bitrateIndex = (b[o + 2].toInt() shr 4) and 0x0F
        if (bitrateIndex == 0 || bitrateIndex == 15) return null
        val bitrateKbps = when {
            version == 1 && layer == 1 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)[bitrateIndex]
            version == 1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)[bitrateIndex]
            version == 1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex]
            layer == 1 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)[bitrateIndex]
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIndex]
        }

        val channelCount = (((b[o + 3].toInt() and 0xC0) shr 6) == 3).let { mono -> if (mono) 1 else 2 }

        // 1.2.20：Xing/Info 头（VBR 文件的常规标记）里记录总帧数——
        // 用「总帧数×每帧样本数」算真实时长，进而用 体积/时长 得到**平均**码率。
        // 此前直接取首帧 bitrate，VBR 文件显示的码率偏差可到 2 倍。
        var xingDurationMs = 0L
        if (layer == 3 && fileSize > 0) {
            val sideInfo = when {
                version == 1 && channelCount == 2 -> 32
                version == 1 -> 17
                channelCount == 2 -> 17
                else -> 9
            }
            val xingAt = o + 4 + sideInfo
            if (xingAt + 12 <= b.size &&
                (startsWith(b, xingAt, "Xing") || startsWith(b, xingAt, "Info"))
            ) {
                val flags = readBeInt(b, xingAt + 4)
                if (flags and 0x01 != 0) {
                    val frames = readBeInt(b, xingAt + 8)
                    val samplesPerFrame = if (version == 1) 1152 else 576
                    if (frames > 0 && sampleRate > 0) {
                        xingDurationMs = frames.toLong() * samplesPerFrame * 1000L / sampleRate
                    }
                }
            }
        }
        val effectiveBitrate =
            if (xingDurationMs > 500) {
                ((fileSize * 8.0 / (xingDurationMs / 1000.0)).toInt())
                    .takeIf { it in 8_000..320_000 }
                    ?: (bitrateKbps * 1000)
            } else {
                bitrateKbps * 1000
            }
        return Parsed(
            sampleRateHz = sampleRate,
            bitrateBps = effectiveBitrate,
            bitsPerSample = 16,
            codec = "audio/mpeg",
            durationMs = xingDurationMs,
            channelCount = channelCount,
        )
    }

    private fun parseMp4(b: ByteArray): Parsed {
        val mdhd = indexOf(b, "mdhd")
        var sampleRate = 0
        if (mdhd >= 0 && mdhd + 28 < b.size) {
            val version = b[mdhd + 4].toInt() and 0xFF
            val timescaleAt = mdhd + 8 + if (version == 1) 16 else 8
            if (timescaleAt + 4 <= b.size) sampleRate = readBeInt(b, timescaleAt)
        }
        var codec = ""
        val stsd = indexOf(b, "stsd")
        if (stsd >= 0 && stsd + 16 < b.size) {
            val format = String(b, stsd + 12, 4, Charsets.US_ASCII)
            codec = when (format) {
                "mp4a" -> "audio/mp4a-latm"
                "alac" -> "audio/alac"
                "flac", "fLaC" -> "audio/flac"
                "Opus", "opus" -> "audio/opus"
                else -> "audio/mp4"
            }
        }
        // 1.2.20：ALAC 真实位深与声道——
        // 采样条目（sample entry，"alac"/"mp4a" 串在 format 字段上）布局：
        //   format(+4) / reserved(6) / dataRef(+8..13) / reserved 8B(+16..23)
        //   channelCount(+24..25, 相对 format 串即 +20)
        // ALAC 扩展 'allc' cookie：ver/flags(+4) / frameLength(+8) /
        //   compatibleVersion(+12) / **bitDepth(+13)** / pb/mb/kb(+14..16) / channels(+17)
        var channels = 0
        var bits = 0
        val entryFormat = indexOfAny(b, listOf("alac", "mp4a", "fLaC", "flac"))
        if (entryFormat >= 0 && entryFormat + 22 <= b.size) {
            channels = ((b[entryFormat + 20].toInt() and 0xFF) shl 8) or
                (b[entryFormat + 21].toInt() and 0xFF)
        }
        if (codec == "audio/alac") {
            val allc = indexOfRange(b, "allc", maxOf(0, entryFormat), minOf(b.size, entryFormat + 4096))
            if (allc >= 0 && allc + 14 <= b.size) {
                bits = (b[allc + 13].toInt() and 0xFF).takeIf { it in 16..32 } ?: 0
            }
        }
        if (sampleRate == 0 && codec.isBlank()) return Parsed()
        return Parsed(
            sampleRateHz = sampleRate,
            bitsPerSample = bits,
            codec = codec,
            channelCount = channels.takeIf { it in 1..8 } ?: 0,
        )
    }

    private fun indexOfAny(b: ByteArray, needles: List<String>): Int {
        var best = -1
        for (n in needles) {
            val at = indexOf(b, n)
            if (at >= 0 && (best == -1 || at < best)) best = at
        }
        return best
    }

    private fun isMpegSync(b: ByteArray): Boolean =
        (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0

    private fun startsWith(b: ByteArray, offset: Int, s: String): Boolean {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        if (offset + bytes.size > b.size) return false
        for (i in bytes.indices) if (b[offset + i] != bytes[i]) return false
        return true
    }

    private fun indexOf(b: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || b.size < needle.size) return -1
        outer@ for (i in 0..(b.size - needle.size)) {
            for (j in needle.indices) {
                if (b[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun indexOf(b: ByteArray, needle: String): Int =
        indexOf(b, needle.toByteArray(Charsets.US_ASCII))

    private fun readLeInt(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return 0
        return (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
    }

    private fun readBeInt(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
    }

    private fun mimeOf(container: String): String = when (container) {
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ape" -> "audio/ape"
        "wma" -> "audio/x-ms-wma"
        else -> ""
    }

    private val Lossy = setOf(
        "audio/mpeg", "audio/mp4a-latm", "audio/aac", "audio/ogg",
        "audio/opus", "audio/vorbis", "audio/x-ms-wma", "audio/mp4"
    )

    private fun isLossyContainer(codec: String, container: String): Boolean {
        if (codec in Lossy) return true
        return container in setOf("mp3", "ogg", "oga", "opus", "aac", "m4a", "mp4", "wma")
    }
}
