package com.smartisan.music.playback

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.smartisan.music.diagnostics.AppLog
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * USB Audio Class 直出（自带 UAC 驱动的最小实现）——**真正的 USB 独占**。
 *
 * 思路：
 *  1. 解析配置描述符里的 UAC1 / UAC2 音频流接口，挑出支持当前采样率与位深的 alt setting；
 *  2. `claimInterface(force = true)` 把内核的 snd-usb-audio 从接口上解绑，
 *     再 `setInterface(alt)` 打开流式接口 —— 这一步成功，其它 App 就再也拿不到这块声卡；
 *  3. 用 class-specific control transfer 设置采样率（UAC1 走 endpoint，UAC2 走 clock source），
 *     并 **GET_CUR 回读校验**（设不上就放弃，否则会变调 8.8%）；
 *  4. 把 usbfs 的 fd 交给原生层，用 isochronous URB 直接推 PCM。
 *
 * 与"假独占"的区别：之前只是在 AudioSink 上 `setPreferredDevice` 把输出钉到 USB 设备，
 * 系统混音器照旧工作，其它 App 仍可同时出声。这里直接把内核驱动从接口上摘掉，
 * 系统里这块声卡**根本不存在**了，因此是唯一 bit-perfect、也唯一能绕开 SRC 的通道。
 *
 * 任何一步失败都返回 null，由上层回退到 AudioTrack（系统共享输出），不会没声音。
 */
object UsbDac {

    private const val TAG = "Usb"

    private val lock = Any()

    init {
        // nativeOpen / nativeSpeed 都在这支库里。放在 object 的 init 里保证
        // 谁先用谁加载（loadLibrary 幂等）。
        runCatching { System.loadLibrary("chuizi_usb") }
            .onFailure { AppLog.e(TAG, "load chuizi_usb failed", it) }
    }

    /** [v22] 硬件音量探测总时限：开声前同步探测，不能把开声拖成几秒。 */
    private const val ProbeBudgetMs = 400L

    /** 目标 PCM 编码：值就是**每样本字节数** */
    const val ENC_16 = 2
    const val ENC_24 = 3
    const val ENC_32 = 4

    interface Sink {
        val channels: Int

        /** 目标 PCM 编码（[ENC_16] / [ENC_24] / [ENC_32]） */
        val enc: Int
        val framesPlayed: Long

        /** 原生层运行时计数（帧数 / 校准 / 欠载），排障日志用 */
        val stats: String

        /** 会话还活着（原生层没报致命错误）。判断"DAC 还在不在"要用它，别只看对象非空 */
        val alive: Boolean

        val deviceName: String

        /** 输出采样率（本次独占协商出来的） */
        val sampleRate: Int

        /** 该 DAC 是否支持通过 UAC 控制传输调硬件音量（探测通过后才为 true） */
        val hardwareVolumeSupported: Boolean

        /**
         * 探测硬件音量：描述符候选 Feature Unit → GET_CUR/RANGE → 写中间值回读验证 → 写回原值。
         * 会话建立、开声之前调用一次；失败返回 false，调用方退回数字音量。
         */
        fun probeHardwareVolume(): Boolean = hardwareVolumeSupported

        /** 硬件音量档位 0..1000，返回是否真正写进设备 */
        fun setHardwareVolume(permille: Int): Boolean

        /**
         * [v22] 按 dB 写 DAC 硬件音量：raw = db × 256（UAC 的 1/256 dB 定点数）。
         * 与数字模式的 -60~0 dB 同一坐标系，跨域交接时响度才对得上。
         */
        fun setHardwareVolumeDb(db: Float): Boolean = false

        /** [v22] TPDF 抖动：数字衰减后量化到目标位深前加 ±1 LSB 三角分布噪声。 */
        fun setDither(enabled: Boolean) {}

        fun start()
        fun write(data: ByteArray, off: Int, size: Int): Int
        fun pause()
        fun resume()
        fun flush()

        /** 软件音量增益（0..1）：在输出端逐包应用，使音量调节即时生效而不受环形缓冲时长影响 */
        fun setSoftwareGain(gain: Float)

        /** [1.4.21] 读回当前实际生效的软件增益（0..1）；不支持读回返回 -1。 */
        fun softwareGain(): Float = -1f

        /** 让阻塞中的 write 立刻返回（不释放句柄） */
        fun abort()
        fun close()
    }

    internal class AltInfo(
        val num: Int,
        val alt: Int,
        val epOut: Int,
        /** 端点每包容量（已按 wMaxPacketSize 的 bit12:11 乘上 high-bandwidth 倍数） */
        val epMax: Int,
        val interval: Int,
        val subslot: Int,
        val bitRes: Int,
        val channels: Int,
        val rates: List<IntRange>,
        /** 反馈端点地址（0 = 设备是 synchronous/adaptive，不需要反馈） */
        val fbEp: Int = 0,
        /** 反馈端点包长：3（UAC1 10.14）或 4（UAC2 16.16） */
        val fbBytes: Int = 0,
    )

    internal class Parsed(
        val uac2: Boolean,
        val clockId: Int,
        val acNum: Int,
        val alts: List<AltInfo>,
        /** UAC2 的 clock selector（0 = 没有） */
        val clockSel: Int = 0,
        val clockWritable: Boolean = true,
        /** 支持硬件音量的 Feature Unit（0 = 不支持；由描述符解析，探测式验证后才会用） */
        val volumeUnit: Int = 0,
        val volumeWritable: Boolean = false,
        /**
         * AC 里出现过的**所有** FEATURE_UNIT unit ID（1.2.5）。
         * bmaControls 的位靠不住——有的固件 Volume 位标 0 但控制请求照常响应，
         * 所以真正支不支持以 open 后的 GET_CUR 探测为准，这里只提供候选。
         */
        val fuCandidates: List<Int> = emptyList(),
        /**
         * [v24·硬件音量修法①] 位于"USB 流输入 → 扬声器输出"播放路径上的 Feature Unit ID。
         * 探测硬件音量时优先用它——capture/其它路径的 FU 写了有回读但控制不了播放音量。
         * 0 = 拓扑未能判定（退回逐个盲试）。
         */
        val playbackUnit: Int = 0,
        /** AC 类描述符摘要（取证：设备到底有哪些 entity、FU 里有什么控制位） */
        val acDump: String = "",
    )

