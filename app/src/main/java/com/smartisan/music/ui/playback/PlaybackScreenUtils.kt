package com.smartisan.music.ui.playback

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.smartisan.music.R
import com.smartisan.music.platform.media.FormatCache
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.playback.LocalAudioLibrary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 解析播放时长：优先用播放器真实时长；流式转码（DFF/DSF 等）期间 [Player.getDuration]
 * 为 0/TIME_UNSET，此时回退到 MediaItem 元数据的探测时长；
 * 若元数据也因首播时探测尚未完成而为 0，再兜底查一次 [FormatCache]（其键含时长，
 * 首播时元数据时长恰为 0，与探测写入键一致，可命中），保证进度条不会冻结在 0。
 */
private fun resolveDurationMs(player: Player): Long {
    val fromPlayer = player.duration.takeIf { it > 0L }
    if (fromPlayer != null) return fromPlayer
    val mediaItem = player.currentMediaItem ?: return 0L
    val metadataDuration = mediaItem.mediaMetadata.durationMs?.takeIf { it > 0L }
    if (metadataDuration != null) return metadataDuration
    val uri = mediaItem.localConfiguration?.uri?.toString() ?: return 0L
    val probeKeyDuration = mediaItem.mediaMetadata.durationMs ?: 0L
    return runCatching { FormatCache.get(uri, probeKeyDuration)?.durationMs?.takeIf { it > 0L } }
        .getOrNull() ?: 0L
}

/**
 * 1.2.27：拖动/打碟统一走**原生 seek**。
 *
 * 旧版在「不可 seek」时会发 `RESELECT_CURRENT_ITEM` 让服务端重建当前项再定位——这条路径
 * 是 1.2.25~1.2.26「拖动弹回 / 拖几次就播放失败」的主要来源：重建会拆掉正在读的 MediaSource，
 * 被取消的读取被 Media3 归类成 `ERROR_CODE_PARSING_CONTAINER_MALFORMED`，直接判曲目失败。
 *
 * 现在 DFF/APE 一律等转码完成才交给播放器，文件天然带 SEEKTABLE，`isCurrentMediaItemSeekable`
 * 恒为 true，重建逻辑已经没有存在意义，彻底删掉。
 */
internal fun MediaController.seekPlaybackTo(positionMs: Long) {
    val target = positionMs.coerceAtLeast(0L)
    if (!isCurrentMediaItemSeekable) {
        // 转换中（文件还没生成）：此刻 seekTo 会被记成「待应用的初始位置」，
        // 等转码完成、MediaSource 装好时由 ExoPlayer 自动落到目标点——不要直接丢弃。
        com.smartisan.music.diagnostics.AppLog.i(
            "Seek",
            "当前不可 seek（转换中），记为待定位 target=$target state=$playbackState",
        )
    }
    seekTo(target)
}

internal fun Player?.snapshot(volume: Float = 1f): PlaybackScreenState {
    val player = this ?: return PlaybackScreenState()
    return PlaybackScreenState(
        mediaItem = player.currentMediaItem,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        repeatMode = player.repeatMode,
        shuffleEnabled = player.shuffleModeEnabled,
        currentPositionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = resolveDurationMs(player),
        volume = volume,
    )
}

internal fun MediaItem.resolveDeleteTarget(): PlaybackDeleteTargetResult {
    val targetMediaId = mediaId.trim()
    if (targetMediaId.isEmpty()) {
        return PlaybackDeleteTargetResult.Unavailable
    }
    val audioQualityBadge =
        mediaMetadata.extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey)
    if (audioQualityBadge == LocalAudioLibrary.AudioQualityBadgeCue) {
        return PlaybackDeleteTargetResult.CueFile
    }
    val deleteUri =
        localConfiguration?.uri?.takeIf(Uri::isMediaStoreUri)
            ?: targetMediaId.toLongOrNull()?.let { id ->
                audioMediaItemUri(id)
            }
            ?: return PlaybackDeleteTargetResult.Unavailable
    return PlaybackDeleteTargetResult.Available(
        PlaybackDeleteTarget(
            mediaId = targetMediaId,
            uri = deleteUri,
        )
    )
}

internal fun MediaItem.canShareAudio(): Boolean {
    return localConfiguration?.uri?.scheme == ContentResolver.SCHEME_CONTENT
}

internal fun Context.tryShareAudio(mediaItem: MediaItem): Boolean {
    val shareUri =
        mediaItem.localConfiguration?.uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return false
    val title =
        mediaItem.mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
            ?: getString(R.string.unknown_song_title)
    val mimeType =
        sequenceOf(
                mediaItem.localConfiguration?.mimeType,
                runCatching { contentResolver.getType(shareUri) }.getOrNull(),
            )
            .mapNotNull { candidate ->
                candidate?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isShareableAudioMimeType)
            }
            .firstOrNull() ?: DefaultAudioShareMimeType
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            clipData = ClipData.newRawUri(title, shareUri)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooserIntent =
        Intent.createChooser(sendIntent, getString(R.string.share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching {
        startActivity(chooserIntent)
    }
        .isSuccess
}

private fun Uri.isMediaStoreUri(): Boolean {
    return scheme == ContentResolver.SCHEME_CONTENT && authority == MediaStore.AUTHORITY
}

private fun String.isShareableAudioMimeType(): Boolean {
    return startsWith("audio/") || this in ShareableApplicationAudioMimeTypes
}

internal fun nextPlaybackRepeatMode(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}

@DrawableRes
internal fun playbackRepeatButtonRes(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> R.drawable.btn_playing_repeat_on
        Player.REPEAT_MODE_ALL -> R.drawable.btn_playing_cycle_on
        else -> R.drawable.btn_playing_cycle_off
    }
}

