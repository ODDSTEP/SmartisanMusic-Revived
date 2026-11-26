@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.smartisan.music.data.settings.SmartCrossfadeStore
import com.smartisan.music.diagnostics.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * [1.4.20] BASS 解码流式 PCM [DataSource]。
 *
 * 用 BASS（含 DSD/APE/FLAC 插件）把冷门格式解成 float PCM，拼 IEEE-float WAV 头交给
 * Media3 的 WavExtractor。与 [StreamingPcmDataSource]（ffmpeg 管道）并列，BASS 优先、
 * ffmpeg 兜底。关键收益：
 *  - DSD 由 libbassdsd 解成 88.2kHz PCM（保真），不再 352800→44100 强降 8 倍 → 根治怪声；
 *  - EQ/混响挂到 BASS channel（BASS_FX BQF/Freeverb），解码出的 PCM 已含 DSP，
 *    无需第二套 EQ，避免与自研 PcmDspAudioProcessor 双叠加；
 *  - 缓冲百分比通过 [TranscodeProgressBus] 上报（问题5：流式解码也能看到百分比）。
 *
 * BASS 绝不自己出声（51号协商 1A）：只产 PCM，输出仍走 libusb 独占 / 系统共享。
 */
internal class BassPcmDataSource(
    private val context: Context,
    private val sourceUri: Uri,
) : DataSource {

    private val listeners = CopyOnWriteArrayList<TransferListener>()
    private val closed = AtomicBoolean(false)
    private val firstFrameLogged = AtomicBoolean(false)
    /** [1.4.20·问题5] 首帧已到达=已开播，之后不再上报转换进度，避免播放全程顶着 99%。 */
    private val progressDone = AtomicBoolean(false)

    private var stream: BassEngine.BassStream? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    /** [1.4.20·路径A] 当前激活的交叉淡化会话（仅尾部窗口内非空）。 */
    private var xfade: BassCrossfadeMixer.Session? = null
    /** 本曲是否已进入交叉淡化（一个 DataSource 只淡化一次）。 */
    private var xfadeAttempted = false
    /** [1.4.30·R140] "队列桥还没登记"只记一次，避免每帧刷屏。 */
    private var xfadeQueueMissLogged = false

    private var sampleRate = 44100
    private var channels = 2
    private var wavHeader = ByteArray(0)
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var headerOffset = 0
    private var headerRemaining = 0
    private var bytesRead = 0L
    private var dataLength = 0L

    // 复用读 PCM 的 ByteBuffer，避免每帧分配（热路径）。
    private var pcmBuf: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder())

    private val progressKey = "bass_${sourceUri.toString().hashCode()}"

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // [1.4.26·R79] 显式 DSP 所有权：BASS 解码链自己施加 BASS_FX DSP。
        PcmDsp.dspOwner = PcmDsp.DspOwner.NONE
        if (!BassEngine.isReady) {
            throw java.io.IOException("BASS 引擎未就绪")
        }
        // 1) 解析本地路径（BASS 需要真实 fd/path，不认云盘虚拟文件）。
        val path = resolvePath(sourceUri)
        val opened = if (path != null) {
            BassEngine.openFile(path)
        } else {
            runCatching {
                val p = context.contentResolver.openFileDescriptor(sourceUri, "r")
                if (p != null) {
                    pfd = p
                    BassEngine.openPfd(p)
                } else null
            }.getOrNull()
        }
        val s = opened ?: throw java.io.IOException(
            "BASS 无法解码该文件（不支持的格式或插件未加载）uri=${sourceUri.toString().takeLast(48)} code=${BassEngine.errorCode()}",
        )
        stream = s
        // [1.4.20·P2] 应用倒放/变速（BASS_FX 衍生 handle），必须在取 PCM 前。
        val modeNotes = s.applyPlaybackMode(
            reverse = BassDspConfig.reverseEnabled(),
            speedX100 = BassDspConfig.playbackSpeedX100(),
        )
        val speedChanged = BassDspConfig.playbackSpeedX100() != 100
        if (modeNotes.isNotBlank()) {
            AppLog.i("Bass", "播放模式：$modeNotes（P2）")
        }
        // [1.4.25·PCM可观测性] coerce 前的原始采样率与变速倍率留一帧，
        // 变调/怪声时可直接对比"源率 vs 实际输出率 vs 变速"三者，不必靠猜。
        val rawRate = s.sampleRate
        val coercedRate = rawRate.coerceIn(8000, 384000)
        val speedX100Now = BassDspConfig.playbackSpeedX100()
        if (rawRate != coercedRate || speedX100Now != 100) {
            AppLog.i(
                "Bass",
                "PCM采样率诊断: 源=${rawRate}Hz 输出=${coercedRate}Hz 变速=${speedX100Now}% " +
                    "（变调排查请核对这三项是否一致）",
            )
        }
        sampleRate = coercedRate
        channels = 2
        dataLength = s.lengthBytes.coerceAtLeast(0L)
        // 变速会改变输出 PCM 总字节数，此时不能让 WAV 头声明固定长度（会与实读不符），
        // 改用未知长度，ExoPlayer 按流读取到 END_OF_INPUT 即可。
        totalLength = if (dataLength > 0L && !speedChanged) 44L + dataLength else C.LENGTH_UNSET.toLong()

        // 2) 挂 EQ/混响/压限/声道平衡（BASS_FX）。[P1·修审计#1] 压限器真接进 BASS 链。
        val eq = BassDspConfig.eqSpecs()
        // [闭环核查] 音乐厅/V3 增强混响在 BASS 链折算成加强的 FREEVERB（roomSize/wet 更大），
        // 与基础混响取最大者，确保用户开了这两个开关在 BASS 链也听得出区别。
        var rev = BassDspConfig.reverb()
        if (BassDspConfig.hallReverb()) rev = maxOf(rev, 0.18f)
        if (BassDspConfig.v3Reverb()) rev = maxOf(rev, 0.24f)
        val comp = BassDspConfig.compressor()
        val bal = BassDspConfig.channelBalance()
        s.applyEqAndReverb(eq, rev, comp, bal)
        // [1.4.23·噪声根治] 通知 sink 层的 PcmDspAudioProcessor：DSP 已由 BASS 全部接管，
        // 流经此处时须字节透传，避免自研 biquad 二次处理造成"非原声预设噪声"。
        // [1.4.28·所有权修正] 必须与「BASS 真的挂了效果」一致：bassDspEnabled=false 时
        // eqSpecs() 返回空、reverb=0、compressor=false、balance=0，BASS 一个 FX 都没挂。
        // 旧实现无条件声明 BASS，Processor 就会透传，用户开了 EQ/混响/压限却完全听不到变化。
        PcmDsp.dspOwner =
            if (BassDspConfig.bassDspEnabled) PcmDsp.DspOwner.BASS else PcmDsp.DspOwner.PCM
        AppLog.i(
            "Bass",
            "解码流已开 rate=$sampleRate ch=$channels 时长=${"%.1f".format(s.durationSec)}s " +
                "EQ段=${eq.size} 混响=$rev 压限=${comp.first} 平衡=$bal path=${(path ?: "pfd").takeLast(40)}",
        )

        // 3) WAV 头：IEEE float（format tag 3），32bit。
        wavHeader = buildFloatWavHeader(dataLength, sampleRate, channels)
        headerRemaining = if (dataSpec.position <= 44L) wavHeader.size else 0
        headerOffset = 0
        bytesRead = 0L
        firstFrameLogged.set(false)
        progressDone.set(false)

        // 4) seek（DataSpec.position 含 44 字节头）。
        if (dataSpec.position > 44L) {
            val sec = (dataSpec.position - 44L) / (sampleRate.toLong() * channels * 4L).toDouble()
            s.seekSec(sec)
        }
        // [1.4.20·路径A] 若本曲是上一首交叉淡化淡入的，跳过已在淡化中播过的开头，
        // 避免 intro 被重复播放（真 crossfade 的关键闭环）。
        xfade?.let {
            runCatching { it.abort() }
            // [1.4.28] 会话被主动中止，真混音标志必须同步复位，否则音量包络会被永久挡掉。
            BassCrossfadeQueue.markRealMixFinished()
        }
        xfade = null
        xfadeAttempted = false
        val skipIntro = BassCrossfadeQueue.consumeSkipFor(sourceUri)
        // [1.4.33·验收第14点] 重复开头根治：真混音在 A 曲尾部已把 B 曲开头 fadeBytes
        // 淡入播过，切歌时 B 曲必须 seek 到 skipSec 续接，否则从头重播=用户看到的重复开头。
        // 旧门槛 `dataSpec.position <= 44L` 只在 position==0 才跳过，但实机切歌时 Media3
        // 带进来的 position 往往是个小偏移（日志 posMs=18ms），导致 skip 被拒→从头播。
        // 新判据：把 position 换算成"已播 PCM 字节"，只要它还落在真混音已播的开头区间内
        // （< skipIntro，留 44B 头）就续接；用户主动 seek 到中后段（远大于 skipIntro）不跳。
        // 注意 bytes/sec 用 PCM 实际字节率，与上面 skipSec 的分母一致。
        val pcmBytesPerSec = sampleRate.toLong() * channels * 4L
        val pcmPos = (dataSpec.position - 44L).coerceAtLeast(0L)
        if (skipIntro > 0L && pcmPos < skipIntro) {
            val skipSec = skipIntro / pcmBytesPerSec.toDouble()
            if (s.seekSec(skipSec)) {
                bytesRead = skipIntro
                AppLog.i(
                    "Bass",
                    "交叉淡化续接：跳过已播 intro ${skipIntro}B (${"%.2f".format(skipSec)}s)" +
                        " dataSpecPos=${dataSpec.position} pcmPos=$pcmPos",
                )
            } else {
                AppLog.w(
                    "Bass",
                    "交叉淡化续接 seekSec 失败，B 曲将从头播（重复开头）skip=${skipIntro}B",
                )
            }
        }
        TranscodeProgressBus.publish(progressKey, sourceUri.toString().takeLast(40), 0)
        return totalLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headerRemaining > 0) {
            val n = minOf(headerRemaining, length)
            System.arraycopy(wavHeader, headerOffset, buffer, offset, n)
            headerOffset += n
            headerRemaining -= n
            return n
        }
        val s = stream ?: throw java.io.IOException("BassPcmDataSource 未 open 就 read")
        val want = minOf(length, pcmBuf.capacity())

        // [1.4.20·路径A] 若已有淡化会话：从 mixer 读混合 PCM（当前曲淡出+下一曲淡入）。
        val activeXfade = xfade
        if (activeXfade != null) {
            pcmBuf.clear()
            val got = activeXfade.read(pcmBuf, want)
            if (got > 0) {
                pcmBuf.position(0)
                pcmBuf.get(buffer, offset, got)
                applyReplayGain(buffer, offset, got)
                feedGain(buffer, offset, got)
                bytesRead += got
                return got
            }
            // 淡化流读完：会话结束，本 DataSource 数据也到头。
            activeXfade.finish()
            xfade = null
            // [1.4.28] 通知音量包络控制器：真混音已结束，接下来切歌不要再淡入一次。
            BassCrossfadeQueue.markRealMixFinished()
            return C.RESULT_END_OF_INPUT
        }

        // [1.4.23·闪退根治] 倒放/变速下禁用 BASS 真 mixer 交叉淡化：
        // mixer 挂的是源 handle，而倒放/变速播放读的是 BASS_FX 衍生 handle，两者数据面不一致；
        // 交叉淡化与衍生 handle 叠加正是真机"开倒放/变速后点歌约 2 秒 SIGSEGV"的触发组合。
        // 此时智能过渡仍生效（SMC 音量包络那条链独立），只是不让 mixer 碰衍生 handle。
        val playbackModeActive = BassDspConfig.reverseEnabled() || BassDspConfig.playbackSpeedX100() != 100
        // 判断是否进入交叉淡化窗口：智能过渡开 + 未试过 + 剩余 ≤ 淡化时长 + 有下一曲。
        if (!xfadeAttempted && !playbackModeActive && SmartCrossfadeStore.currentEnabled() && dataLength > 0L) {
            val remaining = dataLength - bytesRead
            val fadeBytes = SmartCrossfadeStore.currentDurationSec() * sampleRate.toLong() * channels * 4L
            if (remaining in 1L..fadeBytes) {
                // [1.4.30·R140] 队列桥没登记本曲时**不要**置 xfadeAttempted：
                // 旧实现一进窗口就永久放弃，一旦那一刻队列还没同步（切歌瞬间很常见），
                // 整首歌就白丢一次淡化机会——这是"真交叉淡化几乎从不激活"的第二根因。
                val nextUri = BassCrossfadeQueue.nextFor(sourceUri)
                val queueKnown = BassCrossfadeQueue.hasEntry(sourceUri)
                if (nextUri == null && !queueKnown) {
                    if (!xfadeQueueMissLogged) {
                        xfadeQueueMissLogged = true
                        AppLog.i(
                            "Bass",
                            "交叉淡化窗口已到但队列桥还没登记本曲，等后续 read 重试" +
                                " remaining=$remaining fadeBytes=$fadeBytes" +
                                " uri=${sourceUri.toString().takeLast(36)}",
                        )
                    }
                } else {
                    xfadeAttempted = true
                    if (nextUri == null) {
                        AppLog.i(
                            "Bass",
                            "交叉淡化未激活：本曲是队列最后一首" +
                                " remaining=$remaining uri=${sourceUri.toString().takeLast(36)}",
                        )
                    } else {
                        val curPos = BassEngine.channelSeconds2Bytes(
                            s.handle, bytesRead / (sampleRate.toLong() * channels * 4L).toDouble(),
                        )
                        // [1.4.20·Morvanium] 用预分析的档案同步生成过渡决策（纯计算、零 IO 阻塞热路径）。
                        val curProfile = MorvaniumAnalyzer.analyzeCached(sourceUri.toString())
                        val nextProfile = MorvaniumAnalyzer.analyzeCached(nextUri.toString())
                        val plan = MorvaniumAnalyzer.planTransition(
                            current = curProfile,
                            next = nextProfile,
                            baseFadeSec = SmartCrossfadeStore.currentDurationSec(),
                            sameAlbum = false, // 同专辑判定需 MediaItem 元数据，统计上非关键，保守取 false
                        )
                        val cfg = BassCrossfadeMixer.Config(
                            fadeSeconds = plan.fadeSec,
                            bpmAlign = plan.beatAligned,
                            currentRate = sampleRate,
                            channels = channels,
                            plan = plan,
                            applyTempo = false, // 变速不在 mixer 叠加，避免与 DataSource playbackMode 双重变速
                        )
                        val session = BassCrossfadeMixer.activate(s, curPos, nextUri, context, cfg)
                        if (session == null) {
                            AppLog.i(
                                "Bass",
                                "交叉淡化未激活：mixer 打开下一曲失败" +
                                    " next=${nextUri.toString().takeLast(36)}" +
                                    " plan=${plan.fadeSec}s beatAligned=${plan.beatAligned}" +
                                    " curPos=$curPos remaining=$remaining",
                            )
                        } else {
                            xfade = session
                            // [1.4.28] 真双曲混音已接管：音量包络控制器必须让位，否则双重淡化。
                            BassCrossfadeQueue.markRealMixActive()
                            // 登记下一曲开头已播字节：下一曲 DataSource open 时跳过，避免 intro 重复。
                            BassCrossfadeQueue.markSkippedIntro(nextUri, session.fadeBytes)
                            AppLog.i(
                                "Bass",
                                "进入交叉淡化窗口：remaining=$remaining B" +
                                    " fadeBytes=${session.fadeBytes} plan=${plan.fadeSec}s" +
                                    " next=${nextUri.toString().takeLast(36)}",
                            )
                            pcmBuf.clear()
                            val gotX = session.read(pcmBuf, want)
                            if (gotX > 0) {
                                pcmBuf.position(0)
                                pcmBuf.get(buffer, offset, gotX)
                                applyReplayGain(buffer, offset, gotX)
                                bytesRead += gotX
                                return gotX
                            }
                        }
                    }
                }
            }
        }

        pcmBuf.clear()
        val got = s.getPcm(pcmBuf, want)
        if (got <= 0) return C.RESULT_END_OF_INPUT
        pcmBuf.position(0)
        pcmBuf.get(buffer, offset, got)
        applyReplayGain(buffer, offset, got)
        feedGain(buffer, offset, got)
        bytesRead += got
        if (firstFrameLogged.compareAndSet(false, true)) {
            AppLog.i("Bass", "首帧 PCM 已读出 bytes=$got rate=$sampleRate")
            // [1.4.20·问题5] 首帧到达即代表已开播：立刻清掉转换进度，UI 不再显示「正在转换 xx%」。
            // 之后也不再 publish，避免播放全程百分比一路涨到 99 又卡住。
            progressDone.set(true)
            TranscodeProgressBus.clear(progressKey)
        }
        // 百分比：首帧之前按已读字节 / 总数据长度上报；首帧之后停止。
        if (!progressDone.get() && dataLength > 0L) {
            val pct = ((bytesRead * 100) / dataLength).toInt().coerceIn(0, 99)
            TranscodeProgressBus.publish(progressKey, sourceUri.toString().takeLast(40), pct)
        }
        return got
    }

    override fun getUri(): Uri? = sourceUri
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        TranscodeProgressBus.clear(progressKey)
        // [1.4.26·R79-b] 关闭后交还 DSP 所有权给 Processor：下一条流可能是系统直读，
        // 若仍停留在 NONE/BASS，Processor 会按"已由 BASS 处理"透传，导致 EQ/音效整首失效。
        PcmDsp.dspOwner = PcmDsp.DspOwner.PCM
        xfade?.let {
            runCatching { it.abort() }
            BassCrossfadeQueue.markRealMixFinished()
        }
        xfade = null
        runCatching { stream?.close() }
        stream = null
        runCatching { pfd?.close() }
        pfd = null
        AppLog.i("Bass", "解码流关闭：已输出 PCM=$bytesRead B")
    }

    /** 复用的 float 缓冲（增益监测与 ReplayGain 应用共用，热路径零分配）。 */
    private var gainScratch = FloatArray(4096)

    /** [1.4.20·P0] BASS 链增益监测：把读出的 float PCM 喂给 GainMonitor。 */
    private fun feedGain(bytes: ByteArray, offset: Int, len: Int) {
        if (!GainMonitor.enabled || len < 4) return
        val floats = len / 4
        if (gainScratch.size < floats) gainScratch = FloatArray(floats)
        runCatching {
            val fb = ByteBuffer.wrap(bytes, offset, floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.get(gainScratch, 0, floats)
            GainMonitor.feed(gainScratch, floats / channels.coerceAtLeast(1), channels)
        }
    }

    /**
     * [1.4.20·P1] BASS 链 ReplayGain：对读出的 float PCM 乘响度增益后原地写回。
     * 只处理普通单曲输出；交叉淡化混音期间增益已在各 channel 的 BASS_FX 前统一，跳过避免重复。
     */
    private fun applyReplayGain(bytes: ByteArray, offset: Int, len: Int) {
        val db = BassDspConfig.replayGainDb()
        if (db == 0f || len < 4 || xfade != null) return
        val linear = 10.0.pow(db.toDouble() / 20.0).toFloat()
        val floats = len / 4
        if (gainScratch.size < floats) gainScratch = FloatArray(floats)
        runCatching {
            val fb = ByteBuffer.wrap(bytes, offset, floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.get(gainScratch, 0, floats)
            var i = 0
            while (i < floats) {
                val v = gainScratch[i] * linear
                gainScratch[i] = if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(-1f, 1f)
                i++
            }
            fb.position(0)
            fb.put(gainScratch, 0, floats)
        }
    }

    /** 暴露当前解码 channel 给 BPM 分析器（分析用独立 channel，避免打断播放）。 */
    fun currentStream(): BassEngine.BassStream? = stream

    private fun resolvePath(uri: Uri): String? {
        runCatching {
            val path = context.contentResolver
                .query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists() && f.canRead() && f.length() > 0L) return path
            }
        }
        return runCatching {
            val p = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            pfd = p
            "/proc/self/fd/${p.fd}"
        }.getOrNull()
    }

    private fun buildFloatWavHeader(dataLength: Long, sampleRate: Int, channels: Int): ByteArray {
        val bits = 32
        val formatTag = 3 // IEEE float
        val byteRate = sampleRate.toLong() * channels * bits / 8
        val blockAlign = channels * bits / 8
        val out = java.io.ByteArrayOutputStream(44)
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun w32(v: Long) {
            out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
        }
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val dl = if (dataLength > 0L) dataLength else 0x7FFFFFFFL
        w("RIFF"); w32(36L + dl); w("WAVE")
        w("fmt "); w32(16L); w16(formatTag); w16(channels)
        w32(sampleRate.toLong()); w32(byteRate); w16(blockAlign); w16(bits)
        w("data"); w32(dl)
        return out.toByteArray()
    }
}
