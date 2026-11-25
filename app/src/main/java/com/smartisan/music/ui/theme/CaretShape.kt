package com.smartisan.music.ui.theme

/**
 * 排序栏收起/展开三角、设置页卡片三角共用的宽高比。
 *
 * [v24 返工] 严格取 v19 原素材的真实比例：原 PNG bbox 为 x=40..67、y=46..61，
 * 即宽 28、高 16 → 28/16 = 1.75。之前误写成 1.65（来自我重画时的 28×17 bbox），
 * 属于未原样还原的错误，已纠正。
 *
 * 排序栏三角现用 v19 原 PNG（drawable-xxhdpi/btn_songs_chrome_*.png），不走矢量；
 * 设置页三角用本常量在 Compose Canvas 里画，两者比例统一为 1.75。
 */
internal const val CaretAspect = 1.75f
