@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.diagnostics.PlaybackDiagnostics
import java.io.IOException

/**
 * 播放链路埋点。
 *
 * 覆盖三段最容易出现"播不出来 / 有杂音"的地方：
 *  1. **读取**：容器解析失败、数据源读取失败；
 *  2. **解码**：用的是哪个解码器、输入格式到底是什么、是否被复用；
 *  3. **输出**：AudioTrack 初始化失败、缓冲 underrun。
 */
internal class PlaybackDiagnosticsListener(
    private val contextProvider: () -> android.content.Context,
) : AnalyticsListener {

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        elapsedRealtimeMs: Long,
        initializationDurationMs: Long,
    ) {
        AppLog.i("Decode", "decoder=$decoderName init=${initializationDurationMs}ms")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        AppLog.i("Decode", "input: ${formatLine(format)} reuse=${decoderReuseEvaluation?.result}")
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        counters: DecoderCounters,
    ) {
        AppLog.d(
            "Decode",
            "renderer disabled: queued=${counters.queuedInputBufferCount}" +
                " rendered=${counters.renderedOutputBufferCount}" +
                " skippedOut=${counters.skippedOutputBufferCount}" +
                " dropped=${counters.droppedBufferCount}" +
                " maxConsecutiveDropped=${counters.maxConsecutiveDroppedBufferCount}",
        )
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        AppLog.w(
            "AudioOut",
            "underrun(buffer=$bufferSize bytes/${bufferSizeMs}ms, gap=${elapsedSinceLastFeedMs}ms)",
        )
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        AppLog.e("AudioOut", "sink error: ${audioSinkError.message}", audioSinkError)
    }

    override fun onAudioSessionIdChanged(
        eventTime: AnalyticsListener.EventTime,
        audioSessionId: Int,
    ) {
        AppLog.d("AudioOut", "audio session id=$audioSessionId")
    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData,
    ) {
        val format = mediaLoadData.trackFormat ?: return
        AppLog.d("Decode", "container: ${formatLine(format)}")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        AppLog.e("Decode", "load error uri=${loadEventInfo.dataSpec.uri} canceled=$wasCanceled", error)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        AppLog.e("Player", "error ${PlaybackDiagnostics.errorLine(error)}")
    }

    private fun formatLine(format: Format): String {
        val codecs = format.codecs ?: ""
        return "mime=${format.sampleMimeType} codecs=$codecs" +
            " rate=${format.sampleRate} ch=${format.channelCount}" +
            " pcm=${format.pcmEncoding} bitrate=${format.bitrate}" +
            " id=${format.id ?: "-"}"
    }
}
