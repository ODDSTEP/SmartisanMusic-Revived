package com.smartisan.music.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.smartisan.music.diagnostics.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 接管 Media3 内置 WavExtractor 不支持的 WAV 文件，当前主要是：
 *  - WAVE_FORMAT_IEEE_FLOAT（tag 3）且位深 64bit 的浮点 WAV；
 *  - WAVE_FORMAT_EXTENSIBLE（tag 0xFFFE）中子格式为 64bit 浮点的 WAV；
 *  - 64bit 整型 PCM 的 WAV（极罕见）。
 *
 * 处理方式：自己解析 RIFF 结构，边读边把 64bit 样本转成 32bit 浮点 PCM 输出，
 * 由 AudioSink 以 ENCODING_PCM_FLOAT 直出（解码链路本来就是浮点直通，无额外损耗路径）。
 * 普通位深（8/16/24/32/float32）的 WAV 在 sniff 阶段直接放行给内置提取器。
 */
@UnstableApi
class HiResWavExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> {
        val defaultExtractors = androidx.media3.extractor.DefaultExtractorsFactory().createExtractors()
        return arrayOf<Extractor>(HiResWavExtractor()) + defaultExtractors
    }
}

@UnstableApi
internal class HiResWavExtractor : Extractor {

    private object ChunkIds {
        val riff = "RIFF".toByteArray(Charsets.US_ASCII)
        val wave = "WAVE".toByteArray(Charsets.US_ASCII)
        val fmt = "fmt ".toByteArray(Charsets.US_ASCII)
        val data = "data".toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val WAVE_FORMAT_PCM = 0x0001
        const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        /** 嗅探阶段最多向前翻找 fmt 块的字节数，防止把大 data 块整个读进内存。 */
        const val SniffScanLimitBytes = 512 * 1024

        /** 输出片段大小（转换后字节），约 50ms@192kHz/2ch/32bit。 */
        const val EmitChunkBytes = 64 * 1024
    }

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var writerTrackOutput: TrackOutput? = null

    // ---- 解析结果（read 阶段填充）----
    private var sampleRateHz = 0
    private var channelCount = 0
    private var bitsPerSample = 0
    private var formatTag = 0
    private var blockAlign = 0
    private var dataStartPosition = C.LENGTH_UNSET.toLong()
    private var dataEndPosition = C.LENGTH_UNSET.toLong()
    private var dataSizeBytes = C.LENGTH_UNSET.toLong()
    private var pcmEncoding = 0

    // ---- read 状态机 ----
    private var state = State.PARSING_HEADER
    private var pendingSeekPosition = C.POSITION_UNSET.toLong()
    private var framesEmitted = 0L
    private var scratch = ByteArray(EmitChunkBytes)
    private var headerBuffer = ByteArray(8)