    private class Session(
        private val conn: UsbDeviceConnection,
        private val dev: UsbDevice,
        private val asIface: UsbInterface,
        private val alt0: UsbInterface?,
        private val acIface: UsbInterface?,
        private val handle: Long,
        override val channels: Int,
        override val enc: Int,
        override val sampleRate: Int,
        private val parsed: Parsed,
    ) : Sink {

        @Volatile private var closed = false
        /** [1.4.21] 已施加的软件增益记录，供读回确认（软件音量下=真实生效值）。 */
        @Volatile private var softwareGainValue = 1f
        // 读写锁：写线程拿着读锁做最长 200ms 的 nativeWrite，主线程读进度只需并发读锁；
        // 关闭拿写锁，保证 native 句柄不会在调用中被 free。
        private val life = ReentrantReadWriteLock()
        private val ctl = Any()

        /** 硬件音量范围（UAC 原始 dB 定点数，1/256 dB 单位），未探测到时为 null */
        @Volatile private var volRange: IntRange? = null

        /**
         * 探测式硬件音量（1.2.5）：
         * 不再相信描述符里的 Volume 控制位（Rays 这类固件常把位标 0，但控制请求照常响应），
         * open 后逐个候选 unit × 声道发 GET_CUR 问一遍——读得到数据再取区间、
         * SET 写一次回读确认。**问得出、写得上才算支持**，否则如实 false。
         */
        @Volatile private var hwVolUnit = 0
        @Volatile private var hwVolChannel = 0

        override val deviceName: String
            get() = try { dev.deviceName } catch (t: Throwable) { "" }

        override val alive: Boolean
            get() = life.read {
                if (closed) false
                else try { nativeHealthy(handle) != 0 } catch (t: Throwable) { false }
            }

        override val framesPlayed: Long
            get() = life.read {
                if (closed) 0L else try { nativeFramesPlayed(handle) } catch (t: Throwable) { 0L }
            }

        override val stats: String
            get() = life.read {
                try { if (closed) "usb=closed" else nativeStats(handle) } catch (t: Throwable) { "usb=err" }
            }

        override val hardwareVolumeSupported: Boolean
            get() = !closed && hwVolUnit != 0

        override fun setHardwareVolume(permille: Int): Boolean {
            if (closed || hwVolUnit == 0) return false
            val range = volRange ?: return false
            val clamped = permille.coerceIn(0, 1000)
            // 线性映射到设备的 raw dB 区间（UAC 的 raw 是 1/256 dB 定点数）
            val raw = (range.first + ((range.last - range.first) * clamped / 1000.0)).roundToInt()
                .coerceIn(range.first, range.last)
            return life.read {
                if (closed) false
                else try {
                    val ok = setVolumeRaw(raw)
                    if (!ok) AppLog.w(TAG, "hw volume set failed raw=$raw")
                    ok
                } catch (t: Throwable) { false }
            }
        }

        override fun setHardwareVolumeDb(db: Float): Boolean {
            if (closed || hwVolUnit == 0) return false
            val range = volRange ?: return false
            // UAC 的 raw 单位就是 1/256 dB，直接按 dB 换算，和数字模式的坐标系一致。
            // 0 档（-127 dB）必须落到设备最小值：有的 DAC 音量下限只到 -60 dB，
            // 直接按 -127 换算会被 clamp 成 -60，用户拉到 0 仍然有声音。
            val raw =
                if (db <= -120f) range.first
                else (db * 256f).roundToInt().coerceIn(range.first, range.last)
            return life.read {
                if (closed) false
                else try {
                    val ok = setVolumeRaw(raw)
                    if (!ok) AppLog.w(TAG, "hw volume(db) set failed db=$db raw=$raw")
                    ok
                } catch (t: Throwable) { false }
            }
        }

        override fun setDither(enabled: Boolean) {
            if (closed) return
            life.read { if (!closed) nativeSetDither(handle, enabled) }
        }

        /* ---------------- 探测式硬件音量（1.2.5） ---------------- */

        /** 对一个 unit×声道 读一次 Volume CUR。UAC2 与 UAC1 的 bRequest 都试一遍 */
        private fun readVolumeCur(idx: Int, cn: Int): Int? {
            val wValue = (0x02 shl 8) or cn
            return readShort(conn, 0xA1, REQ_UAC2_CUR, 2, idx, wValue)
                ?: readShort(conn, 0xA1, REQ_UAC2_CUR_LEGACY, 2, idx, wValue)
                ?: readShort(conn, 0xA1, REQ_UAC1_CUR, 2, idx, wValue)
        }

        /** 读 Volume 区间：UAC2 用 GET_RANGE(0x02)，UAC1 用 GET_MIN(0x82)/GET_MAX(0x83) */
        private fun readVolumeRange(idx: Int, cn: Int): IntRange? {
            val wValue = (0x02 shl 8) or cn
            readRange(conn, idx, wValue)?.let { return it }
            val min = readShort(conn, 0xA1, 0x82, 2, idx, wValue)
            val max = readShort(conn, 0xA1, 0x83, 2, idx, wValue)
            return if (min != null && max != null) min..max else null
        }

        /**
         * [v24·硬件音量修法①] 逐 unit 实测：写两个相距很远的电平（25% 与 75% 档），
         * 各自回读都必须跟随，且两个回读值要拉开明显距离。只写 mid 一次回读一致是不够的——
         * 有的 FU（capture 路径 / 假音量单元）回读跟随但根本不改变播放输出；双电平
         * 实测能淘汰"回读恒等于写入"的哑单元里的一部分，并给探测留可取证的证据。
         * 确认后把原值写回，探测不留痕。
         */
        private fun verifyVolumeWritable(idx: Int, cn: Int, range: IntRange, original: Int): Boolean {
            val span = range.last - range.first
            val low = (range.first + span / 4).coerceIn(range.first, range.last)
            val high = (range.first + span * 3 / 4).coerceIn(range.first, range.last)
            if (!writeVolumeRaw(idx, cn, low)) return false
            val backLow = readVolumeCur(idx, cn) ?: return false
            if (!writeVolumeRaw(idx, cn, high)) return false
            val backHigh = readVolumeCur(idx, cn) ?: return false
            writeVolumeRaw(idx, cn, original)
            val follows = kotlin.math.abs(backLow - low) <= 256 && kotlin.math.abs(backHigh - high) <= 256
            AppLog.i(
                TAG,
                "hw volume probe levels unit=${idx shr 8} cn=$cn low=$low->$backLow high=$high->$backHigh " +
                    "delta=${kotlin.math.abs(backHigh - backLow)} follows=$follows",
            )
            return follows && kotlin.math.abs(backHigh - backLow) >= span / 3
        }

        /** SET_CUR(Volume)。wValue = (CS_VOLUME shl 8) | cn */
        private fun writeVolumeRaw(idx: Int, cn: Int, raw: Int): Boolean {
            val wValue = (0x02 shl 8) or cn
            val req = if (parsed.uac2) REQ_UAC2_CUR else 0x01
            val w = try {
                conn.controlTransfer(0x21, req, wValue, idx, le16(raw), 2, 800)
            } catch (t: Throwable) { -1 }
            return w >= 0
        }

        /**
         * 逐个候选找"真正能调"的音量单元。
         * 候选来源：描述符解析出的 FEATURE_UNIT → 兜底盲试 1..16。
         * 每个候选先 GET_CUR master（CN=0），不行再试声道（CN=1/2）。
         * 全部失败 = 设备真的不支持，打一条日志作罢。
         */
        override fun probeHardwareVolume(): Boolean {
            if (closed) return false
            val units = ArrayList<Int>()
            // [v24·硬件音量修法①] 播放路径上的 FU 优先——它才是真正控制输出电平的单元。
            if (parsed.playbackUnit != 0) units.add(parsed.playbackUnit)
            if (parsed.volumeUnit != 0 && !units.contains(parsed.volumeUnit)) units.add(parsed.volumeUnit)
            for (u in parsed.fuCandidates) if (u != 0 && !units.contains(u)) units.add(u)
            if (units.isEmpty()) for (u in 1..16) units.add(u)
            AppLog.i(TAG, "hw volume probe candidates (playback first): $units")
            // [v22] 会话建立、开声之前同步探测，必须限时：坏固件的一次控制传输会卡 800ms，
            // 不设上限会把"开声"拖成几秒。正常 DAC 1~3 次往返就结束，400ms 足够。
            val deadline = android.os.SystemClock.elapsedRealtime() + ProbeBudgetMs
            for (u in units) {
                for (cn in intArrayOf(0, 1, 2)) {
                    if (android.os.SystemClock.elapsedRealtime() > deadline) {
                        AppLog.w(TAG, "hw volume probe timeout (${ProbeBudgetMs}ms), fallback digital")
                        return false
                    }
                    val idx = (u shl 8) or parsed.acNum
                    val cur = readVolumeCur(idx, cn) ?: continue
                    val range = readVolumeRange(idx, cn) ?: continue
                    // 区间太小说明这不是音量控制（或设备瞎报），当不支持处理
                    if (range.last - range.first < 64) continue
                    if (!verifyVolumeWritable(idx, cn, range, cur)) continue
                    hwVolUnit = u
                    hwVolChannel = cn
                    volRange = range
                    AppLog.i(
                        TAG,
                        "hw volume probe ok unit=$u cn=$cn range=$range cur=$cur " +
                            "uac2=${parsed.uac2}",
                    )
                    return true
                }
            }
            AppLog.i(TAG, "hw volume probe failed (tried units=${units.take(10)})")
            return false
        }

        /**
         * 写音量到探测到的 unit/声道（CS_VOLUME = 0x02）。
         * 声道模式（cn>=1）时左右声道一起写，只写左声道设备可能只出半边音量。
         */
        private fun setVolumeRaw(raw: Int): Boolean {
            val idx = (hwVolUnit shl 8) or parsed.acNum
            if (hwVolChannel == 0) {
                if (!writeVolumeRaw(idx, 0, raw)) return false
                // 回读校验：写不进去就必须如实失败，否则 UI 显示已生效、实际没变。
                // [1.4.28] 旧写法 `?: return true` 把「回读失败」当成「写成功」，
                // 与这段注释的承诺相反——设备不支持 GET_CUR 时用户会以为音量键坏了。
                val cur = readVolumeCur(idx, 0) ?: return false
                return kotlin.math.abs(cur - raw) <= 256
            }
            var ok = true
            for (cn in intArrayOf(1, 2)) {
                val got = readVolumeCur(idx, cn)
                if (got == null && cn == 2) break   // 单声道设备没有 CN=2
                if (!writeVolumeRaw(idx, cn, raw)) ok = false
            }
            return ok
        }

        override fun start() {
            life.read { if (!closed) nativeStart(handle) }
        }

        override fun write(data: ByteArray, off: Int, size: Int): Int = life.read {
            if (closed) -1 else try { nativeWrite(handle, data, off, size) } catch (t: Throwable) { -1 }
        }

        override fun pause() {
            life.read { if (!closed) nativePause(handle, true) }
        }

        override fun resume() {
            life.read { if (!closed) nativePause(handle, false) }
        }

        override fun flush() {
            life.read { if (!closed) nativeFlush(handle) }
        }

        override fun setSoftwareGain(gain: Float) {
            if (closed) return
            life.read { if (!closed) nativeSetSoftwareGain(handle, gain) }
            softwareGainValue = gain
        }

        override fun softwareGain(): Float = if (closed) -1f else softwareGainValue

        override fun abort() {
            synchronized(ctl) {
                if (closed) return
                try { nativeAbort(handle) } catch (t: Throwable) { }
            }
        }

        /**
         * 关闭会话并**把设备还给系统**。
         *
         * 顺序固定：先停传输 → 退回 alt0 → release 接口 → `USBDEVFS_CONNECT` 把内核
         * 驱动绑回去 → 关 fd。少了 CONNECT，这块 DAC 在系统音频里会"凭空不见"，
         * 只有拔插才回来 —— 也就是"关不掉独占"。
         *
         * [reset] = 关闭后对整设备做一次 USBDEVFS_RESET（等效重新插拔）。
         * 用户手动关闭独占开关时必须带：某些耳机/ROM 下 CONNECT 回绑不会让
         * snd-usb-audio 重新认领全部接口，麦克风会一直失效，只有复位重枚举才恢复。
         * 独占重建路径（切歌/会话丢失）**不能** reset——设备会短暂消失，独占建立失败。
         */
        override fun close() = closeInternal(reset = false)

        fun closeInternal(reset: Boolean) {
            abort()
            val first = life.write {
                if (closed) return@write false
                synchronized(ctl) {
                    if (closed) return@synchronized false
                    closed = true
                    try { nativeClose(handle) } catch (t: Throwable) { }
                    try { asIface.let { conn.setInterface(alt0 ?: it) } } catch (t: Throwable) { }
                    try { conn.releaseInterface(asIface) } catch (t: Throwable) { }
                    acIface?.let { try { conn.releaseInterface(it) } catch (t: Throwable) { } }
                    try { nativeReconnect(conn.fileDescriptor) } catch (t: Throwable) { }
                    if (reset) {
                        try { nativeUsbReset(conn.fileDescriptor) } catch (t: Throwable) { }
                        AppLog.i(TAG, "usb reset on close (restore headset mic / re-enumerate)")
                    }
                    try { conn.close() } catch (t: Throwable) { }
                    true
                }
            }
            if (!first) return
            if (active === this) active = null
            closedAt = android.os.SystemClock.elapsedRealtime()
            // [R74] 会话正常关闭：清掉"独占在场"标记，避免下次 BASS 崩溃被误归因到 USB。
            runCatching { appCtx?.let { UsbExclusiveGuard.markSessionActive(it, false) } }
            AppLog.i(TAG, "usb session closed, device returned to system (${dev.productName})")
        }
    }

