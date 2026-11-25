package com.smartisan.music.data.tags

import android.content.Context
import android.net.Uri
import com.smartisan.music.diagnostics.AppLog
import android.provider.MediaStore

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * 歌曲标签读写：直接把标题 / 歌手 / 专辑 / 封面写回歌曲文件本身（ID3v2 / Vorbis Comment 等），
 * 修改后其它设备、其它播放器读取该文件也能看到同样的信息。
 *
 * 写入前必须先拿到文件的写权限：
 * - Android 10+（分区存储）：对非本应用贡献的音频文件需要 [MediaStore.createWriteRequest] 授权；
 * - Android 9 及以下：需要 WRITE_EXTERNAL_STORAGE 运行时权限。
 * 授权通过后对真实文件路径做随机写（jaudiotagger 原地改标签，不重写整个文件）。
 */
internal object SongTagEditor {

    /** 从文件里读出的当前标签 */
    data class SongTags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val hasArtwork: Boolean,
    )

    /**
     * 解析 mediaId 对应的 MediaStore Uri 与真实文件路径。
     *
     * 1.2.19：支持 Files 补录行（mediaId 形如 "f123"，DFF/DSF/WV 等系统不索引的格式）——
     * 此前只认纯数字 id，补录行 resolve 返回 null → 编辑弹层拿不到文件路径 →
     * 点「保存」时静默失败（用户以为改了，其实没落盘）。
     */
    fun resolve(context: Context, mediaId: String): Pair<Uri, String>? {
        val numericId = mediaId.toLongOrNull()
        val uri =
            if (numericId != null) {
                Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, numericId.toString())
            } else {
                val filesId = mediaId.removePrefix("f").toLongOrNull() ?: return null
                android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    filesId,
                )
            }
        val path =
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
        return if (path.isNullOrBlank()) null else uri to path
    }

    /** 文件名（带后缀），仅用于展示 */
    fun resolveFileName(context: Context, mediaId: String): String? {
        val id = mediaId.toLongOrNull()
        if (id == null) {
            // DFF/DSF 等补录行：mediaId 为 "f<Files 表 _id>"，反查 Files 表拿文件名
            val filesId = mediaId.removePrefix("f").toLongOrNull() ?: return null
            return runCatching {
                context.contentResolver.query(
                    android.provider.MediaStore.Files.getContentUri("external"),
                    arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    "${android.provider.MediaStore.Files.FileColumns._ID} = ?",
                    arrayOf(filesId.toString()),
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        }
        val uri = Uri.withAppendedPath(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            id.toString(),
        )
        return context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun load(path: String): SongTags? =
        runCatching {
            val file = File(path)
            if (!file.exists()) return null
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            if (tag == null) {
                SongTags(title = null, artist = null, album = null, hasArtwork = false)
            } else {
                SongTags(
                    title = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() },
                    artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() },
                    album = tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() },
                    hasArtwork = runCatching { tag.artworkList?.isNotEmpty() == true }
                        .getOrDefault(false),
                )
            }
        }.onFailure {
            AppLog.w("Tags", "load tags failed: ${it.message}")
        }.getOrNull()

    /** 读取内嵌封面原始字节（用于编辑弹层预览） */
    fun loadArtworkBytes(path: String): ByteArray? =
        runCatching {
            AudioFileIO.read(File(path)).tag?.firstArtwork?.binaryData
        }.onFailure {
            AppLog.w("Tags", "load artwork failed: ${it.message}")
        }.getOrNull()

    /**
     * 把标签写回文件。字段传 null / 空白表示清空该字段；[artworkBytes] 非空表示替换封面。
     */
    fun write(
        path: String,
        title: String?,
        artist: String?,
        album: String?,
        artworkBytes: ByteArray?,
    ): Result<Unit> =
        runCatching {
            val file = File(path)
            require(file.exists()) { "file missing: $path" }
            val audioFile = AudioFileIO.read(file)
            val tag =
                audioFile.tag
                    ?: audioFile.getTagOrCreateDefault()
                    ?: error("no tag available for ${audioFile.ext}")

            fun setOrDelete(key: FieldKey, value: String?) {
                runCatching {
                    if (value.isNullOrBlank()) {
                        tag.deleteField(key)
                    } else {
                        tag.setField(key, value.trim())
                    }
                }.onFailure { first ->
                    // 某些容器字段不存在时 deleteField 会抛异常，先删后设再兜底一次
                    if (!value.isNullOrBlank()) {
                        runCatching { tag.addField(key, value.trim()) }
                            .onFailure { throw first }
                    }
                }
            }
            setOrDelete(FieldKey.TITLE, title)
            setOrDelete(FieldKey.ARTIST, artist)
            setOrDelete(FieldKey.ALBUM, album)

            if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                runCatching { tag.deleteArtworkField() }
                val isPng = artworkBytes.size > 8 &&
                    artworkBytes[0] == 0x89.toByte() &&
                    artworkBytes[1] == 0x50.toByte()
                val artwork =
                    ArtworkFactory.getNew().apply {
                        binaryData = artworkBytes
                        mimeType = if (isPng) "image/png" else "image/jpeg"
                    }
                tag.setField(artwork)
            }
            audioFile.commit()
        }.onFailure {
            AppLog.w("Tags", "write tags failed: ${it.message}")
        }

    /** jaudiotagger 能随机写的音频扩展名清单（用于提示哪些文件支持写标签） */
    private val WritableExtensions = setOf(
        "mp3", "flac", "m4a", "m4b", "mp4", "m4p", "ogg", "oga", "opus", "wav", "wma",
        "aif", "aiff", "aifc", "dsf", "aax", "aax+", "aa", "aac",
    )

    fun isSupportedExt(fileName: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in WritableExtensions
    }
}
