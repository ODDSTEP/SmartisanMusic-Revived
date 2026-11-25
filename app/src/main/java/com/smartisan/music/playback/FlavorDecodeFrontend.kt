@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.smartisan.music.data.settings.EngineModeStore

/**
 * 1.4.11 · S12 三包收敛单包后的**解码前端运行时门面**。
 *
 * 三套引擎的真实实现（[OfficialDecodeFrontend] / [DirectDecodeFrontend] /
 * [StreamDecodeFrontend]）现已全部编进同一个 APK。本门面保留历史调用名
 * `FlavorDecodeFrontend`（main 源集 30+ 处引用不动），按 [EngineModeStore] 当前模式
 * 把每次调用委托给选中的实现——从而把「编译期 flavor 差异」改成「运行时模式」。
 *
 * 引擎切换需重启 App 生效（Application.onCreate 时定死引擎，避免运行中重建 Player）。
 */
internal object FlavorDecodeFrontend : DecodeFrontend {

    private val active: DecodeFrontend
        get() = when (EngineModeStore.current()) {
            EngineModeStore.Mode.DIRECT -> DirectDecodeFrontend
            EngineModeStore.Mode.STREAM -> StreamDecodeFrontend
            else -> OfficialDecodeFrontend
        }

    override val engineSummary: String get() = active.engineSummary
    override val capabilityNote: String get() = active.capabilityNote
    override val fallbackOnUnsupported: Boolean get() = active.fallbackOnUnsupported
    override val directReadFirst: Boolean get() = active.directReadFirst

    override fun plan(context: Context, uri: Uri): DecodePlan = active.plan(context, uri)
    override fun selfProbe(context: Context, uri: Uri?): String = active.selfProbe(context, uri)
    override fun probeOnOpen(context: Context, uri: Uri): String? = active.probeOnOpen(context, uri)
    override fun openStream(
        context: Context,
        dataSpec: DataSpec,
        upstreamFactory: DataSource.Factory,
        listeners: List<androidx.media3.datasource.TransferListener>,
    ): StreamResult? = active.openStream(context, dataSpec, upstreamFactory, listeners)
}
