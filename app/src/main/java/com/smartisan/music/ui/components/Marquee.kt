package com.smartisan.music.ui.components

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartisan.music.data.settings.UiSettingsStore

/**
 * [1.4.24·R34/R35 统一滚动字幕] 全 App 只保留这一套跑马灯参数。
 *
 * 背景：v24 只在设置项的 summary 上加了滚动（velocity=16dp / repeatDelay=2600ms），
 * 播放栏标题是默认速度、副标题干脆只有省略号，同一个 App 里三种行为，用户一眼看出割裂。
 *
 * 统一口径（用户拍板）：
 *  - 慢速：16dp/s，跟手、不抢视线；
 *  - 首屏先静置 1.2s，避免一进页面就动；
 *  - 一轮结束停 2.6s 再重来，给眼睛留出读完的时间；
 *  - 无限循环，不加省略号、不换行（文本必须单行）。
 *
 * [1.4.25·R44] 增加**全局开关**（设置 → 界面 → 文字滚动）：传 `enabled = null`（默认）
 * 时自动读 [UiSettingsStore.textMarquee]，一处开关控制全 App 所有滚动点，不用每个
 * 调用点各接一遍。关闭态返回原 Modifier（不滚动），配合调用方的
 * `overflow = TextOverflow.Clip` 实现"直接裁切、不出省略号"。
 *
 * 用法：`BasicText(text, Modifier.smartisanMarquee(), maxLines = 1, softWrap = false)`
 */
@Composable
fun Modifier.smartisanMarquee(
    enabled: Boolean? = null,
    velocityDp: Int = 16,
    repeatDelayMs: Int = 2_600,
    initialDelayMs: Int = 1_200,
): Modifier {
    val context = LocalContext.current
    val store = remember(context) { UiSettingsStore(context) }
    val globalEnabled by store.textMarquee.collectAsState(initial = true)
    val on = enabled ?: globalEnabled
    return if (!on) {
        this
    } else {
        this.basicMarquee(
            iterations = Int.MAX_VALUE,
            velocity = velocityDp.dp,
            repeatDelayMillis = repeatDelayMs,
            initialDelayMillis = initialDelayMs,
            // [1.4.25·R68] 全局统一缩短句尾到下一轮句首的空白：官方默认 1/3 容器宽太大，
            // 这里收到 8% —— 一句读完稍留白即接下一句。
            spacing = MarqueeSpacing.fractionOfContainer(0.08f),
        )
    }
}

