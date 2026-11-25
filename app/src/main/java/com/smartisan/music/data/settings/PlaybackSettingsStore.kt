package com.smartisan.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val PlaybackSettingsStoreName = "playback_settings"

private val Context.playbackSettingsDataStore by preferencesDataStore(
    name = PlaybackSettingsStoreName,
)

data class PlaybackSettings(
    val scratchEnabled: Boolean = true,
    val hidePlayerAxisEnabled: Boolean = false,
    // 1.3.3：撤销 1.3.2 擅自改成默认 true 的改动，恢复原始 0.3.0 的口径——默认关闭、
    // 由设置页开关控制。用户明确要求「按原始口径恢复，别把之前做好的正常功能改了」。
    // 1.3.2 当时在 .kt 里搜不到中文字面量就推理成「功能没注册」，其实是名字在
    // strings.xml 里（R.string.popcorn_sound），功能与 20 个 y_*.ogg 采样一直都在。
    val popcornSoundEnabled: Boolean = false,
    // [1.4.20·摒弃均衡器开关] 用户要求「原声就是原声、预设就是预设，自己调没有开关状态」。
    // 原先默认 false 形成隐藏总闸：UI 已隐藏开关，预设行却因 fxUsable 依赖本字段全灰，
    // 而唯一置 true 的途径又是点预设 → 死锁。现默认 true，DSP 恒可用、原声靠全平曲线
    // 自然表现，字段本身保留（其它模块仍读它）。
    val audioFxEnabled: Boolean = true,
    val audioFxPreset: AudioFxPreset = AudioFxPreset.Original,
    /**
     * [1.4.18·均衡器] 选中的 Salt V4 / AutoEQ 耳机预设 key。
     * 非空时优先于 audioFxPreset：DSP 从 SaltEqCatalog 取该 key 的 10 段参数。
     * 内置预设仍走 audioFxPreset。这样 UI 可按分类自由列出耳机预设，不必塞进枚举。
     */
    val eqPresetKey: String = "",
    val audioFxCustomGainDbPoints: List<Float> = AudioFxDefaultCustomGainDbPoints,
    /**
     * 混响强度（1.4.12·S05）：独立于音效预设，用户可在音效页直接选。
     * 默认 FollowPreset 保持旧行为（按预设写死配比），选关/弱/中/强则覆盖。
     */
    val audioFxReverbMode: AudioFxReverbMode = AudioFxReverbMode.FollowPreset,
    /**
     * USB 独占：接管 USB 声卡（UAC 直出），绕开系统混音与重采样。
     * 这是**真独占**——内核驱动被从接口上摘掉，其它 App 拿不到这块声卡。
     */
    val usbExclusiveEnabled: Boolean = false,
    /**
     * 插入音频设备自动续播：接入有线耳机 / USB 声卡时自动恢复播放。
     */
    val plugAutoResumeEnabled: Boolean = false,
    /** 1.3.7：过滤 DSD 格式音频（默认关），已移入「播放体验」卡片。 */
    val excludeDsdEnabled: Boolean = false,

    // ===== 1.4.20·举一反三新增（P0/P1/P2 全做，全部用户可见可开关）=====
    /** [P1·ReplayGain] 是否按文件 ReplayGain 标签做响度平衡。 */
    val replayGainEnabled: Boolean = false,
    /** [P1·ReplayGain] 无标签时的回退响度归一化（等效旧的音量均衡），独立于标签增益。 */
    val replayGainFallbackEnabled: Boolean = false,
    /** [P1·ReplayGain] 目标增益 dB（-6..+6），叠加在标签增益之上。 */
    val replayGainGainDb: Int = 0,

    /** [P1·压限器] 启用压限器（防削波、压动态）。 */
    val compressorEnabled: Boolean = false,
    /** [P1·压限器] 强度 0..100：越高阈值越低、压缩比越大。 */
    val compressorStrength: Int = 40,
    /** [P1·音乐厅] 音乐厅空间效果（比普通混响更强的厅堂残响）。 */
    val concertHallEnabled: Boolean = false,
    /** [P1·声道平衡] -100(全左)..0(居中)..+100(全右)。 */
    val channelBalance: Int = 0,
    /** [P1·V3混响] 增强版混响算法（多条早期反射 + 更长尾音），独立于基础混响档。 */
    val v3ReverbEnabled: Boolean = false,

    /** [P0·增益可视化] 在音频输出面板显示实时峰值/余量/削波计数。 */
    val gainMeterEnabled: Boolean = false,

    /** [P2·倒放] 倒序播放当前曲目（PCM 帧倒序缓冲）。 */
    val reverseEnabled: Boolean = false,
    /** [P2·变速] 变速不变调播放开关。 */
    val variSpeedEnabled: Boolean = false,
    /** [P2·变速] 播放速度 0.5x..2.0x（以 0.05 为步进的整数档 x100：50..200）。 */
    val playbackSpeedX100: Int = 100,

    /** [P0·播放/暂停淡入淡出] 起播淡入、暂停淡出，根治爆豆音。默认开。 */
    val playPauseFadeEnabled: Boolean = true,
    /** [3A回退开关·修审计#8] BASS DSP 出问题时可切回自研 PcmDsp。默认 true=BASS 生效。 */
    val bassDspEnabled: Boolean = true,

    /** [1.4.21·P2] 软件音量↔系统音量条平滑接力+读回确认。默认开，消除音量跳变爆音。 */
    val smoothVolumeHandoffEnabled: Boolean = true,
)

