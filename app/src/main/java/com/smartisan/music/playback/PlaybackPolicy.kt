package com.smartisan.music.playback

/**
 * 1.4.0 播放策略状态。
 *
 * 「是否转码 / 由谁解码」的决策在 [FlavorDecodeFrontend.plan]（编译期三版不同），
 * 本对象只保留会话层需要的全局状态：USB 独占是否生效、DSD 过滤开关。
 * 由 PlaybackService 在设置变化与每次状态埋点时同步。
 */
internal object PlaybackPolicy {
    @Volatile
    var usbExclusiveActive: Boolean = false

    /**
     * [1.4.5·清单 5·护栏 G-17] 真实独占是否建立，供设置页 Compose 观察「开关开了但权限没拿到」。
     * 与 usbExclusiveActive 的区别：后者是 plan() 的编译期策略状态，只在 logState() 埋点时
     * 由 update() 同步；本 flow 由 PlaybackService.usbExclusiveActive 每次赋值实时驱动，
     * 用于 UI 如实显示 exclusivePref=true 但 exclusive=false 的不一致（13 号页 33 次）。
     */
    private val usbExclusiveEstablishedState =
        kotlinx.coroutines.flow.MutableStateFlow(false)
    val usbExclusiveEstablishedFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = usbExclusiveEstablishedState

    /** 由 PlaybackService 在 usbExclusiveActive 每次赋值时调用；不复用 update()，避免误清 excludeDsd。 */
    fun reportUsbExclusiveEstablished(value: Boolean) {
        usbExclusiveEstablishedState.value = value
    }

    /** 1.3.5：过滤 DSD 格式音频。 */
    @Volatile
    var excludeDsd: Boolean = false
        private set

    private val excludeDsdState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val excludeDsdFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = excludeDsdState

    fun update(
        exclusiveActive: Boolean,
        excludeDsdEnabled: Boolean = false,
    ) {
        usbExclusiveActive = exclusiveActive
        excludeDsd = excludeDsdEnabled
        excludeDsdState.value = excludeDsdEnabled
    }
}