    @Volatile
    private var active: Session? = null

    /** 最近一次 open 传入的 ApplicationContext：会话关闭时要把"独占在场"标记清掉。 */
    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var closedAt = 0L

    val isActive: Boolean get() = active?.alive == true

    /** 当前独占用的设备名（UI 展示） */
    val activeDeviceName: String? get() = active?.takeIf { it.alive }?.deviceName

    /** 当前独占会话是否支持硬件音量（设置页如实展示；无会话时 false） */
    val activeHardwareVolumeSupported: Boolean
        get() = active?.alive == true && active?.hardwareVolumeSupported == true

    /**
     * 刚关掉独占的短暂窗口：重建输出时内核要重新 probe 声卡，
     * 这段时间 AudioFlinger 会发现"USB 输出没了"并发 NOISY 广播，不能当成拔线。
     */
    fun recentlyActive(ms: Long = 3000L): Boolean {
        val t = closedAt
        return t != 0L && android.os.SystemClock.elapsedRealtime() - t < ms
    }

    /**
     * 最近一次 [open] 失败的归类。
     *
     * 为什么需要它：旧版 open 失败一律返回 null，上层只能把降级原因写成
     * "DAC 无法打开或不支持当前源"——但实测多数失败是权限/会话未建立/接口被占用，
     * 跟"DAC 不支持源"无关，设置页因此长期误报（台账 R75）。这里把真实原因带出去。
     */
    enum class OpenFailure {
        NO_USB_SERVICE,
        NO_DEVICE,
        NO_PERMISSION,
        OPEN_DEVICE_FAILED,
        NO_DESCRIPTORS,
        PARSE_FAILED,
        NO_STREAM_IFACE,
        /** 设备确实没有能覆盖该采样率/位深/声道的端点（真·不支持当前源） */
        FORMAT_UNSUPPORTED,
        INTERFACE_NOT_FOUND,
        CLAIM_FAILED,
        SET_INTERFACE_FAILED,
        /** 采样率写下去后被设备回读成别的值（硬开会变调，主动放弃） */
        RATE_UNSUPPORTED,
        NATIVE_OPEN_FAILED,
        EXCEPTION,
    }

    @Volatile
    private var lastFailure: OpenFailure? = null

    @Volatile
    private var lastFailureDetail: String = ""

    /** 最近一次 open 失败的归类；null = 上次 open 没失败（或还没开过）。 */
    fun lastOpenFailure(): OpenFailure? = lastFailure

    /** 最近一次 open 失败的细节（日志/排障用，非用户可见文案）。 */
    fun lastOpenFailureDetail(): String = lastFailureDetail

    /* ---------------- 对外接口 ---------------- */