/** 1.4.12·S05 混响强度：跟随预设 / 关闭 / 弱 / 中 / 强。 */
enum class AudioFxReverbMode(
    val preferenceValue: String,
) {
    FollowPreset("follow_preset"),
    Off("off"),
    Weak("weak"),
    Medium("medium"),
    Strong("strong");

    companion object {
        fun fromPreference(value: String?): AudioFxReverbMode =
            entries.firstOrNull { it.preferenceValue == value } ?: FollowPreset
    }
}

/** [P1·ReplayGain] 响度平衡模式：优先读标签 / 强制用固定增益 / 关闭。 */
enum class ReplayGainMode(
    val preferenceValue: String,
) {
    Off("off"),
    Track("track"),
    Album("album");

    companion object {
        fun fromPreference(value: String?): ReplayGainMode =
            entries.firstOrNull { it.preferenceValue == value } ?: Off
    }
}

enum class AudioFxPreset(
    val preferenceValue: String,
    /** [1.4.16·E2] 对应 Salt Player 预设 key；旧预设为 null（走 10 段固定频点路径）。 */
    val saltKey: String? = null,
) {
    Original("original"),
    Bass("bass"),
    Clear("clear"),
    Vocal("vocal"),
    Rock("rock"),
    Custom("custom"),
    // [1.4.16·E2] Salt Player 30 个源码级预设（含耳机/场景向）
    SaltStudioReference("salt_studio_reference", "studio_reference"),
    SaltMixTranslation("salt_mix_translation", "mix_translation"),
    SaltLowMidCleanup("salt_low_mid_cleanup", "low_mid_cleanup"),
    SaltSmoothTreble("salt_smooth_treble", "smooth_treble"),
    SaltAirAndDetail("salt_air_and_detail", "air_and_detail"),
    SaltVinyl("salt_vinyl", "vinyl"),
    SaltBassBoost("salt_bass_boost", "bass_boost"),
    SaltClearVocals("salt_clear_vocals", "clear_vocals"),
    SaltFemaleVocals("salt_female_vocals", "female_vocals"),
    SaltMaleVocals("salt_male_vocals", "male_vocals"),
    SaltAcoustic("salt_acoustic", "acoustic"),
    SaltLiveFolk("salt_live_folk", "live_folk"),
    SaltAncientStyle("salt_ancient_style_stereo", "ancient_style_stereo"),
    SaltConcert("salt_concert", "concert"),
    SaltMusicHall("salt_music_hall", "music_hall"),
    SaltClassical("salt_classical", "classical"),
    SaltJazz("salt_jazz", "jazz"),
    SaltCinematic("salt_cinematic", "cinematic"),
    SaltAcg("salt_acg", "acg"),
    SaltHipHop("salt_hip_hop", "hip_hop"),
    SaltElectronic("salt_electronic", "electronic"),
    SaltDance("salt_dance", "dance"),
    SaltRock("salt_rock", "rock"),
    SaltMetal("salt_metal", "metal"),
    SaltSubBass("salt_ultra_sub_bass", "ultra_sub_bass"),
    SaltWarm("salt_warm", "warm"),
    SaltBright("salt_bright", "bright"),
    SaltLoFi("salt_lo_fi", "lo_fi"),
    SaltLateNight("salt_late_night", "late_night");

    val isSaltPreset: Boolean get() = saltKey != null

    companion object {
        fun fromPreference(value: String?): AudioFxPreset {
            return entries.firstOrNull { preset -> preset.preferenceValue == value } ?: Original
        }
    }
}

