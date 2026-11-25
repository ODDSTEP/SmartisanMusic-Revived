@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.media.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 真实送给播放设备的输出流参数。
 *
 * 数据来自 AudioTrack 初始化回调（AudioSink.Listener#onAudioTrackInitialized），
 * 反映的是系统最终写入硬件的采样率 / 声道 / PCM 编码，而不是歌曲文件自身的参数。
 */
data class AudioOutputSpec(
    /** 实际输出采样率（Hz）。系统做重采样时，这里是重采样后的值。 */
    val sampleRateHz: Int,
    /** 实际输出声道数。 */
    val channelCount: Int,
    /** 输出位深描述，如 "16bit"、"32bit 浮点"。 */
    val bitDepthLabel: String,
    /** AudioFormat 编码常量（原始值，供高级界面展示）。 */
    val encoding: Int,
    /** 是否为硬件直通（offload）模式。 */
    val offload: Boolean,
    /** 输出缓冲区字节数。 */
    val bufferSizeBytes: Int,
) {
    val isCompressedPassthrough: Boolean
        get() = offload || encoding == AudioFormat.ENCODING_AC3 || encoding == AudioFormat.ENCODING_E_AC3
}

/**
 * 全局输出参数监视器。播放服务侧写入，界面侧（音频输出面板）读取。
 */
object AudioOutputMonitor {

    private val _spec = MutableStateFlow<AudioOutputSpec?>(null)

    /** 最近一次建立的输出流参数；尚未播放或无法探测时为 null。 */
    val spec: StateFlow<AudioOutputSpec?> = _spec.asStateFlow()

    fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) {
        val spec = AudioOutputSpec(
            sampleRateHz = config.sampleRate,
            channelCount = channelCountOf(config.channelConfig),
            bitDepthLabel = bitDepthLabelOf(config.encoding),
            encoding = config.encoding,
            offload = config.offload,
            bufferSizeBytes = config.bufferSize,
        )
        _spec.value = spec
        AppLog.i(
            "AudioOut",
            "output: ${spec.sampleRateHz}Hz ${spec.channelCount}ch ${spec.bitDepthLabel}" +
                " bytes=${spec.bufferSizeBytes} offload=${spec.offload}",
        )
    }

    fun clear() {
        _spec.value = null
    }

    /** 声道掩码 → 声道数。优先使用标准布局，未知掩码按置位数量统计。 */
    fun channelCountOf(channelConfig: Int): Int = when (channelConfig) {
        AudioFormat.CHANNEL_OUT_MONO -> 1
        AudioFormat.CHANNEL_OUT_STEREO -> 2
        AudioFormat.CHANNEL_OUT_QUAD -> 4
        AudioFormat.CHANNEL_OUT_5POINT1, AudioFormat.CHANNEL_OUT_5POINT1POINT2 -> 6
        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.CHANNEL_OUT_7POINT1POINT4 -> 8
        else -> Integer.bitCount(channelConfig).coerceAtLeast(1)
    }

    /** PCM 编码 → 位深描述。 */
    fun bitDepthLabelOf(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "8bit"
        AudioFormat.ENCODING_PCM_16BIT -> "16bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "32bit 浮点"
        else -> "压缩直通"
    }
}
