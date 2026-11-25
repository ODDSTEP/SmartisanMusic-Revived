package com.smartisan.music.ui.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.SoundPool
import com.smartisan.music.R
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.playback.UsbExclusiveSinkRef
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.random.Random

private const val VinylPopcornMaxStreams = 3
private const val VinylPopcornPriority = 1
private const val VinylPopcornActiveStreamLimit = 8
// [v21·爆豆音真实模拟] 撤销 v19 的 0.35~0.55 一刀切放大（用户 2026-11-23 拍板：
// "持续沙沙声属于底噪不能做，pop 要弱化"）。真实偶发 pop 是极短瞬态，但真实峰值可盖过音乐；
// 用户要求弱化成"细微、不盖过正曲"，故回到 0.08~0.15 弱化档，段间随机音量保留强弱分布。
// 不做持续 crackle/底噪（本控制器本就是随机单发，符合）。SoundPool volume 是相对系统媒体音量的
// 乘数（联网核实），0.08~0.15 即 8%~15% 系统音量，属轻微点缀。
private const val VinylPopcornMinVolume = 0.08f
private const val VinylPopcornVolumeRange = 0.07f

/** [v21] 开关打开时的即时试听反馈音量：独立于播放模拟音量，取适中可闻值，保证 #114「开开关听到一次」验收。 */
private const val VinylPopcornPreviewVolume = 0.40f
private const val VinylPopcornMinRate = 0.92f
private const val VinylPopcornRateRange = 0.16f

private const val PopcornFallbackSampleRate = 16_000
private const val PopcornDecodeTimeoutUs = 10_000L
private const val PopcornTag = "Popcorn"

private val VinylPopcornSamples = intArrayOf(
    R.raw.y_1_01,
    R.raw.y_2_01,
    R.raw.y_3_01,
    R.raw.y_4_01,
    R.raw.y_5_01,
    R.raw.y_6_01,
    R.raw.y_7_01,
    R.raw.y_8_01,
    R.raw.y_9_01,
    R.raw.y_10_01,
    R.raw.y_11_01,
    R.raw.y_12_01,
    R.raw.y_13_01,
    R.raw.y_14_01,
    R.raw.y_15_01,
    R.raw.y_16_01,
    R.raw.y_17_01,
    R.raw.y_18_01,
    R.raw.y_19_01,
    R.raw.y_20_01,
)

private data class DecodedPop(
    val pcm: ShortArray,
    val sampleRate: Int,
)

/**
 * 唱盘爆豆音（设置项 R.string.popcorn_sound）。
 *
 * 功能与原始 0.3.0 完全一致：播放中按随机间隔、随机音量/速率播放 res/raw 下 20 个
 * `y_*.ogg` 采样，模拟真实黑胶唱盘上灰尘掉落造成的杂音质感。
 *
 * 1.3.3 新增 USB 真独占支持：独占时 USB 声卡已被从 AudioFlinger 摘掉，[SoundPool] 的
 * `USAGE_MEDIA` 默认路由找不到这块设备，于是「设置里开了唱盘爆豆音却一点声音都没有」。
 * 这里把同一批原始 ogg 采样解成 PCM，交给 [UsbExclusiveSinkRef] 混进正在播放的音乐帧，
 * 与音乐走同一条 UAC 会话；共享输出仍走原来的 SoundPool，行为逐字节不变。
 */