    /**
     * 尝试建立 UAC 直出。返回 null 表示此设备/此格式不可用，调用方应回退。
     * [v24·bit-perfect] targetBits>0（源位深 16/24/32）时优先选**与源位深完全一致**的
     * 端点，使 sink.enc==源字节数、逐字节直传成立（不再 16bit→32bit 扩展）。
     * 没有匹配的端点再退回最高位深（此时非 bit-perfect，由上层如实记录）。
     */
    fun open(
        ctx: Context, rate: Int, channels: Int, preferHigh: Boolean, targetBits: Int = 0,
    ): Sink? {
        synchronized(lock) {
            closeQuietly()
            appCtx = ctx.applicationContext
            lastFailure = null
            lastFailureDetail = ""
            // 刚刚主动关过会话时，内核正在重新 probe snd-usb-audio；此时立刻 force claim
            // 很容易撞上"内核重绑定"窗口 → claimInterface 失败 → 上层看到"主动开会话失败"。
            // 给内核 60ms 落定再开，代价远小于一次失败的会话重建。
            val sinceClosed = if (closedAt == 0L) Long.MAX_VALUE
                else android.os.SystemClock.elapsedRealtime() - closedAt
            if (sinceClosed in 0L..150L) {
                try { Thread.sleep(60) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            AppLog.i(TAG, "uac open req ${rate}Hz ${channels}ch hi=$preferHigh targetBits=$targetBits")
            val mgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return rememberFailure(OpenFailure.NO_USB_SERVICE, "无 UsbManager")
            val dev = findDac(mgr)
                ?: return rememberFailure(OpenFailure.NO_DEVICE, "未找到 USB 音频输出设备")
            if (!mgr.hasPermission(dev)) {
                return rememberFailure(
                    OpenFailure.NO_PERMISSION,
                    "USB 设备权限未授予（${dev.productName}）",
                )
            }
            val conn = try { mgr.openDevice(dev) } catch (t: Throwable) { null }
                ?: return rememberFailure(OpenFailure.OPEN_DEVICE_FAILED, "openDevice 返回 null")
            val kernSpeed = try { nativeSpeed(conn.fileDescriptor) } catch (t: Throwable) { 0 }

            var ok = false
            try {
                val raw = conn.rawDescriptors
                    ?: return rememberFailure(OpenFailure.NO_DESCRIPTORS, "读不到 rawDescriptors")
                val parsed = parse(raw)
                    ?: return rememberFailure(OpenFailure.PARSE_FAILED, "描述符解析失败（没有可用音频流 alt）")
                if (parsed.alts.isEmpty()) {
                    return rememberFailure(OpenFailure.NO_STREAM_IFACE, "没有 AS 音频流接口")
                }
                synchronized(speedCache) { speedCache.clear() }
                val clock0 = resolveClock(conn, parsed)
                val uac2Rates = if (parsed.uac2) readUac2Rates(conn, parsed, clock0) else emptyList()
                AppLog.i(
                    TAG,
                    "uac desc uac2=${parsed.uac2} clock=${parsed.clockId} sel=${parsed.clockSel} " +
                        "cur=$clock0 wr=${parsed.clockWritable} ac=${parsed.acNum} " +
                        "vol=0x${parsed.volumeUnit.toString(16)}/${parsed.volumeWritable} " +
                        "fu=[${parsed.fuCandidates.joinToString(",") { it.toString() }}] " +
                        "kernSpeed=$kernSpeed sysfs=${sysfsSpeed(dev)} alts=${parsed.alts.size} " +
                        "rates=${uac2Rates.size} " +
                        parsed.alts.take(6).joinToString(";") { a ->
                            "if${a.num}/${a.alt} epMax=${a.epMax} intv=${a.interval} " +
                                "sub=${a.subslot} bits=${a.bitRes} ch=${a.channels} fb=${a.fbEp}"
                        },
                )
                if (parsed.acDump.isNotBlank()) {
                    AppLog.i(TAG, "uac ac dump: ${parsed.acDump}")
                }
                // [v24·bit-perfect] 零损失模式：先按源位深精确匹配端点；再按设备最高位深，
                // 最后退到 2 声道。**候选按优先级排成一列逐个尝试**——某一个 alt 的
                // claim/setInterface/nativeOpen 失败（固件只认部分 alt、内核正重绑定等），
                // 自动降一个位深/声道继续开，而不是一次失败就整条链路放弃。
                val candidates = rankCandidates(
                    parsed, dev, rate, channels, preferHigh, kernSpeed, uac2Rates, targetBits,
                )
                if (candidates.isEmpty()) {
                    return rememberFailure(
                        OpenFailure.FORMAT_UNSUPPORTED,
                        "无匹配端点（${rate}Hz ${channels}ch " +
                            "${if (targetBits > 0) "${targetBits}bit" else "任意位深"}；" +
                            "alts=" + parsed.alts.joinToString(";") {
                                "if${it.num}/${it.alt} ${it.subslot * 8}bit ch${it.channels} epMax=${it.epMax}"
                            } + "）",
                    )
                }
                var lastAttempt: Attempt? = null
                for ((index, cand) in candidates.withIndex()) {
                    val attempt = attemptOpen(conn, dev, parsed, cand, rate, kernSpeed)
                    val session = attempt.session
                    if (session != null) {
                        active = session
                        ok = true
                        // [R74] 记录"此刻有独占会话在场"：native 崩溃时用它做归因，
                        // 只有崩溃确实发生在独占会话期间才熔断独占（BASS 崩溃不再连坐）。
                        runCatching {
                            UsbExclusiveGuard.markSessionActive(ctx.applicationContext, true)
                        }
                        val chosen = cand.alt
                        val hs = isHighSpeed(dev, chosen, rate, kernSpeed)
                        val packetMs = packetMs(chosen, hs)
                        val spp = rate * packetMs / 1000.0
                        val npkts = ((4.0 / packetMs).toInt()).coerceIn(1, MAX_PKTS)
                        AppLog.i(
                            TAG,
                            "uac ready: ${dev.productName} ${rate}Hz ${cand.enc * 8}bit " +
                                "ch=${chosen.channels} hs=$hs pktMs=$packetMs " +
                                "spp=${"%.3f".format(spp)} npkts=$npkts " +
                                "ep=0x${chosen.epOut.toString(16)} epMax=${chosen.epMax} " +
                                "if=${chosen.num}/${chosen.alt} attempt=${index + 1}/${candidates.size} " +
                                "sysfs=${sysfsSpeed(dev)}",
                        )
                        return session
                    }
                    lastAttempt = attempt
                    AppLog.w(
                        TAG,
                        "uac attempt #${index + 1}/${candidates.size} failed " +
                            "[${attempt.failure}] if=${cand.alt.num}/${cand.alt.alt} " +
                            "${cand.alt.subslot * 8}bit: ${attempt.detail}",
                    )
                    // 采样率是所有 alt 共用的同一个 clock source：它不被接受时换位深也没用，
                    // 直接结束候选循环，避免为每个 alt 重复等一次 800ms 的控制传输超时。
                    if (attempt.failure == OpenFailure.RATE_UNSUPPORTED) break
                    // claim/setInterface 失败最可能是撞上内核重新 probe 的窗口，短暂停一下再换候选。
                    if (attempt.failure == OpenFailure.CLAIM_FAILED ||
                        attempt.failure == OpenFailure.SET_INTERFACE_FAILED
                    ) {
                        try { Thread.sleep(40) } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                return rememberFailure(
                    lastAttempt?.failure ?: OpenFailure.FORMAT_UNSUPPORTED,
                    lastAttempt?.detail ?: "所有候选端点均打开失败",
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "uac open failed: ${t.message}", t)
                return rememberFailure(OpenFailure.EXCEPTION, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                if (!ok) {
                    // 各候选已在 attemptOpen 里把接口还给系统（退回 alt0 + release）。
                    // 这里只做整设备收尾：把内核驱动绑回去（否则"插着声卡但系统没声音"）并关 fd。
                    try { nativeReconnect(conn.fileDescriptor) } catch (t: Throwable) { }
                    try { conn.close() } catch (t: Throwable) { }
                }
            }
        }
    }

    /** 一个已排好序的候选端点：alt + 它对应的 PCM 编码（每样本字节数）。 */
    private class Candidate(val alt: AltInfo, val enc: Int)

    private class Attempt(
        val session: Session?,
        val failure: OpenFailure?,
        val detail: String,
    )

    private fun rememberFailure(kind: OpenFailure, detail: String): Sink? {
        lastFailure = kind
        lastFailureDetail = detail
        AppLog.w(TAG, "uac open failed [$kind]: $detail")
        return null
    }

    /**
     * 对**单个**候选 alt 走完整建会话流程：force claim → 设采样率 → 切流式 alt → nativeOpen。
     *
     * 失败时把这一轮占用的接口还回系统（退回 alt0 + release + nativeClose），但**不**
     * nativeReconnect/close——那是整设备卸载，交给 [open] 在全部候选都失败后统一做，
     * 避免每个候选都把内核驱动解绑/重绑一次。
     */
    private fun attemptOpen(
        conn: UsbDeviceConnection,
        dev: UsbDevice,
        parsed: Parsed,
        cand: Candidate,
        rate: Int,
        kernSpeed: Int,
    ): Attempt {
        val chosen = cand.alt
        val asIface = findIface(dev, chosen.num, chosen.alt)
            ?: return Attempt(null, OpenFailure.INTERFACE_NOT_FOUND, "找不到接口 if${chosen.num}/${chosen.alt}")
        val alt0 = findIface(dev, chosen.num, 0)
        val acIface = findIface(dev, parsed.acNum, 0)
        var handle = 0L
        var claimed = false
        var acClaimed = false
        var success = false
        try {
            // 取证：所选接口的端点一览（fb 端点解析失误时靠它定位）
            AppLog.i(
                TAG,
                "uac ep dump if=${chosen.num}/${chosen.alt}: " +
                    (0 until asIface.endpointCount).joinToString(";") { e ->
                        val ep = asIface.getEndpoint(e)
                        "0x${ep.address.toString(16)} attr=0x${ep.attributes.toString(16)} " +
                            "max=${ep.maxPacketSize}"
                    },
            )
            // 反馈端点兜底：raw 描述符解析可能漏掉 fb（实测 MOONDROP Rays fb=0），
            // 直接用框架解析好的接口端点表找 iso IN —— 端点表永远比手解析可靠。
            var fbEp = chosen.fbEp
            var fbBytes = if (chosen.fbEp != 0) chosen.fbBytes else 0
            if (fbEp == 0) {
                for (e in 0 until asIface.endpointCount) {
                    val ep = asIface.getEndpoint(e)
                    val isIn = ep.direction == UsbConstants.USB_DIR_IN
                    val iso = ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    val sz = ep.maxPacketSize
                    if (isIn && iso && (sz == 3 || sz == 4)) {
                        fbEp = ep.address
                        fbBytes = sz
                        AppLog.i(TAG, "fb endpoint recovered via if scan: ep=0x${fbEp.toString(16)} bytes=$fbBytes")
                        break
                    }
                }
            }

            // 关键：force claim 会把内核驱动从这些接口上解绑
            claimed = try { conn.claimInterface(asIface, true) } catch (t: Throwable) { false }
            acClaimed = if (acIface == null) true
                else try { conn.claimInterface(acIface, true) } catch (t: Throwable) { false }
            AppLog.i(
                TAG,
                "uac claim=$claimed iface=${chosen.num} alt=${chosen.alt} ac=$acClaimed",
            )
            if (!claimed) {
                return Attempt(
                    null, OpenFailure.CLAIM_FAILED,
                    "claimInterface(${chosen.num}) 失败，接口仍被内核/其它进程占用",
                )
            }
            if (parsed.uac2 && acIface != null && !acClaimed) {
                AppLog.w(TAG, "uac: ac claim failed, rate control may be owned by kernel")
            }

            val clock0 = resolveClock(conn, parsed)
            if (parsed.uac2) {
                // UAC2：采样率属于 clock source（AC 接口），先设再切流式 alt
                if (!setRate(conn, parsed, chosen, rate, clock0)) {
                    return Attempt(
                        null, OpenFailure.RATE_UNSUPPORTED,
                        "UAC2 采样率 ${rate}Hz 未被设备接受（或回读不一致）",
                    )
                }
                if (!conn.setInterface(asIface)) {
                    return Attempt(
                        null, OpenFailure.SET_INTERFACE_FAILED,
                        "UAC2 setInterface(if${chosen.num}/${chosen.alt}) 失败",
                    )
                }
            } else {
                // UAC1：采样率请求发给 endpoint，必须先进 alt
                if (!conn.setInterface(asIface)) {
                    return Attempt(
                        null, OpenFailure.SET_INTERFACE_FAILED,
                        "UAC1 setInterface(if${chosen.num}/${chosen.alt}) 失败",
                    )
                }
                if (!setRate(conn, parsed, chosen, rate, clock0)) {
                    return Attempt(
                        null, OpenFailure.RATE_UNSUPPORTED,
                        "UAC1 采样率 ${rate}Hz 未被设备接受（或回读不一致）",
                    )
                }
            }

            val hs = isHighSpeed(dev, chosen, rate, kernSpeed)
            val packetMs = packetMs(chosen, hs)
            val spp = rate * packetMs / 1000.0
            val npkts = ((4.0 / packetMs).toInt()).coerceIn(1, MAX_PKTS)
            handle = nativeOpen(
                conn.fileDescriptor, chosen.epOut, spp, rate,
                chosen.channels, cand.enc, npkts, chosen.epMax, fbEp, fbBytes,
            )
            if (handle == 0L) {
                return Attempt(null, OpenFailure.NATIVE_OPEN_FAILED, "nativeOpen 返回 0（URB 提交被拒）")
            }
            val session = Session(
                conn, dev, asIface, alt0, acIface, handle,
                chosen.channels, cand.enc, rate, parsed,
            )
            success = true
            return Attempt(session, null, "")
        } catch (t: Throwable) {
            return Attempt(null, OpenFailure.EXCEPTION, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            if (!success) {
                if (handle != 0L) try { nativeClose(handle) } catch (t: Throwable) { }
                if (claimed) {
                    try { conn.setInterface(alt0 ?: asIface) } catch (t: Throwable) { }
                    try { conn.releaseInterface(asIface) } catch (t: Throwable) { }
                }
                if (acClaimed) {
                    try { acIface?.let { conn.releaseInterface(it) } } catch (t: Throwable) { }
                }
            }
        }
    }

    fun closeQuietly(reset: Boolean = false) {
        synchronized(lock) {
            try { active?.closeInternal(reset) } catch (t: Throwable) { }
            active = null
        }
    }

    private var pendingReceiver: BroadcastReceiver? = null

    /**
     * [v12·native 崩溃后半占用根治] 进程重启后强制回收"孤立"的 UAC 设备。
     *
     * 背景：native SIGSEGV(Bass/UAC worker) 直接杀死进程时，来不及走 closeInternal 的
     * releaseInterface + USBDEVFS_CONNECT，内核侧该音频接口仍处于被 claim 的半占用态——
     * 表现为：独占开关显示开、权限却"未授予"、麦克风失效、媒体声也没了，且必须物理
     * 重新插拔才能恢复。v11 的复位依赖进程内 active sink，进程一死 active=null 无从复位，
     * 所以失效。
     *
     * 本方法不依赖任何进程内状态：独立 open 设备 → 强制 claim 音频接口 → USBDEVFS_RESET
     * （等效一次物理插拔，把内核 snd-usb-audio 重新 probe）→ release → reconnect → close。
     * 只在启动时检测到 native 崩溃后调用一次，正常启动不触发（避免每次开 App 都重置声卡）。
     */
    fun resetOrphanedDevice(ctx: Context) {
        runCatching {
            val mgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val dev = findDac(mgr) ?: return
            if (!mgr.hasPermission(dev)) {
                AppLog.w(TAG, "resetOrphanedDevice: no permission, skip")
                return
            }
            val conn = try { mgr.openDevice(dev) } catch (t: Throwable) { null } ?: return
            try {
                // 强制认领所有音频类接口，再 reset，把内核可能残留的占用状态清干净。
                var claimed = false
                for (i in 0 until dev.interfaceCount) {
                    val itf = dev.getInterface(i)
                    if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                    try {
                        if (conn.claimInterface(itf, true)) claimed = true
                    } catch (t: Throwable) { }
                }
                try { nativeUsbReset(conn.fileDescriptor) } catch (t: Throwable) { }
                try { nativeReconnect(conn.fileDescriptor) } catch (t: Throwable) { }
                if (claimed) {
                    for (i in 0 until dev.interfaceCount) {
                        val itf = dev.getInterface(i)
                        if (itf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                        try { conn.releaseInterface(itf) } catch (t: Throwable) { }
                    }
                }
                AppLog.i(TAG, "resetOrphanedDevice: USB reset done, half-occupied state cleared")
            } finally {
                try { conn.close() } catch (t: Throwable) { }
            }
        }.onFailure { AppLog.w(TAG, "resetOrphanedDevice failed: ${it.message}") }
    }

    fun cancelPending(ctx: Context) {
        pendingReceiver?.let { try { ctx.unregisterReceiver(it) } catch (t: Throwable) { } }
        pendingReceiver = null
    }

    /** 申请 USB 权限（已授权时同步回调）。真独占必须先拿到授权。 */
    fun ensure(context: Context, cb: (UsbDevice?) -> Unit) {
        val mgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return cb(null)
        val dev = findDac(mgr) ?: return cb(null)
        if (mgr.hasPermission(dev)) return cb(dev)

        cancelPending(context)
        val action = context.packageName + ".USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != action) return
                try { context.unregisterReceiver(this) } catch (t: Throwable) { }
                if (pendingReceiver === this) pendingReceiver = null
                cb(if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) dev else null)
            }
        }
        val filter = IntentFilter(action)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            pendingReceiver = receiver
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName), flags,
            )
            mgr.requestPermission(dev, pi)
        } catch (t: Throwable) {
            AppLog.w(TAG, "uac requestPermission failed: ${t.message}")
            try { context.unregisterReceiver(receiver) } catch (e: Throwable) { }
            if (pendingReceiver === receiver) pendingReceiver = null
            cb(null)
        }
    }

    fun hasPermission(ctx: Context): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val dev = findDac(mgr) ?: return false
        return try { mgr.hasPermission(dev) } catch (t: Throwable) { false }
    }

