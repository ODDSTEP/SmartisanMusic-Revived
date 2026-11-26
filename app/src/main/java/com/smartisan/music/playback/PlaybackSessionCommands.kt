@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import androidx.core.os.BundleCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListenableFuture
import com.smartisan.music.diagnostics.AppLog

internal const val ScratchSeekModeAction = "com.smartisan.music.action.SET_SCRATCH_SEEK_MODE"
internal const val ScratchSeekModeEnabledKey = "scratch_seek_mode_enabled"
internal const val StartSleepTimerAction = "com.smartisan.music.action.START_SLEEP_TIMER"
internal const val CancelSleepTimerAction = "com.smartisan.music.action.CANCEL_SLEEP_TIMER"
internal const val SleepTimerDurationMsKey = "sleep_timer_duration_ms"
internal const val RefreshLibraryAction = "com.smartisan.music.action.REFRESH_LIBRARY"
internal const val InvalidateLibraryAction = "com.smartisan.music.action.INVALIDATE_LIBRARY"
internal const val SetTrackRatingAction = "com.smartisan.music.action.SET_TRACK_RATING"
internal const val ReplaceQueueAndPlayAction = "com.smartisan.music.action.REPLACE_QUEUE_AND_PLAY"
internal const val TrackRatingMediaIdKey = "track_rating_media_id"
internal const val TrackRatingScoreKey = "track_rating_score"
internal const val TrackRatingMinScore = 0
internal const val TrackRatingMaxScore = 5
internal const val ReplaceQueueMediaItemsKey = "replace_queue_media_items"
/** 1.2.26：大队列只传 mediaId，避免整条队列（MediaItemBundle 含 extras）撑爆 Binder 1MB 事务上限。 */
internal const val ReplaceQueueMediaIdsKey = "replace_queue_media_ids"
/**
 * 1.2.30：只传 id 通道里，**非数字 mediaId** 的完整 MediaItem 必须一起带过去。
 *
 * DFF/DSF/APE 等 Files 补录行的 id 是 `f<数字>`（例如 f1000150202），服务端
 * [getAudioItemsByIds] 只能用 MediaStore.Audio 直查数字 id，f 前缀行只能靠索引缓存命中；
 * 索引未刷新 / 服务重启后缓存为空时这些项会被静默丢弃，重建出来的队列只剩原生 FLAC，
 * 目标 id 也匹配不上 → `indexOfFirst(...).coerceAtLeast(0)` 落回第 0 首。这正是
 * 「点一首 DFF 缓冲完自动跳到列表第 1 首、之后一直跳过所有 DFF」的根因，
 * 而搜索页结果短、走完整 bundle 通道，所以搜索页点 DFF 能播。
 */
internal const val ReplaceQueueDirectItemsKey = "replace_queue_direct_items"
/**
 * 1.2.32：直传项的「纯字符串 uri 兜底」。MediaItem bundle 反序列化（`fromBundle` 两参版本
 * 在 Media3 1.10.1 里第二参并非版本号）在资料库索引起重排 / 过期时会偶发丢
 * `localConfiguration.uri`，导致 f 前缀 DFF 被整体丢弃、点了没反应。这里随包再发一份
 * `mediaId\u0000uri` 的纯字符串映射，服务端用它强制补齐 uri，绝不依赖 bundle 反序列化。
 */
internal const val ReplaceQueueDirectUrisKey = "replace_queue_direct_uris"
/**
 * 1.2.31：Binder 事务上限约 1MB，留出余量后完整 bundle 最多允许 600KB。
 * 1.2.26 拍脑袋用「600 首」当阈值（估算 700B/首），把实测远小于 1MB 的 652 首队列
 * 硬推进「只传 id」通道；那条通道 1.2.30 起又坏了，结果就是「歌曲页 / 搜索页点歌
 * 完全没反应，只有专辑页单曲能播」。现在改成用 Parcel 实测字节数判断。
 */
