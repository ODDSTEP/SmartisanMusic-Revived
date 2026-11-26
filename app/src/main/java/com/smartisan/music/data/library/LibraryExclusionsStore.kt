package com.smartisan.music.data.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.smartisan.music.diagnostics.AppLog

private const val LibraryExclusionsStoreName = "library_exclusions"

private val Context.libraryExclusionsDataStore by preferencesDataStore(
    name = LibraryExclusionsStoreName,
)

data class LibraryExclusions(
    val hiddenMediaIds: Set<String> = emptySet(),
    val hiddenDirectoryKeys: Set<String> = emptySet(),
    /**
     * [1.4.30·R138] 被隐藏的**专辑卡**（不是专辑里的歌）。
     *
     * 键是 `AlbumModels.albumGroupingId()` 的产物（`album:<MediaStore albumId>` 或 `标题|歌手`），
     * 与分组、"查看专辑"跳转共用同一口径。三者互不联动：
     *  - 隐藏专辑卡 **不会** 把它的歌写进 [hiddenMediaIds]，歌在歌曲页/搜索结果照常出现；
     *  - 隐藏歌曲也不会让专辑卡消失（除非整张专辑的歌全被隐藏，那是 [hiddenMediaIds] 的既有行为）；
     *  - 隐藏文件夹同理无关。
     */
    val hiddenAlbumIds: Set<String> = emptySet(),
) {

    fun isAlbumHidden(albumId: String): Boolean = hiddenAlbumIds.contains(albumId)

    fun isDirectoryHidden(directoryKey: String): Boolean {
        val normalized = directoryKey.normalizedDirectoryKey()
        if (normalized.isEmpty()) {
            return false
        }
        return hiddenDirectoryKeys.contains(normalized)
    }

    /** 仅按目录判断是否被隐藏（单个歌曲的隐藏不算），供资料库列表使用：
     *  单独隐藏的歌曲要保留在列表数据里（编辑模式下变暗、可恢复），与文件夹页行为一致 */
    fun isHiddenByDirectory(relativePath: String?): Boolean {
        val normalizedPath = relativePath?.normalizedDirectoryKey().orEmpty()
        if (normalizedPath.isEmpty()) {
            return false
        }
        return hiddenDirectoryKeys.contains(normalizedPath)
    }

    fun isMediaHidden(mediaId: String, relativePath: String?): Boolean {
        if (hiddenMediaIds.contains(mediaId)) {
            return true
        }
        return isHiddenByDirectory(relativePath)
    }

    fun contentHash(): Int {
        return 31 * (31 * hiddenMediaIds.hashCode() + hiddenDirectoryKeys.hashCode()) +
            hiddenAlbumIds.hashCode()
    }
}

class LibraryExclusionsStore(
    private val context: Context,
) {

    val exclusions: Flow<LibraryExclusions> = context.libraryExclusionsDataStore.data
        .map { preferences ->
            LibraryExclusions(
                hiddenMediaIds = preferences[HiddenMediaIdsKey].orEmpty(),
                hiddenDirectoryKeys = preferences[HiddenDirectoryKeysKey]
                    .orEmpty()
                    .asSequence()
                    .map { it.normalizedDirectoryKey() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
                hiddenAlbumIds = preferences[HiddenAlbumIdsKey].orEmpty(),
            )
        }
        .distinctUntilChanged()

    val revision: Flow<Int> = exclusions
        .map { it.contentHash() }
        .distinctUntilChanged()

    suspend fun hideMediaIds(mediaIds: Set<String>) {
        setMediaIdsHidden(mediaIds = mediaIds, hidden = true)
    }

    suspend fun showMediaIds(mediaIds: Set<String>) {
        setMediaIdsHidden(mediaIds = mediaIds, hidden = false)
    }

    suspend fun setMediaIdsHidden(mediaIds: Set<String>, hidden: Boolean) {
        val normalized = mediaIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalized.isEmpty()) {
            return
        }
        context.libraryExclusionsDataStore.edit { preferences ->
            val current = preferences[HiddenMediaIdsKey].orEmpty().toMutableSet()
            if (hidden) {
                current.addAll(normalized)
            } else {
                current.removeAll(normalized)
            }
            preferences[HiddenMediaIdsKey] = current
        }
    }

    /**
     * 1.2.26：MediaStore 换号后把隐藏项从旧 id 迁到新 id。
     * 不迁的话用户会看到「明明隐藏过的歌又冒出来了」，而且旧 id 会永远留在集合里。
     */
    suspend fun remapMediaIds(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        context.libraryExclusionsDataStore.edit { preferences ->
            val current = preferences[HiddenMediaIdsKey].orEmpty()
            if (current.isEmpty()) return@edit
            val next = current.mapTo(mutableSetOf()) { id -> mapping[id] ?: id }
            if (next != current) {
                preferences[HiddenMediaIdsKey] = next
            }
        }
    }

    suspend fun hideDirectoryKeys(directoryKeys: Set<String>) {
        setDirectoryKeysHidden(directoryKeys = directoryKeys, hidden = true)
    }    suspend fun showDirectoryKeys(directoryKeys: Set<String>) {
        setDirectoryKeysHidden(directoryKeys = directoryKeys, hidden = false)
    }

    suspend fun setDirectoryKeysHidden(directoryKeys: Set<String>, hidden: Boolean) {
        val normalized = directoryKeys.asSequence()
            .map { it.normalizedDirectoryKey() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalized.isEmpty()) {
            return
        }
        context.libraryExclusionsDataStore.edit { preferences ->
            val current = preferences[HiddenDirectoryKeysKey]
                .orEmpty()
                .asSequence()
                .map { it.normalizedDirectoryKey() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            if (hidden) {
                current.addAll(normalized)
            } else {
                current.removeAll(normalized)
            }
            preferences[HiddenDirectoryKeysKey] = current
        }
    }

    /** [1.4.30·R138] 专辑卡隐藏集合：单个/多个专辑 id 一起写，[hidden] 决定藏还是恢复。 */
    suspend fun setAlbumIdsHidden(albumIds: Set<String>, hidden: Boolean) {
        val normalized =
            albumIds.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized.isEmpty()) {
            AppLog.w("Library", "专辑隐藏：集合为空，已跳过 hidden=$hidden")
            return
        }
        context.libraryExclusionsDataStore.edit { preferences ->
            val current = preferences[HiddenAlbumIdsKey].orEmpty().toMutableSet()
            val before = current.size
            if (hidden) current.addAll(normalized) else current.removeAll(normalized)
            preferences[HiddenAlbumIdsKey] = current
            // R186 的教训：开关类状态变化必须落一帧，否则事后只能靠猜。
            AppLog.state(
                "SET",
                "albumHidden",
                "LibraryExclusionsStore.setAlbumIdsHidden",
                mapOf(
                    "hidden" to hidden,
                    "count" to normalized.size,
                    "ids" to normalized.joinToString(","),
                    "total" to "$before->${current.size}",
                ),
            )
        }
    }
}

private val HiddenMediaIdsKey = stringSetPreferencesKey("hidden_media_ids")
private val HiddenDirectoryKeysKey = stringSetPreferencesKey("hidden_directory_keys")
private val HiddenAlbumIdsKey = stringSetPreferencesKey("hidden_album_ids")

internal fun String.normalizedDirectoryKey(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}
