package com.smartisan.music.playback

import android.content.Context
import com.smartisan.music.diagnostics.AppLog

/**
 * [v11·独占死循环根治] USB 独占持久化熔断闸。
 *
 * 为什么需要持久化：
 *  - 现场（v9，07:06–07:09）连续 3 次「开独占→点 MP3→native 崩溃→进程重启」，
 *    每次重启后独占开关仍是开（设置是持久化的），v10 的内存态熔断随进程死亡清零，
 *    于是再次进入坏通道 → 再次崩溃 → 死循环，用户来不及进设置关掉。
 *  - Java 的 UncaughtExceptionHandler 抓不到 native SIGSEGV，崩溃瞬间只有
 *    ApplicationExitInfo 能证明。所以改用 SharedPreferences.commit() **同步**落标记，
 *    下次启动在任何解码发生前就能读到，直接禁用独占、走共享输出。
 *
 * 解除条件：只有用户手动把 USB 独占开关「关→开」才清除（见 PlaybackService 的
 * prefChanged 分支），给一次新的机会；若再次 native 崩溃会再次置位。这样既不会
 * 把坏通道无限重试拖死，也不会永久剥夺用户尝试的权利。
 *
 * [R74·设备前提更正] 归因收敛：
 *  - 旧逻辑把**任何** native 崩溃都算到 USB 独占头上。但本机实测（原道 Tears DSP，
 *    系统识别 TEARS DSP · UAC2 · 16/24/32bit · 2ch，硬件支持 32bit/384kHz）USB 独占
 *    本身是稳定的，日志里的崩溃来自 BASS 解码链路（另一条 native 链），跟 UAC 直写无关。
 *    结果是：BASS 一崩就把独占也持久化熔断，用户重开还被"假独占"挡回去，纯误伤。
 *  - 现在只有"崩溃发生时确实有一条活着的 USB 独占会话"才置位熔断：UsbDac 在会话建立/
 *    关闭时通过 [markSessionActive] 落一个带时间戳的在场标记，[disableAfterNativeCrash]
 *    读它做归因。没有独占会话时的崩溃不再连坐独占。
 */
internal object UsbExclusiveGuard {
    private const val TAG = "UsbGuard"
    private const val PREFS = "usb_exclusive_guard"
    private const val KEY_DISABLED = "disabled_after_native_crash"
    private const val KEY_SESSION_ACTIVE = "usb_session_active"
    private const val KEY_SESSION_AT = "usb_session_at_ms"
    private const val KEY_CAUSE = "disabled_cause"

    /** 在场标记的有效期：超过这个时长的"上次崩溃前有过会话"不再作为归因依据，避免陈旧误判。 */
    private const val SESSION_ATTRIBUTION_WINDOW_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 由 [UsbDac] 在 UAC 会话建立/关闭时调用，记录"此刻是否有一条独占会话在跑"。
     *
     * 建立时用 commit 同步落盘：崩溃可能发生在会话建立后的任意时刻，apply 的异步
     * 落盘赶不上进程被信号杀死。关闭时用 apply 即可（正常关闭不赶时间）。
     */
    fun markSessionActive(context: Context, active: Boolean) {
        runCatching {
            val edit = prefs(context).edit()
            if (active) {
                edit.putBoolean(KEY_SESSION_ACTIVE, true)
                    .putLong(KEY_SESSION_AT, System.currentTimeMillis())
                    .commit()
            } else {
                edit.remove(KEY_SESSION_ACTIVE)
                    .remove(KEY_SESSION_AT)
                    .apply()
            }
        }
    }

    /** 崩溃时是否有"仍然有效"的独占会话在场（供诊断/日志核对）。 */
    fun hadActiveSessionAtCrash(context: Context): Boolean = runCatching {
        val p = prefs(context)
        val active = p.getBoolean(KEY_SESSION_ACTIVE, false)
        val at = p.getLong(KEY_SESSION_AT, 0L)
        active && at > 0L &&
            System.currentTimeMillis() - at in 0L..SESSION_ATTRIBUTION_WINDOW_MS
    }.getOrDefault(false)

    /**
     * native 崩溃后同步落盘，禁用独占通道，防止重启后再进坏通道。
     *
     * [R74] 归因：只有崩溃时确实有独占会话在场才置位。BASS 解码链的崩溃（无独占会话）
     * 不再连坐 USB 独占——本机 USB 独占本身是稳定的，旧的一刀切属于误伤。
     */
    fun disableAfterNativeCrash(context: Context) {
        if (!hadActiveSessionAtCrash(context)) {
            // 崩得再狠，只要当时没有独占会话在场，就不是独占的锅。顺手清掉可能陈旧的在场标记。
            runCatching {
                prefs(context).edit()
                    .remove(KEY_SESSION_ACTIVE)
                    .remove(KEY_SESSION_AT)
                    .apply()
            }
            AppLog.w(
                TAG,
                "检测到 native 崩溃，但崩溃时没有 USB 独占会话在场（很可能是 BASS/解码链路），" +
                    "不置位 USB 独占熔断，保持独占可用",
            )
            return
        }
        runCatching {
            prefs(context).edit()
                .putBoolean(KEY_DISABLED, true)
                .putString(KEY_CAUSE, "native crash while usb exclusive session active")
                // 归因已消费，清掉在场标记，避免后续无关崩溃重复引用同一次会话。
                .remove(KEY_SESSION_ACTIVE)
                .remove(KEY_SESSION_AT)
                .commit()
        }
        AppLog.w(TAG, "持久化熔断已置位：独占会话期间 native 崩溃，重启后先走共享输出")
    }

    /** 本次启动是否因历史 native 崩溃而禁用独占。 */
    fun isDisabled(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(KEY_DISABLED, false)
    }.getOrDefault(false)

    /** 熔断原因（诊断用；null = 未熔断）。 */
    fun disabledCause(context: Context): String? = runCatching {
        prefs(context).getString(KEY_CAUSE, null)
    }.getOrNull()

    /**
     * 用户手动切换独占开关时调用。
     *
     * [R185] 旧实现只有一个 `clear()`，文案写"用户手动重开独占"——但"开→关"方向也会走到
     * 这里，日志于是在 `exclusive=false` 时打"重开独占"，复盘时误导方向。
     * 现在按方向拆成两条，并带上 old/new。
     */
    fun clear(context: Context, reenabled: Boolean = true) {
        runCatching {
            prefs(context).edit()
                .putBoolean(KEY_DISABLED, false)
                .remove(KEY_CAUSE)
                .commit()
        }
        if (reenabled) {
            AppLog.i(TAG, "状态：熔断解除(重开) old=false new=true —— 用户手动关→开独占，给一次新的机会")
        } else {
            AppLog.i(TAG, "状态：用户关闭独占，清熔断态 old=true new=false —— 下次重开会重新给机会")
        }
    }
}
