package com.smartisan.music.playback

import com.smartisan.music.data.settings.EqBand
import com.smartisan.music.data.settings.EqFilterType
import com.smartisan.music.playback.BassEngine.EqSpec

/**
 * [1.4.20] BASS DSP 桥接配置：把 App 的均衡器/音效设置翻译成 BASS_FX 参数。
 *
 * PcmDsp.configure(settings) 时同步更新这里；BassPcmDataSource.open() 读取，
 * 把 EQ/混响/压限挂到 BASS 解码 channel 上——这样 BASS 解码出的 PCM 已含 DSP，
 * 再交给 libusb 独占输出，无需第二套 EQ（避免与 PcmDspAudioProcessor 双叠加）。
 */
internal object BassDspConfig {

    /** [3A回退开关·修审计#8] BASS 是否接管 DSP；false 时 BASS 只解码，DSP 交给自研 PcmDsp。 */
    @Volatile
    var bassDspEnabled: Boolean = true

    @Volatile
    private var eqSpecs: List<EqSpec> = emptyList()

    @Volatile
    private var reverbStrength: Float = 0f

    // [1.4.20·P1] BASS 侧音效（压限/声道平衡/音乐厅/V3混响/ReplayGain 固定增益）
    @Volatile private var compressorOn: Boolean = false
    @Volatile private var compressorAmt: Float = 0.4f
    @Volatile private var balance: Float = 0f
    @Volatile private var hallOn: Boolean = false
    @Volatile private var v3On: Boolean = false
    @Volatile private var rgDb: Float = 0f

    // [1.4.20·P2] 倒放/变速（BassPcmDataSource.open 读取）
    @Volatile private var reversePlay: Boolean = false
    @Volatile private var speedX100: Int = 100

    fun reverseEnabled(): Boolean = reversePlay
    fun playbackSpeedX100(): Int = speedX100

    /** 由 PcmDsp.configure 调用，同步当前设置。 */
    fun update(
        enabled: Boolean,
        bands: List<EqBand>?,
        reverb: Float,
        compressorEnabled: Boolean = false,
        compressorStrength: Float = 0.4f,
        channelBalance: Float = 0f,
        concertHall: Boolean = false,
        v3Reverb: Boolean = false,
        replayGainDb: Float = 0f,
    ) {
        eqSpecs = if (enabled && !bands.isNullOrEmpty()) bands.mapNotNull { it.toBassSpec() } else emptyList()
        reverbStrength = if (enabled) reverb.coerceIn(0f, 1f) else 0f
        // [闭环核查] 独立效果不依赖 EQ 的 enabled：用户选「原声」但开了压限/音乐厅/V3/平衡时仍生效。
        compressorOn = compressorEnabled
        compressorAmt = compressorStrength.coerceIn(0f, 1f)
        balance = channelBalance.coerceIn(-1f, 1f)
        hallOn = concertHall
        v3On = v3Reverb
        rgDb = replayGainDb
    }

    fun eqSpecs(): List<EqSpec> = if (bassDspEnabled) eqSpecs else emptyList()
    fun reverb(): Float = if (bassDspEnabled) reverbStrength else 0f
    fun compressor(): Pair<Boolean, Float> = if (bassDspEnabled) compressorOn to compressorAmt else false to 0f
    fun channelBalance(): Float = if (bassDspEnabled) balance else 0f
    fun hallReverb(): Boolean = bassDspEnabled && hallOn
    fun v3Reverb(): Boolean = bassDspEnabled && v3On
    fun replayGainDb(): Float = if (bassDspEnabled) rgDb else 0f

    /** 切歌时由 PcmDsp.applyTrackReplayGain 注入当前曲目的最终响度增益。 */
    fun setReplayGainDb(db: Float) {
        rgDb = db
    }

    /** 由 PcmDsp.configure 同步倒放/变速设置。 */
    fun setPlaybackMode(reverse: Boolean, speedEnabled: Boolean, speed: Int) {
        reversePlay = reverse
        speedX100 = if (speedEnabled) speed.coerceIn(50, 200) else 100
    }

    private fun EqBand.toBassSpec(): EqSpec? {
        val t = when (type) {
            EqFilterType.Peaking -> com.un4seen.bass.BASS_FX.BASS_BFX_BQF_PEAKINGEQ
            EqFilterType.LowShelf -> com.un4seen.bass.BASS_FX.BASS_BFX_BQF_LOWSHELF
            EqFilterType.HighShelf -> com.un4seen.bass.BASS_FX.BASS_BFX_BQF_HIGHSHELF
            EqFilterType.LowPass -> com.un4seen.bass.BASS_FX.BASS_BFX_BQF_LOWPASS
            EqFilterType.HighPass -> com.un4seen.bass.BASS_FX.BASS_BFX_BQF_HIGHPASS
        }
        return EqSpec(t, frequencyHz, gainDb, q)
    }
}
