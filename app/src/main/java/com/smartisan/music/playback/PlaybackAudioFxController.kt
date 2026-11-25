@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import com.smartisan.music.data.settings.AudioFxPreset
import com.smartisan.music.data.settings.EngineModeStore
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.AudioFxControlFrequenciesHz
import com.smartisan.music.data.settings.activeAudioFxGainDbPoints
import com.smartisan.music.data.settings.bassBoostStrength
import kotlin.math.ln

// [1.4.6·10段EQ] 控制频点改由 PlaybackSettingsStore 单一来源提供（10 个），
// 插值 interpolateGainDb 的 lastIndex / 段循环自动跟随，不再写死 5 段。

internal class PlaybackAudioFxController {
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var settings: PlaybackSettings = PlaybackSettings()
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    /**
     * 输出链是否允许挂载效果。高采样率输出（>48kHz）或硬件直通时，
     * 系统效果链会强制重采样/混音，部分机型产生杂音，此时需禁用全部音效。
     */
    @Volatile
    private var outputEffectsSafe: Boolean = true

    fun setOutputEffectsSafe(safe: Boolean) {
        if (outputEffectsSafe == safe) {
            return
        }
        outputEffectsSafe = safe
        applySettings()
    }

    fun setAudioSessionId(sessionId: Int) {
        if (audioSessionId == sessionId) {
            return
        }
        releaseEffects()
        audioSessionId = sessionId
        applySettings()
    }

    fun setSettings(settings: PlaybackSettings) {
        if (this.settings == settings) {
            return
        }
        this.settings = settings
        applySettings()
    }

    fun release() {
        releaseEffects()
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun applySettings() {
        if (!outputEffectsSafe) {
            releaseEffects()
            return
        }
        val preset = settings.activeAudioFxPreset()
        // [1.4.17·普通模式DSP] 官方/流式引擎（ExoPlayer）已在 DefaultAudioSink 注入自研
        // PcmDsp AudioProcessor，EQ/低音/混响由它在 PCM 域统一处理。此处必须禁用系统
        // Equalizer/BassBoost，否则同一份设置驱动两套 EQ 叠加，频响失真、增益翻倍、声音发闷。
        // 音量均衡 LoudnessEnhancer 与 DSP 不重叠，保留。
        // 判据用「当前引擎」而非 audioProcessorActive 标志：冷启动时 sink 尚未 configure、
        // 标志还没置位，但引擎已确定，避免中间态两套 EQ 同时响。DIRECT 引擎无 AudioProcessor，
        // 系统 EQ 照常工作。
        val dspHandlesFx = EngineModeStore.current() != EngineModeStore.Mode.DIRECT
        if (dspHandlesFx) {
            releaseEqualizer()
            releaseBassBoost()
            applyVolumeNormalization()
            return
        }
        if (audioSessionId <= 0 || preset == AudioFxPreset.Original) {
            releaseEffects()
            applyVolumeNormalization()
            return
        }

        applyEqualizer(settings.activeAudioFxGainDbPoints())
        applyBassBoost(preset)
        applyVolumeNormalization()
    }

    /**
     * 音量均衡：系统 LoudnessEnhancer 这条路径已停用（[1.4.28]）。
     *
     * 旧实现读 `volumeNormalizationEnabled` / `volumeNormalizationGainDb`，但这两个字段的
     * 设置入口早已合并掉，界面上没有任何开关；老用户 SP 里留着 true 时会持续叠加一层
     * 系统级增益，且与 PcmDsp 里的 lufs 归一化可能双重生效。现在响度归一化只由
     * PcmDsp 按 UI 上真实存在的 `replayGainFallbackEnabled` 处理，这里只负责释放旧效果。
     */
    private fun applyVolumeNormalization() {
        releaseLoudnessEnhancer()
    }

    private fun createLoudnessEnhancer(): LoudnessEnhancer? {
        return runCatching {
            LoudnessEnhancer(audioSessionId).also { effect ->
                loudnessEnhancer = effect
            }
        }.getOrNull()
    }

    private fun releaseLoudnessEnhancer() {
        loudnessEnhancer?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        loudnessEnhancer = null
    }

    private fun applyEqualizer(gains: FloatArray) {
        val effect = equalizer ?: createEqualizer() ?: return
        val range = runCatching { effect.bandLevelRange }.getOrNull()
        val minLevel = range?.getOrNull(0)?.toInt() ?: return
        val maxLevel = range.getOrNull(1)?.toInt() ?: return
        val bandCount = runCatching { effect.numberOfBands.toInt() }.getOrDefault(0)
        if (bandCount <= 0) {
            runCatching { effect.enabled = false }
            return
        }

        for (band in 0 until bandCount) {
            val bandIndex = band.toShort()
            val centerHz = runCatching {
                effect.getCenterFreq(bandIndex).toFloat() / 1000f
            }.getOrDefault(AudioFxControlFrequenciesHz[band.coerceAtMost(AudioFxControlFrequenciesHz.lastIndex)])
            val gainMb = (interpolateGainDb(centerHz, gains) * 100f)
                .toInt()
                .coerceIn(minLevel, maxLevel)
                .toShort()
            runCatching {
                effect.setBandLevel(bandIndex, gainMb)
            }
        }
        runCatching {
            effect.enabled = true
        }.onFailure {
            releaseEqualizer()
        }
    }

    private fun applyBassBoost(preset: AudioFxPreset) {
        val strength = preset.bassBoostStrength()
        val effect = bassBoost ?: createBassBoost() ?: return
        runCatching {
            if (effect.strengthSupported) {
                effect.setStrength(strength)
            }
            effect.enabled = strength > 0
        }.onFailure {
            releaseBassBoost()
        }
    }

    private fun createEqualizer(): Equalizer? {
        return runCatching {
            Equalizer(0, audioSessionId).also { effect ->
                equalizer = effect
            }
        }.getOrNull()
    }

    private fun createBassBoost(): BassBoost? {
        return runCatching {
            BassBoost(0, audioSessionId).also { effect ->
                bassBoost = effect
            }
        }.getOrNull()
    }

    private fun releaseEffects() {
        releaseEqualizer()
        releaseBassBoost()
        releaseLoudnessEnhancer()
    }

    private fun releaseEqualizer() {
        equalizer?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        equalizer = null
    }

    private fun releaseBassBoost() {
        bassBoost?.let { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
        bassBoost = null
    }

    private fun PlaybackSettings.activeAudioFxPreset(): AudioFxPreset {
        return if (audioFxEnabled) audioFxPreset else AudioFxPreset.Original
    }

    private fun interpolateGainDb(
        centerHz: Float,
        gains: FloatArray,
    ): Float {
        val safeHz = centerHz.coerceAtLeast(AudioFxControlFrequenciesHz.first())
        if (safeHz <= AudioFxControlFrequenciesHz.first()) {
            return gains.first()
        }
        if (safeHz >= AudioFxControlFrequenciesHz.last()) {
            return gains.last()
        }

        for (index in 0 until AudioFxControlFrequenciesHz.lastIndex) {
            val startHz = AudioFxControlFrequenciesHz[index]
            val endHz = AudioFxControlFrequenciesHz[index + 1]
            if (safeHz in startHz..endHz) {
                val fraction = (
                    (ln(safeHz) - ln(startHz)) /
                        (ln(endHz) - ln(startHz))
                    ).coerceIn(0f, 1f)
                return gains[index] + (gains[index + 1] - gains[index]) * fraction
            }
        }
        return gains.last()
    }
}
