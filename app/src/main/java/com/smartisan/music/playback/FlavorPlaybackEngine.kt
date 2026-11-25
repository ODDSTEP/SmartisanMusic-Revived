package com.smartisan.music.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import com.smartisan.music.data.settings.EngineModeStore

/**
 * 1.4.11 · S12 三包收敛单包后的**播放引擎运行时门面**。
 *
 * 三套真实引擎（[OfficialPlaybackEngine] / [DirectPlaybackEngine] /
 * [StreamPlaybackEngine]）全部编进同一 APK。保留历史调用名 `FlavorPlaybackEngine.buildPlayer`，
 * 按当前引擎模式委托。direct 模式返回系统 [MediaPlayerPlayer]（SimpleBasePlayer），
 * official/stream 返回 ExoPlayer——两者都实现 Media3 [Player]，会话层无感。
 *
 * 引擎在 Application.onCreate 时由 [EngineModeStore] 定死；运行中改模式在下次启动生效。
 */
internal object FlavorPlaybackEngine {

    fun buildPlayer(
        context: Context,
        renderersFactory: RenderersFactory,
        mediaSourceFactory: MediaSource.Factory,
        loadControl: LoadControl,
        audioAttributes: AudioAttributes,
    ): Player = when (EngineModeStore.current()) {
        EngineModeStore.Mode.DIRECT ->
            DirectPlaybackEngine.buildPlayer(
                context, renderersFactory, mediaSourceFactory, loadControl, audioAttributes
            )
        EngineModeStore.Mode.STREAM ->
            StreamPlaybackEngine.buildPlayer(
                context, renderersFactory, mediaSourceFactory, loadControl, audioAttributes
            )
        else ->
            OfficialPlaybackEngine.buildPlayer(
                context, renderersFactory, mediaSourceFactory, loadControl, audioAttributes
            )
    }
}
