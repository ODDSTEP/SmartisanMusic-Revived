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
    @Volatile
    private var currentUri: String? = null
    @Volatile
    private var nextUri: Uri? = null

    /** 已被交叉淡化淡入的曲子 → 打开时应跳过的字节数（避免 intro 被播两次）。 */
    @Volatile
    private var skippedInUri: String? = null
    @Volatile
    private var skippedBytes: Long = 0L

    /** PlaybackService 在队列/切歌时调用，更新当前曲与下一曲。 */
    fun update(current: Uri?, next: Uri?) {
        currentUri = current?.toString()
        nextUri = next
    }

    /**
     * DataSource 请求：若 [forCurrent] 与当前曲匹配，返回下一曲 uri。
     * 不匹配返回 null（避免用陈旧队列给错误的歌做淡化）。
     */
    fun nextFor(forCurrent: Uri?): Uri? {
        if (forCurrent == null) return null
        if (currentUri != forCurrent.toString()) return null
        return nextUri
    }

    /** 交叉淡化激活后登记：下一曲 [uri] 的开头 [bytes] 字节已在淡化中播过。 */
    fun markSkippedIntro(uri: Uri?, bytes: Long) {
        if (uri == null || bytes <= 0L) return
        skippedInUri = uri.toString()
        skippedBytes = bytes
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
        currentUri = null
        nextUri = null
        skippedInUri = null
        skippedBytes = 0L
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
