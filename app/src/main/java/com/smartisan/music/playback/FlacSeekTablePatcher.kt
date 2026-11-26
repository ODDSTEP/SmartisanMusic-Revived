package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * 给 ffmpeg 转出的 FLAC 补写 `SEEKTABLE` 元数据块（1.2.25 引入，1.2.29 依据 FLAC 规范重写）。
 *
 * ## 1.2.29 修掉的两个致命错误（1.2.25~1.2.28 一直存在，是「拖动就播放失败」的真正根因）
 *
 * 1. **streamOffset 写成了「文件绝对偏移」**。FLAC 规范定义 SEEKTABLE 的
 *    `streamOffset` 是「从**第一帧的第一个字节**算起，到目标帧头的字节偏移」，
 *    不是从文件开头算起。ExoPlayer 的 `FlacExtractor` 按
 *    `firstFramePosition + streamOffset` 定位，旧版多加了约 8KB 的元数据区长度，
 *    解析器落在帧中间、找不到帧同步码 `0xFF 0xF8`，直接抛
 *    `contentIsMalformed=true` → `ERROR_CODE_PARSING_CONTAINER_MALFORMED`(3001)。
 *    这就是日志里「DS open#N 到某个 pos → 立刻 DS close → load error canceled=true → 3001」
 *    的完整链路，也解释了为什么「能落点不弹回，但落点之后就播放失败」。
 * 2. **sampleNumber 用 STREAMINFO 的 maxBlockSize 估算**。固定块流里帧头存的是帧号，
 *    旧版拿 `maxBlockSize` 当每帧样本数相乘；ffmpeg 实际块大小与之不一致时，
 *    时间→位置映射整体偏移，seek 越远越偏。现在改为逐帧解析帧头里的**真实 blockSize**。
 *
 * 附带修掉：旧版遇到文件里已有 SEEKTABLE 就无条件 `return true`，导致 1.2.25~1.2.28
 * 写坏的历史缓存升级后永远修不好；现在先做语义校验，不合法就**就地覆盖重写**，
 * 无需用户清缓存、无需重新转码。
 *
 * ## 做法
 * 1. 遍历 metadata blocks，记下 STREAMINFO、PADDING、SEEKTABLE 的位置，得出第一帧位置
 *    `audioStart`（= 元数据链结束处）；
 * 2. 顺序扫描音频帧同步码，用「同步码 + 帧头字段合法性 + 帧头 CRC-8 + 样本号递增」定位每一帧，
 *    并从帧头解析出真实 blockSize；
 * 3. 已有 SEEKTABLE → 按原块长度覆盖写入正确的点（结构不变）；没有 → 把 PADDING 块
 *    就地改写成「SEEKTABLE + 剩余 PADDING」，总长度不变，音频数据一字节不动；
 * 4. 写前备份、写后复核（含「第一个点 offset==0」「链尾紧跟帧同步码」），
 *    任何异常回滚并返回 false——绝不能把本来能播的文件写坏。
 *
 * 全程在 IO 线程同步执行（143MB 约 1~3 秒），只做一次；之后缓存命中即带索引。
 */
internal object FlacSeekTablePatcher {

    private const val TAG = "FlacSeekTable"

    private const val TypeStreamInfo = 0
    private const val TypePadding = 1
    private const val TypeSeekTable = 3

    /** 一个 seek point 占 18 字节：sampleNumber(8) + streamOffset(8) + frameSamples(2)。 */
    private const val SeekPointBytes = 18

    /** 真实帧数低于此值说明扫描不可信，放弃。 */
    private const val MinFrames = 32

    /** 新建 SEEKTABLE 时的点数上限（8192B 的 PADDING 最多放 ~454 个）。 */
    private const val MaxSeekPoints = 400

    private const val ScanWindow = 1 shl 20 // 1MB
    private const val ScanOverlap = 64

    /**
     * 确保 [file] 带一个**语义正确**的 SEEKTABLE。
     *
     * @return true 表示该文件当前已带可用索引（本来就合法，或本次写入成功）
     */
    fun ensureSeekTable(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        // 已经合法就别动文件：避免每次缓存命中都重写 143MB 文件的元数据区。
        if (runCatching { validate(file) }.getOrDefault(false)) return true
        return runCatching { patch(file) }
            .onFailure { AppLog.w(TAG, "补索引失败（未改动文件）: ${it.message}") }
            .getOrDefault(false)
    }

