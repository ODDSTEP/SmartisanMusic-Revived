package com.smartisan.music.playback

import android.net.Uri
import android.os.SystemClock

/**
 * [1.4.20·路径A] 交叉淡化的"下一曲"全局桥。
 *
 * 为什么需要它：[BassPcmDataSource] 在解码当前曲时无法自己知道队列的下一首是谁
 * （DataSource 由 Media3 按 MediaItem 创建，拿不到 Playlist 上下文）。所以由
 * [PlaybackService] 在队列/切歌变化时，把"当前 uri → 下一曲 uri"喂进这里；
 * DataSource 进入尾部淡化窗口时读取下一曲 uri 去预取。
 *
 * 纯内存、线程安全、无持久化。纯值传递，不持有 Player，避免生命周期泄漏。
 */
internal object BassCrossfadeQueue {
    /**
     * [1.4.30·R140] 从"单对当前/下一曲"改成**小映射表**（保留最近 4 对）。
     *
     * 原实现的致命时序错误：`update()` 只在 `onMediaItemTransition` 里被调用，
     * 也就是"播放器已经切到新曲"之后。而此时正在被读、正要做淡出的 DataSource
     * 还是**上一首**，它拿自己的 uri 去问 `nextFor()`，而表里 current 已经变成新曲
     * → 永远返回 null → 真交叉淡化几乎永不激活（用户日志里全程只激活过 1 次，
     * 还是打碟唱针 seek 撞出来的特殊时序）。
     *
     * 改成映射表后，切歌时写入的是「新曲 → 新曲的下一曲」，但「旧曲 → 新曲」
     * 这一对**仍然留在表里**，旧曲的 DataSource 读尾部窗口时就能查到自己的下一曲。
     */
    private val pairs =
        object : LinkedHashMap<String, Uri?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri?>?): Boolean =
                size > 4
        }

    private val lock = Any()

    /** 已被交叉淡化淡入的曲子 → 打开时应跳过的字节数（避免 intro 被播两次）。 */
    @Volatile
    private var skippedInUri: String? = null
    @Volatile
    private var skippedBytes: Long = 0L

    /** PlaybackService 在队列/切歌时调用，登记"这一首的下一首是谁"。 */
    fun update(current: Uri?, next: Uri?) {
        val key = current?.toString() ?: return
        synchronized(lock) { pairs[key] = next }
    }

    /**
     * DataSource 请求：返回 [forCurrent] 这一首的下一曲 uri。
     * 表里没有这一首时返回 null（避免用陈旧队列给错误的歌做淡化）。
     *
     * 注意：`null` 有两种含义——"没登记过这一首"和"登记过但它是最后一首"。
     * 对调用方来说两者都是"不做淡化"，行为一致；但 [hasEntry] 能区分出来供日志使用。
     */
    fun nextFor(forCurrent: Uri?): Uri? {
        val key = forCurrent?.toString() ?: return null
        return synchronized(lock) { if (pairs.containsKey(key)) pairs[key] else null }
    }

    /** 是否登记过这一首（哪怕下一首是 null，即最后一首）。供淡化窗口日志区分原因。 */
    fun hasEntry(forCurrent: Uri?): Boolean {
        val key = forCurrent?.toString() ?: return false
        return synchronized(lock) { pairs.containsKey(key) }
    }

    /** 交叉淡化激活后登记：下一曲 [uri] 的开头 [bytes] 字节已在淡化中播过。 */
    fun markSkippedIntro(uri: Uri?, bytes: Long) {
        if (uri == null || bytes <= 0L) return
        skippedInUri = uri.toString()
        skippedBytes = bytes
        // [1.4.33·验收第14点] 真混音负责这一曲开头的精确信号（供包络淡入让位）。
        // 与 skippedInUri 分开：consumeSkipFor 是查询即消费，包络判断要在切歌时序里
        // 独立存在；用时间窗避免把信号带到很久之后的无关切歌。
        introOwnerUri = uri.toString()
        introOwnerAt = SystemClock.elapsedRealtime()
    }

    /** 真混音负责开头的曲 uri + 登记时刻，供切歌时判断包络是否让位。 */
    @Volatile
    private var introOwnerUri: String? = null
    @Volatile
    private var introOwnerAt = 0L

    /**
     * [1.4.33·验收第14点] 这首曲的开头是否由真混音淡入负责过（15 秒内）。
     * 是则 SmartCrossfadeController 切换/自动过渡时不得再用 player.volume 淡入一次——
     * 否则真混音已播过的开头被从 0 音量重拉，听感就是"开头突然断掉又起来"。
     */
    fun isIntroOwnedByRealMix(uri: Uri?): Boolean {
        val owner = introOwnerUri ?: return false
        if (uri == null || owner != uri.toString()) return false
        return SystemClock.elapsedRealtime() - introOwnerAt < 15_000L
    }

    /**
     * 同上，但按 mediaId 匹配（切歌回调里常只有 mediaId 没有完整 Uri）。
     * 内容库 uri 形如 `content://media/external/audio/media/<mediaId>`，
     * 故用"uri 以 mediaId 结尾"兜底；并同样带 15 秒时效。
     */
    fun isIntroOwnedByMediaId(mediaId: String?): Boolean {
        val owner = introOwnerUri ?: return false
        if (mediaId.isNullOrBlank() || !owner.endsWith(mediaId)) return false
        return SystemClock.elapsedRealtime() - introOwnerAt < 15_000L
    }

    /** 消费让位信号（包络已据此跳过淡入后调用，避免重复命中）。 */
    fun clearIntroOwner() {
        introOwnerUri = null
        introOwnerAt = 0L
    }

    /**
     * 某曲 DataSource open 时查询：它是否已被淡入、需要跳过开头多少字节。
     * 查询即消费（一次性），避免下次误跳。
     */
    fun consumeSkipFor(uri: Uri?): Long {
        if (uri == null) return 0L
        if (skippedInUri != uri.toString()) return 0L
        val b = skippedBytes
        skippedInUri = null
        skippedBytes = 0L
        return b
    }

    fun clear() {
        synchronized(lock) { pairs.clear() }
        skippedInUri = null
        skippedBytes = 0L
        introOwnerUri = null
        introOwnerAt = 0L
        realMixActiveFlag = false
        realMixEndedAt = 0L
    }

    // ── [1.4.28] 真双曲混音信号 ─────────────────────────────────────────────
    // 智能过渡有两套实现：BASSmix 真双曲混音（本类 + BassCrossfadeMixer）和
    // SmartCrossfadeController 的音量包络（player.volume 淡出/淡入）。
    // BASS 路径能真混音时，音量包络必须让位，否则同一段交叉淡化被压两次：
    // 最后 halfFadeMs 内 volume 从 1 降到 0，叠在 mixer 的淡化曲线上，
    // 切歌后包络又把新曲从 0 拉起（而新曲开头已被 mixer 播过）→ 音量忽大忽小。
    @Volatile
    private var realMixActiveFlag = false

    @Volatile
    private var realMixStartedAt = 0L

    @Volatile
    private var realMixEndedAt = 0L

    /** mixer 成功接管时调用。 */
    fun markRealMixActive() {
        realMixActiveFlag = true
        realMixStartedAt = SystemClock.elapsedRealtime()
    }

    /** mixer 会话结束（淡化流读完）时调用。 */
    fun markRealMixFinished() {
        realMixActiveFlag = false
        realMixEndedAt = SystemClock.elapsedRealtime()
    }

    /** 当前是否有真双曲混音在跑。带 120 秒超时保护，避免异常路径没走到 finish 时永久禁用包络。 */
    fun realMixActive(): Boolean =
        realMixActiveFlag && SystemClock.elapsedRealtime() - realMixStartedAt < 120_000L

    /** 刚刚结束真混音（默认 3 秒内）：切歌事件紧随其后，包络不应再淡入一次。 */
    fun realMixJustFinished(windowMs: Long = 3_000L): Boolean {
        if (realMixActiveFlag) return true
        if (realMixEndedAt == 0L) return false
        return (SystemClock.elapsedRealtime() - realMixEndedAt) in 0..windowMs
    }
}
