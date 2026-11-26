package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import com.smartisan.music.diagnostics.AppLog

/**
 * A 测试版（direct）·系统直读 —— 只编进「锤子音乐·系统直读」APK。
 *
 * 1.4.1 起：主播放链已改为真·系统 MediaPlayer 出声（见 direct 源集的
 * [MediaPlayerPlayer] / [FlavorPlaybackEngine]），原始 Uri 交给
 * android.media.MediaPlayer 的 setDataSource → prepareAsync → start 整条链，
 * App 不转码、不落盘、不碰 PCM、零缓存。这是与参考 APK 同款的播放核心。
 *
 * 本类只承担「解码取流前端」契约里的元数据与路由标记：所有格式一律 SYSTEM_DIRECT，
 * 系统认得就点开即播；系统认不出由 MediaPlayerPlayer 抛明确 PlaybackException 并写
 * DirectEngine 日志，绝不偷偷转码、绝不自动回退。
 *
 * 如实边界（不粉饰）：系统 MediaPlayer 出声意味着 App 拿不到解码后 PCM，因此
 * 需要 PCM 域的功能——USB 独占 bit-perfect、自研 DSP、64bit 浮点 WAV——在 A 测试版
 * 默认链不可用；这三项在 B 正式版（ExoPlayer 自解码链）完整保留。
 */
internal object DirectDecodeFrontend : DecodeFrontend {

    override val engineSummary =
        "A测试版·系统直读：默认链真用系统MediaPlayer出声(setDataSource→prepare→start)，App不转码/不落盘/不碰PCM/零缓存"

    override val capabilityNote =
        "本版验证「系统MediaPlayer能直接出声多少格式、是否点开即播零缓存」。代价如实说明：依赖App自解码拿PCM的功能(USB独占bit-perfect/自研DSP/64bit浮点WAV)在A版默认链不可用；这三项在B正式版完整保留。"

    override val fallbackOnUnsupported = false
    override val directReadFirst = true

    override fun plan(context: Context, uri: Uri): DecodePlan = DecodePlan.SYSTEM_DIRECT

    /** 主链已由 MediaPlayerPlayer 承担，本方法不再旁路探测（保留契约返回 null）。 */
    override fun probeOnOpen(context: Context, uri: Uri): String? = null

    override fun selfProbe(context: Context, uri: Uri?): String =
        "A系统直读版自检: 主链=android.media.MediaPlayer出声(零缓存/不落盘/不碰PCM); " +
            "系统认不出的格式由DirectEngine日志明确失败，无ffmpeg转码缓存、无自动回退"
}