    private data class Layout(
        val streamInfoOffset: Long,
        val streamInfoLength: Int,
        val paddingOffset: Long,
        val paddingLength: Int,
        val paddingIsLast: Boolean,
        val seekTableOffset: Long,
        val seekTableLength: Int,
        val audioStart: Long,
    )

    private fun readLayout(raf: RandomAccessFile): Layout? {
        if (raf.length() < 8L) return null
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "fLaC") return null
        var pos = 4L
        var streamInfoOffset = -1L
        var streamInfoLength = 0
        var paddingOffset = -1L
        var paddingLength = 0
        var paddingIsLast = false
        var seekTableOffset = -1L
        var seekTableLength = 0
        while (pos + 4 <= raf.length()) {
            raf.seek(pos)
            val header = raf.readUnsignedByte()
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val length = (raf.readUnsignedByte() shl 16) or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()
            when (type) {
                TypeStreamInfo -> {
                    streamInfoOffset = pos + 4
                    streamInfoLength = length
                }
                TypePadding -> {
                    if (length > paddingLength) {
                        paddingOffset = pos
                        paddingLength = length
                        // 必须记住 PADDING 是不是「最后一块」。只有它是最后一块时，
                        // 新写的尾部 PADDING 才能带 last 位；否则文件里还有其它元数据块，
                        // 提前打 last 会让解码器把后面的元数据当音频帧 → container malformed。
                        paddingIsLast = isLast
                    }
                }
                TypeSeekTable -> {
                    seekTableOffset = pos // 块头位置；数据区 = pos + 4
                    seekTableLength = length
                }
            }
            pos += 4 + length
            if (isLast) break
        }
        if (streamInfoOffset < 0 || streamInfoLength < 34) return null
        return Layout(
            streamInfoOffset, streamInfoLength,
            paddingOffset, paddingLength, paddingIsLast,
            seekTableOffset, seekTableLength,
            pos, // 元数据链结束 = 第一帧的第一个字节
        )
    }

    private data class Plan(
        /** 写入起点。已有 SEEKTABLE 时是数据区起点，新建时是 PADDING 块头。 */
        val writeOffset: Long,
        val payload: ByteArray,
        val pointCount: Int,
        val audioStart: Long,
        val firstSample: Long,
        val firstOffset: Long,
        val lastSample: Long,
        val lastOffset: Long,
        val overwriteExisting: Boolean,
    )

    private fun patch(file: File): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val layout = readLayout(raf) ?: return false
            val streamInfo = ByteArray(layout.streamInfoLength)
            raf.seek(layout.streamInfoOffset)
            raf.readFully(streamInfo)
            val totalSamples = readTotalSamples(streamInfo)

            val frames = scanFrames(raf, layout.audioStart)
            if (frames.size < MinFrames) {
                AppLog.i(TAG, "帧数不足，跳过: ${file.name} frames=${frames.size}")
                return false
            }
            if (totalSamples > 0) {
                val coverage = frames.last().sample.toDouble() / totalSamples.toDouble()
                if (coverage < 0.90 || coverage > 1.10) {
                    AppLog.w(
                        TAG,
                        "扫描覆盖异常，放弃: ${file.name} coverage=" +
                            String.format(Locale.US, "%.2f", coverage),
                    )
                    return false
                }
            }

            val plan = if (layout.seekTableOffset >= 0) {
                // 已有 SEEKTABLE（可能是 1.2.25~1.2.28 写坏的）：只覆盖数据区，块头与长度不变。
                val n = layout.seekTableLength / SeekPointBytes
                if (n < 8) return false
                val selected = pickSeekPoints(frames, n)
                Plan(
                    writeOffset = layout.seekTableOffset + 4,
                    payload = buildPoints(selected, n, layout.audioStart),
                    pointCount = n,
                    audioStart = layout.audioStart,
                    firstSample = selected.first().sample,
                    firstOffset = selected.first().offset - layout.audioStart,
                    lastSample = selected.last().sample,
                    lastOffset = selected.last().offset - layout.audioStart,
                    overwriteExisting = true,
                )
            } else {
                if (layout.paddingOffset < 0 ||
                    layout.paddingLength < 4 + 30 * SeekPointBytes
                ) {
                    AppLog.i(TAG, "PADDING 不足，跳过: ${file.name} padding=${layout.paddingLength}")
                    return false
                }
                val n = minOf(
                    (layout.paddingLength - 4) / SeekPointBytes,
                    MaxSeekPoints,
                )
                if (n < 8) return false
                val selected = pickSeekPoints(frames, n)
                val m = layout.paddingLength - 4 - n * SeekPointBytes
                Plan(
                    writeOffset = layout.paddingOffset,
                    payload = buildNewBlocks(selected, n, m, layout.paddingIsLast, layout.audioStart),
                    pointCount = n,
                    audioStart = layout.audioStart,
                    firstSample = selected.first().sample,
                    firstOffset = selected.first().offset - layout.audioStart,
                    lastSample = selected.last().sample,
                    lastOffset = selected.last().offset - layout.audioStart,
                    overwriteExisting = false,
                )
            }

            // 第一个点必须精确落在 (0, 0)；否则 ExoPlayer 的 SeekMap 依旧会偏。
            if (plan.firstSample != 0L || plan.firstOffset != 0L) {
                AppLog.w(
                    TAG,
                    "首帧未对齐，放弃写入: ${file.name} sample=${plan.firstSample} offset=${plan.firstOffset}",
                )
                return false
            }

            val backup = ByteArray(plan.payload.size)
            raf.seek(plan.writeOffset)
            raf.readFully(backup)
            raf.seek(plan.writeOffset)
            raf.write(plan.payload)
            raf.fd.sync()

            if (!verify(raf, plan)) {
                raf.seek(plan.writeOffset)
                raf.write(backup)
                raf.fd.sync()
                AppLog.w(TAG, "复核失败已回滚: ${file.name}")
                return false
            }
            AppLog.i(
                TAG,
                "已补索引: ${file.name} mode=${if (plan.overwriteExisting) "重写旧表" else "新建"}" +
                    " points=${plan.pointCount} frames=${frames.size} total=$totalSamples" +
                    " audioStart=${plan.audioStart}" +
                    " first=(0,0) last=(${plan.lastSample},${plan.lastOffset})",
            )
            return true
        }
    }

    private fun readTotalSamples(streamInfo: ByteArray): Long {
        if (streamInfo.size < 18) return 0L
        return ((streamInfo[13].toLong() and 0x0F) shl 32) or
            ((streamInfo[14].toLong() and 0xFF) shl 24) or
            ((streamInfo[15].toLong() and 0xFF) shl 16) or
            ((streamInfo[16].toLong() and 0xFF) shl 8) or
            (streamInfo[17].toLong() and 0xFF)
    }

    private data class FramePos(val sample: Long, val offset: Long, val frameSamples: Int)

    /**
     * 顺序扫描音频区定位每一帧。FLAC 帧长度不写在帧头里，只能靠同步码 + CRC-8 找
     * （与 ffmpeg demuxer 的常规做法一致）。
     */
    private fun scanFrames(raf: RandomAccessFile, audioStart: Long): List<FramePos> {
        val fileLength = raf.length()
        val result = ArrayList<FramePos>(4096)
        if (audioStart >= fileLength) return result
        val buffer = ByteArray(ScanWindow + ScanOverlap)
        var base = audioStart
        while (base < fileLength) {
            raf.seek(base)
            val want = minOf(buffer.size.toLong(), fileLength - base).toInt()
            val read = raf.read(buffer, 0, want)
            if (read <= 0) break
            val isTail = base + read >= fileLength
            val limit = if (isTail) read else (read - ScanOverlap).coerceAtLeast(0)
            var i = 0
            while (i + 6 < limit) {
                if (!looksLikeSync(buffer, i)) {
                    i++
                    continue
                }
                val header = parseFrameHeader(buffer, i, read)
                if (header == null) {
                    i++
                    continue
                }
                // 固定块流：帧头里是帧号，乘以该帧真实 blockSize 得到起始样本号；
                // 可变块流：帧头里直接就是起始样本号。
                val sample = if (header.isVariableBlocking) {
                    header.number
                } else {
                    header.number * header.blockSize.toLong()
                }
                if (sample >= 0 && (result.isEmpty() || sample > result.last().sample)) {
                    result.add(FramePos(sample, base + i, header.blockSize))
                }
                i += header.headerLength
            }
            if (isTail) break
            base += limit
        }
        return result
    }

    private fun looksLikeSync(data: ByteArray, index: Int): Boolean {
        if ((data[index].toInt() and 0xFF) != 0xFF) return false
        val b1 = data[index + 1].toInt() and 0xFF
        return (b1 and 0xFE) == 0xF8
    }

    private data class FrameHeader(
        val number: Long,
        val isVariableBlocking: Boolean,
        val blockSize: Int,
        val headerLength: Int,
    )

    private fun parseFrameHeader(data: ByteArray, start: Int, end: Int): FrameHeader? {
        if (start + 6 > end) return null
        if ((data[start].toInt() and 0xFF) != 0xFF) return null
        val b1 = data[start + 1].toInt() and 0xFF
        if ((b1 and 0xFE) != 0xF8) return null
        val isVariable = (b1 and 0x01) != 0

        val b2 = data[start + 2].toInt() and 0xFF
        val blockSizeCode = (b2 shr 4) and 0x0F
        val sampleRateCode = b2 and 0x0F
        val b3 = data[start + 3].toInt() and 0xFF
        val channelAssignment = (b3 shr 4) and 0x0F
        val sampleSizeCode = (b3 shr 1) and 0x07
        // 保留位必须 0；保留的 blockSize/sampleRate 码值、非法声道/位深组合一律判为误命中
        if (b3 and 0x01 != 0) return null
        if (blockSizeCode == 0 || sampleRateCode == 0x0F) return null
        if (channelAssignment > 0x0A) return null
        if (sampleSizeCode == 0x03 || sampleSizeCode > 0x06) return null

        var p = start + 4
        val first = data[p].toInt() and 0xFF
        val extra: Int
        var value: Long
        when {
            first and 0x80 == 0 -> {
                extra = 0
                value = first.toLong()
            }
            first and 0xE0 == 0xC0 -> {
                extra = 1
                value = (first and 0x1F).toLong()
            }
            first and 0xF0 == 0xE0 -> {
                extra = 2
                value = (first and 0x0F).toLong()
            }
            first and 0xF8 == 0xF0 -> {
                extra = 3
                value = (first and 0x07).toLong()
            }
            first and 0xFC == 0xF8 -> {
                extra = 4
                value = (first and 0x03).toLong()
            }
            first and 0xFE == 0xFC -> {
                extra = 5
                value = (first and 0x01).toLong()
            }
            first == 0xFE -> {
                extra = 6
                value = 0L
            }
            else -> return null
        }
        p++
        for (k in 0 until extra) {
            if (p >= end) return null
            val c = data[p].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return null
            value = (value shl 6) or (c and 0x3F).toLong()
            p++
        }

        // 帧头里紧跟帧号的是「可选的实际块大小」（code 6/7）与「可选采样率」（code 12/13/14）。
        // 1.2.29：必须把真实 blockSize 读出来，旧版只是 p += 跳过，转而用 STREAMINFO 的
        // maxBlockSize 估算样本号，时间→位置映射整体偏移。
        val blockSize = when (blockSizeCode) {
            0x01 -> 192
            in 0x02..0x05 -> 576 shl (blockSizeCode - 2)
            0x06 -> {
                if (p >= end) return null
                val v = (data[p].toInt() and 0xFF) + 1
                p += 1
                v
            }
            0x07 -> {
                if (p + 1 >= end) return null
                val v = (((data[p].toInt() and 0xFF) shl 8) or
                    (data[p + 1].toInt() and 0xFF)) + 1
                p += 2
                v
            }
            in 0x08..0x0F -> 256 shl (blockSizeCode - 8)
            else -> return null
        }
        when (sampleRateCode) {
            0x0C -> p += 1
            0x0D, 0x0E -> p += 2
        }
        if (p + 1 > end) return null
        val expectedCrc = data[p].toInt() and 0xFF
        if (expectedCrc != crc8(data, start, p)) return null

        return FrameHeader(
            number = value,
            isVariableBlocking = isVariable,
            blockSize = blockSize,
            headerLength = p + 1 - start,
        )
    }

    private fun crc8(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc
    }

    private fun pickSeekPoints(frames: List<FramePos>, maxPoints: Int): List<FramePos> {
        if (frames.size <= maxPoints) return frames
        val step = frames.size.toDouble() / maxPoints.toDouble()
        val out = ArrayList<FramePos>(maxPoints)
        var i = 0.0
        while (i < frames.size && out.size < maxPoints) {
            out.add(frames[i.toInt()])
            i += step
        }
        if (out.last() !== frames.last()) {
            out[out.size - 1] = frames.last()
        }
        return out
    }

    /** 写 n 个 seek point；不足 n 个时用 FLAC 规范的占位点（sampleNumber 全 1）补齐。 */
    private fun writePoints(
        target: ByteArray,
        start: Int,
        points: List<FramePos>,
        n: Int,
        audioStart: Long,
    ) {
        var p = start
        for (i in 0 until n) {
            val pt = points.getOrNull(i)
            if (pt == null) {
                // placeholder seek point：sampleNumber = 0xFFFFFFFFFFFFFFFF，必须排在末尾
                for (k in 0 until 8) target[p + k] = 0xFF.toByte()
                p += 8
                writeLong(target, p, 0L)
                p += 8
                target[p++] = 0
                target[p++] = 0
            } else {
                writeLong(target, p, pt.sample)
                p += 8
                // 规范：相对第一帧起始的偏移，绝不能写文件绝对偏移。
                writeLong(target, p, (pt.offset - audioStart).coerceAtLeast(0L))
                p += 8
                target[p++] = ((pt.frameSamples ushr 8) and 0xFF).toByte()
                target[p++] = (pt.frameSamples and 0xFF).toByte()
            }
        }
    }

    /** 已有 SEEKTABLE：只生成数据区（块头保持原样）。 */
    private fun buildPoints(points: List<FramePos>, n: Int, audioStart: Long): ByteArray {
        val out = ByteArray(n * SeekPointBytes)
        writePoints(out, 0, points, n, audioStart)
        return out
    }

    /**
     * 没有 SEEKTABLE：生成「SEEKTABLE 块头 + n 个点 + 尾部 PADDING 块头 + m 字节」，
     * 总占用严格等于原 PADDING 块（4 字节头 + paddingLength）。
     *
     * `(4 + 18n) + (4 + m) = 4 + paddingLength`  ⇒  `18n + m = paddingLength - 4`
     */
    private fun buildNewBlocks(
        points: List<FramePos>,
        n: Int,
        m: Int,
        paddingWasLast: Boolean,
        audioStart: Long,
    ): ByteArray {
        val out = ByteArray(4 + n * SeekPointBytes + 4 + m)
        var p = 0
        out[p++] = TypeSeekTable.toByte()
        val len = n * SeekPointBytes
        out[p++] = ((len shr 16) and 0xFF).toByte()
        out[p++] = ((len shr 8) and 0xFF).toByte()
        out[p++] = (len and 0xFF).toByte()
        writePoints(out, p, points, n, audioStart)
        p += n * SeekPointBytes
        out[p++] = ((if (paddingWasLast) 0x80 else 0x00) or TypePadding).toByte()
        out[p++] = ((m shr 16) and 0xFF).toByte()
        out[p++] = ((m shr 8) and 0xFF).toByte()
        out[p++] = (m and 0xFF).toByte()
        return out
    }

    private fun writeLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            target[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }

    private fun readLong(raf: RandomAccessFile): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or raf.readUnsignedByte().toLong()
        return v
    }

    /**
     * 复核：SEEKTABLE 内容是否**语义正确**，且元数据链尾紧跟音频帧。
     *
     * 1.2.29：旧版从 PADDING 位置推算音频起点，PADDING 不是最后一块时算错，
     * 于是一部分文件补索引被误判失败并回滚（日志里「转码产物不可 seek」）。
     * 现在统一使用 [Plan.audioStart]（遍历元数据链得到的真实第一帧位置）。
     */
    private fun verify(raf: RandomAccessFile, plan: Plan): Boolean {
        val fileLength = raf.length()
        if (plan.overwriteExisting) {
            raf.seek(plan.writeOffset - 4)
            val header = raf.readUnsignedByte()
            if (header and 0x7F != TypeSeekTable) return false
            val length = (raf.readUnsignedByte() shl 16) or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()
            if (length != plan.pointCount * SeekPointBytes) return false
        } else {
            raf.seek(plan.writeOffset)
            val header = raf.readUnsignedByte()
            if (header and 0x7F != TypeSeekTable || header and 0x80 != 0) return false
            val length = (raf.readUnsignedByte() shl 16) or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()
            if (length != plan.pointCount * SeekPointBytes) return false
            val tailHeaderOffset = plan.writeOffset + 4L + length
            if (tailHeaderOffset + 4 > fileLength) return false
            raf.seek(tailHeaderOffset)
            if (raf.readUnsignedByte() and 0x7F != TypePadding) return false
        }

        // 逐点校验：第一个点必须是 (0, 0)，所有点 sample/offset 非递减，
        // 且最后一个 offset 不能越过音频区末尾。
        //
        // 1.2.30 确定性 bug：新建索引时 plan.writeOffset 指向**PADDING 块头**，
        // 我们刚把那里改写成 SEEKTABLE 块头，真正的数据区在 +4 之后。
        // 旧代码从 writeOffset 逐点读，等于把 4 字节块头当成第一个 seek point，
        // i==0 的 (0,0) 校验必然失败 → 回滚 → ensureSeekTable 永远返回 false
        // → 缓存每次都被判「不可修复、删除重建」→ 同一首歌反复重转、进度归零。
        // 覆盖旧表时 writeOffset 本来就是数据区起点，不能再 +4。
        val pointsOffset =
            if (plan.overwriteExisting) plan.writeOffset else plan.writeOffset + 4L
        raf.seek(pointsOffset)
        var prevSample = -1L
        var prevOffset = -1L
        val maxOffset = fileLength - plan.audioStart
        for (i in 0 until plan.pointCount) {
            val sample = readLong(raf)
            val offset = readLong(raf)
            raf.readUnsignedByte(); raf.readUnsignedByte() // frameSamples
            if (sample == -1L && offset == 0L) continue // placeholder（全 1 = -1）
            if (i == 0 && (sample != 0L || offset != 0L)) return false
            if (sample < prevSample || offset < prevOffset) return false
            if (offset >= maxOffset) return false
            prevSample = sample
            prevOffset = offset
        }

        if (plan.audioStart + 2 > fileLength) return false
        raf.seek(plan.audioStart)
        val b0 = raf.readUnsignedByte()
        val b1 = raf.readUnsignedByte()
        return b0 == 0xFF && (b1 and 0xFE) == 0xF8
    }

    /**
     * 排障用：把「实际读取的缓存 FLAC」的索引状态打进失败日志。
     *
     * 1.2.28 及以前的失败日志里 `head=` 读的是**原始 MediaItem Uri**（DFF 的 FRM8 头），
     * 对判断转码产物好坏毫无用处——用户明确要求过「日志找不到描述的现象就改日志」。
     * 这里直接报出第一帧位置、SEEKTABLE 点数、首尾两个点的 (sample, offset)、
     * 链尾是否是帧同步码。若 `first=(0, 8330, ...)` 而不是 `(0, 0, ...)`，
     * 一眼就能看出是旧版的绝对偏移 bug。
     */
    fun diagnose(file: File): String {
        if (!file.isFile) return "cache=missing"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val layout = readLayout(raf)
                if (layout == null) {
                    "layout=bad magic=${String(ByteArray(4).also { raf.seek(0); raf.readFully(it) }, Charsets.US_ASCII)}"
                } else {
                    val n = if (layout.seekTableOffset >= 0) {
                        layout.seekTableLength / SeekPointBytes
                    } else {
                        0
                    }
                    var firstPoint = "none"
                    var lastPoint = "none"
                    if (n > 0) {
                        raf.seek(layout.seekTableOffset + 4)
                        val s0 = readLong(raf)
                        val o0 = readLong(raf)
                        val f0 = (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte()
                        firstPoint = "($s0,$o0,$f0)"
                        raf.seek(layout.seekTableOffset + 4L + (n - 1) * SeekPointBytes)
                        val s1 = readLong(raf)
                        val o1 = readLong(raf)
                        lastPoint = "($s1,$o1)"
                    }
                    raf.seek(layout.audioStart)
                    val b0 = raf.readUnsignedByte()
                    val b1 = raf.readUnsignedByte()
                    "audioStart=${layout.audioStart} points=$n first=$firstPoint" +
                        " last=$lastPoint" +
                        " tailSync=${b0 == 0xFF && (b1 and 0xFE) == 0xF8}" +
                        " valid=${validate(file)}"
                }
            }
        }.getOrElse { "diagnose failed: ${it.message}" }
    }

    /**
     * 只做校验、不改文件：缓存命中时用它可以发现历史版本写坏的产物（自愈重建）。
     *
     * 1.2.29：除结构外还校验 SEEKTABLE 语义——1.2.25~1.2.28 写的是文件绝对偏移，
     * 第一个点的 offset 约等于元数据区长度（几 KB）而不是 0。命中即判失效，
     * `ensureSeekTable` 会就地覆盖重写，用户无需清缓存也无需重新转码。
     */
    fun isValidSeekableFlac(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return runCatching { validate(file) }.getOrDefault(false)
    }

    /**
     * 元数据链是否被写坏：某个块带了 last 位，但它后面紧跟的字节不是音频帧同步码。
     *
     * 用来区分两种「不可 seek」：链坏（必须重转）vs 只是索引不合法（就地重写即可）。
     */
    fun isChainBroken(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                if (raf.length() < 8L) return true
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "fLaC") return false
                var pos = 4L
                var sawLast = false
                while (pos + 4 <= raf.length()) {
                    raf.seek(pos)
                    val header = raf.readUnsignedByte()
                    val isLast = header and 0x80 != 0
                    val length = (raf.readUnsignedByte() shl 16) or
                        (raf.readUnsignedByte() shl 8) or
                        raf.readUnsignedByte()
                    pos += 4 + length
                    if (isLast) {
                        sawLast = true
                        break
                    }
                }
                if (!sawLast || pos + 2 > raf.length()) return true
                raf.seek(pos)
                val b0 = raf.readUnsignedByte()
                val b1 = raf.readUnsignedByte()
                !(b0 == 0xFF && (b1 and 0xFE) == 0xF8)
            }
        }.getOrDefault(false)
    }

    private fun validate(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val layout = readLayout(raf) ?: return false
            if (layout.seekTableOffset < 0) return false
            val n = layout.seekTableLength / SeekPointBytes
            if (n < 8) return false
            val fileLength = raf.length()
            val maxOffset = fileLength - layout.audioStart
            if (maxOffset <= 0) return false

            raf.seek(layout.seekTableOffset + 4)
            var prevSample = -1L
            var prevOffset = -1L
            for (i in 0 until n) {
                val sample = readLong(raf)
                val offset = readLong(raf)
                raf.readUnsignedByte(); raf.readUnsignedByte()
                if (sample == -1L && offset == 0L) continue // placeholder
                // 第一个点必须是 (0, 0)：旧版写绝对偏移时这里会是 ~8KB，直接判失效。
                if (i == 0 && (sample != 0L || offset != 0L)) return false
                if (sample < prevSample || offset < prevOffset) return false
                if (offset >= maxOffset) return false
                prevSample = sample
                prevOffset = offset
            }
            if (layout.audioStart + 2 > fileLength) return false
            raf.seek(layout.audioStart)
            val b0 = raf.readUnsignedByte()
            val b1 = raf.readUnsignedByte()
            return b0 == 0xFF && (b1 and 0xFE) == 0xF8
        }
    }
}