internal class VinylPopcornSoundController(
    context: Context,
) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(VinylPopcornMaxStreams)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()
    private val random = Random.Default
    private val loadedSampleIds = linkedSetOf<Int>()
    private val activeStreamIds = ArrayDeque<Int>()
    private val lock = Any()

    /** 后台解出的原始采样，供独占通道叠加；解码期间列表为空，先走 SoundPool 兜底。 */
    private val decodedSamples = CopyOnWriteArrayList<DecodedPop>()
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "popcorn-decode").apply { isDaemon = true }
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                synchronized(lock) {
                    loadedSampleIds += sampleId
                }
            }
        }
        VinylPopcornSamples.forEach { resId ->
            soundPool.load(context, resId, VinylPopcornPriority)
        }
        val appCtx = context.applicationContext
        decodeExecutor.execute {
            VinylPopcornSamples.forEach { resId ->
                decodeOggToStereoPcm(appCtx, resId)?.let { sample ->
                    decodedSamples.add(sample)
                }
            }
            if (decodedSamples.isEmpty()) {
                // 极少数 ROM 的 MediaExtractor 解不了 res/raw 的 ogg：用合成的爆豆脉冲兜底，
                // 保证独占下打开开关一定能听到声音，而不是设置项形同虚设。
                decodedSamples.add(
                    DecodedPop(synthesizePopcornPcm(), PopcornFallbackSampleRate),
                )
                AppLog.w(PopcornTag, "爆豆音原始采样解码全部失败，改用合成脉冲（独占通道仍可出声）")
            } else {
                AppLog.i(
                    PopcornTag,
                    "爆豆音采样解码完成: ${decodedSamples.size}/${VinylPopcornSamples.size} 段，可用于 USB 独占叠加",
                )
            }
        }
    }

    fun playRandomPop() {
        // [#84·爆豆音开关无效根治] 底层兜底闸：开关关闭时 enqueuePopcorn() 会返回 false，
        // 旧逻辑随后 fall through 到 SoundPool，导致"关了开关却从扬声器漏出爆豆音"。
        // 这里以开关真值为准直接返回，独占/共享两条路径都不出声，彻底消除竞态漏音。
        // [v18·#114] 被闸拦下也打点，便于真机确认"开/关"到底有没有作用到本控制器。
        if (!UsbExclusiveSinkRef.popcornEnabled) {
            AppLog.i(PopcornTag, "op=爆豆音触发 结果=被开关闸拦下 popcornEnabled=false（不出声）")
            return
        }
        val volume = VinylPopcornMinVolume + (random.nextFloat() * VinylPopcornVolumeRange)
        val rate = VinylPopcornMinRate + (random.nextFloat() * VinylPopcornRateRange)

        // 1.3.3：USB 独占下先尝试混进 UAC。成功就不再启动 SoundPool——
        // 否则声音会从手机扬声器漏出来，且独占设备依旧听不到。
        val owner = UsbExclusiveSinkRef.current
        if (owner != null && decodedSamples.isNotEmpty()) {
            val sampleIdx = random.nextInt(decodedSamples.size)
            val sample = decodedSamples[sampleIdx]
            if (owner.enqueuePopcorn(sample.pcm, sample.sampleRate, volume)) {
                AppLog.i(
                    PopcornTag,
                    "op=爆豆音触发 路径=USB独占叠加 sampleIdx=$sampleIdx rate=${"%.3f".format(rate)} volume=${"%.3f".format(volume)}",
                )
                return
            }
        }

        // 共享输出（或独占会话不可用）：原始 0.3.0 行为，一点不动。
        val sampleId = synchronized(lock) {
            loadedSampleIds.randomOrNull(random)
        } ?: run {
            AppLog.w(PopcornTag, "op=爆豆音触发 路径=SoundPool 失败：无已加载采样")
            return
        }
        val streamId = soundPool.play(
            sampleId,
            volume,
            volume,
            VinylPopcornPriority,
            0,
            rate,
        )
        if (streamId == 0) {
            AppLog.w(PopcornTag, "op=爆豆音触发 路径=SoundPool 失败：play 返回 0")
            return
        }
        AppLog.i(
            PopcornTag,
            "op=爆豆音触发 路径=SoundPool sampleId=$sampleId streamId=$streamId rate=${"%.3f".format(rate)} volume=${"%.3f".format(volume)}",
        )
        synchronized(lock) {
            activeStreamIds.addLast(streamId)
            while (activeStreamIds.size > VinylPopcornActiveStreamLimit) {
                soundPool.stop(activeStreamIds.removeFirst())
            }
        }
    }

    fun stop() {
        // 独占叠加是「一次性登记 + 逐帧消耗」，暂停/关开关时必须立刻清掉，
        // 否则恢复播放后会把上一段音效的尾巴带出来。
        UsbExclusiveSinkRef.current?.clearPopcorn()
        var stopped = 0
        synchronized(lock) {
            while (activeStreamIds.isNotEmpty()) {
                soundPool.stop(activeStreamIds.removeFirst())
                stopped++
            }
        }
        AppLog.i(PopcornTag, "op=爆豆音停止 清理SoundPool流=$stopped clearUsbPopcorn=true")
    }

    fun release() {
        stop()
        decodeExecutor.shutdownNow()
        decodedSamples.clear()
        soundPool.release()
    }
}

/**
 * [v18·#114] 开关打开时的即时试听：独立于播放页 controller，在设置页也能立刻听到一次，
 * 让用户确认开关真的生效。用一次性 SoundPool 播一个原始爆豆采样，播完自动释放。
 * 注意：这里只做"试听确认"，不走 USB 独占混音（设置页通常非独占，共享输出即可听到）。
 */