/** [1.4.18·均衡器] 当前预设对应的 Salt 参数化 EQ band；非 Salt 预设返回 null，DSP 走原 10 段路径。
 *  优先用 eqPresetKey（V4/AutoEQ 耳机预设），否则用内置预设的 saltKey。 */
internal fun PlaybackSettings.activeEqBands(): List<EqBand>? {
    val key = eqPresetKey
    if (key.isNotBlank()) {
        SaltEqPresets.bands[key]?.let { return it }
        SaltEqCatalog.v4Bands[key]?.let { return it }
        SaltEqCatalog.autoEqBands[key]?.let { return it }
    }
    return audioFxPreset.saltKey?.let { SaltEqPresets.bandsFor(it) }
}

// [护栏 G-29·1.4.6 落地] 控制点 10 段、频点单一来源、旧 5 点数据平滑迁移不丢值，严禁写死 5
// [1.4.6·10段EQ] 控制频点扩到 10 个（标准 10 段），全链路按 AudioFxBandCount 驱动。
internal const val AudioFxBandCount = 10
internal const val AudioFxMinGainDb = -12f
internal const val AudioFxMaxGainDb = 12f
/** 10 段控制频点（Hz）：31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k。 */
internal val AudioFxControlFrequenciesHz =
    floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
internal val AudioFxDefaultCustomGainDbPoints =
    listOf(2.0f, 1.6f, 0.8f, 1.2f, 2.8f, 2.2f, 1.7f, 1.3f, 0.9f, 1.1f)

internal fun normalizeAudioFxGainDbPoints(gains: List<Float>): List<Float> {
    return List(AudioFxBandCount) { index ->
        (gains.getOrNull(index) ?: AudioFxDefaultCustomGainDbPoints[index])
            .coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb)
    }
}

/**
 * [1.4.6·10段EQ·旧数据平滑迁移] 1.4.5 及更早的自定义 EQ 存的是 5 个点
 * （60/230/910/4k/14k）。直接套 normalize 会让前 5 个值落到 31~500Hz 的新频点上、
 * 后 5 个补默认值，用户调过的曲线形状全乱。这里按**对数频率**把旧 5 点插值到
 * 新 10 个频点，尽量保住用户已调的低/中/高频走势，不丢值、不重置为全平。
 * 已经是 10 点的（新版本写入的）原样 normalize，不重复插值。
 */
