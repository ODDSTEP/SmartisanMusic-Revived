package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.smartisan.music.data.library.LibraryIndexEntity
import com.smartisan.music.diagnostics.AppLog
import com.smartisan.music.platform.media.AudioFormatProbe
import com.smartisan.music.platform.media.FormatCache
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * 补录行内嵌标签回填（1.2.19）。
 *
 * 背景：DFF/DSF/WavPack/TAK/Musepack/APE(部分 ROM) 等格式进不了 MediaStore.Audio 表，
 * 只能由 [LocalAudioLibrary.queryUnscannedFileRows] 从 Files 表补录。补录行没有
 * TITLE/ARTIST/ALBUM/DURATION，此前只能「标题=文件名、歌手=未知、专辑=文件夹名、时长=0」，
 * 于是出现「歌曲信息显示不全、查看专辑置灰、时长为空、按歌手搜不到」等一系列问题。
 *
 * 这些文件本身往往带完整标签（DSF 内嵌 ID3v2、APE 内嵌 APEv2、WavPack 内嵌 APEv2…）。
 * 这里用 jaudiotagger 直接读文件标签与音频头，把标题/歌手/专辑/年份/曲目号/时长补回索引，
 * 并把采样率/位深/码率写进 [FormatCache]（这些格式系统的 MediaMetadataRetriever 读不出）。
 *
 * 只在后台线程（曲库探测同一 executor）执行，单文件异常静默跳过，绝不阻塞曲库装载。
 */
internal object UnscannedTagBackfill {

    private const val TAG = "TagBackfill"
    private const val MaxFilesPerRun = 400
    private const val PlaceholderUnknown = "<unknown>"

    fun run(
        context: Context,
        indexes: List<LibraryIndexEntity>,
        upsert: (List<LibraryIndexEntity>) -> Unit,
    ): Int {
        val pending =
            indexes.filter { it.mediaId.startsWith(LocalAudioLibrary.UnscannedMediaIdPrefix) }
        if (pending.isEmpty()) return 0
        val updated = ArrayList<LibraryIndexEntity>()
        var scanned = 0
        for (entity in pending) {
            var next = entity
            // 1.2.20：徽章重算不依赖文件读取——1.2.17 建的索引行可能带着旧规则
            // 算出的「WAV」徽章（用户看到的 DFF 挂红 WAV 标），这里统一按当前规则重算
            val freshBadge =
                LocalAudioLibrary.resolveAudioQualityBadge(entity.displayName, entity.mimeType)
            if (next.qualityBadge != freshBadge) {
                next = next.copy(qualityBadge = freshBadge)
            }
            // 已经回填出真实标签的行（标题 ≠ 文件名兜底）跳过文件读取，只做徽章修正
            if (alreadyBackfilled(next)) {
                if (next != entity) updated += next
                continue
            }
            if (scanned++ >= MaxFilesPerRun) break
            runCatching { backfillOne(context, entity) }
                .onSuccess { result ->
                    if (result != null) {
                        // 徽章修正与标签回填合并成一行写回
                        val merged =
                            if (next != entity && result.qualityBadge != next.qualityBadge) {
                                result.copy(qualityBadge = next.qualityBadge)
                            } else {
                                result
                            }
                        updated += merged
                    } else if (next != entity) {
                        updated += next
                    }
                }
                .onFailure {
                    AppLog.d(TAG, "回填失败 mediaId=${entity.mediaId}: ${it.message}")
                    if (next != entity) updated += next
                }
        }
        if (updated.isNotEmpty()) {
            runCatching { upsert(updated) }
                .onFailure { AppLog.w(TAG, "写回索引失败: ${it.message}") }
        }
        AppLog.i(TAG, "补录行标签回填: 命中 ${updated.size}/${pending.size}")
        return updated.size
    }

    /**
     * 判断补录行是否已有真实标签：Files 补录的兜底标题 = 文件名去后缀，
     * 回填成功后标题来自内嵌标签，二者几乎必然不同。
     */
    private fun alreadyBackfilled(entity: LibraryIndexEntity): Boolean {
        val fallbackTitle = entity.displayName.substringBeforeLast('.', entity.displayName)
        return entity.title.isNotBlank() && entity.title != fallbackTitle
    }

    private fun backfillOne(context: Context, entity: LibraryIndexEntity): LibraryIndexEntity? {
        val file = resolveFile(context, entity.uri) ?: return null
        val audioFile = AudioFileIO.read(file)
        val tag = audioFile.tag
        val header = audioFile.audioHeader
        var next = entity

        tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() && it != PlaceholderUnknown }
            ?.let { next = next.copy(title = it) }
        tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() && it != PlaceholderUnknown }
            ?.let { next = next.copy(artist = it) }
        // 专辑优先用文件内标签：补录行的 album 只是文件夹名兜底
        tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() && it != PlaceholderUnknown }
            ?.let { next = next.copy(album = it) }
        tag?.getFirst(FieldKey.ALBUM_ARTIST)
            ?.takeIf { it.isNotBlank() && it != PlaceholderUnknown }
            ?.let { next = next.copy(albumArtist = it) }
        tag?.getFirst(FieldKey.YEAR)?.trim()?.takeIf { it.length == 4 }?.toIntOrNull()
            ?.let { next = next.copy(year = it) }
        tag?.getFirst(FieldKey.TRACK)?.trim()?.toIntOrNull()
            ?.let { next = next.copy(track = it) }

        val durationSec = runCatching { header.trackLength }.getOrDefault(0)
        if (next.durationMs <= 0L && durationSec > 0) {
            next = next.copy(durationMs = durationSec * 1000L)
        }

        val sampleRate = runCatching { header.sampleRateAsNumber.toInt() }.getOrDefault(0)
        val bitsPerSample = runCatching { header.bitsPerSample }.getOrDefault(0)
        val bitrateKbps = runCatching { header.bitRateAsNumber.toInt() }.getOrDefault(0)
        if (sampleRate > 0 || bitsPerSample > 0 || bitrateKbps > 0) {
            FormatCache.put(
                entity.uri,
                next.durationMs,
                AudioFormatProbe.Result(
                    sampleRateHz = sampleRate.coerceAtLeast(0),
                    bitrateBps = (bitrateKbps * 1000L).coerceIn(0L, 40_000_000L).toInt(),
                    bitsPerSample = bitsPerSample.coerceAtLeast(0),
                    codec = codecOf(entity),
                    durationMs = if (next.durationMs > 0L) next.durationMs else 0L,
                ),
            )
        }
        return if (next == entity) null else next
    }

    private fun codecOf(entity: LibraryIndexEntity): String {
        val ext = entity.displayName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "dff", "dsf", "dsd", "dst" -> "audio/dsd"
            "ape", "mac", "apl" -> "audio/ape"
            "wv", "wvc" -> "audio/x-wavpack"
            "flac" -> "audio/flac"
            "wav", "wave", "aiff", "aif", "aifc" -> "audio/wav"
            else -> entity.mimeType?.takeIf { it.isNotBlank() } ?: ""
        }
    }

    /** content://media/external/file/<id> → 真实文件路径（需要「所有文件访问」权限）。 */
    private fun resolveFile(context: Context, uriString: String): File? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val direct = File(uri.path ?: return@runCatching null)
            return@runCatching if (direct.canRead()) direct else null
        }
        val path =
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (path.isNullOrBlank()) return@runCatching null
        val file = File(path)
        if (file.exists() && file.canRead()) file else null
    }.getOrNull()
}
