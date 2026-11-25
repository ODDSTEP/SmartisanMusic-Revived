package com.smartisan.music.playback

import com.smartisan.music.diagnostics.AppLog

/**
 * [1.4.24·R39] 播放/暂停**指令下发**审计收口。
 *
 * ## 为什么需要它（一次踩坑的教训）
 *
 * 2026-11-24 03:11:06，日志里出现
 * ```
 * UsbDiag: state机: playWhenReady=false reason=1 isPlaying=false state=3 pos=4085
 * ```
 * 整整 1.2 秒没有任何用户操作，但播放器自己停了。`reason=1` 只说明"是代码主动调的"，
 * **不说明"是谁调的"**——通知栏？播放条？某段自愈代码？用户当时明明在听歌。
 *
 * 第一版修法是在 `Player.Listener.onPlayWhenReadyChanged` 里打 `caller=` 栈。
 * **那是错的**：该回调是 media3 内部派发过来的，栈顶永远是
 * `ExoPlayerImpl → ListenerSet → onPlayWhenReadyChanged`，
 * 真正的业务调用方（谁调的 pause）**根本不在栈里**——它早就返回了。
 * 打了也等于没打，属于"日志看起来很全、实际查不出任何东西"的假完备。
 *
 * ## 正确做法
 *
 * 在**下指令的那一刻**取栈。所以本类提供两个入口：
 * - [pause] / [play] —— 业务代码要暂停/恢复播放时一律走这里；
 * - [trace] —— 已经直接调了 `player.pause()` 的地方，改成 `trace { player.pause() }`。
 *
 * 这样日志里会直接出现 `caller=MusicAppShell.kt:1192` 这种**可定位到行的**来源，
 * 而不是 media3 的内部派发栈。
 */
object PlaybackIntentAudit {

    private const val Tag = "PlaybackIntent"

    /**
     * [1.4.24·R39] 最近一次播放/暂停指令的「票据」。
     *
     * 播放器回调 `onPlayWhenReadyChanged` 是**异步**的：业务代码调完 `pause()` 就返回了，
     * 回调才在稍后由 media3 派发过来。所以回调现场**取不到**业务调用栈。
     *
     * 解决办法：下指令时把「谁下的 + 栈」存进这张票据（带时间戳），
     * 回调到来时按时间窗口把这张票据取出来贴上去——
     * 于是日志里 `UsbDiag` 那一帧的 `intent=` 字段就直接写着
     * `大播放页·拖动唱针定位`，不用再猜。
     */
    private data class Ticket(
        val op: String,
        val source: String,
        val caller: String,
        val atMs: Long,
    )

    @Volatile
    private var lastTicket: Ticket? = null

    /** 票据有效期：超过这个窗口就认为与本次回调无关。 */
    private const val TicketWindowMs = 3_000L

    /**
     * 取最近一次指令票据（若有且未过期）。由播放器的状态回调调用，
     * 把「结果」和「动因」配成一对。
     */
    fun consumeRecentTicket(): String? {
        val t = lastTicket ?: return null
        if (android.os.SystemClock.elapsedRealtime() - t.atMs > TicketWindowMs) return null
        return "${t.source} | caller=${t.caller}"
    }

    /** 记录一次"要暂停"的指令，并返回原对象，方便链式调用：
     * `PlaybackIntentAudit.pause(controller, "播放条按钮")?.pause()`
     *
     * @param source 人话描述这次暂停的动因（业务场景），会写进 `src=` 字段。
     * @param extra 额外字段（如 reason、mediaId 等），可为空。
     */
    fun <T> pause(target: T?, source: String, extra: Map<String, Any?> = emptyMap()): T? {
        if (target == null) {
            AppLog.w(Tag, "暂停指令目标为空，已跳过 src=$source")
            return null
        }
        log("暂停", source, extra)
        return target
    }

    /** 同 [pause]，但用于"恢复播放"。 */
    fun <T> play(target: T?, source: String, extra: Map<String, Any?> = emptyMap()): T? {
        if (target == null) {
            AppLog.w(Tag, "播放指令目标为空，已跳过 src=$source")
            return null
        }
        log("恢复播放", source, extra)
        return target
    }

    /**
     * 已经直接调用了底层 API 的地方，用它包一层即可拿到调用来源。
     *
     * ```kotlin
     * PlaybackIntentAudit.trace("USB 掉线自动暂停") { player?.pause() }
     * ```
     */
    inline fun trace(source: String, extra: Map<String, Any?> = emptyMap(), block: () -> Unit) {
        log("暂停/恢复指令", source, extra)
        block()
    }

    /**
     * 取栈。**必须在下指令的当场调用**，事后补就只剩 media3 的内部派发栈了。
     * 深度取 8 帧：既能穿透 Compose 的 lambda / 内联函数，又不至于把一行撑太长。
     */
    @PublishedApi
    internal fun log(op: String, source: String, extra: Map<String, Any?>) {
        val caller = AppLog.caller(skip = 1, depth = 8)
        lastTicket = Ticket(op, source, caller, android.os.SystemClock.elapsedRealtime())
        val fields = LinkedHashMap<String, Any?>(extra.size + 2)
        fields.putAll(extra)
        fields["caller"] = caller
        AppLog.state(Tag, op, source, fields)
    }
}
