package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 「高解析文件正在转换」的进度广播（1.2.28 按任务重写，1.2.29 修「归零又从头转」）。
 *
 * 1.2.27 的 bug：进度由 [ApeTranscoder.TranscodingDataSource.open] 发布、在 `finally` 里
 * `clear()`。一次 seek 会让 ExoPlayer 重新 `open` 同一个 DataSource，于是「发布 → 清除」
 * 被反复执行；多个 DataSource（当前项 + 预载项）并发时还会互相把对方的状态清掉。
 *
 * 1.2.28 遗留的 bug：[clear] 在 primary 结束后会 `active.keys.firstOrNull()` 接管下一个任务，
 * 于是「当前这首转完 → 立刻显示预加载的下一首，并且从 0% 开始」。日志 23:15:22 当前曲
 * 转码完成、23:15:22.545 立刻开始转下一首 DFF，正是用户看到的「百分比很高突然归零、
 * 又从头开始转换」。预加载任务的进度根本不该出现在 UI 上。
 *
 * 1.2.29 规则：
 * - 只有**新注册**的任务能成为 primary（用户主动点歌 / 切歌）；
 * - primary 结束就整体清空，**绝不接管**已在后台跑着的其它任务；
 * - 后台任务继续上报但不显示、不抢占；
 * - 每跨 10% 打一条日志，让「进度归零」在日志里可直接定位（用户明确要求过日志可定位）。
 */
internal object TranscodeProgressBus {

    private const val TAG = "TranscodeProgress"

    data class State(
        /** 0~99，100 不在 Bus 里出现（完成即注销）。 */
        val percent: Int,
        /** 当前正在转换的缓存文件名，便于日志对照。 */
        val label: String,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** key → 最近一次百分比。 */
    private val active = ConcurrentHashMap<String, Int>()

    /** key → 上次打日志的十位数，用于节流。 */
    private val loggedBucket = ConcurrentHashMap<String, Int>()

    @Volatile
    private var primaryKey: String? = null

    fun publish(key: String, label: String, percent: Int) {
        val safe = percent.coerceIn(0, 99)
        val isNew = !active.containsKey(key)
        active[key] = safe
        if (primaryKey == null) {
            // 后台早已在跑的任务不抢显示；只有新任务（用户新点的一首）才接管。
            if (!isNew) return
            primaryKey = key
        }
        if (key != primaryKey) return

        val bucket = safe / 10
        if (loggedBucket[key] != bucket) {
            loggedBucket[key] = bucket
            AppLog.i(TAG, "进度 primary=$key label=$label percent=$safe%")
        }
        val next = State(safe, label)
        if (_state.value != next) _state.value = next
    }

    fun clear(key: String) {
        active.remove(key)
        loggedBucket.remove(key)
        if (primaryKey != key) return
        // 关键：primary 完成就整体清空，绝不切到排队/预载任务重新开始显示 0%。
        primaryKey = null
        AppLog.i(TAG, "进度结束 primary=$key，状态清空（不接管后台任务）")
        _state.value = null
    }
}
