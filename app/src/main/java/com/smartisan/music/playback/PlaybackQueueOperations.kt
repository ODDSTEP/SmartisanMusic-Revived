package com.smartisan.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.isExternalAudioLaunchItem
import kotlin.random.Random

internal fun Player?.replaceQueueAndPlay(
    mediaItems: List<MediaItem>,
    startIndex: Int = 0,
    shuffleModeEnabled: Boolean = false,
    keepShuffle: Boolean = false,
) {
    val player = this ?: return
    // 1.3.5 实验性功能「不播放 DSD 格式音频」：DSD 不进任何播放列表；
    // 若点的正好是 DSD（原始起点被过滤掉），直接不响应。
    val effectiveItems =
        if (PlaybackPolicy.excludeDsd) mediaItems.filterNot { it.isDsdItem() }
        else mediaItems
    val startItemId = mediaItems.getOrNull(startIndex)?.mediaId
    val effectiveStartIndex =
        if (PlaybackPolicy.excludeDsd && startItemId != null)
            effectiveItems.indexOfFirst { it.mediaId == startItemId }
        else startIndex
    // 起点本身是 DSD 且开启了过滤：不响应（不跳到别的歌，避免"点 A 播 B"）
    if (effectiveStartIndex < 0) return
    val safeStartIndex = playbackQueueStartIndex(
        itemCount = effectiveItems.size,
        startIndex = effectiveStartIndex,
    ) ?: return
    // 1.2.25：点「某一首」时保留用户当前的随机播放状态（旧版无条件置 false，
    // 表现为「开了随机，点一首歌就被悄悄关掉」）。「播放全部」按钮仍走顺序播放。
    val effectiveShuffle = if (keepShuffle) player.shuffleModeEnabled else shuffleModeEnabled
    if (player is MediaController) {
        player.sendReplaceQueueAndPlayCommand(
            mediaItems = effectiveItems,
            startIndex = safeStartIndex,
            shuffleModeEnabled = effectiveShuffle,
        )
        return
    }
    player.replaceQueueAndPlayDirect(
        mediaItems = effectiveItems,
        startIndex = safeStartIndex,
        shuffleModeEnabled = effectiveShuffle,
    )
}

internal fun Player.replaceQueueAndPlayDirect(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    shuffleModeEnabled: Boolean = false,
) {
    val safeStartIndex = playbackQueueStartIndex(
        itemCount = mediaItems.size,
        startIndex = startIndex,
    ) ?: return
    this.shuffleModeEnabled = shuffleModeEnabled
    playWhenReady = true
    // 1.2.20：队列去重——点排序/刷新后再次点同一首时，列表内容与当前队列完全一致，
    // 没必要 setMediaItems+prepare 重来一遍（实测一次连点触发 15 次重复装载，
    // 是「点几下死机卡顿」的嫌疑来源）。只有队列真的变化了才重建。
    val currentIds = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
    val newIds = mediaItems.map { it.mediaId }
    val sameQueue = currentIds == newIds
    val alreadyPlayingThisQueue = sameQueue && currentMediaItemIndex == safeStartIndex
    AppLog.i(
        "Queue",
        "replaceQueueAndPlayDirect: incoming=${mediaItems.size} start=$safeStartIndex" +
            " startId=${mediaItems[safeStartIndex].mediaId}" +
            " sameQueue=$sameQueue alreadyPlaying=$alreadyPlayingThisQueue" +
            " currentIndex=$currentMediaItemIndex currentCount=$mediaItemCount" +
            " currentId=${currentMediaItem?.mediaId}",
    )
    when {
        alreadyPlayingThisQueue -> Unit // 完全一致：不重建、不 seek，避免任何 PLAYLIST_CHANGED
        sameQueue -> {
            // 1.2.30：队列内容没变，只是起点不同。旧版在这里整表 setMediaItems，
            // 每次都产生 PLAYLIST_CHANGED → 当前项被打回第 0 首，是「连跳」的放大器。
            AppLog.i("Queue", "队列未变，仅 seek 到 $safeStartIndex（不重建）")
            seekToDefaultPosition(safeStartIndex)
        }
        else -> {
            setMediaItems(mediaItems, safeStartIndex, 0L)
            prepare()
        }
    }
    play()
}

internal fun Player?.replaceQueueAndPlayShuffled(
    mediaItems: List<MediaItem>,
    random: Random = Random.Default,
) {
    val player = this ?: return
    if (mediaItems.isEmpty()) {
        return
    }
    // 1.3.5：开启 DSD 过滤时，随机起点必须基于「过滤后」的列表长度，
    // 否则随机到被过滤掉的 DSD 会表现为「点随机播放没反应」。
    val effective =
        if (PlaybackPolicy.excludeDsd) mediaItems.filterNot { it.isDsdItem() }
        else mediaItems
    if (effective.isEmpty()) return
    player.replaceQueueAndPlay(
        mediaItems = effective,
        startIndex = random.nextInt(effective.size),
        shuffleModeEnabled = true,
    )
}