internal fun repeatContentDescriptionRes(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> R.string.repeat_single
        Player.REPEAT_MODE_ALL -> R.string.repeat_all
        else -> R.string.repeat_none
    }
}

internal fun repeatToastRes(repeatMode: Int): Int = repeatContentDescriptionRes(repeatMode)

internal fun shuffleToastRes(shuffleEnabled: Boolean): Int {
    return if (shuffleEnabled) {
        R.string.shuffle_on
    } else {
        R.string.shuffle_off
    }
}

// ===== [1.4.16·V1 地基重构] 音量单点写入 + 写入保护窗口 =====
// 为什么反复犯：全 App 有三处写系统音量——±键 nudge 直接 setStreamVolume、
// onValueChange→setMusicStreamVolumeFraction 又写一次、拖动走等响曲线；三套口径互相打架，
// 且写入后被系统/UAC 弹回的脏档位又被回读成"剩一格"，于是"减键→静音→弹回一格→再减"死循环。
// 现在统一：所有写入只走 setMusicStreamVolumeFraction；写后 protection 窗口内视觉锁定写入目标，
// 不再被回弹档位污染；超出窗口才跟随系统（物理音量键依旧生效）。
private const val VolumeWriteGuardMs = 900L
@Volatile private var lastWrittenFraction = -1f
@Volatile private var lastWrittenAtMs = 0L

internal fun Context.musicStreamVolumeFraction(): Float {
    val audioManager = getSystemService(AudioManager::class.java) ?: return 1f
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) return 0f
    val guarded = lastWrittenFraction
    if (guarded >= 0f &&
        android.os.SystemClock.elapsedRealtime() - lastWrittenAtMs < VolumeWriteGuardMs
    ) {
        return guarded.coerceIn(0f, 1f)
    }
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
    return (currentVolume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
}

internal fun Context.setMusicStreamVolumeFraction(value: Float) {
    val audioManager = getSystemService(AudioManager::class.java) ?: return
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val target = value.coerceIn(0f, 1f)
    // 先登记写入目标与时间：写入后视觉立即锁到目标，杜绝回弹档位把它拉回"一格"。
    lastWrittenFraction = target
    lastWrittenAtMs = android.os.SystemClock.elapsedRealtime()
    val targetVolume = (target * maxVolume.toFloat()).roundToInt().coerceIn(0, maxVolume)
    // [v12·音量0格自己跳回1格根治] 旧实现无论目标档位都先做一次 ADJUST_UNMUTE（1.4.24
    // 为治"0档只置mute标志不触发广播"引入）。真机日志(17:13-17:16)坐实副作用：目标=0 时
    // ADJUST_UNMUTE 会把 STREAM_MUSIC 从静音档弹回 1/150，紧接着 setStreamVolume(0) 拉回 0，
    // 但 ADJUST_UNMUTE 的广播异步到达、晚于 setStreamVolume 的广播 → syncSystemVolume 读到 1
    // → 增益/音量条从 0 跳回 1 格，反复拉锯。
    // 正解：只有目标 >0 档才需要解除静音；目标=0 时禁止 unmute，直接 setStreamVolume(0)
    // 并置静音标志（数值 0 本身就是静音，无需也不该再 unmute）。
    if (targetVolume > 0) {
        runCatching {
            if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
    // 0 档双保险：setStreamVolume(0) 后再次确认读回为 0（部分机型有延迟回弹），
    // 若被弹回立即重写一次，确保物理静音与视觉一致。
    if (targetVolume == 0) {
        runCatching {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }
    // [v10·0 格必静音] 写完系统音量后**直接**把增益同步到输出端，不依赖广播回环。
    // 根因：0 档静音旧实现只靠 setStreamVolume→VOLUME_CHANGED 广播→UsbVolume.syncSystemVolume，
    // 反复拉回 0 时系统可能不重发广播（或 900ms 视觉保护窗把 UI 锁 0 而增益没动），导致
    // 「第一次拉到 0 静音、再拉高再拉回 0 就不静音」。这里用实际写入档位重算比例立即施加，
    // 0 档 immediate 归零。非独占/独占都用同一入口，彻底与广播时序解耦。
    com.smartisan.music.playback.UsbVolume.applyFraction(
        targetVolume.toFloat() / maxVolume.toFloat(),
        immediate = targetVolume == 0,
        // [1.4.30·R183③] 这是"音量键/界面按键"写系统音量后的直接落地口，
        // 与 VOLUME_CHANGED 广播回环的 system-sync 区分开，便于判断重复写是不是这条。
        source = "volume-key-or-ui",
    )
}

internal fun Context.toast(stringRes: Int) {
    Toast.makeText(this, getString(stringRes), Toast.LENGTH_SHORT).show()
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    val secondsText = if (seconds < 10L) "0$seconds" else seconds.toString()
    return if (hours > 0L) {
        val minutesText = if (minutes < 10L) "0$minutes" else minutes.toString()
        "$hours:$minutesText:$secondsText"
    } else {
        "${totalSeconds / 60L}:$secondsText"
    }
}

internal fun fractionFromPosition(positionX: Float, trackWidthPx: Int): Float {
    if (trackWidthPx <= 0) return 0f
    return (positionX / trackWidthPx.toFloat()).coerceIn(0f, 1f)
}

private const val DefaultAudioShareMimeType = "audio/*"

private val ShareableApplicationAudioMimeTypes =
    setOf(
        "application/ogg",
        "application/x-ogg",
        "application/itunes",
    )
