package com.smartisan.music.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * C 测试版（stream）引擎：流式 PCM 管道，但会话层仍是 ExoPlayer。
 * 差异在解码取流前端 [FlavorDecodeFrontend]，播放引擎仍是 ExoPlayer。
 */
internal object StreamPlaybackEngine {

    fun buildPlayer(
        context: Context,
        renderersFactory: RenderersFactory,
        mediaSourceFactory: MediaSource.Factory,
        loadControl: LoadControl,
        audioAttributes: AudioAttributes,
    ): Player {
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PlaylistPreloadDurationUs))
            }
    }

    // 与 PlaybackService 历史常量保持一致（30 秒预加载）。
    private const val PlaylistPreloadDurationUs = 30_000_000L
}
