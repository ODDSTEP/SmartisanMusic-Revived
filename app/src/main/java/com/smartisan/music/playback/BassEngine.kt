@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import com.smartisan.music.diagnostics.AppLog
import com.un4seen.bass.BASS
import com.un4seen.bass.BASS_FX
import com.un4seen.bass.BASSmix
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [1.4.20] BASS 音频引擎核心封装（有正式授权）。
 *
 * 职责边界（严格遵守 51 号协商 1A）：
 *  - BASS 只做【解码 + DSP(EQ/混响) + BPM 分析】，解码出的 float PCM 交回 ExoPlayer / libusb 独占输出；
 *  - BASS 绝不自己出声（AAudio 走系统路由、无法指定 USB DAC、与 libusb 独占互斥）。
 *
 * 分层：
 *  - init(context)：进程级一次，加载 native so + FLAC/APE/DSD 插件；
 *  - open(uri/path)：打开一个解码 channel（BASS_STREAM_DECODE | BASS_SAMPLE_FLOAT）；
 *  - 每个 [BassStream] 可独立挂 EQ/混响、取 PCM、seek、测 BPM、关闭。
 *
 * 线程：BASS 的 ChannelGetData 需在持有 channel 的线程调用；本类不持有长生命周期线程，
 * 由调用方（BassPcmDataSource / BPM 分析器）保证单线程访问同一 channel。
 */
internal object BassEngine {

    private const val TAG = "Bass"
    @Volatile private var initialized = false
    @Volatile private var initFailed = false
    private val pluginHandles = mutableListOf<Int>()

    /**
     * [1.4.22·崩溃根治] BASS/BASS_FX/BASSmix 的 native API 全局串行锁。
     *
     * BASS 官方硬性要求：所有 BASS 调用必须串行化，跨线程并发调用（尤其
     * BASS_StreamCreate/StreamFree/ChannelGetData/FX 与 BPM_DecodeGet 同时跑）
     * 会导致 native 层 use-after-free → SIGSEGV，Java 的 try-catch 完全抓不到，
     * 表现就是「开倒放/变速后点歌 2 秒闪退」。本锁把所有 native 调用串到同一把锁上：
     *  - init / mixer 系列 / open 系列 / errorCode（object 层）
     *  - BassStream 的 getPcm / seek / FX / FFT / BPM / close（实例层）
     * 所有路径共用同一把锁，杜绝跨线程踩踏。withLock 是可重入的，嵌套调用安全。
     */
    private val nativeLock = ReentrantLock()
    internal inline fun <T> withLock(block: () -> T): T = nativeLock.withLock(block)