internal const val ReplaceQueueBundleMaxBytes = 800_000
/** 兜底的绝对数量上限：即便每首都很小，也不让一次 Binder 事务塞进过多条目。 */
internal const val ReplaceQueueBundleHardLimit = 1500
internal const val ReplaceQueueStartIndexKey = "replace_queue_start_index"
internal const val ReplaceQueueShuffleModeKey = "replace_queue_shuffle_mode"
internal const val NotificationPlaybackModeAction =
    "com.smartisan.music.action.NOTIFICATION_PLAYBACK_MODE"
internal const val NotificationFavoriteAction =
    "com.smartisan.music.action.NOTIFICATION_FAVORITE"
/**
 * [1.4.30·R134] 通知栏播放/暂停键改走自定义 session command。
 *
 * 根因（1.4.29 真机日志）：自绘通知原来用
 * `actionFactory.createMediaActionPendingIntent(session, COMMAND_PLAY_PAUSE)`。
 * 暂停态下 media3 会把它做成**前台服务**类型的 PendingIntent；覆盖安装后首次冷启、
 * App 仍停在后台时，该 PendingIntent 从 SystemUI 侧发不出来——日志里
 * 15:00:48 恢复队列（playWhenReady=false）到 15:01:33 用户点通知播放键，
 * 服务**一条 play/pause 命令都没收到**；而"收藏/模式"两个键走自定义命令，
 * 同一时刻点就有反应。改成同一条通道即可。
 */
internal const val NotificationPlayPauseAction =
    "com.smartisan.music.action.NOTIFICATION_PLAY_PAUSE"
/**
 * [1.4.32·验收12] 通知栏「下一首/上一首」也改走自定义 session command。
 *
 * 与 R134 的 play/pause 同一根因：自绘通知原用
 * `createMediaActionPendingIntent(session, COMMAND_SEEK_TO_NEXT/PREVIOUS_MEDIA_ITEM)`，
 * 暂停态下 media3 做成前台服务类型 PendingIntent；冷启后 App 在后台时 SystemUI 投递不到，
 * 表现为「没点过播放就点下一首没反应」。R134 当时只修了 play/pause，漏了 next/prev，
 * 这里补齐。取不到自定义按钮时仍回退系统默认路径，不留白键。
 */
internal const val NotificationNextAction =
    "com.smartisan.music.action.NOTIFICATION_NEXT"
internal const val NotificationPreviousAction =
    "com.smartisan.music.action.NOTIFICATION_PREVIOUS"
internal const val UpdateTrackMetadataAction =
    "com.smartisan.music.action.UPDATE_TRACK_METADATA"
internal const val UpdateTrackMetadataIdKey = "update_track_metadata_id"
internal const val UpdateTrackMetadataTitleKey = "update_track_metadata_title"
internal const val UpdateTrackMetadataArtistKey = "update_track_metadata_artist"
internal const val UpdateTrackMetadataAlbumKey = "update_track_metadata_album"

internal val ScratchSeekModeCommand = SessionCommand(ScratchSeekModeAction, Bundle.EMPTY)
internal val StartSleepTimerCommand = SessionCommand(StartSleepTimerAction, Bundle.EMPTY)
internal val CancelSleepTimerCommand = SessionCommand(CancelSleepTimerAction, Bundle.EMPTY)
internal val RefreshLibraryCommand = SessionCommand(RefreshLibraryAction, Bundle.EMPTY)
internal val InvalidateLibraryCommand = SessionCommand(InvalidateLibraryAction, Bundle.EMPTY)
internal val SetTrackRatingCommand = SessionCommand(SetTrackRatingAction, Bundle.EMPTY)
internal val ReplaceQueueAndPlayCommand = SessionCommand(ReplaceQueueAndPlayAction, Bundle.EMPTY)
internal val NotificationPlaybackModeCommand =
    SessionCommand(NotificationPlaybackModeAction, Bundle.EMPTY)
internal val NotificationFavoriteCommand =
    SessionCommand(NotificationFavoriteAction, Bundle.EMPTY)