internal fun migrateAudioFxCustomGainDbPoints(gains: List<Float>): List<Float> {
    if (gains.size == AudioFxBandCount) return normalizeAudioFxGainDbPoints(gains)
    if (gains.isEmpty()) return AudioFxDefaultCustomGainDbPoints
    // 旧点按旧 5 段频点的对数坐标铺开
    val oldHz = floatArrayOf(60f, 230f, 910f, 4_000f, 14_000f)
    val clean = List(gains.size) { gains[it].coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) }
    val lnOld = oldHz.take(clean.size).map { kotlin.math.ln(it) }
    val newHz = AudioFxControlFrequenciesHz
    return List(AudioFxBandCount) { i ->
        val x = kotlin.math.ln(newHz[i])
        when {
            clean.size == 1 -> clean[0]
            x <= lnOld.first() -> clean.first()
            x >= lnOld.last() -> clean.last()
            else -> {
                var seg = 0
                // [1.4.28] 上界必须是 lastIndex：旧写法 lastIndex-1 让 seg 最多停在倒数第二段，
                // 8000Hz 落在 [4000,14000] 却用 [910,4000] 插值、frac 被 clamp 到 1 → 直接取 4k 的值，
                // 老用户升级后 8k 点整段塌陷。
                while (seg < lnOld.lastIndex && x > lnOld[seg + 1]) seg++
                val frac = ((x - lnOld[seg]) / (lnOld[seg + 1] - lnOld[seg])).coerceIn(0f, 1f)
                (clean[seg] + (clean[seg + 1] - clean[seg]) * frac)
                    .coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb)
            }
        }
    }
}

internal fun PlaybackSettings.activeAudioFxGainDbPoints(): FloatArray {
    return audioFxPreset.equalizerGainDbPoints(audioFxCustomGainDbPoints)
}

internal fun AudioFxPreset.equalizerGainDbPoints(
    customGainDbPoints: List<Float> = AudioFxDefaultCustomGainDbPoints,
): FloatArray {
    return when (this) {
        AudioFxPreset.Original -> FloatArray(AudioFxBandCount) { 0f }
        AudioFxPreset.Bass -> floatArrayOf(5.5f, 4.8f, 3.2f, 2.0f, 0.6f, -0.2f, -0.5f, -0.8f, -0.6f, -0.3f)
        AudioFxPreset.Clear -> floatArrayOf(-1.8f, -1.4f, -0.6f, 0.4f, 1.2f, 2.0f, 2.8f, 3.4f, 3.1f, 2.7f)
        AudioFxPreset.Vocal -> floatArrayOf(-1.2f, -0.6f, 0.0f, 0.5f, 1.8f, 3.4f, 3.0f, 2.1f, 0.4f, -0.7f)
        AudioFxPreset.Rock -> floatArrayOf(3.2f, 2.6f, 1.4f, 0.2f, -0.6f, -1.1f, 0.4f, 2.2f, 3.4f, 3.8f)
        AudioFxPreset.Custom -> normalizeAudioFxGainDbPoints(customGainDbPoints).toFloatArray()
        // [1.4.16·E2] Salt 预设走参数化 band（见 PcmDsp），这里返回全平，仅作兜底。
        else -> FloatArray(AudioFxBandCount) { 0f }
    }
}

internal fun AudioFxPreset.bassBoostStrength(): Short {
    return when (this) {
        AudioFxPreset.Original -> 0
        AudioFxPreset.Bass -> 620
        AudioFxPreset.Clear -> 80
        AudioFxPreset.Vocal -> 120
        AudioFxPreset.Rock -> 320
        AudioFxPreset.Custom -> 0
        else -> 0
    }.toShort()
}