    /** 进程级初始化：加载 so + 插件。线程安全，重复调用无副作用。 */
    fun init(context: Context): Boolean = withLock {
        if (initialized) return@withLock true
        if (initFailed) return@withLock false
        try {
            // BASS_Init(-1=默认设备, 44100, 0)：纯解码用途，不播放。
            // 不强制 AAudio 出声（BASS_CONFIG_ANDROID_AAUDIO=1 会让 BASS 占用音频焦点），
            // 我们只要解码通道，保持默认即可。
            val ok = BASS.BASS_Init(-1, 44100, 0)
            if (!ok) {
                AppLog.w(TAG, "BASS_Init failed code=${BASS.BASS_ErrorGetCode()}")
                initFailed = true
                return@withLock false
            }
            // [1.4.29·R132] libbassdsd / libbassape 已从 jniLibs 彻底删除（1.4.26 只是停止加载，
            // .so 仍占 272KB 包体）。这两个插件解 DSD/APE 及 ≥96kHz 高码率时稳定 SIGSEGV，
            // 而 OfficialDecodeFrontend.isBassUnsafe 已把这些格式全部挡在 BASS 之外。
            // FLAC 插件保留（BASS 兜底解码仍需）。改回加载前先解决崩溃问题。
            val libDir = context.applicationInfo.nativeLibraryDir
            loadPlugin("$libDir/libbassflac.so")
            initialized = true
            AppLog.i(TAG, "BASS 引擎就绪 (ver=${BASS.BASS_GetVersion()}) 插件=${pluginHandles.size}")
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "BASS init 异常: ${t.message}", t)
            initFailed = true
            false
        }
    }

    private fun loadPlugin(path: String) {
        try {
            val h = BASS.BASS_PluginLoad(path, 0)
            if (h != 0) pluginHandles.add(h)
            else AppLog.w(TAG, "插件加载失败 $path code=${BASS.BASS_ErrorGetCode()}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "插件加载异常 $path: ${t.message}")
        }
    }

    val isReady: Boolean get() = initialized

    fun errorCode(): Int = withLock { BASS.BASS_ErrorGetCode() }

    // ---------- [1.4.20·路径A] BASS mixer 原语：真双曲交叉淡化 ----------

    /** 创建一个常驻 DECODE mixer。freq/chans 用当前曲参数；失败返回 0。 */
    fun createMixer(freq: Int, chans: Int): Int = withLock {
        if (!initialized) return@withLock 0
        val h = BASSmix.BASS_Mixer_StreamCreate(freq, chans, BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT)
        if (h == 0) AppLog.w(TAG, "BASS_Mixer_StreamCreate 失败 code=${BASS.BASS_ErrorGetCode()}")
        h
    }

    /** 把一个 decode channel 挂到 mixer。flags 传 BASS_MIXER_CHAN_NORAMPIN 避免起播 ramp 爆音。 */
    fun mixerAdd(mixer: Int, channel: Int, startAtByte: Long = 0L): Boolean = withLock {
        val ok = if (startAtByte > 0L) {
            BASSmix.BASS_Mixer_StreamAddChannelEx(
                mixer, channel,
                BASSmix.BASS_MIXER_CHAN_NORAMPIN or BASSmix.BASS_MIXER_CHAN_DOWNMIX,
                startAtByte, 0L,
            )
        } else {
            BASSmix.BASS_Mixer_StreamAddChannel(
                mixer, channel,
                BASSmix.BASS_MIXER_CHAN_NORAMPIN or BASSmix.BASS_MIXER_CHAN_DOWNMIX,
            )
        }
        if (!ok) AppLog.w(TAG, "mixerAdd 失败 mixer=$mixer ch=$channel code=${BASS.BASS_ErrorGetCode()}")
        ok
    }

    fun mixerRemove(channel: Int): Boolean = withLock { BASSmix.BASS_Mixer_ChannelRemove(channel) }

    /** 从 mixer 读混合后的 float PCM，返回字节数。 */
    fun mixerGetData(mixer: Int, buffer: ByteBuffer, length: Int): Int =
        withLock { BASS.BASS_ChannelGetData(mixer, buffer, length) }

    /**
     * 给挂在 mixer 上的 channel 设音量包络（mixer 字节位置坐标）。
     * [nodes] 为 (位置字节, 增益) 序列；用多段 cos/sin 采样点实现等功率曲线。
     */
    fun mixerVolumeEnvelope(channel: Int, nodes: Array<BASSmix.BASS_MIXER_NODE>): Boolean = withLock {
        val ok = BASSmix.BASS_Mixer_ChannelSetEnvelope(
            channel, BASSmix.BASS_MIXER_ENV_VOL, nodes, nodes.size,
        )
        if (!ok) AppLog.w(TAG, "setEnvelope 失败 ch=$channel code=${BASS.BASS_ErrorGetCode()}")
        ok
    }

    /** 秒 -> mixer 字节位置（mixer 用自身采样率换算）。 */
    fun mixerSeconds2Bytes(mixer: Int, sec: Double): Long =
        withLock { BASS.BASS_ChannelSeconds2Bytes(mixer, sec) }

    /** 秒 -> 源 channel 字节位置。 */
    fun channelSeconds2Bytes(channel: Int, sec: Double): Long =
        withLock { BASS.BASS_ChannelSeconds2Bytes(channel, sec) }

    fun freeMixer(mixer: Int) = withLock {
        if (mixer != 0) runCatching { BASS.BASS_StreamFree(mixer) }
    }

    /** 供 DataSource 打开任意本地 uri 为独立 decode channel（预取下一曲用）。 */
    fun openForCrossfade(uri: Uri, context: Context): BassStream? = withLock {
        if (!initialized) return@withLock null
        val flags = BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT
        // 先试真实路径，再试 fd。
        val path = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val h = when {
            !path.isNullOrBlank() && java.io.File(path).canRead() ->
                BASS.BASS_StreamCreateFile(path, 0L, 0L, flags)
            else -> runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) BASS.BASS_StreamCreateFile(pfd, 0L, 0L, flags) else 0
            }.getOrDefault(0)
        }
        if (h == 0) {
            AppLog.w(TAG, "预取解码失败 uri=${uri.toString().takeLast(40)} code=${BASS.BASS_ErrorGetCode()}")
            null
        } else BassStream(h)
    }

    /** 打开一个解码流。filePath 用真实本地路径；pfd 用 ParcelFileDescriptor（content://）。 */
    fun openFile(filePath: String, decodeFloat: Boolean = true): BassStream? = withLock {
        if (!initialized) return@withLock null
        val flags = BASS.BASS_STREAM_DECODE or (if (decodeFloat) BASS.BASS_SAMPLE_FLOAT else 0)
        val h = BASS.BASS_StreamCreateFile(filePath, 0L, 0L, flags)
        if (h == 0) {
            AppLog.w(TAG, "BASS_StreamCreateFile 失败 path=${filePath.takeLast(48)} code=${BASS.BASS_ErrorGetCode()}")
            return@withLock null
        }
        BassStream(h)
    }

    fun openPfd(pfd: android.os.ParcelFileDescriptor, decodeFloat: Boolean = true): BassStream? = withLock {
        if (!initialized) return@withLock null
        val flags = BASS.BASS_STREAM_DECODE or (if (decodeFloat) BASS.BASS_SAMPLE_FLOAT else 0)
        val h = BASS.BASS_StreamCreateFile(pfd, 0L, 0L, flags)
        if (h == 0) {
            AppLog.w(TAG, "BASS_StreamCreateFile(pfd) 失败 code=${BASS.BASS_ErrorGetCode()}")
            return@withLock null
        }
        BassStream(h)
    }

    /** 一个 BASS 解码通道会话。EQ/混响/BPM/PCM 读取都作用于这个 channel。 */
    class BassStream internal constructor(val handle: Int) {

        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val fxHandles = mutableListOf<Int>()
        /** [1.4.20·P2] 实际读取 handle：倒放/变速时指向 BASS_FX 衍生 channel，默认=源 handle。 */
        private var readHandle: Int = handle
        /** [1.4.20·P2] 倒放/变速衍生 handle，关闭时需先释放。 */
        private var derivedHandle: Int = 0
        /** [1.4.22] 倒放中间 handle：reverse 与 tempo 串联时，两者都要释放。 */
        private var intermediateHandle: Int = 0
        /** [1.4.20·Morvanium] FFT 分析复用的直接缓冲（离线低频调用，避免每次分配）。 */
        private val fftBuffer: ByteBuffer by lazy { ByteBuffer.allocateDirect(513 * 4).order(java.nio.ByteOrder.nativeOrder()) }

        /**
         * [1.4.25·R70] 通道采样率（协商 USB 输出采样率用）。
         *
         * 原实现是 `by lazy`：值要拖到首次访问才算，而首次访问往往发生在切歌/seek 之后
         * ——此时 channel 已被 close()/BASS_StreamFree 释放，拿失效 handle 调
         * BASS_ChannelGetInfo → native 回写 info 结构失败 → JNI
         * "fid == null in call to SetIntField" → SIGABRT（点歌必现闪退的源头）。
         *
         * 现在改为**创建时（仍在 open* 的全局锁内）立即求值并缓存**：
         *  - 之后任何线程读 sampleRate 都不再触碰 native，closed 后读到的是安全旧值；
         *  - 求值前校验 closed / handle != 0，杜绝拿死 handle 调 native。
         */
        private val sampleRateCache: Int = BassEngine.withLock {
            if (closed.get() || handle == 0) {
                44100
            } else {
                val info = BASS.BASS_CHANNELINFO()
                if (BASS.BASS_ChannelGetInfo(handle, info) && info.freq > 0) info.freq else 44100
            }
        }

        val sampleRate: Int get() = sampleRateCache

        /**
         * [1.4.25·R70] 通道总字节数（-1=未知）。同 [sampleRate]：创建时在锁内求值缓存。
         * 仅当创建时没取到有效值（<=0，个别容器头尚未解析完）才在未关闭时补读一次并记住，
         * closed 后一律返回缓存值，绝不再碰已释放的 handle。
         */
        @Volatile private var lengthBytesCache: Long = BassEngine.withLock {
            if (closed.get() || handle == 0) -1L else BASS.BASS_ChannelGetLength(handle, BASS.BASS_POS_BYTE)
        }

        val lengthBytes: Long
            get() {
                val cached = lengthBytesCache
                if (cached > 0L || closed.get()) return cached
                return BassEngine.withLock {
                    if (closed.get() || handle == 0) {
                        lengthBytesCache
                    } else {
                        BASS.BASS_ChannelGetLength(handle, BASS.BASS_POS_BYTE).also { len ->
                            if (len > 0L) lengthBytesCache = len
                        }
                    }
                }
            }

        /**
         * [1.4.23·闪退根治] 带全局锁读取源通道字节位置。
         * 原 mixer 会话里有一处 BASS_ChannelGetPosition 裸调在锁外，与并行播放/close
         * 的 StreamFree 竞争 → native use-after-free → SIGSEGV。统一收口到全局锁。
         */
        fun sourcePositionBytes(): Long = BassEngine.withLock {
            if (closed.get()) 0L else BASS.BASS_ChannelGetPosition(handle, BASS.BASS_POS_BYTE)
        }

        /**
         * 总时长秒。[1.4.25·R70] closed 后直接返回 0，绝不对已释放的 handle 调
         * BASS_ChannelBytes2Seconds（同样会走 native 读 channel 结构 → SIGABRT）。
         */
        val durationSec: Double
            get() = if (closed.get()) 0.0 else BassEngine.withLock {
                if (closed.get() || handle == 0) 0.0
                else BASS.BASS_ChannelBytes2Seconds(handle, lengthBytes)
            }

        /**
         * [1.4.20·P2] 应用倒放/变速（在 open 后、读 PCM 前调用）。
         * 依次创建 reverse → tempo 衍生 handle（flags 用 DECODE|FLOAT），
         * 最终把它们串联，读取从最外层 handle 取。任一失败则保持原样，绝不让播放失败。
         */
        fun applyPlaybackMode(reverse: Boolean, speedX100: Int): String = BassEngine.withLock {
            if (closed.get()) return@withLock ""
            // [1.4.23·闪退根治] 幂等：若本 stream 曾被重复 apply（设置热更新/重建），
            // 先释放上一次的衍生 handle，避免旧 handle 悬空被 native 读到 → SIGSEGV。
            releaseDerivedHandles()
            readHandle = handle
            val notes = mutableListOf<String>()
            var cur = handle
            if (reverse) {
                val rev = BASS_FX.BASS_FX_ReverseCreate(
                    cur, 0.5f, BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT,
                )
                if (rev != 0) {
                    BASS.BASS_ChannelSetAttribute(rev, BASS_FX.BASS_ATTRIB_REVERSE_DIR, BASS_FX.BASS_FX_RVS_REVERSE.toFloat())
                    // [1.4.22] 倒放是中间 handle：后面可能再叠变速。单独记下，close 时释放。
                    intermediateHandle = rev
                    cur = rev
                    notes += "倒放"
                } else {
                    AppLog.w(TAG, "倒放创建失败 code=${BASS.BASS_ErrorGetCode()}")
                }
            }
            if (speedX100 in 50..200 && speedX100 != 100) {
                val tempo = BASS_FX.BASS_FX_TempoCreate(
                    cur, BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT,
                )
                if (tempo != 0) {
                    val pct = speedX100 - 100f
                    BASS.BASS_ChannelSetAttribute(tempo, BASS_FX.BASS_ATTRIB_TEMPO, pct)
                    // [P2·抗锯齿] Shannon 插值 + 抗锯齿滤波器，变速不变调且无锯齿混叠。
                    BASS.BASS_ChannelSetAttribute(tempo, BASS_FX.BASS_ATTRIB_TEMPO_OPTION_USE_AA_FILTER, 1f)
                    BASS.BASS_ChannelSetAttribute(tempo, BASS_FX.BASS_ATTRIB_TEMPO_OPTION_AA_FILTER_LENGTH, 64f)
                    derivedHandle = tempo
                    cur = tempo
                    notes += "变速${speedX100 / 100f}x"
                } else {
                    AppLog.w(TAG, "变速创建失败 code=${BASS.BASS_ErrorGetCode()}")
                }
            }
            if (cur != handle) {
                readHandle = cur
            }
            notes.joinToString("+")
        }

        /** 读取 float PCM（交错）。length 为字节数，返回实际读取字节数。 */
        fun getPcm(buffer: ByteBuffer, length: Int): Int = BassEngine.withLock {
            if (closed.get()) return@withLock 0
            BASS.BASS_ChannelGetData(readHandle, buffer, length)
        }

        /** seek 到字节位置（作用于源；倒放/变速衍生 channel 的 seek 由 BASS 同步）。 */
        fun seekByte(pos: Long): Boolean = BassEngine.withLock {
            !closed.get() && BASS.BASS_ChannelSetPosition(readHandle, pos, BASS.BASS_POS_BYTE)
        }

        /** seek 到秒。 */
        fun seekSec(sec: Double): Boolean = BassEngine.withLock {
            if (closed.get()) return@withLock false
            val pos = BASS.BASS_ChannelSeconds2Bytes(readHandle, sec)
            BASS.BASS_ChannelSetPosition(readHandle, pos, BASS.BASS_POS_BYTE)
        }

        /** 挂一段 biquad EQ。返回是否成功。band: type/freq/gainDb/Q。 */
        fun addEqBand(type: Int, freq: Float, gainDb: Float, q: Float): Int = BassEngine.withLock {
            if (closed.get()) return@withLock 0
            val fx = BASS.BASS_ChannelSetFX(handle, BASS_FX.BASS_FX_BFX_BQF, 0)
            if (fx == 0) return@withLock 0
            val p = BASS_FX.BASS_BFX_BQF().apply {
                lFilter = type
                fCenter = freq.coerceAtLeast(1f)
                fGain = gainDb
                fQ = q.coerceIn(0.1f, 20f)
                fBandwidth = 0f
                lChannel = BASS_FX.BASS_BFX_CHANALL
            }
            if (BASS.BASS_FXSetParameters(fx, p)) {
                fxHandles.add(fx)
                fx
            } else {
                BASS.BASS_ChannelRemoveFX(handle, fx)
                0
            }
        }

        /** 挂 Freeverb 混响。strength 0..1，0=不挂。 */
        fun setReverb(strength: Float) = BassEngine.withLock {
            clearReverb()
            if (closed.get() || strength <= 0f) return@withLock
            val fx = BASS.BASS_ChannelSetFX(handle, BASS_FX.BASS_FX_BFX_FREEVERB, 0)
            if (fx == 0) return@withLock
            val p = BASS_FX.BASS_BFX_FREEVERB().apply {
                fDryMix = 1f - strength * 0.3f
                fWetMix = strength
                fRoomSize = 0.5f + strength * 0.3f
                fDamp = 0.5f
                fWidth = 1f
                lChannel = BASS_FX.BASS_BFX_CHANALL
            }
            if (BASS.BASS_FXSetParameters(fx, p)) fxHandles.add(fx)
            else BASS.BASS_ChannelRemoveFX(handle, fx)
        }

        /** 清掉所有 EQ 段（保留混响）。 */
        fun clearEq() = BassEngine.withLock {
            fxHandles.toList().forEach { h ->
                runCatching { BASS.BASS_ChannelRemoveFX(handle, h) }
            }
            fxHandles.clear()
        }

        /** 清掉混响：识别方式=重建时只保留 EQ，这里简单全清再让调用方重挂。 */
        private fun clearReverb() { /* 由 clearAllFx 统一处理，见 applyEqAndReverb */ }

        /** 一次性应用全部 BASS_FX：EQ + 混响 + 压限 + 声道平衡（避免增量挂 FX 重复）。 */
        fun applyEqAndReverb(
            bands: List<EqSpec>,
            reverbStrength: Float,
            compressor: Pair<Boolean, Float> = false to 0f,
            channelBalance: Float = 0f,
        ) = BassEngine.withLock {
            clearAllFx()
            bands.forEach { addEqBand(it.type, it.freq, it.gainDb, it.q) }
            if (reverbStrength > 0f) setReverb(reverbStrength)
            if (compressor.first) setCompressor(compressor.second)
            if (channelBalance != 0f) setChannelBalance(channelBalance)
        }

        /** [1.4.20·P1] BASS_FX 压限器（COMPRESSOR2）：强度 0..1 映射阈值/压缩比。 */
        private fun setCompressor(strength: Float) = BassEngine.withLock {
            if (closed.get()) return@withLock
            val fx = BASS.BASS_ChannelSetFX(handle, BASS_FX.BASS_FX_BFX_COMPRESSOR2, 0)
            if (fx == 0) return@withLock
            val s = strength.coerceIn(0f, 1f)
            val p = BASS_FX.BASS_BFX_COMPRESSOR2().apply {
                fGain = 0f
                fThreshold = -6f - 18f * s
                fRatio = 2f + 6f * s
                fAttack = 10f
                fRelease = 200f
                lChannel = BASS_FX.BASS_BFX_CHANALL
            }
            if (BASS.BASS_FXSetParameters(fx, p)) fxHandles.add(fx)
            else BASS.BASS_ChannelRemoveFX(handle, fx)
        }

        /** [1.4.20·P1] 声道平衡：用 VOLUME FX 分别调左右声道增益（等功率）。 */
        private fun setChannelBalance(balance: Float) = BassEngine.withLock {
            if (closed.get()) return@withLock
            val b = balance.coerceIn(-1f, 1f)
            val l = kotlin.math.sqrt((1f - b).toDouble()).toFloat()
            val r = kotlin.math.sqrt((1f + b).toDouble()).toFloat()
            val fxL = BASS.BASS_ChannelSetFX(handle, BASS_FX.BASS_FX_BFX_VOLUME, 0)
            if (fxL != 0) {
                val pL = BASS_FX.BASS_BFX_VOLUME().apply {
                    lChannel = 1
                    fVolume = l
                }
                if (BASS.BASS_FXSetParameters(fxL, pL)) fxHandles.add(fxL)
                else BASS.BASS_ChannelRemoveFX(handle, fxL)
            }
            val fxR = BASS.BASS_ChannelSetFX(handle, BASS_FX.BASS_FX_BFX_VOLUME, 0)
            if (fxR != 0) {
                val pR = BASS_FX.BASS_BFX_VOLUME().apply {
                    lChannel = 2
                    fVolume = r
                }
                if (BASS.BASS_FXSetParameters(fxR, pR)) fxHandles.add(fxR)
                else BASS.BASS_ChannelRemoveFX(handle, fxR)
            }
        }

        private fun clearAllFx() = BassEngine.withLock {
            fxHandles.toList().forEach { runCatching { BASS.BASS_ChannelRemoveFX(handle, it) } }
            fxHandles.clear()
        }

        /**
         * [1.4.20·Morvanium] 取一路 FFT 频谱（1024 点，float dB），用于分频能量/段落分析。
         * 数据写入 [out]（长度≥513）。返回实际写入的 float 数，失败返回 0。
         */
        fun readFft(out: FloatArray): Int = BassEngine.withLock {
            if (closed.get()) return@withLock 0
            val buf = fftBuffer
            buf.clear()
            val bytes = BASS.BASS_ChannelGetData(
                readHandle, buf, BASS.BASS_DATA_FFT1024 or BASS.BASS_DATA_FLOAT,
            )
            if (bytes <= 0) return@withLock 0
            buf.flip()
            val fb = buf.asFloatBuffer()
            val floats = (bytes / 4).coerceAtMost(out.size)
            fb.get(out, 0, floats)
            floats
        }

        /** [1.4.20·Morvanium] seek 到指定秒（分析采样点用）。 */
        fun seekSecForAnalysis(sec: Double): Boolean = seekSec(sec)

        /**
         * 离线 BPM 检测（[1.4.20·问题6] 智能过渡按节奏切入）。
         * 注意：BASS_FX_BPM_DecodeGet 会消费 channel，必须在独立 channel 上跑（非播放 channel）。
         * 这里传入的是专用于分析的解码 channel，调用完即弃。返回 BPM（0=检测失败）。
         */
        fun detectBpm(minBpm: Int = 60, maxBpm: Int = 200): Float = BassEngine.withLock {
            if (closed.get()) return@withLock 0f
            val len = durationSec
            if (len <= 1.0) return@withLock 0f
            val span = minOf(len - 0.5, 30.0)
            val mm = (minBpm shl 16) or maxBpm
            try {
                val bpm = BASS_FX.BASS_FX_BPM_DecodeGet(
                    handle, 0.0, span, mm, BASS_FX.BASS_FX_BPM_MULT2, null, null,
                )
                AppLog.i(TAG, "BPM 检测完成 bpm=$bpm span=${span.toInt()}s")
                bpm
            } catch (t: Throwable) {
                AppLog.w(TAG, "BPM 检测异常: ${t.message}")
                0f
            }
        }

        /**
         * [1.4.23·闪退根治] 释放倒放/变速衍生 handle（最外层 tempo → 中间 reverse）。
         * BASS_FX 衍生 handle 默认不接管源（未设 FREESOURCE），必须逐级手动释放，
         * 否则源被提前释放而衍生 handle 仍在读 = use-after-free = native SIGSEGV。
         * 调用方须已持有 [BassEngine.withLock]。
         */
        private fun releaseDerivedHandles() {
            if (derivedHandle != 0 && derivedHandle != handle) {
                runCatching { BASS.BASS_StreamFree(derivedHandle) }
                derivedHandle = 0
            }
            if (intermediateHandle != 0 && intermediateHandle != handle) {
                runCatching { BASS.BASS_StreamFree(intermediateHandle) }
                intermediateHandle = 0
            }
        }

        fun close() = BassEngine.withLock {
            // [1.4.25·R70] 顺序不变式：先在锁内置 closed=true，再释放 FX / 衍生 handle / 源 handle。
            // 任何线程在锁内看到 closed=true 后都不会再调 native（getPcm/seek/sampleRate/...），
            // 因此不存在"读到已释放 handle"的窗口；此顺序不可颠倒。
            if (!closed.compareAndSet(false, true)) return@withLock
            clearAllFx()
            // [1.4.22·崩溃根治 / 1.4.23] 释放顺序：最外层衍生(tempo) → 中间(reverse) → 源(handle)。
            releaseDerivedHandles()
            if (handle != 0) runCatching { BASS.BASS_StreamFree(handle) }
        }
    }

    data class EqSpec(val type: Int, val freq: Float, val gainDb: Float, val q: Float)
}