internal val NotificationPlayPauseCommand =
    SessionCommand(NotificationPlayPauseAction, Bundle.EMPTY)
internal val NotificationNextCommand = SessionCommand(NotificationNextAction, Bundle.EMPTY)
internal val NotificationPreviousCommand =
    SessionCommand(NotificationPreviousAction, Bundle.EMPTY)
internal val UpdateTrackMetadataCommand = SessionCommand(UpdateTrackMetadataAction, Bundle.EMPTY)

internal fun MediaController.setScratchSeekModeEnabled(enabled: Boolean) {
    val args = Bundle().apply {
        putBoolean(ScratchSeekModeEnabledKey, enabled)
    }
    sendCustomCommand(ScratchSeekModeCommand, args)
}

internal fun MediaController.startSleepTimer(durationMs: Long) {
    val args = Bundle().apply {
        putLong(SleepTimerDurationMsKey, durationMs)
    }
    sendCustomCommand(StartSleepTimerCommand, args)
}

internal fun MediaController.cancelSleepTimer() {
    sendCustomCommand(CancelSleepTimerCommand, Bundle.EMPTY)
}

internal fun MediaController.refreshLibrary() =
    sendCustomCommand(RefreshLibraryCommand, Bundle.EMPTY)

/** 歌曲信息编辑落盘后，同步替换播放队列里的旧元数据（小播放条/大播放页立即更新） */
internal fun MediaController.updateQueuedTrackMetadata(
    mediaId: String,
    title: String?,
    artist: String?,
    album: String?,
): ListenableFuture<SessionResult> {
    val args = Bundle().apply {
        putString(UpdateTrackMetadataIdKey, mediaId)
        putString(UpdateTrackMetadataTitleKey, title)
        putString(UpdateTrackMetadataArtistKey, artist)
        putString(UpdateTrackMetadataAlbumKey, album)
    }
    return sendCustomCommand(UpdateTrackMetadataCommand, args)
}

internal fun MediaController.invalidateLibrary() =
    sendCustomCommand(InvalidateLibraryCommand, Bundle.EMPTY)

internal fun MediaController.setTrackRating(mediaId: String, score: Int): ListenableFuture<SessionResult> {
    val args = Bundle().apply {
        putString(TrackRatingMediaIdKey, mediaId)
        putInt(TrackRatingScoreKey, score.coerceIn(TrackRatingMinScore, TrackRatingMaxScore))
    }
    return sendCustomCommand(SetTrackRatingCommand, args)
}