class PlaybackSettingsStore(
    private val context: Context,
) {

    // [1.4.19·代码质量#3] 修复切引擎后 usbExclusiveEnabled 陈旧 bug：
    // 原实现用 EngineModeStore.current() 快照算该值，切引擎不触发 datastore 发射 → 值永久陈旧，
    // 而 UI 用 collectAsState 订阅了引擎 flow，导致"界面已切换、服务层还拿旧值"。
    // 改为 combine 订阅引擎 flow：引擎切换会让本 flow 重新发射并按需重算独占开关。
    val settings: Flow<PlaybackSettings> = combine(
        context.playbackSettingsDataStore.data,
        EngineModeStore.mode,
    ) { preferences, engineMode ->
            PlaybackSettings(
                scratchEnabled = preferences[ScratchEnabledKey] ?: true,
                hidePlayerAxisEnabled = preferences[HidePlayerAxisEnabledKey] ?: false,
                popcornSoundEnabled = preferences[PopcornSoundEnabledKey] ?: false,
                // [1.4.20] 默认 true：无隐藏总闸，DSP 恒可用；原声靠全平曲线表现，不靠 false 关掉。
                audioFxEnabled = preferences[AudioFxEnabledKey] ?: true,
                audioFxPreset = AudioFxPreset.fromPreference(preferences[AudioFxPresetKey]),
                eqPresetKey = preferences[EqPresetKeyKey] ?: "",
                audioFxCustomGainDbPoints = parseAudioFxCustomGainDbPoints(
                    preferences[AudioFxCustomGainDbPointsKey],
                ),
                audioFxReverbMode = AudioFxReverbMode.fromPreference(
                    preferences[AudioFxReverbModeKey],
                ),
                // 1.4.11 S12 单包：A 引擎（系统直读）拿不到 PCM，USB 独占是假会话，强制关。
                // [1.4.19] 用 combine 传入的 engineMode（实时）——切引擎立即重算，不再陈旧。
                usbExclusiveEnabled =
                    if (engineMode == EngineModeStore.Mode.DIRECT) false
                    else preferences[UsbExclusiveEnabledKey] ?: false,
                plugAutoResumeEnabled = preferences[PlugAutoResumeEnabledKey] ?: false,
                excludeDsdEnabled = preferences[ExcludeDsdEnabledKey] ?: false,
                // ===== 1.4.20 新增 =====
                replayGainEnabled = preferences[ReplayGainEnabledKey] ?: false,
                replayGainFallbackEnabled = preferences[ReplayGainFallbackEnabledKey] ?: false,
                replayGainGainDb = (preferences[ReplayGainGainDbKey] ?: 0).coerceIn(-6, 6),
                compressorEnabled = preferences[CompressorEnabledKey] ?: false,
                compressorStrength = (preferences[CompressorStrengthKey] ?: 40).coerceIn(0, 100),
                concertHallEnabled = preferences[ConcertHallEnabledKey] ?: false,
                channelBalance = (preferences[ChannelBalanceKey] ?: 0).coerceIn(-100, 100),
                v3ReverbEnabled = preferences[V3ReverbEnabledKey] ?: false,
                gainMeterEnabled = preferences[GainMeterEnabledKey] ?: false,
                reverseEnabled = preferences[ReverseEnabledKey] ?: false,
                variSpeedEnabled = preferences[VariSpeedEnabledKey] ?: false,
                playbackSpeedX100 = (preferences[PlaybackSpeedX100Key] ?: 100).coerceIn(50, 200),
                playPauseFadeEnabled = preferences[PlayPauseFadeEnabledKey] ?: true,
                bassDspEnabled = preferences[BassDspEnabledKey] ?: true,
                smoothVolumeHandoffEnabled = preferences[SmoothVolumeHandoffEnabledKey] ?: true,
            )
        }
        .distinctUntilChanged()

    suspend fun setScratchEnabled(enabled: Boolean) {
        setBoolean(ScratchEnabledKey, enabled)
    }

    suspend fun setExcludeDsdEnabled(enabled: Boolean) {
        setBoolean(ExcludeDsdEnabledKey, enabled)
    }

    // ===== 1.4.20 新增 setter（P0/P1/P2）=====
    suspend fun setReplayGainEnabled(enabled: Boolean) = setBoolean(ReplayGainEnabledKey, enabled)
    suspend fun setReplayGainFallbackEnabled(enabled: Boolean) = setBoolean(ReplayGainFallbackEnabledKey, enabled)
    suspend fun setReplayGainGainDb(db: Int) = setInt(ReplayGainGainDbKey, db.coerceIn(-6, 6))
    suspend fun setCompressorEnabled(enabled: Boolean) = setBoolean(CompressorEnabledKey, enabled)
    suspend fun setCompressorStrength(v: Int) = setInt(CompressorStrengthKey, v.coerceIn(0, 100))
    suspend fun setConcertHallEnabled(enabled: Boolean) = setBoolean(ConcertHallEnabledKey, enabled)
    suspend fun setChannelBalance(v: Int) = setInt(ChannelBalanceKey, v.coerceIn(-100, 100))
    suspend fun setV3ReverbEnabled(enabled: Boolean) = setBoolean(V3ReverbEnabledKey, enabled)
    suspend fun setGainMeterEnabled(enabled: Boolean) = setBoolean(GainMeterEnabledKey, enabled)
    suspend fun setReverseEnabled(enabled: Boolean) = setBoolean(ReverseEnabledKey, enabled)
    suspend fun setVariSpeedEnabled(enabled: Boolean) = setBoolean(VariSpeedEnabledKey, enabled)
    suspend fun setPlaybackSpeedX100(v: Int) = setInt(PlaybackSpeedX100Key, v.coerceIn(50, 200))
    suspend fun setPlayPauseFadeEnabled(enabled: Boolean) = setBoolean(PlayPauseFadeEnabledKey, enabled)
    suspend fun setBassDspEnabled(enabled: Boolean) = setBoolean(BassDspEnabledKey, enabled)
    suspend fun setSmoothVolumeHandoffEnabled(enabled: Boolean) = setBoolean(SmoothVolumeHandoffEnabledKey, enabled)

    suspend fun setHidePlayerAxisEnabled(enabled: Boolean) {
        setBoolean(HidePlayerAxisEnabledKey, enabled)
    }

    suspend fun setPopcornSoundEnabled(enabled: Boolean) {
        setBoolean(PopcornSoundEnabledKey, enabled)
    }

    suspend fun setAudioFxEnabled(enabled: Boolean) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[AudioFxEnabledKey] = enabled
            // 1.2.26：旧版选过「原声」后 preset 被写成 Original、enabled 被写成 false；
            // 用户再打开开关时 preset 仍是 Original，曲线全平 → 「开关开了却没有任何效果」。
            // 这里在开启时兜底落到 Custom（用户自己调的那条曲线，默认值很温和），
            // 用户随后可以直接改预设。
            val current = AudioFxPreset.fromPreference(preferences[AudioFxPresetKey])
            if (enabled && current == AudioFxPreset.Original) {
                preferences[AudioFxPresetKey] = AudioFxPreset.Custom.preferenceValue
            }
        }
    }

    suspend fun setUsbExclusiveEnabled(enabled: Boolean) {
        setBoolean(UsbExclusiveEnabledKey, enabled)
    }

    suspend fun setPlugAutoResumeEnabled(enabled: Boolean) {
        setBoolean(PlugAutoResumeEnabledKey, enabled)
    }

    suspend fun setAudioFxPreset(preset: AudioFxPreset) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[AudioFxPresetKey] = preset.preferenceValue
            preferences[AudioFxEnabledKey] = preset != AudioFxPreset.Original
            // [1.4.18] 选内置预设时清空耳机预设 key，避免两者叠加。
            preferences[EqPresetKeyKey] = ""
        }
    }

    /** [1.4.18·均衡器] 选中 V4/AutoEQ 耳机预设；空 key = 回到内置原声。 */
    suspend fun setEqPresetKey(key: String) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[EqPresetKeyKey] = key
            preferences[AudioFxEnabledKey] = key.isNotBlank()
            if (key.isBlank()) {
                preferences[AudioFxPresetKey] = AudioFxPreset.Original.preferenceValue
            }
        }
    }

    suspend fun setAudioFxCustomGainDbPoints(gains: List<Float>) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[AudioFxCustomGainDbPointsKey] = serializeAudioFxCustomGainDbPoints(gains)
            preferences[AudioFxPresetKey] = AudioFxPreset.Custom.preferenceValue
            preferences[AudioFxEnabledKey] = true
        }
    }

    /**
     * [1.4.23·混响解耦] 混响强度独立持久化，**绝不再改动 EQ 预设/音效总闸**。
     * 旧版这里在选弱/中/强且音效未开时，把 Original 强制翻成 Custom 并打开 audioFxEnabled，
     * 于是"选混响 → 上面 EQ 自动跳自定义""恢复原声 → 混响跟着失效"——用户以为混响没开。
     * DSP 侧 PcmDsp.configure 早已用 reverbExplicitlyEnabled/anyEffectActive 支持
     * "原声 + 独立混响"，混响只需写自己的 key 即可生效，不需要借用 EQ 总闸。
     */
    suspend fun setAudioFxReverbMode(mode: AudioFxReverbMode) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[AudioFxReverbModeKey] = mode.preferenceValue
        }
    }

    private suspend fun setBoolean(
        key: Preferences.Key<Boolean>,
        value: Boolean,
    ) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    private suspend fun setInt(
        key: Preferences.Key<Int>,
        value: Int,
    ) {
        context.playbackSettingsDataStore.edit { preferences ->
            preferences[key] = value
        }
    }
}

