package com.smartisan.music.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * A 测试版（direct）引擎：默认链真·系统 MediaPlayer 出声。
 *
 * 与参考 APK 同款播放核心：原始 Uri 交给 android.media.MediaPlayer 整条链出声，
 * App 不转码、不落盘、不碰 PCM、零缓存。renderers/mediaSource/loadControl 是
 * ExoPlayer 专属，本版不碰。
 *
 * 说明：USB 独占 / 自研 DSP / 64bit 浮点 WAV 等需要「App 拿解码后 PCM」的场景，
 * 本版默认链（MediaPlayer）给不了——这些功能在 B 正式版完整保留；A 包如实标注。
 */
internal object DirectPlaybackEngine {

    fun buildPlayer(
        context: Context,
        renderersFactory: RenderersFactory,
        mediaSourceFactory: MediaSource.Factory,
        loadControl: LoadControl,
        audioAttributes: AudioAttributes,
    ): Player {
        return MediaPlayerPlayer(context, Looper.getMainLooper(), audioAttributes)
    }
}
