package com.smartisan.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音质/时长回填的「通知总线」（1.2.26）。
 *
 * 背景：DFF/DSF/APE 这类格式，MediaStore 没有时长，`player.duration` 在流式转码期间
 * 也恒为 0；[com.smartisan.music.platform.media.AudioFormatProbe] 探测到结果后只写进了
 * [com.smartisan.music.platform.media.FormatCache]，**不会触发 Compose 重组**，于是大播放页
 * 的进度条一直停在 0:00、歌曲信息里时长/采样率/位深全是「—」。
 *
 * 这里只做一件事：探测成功时自增 [revision]，UI 侧 `collectAsState` 它即可重新读缓存。
 * 刻意不去 `replaceMediaItem`——那会重建 MediaSource 并造成一次可听的断音。
 */
internal object PlaybackFormatBus {

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun bump() {
        _revision.value = _revision.value + 1
    }
}