private fun parseAudioFxCustomGainDbPoints(value: String?): List<Float> {
    if (value.isNullOrBlank()) {
        return AudioFxDefaultCustomGainDbPoints
    }
    val parsed = value.split(",")
        .mapNotNull { item -> item.trim().toFloatOrNull() }
    // [1.4.6·10段EQ] 旧 5 点存储读出来先平滑迁移到 10 点，再 normalize。
    return migrateAudioFxCustomGainDbPoints(parsed)
}

private fun serializeAudioFxCustomGainDbPoints(gains: List<Float>): String {
    return normalizeAudioFxGainDbPoints(gains).joinToString(",") { gain ->
        gain.toString()
    }
}

private val ScratchEnabledKey = booleanPreferencesKey("scratch_enabled")
private val HidePlayerAxisEnabledKey = booleanPreferencesKey("hide_player_axis_enabled")
private val PopcornSoundEnabledKey = booleanPreferencesKey("popcorn_sound_enabled")
private val AudioFxEnabledKey = booleanPreferencesKey("audio_fx_enabled")
private val AudioFxPresetKey = stringPreferencesKey("audio_fx_preset")
private val EqPresetKeyKey = stringPreferencesKey("eq_preset_key")
private val AudioFxCustomGainDbPointsKey = stringPreferencesKey("audio_fx_custom_gain_db_points")
private val AudioFxReverbModeKey = stringPreferencesKey("audio_fx_reverb_mode")
private val VolumeNormalizationGainDbKey =
    androidx.datastore.preferences.core.intPreferencesKey("volume_normalization_gain_db")