    private enum class State { PARSING_HEADER, EMITTING_PCM, FINISHED }

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(12)
        if (!input.peekFully(header, 0, 12, true)) return false
        if (!ChunkIds.riff.contentEquals(header.copyOfRange(0, 4))) return false
        if (!ChunkIds.wave.contentEquals(header.copyOfRange(8, 12))) return false
        // 只接管内置提取器处理不了的 64bit WAV；其余格式放行。
        if (!sniffSupports64Bit(input)) return false
        AppLog.i("Decode", "hires wav detected -> custom extractor")
        return true
    }

    /**
     * 在嗅探阶段逐块扫描 fmt 块，判断是否是内置提取器不支持的 64bit 样本。
     * 全程使用 peek，不改变读取位置，失败时内置提取器仍可从头 sniff。
     */
    private fun sniffSupports64Bit(input: ExtractorInput): Boolean {
        input.resetPeekPosition()
        // 先跳过 12 字节 RIFF 头（"RIFF"+size+"WAVE"），从第一个真正的 chunk 开始扫。
        // 注意：RIFF 头里的 size 是整个文件大小，若把它当 chunk 解析会把探测位置
        // 推到几百 MB 之外，直接超过扫描上限导致永远嗅探失败。
        if (!input.peekFully(ByteArray(12), 0, 12, true)) return false
        var scanned = 12
        val chunkHeader = ByteArray(8)
        while (scanned < SniffScanLimitBytes) {
            if (!input.peekFully(chunkHeader, 0, 8, true)) return false
            scanned += 8
            val id = chunkHeader.copyOfRange(0, 4)
            val size = readLeInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
            if (id.contentEquals(ChunkIds.fmt)) {
                if (size < 16) return false
                val fmt = ByteArray(size.coerceAtMost(64L).toInt())
                if (!input.peekFully(fmt, 0, fmt.size, true)) return false
                val tag = readLeShort(fmt, 0)
                val channels = readLeShort(fmt, 2)
                val bits = readLeShort(fmt, 14)
                var effectiveTag = tag
                if (tag == WAVE_FORMAT_EXTENSIBLE && size >= 40) {
                    // cbSize(2) + validBits(2) + channelMask(4) 后是 16 字节子格式 GUID，前 2 字节是真实格式码
                    effectiveTag = readLeShort(fmt, 24)
                }
                val supported64 =
                    channels > 0 && bits == 64 &&
                        (effectiveTag == WAVE_FORMAT_IEEE_FLOAT || effectiveTag == WAVE_FORMAT_PCM)
                if (supported64) {
                    AppLog.d(
                        "Decode",
                        "hires wav sniff: tag=0x${effectiveTag.toString(16)} bits=$bits ch=$channels",
                    )
                }
                return supported64
            }
            if (id.contentEquals(ChunkIds.data)) {
                // data 在 fmt 之前：非标准结构，交给内置提取器处理
                return false
            }
            // 其它块（LIST/JUNK/fact 等）：跳过继续找 fmt。
            // 先做上限检查再推进，避免对超大块执行无意义的 advancePeekPosition。
            val advance = ((size + 1) / 2 * 2).toInt()
            if (scanned + advance > SniffScanLimitBytes) return false
            scanned += advance
            if (!input.advancePeekPosition(advance, true)) return false
        }
        return false
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        // 复位解析结果：框架在 seek / 重新嗅探时可能复用同一 Extractor 实例，
        // 解析字段对同一个文件是常量，这里复位可保证下次从头解析时干净。
        sampleRateHz = 0
        channelCount = 0
        bitsPerSample = 0
        formatTag = 0
        blockAlign = 0
        dataStartPosition = C.LENGTH_UNSET.toLong()
        dataEndPosition = C.LENGTH_UNSET.toLong()
        dataSizeBytes = C.LENGTH_UNSET.toLong()
        pcmEncoding = 0
        state = State.PARSING_HEADER
    }

    override fun read(input: ExtractorInput, seekPositionHolder: PositionHolder): Int {
        return when (state) {
            State.PARSING_HEADER -> {
                if (!parseRiffHeader(input)) {
                    AppLog.e("Decode", "hires wav: header parse failed")
                    state = State.FINISHED
                    extractorOutput?.endTracks()
                    return Extractor.RESULT_END_OF_INPUT
                }
                if (trackOutput == null) {
                    emitTrackFormat()
                }
                state = State.EMITTING_PCM
                Extractor.RESULT_CONTINUE
            }
            State.EMITTING_PCM -> emitPcm(input)
            State.FINISHED -> Extractor.RESULT_END_OF_INPUT
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        if (state == State.FINISHED) return
        // 框架（BundledExtractorsAdapter）不会替我们重定位 ExtractorInput，
        // 它只是把目标字节位置传进来；真正的定位由 emitPcm 里的「前跳」完成。
        // 目标必须落在 data 区内；小于 dataStartPosition 时收敛到起点。
        val target = if (dataStartPosition != C.LENGTH_UNSET.toLong() && position < dataStartPosition) {
            dataStartPosition
        } else {
            position
        }
        pendingSeekPosition = target
        // 头部已解析过就直接进入发射态；否则留待 read 的 PARSING_HEADER 阶段先解析头部。
        state = if (dataStartPosition != C.LENGTH_UNSET.toLong()) State.EMITTING_PCM else State.PARSING_HEADER
        AppLog.i(
            "Decode",
            "hires wav seek: position=$position -> target=$target state=${state} " +
                "dataStart=$dataStartPosition curPos=${if (dataStartPosition != C.LENGTH_UNSET.toLong()) "(待读取)" else "未知"}",
        )
    }

    override fun release() {
        extractorOutput = null
        trackOutput = null
        writerTrackOutput = null
    }

    // ---- 头部解析（read 阶段，会真实移动读取位置）----

    private fun parseRiffHeader(input: ExtractorInput): Boolean {
        val riffHeader = ByteArray(12)
        if (input.position == 0L) {
            input.readFully(riffHeader, 0, 12)
            if (!ChunkIds.riff.contentEquals(riffHeader.copyOfRange(0, 4)) ||
                !ChunkIds.wave.contentEquals(riffHeader.copyOfRange(8, 12))
            ) {
                return false
            }
        }
        while (true) {
            input.readFully(headerBuffer, 0, 8)
            val id = headerBuffer.copyOfRange(0, 4)
            val size = readLeInt(headerBuffer, 4).toLong() and 0xFFFFFFFFL
            when {
                id.contentEquals(ChunkIds.fmt) -> {
                    if (size < 16 || size > 64) return false
                    val fmt = ByteArray(size.toInt())
                    input.readFully(fmt, 0, fmt.size)
                    if (fmt.size % 2 == 1) input.skipFully(1)
                    formatTag = readLeShort(fmt, 0)
                    channelCount = readLeShort(fmt, 2)
                    sampleRateHz = readLeInt(fmt, 4)
                    blockAlign = readLeShort(fmt, 12)
                    bitsPerSample = readLeShort(fmt, 14)
                    var effectiveTag = formatTag
                    if (formatTag == WAVE_FORMAT_EXTENSIBLE && size >= 40) {
                        effectiveTag = readLeShort(fmt, 24)
                    }
                    if (effectiveTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 64) {
                        pcmEncoding = C.ENCODING_PCM_FLOAT
                    } else if (effectiveTag == WAVE_FORMAT_PCM && bitsPerSample == 64) {
                        pcmEncoding = C.ENCODING_PCM_FLOAT
                    } else {
                        // 不该走到这里（sniff 已过滤），保险起见报告不支持
                        return false
                    }
                    if (channelCount <= 0 || sampleRateHz <= 0 || blockAlign <= 0) return false
                }
                id.contentEquals(ChunkIds.data) -> {
                    dataStartPosition = input.position
                    if (size == 0L || size == 0xFFFFFFFFL ||
                        (input.length != C.LENGTH_UNSET.toLong() && input.position + size > input.length)
                    ) {
                        dataSizeBytes = (input.length - input.position).coerceAtLeast(0)
                    } else {
                        dataSizeBytes = size
                    }
                    dataEndPosition = dataStartPosition + dataSizeBytes
                    return true
                }
                else -> {
                    if (size > Int.MAX_VALUE) return false
                    input.skipFully(size.toInt())
                    if (size % 2 == 1L) input.skipFully(1)
                }
            }
        }
    }

    private fun emitTrackFormat() {
        val output = extractorOutput ?: return
        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        trackOutput = track
        writerTrackOutput = track
        val durationUs = if (dataSizeBytes > 0) {
            (dataSizeBytes * 1_000_000L) / bytesPerSecond()
        } else {
            C.TIME_UNSET
        }
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRateHz)
                .setChannelCount(channelCount)
                .setAverageBitrate(sampleRateHz * channelCount * 64) // 原始 64bit 码率
                .build(),
        )
        output.endTracks()
        output.seekMap(PcmSeekMap(durationUs))
        AppLog.i(
            "Decode",
            "hires wav: ${sampleRateHz}Hz ${channelCount}ch ${bitsPerSample}bit " +
                "tag=0x${effectiveTagHex()} size=${dataSizeBytes / 1024 / 1024}MB -> float32 输出",
        )
    }

    private fun effectiveTagHex(): String {
        val tag = if (formatTag == WAVE_FORMAT_EXTENSIBLE) WAVE_FORMAT_IEEE_FLOAT else formatTag
        return tag.toString(16)
    }

    // ---- 样本转换与输出 ----

    private fun emitPcm(input: ExtractorInput): Int {
        if (pendingSeekPosition != C.POSITION_UNSET.toLong()) {
            val target = pendingSeekPosition
            pendingSeekPosition = C.POSITION_UNSET.toLong()
            val currentPos = input.position
            // 关键：从「当前读取位置」前跳到 target。框架不替我们定位，
            // 且内置 Extractor 只支持前跳（后跳由框架在流头 0 处重开处理），
            // 所以 delta 应始终 >= 0。若 < 0 说明走到异常分支，记日志告警。
            val delta = target - currentPos
            if (delta > 0) {
                var remainingSkip = delta
                while (remainingSkip > 0) {
                    val step = remainingSkip.coerceAtMost(256L * 1024 * 1024)
                    input.skipFully(step.toInt())
                    remainingSkip -= step
                }
            } else if (delta < 0) {
                AppLog.w(
                    "Decode",
                    "hires wav seek: 需要后跳 delta=$delta (cur=$currentPos target=$target) " +
                        "—— 框架应已在流头 0 处重开；若此告警后杂音，请发日志",
                )
            }
            val landed = input.position
            // 对齐校验：样本边界相对 dataStartPosition 必须为 blockAlign 的整数倍，
            // 否则会从错字节开始解码成白噪。SeekMap 已返回对齐位置，这里只是兜底报警。
            val offInData = landed - dataStartPosition
            if (blockAlign > 0 && offInData % blockAlign != 0L) {
                AppLog.e(
                    "Decode",
                    "hires wav seek: 未对齐! landed=$landed dataStart=$dataStartPosition " +
                        "offsetInData=$offInData blockAlign=$blockAlign -> 杂音风险",
                )
            }
            framesEmitted = (target - dataStartPosition).coerceAtLeast(0) / blockAlign.coerceAtLeast(1)
            AppLog.i(
                "Decode",
                "hires wav seek applied: from=$currentPos to=$target landed=$landed " +
                    "frames=$framesEmitted",
            )
        }
        val track = writerTrackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val remaining = if (dataEndPosition != C.LENGTH_UNSET.toLong()) {
            (dataEndPosition - input.position).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }
        if (remaining == 0L) {
            state = State.FINISHED
            return Extractor.RESULT_END_OF_INPUT
        }
        // 读入按 blockAlign 对齐的 64bit 样本
        var readBytes = remaining.coerceAtMost(EmitChunkBytes.toLong()).toInt()
        readBytes -= readBytes % blockAlign.coerceAtLeast(1)
        if (readBytes <= 0) readBytes = blockAlign * 2
        if (scratch.size < readBytes) scratch = ByteArray(readBytes)
        val got = input.read(scratch, 0, readBytes)
        if (got == C.RESULT_END_OF_INPUT) {
            state = State.FINISHED
            return Extractor.RESULT_END_OF_INPUT
        }
        var usable = got
        usable -= usable % blockAlign.coerceAtLeast(1)
        if (usable <= 0) {
            state = State.FINISHED
            return Extractor.RESULT_END_OF_INPUT
        }
        val outFrames = usable / blockAlign
        val outBytes = outFrames * channelCount * 4
        val outBuffer = ByteArray(outBytes)
        val src = ByteBuffer.wrap(scratch, 0, usable).order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteBuffer.wrap(outBuffer).order(ByteOrder.LITTLE_ENDIAN)
        when (pcmEncoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (src.remaining() >= 8) {
                    dst.putFloat(src.double.toFloat())
                }
            }
            else -> {
                // 64bit 整型 PCM：归一到 [-1, 1) 浮点
                while (src.remaining() >= 8) {
                    val v = src.long
                    dst.putFloat((v.toDouble() / 9.223372036854776E18).toFloat())
                }
            }
        }
        track.sampleData(ParsableByteArray(outBuffer, outBytes), outBytes)
        val frameCount = outFrames.toLong()
        val timeUs = (framesEmitted * 1_000_000L) / sampleRateHz.coerceAtLeast(1)
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, outBytes, 0, null)
        framesEmitted += frameCount
        return Extractor.RESULT_CONTINUE
    }

    private fun bytesPerSecond(): Long = sampleRateHz.toLong() * blockAlign

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(bytes: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 3 downTo 0) {
            v = (v shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return v
    }

    /** 恒定码率线性 PCM 的 SeekMap。 */
    private inner class PcmSeekMap(private val durationUs: Long) : SeekMap {
        override fun isSeekable(): Boolean = durationUs != C.TIME_UNSET && durationUs > 0

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable()) return SeekMap.SeekPoints(SeekPoint.START)
            val clamped = timeUs.coerceIn(0, durationUs)
            val byteOffset = (bytesPerSecond() * clamped) / 1_000_000L
            val aligned = byteOffset - (byteOffset % blockAlign)
            val position = dataStartPosition + aligned
            return SeekMap.SeekPoints(SeekPoint(clamped, position))
        }

        override fun getDurationUs(): Long = durationUs
    }
}