internal fun playbackQueueStartIndex(
    itemCount: Int,
    startIndex: Int,
): Int? {
    if (itemCount <= 0) {
        return null
    }
    return startIndex.coerceIn(0, itemCount - 1)
}

internal val MediaItem.stableKey: String?
    get() = mediaMetadata.extras
        ?.getString(LocalAudioLibrary.StableKeyExtraKey)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

internal fun Player.deduplicateQueueCandidates(candidates: List<MediaItem>): List<MediaItem> {
    if (candidates.isEmpty()) {
        return emptyList()
    }
    val seenStableKeys = linkedSetOf<String>()
    val seenMediaIds = linkedSetOf<String>()
    val seenMediaIdsWithoutStableKey = linkedSetOf<String>()
    for (index in 0 until mediaItemCount) {
        val item = getMediaItemAt(index)
        val mediaId = item.mediaId.trim().takeIf(String::isNotEmpty)
        val stableKey = item.stableKey
        stableKey?.let(seenStableKeys::add)
        mediaId?.let(seenMediaIds::add)
        if (stableKey == null) {
            mediaId?.let(seenMediaIdsWithoutStableKey::add)
        }
    }
    return candidates.filter { item ->
        if (item.isExternalAudioLaunchItem()) {
            return@filter true
        }
        val stableKey = item.stableKey
        val mediaId = item.mediaId.trim()
        val normalizedMediaId = mediaId.takeIf(String::isNotEmpty)
        val alreadyQueued = if (stableKey != null) {
            stableKey in seenStableKeys ||
                normalizedMediaId?.let(seenMediaIdsWithoutStableKey::contains) == true
        } else {
            normalizedMediaId?.let(seenMediaIds::contains) == true
        }
        if (alreadyQueued) {
            false
        } else {
            stableKey?.let(seenStableKeys::add)
            normalizedMediaId?.let(seenMediaIds::add)
            if (stableKey == null) {
                normalizedMediaId?.let(seenMediaIdsWithoutStableKey::add)
            }
            true
        }
    }
}

internal fun Player?.removeMediaItemsByMediaIds(mediaIds: Set<String>) {
    val player = this ?: return
    val normalizedMediaIds = mediaIds.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    if (normalizedMediaIds.isEmpty()) {
        return
    }

    for (index in player.mediaItemCount - 1 downTo 0) {
        if (player.getMediaItemAt(index).mediaId in normalizedMediaIds) {
            player.removeMediaItem(index)
        }
    }
}

internal fun Player?.removeMediaItemsMatching(predicate: (MediaItem) -> Boolean) {
    val player = this ?: return
    var rangeEnd = player.mediaItemCount
    var index = rangeEnd - 1
    while (index >= 0) {
        if (!predicate(player.getMediaItemAt(index))) {
            rangeEnd = index
            index -= 1
            continue
        }

        var rangeStart = index
        while (rangeStart > 0 && predicate(player.getMediaItemAt(rangeStart - 1))) {
            rangeStart -= 1
        }
        player.removeMediaItems(rangeStart, rangeEnd)
        rangeEnd = rangeStart
        index = rangeStart - 1
    }
}

internal fun MediaItem.withPlaybackRating(score: Int): MediaItem {
    val normalizedScore = score.coerceIn(0, 5)
    val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
        RatingExtraKeys.forEach(::remove)
        if (normalizedScore > 0) {
            putLong(LocalAudioLibrary.RatingExtraKey, normalizedScore.toLong())
        }
    }
    val metadata = mediaMetadata.buildUpon()
        .setExtras(extras)
        .build()
    return buildUpon()
        .setMediaMetadata(metadata)
        .build()
}

/**
 * 用编辑歌曲信息后的新标签替换队列条目的元数据（标题/歌手/专辑）：
 * 让底部小播放条与大播放页立即显示新名称，而不必等媒体库重扫。
 * 注意：displayTitle 必须一起写——小播放条优先读 displayTitle，
 * 只写 title 的话小播放条仍显示旧名（1.2.15 的残留 bug）。
 */
internal fun MediaItem.withTrackMetadata(
    title: String?,
    artist: String?,
    album: String?,
): MediaItem {
    val metadata = mediaMetadata.buildUpon().apply {
        if (!title.isNullOrBlank()) {
            setTitle(title)
            setDisplayTitle(title)
        }
        if (!artist.isNullOrBlank()) {
            setArtist(artist)
            setSubtitle(artist)
        }
        if (!album.isNullOrBlank()) setAlbumTitle(album)
    }.build()
    return buildUpon()
        .setMediaMetadata(metadata)
        .build()
}

private val RatingExtraKeys = listOf(
    LocalAudioLibrary.RatingExtraKey,
    "star",
    "score",
    "rating",
    "play_score",
)