internal fun MediaController.sendReplaceQueueAndPlayCommand(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    shuffleModeEnabled: Boolean,
): ListenableFuture<SessionResult> {
    val fullItemBundles =
        ArrayList(
            mediaItems.map { item ->
                item.toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION)
            },
        )
    val fullArgs =
        Bundle().apply {
            putParcelableArrayList(ReplaceQueueMediaItemsKey, fullItemBundles)
            putInt(ReplaceQueueStartIndexKey, startIndex)
            putBoolean(ReplaceQueueShuffleModeKey, shuffleModeEnabled)
        }
    val fullBytes = fullArgs.parceledSizeBytes()
    val useFullBundle =
        fullBytes <= ReplaceQueueBundleMaxBytes &&
            mediaItems.size <= ReplaceQueueBundleHardLimit
    // 只有真正接近 Binder 上限才走「只传 id」通道。这条通道历史上是多条 bug 的来源
    // （1.2.26 丢 f 前缀 → 连跳；1.2.30 direct items 覆盖 → 完全播不了），
    // 因此能不用就不用，并且两条分支都必须把选择依据（实测字节数）打进日志。
    AppLog.i(
        "Queue",
        "发送重建队列命令[${if (useFullBundle) "完整 bundle" else "只传 id"}]: " +
            "count=${mediaItems.size} bytes=$fullBytes limit=$ReplaceQueueBundleMaxBytes" +
            " startIndex=$startIndex targetId=${mediaItems.getOrNull(startIndex)?.mediaId}" +
            " shuffle=$shuffleModeEnabled",
    )
    return sendCustomCommand(
        ReplaceQueueAndPlayCommand,
        if (useFullBundle) {
            fullArgs
        } else {
            Bundle().apply {
                putStringArrayList(
                    ReplaceQueueMediaIdsKey,
                    ArrayList(mediaItems.map { item -> item.mediaId }),
                )
                // 非数字 id（f 前缀 Files 补录行）服务端可能按 id 还原不到，随包带上备查。
                val directItems =
                    mediaItems.filter { item -> item.mediaId.toLongOrNull() == null }
                if (directItems.isNotEmpty()) {
                    putParcelableArrayList(
                        ReplaceQueueDirectItemsKey,
                        ArrayList(
                            directItems.map { item ->
                                item.toBundleIncludeLocalConfiguration(
                                    MediaLibraryInfo.INTERFACE_VERSION,
                                )
                            },
                        ),
                    )
                    // 1.2.32：纯字符串 uri 兜底，规避 MediaItem bundle 偶发丢 uri。
                    val uriPairs =
                        directItems.mapNotNull { item ->
                            item.localConfiguration?.uri?.toString()?.let { u ->
                                item.mediaId + "\u0000" + u
                            }
                        }
                    putStringArrayList(
                        ReplaceQueueDirectUrisKey,
                        ArrayList(uriPairs),
                    )
                }
                putInt(ReplaceQueueStartIndexKey, startIndex)
                putBoolean(ReplaceQueueShuffleModeKey, shuffleModeEnabled)
            }
        },
    )
}

/** 用 Parcel 实测 Bundle 序列化后的字节数，作为是否改走「只传 id」通道的依据。 */
private fun Bundle.parceledSizeBytes(): Int {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(this)
        parcel.dataSize()
    } finally {
        parcel.recycle()
    }
}

internal fun Bundle.decodeReplaceQueueAndPlayMediaIds(): List<String>? =
    getStringArrayList(ReplaceQueueMediaIdsKey)?.filter { it.isNotBlank() }

/** 1.2.30：客户端随包带过来的、服务端无法按 id 还原的非数字 id 项（按 mediaId 索引）。 */
internal fun Bundle.decodeReplaceQueueDirectItems(): Map<String, MediaItem> {
    val bundles = BundleCompat.getParcelableArrayList(
        this,
        ReplaceQueueDirectItemsKey,
        Bundle::class.java,
    ) ?: return emptyMap()
    return bundles.mapNotNull { bundle ->
        runCatching {
            MediaItem.fromBundle(bundle, MediaLibraryInfo.INTERFACE_VERSION)
        }.getOrNull()
    }.associateBy(MediaItem::mediaId)
}

/**
 * 1.2.32：直传项的「纯字符串 uri 兜底」解码。随包发的是 `mediaId\u0000uri` 列表，
 * 用 Uri.parse 还原，绝不依赖 MediaItem bundle 反序列化。键是 mediaId，值是可可靠还原的 uri。
 */
internal fun Bundle.decodeReplaceQueueDirectUris(): Map<String, Uri> {
    val pairs = getStringArrayList(ReplaceQueueDirectUrisKey) ?: return emptyMap()
    val map = mutableMapOf<String, Uri>()
    for (p in pairs) {
        val sep = p.indexOf('\u0000')
        if (sep <= 0) continue
        val id = p.substring(0, sep)
        val uriStr = p.substring(sep + 1)
        runCatching { map[id] = Uri.parse(uriStr) }
    }
    return map
}

internal fun Bundle.decodeReplaceQueueAndPlayMediaItems(): List<MediaItem> {    val itemBundles = BundleCompat.getParcelableArrayList(
        this,
        ReplaceQueueMediaItemsKey,
        Bundle::class.java,
    )
        ?: return emptyList()
    return itemBundles.map { itemBundle ->
        MediaItem.fromBundle(itemBundle, MediaLibraryInfo.INTERFACE_VERSION)
    }
}