internal fun playPopcornPreview(context: Context) {
    val tag = PopcornTag
    val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .build()
    val resId = VinylPopcornSamples.firstOrNull() ?: return
    pool.setOnLoadCompleteListener { sp, sampleId, status ->
        if (status == 0) {
            // [v21] 试听=开关即时反馈（适中可闻），播放=真实模拟弱化档；二者用途不同，音量可不同。
            val previewVolume = VinylPopcornPreviewVolume
            sp.play(sampleId, previewVolume, previewVolume, 1, 0, 1f)
            AppLog.i(tag, "op=爆豆音开关试听 路径=SoundPool预览 sampleId=$sampleId volume=${"%.2f".format(previewVolume)}（开关打开即时反馈）")
        } else {
            AppLog.w(tag, "op=爆豆音开关试听 失败：采样加载 status=$status")
        }
        // 给播放留 3 秒，再释放。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ sp.release() }, 3000L)
    }
    pool.load(context, resId, 1)
}

/**
 * 用 MediaExtractor + MediaCodec 把一段 res/raw 的 ogg(Vorbis) 解成 16bit 立体声交错 PCM。
 * 与 ScratchSoundController 的 MediaCodec 解码路径同源；失败返回 null，由调用方决定是否兜底。
 */
private fun decodeOggToStereoPcm(context: Context, resId: Int): DecodedPop? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var afd: AssetFileDescriptor? = null
    return try {
        val fd = context.resources.openRawResourceFd(resId) ?: return null
        afd = fd
        extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }
        if (trackIndex < 0) return null
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
        codec = decoder
        val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            PopcornFallbackSampleRate
        }
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            2
        }
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val pcmBytes = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false

        while (!outputEnded) {
            if (!inputEnded) {
                val inIdx = decoder.dequeueInputBuffer(PopcornDecodeTimeoutUs)
                if (inIdx >= 0) {
                    val inBuf = decoder.getInputBuffer(inIdx)
                    if (inBuf == null) {
                        inputEnded = true
                    } else {
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, size, extractor.sampleTime, extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }
            }
            when (val outIdx = decoder.dequeueOutputBuffer(info, PopcornDecodeTimeoutUs)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = decoder.outputFormat
                    channels = if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    } else {
                        channels
                    }
                    pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> {
                    if (outIdx >= 0) {
                        if (info.size > 0 && pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                            val buf = decoder.getOutputBuffer(outIdx)?.duplicate()?.apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                                order(ByteOrder.LITTLE_ENDIAN)
                            }
                            if (buf != null) {
                                val shortView = buf.asShortBuffer()
                                val shorts = ShortArray(shortView.remaining())
                                shortView.get(shorts)
                                val ch = channels.coerceAtLeast(1)
                                val frameCount = shorts.size / ch
                                for (f in 0 until frameCount) {
                                    val l = shorts[f * ch]
                                    val r = if (ch > 1) shorts[f * ch + 1] else l
                                    pcmBytes.write(l.toInt() and 0xFF)
                                    pcmBytes.write((l.toInt() shr 8) and 0xFF)
                                    pcmBytes.write(r.toInt() and 0xFF)
                                    pcmBytes.write((r.toInt() shr 8) and 0xFF)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEnded = true
                        }
                    }
                }
            }
        }
        val bytes = pcmBytes.toByteArray()
        if (bytes.size < 4) return null
        val pcm = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        DecodedPop(pcm, srcRate)
    } catch (error: Throwable) {
        AppLog.w(
            PopcornTag,
            "爆豆音采样解码失败 res=$resId: ${error.javaClass.simpleName} ${error.message}",
        )
        null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
        runCatching { afd?.close() }
    }
}

/**
 * [1.4.24·R32] 兜底用的合成爆豆音：250ms 单次脉冲（不再是 5 秒长段）。
 *
 * 只在原始采样全部解码失败时使用。长度与裁好的真实采样一致，避免"兜底时密度突然变高"。
 */
private fun synthesizePopcornPcm(): ShortArray {
    val rate = PopcornFallbackSampleRate
    val frames = rate / 4 // 250ms
    val out = ShortArray(frames * 2)
    val rnd = Random(20261106L)
    val start = rate * 40 / 1000 // 40ms 留白，贴合真实采样「峰值前 40ms」的裁剪口径
    val length = (rate * 0.18).toInt()
    val amp = 0.72f * 32767f
    for (i in 0 until length) {
        val index = start + i
        if (index >= frames) break
        val decay = exp(-i.toDouble() / (length / 3.0)).toFloat()
        val noise = (rnd.nextFloat() * 2f - 1f) * decay * amp
        val sample = noise.toInt().coerceIn(-32768, 32767).toShort()
        out[index * 2] = sample
        out[index * 2 + 1] = sample
    }
    return out
}