private val UsbExclusiveEnabledKey = booleanPreferencesKey("usb_exclusive_enabled")
private val PlugAutoResumeEnabledKey = booleanPreferencesKey("plug_auto_resume_enabled")
private val ExcludeDsdEnabledKey = booleanPreferencesKey("exclude_dsd_enabled")
// ===== 1.4.20 新增 key =====
private val ReplayGainEnabledKey = booleanPreferencesKey("replay_gain_enabled")
private val ReplayGainFallbackEnabledKey = booleanPreferencesKey("replay_gain_fallback_enabled")
private val ReplayGainGainDbKey = androidx.datastore.preferences.core.intPreferencesKey("replay_gain_gain_db")
private val CompressorEnabledKey = booleanPreferencesKey("compressor_enabled")
private val CompressorStrengthKey = androidx.datastore.preferences.core.intPreferencesKey("compressor_strength")
private val ConcertHallEnabledKey = booleanPreferencesKey("concert_hall_enabled")
private val ChannelBalanceKey = androidx.datastore.preferences.core.intPreferencesKey("channel_balance")
private val V3ReverbEnabledKey = booleanPreferencesKey("v3_reverb_enabled")
private val GainMeterEnabledKey = booleanPreferencesKey("gain_meter_enabled")
private val ReverseEnabledKey = booleanPreferencesKey("reverse_enabled")
private val VariSpeedEnabledKey = booleanPreferencesKey("vari_speed_enabled")
private val PlaybackSpeedX100Key = androidx.datastore.preferences.core.intPreferencesKey("playback_speed_x100")
private val PlayPauseFadeEnabledKey = booleanPreferencesKey("play_pause_fade_enabled")
private val BassDspEnabledKey = booleanPreferencesKey("bass_dsp_enabled")
private val SmoothVolumeHandoffEnabledKey = booleanPreferencesKey("smooth_volume_handoff_enabled")
