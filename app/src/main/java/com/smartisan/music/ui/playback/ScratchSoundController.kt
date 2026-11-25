package com.smartisan.music.ui.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.smartisan.music.playback.ApeTranscoder
import com.smartisan.music.playback.UsbExclusiveSinkRef
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

private const val ScratchPlaybackCycleMs = 1_800f
private const val ScratchMinMotionDegrees = 0.05f
private const val ScratchDecodeTimeoutUs = 10_000L
private const val ScratchDecodeWindowBeforeMs = 12_000L
private const val ScratchDecodeWindowAfterMs = 18_000L
private const val ScratchDecodeRefreshDistanceMs = 4_000L
private const val ScratchMinDeltaTimeMs = 8L
private const val ScratchMaxDeltaTimeMs = 72L
private const val ScratchOutputFrames = 384
private const val ScratchOutputChannels = 2
// 1.3.2：原 1.08f 会把样本硬削到 Short 边界，长按搓碟时叠加成刺耳破音
private const val ScratchOutputGain = 0.95f
private const val ScratchMaxPlaybackRatePermille = 6_000
// 1.3.2：手指按住不动时 Compose 不再产生 pointer event，靠这个超时把刮擦速率归零
private const val ScratchMotionIdleTimeoutMs = 80L

internal class ScratchSoundController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val playbackLock = ReentrantLock()
    private val playbackWakeCondition = playbackLock.newCondition()
    private val playbackThread = Thread(::playbackLoop, "ScratchAudioTrack").apply {
        isDaemon = true
        start()
    }

    @Volatile
    private var released = false

    @Volatile
    private var sourceGeneration = 0

    @Volatile
    private var currentSourceKey: String? = null

    @Volatile
    private var currentSourceUri: Uri? = null

    @Volatile
    private var loadingRequest: ScratchDecodeRequest? = null

    @Volatile
    private var pendingDecodePositionMs: Long? = null

    @Volatile
    private var scratchBuffer: ScratchBuffer? = null

    @Volatile
    private var scratchActive = false

    @Volatile
    private var scratchPositionMs = 0L

    @Volatile
    private var requestedDirection = 0

    @Volatile
    private var requestedPlaybackRatePermille = 0f

    @Volatile
    private var cursorResetGeneration = 0L

    @Volatile
    private var lastMotionRealtimeMs = 0L

    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0
    private var playbackCursor = 0.0
    private var appliedCursorResetGeneration = -1L
    private var appliedBufferIdentity: ScratchBufferIdentity? = null
    private val stereoWriteBuffer = ShortArray(ScratchOutputFrames * ScratchOutputChannels)

    fun prepareSource(
        sourceUri: Uri?,
        positionMs: Long,
    ) {
        val sourceKey = sourceUri?.toString()
        if (sourceKey.isNullOrEmpty()) {
            sourceGeneration += 1
            currentSourceKey = null
            currentSourceUri = null
            loadingRequest = null
            pendingDecodePositionMs = null
            scratchBuffer = null
            wakePlaybackThread()
            return
        }
        currentSourceUri = sourceUri
        if (currentSourceKey != sourceKey) {
            currentSourceKey = sourceKey
            scratchBuffer = null
            loadingRequest = null
            pendingDecodePositionMs = null
        }
        requestBufferForPosition(sourceUri, sourceKey, positionMs)
    }

    private fun requestBufferForPosition(
        sourceUri: Uri,
        sourceKey: String,
        positionMs: Long,
    ) {
        if (released) {
            return
        }
        val anchorPositionMs = positionMs.coerceAtLeast(0L)
        scratchBuffer?.takeIf { buffer ->
            buffer.covers(sourceKey, anchorPositionMs) &&
                !buffer.needsRefresh(anchorPositionMs)
        }?.let {
            return
        }
        loadingRequest?.takeIf { request ->
            request.sourceKey == sourceKey
        }?.let {
            pendingDecodePositionMs = anchorPositionMs
            return
        }
        startDecodeRequest(sourceUri, sourceKey, anchorPositionMs)
    }

    private fun startDecodeRequest(
        sourceUri: Uri,
        sourceKey: String,
        anchorPositionMs: Long,
    ) {
        val request = ScratchDecodeRequest(
            sourceUri = sourceUri,
            sourceKey = sourceKey,
            windowStartMs = (anchorPositionMs - ScratchDecodeWindowBeforeMs).coerceAtLeast(0L),
            windowEndMs = anchorPositionMs + ScratchDecodeWindowAfterMs,
        )
        val generation = sourceGeneration + 1
        sourceGeneration = generation
        loadingRequest = request
        decodeExecutor.execute {
            val decodedBuffer = decodeScratchBuffer(appContext, request)
            if (released || generation != sourceGeneration) {
                return@execute
            }
            if (decodedBuffer != null) {
                scratchBuffer = decodedBuffer
            }
            loadingRequest = null
            val pendingPositionMs = pendingDecodePositionMs
            pendingDecodePositionMs = null
            if (
                pendingPositionMs != null &&
                currentSourceKey == sourceKey &&
                currentSourceUri == sourceUri &&
                scratchBuffer?.takeIf { buffer ->
                    buffer.covers(sourceKey, pendingPositionMs) &&
                        !buffer.needsRefresh(pendingPositionMs)
                } == null
            ) {
                startDecodeRequest(sourceUri, sourceKey, pendingPositionMs.coerceAtLeast(0L))
            }
            wakePlaybackThread()
        }
    }

    fun onScratchStart(
        sourceUri: Uri?,
        positionMs: Long,
    ) {
        prepareSource(sourceUri, positionMs)
        scratchPositionMs = positionMs
        scratchActive = true
        requestedDirection = 1
        requestedPlaybackRatePermille = 0f
        lastMotionRealtimeMs = 0L
        cursorResetGeneration += 1
        wakePlaybackThread()
    }

    fun onScratchMotion(
        positionMs: Long,
        deltaAngleDegrees: Float,
    ) {
        val magnitude = abs(deltaAngleDegrees)
        if (released) {
            return
        }
        scratchPositionMs = positionMs
        if (magnitude < ScratchMinMotionDegrees) {
            requestedDirection = 0
            requestedPlaybackRatePermille = 0f
            cursorResetGeneration += 1
            wakePlaybackThread()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val deltaTimeMs = when {
            lastMotionRealtimeMs == 0L -> 16L
            else -> (now - lastMotionRealtimeMs).coerceIn(
                ScratchMinDeltaTimeMs,
                ScratchMaxDeltaTimeMs,
            )
        }
        lastMotionRealtimeMs = now

        val direction = if (deltaAngleDegrees >= 0f) 1 else -1
        requestedPlaybackRatePermille = scratchPlaybackRatePermille(magnitude, deltaTimeMs).toFloat()
        requestedDirection = direction
        currentSourceUri?.let { sourceUri ->
            currentSourceKey?.let { sourceKey ->
                requestBufferForPosition(sourceUri, sourceKey, positionMs)
            }
        }
        scratchActive = true
        wakePlaybackThread()
    }

    fun stop() {
        scratchActive = false
        requestedDirection = 0
        requestedPlaybackRatePermille = 0f
        lastMotionRealtimeMs = 0L
        wakePlaybackThread()
    }

    fun release() {
        stop()
        released = true
        sourceGeneration += 1
        decodeExecutor.shutdownNow()
        wakePlaybackThread()
        playbackThread.join(500)
        // 播放线程可能是在写入过程里退出的，兜底把临时接管的 UAC 流交还
        UsbExclusiveSinkRef.current?.endScratchOutputStream()
    }

    private fun wakePlaybackThread() {
        playbackLock.withLock {
            playbackWakeCondition.signalAll()
        }
    }

    private fun playbackLoop() {
        try {
            while (!released) {
                // 1.3.2：手指按住不动时不会再有 onScratchMotion 回调，若不主动归零，
                // requestedPlaybackRatePermille / direction 会一直保留最后一次摩擦值，
                // cursor 越界后反复从同一相位重播同一小段，产生周期性刺耳「恶声」。
                // lastMotionRealtimeMs 此前只写不读，静止保护形同虚设。
                if (scratchActive) {
                    val last = lastMotionRealtimeMs
                    if (last == 0L ||
                        SystemClock.elapsedRealtime() - last > ScratchMotionIdleTimeoutMs
                    ) {
                        requestedDirection = 0
                        requestedPlaybackRatePermille = 0f
                    }
                }
                val buffer = scratchBuffer
                val sourceKey = currentSourceKey
                val shouldPlay = scratchActive &&
                    requestedDirection != 0 &&
                    requestedPlaybackRatePermille > 0f &&
                    buffer != null &&
                    sourceKey != null &&
                    buffer.covers(sourceKey, scratchPositionMs) &&
                    !released

                if (!shouldPlay || buffer.stereoSamples.isEmpty()) {
                    pauseAndFlushTrack()
                    playbackLock.withLock {
                        if (!released) {
                            playbackWakeCondition.await(24L, TimeUnit.MILLISECONDS)
                        }
                    }
                    continue
                }

                val localCursorResetGeneration = cursorResetGeneration
                val bufferIdentity = buffer.identity
                if (shouldResetScratchCursor(
                        appliedBufferIdentity = appliedBufferIdentity,
                        currentBufferIdentity = bufferIdentity,
                        appliedCursorResetGeneration = appliedCursorResetGeneration,
                        currentCursorResetGeneration = localCursorResetGeneration,
                    ) ||
                    !buffer.containsSample(playbackCursor)
                ) {
                    playbackCursor = buffer.positionMsToSample(scratchPositionMs)
                    appliedBufferIdentity = bufferIdentity
                    appliedCursorResetGeneration = localCursorResetGeneration
                }

                val frameStep = (requestedPlaybackRatePermille / 1_000f) *
                    requestedDirection.toDouble()
                if (!buffer.containsSampleSpan(playbackCursor, frameStep, ScratchOutputFrames)) {
                    playbackCursor = buffer.positionMsToSample(scratchPositionMs)
                }
                if (!buffer.containsSampleSpan(playbackCursor, frameStep, ScratchOutputFrames)) {
                    currentSourceUri?.let { sourceUri ->
                        requestBufferForPosition(sourceUri, sourceKey, scratchPositionMs)
                    }
                    pauseAndFlushTrack()
                    playbackLock.withLock {
                        if (!released) {
                            playbackWakeCondition.await(24L, TimeUnit.MILLISECONDS)
                        }
                    }
                    continue
                }

                emitScratchFrames(buffer, frameStep)
            }
        } finally {
            releaseAudioTrack()
        }
    }

    /**
     * 输出一段刮擦音。
     *
     * USB 独占开启时打碟声必须走 USB：[ensureAudioTrack] 建的旁路 AudioTrack 是系统
     * AudioTrack，而独占会话已经把这块 USB 声卡从 AudioFlinger 里摘掉，它的默认路由
     * 找不到该设备——继续走 AudioTrack 就退化成"从手机扬声器出声"（1.2.13 修复）。
     * 所以这里优先交给 [UsbExclusiveSinkRef] 的同一条 UAC 会话；
     * 只有在没有活跃会话（或采样率不匹配）时才回退到 AudioTrack。
     */
    private fun emitScratchFrames(
        buffer: ScratchBuffer,
        frameStep: Double,
    ) {
        fillStereoBuffer(buffer, frameStep)
        val owner = UsbExclusiveSinkRef.current
        if (owner != null &&
            owner.writeScratchFrames(
                buffer.sampleRate,
                stereoWriteBuffer,
                ScratchOutputFrames,
            )
        ) {
            // 已走 UAC：系统侧音轨不再需要，避免留一条占着资源的静音 AudioTrack
            releaseAudioTrack()
            return
        }
        val track = ensureAudioTrack(buffer.sampleRate)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        track.write(
            stereoWriteBuffer,
            0,
            stereoWriteBuffer.size,
            AudioTrack.WRITE_BLOCKING,
        )
    }

    private fun fillStereoBuffer(
        buffer: ScratchBuffer,
        frameStep: Double,
    ) {
        val lastFrameIndex = buffer.frameCount - 1
        if (lastFrameIndex < 0) {
            stereoWriteBuffer.fill(0)
            return
        }
        if (frameStep == 0.0) {
            stereoWriteBuffer.fill(0)
            return
        }
        var outputIndex = 0
        repeat(ScratchOutputFrames) {
            val left = amplifySample(
                interpolateStereoSample(buffer.stereoSamples, playbackCursor, channel = 0),
            )
            val right = amplifySample(
                interpolateStereoSample(buffer.stereoSamples, playbackCursor, channel = 1),
            )
            stereoWriteBuffer[outputIndex++] = left
            stereoWriteBuffer[outputIndex++] = right
            playbackCursor += frameStep
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack {
        audioTrack?.takeIf { audioTrackSampleRate == sampleRate }?.let { return it }
        releaseAudioTrack()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val safeBufferSize = maxOf(
            minBufferSize.coerceAtLeast(0),
            stereoWriteBuffer.size * Short.SIZE_BYTES * 4,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(safeBufferSize)
            .build()
            .also {
                // 注意：这里**不要**把音轨钉到内置扬声器（旧行为）。
                // USB 独占时的刮擦音由 [emitScratchFrames] 直接写进同一条 UAC 会话；
                // 只有在没有活跃 UAC 会话（或采样率不匹配）的兜底场景才会走到这里，
                // 此时保持系统默认路由即可（独占已释放 / 本来就是普通输出）。
                audioTrack = it
                audioTrackSampleRate = sampleRate
            }
    }

    private fun pauseAndFlushTrack() {
        // 走 UAC 时系统侧音轨并不存在，这里同时把 UAC 流的临时接管交还
        UsbExclusiveSinkRef.current?.endScratchOutputStream()
        val track = audioTrack ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.release()
        audioTrack = null
        audioTrackSampleRate = 0
    }
}

internal fun scratchPlaybackRatePermille(
    deltaAngleDegrees: Float,
    deltaTimeMs: Long,
): Int {
    val scaledRate = (
        ScratchPlaybackCycleMs * deltaAngleDegrees /
            (360f * deltaTimeMs.coerceIn(ScratchMinDeltaTimeMs, ScratchMaxDeltaTimeMs).toFloat())
    ) * 1_000f
    return scaledRate
        .roundToInt()
        .coerceIn(0, ScratchMaxPlaybackRatePermille)
}

private fun amplifySample(sample: Int): Short {
    return (sample * ScratchOutputGain)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
}

private fun interpolateStereoSample(
    samples: ShortArray,
    sampleIndex: Double,
    channel: Int,
): Int {
    if (samples.isEmpty()) {
        return 0
    }
    val frameCount = samples.size / ScratchOutputChannels
    val lastFrameIndex = frameCount - 1
    val clampedIndex = sampleIndex.coerceIn(0.0, lastFrameIndex.toDouble())
    val leftFrameIndex = clampedIndex.toInt()
    val rightFrameIndex = (leftFrameIndex + 1).coerceAtMost(lastFrameIndex)
    val fraction = clampedIndex - leftFrameIndex
    val sampleChannel = channel.coerceIn(0, ScratchOutputChannels - 1)
    val left = samples[(leftFrameIndex * ScratchOutputChannels) + sampleChannel].toDouble()
    val right = samples[(rightFrameIndex * ScratchOutputChannels) + sampleChannel].toDouble()
    return (left + ((right - left) * fraction)).roundToInt()
}

/**
 * AudioTrack 只接受 [4000, 192000] 范围内的采样率。部分机型会把 DSD 源解出成
 * 352800 Hz 的 PCM，直接喂给 [android.media.AudioTrack.Builder.setSampleRate]
 * 会抛 `IllegalArgumentException: Invalid sample rate` 导致打碟线程崩溃闪退。
 * 这里把超范围的采样率收敛到不高于它的最高标准采样率，再由 [resampleStereoShorts]
 * 把解码出的 PCM 重采样到该速率，保证音高与时长正确、且 [android.media.AudioTrack]
 * 一定能建出来。
 */
private val ScratchSafeSampleRatesDesc = intArrayOf(
    192000, 176400, 96000, 88200, 48000, 44100, 32000, 24000, 16000, 12000, 8000,
)

private fun sanitizeScratchSampleRate(rate: Int): Int {
    if (rate in 4000..192000) return rate
    for (supported in ScratchSafeSampleRatesDesc) {
        if (supported <= rate) return supported
    }
    return 48000
}

/**
 * 线性插值重采样器。把超范围解码采样率（如 DSD 解出的 352800 Hz）降到
 * [android.media.AudioTrack] 能播放的速率。输出帧数按速率比缩放，
 * 因此播放窗口的时长/音高保持不变。
 */
private fun resampleStereoShorts(
    samples: ShortArray,
    srcRate: Int,
    dstRate: Int,
): ShortArray {
    if (dstRate <= 0 || srcRate <= 0 || srcRate == dstRate) return samples
    val frameCount = samples.size / ScratchOutputChannels
    if (frameCount <= 0) return samples
    val dstFrameCount = (frameCount.toDouble() * dstRate / srcRate).roundToInt()
    if (dstFrameCount <= 0) return samples
    val out = ShortArray(dstFrameCount * ScratchOutputChannels)
    val ratio = srcRate.toDouble() / dstRate.toDouble()
    repeat(dstFrameCount) { i ->
        val srcPos = i * ratio
        val i0 = srcPos.toInt().coerceIn(0, frameCount - 1)
        val i1 = (i0 + 1).coerceIn(0, frameCount - 1)
        val frac = (srcPos - i0).coerceIn(0.0, 1.0)
        repeat(ScratchOutputChannels) { c ->
            val s0 = samples[i0 * ScratchOutputChannels + c].toDouble()
            val s1 = samples[i1 * ScratchOutputChannels + c].toDouble()
            out[i * ScratchOutputChannels + c] = (s0 + (s1 - s0) * frac)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
    return out
}

private data class ScratchBuffer(
    val sourceKey: String,
    val sampleRate: Int,
    val windowStartMs: Long,
    val stereoSamples: ShortArray,
) {
    val frameCount: Int
        get() = stereoSamples.size / ScratchOutputChannels

    val identity: ScratchBufferIdentity
        get() = ScratchBufferIdentity(
            sourceKey = sourceKey,
            windowStartMs = windowStartMs,
            sampleRate = sampleRate,
            frameCount = frameCount,
        )

    private val windowEndMs: Long
        get() = windowStartMs + ((frameCount * 1_000L) / sampleRate.coerceAtLeast(1))

    fun covers(
        sourceKey: String,
        positionMs: Long,
    ): Boolean {
        return this.sourceKey == sourceKey && positionMs in windowStartMs..windowEndMs
    }

    fun needsRefresh(positionMs: Long): Boolean {
        return (windowStartMs > 0L && positionMs - windowStartMs < ScratchDecodeRefreshDistanceMs) ||
            windowEndMs - positionMs < ScratchDecodeRefreshDistanceMs
    }

    fun containsSample(sampleIndex: Double): Boolean {
        return sampleIndex in 0.0..(frameCount - 1).toDouble()
    }

    fun containsSampleSpan(
        startSampleIndex: Double,
        frameStep: Double,
        outputFrames: Int,
    ): Boolean {
        return containsScratchSampleSpan(
            frameCount = frameCount,
            startSampleIndex = startSampleIndex,
            frameStep = frameStep,
            outputFrames = outputFrames,
        )
    }

    fun positionMsToSample(positionMs: Long): Double {
        if (stereoSamples.isEmpty()) {
            return 0.0
        }
        val localPositionMs = positionMs.coerceAtLeast(windowStartMs) - windowStartMs
        val samplePosition = localPositionMs * sampleRate.toDouble() / 1_000.0
        return samplePosition.coerceIn(0.0, (frameCount - 1).toDouble())
    }
}

internal data class ScratchBufferIdentity(
    val sourceKey: String,
    val windowStartMs: Long,
    val sampleRate: Int,
    val frameCount: Int,
)

internal fun shouldResetScratchCursor(
    appliedBufferIdentity: ScratchBufferIdentity?,
    currentBufferIdentity: ScratchBufferIdentity,
    appliedCursorResetGeneration: Long,
    currentCursorResetGeneration: Long,
): Boolean {
    return appliedBufferIdentity != currentBufferIdentity ||
        appliedCursorResetGeneration != currentCursorResetGeneration
}

internal fun containsScratchSampleSpan(
    frameCount: Int,
    startSampleIndex: Double,
    frameStep: Double,
    outputFrames: Int,
): Boolean {
    if (frameCount <= 0 || outputFrames <= 0) {
        return false
    }
    val lastSampleIndex = (frameCount - 1).toDouble()
    val endSampleIndex = startSampleIndex + (frameStep * (outputFrames - 1))
    return startSampleIndex in 0.0..lastSampleIndex &&
        endSampleIndex in 0.0..lastSampleIndex
}

private data class ScratchDecodeRequest(
    val sourceUri: Uri,
    val sourceKey: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
) {
    val windowStartUs: Long
        get() = windowStartMs * 1_000L

    val windowEndUs: Long
        get() = windowEndMs * 1_000L
}

private fun decodeScratchBuffer(
    context: Context,
    request: ScratchDecodeRequest,
): ScratchBuffer? {
    // 1.2.23：MediaCodec 不支持的格式（DSD/DSF/DFF、APE、DTS、TTA、WavPack、TAK、WMA…）
    // 先走 ffmpeg 兜底，让打碟也能出声；MediaCodec 路径极少数误判能解时再补一次兜底。
    val needsFfmpeg = runCatching {
        ApeTranscoder.needsTranscode(context, request.sourceUri)
    }.getOrDefault(false)
    if (needsFfmpeg) {
        decodeScratchBufferFfmpeg(context, request)?.let { return it }
    }
    return decodeScratchBufferMediaCodec(context, request)
        ?: if (!needsFfmpeg) decodeScratchBufferFfmpeg(context, request) else null
}

/**
 * ffmpeg 兜底的打碟解码（1.2.23）：把时间区间交给内置 ffmpeg 解成裸 PCM 再读进采样缓冲。
 * 用于 MediaCodec 不支持的格式，复用 [ApeTranscoder.decodeToRawPcm]（同源 ffmpeg 二进制与输入落地逻辑）。
 */
private fun decodeScratchBufferFfmpeg(
    context: Context,
    request: ScratchDecodeRequest,
): ScratchBuffer? {
    return runCatching {
        val rate = 44_100
        val outFile = File(context.cacheDir, "scratch_pcm_" + abs(System.nanoTime()).toString(16) + ".raw")
        val ok = ApeTranscoder.decodeToRawPcm(
            context,
            request.sourceUri,
            request.windowStartMs,
            request.windowEndMs,
            rate,
            outFile,
        )
        if (!ok) {
            runCatching { outFile.delete() }
            return@runCatching null
        }
        val bytes = outFile.readBytes()
        runCatching { outFile.delete() }
        if (bytes.size < 4) return@runCatching null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val frameCapacity = (bytes.size / 4).coerceAtLeast(2_048)
        val sampleBuilder = StereoSampleBuilder(frameCapacity)
        sampleBuilder.append(buffer, /* channelCount = */ 2, AudioFormat.ENCODING_PCM_16BIT)
        val rawSamples = sampleBuilder.build()
        if (rawSamples.isEmpty()) return@runCatching null
        val safeSampleRate = sanitizeScratchSampleRate(rate)
        val stereoSamples = resampleStereoShorts(rawSamples, rate, safeSampleRate)
        ScratchBuffer(
            sourceKey = request.sourceKey,
            sampleRate = safeSampleRate,
            windowStartMs = request.windowStartMs,
            stereoSamples = stereoSamples,
        )
    }.getOrNull()
}

/**
 * MediaCodec + MediaExtractor 解码路径（[android.media.MediaCodec] 原生支持的常见格式）。
 * 失败返回 null，由 [decodeScratchBuffer] 决定是否走 ffmpeg 兜底。
 */
private fun decodeScratchBufferMediaCodec(
    context: Context,
    request: ScratchDecodeRequest,
): ScratchBuffer? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
        extractor.setDataSource(context, request.sourceUri, null)
        val trackIndex = findAudioTrackIndex(extractor)
        if (trackIndex == -1) {
            null
        } else {
            extractor.selectTrack(trackIndex)
            extractor.seekTo(request.windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(sourceFormat, null, null, 0)
                start()
            }
            codec = decoder

            val initialSampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val initialCapacity = buildInitialSampleCapacity(
                request,
                sanitizeScratchSampleRate(initialSampleRate),
            )
            val sampleBuilder = StereoSampleBuilder(initialCapacity)
            val bufferInfo = MediaCodec.BufferInfo()
            var outputSampleRate = initialSampleRate
            var outputChannelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var firstOutputTimeUs: Long? = null
            var inputEnded = false
            var outputEnded = false

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(ScratchDecodeTimeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: break
                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || (sampleTimeUs >= 0L && sampleTimeUs > request.windowEndUs)) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                sampleTimeUs,
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, ScratchDecodeTimeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                if (firstOutputTimeUs == null) {
                                    firstOutputTimeUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                }
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?.duplicate()
                                    ?.apply {
                                        position(bufferInfo.offset)
                                        limit(bufferInfo.offset + bufferInfo.size)
                                    }
                                if (outputBuffer != null) {
                                    sampleBuilder.append(
                                        outputBuffer,
                                        outputChannelCount,
                                        pcmEncoding,
                                    )
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputEnded = true
                            }
                        }
                    }
                }
            }

            val rawSamples = sampleBuilder.build()
            if (rawSamples.isEmpty()) {
                null
            } else {
                val safeSampleRate = sanitizeScratchSampleRate(outputSampleRate)
                val stereoSamples = resampleStereoShorts(rawSamples, outputSampleRate, safeSampleRate)
                ScratchBuffer(
                    sourceKey = request.sourceKey,
                    sampleRate = safeSampleRate,
                    windowStartMs = (firstOutputTimeUs ?: request.windowStartUs) / 1_000L,
                    stereoSamples = stereoSamples,
                )
            }
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
    repeat(extractor.trackCount) { index ->
        val format = extractor.getTrackFormat(index)
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: return@repeat
        if (mimeType.startsWith("audio/")) {
            return index
        }
    }
    return -1
}

private fun buildInitialSampleCapacity(
    request: ScratchDecodeRequest,
    sampleRate: Int,
): Int {
    val durationMs = (request.windowEndMs - request.windowStartMs).coerceAtLeast(1L)
    val projectedSamples = (durationMs * sampleRate / 1_000L)
        .coerceAtLeast(sampleRate.toLong())
    return projectedSamples.toInt()
}

private class StereoSampleBuilder(initialCapacity: Int) {
    private var buffer = ShortArray(initialCapacity.coerceAtLeast(2_048) * ScratchOutputChannels)
    private var frameCount = 0

    fun append(
        outputBuffer: ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> appendFloat(outputBuffer, channelCount)
            else -> appendIntegerPcm(outputBuffer, channelCount, pcmEncoding)
        }
    }

    fun build(): ShortArray = buffer.copyOf(frameCount * ScratchOutputChannels)

    private fun appendIntegerPcm(
        outputBuffer: ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerSample = pcmBytesPerSample(pcmEncoding)
        val pcmBuffer = outputBuffer.order(ByteOrder.nativeOrder())
        val frameCount = pcmBuffer.remaining() / (channels * bytesPerSample)
        ensureCapacity(this.frameCount + frameCount)
        repeat(frameCount) {
            val left = pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            val right = if (channels > 1) {
                pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            } else {
                left
            }
            repeat((channels - 2).coerceAtLeast(0)) {
                pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            }
            val outputIndex = this.frameCount * ScratchOutputChannels
            buffer[outputIndex] = left
            buffer[outputIndex + 1] = right
            this.frameCount += 1
        }
    }

    private fun appendFloat(
        outputBuffer: ByteBuffer,
        channelCount: Int,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        val floatBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frameCount = floatBuffer.remaining() / channels
        ensureCapacity(this.frameCount + frameCount)
        repeat(frameCount) {
            val left = floatBuffer.get()
            val right = if (channels > 1) {
                floatBuffer.get()
            } else {
                left
            }
            repeat((channels - 2).coerceAtLeast(0)) {
                floatBuffer.get()
            }
            val outputIndex = this.frameCount * ScratchOutputChannels
            buffer[outputIndex] = (left * Short.MAX_VALUE.toFloat())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            buffer[outputIndex + 1] = (right * Short.MAX_VALUE.toFloat())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            this.frameCount += 1
        }
    }

    private fun ensureCapacity(requiredFrameCount: Int) {
        val requiredSize = requiredFrameCount * ScratchOutputChannels
        if (requiredSize <= buffer.size) {
            return
        }
        var newSize = buffer.size
        while (newSize < requiredSize) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

internal fun pcmBytesPerSample(pcmEncoding: Int): Int {
    return when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> 4
        else -> 2
    }
}

internal fun ByteBuffer.readPcmSampleAsShort(pcmEncoding: Int): Short {
    return when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> {
            (((get().toInt() and 0xFF) - 128) shl 8).toShort()
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val signed24 = readSigned24BitSample()
            (signed24 shr 8).toShort()
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            (int shr 16).toShort()
        }
        else -> short
    }
}

private fun ByteBuffer.readSigned24BitSample(): Int {
    val first = get().toInt() and 0xFF
    val second = get().toInt() and 0xFF
    val third = get().toInt() and 0xFF
    val value = if (order() == ByteOrder.BIG_ENDIAN) {
        (first shl 16) or (second shl 8) or third
    } else {
        first or (second shl 8) or (third shl 16)
    }
    return if ((value and 0x80_0000) != 0) {
        value or -0x100_0000
    } else {
        value
    }
}