    /**
     * 找一块**能出声**的 USB 声卡：音频类接口里带等时 OUT 端点的设备。
     *
     * 这是"独占目标"和"设备还在不在"的**唯一判据**，不能用 AudioManager：
     * 独占期间系统会把该输出摘掉，用 `getDevices` 会把"正在独占"误判成"已拔出"。
     */
    fun findDac(mgr: UsbManager): UsbDevice? = try {
        mgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i ->
                val itf = d.getInterface(i)
                itf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    (0 until itf.endpointCount).any { e ->
                        val ep = itf.getEndpoint(e)
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                            ep.direction == UsbConstants.USB_DIR_OUT
                    }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** 任意 USB 音频类设备（麦克风/耳机也算）。只用于提示，不能用来判定 DAC 在位 */
    fun findAnyAudio(mgr: UsbManager): UsbDevice? = try {
        mgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
    } catch (t: Throwable) {
        null
    }

    /* ---------------- 选流 ---------------- */

    /**
     * [v24·bit-perfect] 产出**有序候选列表**：先精确匹配源位深（targetBits>0），再按
     * "声道匹配优先、位深从高到低"排序。open 会按顺序逐个尝试，前一个 alt 打不开
     * （固件只认部分 alt / claim 被占 / setInterface 失败）就自动降到下一个，
     * 不再一次失败就整条独占链路放弃。
     */
    private fun rankCandidates(
        p: Parsed, dev: UsbDevice, rate: Int, ch: Int, preferHigh: Boolean, kernSpeed: Int,
        uac2Rates: List<IntRange> = emptyList(), targetBits: Int = 0,
    ): List<Candidate> {
        val ordered = ArrayList<Candidate>(4)
        fun addAll(list: List<Candidate>) {
            for (c in list) {
                if (ordered.none { it.alt.num == c.alt.num && it.alt.alt == c.alt.alt }) ordered.add(c)
            }
        }
        // 1) 零损失：源位深精确匹配的端点最优先（逐字节直传前提）。
        if (targetBits > 0) {
            addAll(rankAlts(p, dev, rate, ch, preferHigh, kernSpeed, uac2Rates, targetBits))
        }
        // 2) 常规：该采样率/声道下设备支持的最高位深。
        addAll(rankAlts(p, dev, rate, ch, preferHigh, kernSpeed, uac2Rates, 0))
        // 3) 声道兜底：请求单声道但设备只有立体声端点时按 2ch 开（多余声道补静音）。
        if (ch != 2) {
            addAll(rankAlts(p, dev, rate, 2, preferHigh, kernSpeed, uac2Rates, 0))
        }
        return ordered.take(4)
    }

    private fun rankAlts(
        p: Parsed, dev: UsbDevice, rate: Int, ch: Int, preferHigh: Boolean, kernSpeed: Int,
        uac2Rates: List<IntRange>, targetBits: Int,
    ): List<Candidate> {
        val scored = ArrayList<Pair<Int, Candidate>>()
        for (a in p.alts) {
            if (a.epOut == 0 || a.epMax <= 0 || a.channels < ch) continue
            val rates = if (a.rates.isNotEmpty()) a.rates else uac2Rates
            if (rates.isNotEmpty() && rates.none { rate in it }) continue
            val enc = encFor(a, preferHigh, targetBits) ?: continue
            val hs = isHighSpeed(dev, a, rate, kernSpeed)
            val fpp = packetSamples(a, rate, hs)
            val need = ceil(fpp * enc * a.channels).toInt()
            if (a.epMax < need) continue
            // 声道数必须与描述符一致，否则帧长错位会让播放速度翻几倍
            var score = if (a.channels == ch) 5000 else 0
            if (targetBits > 0) {
                score += if (a.bitRes == targetBits) 100 else 0
            } else {
                score += enc * 8 + a.bitRes
            }
            scored.add(score to Candidate(a, enc))
        }
        scored.sortByDescending { it.first }
        return scored.map { it.second }
    }

    /** targetBits>0 时只返回编码字节数==targetBits/8 的端点（bit-perfect 精确匹配）。 */
    private fun encFor(a: AltInfo, preferHigh: Boolean, targetBits: Int = 0): Int? {
        if (targetBits > 0) {
            val want = when (targetBits) {
                16 -> ENC_16
                24 -> ENC_24
                32 -> ENC_32
                else -> 0
            }
            if (want == 0) return null
            // 必须与源位深字节数完全一致，绝不做 16→32 的整数扩展（那不是逐字节直传）。
            return when (want) {
                ENC_16 -> if (a.subslot == 2 && a.bitRes >= 16) ENC_16 else null
                ENC_24 -> if (a.subslot == 3 && a.bitRes >= 24) ENC_24 else null
                ENC_32 -> if (a.subslot == 4 && a.bitRes >= 16) ENC_32 else null
                else -> null
            }
        }
        return when {
            !preferHigh && a.subslot == 2 && a.bitRes >= 16 -> ENC_16
            a.subslot == 3 && a.bitRes >= 24 -> ENC_24
            a.subslot == 4 && a.bitRes >= 16 -> ENC_32
            a.subslot == 2 && a.bitRes >= 16 -> ENC_16
            else -> null
        }
    }

    private fun findIface(dev: UsbDevice, num: Int, alt: Int): UsbInterface? = try {
        (0 until dev.interfaceCount)
            .map { dev.getInterface(it) }
            .firstOrNull { it.id == num && it.alternateSetting == alt }
    } catch (t: Throwable) {
        null
    }

    /* ---------------- 采样率控制 ---------------- */

    private fun readRate(conn: UsbDeviceConnection, reqType: Int, idx: Int, len: Int, req: Int): Int? = try {
        val rb = ByteArray(len)
        val r = conn.controlTransfer(reqType, req, 0x0100, idx, rb, len, 800)
        if (r < len) null
        else {
            val v = if (len == 4) le32(rb) else u24(rb)
            if (v <= 0) null else v
        }
    } catch (t: Throwable) {
        null
    }

    private fun readRateAny(
        conn: UsbDeviceConnection, reqType: Int, idx: Int, len: Int, vararg reqs: Int,
    ): Int? {
        for (r in reqs) readRate(conn, reqType, idx, len, r)?.let { return it }
        return null
    }

    /** 有符号 16 位（音量/音量的 GET_MIN/MAX/CUR/RANGE 都是 2 字节有符号）。
     *  [wValue] = (CS shl 8) | 声道号，默认 master 音量（0x0200） */
    private fun readShort(
        conn: UsbDeviceConnection, reqType: Int, req: Int, len: Int, idx: Int,
        wValue: Int = 0x0200,
    ): Int? = try {
        val rb = ByteArray(len)
        val r = conn.controlTransfer(reqType, req, wValue, idx, rb, len, 800)
        if (r < len) null
        else {
            val raw = (rb[0].toInt() and 0xFF) or ((rb[1].toInt() and 0xFF) shl 8)
            if (raw >= 0x8000) raw - 0x10000 else raw
        }
    } catch (t: Throwable) {
        null
    }

    /** UAC2 GET_RANGE(CS_VOLUME)：wNumSubRanges + n×(dMIN/dMAX/dRES)，各 2 字节 */
    private fun readRange(conn: UsbDeviceConnection, idx: Int, wValue: Int = 0x0200): IntRange? = try {
        val head = ByteArray(2)
        val r = conn.controlTransfer(0xA1, REQ_UAC2_RANGE, wValue, idx, head, 2, 800)
        if (r < 2) null
        else {
            val n = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
            if (n <= 0 || n > 64) null
            else {
                val buf = ByteArray(2 + n * 6)
                val r2 = conn.controlTransfer(0xA1, REQ_UAC2_RANGE, wValue, idx, buf, buf.size, 800)
                if (r2 < 2 + n * 6) null
                else {
                    var min: Int? = null
                    var max: Int? = null
                    for (k in 0 until n) {
                        val o = 2 + k * 6
                        val a = s16(buf, o)
                        val b = s16(buf, o + 2)
                        if (min == null || a < min) min = a
                        if (max == null || b > max) max = b
                    }
                    if (min != null && max != null) min..max else null
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun s16(b: ByteArray, o: Int): Int {
        val raw = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private const val REQ_UAC2_CUR = 0x01
    private const val REQ_UAC2_CUR_LEGACY = 0x81
    private const val REQ_UAC2_RANGE = 0x02
    private const val REQ_UAC2_RANGE_LEGACY = 0x82
    private const val REQ_UAC1_CUR = 0x81

    /**
     * 设置采样率。**设不上必须返回 false**：设备留在默认 48k 而我们推 44.1k 的帧 = 变调 8.8%。
     * 无论 SET 成功与否都 GET_CUR 回读比对。
     */
    private fun setRate(
        conn: UsbDeviceConnection, p: Parsed, a: AltInfo, rate: Int, clockId: Int,
    ): Boolean {
        try {
            if (p.uac2) {
                val id = if (clockId > 0) clockId else p.clockId
                val idx = (id shl 8) or p.acNum
                var wrote = false
                if (p.clockWritable) {
                    val w = conn.controlTransfer(0x21, 0x01, 0x0100, idx, le32(rate), 4, 800)
                    wrote = w >= 0
                    if (!wrote) AppLog.w(TAG, "uac: uac2 SET_CUR rate failed: $w clock=$id")
                } else {
                    AppLog.w(TAG, "uac: uac2 clock $id read-only, verify only")
                }
                val cur = readRateAny(conn, 0xA1, idx, 4, REQ_UAC2_CUR, REQ_UAC2_CUR_LEGACY)
                if (cur != null) {
                    if (cur != rate) {
                        AppLog.w(TAG, "uac: uac2 rate mismatch $cur != $rate clock=$id")
                        return false
                    }
                    return true
                }
                return wrote
            } else {
                val data = u24Bytes(rate)
                var idx = a.epOut
                var w = conn.controlTransfer(0x22, 0x01, 0x0100, idx, data, 3, 800)
                if (w < 0) {
                    idx = a.num
                    w = conn.controlTransfer(0x22, 0x01, 0x0100, idx, data, 3, 800)
                }
                if (w < 0) AppLog.w(TAG, "uac: uac1 SET_CUR rate failed: $w")
                val cur = readRate(conn, 0xA2, idx, 3, REQ_UAC1_CUR)
                if (cur != null) {
                    if (cur != rate) {
                        AppLog.w(TAG, "uac: uac1 rate mismatch $cur != $rate idx=$idx")
                        return false
                    }
                    return true
                }
                return w >= 0
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "uac: setRate exception ${t.message}")
            return false
        }
    }

    private fun resolveClock(conn: UsbDeviceConnection, p: Parsed): Int {
        if (!p.uac2 || p.clockSel == 0) return p.clockId
        return try {
            val rb = ByteArray(1)
            val idx = (p.clockSel shl 8) or p.acNum
            var r = conn.controlTransfer(0xA1, REQ_UAC2_CUR, 0x0100, idx, rb, 1, 800)
            var v = rb[0].toInt() and 0xFF
            if (r < 1 || v == 0) {
                r = conn.controlTransfer(0xA1, REQ_UAC2_CUR_LEGACY, 0x0100, idx, rb, 1, 800)
                v = rb[0].toInt() and 0xFF
            }
            if (r >= 1 && v > 0) v else p.clockId
        } catch (t: Throwable) {
            p.clockId
        }
    }

    private fun readUac2Rates(conn: UsbDeviceConnection, p: Parsed, clockId: Int): List<IntRange> {
        if (clockId <= 0) return emptyList()
        val idx = (clockId shl 8) or p.acNum
        for (req in intArrayOf(REQ_UAC2_RANGE, REQ_UAC2_RANGE_LEGACY)) {
            val got = readRates(conn, idx, req)
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    private fun readRates(conn: UsbDeviceConnection, idx: Int, req: Int): List<IntRange> {
        val empty = emptyList<IntRange>()
        return try {
            val head = ByteArray(2)
            val r = conn.controlTransfer(0xA1, req, 0x0100, idx, head, 2, 800)
            if (r < 2) return empty
            val n = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
            if (n <= 0 || n > 64) return empty
            val buf = ByteArray(2 + n * 12)
            val r2 = conn.controlTransfer(0xA1, req, 0x0100, idx, buf, buf.size, 800)
            if (r2 < 2 + n * 12) return empty
            val out = ArrayList<IntRange>(n)
            for (k in 0 until n) {
                val o = 2 + k * 12
                val min = le32(buf, o)
                val max = le32(buf, o + 4)
                if (min > 0 && max >= min) out.add(min..max)
            }
            out
        } catch (t: Throwable) {
            empty
        }
    }

    /* ---------------- 速度 ---------------- */

    /**
     * 一个 isochronous 包覆盖的时长（毫秒）。
     * bInterval 在两种速度下**含义不同**：高速是指数（2^(n-1) 个 125µs 微帧），
     * 全速**直接就是毫秒**。混用会把包长算成 2/4/8 倍。
     */
    internal fun packetMs(a: AltInfo, hs: Boolean): Double {
        if (!hs) return a.interval.coerceIn(1, 255).toDouble()
        val ticks = 1 shl ((a.interval - 1).coerceIn(0, 10))
        return ticks / 8.0
    }

    private fun packetSamples(a: AltInfo, rate: Int, hs: Boolean): Double =
        rate * packetMs(a, hs) / 1000.0

    private fun isHighSpeed(dev: UsbDevice, a: AltInfo, rate: Int, kernSpeed: Int): Boolean {
        if (kernSpeed > 0) return if (kernSpeed > 6) kernSpeed >= 480 else kernSpeed >= 3
        val speed = sysfsSpeed(dev)
        if (speed > 0) return speed > 12
        val needFs = ceil(rate / 1000.0 * a.subslot.coerceIn(2, 4) * a.channels).toInt()
        return a.epMax < needFs
    }

    private val speedCache = HashMap<String, Int>()

    private fun sysfsSpeed(dev: UsbDevice): Int {
        val name = try { dev.deviceName } catch (t: Throwable) { null } ?: return 0
        synchronized(speedCache) { speedCache[name]?.let { return it } }
        // /dev/bus/usb/001/002 → 总线 001 / 设备号 002
        val parts = name.trimEnd('/').split('/')
        if (parts.size < 2) return 0
        val num = parts[parts.size - 1].toIntOrNull() ?: return 0
        val bus = parts[parts.size - 2].toIntOrNull() ?: return 0
        val list = try { File("/sys/bus/usb/devices").listFiles() } catch (t: Throwable) { null } ?: return 0
        for (d in list) {
            val b = readInt(File(d, "busnum")) ?: continue
            val n = readInt(File(d, "devnum")) ?: continue
            if (b == bus && n == num) {
                val sp = readInt(File(d, "speed")) ?: 0
                if (sp > 0) synchronized(speedCache) { speedCache[name] = sp }
                return sp
            }
        }
        return 0
    }

    private fun readInt(f: File): Int? = try {
        if (!f.exists()) null else f.readText().trim().toDoubleOrNull()?.toInt()
    } catch (t: Throwable) {
        null
    }

    /* ---------------- 描述符解析 ---------------- */

    internal fun parse(raw: ByteArray): Parsed? {
        var uac2 = false
        var clockId = 0
        var clockSel = 0
        var clockWritable = true
        var acNum = 0
        var acSeen = false
        var curNum = -1
        var curAlt = 0
        var curCls = 0
        var curSub = 0
        var curCh = 0
        var curSubslot = 0
        var curBits = 0
        val curFormats = ArrayList<IntArray>()
        var curEpOut = 0
        var curEpMax = 0
        var curInterval = 1
        var curFb = 0
        var curFbMax = 0
        val curRates = ArrayList<IntRange>()
        val alts = ArrayList<AltInfo>()
        var volumeUnit = 0
        var volumeWritable = false
        val fuCandidates = ArrayList<Int>()
        // [v24·硬件音量修法①] UAC 拓扑：entity_id -> source_id、unit_id -> 类型，
        // 以及每个 Terminal 的 wTerminalType。用来判定哪个 Feature Unit 真正在
        // "USB 流 → 扬声器"播放路径上（而不是麦克风 capture 路径）。
        val entitySource = HashMap<Int, Int>()
        val entityKind = HashMap<Int, String>()
        val terminalType = HashMap<Int, Int>()
        val acSb = StringBuilder()

        fun u24(o: Int): Int = (raw[o].toInt() and 0xFF) or
            ((raw[o + 1].toInt() and 0xFF) shl 8) or
            ((raw[o + 2].toInt() and 0xFF) shl 16)

        /** wMaxPacketSize 的 bit 12:11 是 HS 的 additional transactions per microframe */
        fun effMax(v: Int): Int {
            val base = v and 0x7FF
            val mult = (v shr 11) and 3
            return base * (1 + mult)
        }

        fun flush() {
            var bestSub = 0
            var bestBits = 0
            for (f in curFormats) {
                val sub = f[0]
                val bits = f[1]
                if (sub !in 2..4) continue
                if (minOf(bits, sub * 8) > minOf(bestBits, bestSub * 8)) {
                    bestSub = sub
                    bestBits = bits
                }
            }
            if (bestSub == 0 && curSubslot in 2..4) {
                bestSub = curSubslot
                bestBits = curBits
            }
            if (curCls == 1 && curSub == 2 && curEpOut != 0 && bestSub != 0) {
                alts.add(
                    AltInfo(
                        curNum, curAlt, curEpOut, effMax(curEpMax), curInterval,
                        bestSub, bestBits, if (curCh > 0) curCh else 2, ArrayList(curRates),
                        curFb, if (curFb != 0) (curFbMax and 0x7FF) else 0,
                    ),
                )
            }
            curEpOut = 0
            curEpMax = 0
            curInterval = 1
            curFb = 0
            curFbMax = 0
            curSubslot = 0
            curBits = 0
            curCh = 0
            curFormats.clear()
            curRates.clear()
        }

        // 预扫 AC 接口的 bcdADC：决定后面 Format Type I 的字段布局。
        // 描述符顺序不是强制的，单趟解析可能把 UAC2 的格式描述符按 UAC1 读（offset 差 1 字节）。
        run {
            var cls = 0
            var sub = 0
            var i = 0
            while (i + 2 <= raw.size) {
                val len = raw[i].toInt() and 0xFF
                if (len < 2 || i + len > raw.size) break
                when (raw[i + 1].toInt() and 0xFF) {
                    4 -> if (len >= 9) {
                        cls = raw[i + 5].toInt() and 0xFF
                        sub = raw[i + 6].toInt() and 0xFF
                    }
                    0x24 -> if (cls == 1 && sub == 1 && len >= 5 &&
                        (raw[i + 2].toInt() and 0xFF) == 1
                    ) {
                        val bcd = (raw[i + 3].toInt() and 0xFF) or
                            ((raw[i + 4].toInt() and 0xFF) shl 8)
                        if (bcd >= 0x0200) uac2 = true
                    }
                }
                i += len
            }
        }

        var i = 0
        while (i + 2 <= raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len < 2 || i + len > raw.size) break
            when (raw[i + 1].toInt() and 0xFF) {
                4 -> {
                    if (len < 9) {
                        i += len
                        continue
                    }
                    flush()
                    curNum = raw[i + 2].toInt() and 0xFF
                    curAlt = raw[i + 3].toInt() and 0xFF
                    curCls = raw[i + 5].toInt() and 0xFF
                    curSub = raw[i + 6].toInt() and 0xFF
                    if (curCls == 1 && curSub == 1 && curAlt == 0 && !acSeen) {
                        acNum = curNum
                        acSeen = true
                    }
                }
                0x24 -> {
                    if (len < 3) {
                        i += len
                        continue
                    }
                    val sub = raw[i + 2].toInt() and 0xFF
                    // [v24·硬件音量修法①] 记录拓扑：每个 entity 的 ID/source。
                    // UAC2 AC 类描述符里 IT/OT(2/3) 的 bSourceID 在 offset 7，
                    // FU/MIXER/SELECTOR(4/5/6) 的 bSourceID 在 offset 4。
                    if (curCls == 1 && curSub == 1) {
                        when (sub) {
                            2, 3 -> if (len >= 8) {
                                val eid = raw[i + 3].toInt() and 0xFF
                                val wtt = (raw[i + 4].toInt() and 0xFF) or
                                    ((raw[i + 5].toInt() and 0xFF) shl 8)
                                val src = raw[i + 7].toInt() and 0xFF
                                terminalType[eid] = wtt
                                if (src != 0) entitySource[eid] = src
                                entityKind[eid] = if (sub == 2) "IT" else "OT"
                            }
                            4, 5, 6 -> if (len >= 5) {
                                val eid = raw[i + 3].toInt() and 0xFF
                                val src = raw[i + 4].toInt() and 0xFF
                                if (src != 0) entitySource[eid] = src
                                entityKind[eid] = when (sub) { 4 -> "MIX"; 5 -> "SEL"; else -> "FU" }
                            }
                        }
                    }
                    // AC 类描述符取证：下次日志里能直接看出设备有哪些 entity、
                    // Feature Unit 里到底放没放 Volume 控制位（1.2.4）
                    if (curCls == 1 && curSub == 1 && acSb.length < 280) {
                        val id = if (len >= 4) (raw[i + 3].toInt() and 0xFF) else -1
                        val name = when (sub) {
                            1 -> "hdr"; 2 -> "it"; 3 -> "ot"; 4 -> "mix"; 5 -> "sel"
                            6 -> "fu"; 10 -> "clk"; 11 -> "csel"; 12 -> "cmul"
                            else -> "s$sub"
                        }
                        acSb.append(name).append(if (id >= 0) "@0x${id.toString(16)}" else "")
                        if (sub == 6 && len >= 5) {
                            val hex = StringBuilder()
                            for (k in 0 until minOf(len - 5, 16)) {
                                hex.append("%02x".format(raw[i + 5 + k].toInt() and 0xFF))
                            }
                            acSb.append(" raw=").append(hex)
                        }
                        acSb.append(' ')
                    }
                    if (curCls == 1 && curSub == 1) {
                        when {
                            sub == 1 && len >= 5 -> {
                                val bcd = (raw[i + 3].toInt() and 0xFF) or ((raw[i + 4].toInt() and 0xFF) shl 8)
                                if (bcd >= 0x0200) uac2 = true
                            }
                            sub == 10 && len >= 6 -> {
                                // CLOCK_SOURCE: bClockID(3) bmAttributes(4) bmControls(5)
                                clockId = raw[i + 3].toInt() and 0xFF
                                clockWritable = (raw[i + 5].toInt() and 0x01) != 0
                            }
                            sub == 11 && len >= 4 -> {
                                // CLOCK_SELECTOR：当前选中的时钟要通过 GET_CUR 问
                                clockSel = raw[i + 3].toInt() and 0xFF
                            }
                            // FEATURE_UNIT：硬件音量的控制点。
                            // UAC2：bLength=6+(ch+1)*4，每通道 bmaControls 4 字节，
                            //       每个控制占 2 bit：01=只读 10=主机可写 11=可读可写，
                            //       Mute 在 bits 1:0、**Volume 在 bits 3:2**（1.2.2 误查 1:0，
                            //       导致 Rays 这类设备的音量单元永远检测不到 → 硬件音量不可用）。
                            //       1.2.4 再进一步：不再只看 master 通道——有些 DAC 只给
                            //       ch1/ch2 开 Volume，master 是 0；遍历全部 bmaControls。
                            // UAC1：offset 5 是 bControlSize，bmaControls 从 6 开始、每通道
                            //       一块，Volume = bit1（1.2.3 误读 bControlSize 本身，恒真）。
                            sub == 6 && len >= (if (uac2) 9 else 8) -> {
                                val unitId = raw[i + 3].toInt() and 0xFF
                                // 无论控制位写什么，先把 unit ID 收进候选（1.2.5）：
                                // 支不支持由 open 后的探测决定，位描述经常撒谎
                                if (unitId != 0 && !fuCandidates.contains(unitId)) fuCandidates.add(unitId)
                                var vol = false
                                if (uac2) {
                                    val n = ((len - 6) / 4).coerceAtLeast(1)
                                    for (k in 0 until n) {
                                        val o = i + 5 + k * 4
                                        if (o + 4 > i + len) break
                                        val bma = le32(raw, o)
                                        val volBits = (bma shr 2) and 0x3
                                        if (volBits == 0x2 || volBits == 0x3) { vol = true; break }
                                    }
                                } else {
                                    val csize = (raw[i + 5].toInt() and 0xFF).coerceIn(1, 2)
                                    val n = ((len - 6) / csize).coerceAtLeast(1)
                                    for (k in 0 until n) {
                                        val o = i + 6 + k * csize
                                        if (o + csize > i + len) break
                                        val bma = (raw[o].toInt() and 0xFF) or
                                            (if (csize > 1) (raw[o + 1].toInt() and 0xFF) shl 8 else 0)
                                        if (bma and 0x02 != 0) { vol = true; break }
                                    }
                                }
                                if (vol && volumeUnit == 0) {
                                    volumeUnit = unitId
                                    volumeWritable = true
                                }
                            }
                        }
                    } else if (curCls == 1 && curSub == 2) {
                        // UAC1 与 UAC2 的声道数位置**相反**：
                        //  · UAC2：声道数在 AS General（subtype 1，16 字节）的 offset 10
                        //    （bNrChannels），Format Type I 只有 6 字节、没有声道数字段；
                        //  · UAC1：AS General 只有 7 字节，声道数在 Format Type I 的 offset 4。
                        if (sub == 1 && uac2 && len >= 11) {
                            curCh = raw[i + 10].toInt() and 0xFF
                        } else if (sub == 2 && len >= 6 && (raw[i + 3].toInt() and 0xFF) == 1) {
                            if (uac2) {
                                // UAC2 Format Type I 固定 **6 字节**
                                curSubslot = raw[i + 4].toInt() and 0xFF
                                curBits = raw[i + 5].toInt() and 0xFF
                                curFormats.add(intArrayOf(curSubslot, curBits))
                            } else if (len >= 8) {
                                curCh = raw[i + 4].toInt() and 0xFF
                                curSubslot = raw[i + 5].toInt() and 0xFF
                                curBits = raw[i + 6].toInt() and 0xFF
                                curFormats.add(intArrayOf(curSubslot, curBits))
                                val n = raw[i + 7].toInt() and 0xFF
                                if (n == 0 && len >= 14) {
                                    val min = u24(i + 8)
                                    val max = u24(i + 11)
                                    if (min > 0 && max >= min) curRates.add(min..max)
                                } else if (n in 1..20) {
                                    for (k in 0 until n) {
                                        val o = i + 8 + k * 3
                                        if (o + 3 <= i + len) {
                                            val f = u24(o)
                                            if (f > 0) curRates.add(f..f)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                5 -> {
                    if (len < 7) {
                        i += len
                        continue
                    }
                    val addr = raw[i + 2].toInt() and 0xFF
                    val attr = raw[i + 3].toInt() and 0xFF
                    val maxP = (raw[i + 4].toInt() and 0xFF) or ((raw[i + 5].toInt() and 0xFF) shl 8)
                    val intv = if (len >= 7) raw[i + 6].toInt() and 0xFF else 1
                    val usage = (attr shr 4) and 3
                    if ((attr and 3) == 1) {
                        if ((addr and 0x80) == 0 && usage == 0) {
                            curEpOut = addr
                            curEpMax = maxP
                            curInterval = if (intv <= 0) 1 else intv
                        } else if ((addr and 0x80) != 0 && usage == 1) {
                            curFb = addr
                            curFbMax = maxP
                        }
                    }
                }
            }
            i += len
        }
        flush()
        if (alts.isEmpty()) return null

        // [v24·硬件音量修法①] 从拓扑算出播放路径上的 Feature Unit。
        // 判据：沿每个 entity 的 source 反向回溯，若起点是 USB Streaming 输入终端
        // （wTerminalType=0x0101），说明这条链是"USB→扬声器"的播放路径；取该链上第一个 FU。
        // capture 路径起点是麦克风输入终端(0x02xx)，据此排除——这正是"写 unit3 有回读
        // 却不影响播放音量"的根因（unit3 可能在 capture 路径）。
        fun isUsbStreamIn(eid: Int) = terminalType[eid] == 0x0101
        fun isMicIn(eid: Int) = terminalType[eid]?.let { it in 0x0200..0x02FF } == true
        fun chainStart(id: Int): Int {
            var cur = id
            val seen = HashSet<Int>()
            while (!seen.contains(cur)) {
                seen.add(cur)
                val src = entitySource[cur] ?: return cur
                cur = src
            }
            return cur
        }
        var playbackUnit = 0
        // 优先找"source 链起点=USB 流输入终端"的 FU；其次找 FU 中不在麦克风路径上的。
        for (fu in fuCandidates) {
            if (chainStart(fu).let { isUsbStreamIn(it) }) { playbackUnit = fu; break }
        }
        if (playbackUnit == 0) {
            for (fu in fuCandidates) {
                if (!chainStart(fu).let { isMicIn(it) }) { playbackUnit = fu; break }
            }
        }
        AppLog.i(
            TAG,
            "uac topology: playbackUnit=$playbackUnit fu=${fuCandidates.joinToString(",")} " +
                "kinds=${entityKind.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
                "terms=${terminalType.entries.joinToString(",") { "${it.key}:0x${it.value.toString(16)}" }}",
        )
        return Parsed(
            uac2, clockId, acNum, alts, clockSel, clockWritable, volumeUnit, volumeWritable,
            fuCandidates, playbackUnit,
            acSb.toString().trim(),
        )
    }

    private fun u24Bytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
    )

    private fun le16(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
    )

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun u24(b: ByteArray, o: Int = 0): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, o: Int = 0): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    /* ---------------- native ---------------- */

    /** 每个 URB 的包数上限，**必须和 usbdac.cpp 的 MAX_PKTS 一致** */
    private const val MAX_PKTS = 32

    external fun nativeOpen(
        fd: Int, ep: Int, samplesPerPacket: Double, rate: Int,
        channels: Int, bytesPerSample: Int, packetsPerUrb: Int,
        maxPacketBytes: Int, fbEp: Int, fbBytes: Int,
    ): Long

    /** USBDEVFS_GET_SPEED：返回内核速度枚举（或 Mbps），不支持时 0 */
    external fun nativeSpeed(fd: Int): Int

    /** USBDEVFS_CONNECT：把内核驱动重新绑回接口（退出独占后让系统音频恢复） */
    external fun nativeReconnect(fd: Int): Int

    /** USBDEVFS_RESET：整设备复位重枚举（等效重新插拔，用于关闭独占后恢复麦克风） */
    external fun nativeUsbReset(fd: Int): Int

    external fun nativeStart(h: Long)
    external fun nativeWrite(h: Long, data: ByteArray, off: Int, size: Int): Int
    external fun nativePause(h: Long, paused: Boolean)
    external fun nativeSetSoftwareGain(h: Long, gain: Float)
    external fun nativeSetDither(h: Long, enabled: Boolean)
    external fun nativeFlush(h: Long)
    external fun nativeFramesPlayed(h: Long): Long
    external fun nativeHealthy(h: Long): Int
    external fun nativeStats(h: Long): String

    /** 置"关闭中"标志并唤醒阻塞中的 write；不释放句柄 */
    external fun nativeAbort(h: Long)
    external fun nativeClose(h: Long)
}
