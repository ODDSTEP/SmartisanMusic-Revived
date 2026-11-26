package com.smartisan.music.playback

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.smartisan.music.R
import com.smartisan.music.data.library.LibraryIndexDao
import com.smartisan.music.data.library.LibraryIndexDatabase
import com.smartisan.music.data.library.LibraryIndexEntity
import com.smartisan.music.data.library.LibraryIndexSnapshotEntity
import com.smartisan.music.data.playback.PlaybackStatsRecord
import com.smartisan.music.data.tags.SongTagEditor
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.platform.media.AudioQualityTiers
import com.smartisan.music.platform.media.FormatCache
import com.smartisan.music.platform.media.audioMediaCollectionUri
import com.smartisan.music.platform.text.HanLatinTransliterator
import java.io.File
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class LocalAudioLibrary(
    private val context: Context,
    private val playbackStatsProvider: () -> Map<String, PlaybackStatsRecord> = { emptyMap() },
    private val playbackStatsByIdsProvider: (Set<String>) -> Map<String, PlaybackStatsRecord> =
        { mediaIds ->
            playbackStatsProvider().filterKeys(mediaIds::contains)
        },
    private val libraryIndexDatabase: LibraryIndexDatabase =
        LibraryIndexDatabase.getInstance(context),
) {

    private val audioCacheLock = Any()
    private val libraryIndexDao = libraryIndexDatabase.libraryIndexDao()
    @Volatile private var audioCache = AudioCache()

    /**
     * 1.2.26：索引重建发现 mediaId 变了（文件被移动/重命名、SD 卡重挂载）时回调，
     * 参数是「旧 mediaId → 新 mediaId」。由 [PlaybackService] 接到收藏/统计/歌单/隐藏项迁移上。
     */
    @Volatile
    var onMediaIdRemapped: ((Map<String, String>) -> Unit)? = null

    fun getRootItem(): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(context.getString(R.string.library_root))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()

        return MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(metadata).build()
    }

    fun getAudioItems(forceRefresh: Boolean = false): List<MediaItem> {
        val currentSnapshot = currentMediaStoreSnapshot()
        synchronized(audioCacheLock) {
            val cache = audioCache
            // generation 负责日常增量变化，version 负责更大范围的媒体库重建。
            if (!forceRefresh && cache.snapshot == currentSnapshot && cache.items.isNotEmpty()) {
                // 1.2.20：探测/回填必须在**所有**加载路径触发——旧版只在曲库重建时触发，
                // 而文件不变时永远走缓存，导致 1.2.18 以来格式探测与补录行标签回填
                // 从未执行（实测日志零记录），音质排序全 0、DFF 一直「未知歌手+WAV 徽章」。
                triggerFormatProbe(cache.items)
                return cache.items
            }
        }

        if (!forceRefresh) {
            val indexedItems = getAudioItemsFromIndexIfFresh(currentSnapshot)
            if (indexedItems.isNotEmpty()) {
                synchronized(audioCacheLock) {
                    audioCache =
                        AudioCache(
                            snapshot = currentSnapshot,
                            items = indexedItems,
                        )
                }
                triggerFormatProbe(indexedItems)
                return indexedItems
            }
        }

        return reconcileAudioIndexAndCache(currentSnapshot)
    }

    fun getAudioItemsByIds(mediaIds: List<String>): List<MediaItem> {
        // mediaId 两类：Audio 表数字 id；UnscannedMediaIdPrefix+id（DFF/DSF 等 Files 补录行）。
        // 旧行为把非数字 id 直接丢弃，会导致补录行在队列重建时丢失。
        val requestedIdStrings =
            mediaIds
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
        if (requestedIdStrings.isEmpty()) {
            return emptyList()
        }

        val cachedItemsById = getValidCachedItemsById(requestedIdStrings.toSet())
        val missingIdStrings = requestedIdStrings.filter { id -> id !in cachedItemsById }
        if (missingIdStrings.isEmpty()) {
            return requestedIdStrings.mapNotNull(cachedItemsById::get)
        }
        if (!canReadLibraryIndexOnCurrentThread()) {
            return requestedIdStrings.mapNotNull(cachedItemsById::get)
        }

        val indexFresh = isAudioIndexFresh(currentMediaStoreSnapshot())
        val indexedItemsById =
            if (indexFresh) {
                libraryIndexDao
                    .getValidIndexesByMediaIdsChunked(missingIdStrings)
                    .toMediaItems(playbackStatsForIds(missingIdStrings.toSet()))
                    .associateBy(MediaItem::mediaId)
            } else {
                emptyMap()
            }
        val stillMissingIdStrings = missingIdStrings.filter { id -> id !in indexedItemsById }
        val playbackStats = playbackStatsForIds(stillMissingIdStrings.toSet())
        // MediaStore 直查只支持 Audio 表数字 id；Files 补录行（前缀 id）只能靠索引命中，
        // 或由 getAudioItemsByQueueKeys 的 stableKey 兜底。
        val stillMissingNumericIds =
            stillMissingIdStrings.mapNotNull { id ->
                id.toLongOrNull()?.takeIf { it > 0L }
            }
        val queriedItemsById =
            queryAudioIndexesByIds(stillMissingNumericIds)
                .toMediaItems(playbackStats)
                .associateBy(MediaItem::mediaId)
        val itemsById = cachedItemsById + indexedItemsById + queriedItemsById

        return requestedIdStrings.mapNotNull(itemsById::get)
    }

    fun getAudioItemsByQueueKeys(queueKeys: List<PlaybackQueueSnapshotItem>): List<MediaItem> {
        if (queueKeys.isEmpty()) {
            return emptyList()
        }
        val byIds =
            getAudioItemsByIds(queueKeys.map(PlaybackQueueSnapshotItem::mediaId))
                .associateBy(MediaItem::mediaId)
                .toMutableMap()
        val missingStableKeys =
            queueKeys
                .asSequence()
                .filter { key -> key.mediaId !in byIds }
                .map(PlaybackQueueSnapshotItem::stableKey)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        if (missingStableKeys.isNotEmpty() && canReadLibraryIndexOnCurrentThread()) {
            val currentSnapshot = currentMediaStoreSnapshot()
            if (!isAudioIndexFresh(currentSnapshot)) {
                reconcileAudioIndexAndCache(currentSnapshot)
            }
            val stableItems =
                libraryIndexDao.getValidIndexesByStableKeysChunked(missingStableKeys).toMediaItems()
            stableItems.forEach { item ->
                byIds[item.mediaId] = item
            }
            val stableItemsByKey = stableItems.associateBy { item -> item.stableKey.orEmpty() }
            return queueKeys.mapNotNull { key ->
                byIds[key.mediaId] ?: stableItemsByKey[key.stableKey]
            }
        }
        return queueKeys.mapNotNull { key -> byIds[key.mediaId] }
    }

    fun invalidateAudioItems() {
        synchronized(audioCacheLock) {
            audioCache = AudioCache()
        }
    }

    fun refreshAudioItems(): RefreshResult {
        val indexedSnapshot = queryIndexedAudioSnapshot()
        val pendingAudioPaths = discoverUnindexedAudioPaths(indexedSnapshot)
        val scanResult = scanAudioFiles(pendingAudioPaths)
        val mediaStoreRefreshSucceeded = requestMediaStoreRefresh()
        val items = getAudioItems(forceRefresh = true)
        return RefreshResult(
            items = items,
            scannedFileCount = scanResult.scannedFileCount,
            failedScanCount = scanResult.failedScanCount,
            scanTimedOut = scanResult.timedOut,
            mediaStoreRefreshSucceeded = mediaStoreRefreshSucceeded,
        )
    }

    fun getItem(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) {
            return getRootItem()
        }
        return getAudioItemsByIds(listOf(mediaId)).firstOrNull()
    }

    private fun getAudioItemsFromIndexIfFresh(
        currentSnapshot: MediaStoreSnapshot
    ): List<MediaItem> {
        if (!isAudioIndexFresh(currentSnapshot)) {
            return emptyList()
        }
        return libraryIndexDao.getValidIndexes().toMediaItems()
    }

    private fun isAudioIndexFresh(currentSnapshot: MediaStoreSnapshot): Boolean {
        val snapshotKey = libraryIndexDao.getSnapshotKey() ?: return false
        return snapshotKey == currentSnapshot.storageKey && libraryIndexDao.getValidIndexCount() > 0
    }

    private fun canReadLibraryIndexOnCurrentThread(): Boolean {
        return Looper.myLooper() != Looper.getMainLooper()
    }

    private fun reconcileAudioIndexAndCache(currentSnapshot: MediaStoreSnapshot): List<MediaItem> {
        val items = reconcileAudioIndex(currentSnapshot)
        synchronized(audioCacheLock) {
            audioCache =
                AudioCache(
                    snapshot = currentSnapshot,
                    items = items,
                )
        }
        triggerFormatProbe(items)
        return items
    }

    /** 生成音质探测入参（uri + 时长 + 体积 + 容器后缀）。 */
    private fun buildProbeInputs(items: List<MediaItem>): List<FormatCache.ProbeInput> =
        items.map { item ->
            val duration = item.mediaMetadata.durationMs ?: 0L
            val extras = item.mediaMetadata.extras
            val displayName = extras?.getString(DisplayNameExtraKey)
            FormatCache.ProbeInput(
                // 用 MediaItem 自身 uri：Files 补录行（DFF 等）不是 audio/media 集合
                uri = item.localConfiguration?.uri?.toString()
                    ?: "content://media/external/audio/media/${item.mediaId}",
                filePath = null,
                // 1.2.19：传入真实文件体积——FLAC/WAV/ALAC/APE 等无损容器文件头里没有码率字段，
                // 只能靠「体积/时长」估算；此前固定传 0 让这条兜底全线失效，码率永远显示「—」
                fileSize = extras?.getLong(FileSizeExtraKey, 0L) ?: 0L,
                durationMs = duration,
                mimeType = extras?.getString(MimeTypeExtraKey),
                container =
                    displayName
                        ?.substringAfterLast('.', missingDelimiterValue = "")
                        ?.lowercase(Locale.ROOT)
                        .orEmpty(),
            )
        }

    /**
     * 音质探测（采样率/码率/位深）与资料库索引解耦：
     * 有缓存直接命中；未命中的在后台线程逐个解文件头，完成后清空内存缓存，
     * 下一次资料库读取即可拿到带音质信息的 MediaItem。
     *
     * 1.2.20：补录行标签回填不再依赖「有未探测条目」——即使全部命中缓存，
     * 回填也要跑（否则补录行的歌手/专辑/时长永远补不上）。
     * probePending 防止每次列表加载都重复排队。
     */
    private val probePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun triggerFormatProbe(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (!probePending.compareAndSet(false, true)) return
        formatProbeExecutor.execute {
            try {
                // 1.2.19：先回填补录行（DFF/DSF/WV/TAK…）的内嵌标签与时长——
                // 补出来的时长是「体积/时长」估算码率的必要分母，顺序不能反。
                val backfilled =
                    UnscannedTagBackfill.run(
                        context,
                        runCatching { libraryIndexDao.getValidIndexes() }.getOrDefault(emptyList()),
                    ) { updated ->
                        libraryIndexDao.upsertIndexes(updated)
                    }
                val probeInputs =
                    if (backfilled > 0) {
                        buildProbeInputs(libraryIndexDao.getValidIndexes().toMediaItems())
                    } else {
                        buildProbeInputs(items)
                    }
                val probedCount = FormatCache.probeAll(context, probeInputs)
                // 只有真有新数据（回填改索引 / 新探测结果）才强制重载列表，
                // 否则「重载→探测→重载」会空转
                if (backfilled > 0 || probedCount > 0) {
                    synchronized(audioCacheLock) { audioCache = AudioCache() }
                }
            } catch (t: Throwable) {
                com.smartisan.music.diagnostics.AppLog.w("LocalAudioLibrary", "format probe failed: ${t.message}")
            } finally {
                probePending.set(false)
            }
        }
    }

    // ===== [1.4.25·R64] 脏专辑兜底：MediaStore 专辑被判脏留空时，改读文件内嵌标签 =====
    //
    // 现象：歌本身有专辑信息，却被归进「未知专辑」；进「编辑歌曲信息」弹窗又能预填正确专辑名。
    // 根因：索引取 MediaStore.ALBUM，被 MetadataSanitizer 判脏后留空 → 掉进未知专辑；
    //       编辑弹窗直接读文件内嵌 ID3，所以是对的。
    // 修法：只针对这一小批「被判脏留空」的行，用 jaudiotagger 读文件内嵌 ALBUM 兜底。
    // 性能：索引构建可能跑在主线程（MediaLibraryService 回调），绝不在那里做文件 I/O——
    //       任务丢进 audio-format-probe 后台线程批量处理；结果按 stableKey+dateModified 缓存
    //       （含未命中的负缓存），索引重建/行比较时直接命中，既避免重复读文件，也保证
    //       兜底出来的专辑不会在下次重建时又被抹成 null（否则 matches() 会反复重建该行）。

    private data class AlbumRescueTask(
        val mediaId: String,
        val stableKey: String,
        val dateModified: Long?,
        val title: String?,
        val artist: String?,
    )

    /** album 为 null 表示「读过了但没有可用专辑」，用作负缓存，避免反复读同一个坏文件。 */
    private data class AlbumRescueResult(val dateModified: Long?, val album: String?)

    private val albumRescueCache = ConcurrentHashMap<String, AlbumRescueResult>()
    private val albumRescueQueue = ConcurrentHashMap<String, AlbumRescueTask>()
    private val albumRescuePending = AtomicBoolean(false)

    /**
     * 脏专辑兜底入口（无 I/O，可在任意线程调用）：
     * 命中缓存直接返回文件标签里的专辑；未命中先返回 null（本次仍归未知专辑），
     * 同时把任务排进后台队列，下一次曲库读取即可拿到正确专辑。
     */
    private fun rescuedAlbum(row: AudioCursorRow, artist: String?, title: String?): String? {
        val cached = albumRescueCache[row.stableKey]
        if (cached != null && cached.dateModified == row.dateModified) return cached.album
        albumRescueQueue[row.stableKey] =
            AlbumRescueTask(
                mediaId = row.mediaId,
                stableKey = row.stableKey,
                dateModified = row.dateModified,
                title = title,
                artist = artist,
            )
        scheduleAlbumRescue()
        return null
    }

    private fun scheduleAlbumRescue() {
        if (!albumRescuePending.compareAndSet(false, true)) return
        formatProbeExecutor.execute {
            try {
                runAlbumRescue()
            } catch (t: Throwable) {
                AppLog.w("LocalAudioLibrary", "脏专辑兜底失败: ${t.message}")
            } finally {
                albumRescuePending.set(false)
            }
            // 队列还有剩余（单轮有条数上限）就继续排下一轮
            if (albumRescueQueue.isNotEmpty()) scheduleAlbumRescue()
        }
    }

    private fun runAlbumRescue() {
        val batch = albumRescueQueue.values.take(AlbumRescueBatchSize)
        if (batch.isEmpty()) return
        val updated = ArrayList<LibraryIndexEntity>()
        for (task in batch) {
            albumRescueQueue.remove(task.stableKey)
            val path =
                runCatching { SongTagEditor.resolve(context, task.mediaId)?.second }.getOrNull()
            val tagAlbum =
                path?.let { filePath -> runCatching { SongTagEditor.load(filePath)?.album }.getOrNull() }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            // 与 MediaStore 那条用同一套判脏口径：读出来还是脏的（或没有）就继续归未知专辑
            val usable =
                tagAlbum?.takeIf {
                    !MetadataSanitizer.isDirtyAlbumName(it, task.artist, task.title)
                }
            albumRescueCache[task.stableKey] = AlbumRescueResult(task.dateModified, usable)
            if (usable == null) continue
            val entity =
                runCatching { libraryIndexDao.getValidIndexesByStableKeys(listOf(task.stableKey)) }
                    .getOrDefault(emptyList())
                    .firstOrNull() ?: continue
            if (entity.album == usable) continue
            updated += entity.copy(album = usable)
            AppLog.i(
                "LocalAudioLibrary",
                "脏专辑兜底成功：文件标签=$usable ｜ 歌曲=${entity.title} ｜ ${entity.displayName}",
            )
        }
        if (updated.isEmpty()) {
            AppLog.i("LocalAudioLibrary", "脏专辑兜底: 本轮读取 ${batch.size} 个文件, 均无可用内嵌专辑")
            return
        }
        AppLog.i(
            "LocalAudioLibrary",
            "脏专辑兜底: 本轮读取 ${batch.size} 个文件, 救回 ${updated.size} 首",
        )
        runCatching { libraryIndexDao.upsertIndexes(updated) }
            .onSuccess {
                // 索引里的专辑变了，清内存缓存，下一次列表读取即可显示兜底结果
                synchronized(audioCacheLock) { audioCache = AudioCache() }
            }
            .onFailure { AppLog.w("LocalAudioLibrary", "脏专辑兜底写回索引失败: ${it.message}") }
    }

    private fun reconcileAudioIndex(currentSnapshot: MediaStoreSnapshot): List<MediaItem> {
        val indexedAt = System.currentTimeMillis()
        val existingIndexes = libraryIndexDao.getAllIndexes()
        val existingByStableKey = existingIndexes.associateBy(LibraryIndexEntity::stableKey)
        val existingByMediaId = existingIndexes.associateBy(LibraryIndexEntity::mediaId)
        // Audio 表主行 + Files 表补录行（DFF/DSF 等 MediaScanner 不索引的格式）；
        // 同一 stableKey 只保留一行，Audio 行在前（distinctBy 保第一个）
        val currentRows =
            (queryAudioCursorRows(
                selection = audioSelection(),
                selectionArgs = null,
                sortOrder = audioSortOrder(),
            ) + queryUnscannedFileRows())
                .distinctBy(AudioCursorRow::stableKey)
        val currentStableKeys = currentRows.mapTo(linkedSetOf(), AudioCursorRow::stableKey)
        // 1.2.26：MediaStore 重新发号时记录「旧 id → 新 id」，供收藏/统计/歌单/隐藏项迁移。
        val remappedMediaIds = LinkedHashMap<String, String>()
        val nextIndexes = currentRows.map { row ->
            val previous = existingByStableKey[row.stableKey] ?: existingByMediaId[row.mediaId]
            if (previous != null && previous.matches(row)) {
                if (previous.mediaId != row.mediaId) {
                    remappedMediaIds[previous.mediaId] = row.mediaId
                }
                previous.copy(
                    mediaId = row.mediaId,
                    uri = row.uri,
                    valid = true,
                )
            } else {
                row.toLibraryIndexEntity(indexedAt)
            }
        }
        val invalidStableKeys =
            existingIndexes
                .asSequence()
                .filter(LibraryIndexEntity::valid)
                .map(LibraryIndexEntity::stableKey)
                .filter { stableKey -> stableKey !in currentStableKeys }
                .toList()

        libraryIndexDatabase.runInTransaction {
            if (existingIndexes.isEmpty()) {
                libraryIndexDao.deleteAllIndexes()
            }
            if (nextIndexes.isNotEmpty()) {
                libraryIndexDao.upsertIndexes(nextIndexes)
            }
            invalidStableKeys.chunked(SqlBindParameterChunkSize).forEach { chunk ->
                libraryIndexDao.markInvalid(chunk, indexedAt)
            }
            libraryIndexDao.upsertSnapshot(
                LibraryIndexSnapshotEntity(
                    snapshotKey = currentSnapshot.storageKey,
                    updatedAt = indexedAt,
                )
            )
        }

        // 索引落库之后再迁业务数据，避免「索引回滚了但收藏已经改名」的错位
        if (remappedMediaIds.isNotEmpty()) {
            onMediaIdRemapped?.invoke(remappedMediaIds)
        }

        return libraryIndexDao.getValidIndexes().toMediaItems()
    }

    /**
     * 1.2.26：SQLite 绑定变量上限在 Android 11 之前是 999，整库「播放全部」时
     * `WHERE mediaId IN (?,?,…)` 会直接抛 SQLiteException（表现为点了没反应）。
     * 统一按 500 分块，与 [queryAudioIndexesByIds] 的口径保持一致。
     */
    private fun LibraryIndexDao.getValidIndexesByMediaIdsChunked(
        mediaIds: List<String>,
    ): List<LibraryIndexEntity> =
        if (mediaIds.size <= MediaStoreIdSelectionChunkSize) {
            getValidIndexesByMediaIds(mediaIds)
        } else {
            mediaIds.chunked(MediaStoreIdSelectionChunkSize).flatMap { getValidIndexesByMediaIds(it) }
        }

    private fun LibraryIndexDao.getValidIndexesByStableKeysChunked(
        stableKeys: List<String>,
    ): List<LibraryIndexEntity> =
        if (stableKeys.size <= MediaStoreIdSelectionChunkSize) {
            getValidIndexesByStableKeys(stableKeys)
        } else {
            stableKeys.chunked(MediaStoreIdSelectionChunkSize)
                .flatMap { getValidIndexesByStableKeys(it) }
        }

    private fun queryAudioIndexesByIds(mediaIds: List<Long>): List<LibraryIndexEntity> {        if (mediaIds.isEmpty()) {
            return emptyList()
        }
        val indexedAt = System.currentTimeMillis()
        return mediaIds
            .chunked(MediaStoreIdSelectionChunkSize)
            .flatMap { ids ->
                queryAudioCursorRows(
                    selection =
                        buildString {
                            append(audioSelection())
                            append(" AND ${MediaStore.Audio.Media._ID} IN (")
                            append(ids.joinToString(separator = ",") { "?" })
                            append(")")
                        },
                    selectionArgs = ids.map(Long::toString).toTypedArray(),
                    sortOrder = null,
                )
            }
            .map { row -> row.toLibraryIndexEntity(indexedAt) }
    }

    private fun queryAudioCursorRows(
        selection: String,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): List<AudioCursorRow> {
        val rows = mutableListOf<AudioCursorRow>()
        val externalVolumeRoots =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                externalVolumeRoots()
            } else {
                emptySet()
            }
        try {
            context.contentResolver
                .query(
                    audioCollection(),
                    audioItemProjection(),
                    selection,
                    selectionArgs,
                    sortOrder,
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.toAudioCursorRow(externalVolumeRoots)?.let(rows::add)
                    }
                }
        } catch (_: SecurityException) {
            return emptyList()
        }

        return rows
    }

    private fun Cursor.toAudioCursorRow(
        externalVolumeRoots: Set<ExternalVolumeRoot>
    ): AudioCursorRow? {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
        val storageLocation = audioStorageLocation(externalVolumeRoots)
        val displayName =
            getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return null
        val stableKey =
            stableAudioLibraryKey(
                storageLocation.volumeName,
                storageLocation.relativePath,
                displayName,
            ) ?: return null
        val durationMs = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
        val mimeType =
            getString(getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))?.takeIf {
                it.isNotBlank()
            }

        return AudioCursorRow(
            mediaId = id.toString(),
            stableKey = stableKey,
            uri = ContentUris.withAppendedId(audioCollection(), id).toString(),
            title = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
            artist = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)),
            album = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)),
            albumArtist =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST))
                } else {
                    null
                },
            albumId =
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)).takeIf { it > 0L },
            durationMs = durationMs,
            track = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)).takeIf { it > 0 },
            year = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)).takeIf { it > 0 },
            volumeName = storageLocation.volumeName,
            relativePath = storageLocation.relativePath,
            displayName = displayName,
            mimeType = mimeType,
            fileSize =
                getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)).takeIf {
                    it > 0L
                },
            dateAdded =
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)).takeIf {
                    it > 0L
                },
            dateModified =
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)).takeIf {
                    it > 0L
                },
            generationAdded =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.GENERATION_ADDED)).takeIf {
                        it > 0L
                    }
                } else {
                    null
                },
            generationModified =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.GENERATION_MODIFIED))
                        .takeIf { it > 0L }
                } else {
                    null
                },
        )
    }

    /**
     * 补录 MediaStore.Files 里「MediaScanner 不认识、进不了 Audio 表」的音频文件
     * （.dff/.dsf/.dts/.wv/.tak 等）。这些格式靠 [ApeTranscoder] 无损转码播放。
     *
     * mediaId 加 [UnscannedMediaIdPrefix] 前缀（如 "f123"），与 Audio 表 _ID 区隔防撞号；
     * 下游对 mediaId 做 toLongOrNull 的路径（封面、标签编辑）天然跳过它们。
     */
    private fun queryUnscannedFileRows(): List<AudioCursorRow> {
        val rows = mutableListOf<AudioCursorRow>()
        val externalVolumeRoots =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                externalVolumeRoots()
            } else {
                emptySet()
            }
        val selection =
            UnscannedFileExtensions.joinToString(separator = " OR ") { ext ->
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.$ext'"
            }
        val projection =
            mutableListOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                // Files 表有同名 duration 列（未知格式多为 0）
                MediaStore.Audio.Media.DURATION,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection += MediaStore.MediaColumns.GENERATION_ADDED
            projection += MediaStore.MediaColumns.GENERATION_MODIFIED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.VOLUME_NAME
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            projection += MediaStore.MediaColumns.DATA
        }
        try {
            context.contentResolver
                .query(
                    MediaStore.Files.getContentUri(ExternalVolumeName),
                    projection.toTypedArray(),
                    selection,
                    null,
                    null,
                )
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.toUnscannedFileRow(externalVolumeRoots)?.let(rows::add)
                    }
                }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            // 某些机型 Files 表缺 duration 列等：整表放弃补录，不影响主曲库
            return emptyList()
        }
        return rows
    }

    private fun Cursor.toUnscannedFileRow(
        externalVolumeRoots: Set<ExternalVolumeRoot>
    ): AudioCursorRow? {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
        val storageLocation = audioStorageLocation(externalVolumeRoots)
        val displayName =
            getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                ?.trim()
                ?.takeIf(String::isNotEmpty) ?: return null
        val stableKey =
            stableAudioLibraryKey(
                storageLocation.volumeName,
                storageLocation.relativePath,
                displayName,
            ) ?: return null
        val durationMs =
            runCatching { getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)) }
                .getOrDefault(0L)
                .coerceAtLeast(0L)
        val mimeType =
            runCatching { getString(getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        return AudioCursorRow(
            mediaId = UnscannedMediaIdPrefix + id,
            stableKey = stableKey,
            uri =
                ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri(ExternalVolumeName),
                    id,
                ).toString(),
            // [1.4.24·R28] Files 表没有 TITLE/ARTIST/ALBUM。标题仍从文件名推断；
            // **专辑不再用所在文件夹名兜底**——用户拍板「允许没有专辑信息」，
            // 文件夹名不是专辑名，硬塞进去会造出大量假的「专辑」。
            // 空专辑统一进「未知专辑」卡，编辑清空后也不会被文件夹名回填。
            title = displayName.substringBeforeLast('.', displayName),
            artist = null,
            album = null,
            albumArtist = null,
            albumId = null,
            durationMs = durationMs,
            track = null,
            year = null,
            volumeName = storageLocation.volumeName,
            relativePath = storageLocation.relativePath,
            displayName = displayName,
            mimeType = mimeType,
            fileSize =
                runCatching { getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)) }
                    .getOrDefault(0L)
                    .takeIf { it > 0L },
            dateAdded =
                runCatching { getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)) }
                    .getOrDefault(0L)
                    .takeIf { it > 0L },
            dateModified =
                runCatching { getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) }
                    .getOrDefault(0L)
                    .takeIf { it > 0L },
            generationAdded =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_ADDED))
                    }.getOrDefault(0L).takeIf { it > 0L }
                } else {
                    null
                },
            generationModified =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching {
                        getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED))
                    }.getOrDefault(0L).takeIf { it > 0L }
                } else {
                    null
                },
        )
    }

    private fun Cursor.audioStorageLocation(
        externalVolumeRoots: Set<ExternalVolumeRoot>
    ): AudioStorageLocation {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return AudioStorageLocation(
                volumeName =
                    getString(getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
                        ?.trim()
                        ?.takeIf(String::isNotEmpty) ?: StableKeyVolumeFallback,
                relativePath =
                    normalizeLibraryRelativePath(
                        getString(getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                    ),
            )
        }

        val dataPath = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
        val audioFile = dataPath?.let(::File)
        val volumeRoot = audioFile?.let { file ->
            externalVolumeRoots.firstOrNull { candidate ->
                file.absolutePath == candidate.root.absolutePath ||
                    file.absolutePath.startsWith("${candidate.root.absolutePath}${File.separator}")
            }
        }
        return AudioStorageLocation(
            volumeName = volumeRoot?.mediaStoreVolumeName ?: StableKeyVolumeFallback,
            relativePath =
                normalizeLibraryRelativePath(
                    if (audioFile != null && volumeRoot != null) {
                        audioFile.relativePathFromVolumeRoot(volumeRoot.root)
                    } else {
                        audioFile?.parentFile?.absolutePath
                    }
                ),
        )
    }

    private fun AudioCursorRow.toLibraryIndexEntity(indexedAt: Long): LibraryIndexEntity {
        val normalizedTitle =
            title?.repairMetadataEncoding()?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unknown_song_title)
        val normalizedArtist =
            artist?.repairMetadataEncoding()?.takeIf {
                it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
            } ?: context.getString(R.string.unknown_artist)
        // 1.2.19：与歌手一致过滤 MediaStore 的 "<unknown>" 占位，避免专辑栏直接显示这串英文
        val normalizedAlbum =
            album?.repairMetadataEncoding()?.takeIf {
                it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
            }
        // [1.4.16·F5] 列表元数据清洗：MediaStore 对 WAV/DFF/APE 的标签极脏（title 塞进
        // "歌手 - 歌名"、artist=未知、album 乱填成歌手/hash），而编辑框读文件标签是对的。
        // 这里就地做高置信度修正：拆出歌手、脏专辑留空。tagOverride 在更上游构造 MediaItem
        // 时覆盖，不受影响。
        val sanitized =
            MetadataSanitizer.clean(
                title = normalizedTitle,
                artist = normalizedArtist.takeIf { it != context.getString(R.string.unknown_artist) },
                album = normalizedAlbum,
            )
        val finalTitle = sanitized.title ?: normalizedTitle
        val finalArtist = sanitized.artist ?: context.getString(R.string.unknown_artist)
        // [1.4.25·R64] MediaStore 专辑被判脏留空（原本有值、清洗后变 null）时，
        // 改读文件内嵌标签兜底——用户编辑弹窗里显示的就是这个值，能救回真专辑。
        val finalAlbum =
            sanitized.album
                ?: if (normalizedAlbum != null) {
                    rescuedAlbum(this, sanitized.artist, finalTitle)
                } else {
                    null
                }
        val normalizedAlbumArtist =
            albumArtist?.repairMetadataEncoding()?.takeIf {
                it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
            }
        val titleSortKey = LibraryTitleNormalizer.normalize(finalTitle)
        return LibraryIndexEntity(
            mediaId = mediaId,
            stableKey = stableKey,
            uri = uri,
            title = finalTitle,
            artist = finalArtist,
            album = finalAlbum,
            albumArtist = normalizedAlbumArtist,
            albumId = albumId,
            durationMs = durationMs,
            track = track,
            year = year,
            volumeName = volumeName,
            relativePath = relativePath,
            displayName = displayName,
            mimeType = mimeType,
            fileSize = fileSize,
            dateAdded = dateAdded,
            dateModified = dateModified,
            generationAdded = generationAdded,
            generationModified = generationModified,
            titleSortKey = titleSortKey,
            titleSection = titleSortKey.libraryTitleSection(),
            qualityBadge = resolveAudioQualityBadge(displayName, mimeType),
            indexedAt = indexedAt,
            valid = true,
        )
    }

    private fun LibraryIndexEntity.matches(row: AudioCursorRow): Boolean {
        if (stableKey != row.stableKey ||
            dateModified != row.dateModified ||
            generationModified != row.generationModified ||
            durationMs != row.durationMs ||
            relativePath != row.relativePath ||
            displayName != row.displayName ||
            mimeType != row.mimeType ||
            fileSize != row.fileSize
        ) {
            return false
        }
        // 1.2.16 补强：title/artist/album 也参与比较（归一走同一管道）——
        // 否则 MediaStore.update 只改标签、文件属性未变时，索引重建会复用旧行，
        // 列表继续显示旧歌名/歌手/专辑
        val fresh = row.toLibraryIndexEntity(indexedAt)
        // 1.2.19：音质徽章也参与比较——新增 DSD 徽章后，旧索引行如果不重建，
        // DFF/DSF 会一直沿用旧规则算出的 WAV 徽章（用户看到的「红色 WAV 标」）
        return title == fresh.title &&
            artist == fresh.artist &&
            album == fresh.album &&
            qualityBadge == fresh.qualityBadge
    }

    private fun List<LibraryIndexEntity>.toMediaItems(
        playbackStats: Map<String, PlaybackStatsRecord>? = null
    ): List<MediaItem> {
        val resolvedPlaybackStats =
            playbackStats ?: runCatching(playbackStatsProvider).getOrDefault(emptyMap())
        return map { index -> index.toMediaItem(resolvedPlaybackStats[index.mediaId]) }
    }

    private fun LibraryIndexEntity.toMediaItem(stats: PlaybackStatsRecord?): MediaItem {
        // 1.2.16：叠加歌曲信息编辑的应用内覆盖层——媒体扫描器追上来之前，
        // 各列表/播放条就显示用户刚保存的标题/歌手/专辑（见 TagOverrideStore）。
        val tagOverride = com.smartisan.music.data.tags.TagOverrideStore.get(context, stableKey, mediaId)
        val playCount = stats?.playCount?.takeIf { it > 0L }
        val score = stats?.score?.takeIf { it > 0 }
        val extras =
            Bundle().apply {
                putString(MediaIdExtraKey, mediaId)
                if (displayName.isNotBlank()) {
                    putString(DisplayNameExtraKey, displayName)
                }
                putString(StableKeyExtraKey, stableKey)
                putString(TitleSortKeyExtraKey, titleSortKey)
                putString(TitleSectionExtraKey, titleSection)
                if (relativePath.isNotBlank()) {
                    putString(RelativePathExtraKey, relativePath)
                }
                if (!mimeType.isNullOrBlank()) {
                    putString(MimeTypeExtraKey, mimeType)
                }
                fileSize?.takeIf { it > 0L }?.let { putLong(FileSizeExtraKey, it) }
                albumId?.let { putLong(AlbumIdExtraKey, it) }
                dateAdded?.let { putLong(DateAddedExtraKey, it) }
                generationAdded?.let { putLong(GenerationAddedExtraKey, it) }
                if (!qualityBadge.isNullOrBlank()) {
                    putString(AudioQualityBadgeExtraKey, qualityBadge)
                }
                val probed = FormatCache.get(uri, durationMs ?: 0L)
                if (probed != null) {
                    if (probed.codec.isNotBlank()) {
                        putString(FormatExtraKey, probed.codec)
                    }
                    putInt(SampleRateHzExtraKey, probed.sampleRateHz)
                    putInt(BitrateBpsExtraKey, probed.bitrateBps)
                    putInt(BitsPerSampleExtraKey, probed.bitsPerSample)
                    if (probed.channelCount > 0) {
                        putInt(ChannelCountExtraKey, probed.channelCount)
                    }
                    putInt(
                        QualityTierExtraKey,
                        AudioQualityTiers.tierOf(
                            container = "",
                            codec = probed.codec,
                            sampleRateHz = probed.sampleRateHz,
                            bitsPerSample = probed.bitsPerSample,
                            bitrateBps = probed.bitrateBps,
                        ).rank,
                    )
                }
                if (playCount != null) {
                    putLong(PlayCountExtraKey, playCount)
                }
                if (score != null) {
                    putLong(RatingExtraKey, score.toLong())
                }
            }

        // [1.4.24·R28] 覆盖层三态语义：null=用户没改（用原值）；""=用户显式清空（必须为空，
        // 不能再回填文件夹名/原标签）；非空=用新值。旧实现用 `takeIf { isNotBlank() } ?: album`
        // 把"清空"和"没改"混为一谈，用户清掉专辑后一刷新就被原值填回来。
        fun effective(original: String?, override: String?): String? =
            if (override == null) original else override.takeIf { it.isNotBlank() }
        val effectiveTitle = effective(title, tagOverride?.title)
        val effectiveArtist = effective(artist, tagOverride?.artist)
        val effectiveAlbum = effective(album, tagOverride?.album)
        // DFF/DSF 等补录行 MediaStore 无时长：用探测出的文件头时长兜底（歌曲信息/列表显示）
        val effectiveDurationMs =
            durationMs.takeIf { it != null && it > 0L }
                ?: probedDurationMsFallback(extras, uri)
        val metadataBuilder =
            MediaMetadata.Builder()
                .setTitle(effectiveTitle)
                .setDisplayTitle(effectiveTitle)
                .setArtist(effectiveArtist)
                .setSubtitle(effectiveArtist)
                .setDurationMs(effectiveDurationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)

        if (!effectiveAlbum.isNullOrBlank()) {
            metadataBuilder.setAlbumTitle(effectiveAlbum)
        }
        if (!albumArtist.isNullOrBlank()) {
            metadataBuilder.setAlbumArtist(albumArtist)
        }
        track?.let(metadataBuilder::setTrackNumber)
        year?.let(metadataBuilder::setReleaseYear)
        mediaId.toLongOrNull()?.let { id ->
            metadataBuilder.setArtworkUri(trackArtworkUri(id))
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    /** 探测时长兜底：MediaStore 时长缺失时读 FormatCache 的文件头解析时长。 */
    private fun probedDurationMsFallback(extras: Bundle, uriString: String): Long? {
        val probed =
            runCatching { FormatCache.get(uriString, 0L) }.getOrNull() ?: return null
        val probedDuration = probed.durationMs.takeIf { it > 0L } ?: return null
        extras.putLong(DurationExtraKey, probedDuration)
        return probedDuration
    }

    private fun getValidCachedItemsById(mediaIds: Set<String>): Map<String, MediaItem> {
        if (mediaIds.isEmpty()) {
            return emptyMap()
        }
        return synchronized(audioCacheLock) {
            val cache = audioCache
            if (cache.items.isEmpty() || cache.snapshot != currentMediaStoreSnapshot()) {
                return@synchronized emptyMap()
            }
            cache.items
                .asSequence()
                .filter { item -> item.mediaId in mediaIds }
                .associateBy(MediaItem::mediaId)
        }
    }

    private fun playbackStatsForIds(mediaIds: Set<String>): Map<String, PlaybackStatsRecord> {
        if (mediaIds.isEmpty()) {
            return emptyMap()
        }
        return runCatching {
                playbackStatsByIdsProvider(mediaIds)
            }
            .getOrDefault(emptyMap())
    }

    companion object {
        const val ROOT_ID = "root"
        const val StableKeyExtraKey = "com.smartisan.music.extra.STABLE_KEY"
        const val MediaIdExtraKey = "com.smartisan.music.extra.MEDIA_ID"
        const val AlbumIdExtraKey = "com.smartisan.music.extra.ALBUM_ID"
        const val RelativePathExtraKey = "com.smartisan.music.extra.RELATIVE_PATH"
        const val MimeTypeExtraKey = "com.smartisan.music.extra.MIME_TYPE"
        const val FileSizeExtraKey = "com.smartisan.music.extra.FILE_SIZE"
        const val DateAddedExtraKey = "com.smartisan.music.extra.DATE_ADDED"
        const val GenerationAddedExtraKey = "com.smartisan.music.extra.GENERATION_ADDED"
        const val TitleSortKeyExtraKey = "com.smartisan.music.extra.TITLE_SORT_KEY"
        const val TitleSectionExtraKey = "com.smartisan.music.extra.TITLE_SECTION"
        const val AudioQualityBadgeExtraKey = "com.smartisan.music.extra.AUDIO_QUALITY_BADGE"
        const val SampleRateHzExtraKey = "com.smartisan.music.extra.SAMPLE_RATE_HZ"
        const val BitrateBpsExtraKey = "com.smartisan.music.extra.BITRATE_BPS"
        const val BitsPerSampleExtraKey = "com.smartisan.music.extra.BITS_PER_SAMPLE"
        const val ChannelCountExtraKey = "com.smartisan.music.extra.CHANNEL_COUNT"
        const val QualityTierExtraKey = "com.smartisan.music.extra.QUALITY_TIER"
        const val FormatExtraKey = "com.smartisan.music.extra.FORMAT"
        const val DisplayNameExtraKey = "com.smartisan.music.extra.DISPLAY_NAME"
        const val DurationExtraKey = "com.smartisan.music.extra.DURATION_MS"
        private val formatProbeExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "audio-format-probe").apply { isDaemon = true }
            }
        const val PlayCountExtraKey = "com.smartisan.music.extra.PLAY_COUNT"
        const val RatingExtraKey = "com.smartisan.music.extra.RATING"
        const val AudioQualityBadgeFlac = "flac"
        const val AudioQualityBadgeApe = "ape"
        const val AudioQualityBadgeWav = "wav"
        const val AudioQualityBadgeAiff = "aiff"
        const val AudioQualityBadgeAlac = "alac"
        const val AudioQualityBadgeCue = "cue"
        /**
         * 显示为 DSD 徽章的扩展名。
         * 1.2.19：只保留真正的 DSD 容器 —— 此前把 WavPack/TAK/Shorten/TTA/TrueHD 也算进来，
         * 这些无损格式会被打上 DSD 标（与「红色 WAV 标」一样属于误导）。
         */
        private val TranscodeBadgeExtensions =
            setOf("dff", "dsf", "dsd", "dst", "dts", "dtshd", "dtshdma")
        private const val MediaScannerWaitTimeoutSeconds = 30L
        private const val MediaStoreIdSelectionChunkSize = 500
        private const val SqlBindParameterChunkSize = 900
        /** [1.4.25·R64] 脏专辑兜底单轮最多读多少个文件（后台线程，避免一次读太多拖慢探测）。 */
        private const val AlbumRescueBatchSize = 200
        private const val ExternalVolumeName = "external"
        private const val MediaStoreFingerprintSeed = 1_125_899_906_842_597L
        private const val MediaStoreFingerprintMultiplier = 31L
        private val AudioFileExtensions =
            setOf(
                "aac",
                "aif",
                "aiff",
                "alac",
                "amr",
                "ape",
                "flac",
                "m4a",
                "m4b",
                "mid",
                "midi",
                "mka",
                "mp3",
                "oga",
                "ogg",
                "opus",
                "wav",
                "wma",
            )

        /** Files 补录行 mediaId 前缀（与 Audio 表数字 _ID 区隔防撞）。 */
        const val UnscannedMediaIdPrefix = "f"

        /**
         * MediaScanner 不索引进 Audio 表、需要走 Files 补录的扩展名
         * （与 ApeTranscoder.TranscodeExtensions 覆盖的格式对应）。
         */
        private val UnscannedFileExtensions =
            setOf(
                "dff", "dsf", "dsd", "dst",
                "dts", "dtshd", "dtshdma",
                "tta", "wv", "wvc", "tak", "shn",
                "ac3", "eac3", "ddp", "a52",
                "mlp", "thd", "truehd",
                "mpc", "mpp", "mp+",
                "ape", "mac", "apl",
                // 1.2.19 补齐：转码链支持但此前漏登记，导致这些格式跟 DFF 一样「搜不到」
                "aiff", "aif", "aifc", "au", "caf", "w64", "voc", "wsd", "msf", "vqf",
            )

        fun albumArtworkUri(albumId: Long): Uri {
            return ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
        }

        fun trackArtworkUri(mediaId: Long): Uri {
            return Uri.parse("content://media/external/audio/media/$mediaId/albumart")
        }

        internal fun resolveAudioQualityBadge(
            displayName: String?,
            mimeType: String?,
        ): String? {
            val extension =
                displayName
                    ?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.lowercase(Locale.ROOT)
                    .orEmpty()
            val normalizedMimeType = mimeType?.lowercase(Locale.ROOT).orEmpty()

            return when {
                // DSD 系列（DFF/DSF/DSD 等）常被 MediaStore 误标成 audio/wav / audio/x-wav，
                // 必须用扩展名先判定，否则会落到 WAV 分支显示成红色 WAV 标（用户反馈）。
                extension in TranscodeBadgeExtensions -> "dsd"
                extension == AudioQualityBadgeFlac ||
                    normalizedMimeType.contains(AudioQualityBadgeFlac) -> {
                    AudioQualityBadgeFlac
                }
                extension == AudioQualityBadgeApe ||
                    normalizedMimeType.contains(AudioQualityBadgeApe) ||
                    normalizedMimeType.contains("monkeys-audio") -> {
                    AudioQualityBadgeApe
                }
                extension == AudioQualityBadgeWav ||
                    normalizedMimeType.contains(AudioQualityBadgeWav) ||
                    normalizedMimeType.contains("wave") -> {
                    AudioQualityBadgeWav
                }
                extension == AudioQualityBadgeAiff ||
                    extension == "aif" ||
                    normalizedMimeType.contains(AudioQualityBadgeAiff) -> {
                    AudioQualityBadgeAiff
                }
                extension == AudioQualityBadgeAlac ||
                    normalizedMimeType.contains(AudioQualityBadgeAlac) -> {
                    AudioQualityBadgeAlac
                }
                extension == AudioQualityBadgeCue ||
                    normalizedMimeType.contains(AudioQualityBadgeCue) -> {
                    AudioQualityBadgeCue
                }
                else -> null
            }
        }
    }

    data class RefreshResult(
        val items: List<MediaItem>,
        val scannedFileCount: Int,
        val failedScanCount: Int,
        val scanTimedOut: Boolean,
        val mediaStoreRefreshSucceeded: Boolean,
    ) {
        // ContentResolver.refresh() 是 provider best-effort 提示；MediaStore 不支持时会返回 false。
        // 手动重扫是否成功应以 MediaScanner 回调和重新查询结果为准，避免误报失败。
        val successful: Boolean
            get() = !scanTimedOut && failedScanCount == 0
    }

    private data class AudioCache(
        val snapshot: MediaStoreSnapshot? = null,
        val items: List<MediaItem> = emptyList(),
    )

    private fun currentMediaStoreSnapshot(): MediaStoreSnapshot {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return currentApi30MediaStoreSnapshot()
        }
        val fingerprint = currentMediaStoreFingerprint()
        val versions =
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                Api29.mediaStoreVersions(context)
            } else {
                mapOf(ExternalVolumeName to MediaStore.getVersion(context))
            }
        return MediaStoreSnapshot(
            versions.mapValues { (_, version) ->
                VolumeSnapshot(
                    version = version,
                    generation = fingerprint,
                )
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun currentApi30MediaStoreSnapshot(): MediaStoreSnapshot {
        val volumes = runCatching {
            MediaStore.getExternalVolumeNames(context)
        }
            .getOrDefault(emptySet())
        return MediaStoreSnapshot(
            volumes.sorted().associateWith { volume ->
                VolumeSnapshot(
                    version = MediaStore.getVersion(context, volume),
                    generation = MediaStore.getGeneration(context, volume),
                )
            }
        )
    }

    private fun currentMediaStoreFingerprint(): Long {
        var fingerprint = MediaStoreFingerprintSeed
        runCatching {
            context.contentResolver.query(
                audioCollection(),
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.SIZE,
                ),
                audioSelection(),
                null,
                "${MediaStore.Audio.Media._ID} ASC",
            )
        }
            .getOrNull()
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dateModifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    fingerprint =
                        fingerprint * MediaStoreFingerprintMultiplier + cursor.getLong(idColumn)
                    fingerprint =
                        fingerprint * MediaStoreFingerprintMultiplier +
                            cursor.getLong(dateModifiedColumn)
                    fingerprint =
                        fingerprint * MediaStoreFingerprintMultiplier + cursor.getLong(sizeColumn)
                }
            }
        return fingerprint
    }

    private data class MediaStoreSnapshot(val volumes: Map<String, VolumeSnapshot>) {
        val storageKey: String =
            volumes.entries.joinToString("\u001e") { (volume, snapshot) ->
                listOf(volume, snapshot.version, snapshot.generation.toString())
                    .joinToString("\u001f")
            }
    }

    private data class VolumeSnapshot(
        val version: String,
        val generation: Long,
    )

    private data class IndexedAudioSnapshot(val fileKeys: Set<String>)

    private data class ExternalVolumeRoot(
        val root: File,
        val mediaStoreVolumeName: String,
    )

    private data class AudioStorageLocation(
        val volumeName: String,
        val relativePath: String,
    )

    private data class ScanResult(
        val scannedFileCount: Int,
        val failedScanCount: Int,
        val timedOut: Boolean,
    )

    private data class AudioCursorRow(
        val mediaId: String,
        val stableKey: String,
        val uri: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val albumId: Long?,
        val durationMs: Long,
        val track: Int?,
        val year: Int?,
        val volumeName: String,
        val relativePath: String,
        val displayName: String,
        val mimeType: String?,
        val fileSize: Long?,
        val dateAdded: Long?,
        val dateModified: Long?,
        val generationAdded: Long?,
        val generationModified: Long?,
    )

    private fun audioCollection(): Uri {
        return audioMediaCollectionUri()
    }

    private fun audioItemProjection(): Array<String> {
        val projection =
            mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection += MediaStore.Audio.Media.ALBUM_ARTIST
            projection += MediaStore.Audio.Media.GENERATION_ADDED
            projection += MediaStore.Audio.Media.GENERATION_MODIFIED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.VOLUME_NAME
            projection += MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            projection += MediaStore.MediaColumns.DATA
        }
        return projection.toTypedArray()
    }

    private fun audioSelection(): String {
        return buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} > 0")
        }
    }

    private fun audioSortOrder(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaStore.Audio.Media.GENERATION_ADDED} DESC"
        } else {
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        }
    }

    private fun queryIndexedAudioSnapshot(): IndexedAudioSnapshot {
        val fileKeys = linkedSetOf<String>()
        val externalVolumeRoots =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                externalVolumeRoots()
            } else {
                emptySet()
            }
        val projection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
            }

        runCatching {
            context.contentResolver.query(
                audioCollection(),
                projection,
                audioSelection(),
                null,
                null,
            )
        }
            .getOrNull()
            ?.use { cursor ->
                val displayNameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val storageLocation = cursor.audioStorageLocation(externalVolumeRoots)
                    val displayName = cursor.getString(displayNameColumn)
                    stableAudioLibraryKey(
                            storageLocation.volumeName,
                            storageLocation.relativePath,
                            displayName,
                        )
                        ?.let(fileKeys::add)
                }
            }

        return IndexedAudioSnapshot(fileKeys = fileKeys)
    }

    private fun discoverUnindexedAudioPaths(indexedSnapshot: IndexedAudioSnapshot): List<String> {
        val pendingPaths = linkedSetOf<String>()
        externalVolumeRoots().forEach { volumeRoot ->
            volumeRoot.root
                .walkTopDown()
                .onEnter { directory -> shouldEnterDirectory(volumeRoot.root, directory) }
                .onFail { _, _ -> }
                .forEach { candidate ->
                    if (
                        candidate.name.startsWith('.') ||
                            !candidate.hasAudioCandidateExtension() ||
                            !candidate.isFile ||
                            !candidate.canRead()
                    ) {
                        return@forEach
                    }
                    val relativePathFromRoot =
                        candidate.relativeToOrNull(volumeRoot.root)?.invariantSeparatorsPath
                            ?: return@forEach
                    if (shouldSkipMediaScannerPath(relativePathFromRoot)) {
                        return@forEach
                    }
                    val relativePath = candidate.relativePathFromVolumeRoot(volumeRoot.root)
                    val key =
                        stableAudioLibraryKey(
                            volumeName = volumeRoot.mediaStoreVolumeName,
                            relativePath = relativePath,
                            displayName = candidate.name,
                        ) ?: return@forEach
                    if (key !in indexedSnapshot.fileKeys) {
                        pendingPaths += candidate.absolutePath
                    }
                }
        }
        return pendingPaths.toList()
    }

    private fun externalVolumeRoots(): Set<ExternalVolumeRoot> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return context
            .getExternalFilesDirs(null)
            .asSequence()
            .filterNotNull()
            .mapNotNull { appDir ->
                val root =
                    volumeRootFromAppDir(appDir)?.takeIf { it.isDirectory }
                        ?: return@mapNotNull null
                val storageVolume = storageManager?.getStorageVolume(root)
                val mediaStoreVolumeName =
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                            storageVolume?.mediaStoreVolumeName
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                            Api29.mediaStoreVolumeName(storageVolume, root)
                        }
                        else -> root.absolutePath
                    } ?: root.absolutePath
                ExternalVolumeRoot(
                    root = root,
                    mediaStoreVolumeName = mediaStoreVolumeName,
                )
            }
            .toSet()
    }

    private fun volumeRootFromAppDir(appDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val path = appDir.absolutePath
        val markerIndex = path.indexOf(marker)
        if (markerIndex <= 0) {
            return null
        }
        return File(path.substring(0, markerIndex))
    }

    private fun shouldEnterDirectory(scanRoot: File, directory: File): Boolean {
        if (directory == scanRoot) {
            return true
        }
        if (!directory.canRead()) {
            return false
        }
        if (directory.name.startsWith('.')) {
            return false
        }
        val relativePath = directory.relativeToOrNull(scanRoot)?.invariantSeparatorsPath.orEmpty()
        if (shouldSkipMediaScannerPath(relativePath)) {
            return false
        }
        return when {
            relativePath == "Android/data" || relativePath.startsWith("Android/data/") -> false
            relativePath == "Android/obb" || relativePath.startsWith("Android/obb/") -> false
            else -> true
        }
    }

    private fun File.relativePathFromVolumeRoot(volumeRoot: File): String? {
        val parent = parentFile ?: return null
        if (parent == volumeRoot) {
            return null
        }
        return parent
            .relativeToOrNull(volumeRoot)
            ?.invariantSeparatorsPath
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it/" }
    }

    private fun scanAudioFiles(paths: List<String>): ScanResult {
        if (paths.isEmpty()) {
            return ScanResult(
                scannedFileCount = 0,
                failedScanCount = 0,
                timedOut = false,
            )
        }
        val scannedFileCount = AtomicInteger(0)
        val failedScanCount = AtomicInteger(0)
        var timedOut = false

        paths.chunked(128).forEach { batch ->
            val latch = CountDownLatch(batch.size)
            val acceptingCallbacks = AtomicBoolean(true)
            val completedCallbackCount = AtomicInteger(0)
            val mimeTypes = batch.map(::resolveAudioMimeType).toTypedArray()
            MediaScannerConnection.scanFile(
                context,
                batch.toTypedArray(),
                mimeTypes,
            ) { _, uri ->
                if (acceptingCallbacks.get()) {
                    completedCallbackCount.incrementAndGet()
                    if (uri == null) {
                        failedScanCount.incrementAndGet()
                    } else {
                        scannedFileCount.incrementAndGet()
                    }
                }
                latch.countDown()
            }
            if (!latch.await(MediaScannerWaitTimeoutSeconds, TimeUnit.SECONDS)) {
                acceptingCallbacks.set(false)
                timedOut = true
                failedScanCount.addAndGet(batch.size - completedCallbackCount.get())
            }
        }

        return ScanResult(
            scannedFileCount = scannedFileCount.get(),
            failedScanCount = failedScanCount.get(),
            timedOut = timedOut,
        )
    }

    private fun requestMediaStoreRefresh(): Boolean {
        return runCatching {
                context.contentResolver.refresh(
                    audioCollection(),
                    null,
                    null,
                )
            }
            .getOrDefault(false)
    }

    private fun File.hasAudioCandidateExtension(): Boolean {
        val extension = extension.lowercase(Locale.ROOT)
        return extension in AudioFileExtensions
    }

    private fun resolveAudioMimeType(path: String): String? {
        val extension =
            path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) {
            return null
        }
        return when (extension) {
            AudioQualityBadgeApe -> "audio/ape"
            AudioQualityBadgeAlac -> "audio/alac"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object Api29 {
    fun mediaStoreVersions(context: Context): Map<String, String> {
        return runCatching {
            MediaStore.getExternalVolumeNames(context)
                .sorted()
                .associateWith { volume -> MediaStore.getVersion(context, volume) }
                .takeIf(Map<String, String>::isNotEmpty)
        }
            .getOrNull() ?: mapOf(MediaStore.VOLUME_EXTERNAL to MediaStore.getVersion(context))
    }

    fun mediaStoreVolumeName(storageVolume: StorageVolume?, root: File): String {
        return when {
            storageVolume?.isPrimary == true -> MediaStore.VOLUME_EXTERNAL_PRIMARY
            !storageVolume?.uuid.isNullOrBlank() ->
                storageVolume.uuid.orEmpty().lowercase(Locale.ROOT)
            else -> root.absolutePath
        }
    }
}

private object LibraryTitleNormalizer {
    private val combiningMarks = "\\p{Mn}+".toRegex()

    @Synchronized
    fun normalize(title: String): String {
        val trimmed = title.trim()
        val transliterated = HanLatinTransliterator.transliterate(trimmed)
        return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}

internal fun String.repairMetadataEncoding(): String {
    if (isEmpty() || !looksLikeMojibake()) {
        return this
    }
    return MetadataRepairCharsets.firstNotNullOfOrNull { charset ->
        repairMojibake(charset)
    } ?: this
}

private fun String.repairMojibake(charset: Charset): String? {
    return toByteArrayOrNull(charset)
        ?.let { bytes -> String(bytes, StandardCharsets.UTF_8) }
        ?.takeIf { repaired ->
            repaired.isNotEmpty() &&
                repaired != this &&
                ReplacementCharacter !in repaired &&
                (repaired.containsCjkOrFullWidth() || !repaired.looksLikeMojibake())
        }
}

private fun String.toByteArrayOrNull(charset: Charset): ByteArray? {
    return runCatching {
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(this))
        ByteArray(buffer.remaining()).also(buffer::get)
    }
        .getOrNull()
}

private fun String.looksLikeMojibake(): Boolean {
    return MojibakeMarkers.any(::contains)
}

private fun String.containsCjkOrFullWidth(): Boolean {
    return any { char ->
        val code = char.code
        code in 0x3040..0x30FF ||
            code in 0x3400..0x9FFF ||
            code in 0x1100..0x11FF ||
            code in 0xAC00..0xD7AF ||
            code in 0xFF01..0xFFEF
    }
}

private val MetadataRepairCharsets =
    listOf(
        StandardCharsets.ISO_8859_1,
        Charset.forName("windows-1252"),
        Charset.forName("windows-1250"),
    )

private val MojibakeMarkers = listOf("Ã", "Â", "ã", "ď", "ï", "æ", "å", "¤", "½", "ž")
private const val ReplacementCharacter = '\uFFFD'

private fun String.libraryTitleSection(): String {
    val firstLetter =
        firstOrNull { char ->
            char.isLetterOrDigit()
        } ?: return "#"
    val upper = firstLetter.uppercaseChar()
    return if (upper in 'A'..'Z') {
        upper.toString()
    } else {
        "#"
    }
}

internal fun stableAudioLibraryKey(
    volumeName: String?,
    relativePath: String?,
    displayName: String?,
): String? {
    val normalizedDisplayName = displayName?.trim().orEmpty()
    if (normalizedDisplayName.isEmpty()) {
        return null
    }
    val normalizedVolumeName =
        volumeName?.trim()?.takeIf(String::isNotEmpty) ?: StableKeyVolumeFallback
    return "$normalizedVolumeName:${normalizeLibraryRelativePath(relativePath)}$normalizedDisplayName"
        .lowercase(Locale.ROOT)
}

private fun normalizeLibraryRelativePath(relativePath: String?): String {
    return relativePath
        ?.replace('\\', '/')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.endsWith('/')) it else "$it/" }
        .orEmpty()
}

internal fun shouldSkipMediaScannerPath(relativePath: String): Boolean {
    return relativePath.replace('\\', '/').split('/').filter(String::isNotEmpty).any { segment ->
        segment.startsWith('.')
    }
}

private const val StableKeyVolumeFallback = "unknown-volume"
