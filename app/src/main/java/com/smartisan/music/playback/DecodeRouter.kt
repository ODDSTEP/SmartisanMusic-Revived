package com.smartisan.music.playback

import android.net.Uri
import com.smartisan.music.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.4.0 解码路由：三个 App（official=B 正式版 / direct=A 系统直读 / stream=C 流式）
 * 共用同一套 MediaLibraryService + ExoPlayer 会话层，差异**只**落在「曲目字节流由谁
 * 产生」这一层，由 flavor 专属源集的 [FlavorDecodeFrontend] 在**编译期**决定。
 *
 * 为什么不动会话层：队列、进度、通知栏、歌词、音量均衡、DSP、爆豆音、唱针 seek、
 * USB 独占位深直出全都挂在 Media3 的 Player 接口 / 解码后 PCM 域上。把整个播放器
 * 框架换成 android.media.MediaPlayer 会让这些功能整片失效（1.3.7 已吃过亏）。
 * 所以「三条路线」可安全替换的就是解码取流前端，绝不动会话层。
 *
 * 三版对冷门格式（DSD/DSF/DFF/APE/TTA/WavPack/TAK/WMA/DTS…）的策略：
 *  - B 正式版 official：双路切换。普通格式原始 Uri 直读（零缓存点开即播）；冷门格式
 *    或开 USB 独占时回 App 自解码（ffmpeg 无损 FLAC 缓存，PCM 域功能全保）。
 *  - A 系统直读 direct：一律原始 Uri 交系统解码链，App 不转码、不落盘、不碰 PCM。
 *    系统认不出的冷门格式会明确失败并写日志（不偷偷转码），这正是本版要验的结论。
 *  - C 流式解码 stream：冷门格式由 ffmpeg 边解边喂 PCM，不写任何输出缓存文件；
 *    逐阶段日志写明缓冲与 seek 竞态（沙箱无真机，不宣称已验证能播）。
 */
internal object DecodeRouter {

    enum class Engine(val id: String, val label: String) {
        OFFICIAL("official", "双路正式版"),
        DIRECT("direct", "系统直读"),
        STREAM("stream", "流式解码"),
    }

    // [v12·引擎日志实时化] 旧实现是 `val engine = when(current())`——对象首次加载时求值的
    // **启动快照**。用户在设置里切到「流式解码」后，真实解码已由 FlavorDecodeFrontend.active
    // （运行时 getter）委托给 StreamDecodeFrontend，但日志里所有 engine= 都读这个快照，
    // 恒显示 official，严重误导诊断（用户明明切了流式，日志却说 official）。改为 get() 实时
    // 跟随 EngineModeStore.current()，与真实解码口径一致。所有使用点都是日志/诊断，无决策用途。
    val engine: Engine
        get() = when (com.smartisan.music.data.settings.EngineModeStore.current()) {
            com.smartisan.music.data.settings.EngineModeStore.Mode.DIRECT -> Engine.DIRECT
            com.smartisan.music.data.settings.EngineModeStore.Mode.STREAM -> Engine.STREAM
            else -> Engine.OFFICIAL
        }

    val engineId: String get() = engine.id
    // 1.4.11 S12 单包：标签随当前运行时引擎变化，诊断日志不再误报固定 official。
    val engineLabel: String get() = engine.label
    val engineSummary: String get() = FlavorDecodeFrontend.engineSummary
    val capabilityNote: String get() = FlavorDecodeFrontend.capabilityNote

    /** 该引擎冷门格式不被系统认出时，是否自动回退到 App 解码。 */
    val fallbackOnUnsupported: Boolean get() = FlavorDecodeFrontend.fallbackOnUnsupported

    /** 是否以系统直读为首选（诊断会话头用）。 */
    val directReadFirst: Boolean get() = FlavorDecodeFrontend.directReadFirst

    // 3003 回退标记：命中的 uri 强制走 App 解码。B 正式版在 3003 时打标记并重新
    // prepare；A/C 测试版不自动回退，故不会新写入（集合保留供日志与兼容）。
    private val forceTranscodeUris = ConcurrentHashMap.newKeySet<String>()

    fun markForceTranscode(uri: Uri?) {
        val key = uri?.toString() ?: return
        forceTranscodeUris.add(key)
    }

    fun isForceTranscode(uri: Uri?): Boolean {
        val key = uri?.toString() ?: return false
        return forceTranscodeUris.contains(key)
    }

    fun clearForceTranscode(uri: Uri?) {
        val key = uri?.toString() ?: return
        forceTranscodeUris.remove(key)
    }

    val forceTranscodeCount: Int get() = forceTranscodeUris.size
}
