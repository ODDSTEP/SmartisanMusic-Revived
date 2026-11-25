package com.smartisan.music.ui.settings

import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.theme.monetContentBackground
import com.smartisan.music.ui.theme.themedAccent
import kotlinx.coroutines.launch
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.library.cardTextureMonet
import com.smartisan.music.data.memory.insight
import com.smartisan.music.data.settings.*
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.navigation.*
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.smartisan.music.ui.theme.themedInkRes

/**
 * [v18·数值输入弹窗] 把外部 String 状态包装成「进入弹窗光标停在文本末尾」的 TextFieldValue。
 * - 外部 text 变化（打开弹窗时填入初值，如 "+0"/"0"）→ 光标重置到末尾；
 * - 用户自己键入 → 保留其光标位置（不强制末尾），符合 Compose 最佳实践。
 * 返回一个 [TextFieldValue, (TextFieldValue) -> Unit] 对，直接喂给 BasicTextField。
 */
@Composable
private fun rememberCursorEndField(
    text: String,
    onTextChange: (String) -> Unit,
): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // 外部文本变化（初值注入/重置）时把光标挪到末尾。
    LaunchedEffect(text) {
        if (fieldValue.text != text) {
            fieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }
    val onValue: (TextFieldValue) -> Unit = { v ->
        fieldValue = v
        if (v.text != text) onTextChange(v.text)
    }
    return fieldValue to onValue
}

@Composable
// [护栏 G-31·1.4.6 落地] 设置页卡片顺序；1.4.8 在 G-36 下把外观卡片上移：
// ui→appearance(深色模式/色彩主题/状态栏沉浸)→output→diagnostics→playback→file_scan→memory；
// 资料库整卡取消，分隔符逻辑/存储/弹窗未动
internal fun SettingsRootPage(
    active: Boolean,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    onClose: () -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAudioFxClick: () -> Unit,
    onColorThemeClick: () -> Unit,
    onArtistSeparatorsClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onThemeClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onEngineClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val playbackStore = androidx.compose.runtime.remember(context) {
        runCatching { com.smartisan.music.data.settings.PlaybackSettingsStore(context) }.getOrNull()
    }
    // [v20·零损失] 冲突功能置灰 / 透明降级提示的状态源。
    val zeroLoss by com.smartisan.music.data.settings.UsbZeroLossStore.enabled.collectAsState()
    val fallbackReason by
        com.smartisan.music.playback.UsbExclusiveSinkRef.fallbackReason.collectAsState()
    SettingsScaffold(active, stringResource(R.string.setting), onClose, modifier, complete = true) {
        // [1.4.25·R46] 卡内顺序（用户拍板）：沉浸模式→底栏可见数→图标文字提示→
        // 专辑页播放栏→通知栏布局→对调排序/播放栏(R53)→文字滚动(R44)。
        // 前两项「专辑页播放栏/通知栏布局」由外观卡搬入本卡（R46②）。
        SettingsSection(R.string.settings_section_ui) {
            val uiStore = remember(context) {
                com.smartisan.music.data.settings.UiSettingsStore(context)
            }
            val iconNameHint by uiStore.iconNameHintEnabled.collectAsState(initial = true)
            val immersiveMode by uiStore.immersiveModeEnabled.collectAsState(initial = true)
            val albumPlayBarVisible by
                uiStore.albumPlayActionsVisible.collectAsState(initial = true)
            val notificationCustom by uiStore.notificationCustomLayout.collectAsState(initial = true)
            val sortPlayBarSwap by uiStore.sortPlayBarSwap.collectAsState(initial = false)
            val textMarquee by uiStore.textMarquee.collectAsState(initial = true)
            // 1) 沉浸模式
            SettingsSwitchRow(
                R.string.immersive_mode,
                immersiveMode,
                RowShape.Top,
                { enabled -> scope.launch { uiStore.setImmersiveModeEnabled(enabled) } },
            )
            // 2) 底栏可见数
            val pinned =
                pluralStringResource(
                    R.plurals.bottom_tab_pinned_count,
                    navigationSettings.layout.bottomCount,
                    navigationSettings.layout.bottomCount,
                )
            val more =
                pluralStringResource(
                    R.plurals.bottom_tab_more_count,
                    navigationSettings.layout.overflowDestinations.size,
                    navigationSettings.layout.overflowDestinations.size,
                )
            SettingsValueRow(
                R.string.bottom_tab_visibility,
                stringResource(R.string.bottom_tab_layout_summary, pinned, more),
                true,
                RowShape.Middle,
                onNavigationClick,
            )
            // 3) 图标文字提示
            SettingsSwitchDialogRow(
                R.string.icon_name_hint,
                iconNameHint,
                RowShape.Middle,
                { enabled -> scope.launch { uiStore.setIconNameHintEnabled(enabled) } },
                descOn = R.string.icon_name_hint_desc_on,
                descOff = R.string.icon_name_hint_desc_off,
            )
            // 4) 专辑页播放栏（R46② 由外观卡搬入）
            SettingsSwitchDialogRow(
                R.string.album_play_bar_visible,
                albumPlayBarVisible,
                RowShape.Middle,
                { visible -> scope.launch { uiStore.setAlbumPlayActionsVisible(visible) } },
                descOn = R.string.album_play_bar_visible_desc_on,
                descOff = R.string.album_play_bar_visible_desc_off,
            )
            // 5) 通知栏布局（R46② 由外观卡搬入）
            SettingsSwitchDialogRow(
                R.string.notification_custom_layout,
                notificationCustom,
                RowShape.Middle,
                { custom -> scope.launch { uiStore.setNotificationCustomLayout(custom) } },
                descOn = R.string.notification_custom_layout_desc_on,
                descOff = R.string.notification_custom_layout_desc_off,
            )
            // 6) [R53] 排序栏与播放栏对调
            SettingsSwitchDialogRow(
                R.string.sort_play_bar_swap,
                sortPlayBarSwap,
                RowShape.Middle,
                { swapped -> scope.launch { uiStore.setSortPlayBarSwap(swapped) } },
                descOn = R.string.sort_play_bar_swap_desc_on,
                descOff = R.string.sort_play_bar_swap_desc_off,
            )
            // 7) [R44] 文字滚动显示总开关
            SettingsSwitchDialogRow(
                R.string.text_marquee,
                textMarquee,
                RowShape.Bottom,
                { enabled -> scope.launch { uiStore.setTextMarquee(enabled) } },
                descOn = R.string.text_marquee_desc_on,
                descOff = R.string.text_marquee_desc_off,
            )
        }
        // [护栏 G-36·1.4.8 落地] 外观卡片上移到「界面」下、「音频输出」上；
        // 色彩主题从界面搬入本卡（Top=深色模式 / Middle=色彩主题 / Bottom=状态栏沉浸）。
        SettingsSection(R.string.settings_section_appearance) {
            val colorTheme by
                com.smartisan.music.data.settings.ColorThemeStore.theme.collectAsState()
            val statusBarImmersive by
                com.smartisan.music.data.settings.StatusBarStyleStore.immersive.collectAsState()
            SettingsValueRow(
                R.string.theme_settings,
                stringResource(themeMode.labelRes),
                true,
                RowShape.Top,
                onThemeClick,
            )
            SettingsValueRow(
                R.string.color_theme,
                stringResource(colorTheme.labelRes),
                true,
                RowShape.Middle,
                onColorThemeClick,
            )
            SettingsSwitchDialogRow(
                R.string.status_bar_immersive,
                statusBarImmersive,
                RowShape.Middle,
                { enabled ->
                    com.smartisan.music.data.settings.StatusBarStyleStore.set(context, enabled)
                },
                descOn = R.string.status_bar_immersive_desc_on,
                descOff = R.string.status_bar_immersive_desc_off,
            )
            // [1.4.14·S08] 真正隐藏状态栏：开=全屏、状态栏消失。此前只有"沉浸"改颜色，
            // 无论怎么切状态栏都显示着，用户找不到"隐藏"入口——这里补上真正的隐藏开关。
            val statusBarHidden by
                com.smartisan.music.data.settings.StatusBarStyleStore.hidden.collectAsState()
            SettingsSwitchDialogRow(
                R.string.status_bar_hidden,
                statusBarHidden,
                RowShape.Bottom,
                { enabled ->
                    com.smartisan.music.data.settings.StatusBarStyleStore.setHidden(context, enabled)
                },
                descOn = R.string.status_bar_hidden_desc_on,
                descOff = R.string.status_bar_hidden_desc_off,
            )
            // [1.4.25·R46②] 专辑页播放栏 / 通知栏布局两开关已搬入「界面」卡；
            // 外观卡只保留纯视觉项（深色模式/色彩主题/状态栏沉浸/状态栏隐藏）。
            // 末项改 Bottom 收口。
        }
        SettingsSection(R.string.settings_section_playback) {
            SettingsValueRow(
                R.string.equalizer,
                // [1.4.20·摒弃开关] 不再显示「关闭」状态，摘要直接显示当前预设
                // （原声 / 耳机名等），用户要求「没有开关状态，它用什么就是什么」。
                when {
                    playbackSettings.eqPresetKey.isNotBlank() ->
                        com.smartisan.music.data.settings.SaltEqCatalog.v4Names[playbackSettings.eqPresetKey]
                            ?: com.smartisan.music.data.settings.SaltEqCatalog.autoEqNames[playbackSettings.eqPresetKey]
                            ?: playbackSettings.eqPresetKey
                    else -> playbackSettings.audioFxPreset.labelText()
                },
                true,
                RowShape.Top,
                onAudioFxClick,
            )
            // [1.4.23·功能去重] 「音量均衡/播放增益」已并入均衡器页的单一「响度均衡(ReplayGain)」
            // 区块（标签+回退+增益三件套），此处不再重复摆放，避免两处同功能重合、双重叠加。
            // [v20·零损失] 打碟音与爆豆音会破坏逐字节直传，零损失开启时置灰并说明。
            SettingsSwitchRow(
                R.string.djing,
                playbackSettings.scratchEnabled,
                RowShape.Middle,
                onScratchEnabledChange,
                enabled = !zeroLoss,
                lockedSummary = if (zeroLoss) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsSwitchRow(
                R.string.player_axis_enabled,
                playbackSettings.hidePlayerAxisEnabled,
                RowShape.Middle,
                onHidePlayerAxisEnabledChange,
            )
            SettingsSwitchRow(
                R.string.popcorn_sound,
                playbackSettings.popcornSoundEnabled,
                RowShape.Middle,
                onPopcornSoundEnabledChange,
                enabled = !zeroLoss,
                lockedSummary = if (zeroLoss) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsSwitchDialogRow(
                R.string.exclude_dsd,
                playbackSettings.excludeDsdEnabled,
                RowShape.Bottom,
                { enabled ->
                    scope.launch {
                        playbackStore?.setExcludeDsdEnabled(enabled)
                    }
                },
                descOn = R.string.exclude_dsd_desc_on,
                descOff = R.string.exclude_dsd_desc_off,
            )
        }
        // 1.4.11 · S12：单包三引擎切换入口（重启生效）。独立成区，不动原输出分区 shape 链。
        SettingsSection(R.string.settings_section_engine) {
            val engineMode by com.smartisan.music.data.settings.EngineModeStore.mode.collectAsState()
            SettingsValueRow(
                R.string.playback_engine,
                engineMode.label,
                true,
                RowShape.Single,
                onEngineClick,
            )
        }
        SettingsSection(R.string.settings_section_output) {
            val usbDevices by com.smartisan.music.playback.UsbAudioMonitor.devices.collectAsState(
                initial = emptyList()
            )
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.smartisan.music.playback.UsbAudioMonitor.start(context.applicationContext)
            }
            val device = usbDevices.firstOrNull()
            val usbActive = com.smartisan.music.playback.UsbDac.isActive
            // [1.4.5·清单 5·护栏 G-17] 观察「真实独占是否建立」：开关开了但 USB 权限没拿到 / 会话未建立时，
            // 不再显示「独占输出中」误导用户，明确标注「USB 权限未授予，当前非独占」。
            // 只加一个 when 分支与一个状态读取，不动主题/配色/外观/开关行为。
            val exclusiveEstablished by
                com.smartisan.music.playback.PlaybackPolicy.usbExclusiveEstablishedFlow
                    .collectAsState(initial = false)
            // 1.4.18·切引擎即时生效：这里必须用 collectAsState 订阅引擎 flow，
            // 不能用 current() 快照——快照不参与重组，切完引擎退回本页不会刷新置灰状态。
            val engineMode by com.smartisan.music.data.settings.EngineModeStore.mode.collectAsState()
            val directEngine = engineMode == com.smartisan.music.data.settings.EngineModeStore.Mode.DIRECT
            val usbSummary: String? =
                when {
                    // [1.4.24·R26] 直读引擎的原因只保留一处：放到开关左边（lockedSummary），
                    // 名称下方不再重复"系统直读版不支持"，也不再用"不可用"这种没信息量的占位。
                    directEngine -> null
                    device == null && !usbActive -> stringResource(R.string.usb_device_none)
                    // [1.4.25·R75] 有明确降级原因时优先显示 sink 写入的真实原因（已按
                    // 熔断/会话未建立/格式不支持细分），避免一律甩"DAC 不支持当前源"误导；
                    // 放在 !exclusiveEstablished 之前，保证重进设置页文案稳定、不再图1→图2跳变。
                    playbackSettings.usbExclusiveEnabled && fallbackReason != null -> fallbackReason
                    playbackSettings.usbExclusiveEnabled && !exclusiveEstablished ->
                        stringResource(R.string.usb_exclusive_permission_pending)
                    playbackSettings.usbExclusiveEnabled ->
                        stringResource(R.string.usb_exclusive_active, device?.name ?: "USB DAC")
                    else -> stringResource(R.string.usb_device_connected, device?.name ?: "USB DAC")
                }
            SettingsSwitchRow(
                R.string.usb_exclusive,
                if (directEngine) false else playbackSettings.usbExclusiveEnabled,
                RowShape.Top,
                { enabled -> scope.launch { playbackStore?.setUsbExclusiveEnabled(enabled) } },
                enabled = !directEngine,
                lockedSummary =
                    if (directEngine) {
                        stringResource(R.string.usb_exclusive_direct_unavailable)
                    } else null,
                summary = usbSummary,
            )
            // [v20·零损失] bit-perfect 开关：依赖 USB 独占（DIRECT 引擎或独占关时置灰说明）。
            // 禁用态用短标签（长说明放行下，避免被 maxLines=1 截成半句）。
            SettingsSwitchDialogRow(
                R.string.usb_zero_loss,
                zeroLoss,
                RowShape.Middle,
                { enabled ->
                    com.smartisan.music.data.settings.UsbZeroLossStore.set(context, enabled)
                },
                descOn = R.string.usb_zero_loss_desc_on,
                descOff = R.string.usb_zero_loss_desc_off,
                enabled = !directEngine && playbackSettings.usbExclusiveEnabled,
                lockedSummary =
                    when {
                        directEngine -> stringResource(R.string.usb_zero_loss_need_engine)
                        !playbackSettings.usbExclusiveEnabled ->
                            stringResource(R.string.usb_zero_loss_need_usb)
                        else -> null
                    },
            )
            // [v20] 说明与降级提示都不插在行与行之间：设置卡片是 Top/Middle/Bottom 拼成的一整块，
            // 中间插文字会把卡片切断（露出页面底色、方块边角）。说明由开关弹窗承担，
            // 降级短句放进 USB 独占行的 summary（见上）。
            //
            // [v22·音量] 1.2.6 把音量模式整套摘掉了（只留线性 PCM 衰减），这里按 Salt Player
            // 的口径恢复：自动 / 数字音量（-60~0 dB 映射）/ DAC 硬件音量（UAC SET_CUR）。
            val volumeMode by com.smartisan.music.playback.UsbVolumeModeStore.mode.collectAsState()
            val hwVolSupported by com.smartisan.music.playback.UsbVolume.hardwareSupportedFlow.collectAsState()
            val currentDb by com.smartisan.music.playback.UsbVolume.currentDbFlow.collectAsState()
            var showVolumeMode by remember { mutableStateOf(false) }
            SettingsChoiceRow(
                R.string.usb_volume_mode,
                value =
                    stringResource(
                        when (volumeMode) {
                            com.smartisan.music.playback.UsbVolumeMode.AUTO ->
                                R.string.usb_volume_mode_auto
                            com.smartisan.music.playback.UsbVolumeMode.DIGITAL ->
                                R.string.usb_volume_mode_digital
                            com.smartisan.music.playback.UsbVolumeMode.HARDWARE ->
                                R.string.usb_volume_mode_dac
                        }
                    ),
                summary =
                    stringResource(
                        R.string.usb_volume_current,
                        "%.1f dB".format(java.util.Locale.US, currentDb),
                        stringResource(
                            if (hwVolSupported) R.string.usb_volume_hw_supported
                            else R.string.usb_volume_hw_unsupported
                        ),
                    ),
                shape = RowShape.Middle,
                onClick = { showVolumeMode = true },
            )
            if (showVolumeMode) {
                UsbVolumeModeDialog(
                    current = volumeMode,
                    hardwareSupported = hwVolSupported,
                    zeroLoss = zeroLoss,
                    onSelect = {
                        com.smartisan.music.playback.UsbVolumeModeStore.set(context, it)
                        showVolumeMode = false
                    },
                    onDismiss = { showVolumeMode = false },
                )
            }
            // [v22·TPDF] 数字衰减会等效降位深，量化前加 ±1 LSB 三角分布噪声。
            val dither by com.smartisan.music.playback.UsbVolumeModeStore.dither.collectAsState()
            SettingsSwitchDialogRow(
                R.string.usb_tpdf_dither,
                dither,
                RowShape.Middle,
                { enabled ->
                    com.smartisan.music.playback.UsbVolumeModeStore.setDither(context, enabled)
                },
                descOn = R.string.usb_tpdf_dither_desc_on,
                descOff = R.string.usb_tpdf_dither_desc_off,
                summary = stringResource(R.string.usb_tpdf_dither_summary),
                enabled = !directEngine && !zeroLoss,
                lockedSummary =
                    when {
                        directEngine -> stringResource(R.string.usb_exclusive_direct_locked)
                        zeroLoss -> stringResource(R.string.usb_zero_loss_locked)
                        else -> null
                    },
            )
            // 插入音频设备自动续播：接入有线耳机 / USB 声卡时自动恢复播放
            // （描述较长，行内小字会被裁剪 → 切换时弹窗说明，1.2.3）
            SettingsSwitchDialogRow(
                R.string.plug_auto_resume,
                playbackSettings.plugAutoResumeEnabled,
                RowShape.Middle,
                { enabled -> scope.launch { playbackStore?.setPlugAutoResumeEnabled(enabled) } },
                descOn = R.string.plug_auto_resume_desc_on,
                descOff = R.string.plug_auto_resume_desc_off,
            )
            // [1.4.6·X01/X02/X03] 独占性能优先：默认开，用户可关（略省电）。
            // [护栏 G-34·1.4.7 落地] 就地取材：删除 1.4.6 那行 value 会被 maxLines=1/Clip 截断的
            // 「允许后台活动」长说明行；电池优化白名单引导文字并入本开关的现成 SettingsDescDialog，
            // 不截断、不新增任何行样式。独占性能优先由 Middle 收口为 Bottom。
            var perfPriority by remember(context) {
                mutableStateOf(
                    com.smartisan.music.playback.UsbExclusivePerformance.isEnabled(context)
                )
            }
            SettingsSwitchDialogRow(
                R.string.usb_exclusive_perf_priority,
                perfPriority,
                RowShape.Middle,
                { enabled ->
                    perfPriority = enabled
                    com.smartisan.music.playback.UsbExclusivePerformance.setEnabled(context, enabled)
                },
                descOn = R.string.usb_exclusive_perf_priority_desc_on,
                descOff = R.string.usb_exclusive_perf_priority_desc_off,
                // [1.4.9·G-50] A 版（系统直读）不建 UAC 会话，此开关空转，置灰并说明。
                enabled = !directEngine,
                lockedSummary =
                    if (directEngine) stringResource(R.string.usb_exclusive_perf_priority_locked) else null,
            )
            // [1.4.19·S03] 新开源 USB 独占地基开关（默认关）。开启后独占走 libusb 用户态引擎，
            // 失败会自动回退旧引擎；需实机验证。DIRECT 引擎下无独占，置灰。
            // [v15] 开启前弹风险提示（闪退/杂音），用户确认后才真正开启。
            val useNewUsb by com.smartisan.music.data.settings.UsbEngineStore.useNew.collectAsState()
            var showNewEngineRisk by remember { mutableStateOf(false) }
            // [#93] 改用不带内部说明弹窗的 SettingsSwitchRow：原 SettingsSwitchDialogRow 会在
            // onChange 里无条件再弹一个说明弹窗，与下面的风险弹窗叠加（关掉上层露出下层图1）。
            // 说明文案已并入风险弹窗正文，一次拨动只弹一个窗。
            SettingsSwitchRow(
                R.string.usb_new_engine,
                useNewUsb,
                RowShape.Bottom,
                { enabled ->
                    if (enabled && !useNewUsb) {
                        showNewEngineRisk = true
                    } else {
                        com.smartisan.music.data.settings.UsbEngineStore.set(context, enabled)
                    }
                },
                enabled = !directEngine,
                lockedSummary =
                    if (directEngine) stringResource(R.string.usb_exclusive_direct_locked) else null,
            )
            if (showNewEngineRisk) {
                NewEngineRiskDialog(
                    onConfirm = {
                        showNewEngineRisk = false
                        com.smartisan.music.data.settings.UsbEngineStore.set(context, true)
                    },
                    onCancel = { showNewEngineRisk = false },
                )
            }
            if (directEngine) {
                // 开关行的行内小字单行会裁剪，单独给一块多行说明，别让用户对着灰开关猜原因。
                BasicText(
                    stringResource(R.string.usb_exclusive_direct_note),
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 2.dp),
                    style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
                )
            }
        }
        // [1.4.19·S07] 智能过渡（自动混音）：独立 section，默认关、开了才生效。
        SettingsSection(R.string.smart_crossfade) {
            val cfEnabled by com.smartisan.music.data.settings.SmartCrossfadeStore.enabled.collectAsState()
            val cfDuration by com.smartisan.music.data.settings.SmartCrossfadeStore.durationSec.collectAsState()
            // [1.4.23] 过渡时长：点击弹出选项列表 + 自定义输入，不再一个个点循环切换。
            var cfDurationPickerOpen by remember { mutableStateOf(false) }
            var cfCustomInput by remember { mutableStateOf("") }
            SettingsSwitchDialogRow(
                R.string.smart_crossfade,
                cfEnabled,
                RowShape.Top,
                { enabled ->
                    com.smartisan.music.data.settings.SmartCrossfadeStore.setEnabled(context, enabled)
                },
                descOn = R.string.smart_crossfade_desc_on,
                descOff = R.string.smart_crossfade_desc_off,
            )
            SettingsValueRow(
                R.string.smart_crossfade_duration,
                stringResource(R.string.smart_crossfade_duration_value, cfDuration),
                true,
                RowShape.Bottom,
                {
                    cfCustomInput = cfDuration.toString()
                    cfDurationPickerOpen = true
                },
            )
            if (cfDurationPickerOpen) {
                val focus = remember { FocusRequester() }
                SmartisanModal(
                    { cfDurationPickerOpen = false },
                    Modifier.widthIn(max = dimensionResource(R.dimen.revone_global_dialog_content_width))
                        .fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .cardTextureMonet(R.drawable.revone_global_dialog_shape_background)
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                stringResource(R.string.smart_crossfade_duration),
                                style = TextStyle(
                                    color = colorResource(R.color.title_color),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                            )
                        }
                        Column(
                            Modifier.fillMaxWidth()
                                .cardTextureMonet(R.drawable.revone_global_dialog_message_background)
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                        ) {
                            // 预设选项列表 3 / 6 / 10 秒，点选即生效并关闭弹窗。
                            com.smartisan.music.data.settings.SmartCrossfadeStore.supportedDurationSecs
                                .forEach { sec ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .height(44.dp)
                                            .clickable {
                                                com.smartisan.music.data.settings.SmartCrossfadeStore
                                                    .setDurationSec(context, sec)
                                                cfDurationPickerOpen = false
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicText(
                                            stringResource(R.string.smart_crossfade_duration_value, sec),
                                            style = settingsTextStyle().copy(
                                                color = colorResource(
                                                    if (sec == cfDuration) R.color.blue_btn_text_color_selector
                                                    else R.color.title_color
                                                )
                                            ),
                                        )
                                    }
                                }
                            // 自定义秒数输入（1~60 秒）。
                            Row(
                                Modifier.fillMaxWidth().height(44.dp)
                                    .smartisanPainterBackground(
                                        rememberSmartisanDrawablePainter(R.drawable.edit_text_bg)
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val cfField = rememberCursorEndField(cfCustomInput) { cfCustomInput = it }
                                BasicTextField(
                                    cfField.first,
                                    cfField.second,
                                    Modifier.weight(1f)
                                        .padding(horizontal = 12.dp)
                                        .focusRequester(focus),
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = colorResource(R.color.editor_text_color),
                                        fontSize = 15.sp,
                                    ),
                                    cursorBrush = SolidColor(colorResource(R.color.editor_text_color)),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        val v = cfCustomInput.toIntOrNull()
                                        if (v != null) {
                                            com.smartisan.music.data.settings.SmartCrossfadeStore
                                                .setCustomDurationSec(context, v)
                                        }
                                        cfDurationPickerOpen = false
                                    }),
                                    decorationBox = { field ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (cfCustomInput.isEmpty())
                                                BasicText(
                                                    "自定义秒数 1 ~ 60",
                                                    style = settingsTextStyle(summary = true),
                                                )
                                            field()
                                        }
                                    },
                                )
                            }
                            BasicText(
                                "选择预设，或直接输入 1 ~ 60 秒后按确定",
                                Modifier.padding(top = 8.dp),
                                style = settingsTextStyle(summary = true).copy(fontSize = 12.sp),
                            )
                            // 确定按钮
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(dimensionResource(R.dimen.revone_dialog_button_height))
                                    .clickable {
                                        val v = cfCustomInput.toIntOrNull()
                                        if (v != null) {
                                            com.smartisan.music.data.settings.SmartCrossfadeStore
                                                .setCustomDurationSec(context, v)
                                        }
                                        cfDurationPickerOpen = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    stringResource(R.string.done),
                                    style = settingsTextStyle().copy(
                                        color = colorResource(R.color.blue_btn_text_color_selector)
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        SettingsSection(R.string.settings_section_file_scan) {
            val fileScanStore = remember(context) {
                com.smartisan.music.data.settings.FileScanSettingsStore(context)
            }
            val fileScanSettings by
                fileScanStore.settings.collectAsState(
                    initial = com.smartisan.music.data.settings.FileScanSettings()
                )
            SettingsSwitchRow(
                R.string.settings_filter_non_song,
                fileScanSettings.filterNonSong,
                RowShape.Top,
                onChange = { enabled ->
                    scope.launch { fileScanStore.setFilterNonSong(enabled) }
                },
            )
            SettingsSwitchRow(
                R.string.settings_skip_small_files,
                fileScanSettings.skipSmallFiles,
                RowShape.Middle,
                onChange = { enabled ->
                    scope.launch { fileScanStore.setSkipSmallFiles(enabled) }
                },
            )
            SettingsSwitchRow(
                R.string.settings_skip_short_songs,
                fileScanSettings.skipShortSongs,
                RowShape.Middle,
                onChange = { enabled ->
                    scope.launch { fileScanStore.setSkipShortSongs(enabled) }
                },
            )
            // [护栏 G-34·1.4.7 落地] 艺人拆分规则并入同一张卡片做第 4 行（Top/Middle/Middle/Bottom），
            // 取消单独的 Single 矩形框与卡片外孤行说明；用途说明并进点开的现成 ArtistSeparatorsDialog。
            SettingsValueRow(
                R.string.artist_separators,
                artistSettings.separators.sorted().joinToString(" ").ifEmpty {
                    stringResource(R.string.not_set)
                },
                true,
                RowShape.Bottom,
                onArtistSeparatorsClick,
            )
        }
        SettingsSection(R.string.settings_section_memory) {
            SettingsValueRow(
                R.string.memory,
                stringResource(R.string.memory_summary),
                true,
                RowShape.Top,
                onMemoryClick,
            )
            // 1.4.11 · S16 记忆式首页开关（默认开）。与「理解记忆」统计分开，只管本地列表位置。
            val rememberScroll by
                com.smartisan.music.data.settings.SongListMemoryStore.enabled.collectAsState()
            SettingsSwitchDialogRow(
                R.string.remember_song_scroll,
                rememberScroll,
                RowShape.Bottom,
                { enabled ->
                    com.smartisan.music.data.settings.SongListMemoryStore.set(context, enabled)
                },
                descOn = R.string.remember_song_scroll_desc_on,
                descOff = R.string.remember_song_scroll_desc_off,
            )
        }
        SettingsSection(R.string.settings_section_diagnostics) {
            SettingsValueRow(
                R.string.export_logs,
                stringResource(R.string.export_logs_summary),
                true,
                RowShape.Top,
                {
                    // [1.4.25·R71] 导出日志改为后台执行（原来在主线程 waitFor 导致 ANR），
                    // 加 try/catch 兜底——正在导出时会直接返回 true 被忽略，不弹失败提示。
                    scope.launch {
                        val ok = runCatching {
                            com.smartisan.music.diagnostics.LogExporter.exportAndShare(context)
                        }.getOrDefault(false)
                        if (!ok) {
                            android.widget.Toast.makeText(
                                context,
                                R.string.export_logs_failed,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
            // 转码缓存管理（1.2.16 方案 D）：显示占用 + 一键清理
            var transcodeCacheSize by remember { mutableStateOf(0L) }
            var transcodeCacheRefresh by remember { mutableStateOf(0) }
            var showTranscodeCacheClear by remember { mutableStateOf(false) }
            LaunchedEffect(transcodeCacheRefresh) {
                transcodeCacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.smartisan.music.playback.ApeTranscoder.cacheSizeBytes(context)
                }
            }
            SettingsValueRow(
                R.string.transcode_cache,
                android.text.format.Formatter.formatShortFileSize(context, transcodeCacheSize),
                true,
                RowShape.Bottom,
                { showTranscodeCacheClear = true },
            )
            if (showTranscodeCacheClear) {
                SmartisanDeleteConfirmation(
                    stringResource(R.string.transcode_cache_clear_confirm),
                    { showTranscodeCacheClear = false },
                    {
                        showTranscodeCacheClear = false
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.smartisan.music.playback.ApeTranscoder.clearCache(context)
                            }
                            transcodeCacheRefresh++
                            android.widget.Toast.makeText(
                                context,
                                R.string.transcode_cache_cleared,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
        SettingsGap()
    }
}

@Composable
internal fun NavigationSettingsPage(
    active: Boolean,
    navigationSettings: NavigationSettings,
    onClose: () -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(active, stringResource(R.string.bottom_tab_visibility), onClose, modifier) {
        // 1.3.7：二级页左上小标题已去掉（与顶部大标题重复），只保留内容行。
        SettingsGap()
        Column(Modifier.fillMaxWidth()) {
            MusicDestination.movableEntries.forEachIndexed { index, destination ->
                val layout = navigationSettings.layout
                val pinned = layout.isPinned(destination)
                val enabled =
                    if (pinned) layout.bottomCount > MinBottomDestinationCount
                    else layout.bottomCount < MaxBottomDestinationCount
                SettingsSwitchRow(
                    destination.labelRes,
                    pinned,
                    if (index == 0) RowShape.Top else RowShape.Middle,
                    { onTabPinnedChange(destination.route, it) },
                    enabled = enabled,
                )
            }
            SettingsSwitchRow(
                MusicDestination.More.labelRes,
                true,
                RowShape.Bottom,
                {},
                enabled = false,
                lockedSummary = stringResource(R.string.bottom_tab_more_locked),
            )
        }
        SettingsGap()
    }
}

@Composable
internal fun AudioFxSettingsPage(
    active: Boolean,
    playbackSettings: PlaybackSettings,
    onClose: () -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // [1.4.20·摒弃开关] 没有开关状态，它用什么就是什么：当前预设即 audioFxPreset。
    // 原声对应全平曲线，天然「原声就是原声」，不再用 audioFxEnabled 兜回 Original。
    val preset = playbackSettings.audioFxPreset
    // [1.4.18·均衡器] 选中的耳机预设 key（V4/AutoEQ），非空时优先于内置 preset。
    val selectedEqKey = playbackSettings.eqPresetKey
    // [1.4.12·S05] 混响强度：沿用本文件已有的"Composable 直接构造 PlaybackSettingsStore"模式，
    // 不必穿过 4 层回调签名。读写都走 store，settings flow 会把新值推回 PcmDsp.configure。
    val reverbContext = androidx.compose.ui.platform.LocalContext.current
    val reverbScope = rememberCoroutineScope()
    val reverbStore = remember(reverbContext) {
        runCatching { com.smartisan.music.data.settings.PlaybackSettingsStore(reverbContext) }.getOrNull()
    }
    val reverbModes = AudioFxReverbMode.entries
    // [1.4.18·切引擎即时生效] 系统直读（DIRECT）引擎下 App 拿不到 PCM，自研音效全部失效：
    // 整个均衡器页置灰并给出明确说明。必须用 collectAsState 订阅引擎 flow，
    // 切完引擎退回本页立即刷新，不再需要退到主界面再进。
    val engineModeHere by com.smartisan.music.data.settings.EngineModeStore.mode.collectAsState()
    val directEngine = engineModeHere == com.smartisan.music.data.settings.EngineModeStore.Mode.DIRECT
    // [v20·零损失] EQ 会破坏逐字节直传，零损失开启时整页 EQ 置灰。
    val zeroLossEq by com.smartisan.music.data.settings.UsbZeroLossStore.enabled.collectAsState()
    // [1.4.20·摒弃开关] fxUsable 不再依赖 audioFxEnabled（那是已摒弃的隐藏总闸），
    // 由引擎能力 + 零损失共同决定：DIRECT 无 PCM 或零损失模式下置灰并给出说明。
    val fxUsable = !directEngine && !zeroLossEq

    // [1.4.23·弹窗改造] 压限强度/声道平衡/ReplayGain增益/变速 从"点击循环数值"改为
    // 点开输入弹窗（复用下方 EQ dB 弹窗同款 SmartisanModal）。editingFx 非空时展示对应弹窗。
    var editingFx by remember { mutableStateOf<Int?>(null) }
    var fxInput by rememberSaveable { mutableStateOf("") }
    // 0=压限强度(0..100) 1=声道平衡(-100..100) 2=ReplayGain增益(-6..6) 3=变速(50..200)
    val fxTitle = when (editingFx) {
        0 -> stringResource(R.string.fx_compressor_strength)
        1 -> stringResource(R.string.fx_channel_balance)
        2 -> stringResource(R.string.fx_replaygain_gain)
        3 -> stringResource(R.string.fx_speed)
        else -> ""
    }
    val fxRangeHint = when (editingFx) {
        0 -> "0 ~ 100（%）"
        1 -> "-100（全左） ~ +100（全右）"
        2 -> "-6 ~ +6 dB"
        3 -> "0.50x ~ 2.00x（输入 50~200）"
        else -> ""
    }

    // 动态小字：像 Salt Player 一样显示「N 个滤波器 ±X.X dB」，不再千篇一律。
    @Composable
    fun eqSummary(key: String?): String {
        // [1.4.23·修假空壳] 直接按 key 查三个数据源，不再用 startsWith("v4_") 前缀猜测——
        // V4 预设的真实 key 是 akg_k240_mk2 / bose_qc35 等（无前缀），旧判断导致 20 个
        // V4 预设一律查不到→显示"原声"，用户误以为"预设是空壳"。band.id 里才有 v4_ 前缀。
        val bands = key?.let {
            com.smartisan.music.data.settings.SaltEqPresets.bands[it]
                ?: com.smartisan.music.data.settings.SaltEqCatalog.v4Bands[it]
                ?: com.smartisan.music.data.settings.SaltEqCatalog.autoEqBands[it]
        } ?: return stringResource(R.string.audio_fx_original_summary)
        if (bands.isEmpty()) return stringResource(R.string.audio_fx_original_summary)
        val peak = bands.maxOf { kotlin.math.abs(it.gainDb) }
        val sign = if (bands.sumOf { it.gainDb.toDouble() } >= 0) "+" else "-"
        return "${bands.size} 个滤波器 $sign${"%.1f".format(peak)} dB"
    }

    SettingsScaffold(
        active,
        stringResource(R.string.equalizer),
        onClose,
        modifier,
        pinnedHeader = {
            // [1.4.23·EQ固定频响] 频响卡片钉在顶部，下方预设/音效选项滚动时它不动。
            SettingsGap()
            // [v20·零损失] 明确告知为何整页 EQ 置灰。
            if (zeroLossEq) {
                BasicText(
                    stringResource(R.string.usb_zero_loss_locked),
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
                    style = settingsTextStyle(summary = true),
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    .height(188.dp)
                    .smartisanShadowBackground(RowShape.Single.background, RowShape.Single.shadow)
                    .padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .height(34.dp)
                        .alpha(if (fxUsable) 1f else 0.72f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        stringResource(R.string.audio_fx_curve),
                        Modifier.weight(1f),
                        style = settingsTextStyle(),
                    )
                    BasicText(
                        preset.labelText(),
                        style = settingsTextStyle(summary = true),
                    )
                }
                AudioFxCurve(
                    preset,
                    fxUsable,
                    playbackSettings.audioFxCustomGainDbPoints,
                    onAudioFxCustomGainDbPointsChange,
                    Modifier.fillMaxWidth().weight(1f),
                    eqPresetKey = selectedEqKey,
                )
            }
            SettingsGap()
        },
    ) {
        // [1.4.20·摒弃开关] 没有独立的「启用音效」开关：原声即默认、选原声即全平曲线，
        // 其它预设即对应 DSP，自己调即自定义曲线；开关概念整体移除。
        if (directEngine) {
            BasicText(
                stringResource(R.string.audio_fx_direct_note),
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp),
                style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
            )
        }
        // [1.4.18·均衡器] 按 Salt Player 的方式分类：内置 / V4 耳机 / AutoEQ 耳机。
        // 内置预设（原声 + 5 个场景）；选中即关闭耳机预设 key。
        SettingsSection(R.string.eq_section_builtin) {
            Column(Modifier.selectableGroup()) {
                AudioFxPreset.entries.forEachIndexed { index, item ->
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectSmartisanPressedAsState()
                    val selected = selectedEqKey.isBlank() && item == preset
                    SettingsRow(
                        R.string.equalizer,
                        if (item == AudioFxPreset.Original) eqSummary(null)
                        else item.saltKey?.let { eqSummary(it) } ?: item.summaryText(),
                        RowShape.at(index, AudioFxPreset.entries.size),
                        fxUsable,
                        source,
                        Modifier.selectable(
                            selected,
                            source,
                            null,
                            enabled = fxUsable,
                            role = Role.RadioButton,
                            onClick = smartisanClick { onAudioFxPresetChange(item) },
                        ),
                        titleAccessoryGap = 10.dp,
                        titleText = item.labelText(),
                    ) {
                        EqRadio(selected, fxUsable, pressed)
                    }
                }
            }
        }
        // V4 耳机预设（Salt V4 tab 精确参数）——[1.4.23·长列表折叠] 默认收起
        val v4Keys = com.smartisan.music.data.settings.SaltEqCatalog.v4Bands.keys.toList()
        val v4Selected = selectedEqKey in com.smartisan.music.data.settings.SaltEqCatalog.v4Bands
        CollapsibleSettingsSection(
            R.string.eq_section_v4,
            summary = if (v4Selected) eqSummary(selectedEqKey) else "",
            expandedByDefault = v4Selected,
        ) {
            Column(Modifier.selectableGroup()) {
                v4Keys.forEachIndexed { index, key ->
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectSmartisanPressedAsState()
                    val selected = selectedEqKey == key
                    SettingsRow(
                        R.string.equalizer,
                        eqSummary(key),
                        RowShape.at(index, v4Keys.size),
                        fxUsable,
                        source,
                        Modifier.selectable(
                            selected, source, null, enabled = fxUsable, role = Role.RadioButton,
                            onClick = smartisanClick { reverbScope.launch { reverbStore?.setEqPresetKey(key) } },
                        ),
                        titleAccessoryGap = 10.dp,
                        titleText = com.smartisan.music.data.settings.SaltEqCatalog.v4Names[key] ?: key,
                    ) {
                        EqRadio(selected, fxUsable, pressed)
                    }
                }
            }
        }
        // AutoEQ 耳机预设（Salt AutoEQ tab，从 8850 个中精选知名型号）——默认收起
        val autoEqKeys = com.smartisan.music.data.settings.SaltEqCatalog.autoEqBands.keys.toList()
        val autoEqSelected = selectedEqKey in com.smartisan.music.data.settings.SaltEqCatalog.autoEqBands
        CollapsibleSettingsSection(
            R.string.eq_section_autoeq,
            summary = if (autoEqSelected) eqSummary(selectedEqKey) else "",
            expandedByDefault = autoEqSelected,
        ) {
            Column(Modifier.selectableGroup()) {
                autoEqKeys.forEachIndexed { index, key ->
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectSmartisanPressedAsState()
                    val selected = selectedEqKey == key
                    SettingsRow(
                        R.string.equalizer,
                        eqSummary(key),
                        RowShape.at(index, autoEqKeys.size),
                        fxUsable,
                        source,
                        Modifier.selectable(
                            selected, source, null, enabled = fxUsable, role = Role.RadioButton,
                            onClick = smartisanClick { reverbScope.launch { reverbStore?.setEqPresetKey(key) } },
                        ),
                        titleAccessoryGap = 10.dp,
                        titleText = com.smartisan.music.data.settings.SaltEqCatalog.autoEqNames[key] ?: key,
                    ) {
                        EqRadio(selected, fxUsable, pressed)
                    }
                }
            }
        }
        // [1.4.12·S05] 混响强度独立入口：用户此前在音效页找不到混响——它只被写死在预设
        // 配比里（人声/清澈 0.13，其余 0.05），无从调节。这里给出可见、可选的四档 + 跟随预设。
        // [1.4.25·R48] 去掉混响卡片前多余的 SettingsGap——它让混响标题上方间距达 75px，
        // 而 V4/AutoEQ 标题上方仅 33px，同一类标题却不对齐。去掉后与其它标题一致。
        SettingsSection(R.string.audio_fx_reverb) {
            val currentReverb = playbackSettings.audioFxReverbMode
            reverbModes.forEachIndexed { index, mode ->
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectSmartisanPressedAsState()
                SettingsRow(
                    reverbModeLabelRes(mode),
                    stringResource(reverbModeSummaryRes(mode)),
                    RowShape.at(index, reverbModes.size),
                    fxUsable,
                    source,
                    Modifier.selectable(
                        mode == currentReverb,
                        source,
                        null,
                        enabled = fxUsable,
                        role = Role.RadioButton,
                        onClick = smartisanClick {
                            reverbScope.launch { reverbStore?.setAudioFxReverbMode(mode) }
                        },
                    ),
                    titleAccessoryGap = 10.dp,
                ) {
                    Box(
                        Modifier.padding(end = 6.dp)
                            .size(28.dp)
                            .alpha(if (fxUsable) 1f else 0.62f)
                    ) {
                        if (mode == currentReverb)
                            Image(
                                rememberSmartisanDrawablePainter(
                                    R.drawable.selector_radio_choice,
                                    enabled = fxUsable,
                                    pressed = pressed,
                                ),
                                null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Inside,
                            )
                    }
                }
            }
        }
        // ===== [1.4.20·P0/P1/P2] 音效增强全家桶：全部用户可见可开关 =====
        SettingsGap()
        // [1.4.21] DIRECT 引擎无 PCM，这些音效无法生效，给出如实说明（不静默挂羊头）
        if (directEngine) {
            BasicText(
                stringResource(R.string.audio_fx_direct_note),
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp),
                style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
            )
        }
        SettingsSection(R.string.eq_section_effects) {
            val s = playbackSettings
            // P0 播放/暂停淡入淡出
            SettingsSwitchDialogRow(
                R.string.fx_playpause_fade,
                s.playPauseFadeEnabled,
                RowShape.Top,
                { reverbScope.launch { reverbStore?.setPlayPauseFadeEnabled(it) } },
                descOn = R.string.fx_playpause_fade_on,
                descOff = R.string.fx_playpause_fade_off,
            )
            // P1 压限器 [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_compressor,
                s.compressorEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setCompressorEnabled(it) } },
                descOn = R.string.fx_compressor_on,
                descOff = R.string.fx_compressor_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsValueRow(
                R.string.fx_compressor_strength,
                stringResource(R.string.fx_compressor_strength_value, s.compressorStrength),
                true,
                RowShape.Middle,
                onClick = { editingFx = 0; fxInput = s.compressorStrength.toString() },
            )
            // P1 音乐厅 [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_concert_hall,
                s.concertHallEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setConcertHallEnabled(it) } },
                descOn = R.string.fx_concert_hall_on,
                descOff = R.string.fx_concert_hall_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            // P1 V3 增强混响 [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_v3_reverb,
                s.v3ReverbEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setV3ReverbEnabled(it) } },
                descOn = R.string.fx_v3_reverb_on,
                descOff = R.string.fx_v3_reverb_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            // P1 声道平衡
            val balanceLabel = when {
                s.channelBalance < 0 -> stringResource(R.string.fx_channel_balance_left, -s.channelBalance)
                s.channelBalance > 0 -> stringResource(R.string.fx_channel_balance_right, s.channelBalance)
                else -> stringResource(R.string.fx_channel_balance_center)
            }
            SettingsValueRow(
                R.string.fx_channel_balance,
                stringResource(R.string.fx_channel_balance_value, balanceLabel),
                true,
                RowShape.Middle,
                onClick = { editingFx = 1; fxInput = s.channelBalance.toString() },
            )
            // P1 ReplayGain [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_replaygain,
                s.replayGainEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setReplayGainEnabled(it) } },
                descOn = R.string.fx_replaygain_on,
                descOff = R.string.fx_replaygain_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsSwitchDialogRow(
                R.string.fx_replaygain_fallback,
                s.replayGainFallbackEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setReplayGainFallbackEnabled(it) } },
                descOn = R.string.fx_replaygain_fallback_on,
                descOff = R.string.fx_replaygain_fallback_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsValueRow(
                R.string.fx_replaygain_gain,
                stringResource(R.string.fx_replaygain_gain_value, s.replayGainGainDb),
                true,
                RowShape.Middle,
                onClick = { editingFx = 2; fxInput = s.replayGainGainDb.toString() },
            )
            // P0 增益可视化
            SettingsSwitchDialogRow(
                R.string.fx_gain_meter,
                s.gainMeterEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setGainMeterEnabled(it) } },
                descOn = R.string.fx_gain_meter_on,
                descOff = R.string.fx_gain_meter_off,
            )
            // P2 倒放 [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_reverse,
                s.reverseEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setReverseEnabled(it) } },
                descOn = R.string.fx_reverse_on,
                descOff = R.string.fx_reverse_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            // P2 变速 [v20·零损失置灰]
            SettingsSwitchDialogRow(
                R.string.fx_varispeed,
                s.variSpeedEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setVariSpeedEnabled(it) } },
                descOn = R.string.fx_varispeed_on,
                descOff = R.string.fx_varispeed_off,
                enabled = !zeroLossEq,
                lockedSummary = if (zeroLossEq) stringResource(R.string.usb_zero_loss_locked) else null,
            )
            SettingsValueRow(
                R.string.fx_speed,
                stringResource(R.string.fx_speed_value, s.playbackSpeedX100 / 100f),
                true,
                RowShape.Middle,
                onClick = { editingFx = 3; fxInput = s.playbackSpeedX100.toString() },
            )
            // 3A 回退开关（修审计#8 死开关）
            SettingsSwitchDialogRow(
                R.string.fx_bass_dsp,
                s.bassDspEnabled,
                RowShape.Middle,
                { reverbScope.launch { reverbStore?.setBassDspEnabled(it) } },
                descOn = R.string.fx_bass_dsp_on,
                descOff = R.string.fx_bass_dsp_off,
            )
            // [1.4.21·P2] 软件音量平滑接力+读回
            SettingsSwitchDialogRow(
                R.string.fx_smooth_volume,
                s.smoothVolumeHandoffEnabled,
                RowShape.Bottom,
                { reverbScope.launch { reverbStore?.setSmoothVolumeHandoffEnabled(it) } },
                descOn = R.string.fx_smooth_volume_on,
                descOff = R.string.fx_smooth_volume_off,
            )
        }
        SettingsGap()

        // [1.4.23·弹窗改造] 四项数值输入弹窗，复用 EQ dB 弹窗同款 SmartisanModal 结构。
        val fxEditing = editingFx
        if (fxEditing != null) {
            val fxFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            SmartisanModal(
                { editingFx = null },
                Modifier.widthIn(max = dimensionResource(R.dimen.revone_global_dialog_content_width))
                    .fillMaxWidth(),
            ) {
                // [v19·键盘修正] 请求焦点显示光标（停在末尾）后立刻 hide 输入法——
                // 只开光标不弹键盘；用户主动点输入框时才弹（联网核对的标准做法）。
                val keyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    delay(250)
                    fxFocus.requestFocus()
                    keyboardController?.hide()
                }
                Column(
                    Modifier.fillMaxWidth()
                        .cardTextureMonet(R.drawable.revone_global_dialog_shape_background)
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            fxTitle,
                            style = TextStyle(
                                color = colorResource(R.color.title_color),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .cardTextureMonet(R.drawable.revone_global_dialog_message_background)
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(44.dp)
                                .smartisanPainterBackground(
                                    rememberSmartisanDrawablePainter(R.drawable.edit_text_bg)
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val fxField = rememberCursorEndField(fxInput) { fxInput = it }
                            BasicTextField(
                                fxField.first,
                                fxField.second,
                                Modifier.weight(1f)
                                    .padding(horizontal = 12.dp)
                                    .focusRequester(fxFocus),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = colorResource(R.color.editor_text_color),
                                    fontSize = 15.sp,
                                ),
                                cursorBrush = SolidColor(colorResource(R.color.editor_text_color)),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = {
                                    applyFxValue(fxEditing, fxInput, reverbStore, reverbScope)
                                    editingFx = null
                                }),
                                decorationBox = { field ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (fxInput.isEmpty())
                                            BasicText(
                                                fxRangeHint,
                                                style = settingsTextStyle(summary = true),
                                            )
                                        field()
                                    }
                                },
                            )
                        }
                        BasicText(
                            "输入范围：$fxRangeHint",
                            Modifier.padding(top = 8.dp),
                            style = settingsTextStyle(summary = true).copy(fontSize = 12.sp),
                        )
                    }
                    SmartisanDialogButton(
                        stringResource(R.string.done),
                        {
                            applyFxValue(fxEditing, fxInput, reverbStore, reverbScope)
                            editingFx = null
                        },
                        Modifier.fillMaxWidth()
                            .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                        backgroundRes = R.drawable.revone_dialog_button_bg_selector,
                        textColorRes = R.color.blue_btn_text_color_selector,
                    )
                }
            }
        }
    }
}

/** [1.4.23·弹窗改造] 把输入串解析、钳制到对应范围并写回 store。 */
private fun applyFxValue(
    field: Int,
    input: String,
    store: com.smartisan.music.data.settings.PlaybackSettingsStore?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (store == null) return
    val v = input.trim().toIntOrNull() ?: return
    scope.launch {
        when (field) {
            0 -> store.setCompressorStrength(v.coerceIn(0, 100))
            1 -> store.setChannelBalance(v.coerceIn(-100, 100))
            2 -> store.setReplayGainGainDb(v.coerceIn(-6, 6))
            3 -> store.setPlaybackSpeedX100(v.coerceIn(50, 200))
        }
    }
}

/** [1.4.18·均衡器] 预设选中态的单选圆点，复用系统现成 selector 资源。 */
@Composable
private fun EqRadio(selected: Boolean, enabled: Boolean, pressed: Boolean) {
    Box(
        Modifier.padding(end = 6.dp)
            .size(28.dp)
            .alpha(if (enabled) 1f else 0.62f),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Image(
                rememberSmartisanDrawablePainter(
                    R.drawable.selector_radio_choice,
                    enabled = enabled,
                    pressed = pressed,
                ),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside,
            )
        }
    }
}

private fun reverbModeLabelRes(mode: AudioFxReverbMode): Int = when (mode) {
    AudioFxReverbMode.FollowPreset -> R.string.audio_fx_reverb_follow
    AudioFxReverbMode.Off -> R.string.audio_fx_reverb_off
    AudioFxReverbMode.Weak -> R.string.audio_fx_reverb_weak
    AudioFxReverbMode.Medium -> R.string.audio_fx_reverb_medium
    AudioFxReverbMode.Strong -> R.string.audio_fx_reverb_strong
}

private fun reverbModeSummaryRes(mode: AudioFxReverbMode): Int = when (mode) {
    AudioFxReverbMode.FollowPreset -> R.string.audio_fx_reverb_follow_summary
    AudioFxReverbMode.Off -> R.string.audio_fx_reverb_off_summary
    AudioFxReverbMode.Weak -> R.string.audio_fx_reverb_weak_summary
    AudioFxReverbMode.Medium -> R.string.audio_fx_reverb_medium_summary
    AudioFxReverbMode.Strong -> R.string.audio_fx_reverb_strong_summary
}

@Composable
internal fun MemoryPage(
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context.applicationContext) {
        com.smartisan.music.data.memory.ListeningMemoryStore(context.applicationContext)
    }
    val memory by store.memory.collectAsState(
        initial = com.smartisan.music.data.memory.ListeningMemory()
    )
    SettingsScaffold(active, stringResource(R.string.memory), onClose, modifier) {
        if (memory.totalPlays <= 0L) {
            SettingsSection(R.string.memory) {
                MemoryRow(
                    stringResource(R.string.memory_total_plays),
                    stringResource(R.string.memory_empty),
                    RowShape.Single,
                )
            }
        } else {
            val insight = memory.insight()
            SettingsSection(R.string.memory_total_plays) {
                MemoryRow(
                    stringResource(R.string.memory_total_plays),
                    context.getString(
                        R.string.memory_total_plays_value,
                        insight.totalPlays.toInt(),
                        com.smartisan.music.data.memory.formatListenedDuration(
                            insight.totalPlayedMs
                        ),
                    ),
                    RowShape.Top,
                )
                if (insight.favouriteHourStart >= 0) {
                    MemoryRow(
                        stringResource(R.string.memory_active_hours),
                        context.getString(
                            R.string.memory_hour_range,
                            insight.favouriteHourStart,
                            insight.favouriteHourEnd,
                        ),
                        RowShape.Middle,
                    )
                }
                MemoryRow(
                    stringResource(R.string.memory_hi_res_ratio),
                    "${insight.highResolutionPercent}%",
                    RowShape.Bottom,
                )
            }
            if (insight.topArtists.isNotEmpty()) {
                SettingsSection(R.string.memory_top_artist) {
                    insight.topArtists.forEachIndexed { index, entry ->
                        MemoryRow(
                            entry.first,
                            context.getString(R.string.memory_plays_suffix, entry.second),
                            RowShape.at(index, insight.topArtists.size),
                        )
                    }
                }
            }
            if (insight.topAlbums.isNotEmpty()) {
                SettingsSection(R.string.memory_top_album) {
                    insight.topAlbums.forEachIndexed { index, entry ->
                        MemoryRow(
                            entry.first,
                            context.getString(R.string.memory_plays_suffix, entry.second),
                            RowShape.at(index, insight.topAlbums.size),
                        )
                    }
                }
            }
            if (insight.favouriteTracks.isNotEmpty()) {
                SettingsSection(R.string.memory_recent) {
                    insight.favouriteTracks.forEachIndexed { index, track ->
                        MemoryRow(
                            track.title.ifBlank { track.mediaId },
                            context.getString(R.string.memory_plays_suffix, track.playCount),
                            RowShape.at(index, insight.favouriteTracks.size),
                        )
                    }
                }
            }
            SettingsGap()
            MemoryRow(
                stringResource(R.string.memory_clear),
                "",
                RowShape.Single,
                onClick = {
                    scope.launch {
                        store.clear()
                        android.widget.Toast.makeText(
                            context,
                            R.string.memory_cleared,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
        SettingsGap()
    }
}

@Composable
private fun MemoryRow(
    label: String,
    value: String,
    shape: RowShape,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .height(dimensionResource(R.dimen.list_item_min_height))
            .smartisanShadowBackground(shape.background, shape.shadow, true, false)
            .then(
                if (onClick != null)
                    Modifier.clickable(source, null, onClick = smartisanClick(onClick))
                else Modifier
            )
            .padding(
                start = dimensionResource(R.dimen.settings_row_content_margin_start),
                end = dimensionResource(R.dimen.settings_row_accessory_margin_end),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            Modifier.weight(1f).padding(end = 12.dp),
            style = settingsTextStyle(),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        if (value.isNotBlank()) {
            BasicText(
                value,
                style = settingsTextStyle(summary = true),
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun SettingsScaffold(
    active: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    complete: Boolean = false,
    // [1.4.23·EQ固定频响] 固定在顶部、不随下方选项滚动的卡片（如均衡器频响曲线）。
    // 渲染在滚动 Column 之外，因此上下滑时纹丝不动。默认空，不影响其它设置页。
    pinnedHeader: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().libraryTexture(pageDeep = true)) {
        if (active) {
            val close =
                SmartisanTitleBarAction(
                    if (complete) R.drawable.standard_icon_complete_selector
                    else R.drawable.standard_icon_back_selector,
                    stringResource(if (complete) R.string.done else R.string.back),
                    onClose,
                )
            SmartisanTitleBar(
                title,
                navigationIcon = if (complete) null else close,
                action = if (complete) close else null,
            )
            // 固定头部：置于滚动容器之外，背景与滚动区一致，保证视觉连续。
            Column(
                Modifier.fillMaxWidth()
                    .libraryTexture(pageDeep = true)
                    .padding(horizontal = dimensionResource(R.dimen.list_item_left_right_margin)),
                content = pinnedHeader,
            )
            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .libraryTexture(pageDeep = true)
                    .verticalScroll(scroll)
                    .padding(horizontal = dimensionResource(R.dimen.list_item_left_right_margin)),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsGap() {
    Spacer(Modifier.height(dimensionResource(R.dimen.list_item_vertical_gap)))
}

/**
 * [v21·设置卡片三角 / v24 形状回退] 标题右侧的实心三角：展开态尖朝下(▼)、收起态尖朝上(▲)。
 * 颜色用标题同款 summary 文字色、紧贴标题尾部。用 Canvas 画实心三角形，
 * [v24 返工] 宽高比对齐 v19 原三角真实比例 1.75:1（CaretAspect），与排序栏三角统一。
 */
@Composable
private fun ExpandTriangle(expanded: Boolean) {
    val tint = colorResource(R.color.setting_item_summary_text_color)
    val w = 9.dp
    // [v24 返工] 对齐 v19 原三角 1.75:1（原 1.65 是重画时的错误值）
    val h = w / com.smartisan.music.ui.theme.CaretAspect
    // [1.4.24·R22] 形状与 9dp 尺寸完全不变，只把绘制换成统一矢量组件：
    // 三个顶点倒圆 + 极细同色描边压实抗锯齿边缘，解决"密度不够/发虚"的观感。
    // 均衡器等二级页的卡片标题都走 SettingsSection → 这里，一处生效全局一致。
    com.smartisan.music.ui.components.SmartisanCaret(
        pointingDown = expanded,
        contentWidth = w,
        modifier = Modifier.size(w, h),
        color = tint,
    )
}

/**
 * [v19·设置卡片 / v20 返工] 带标题卡片统一入口。
 * - 用户要求：所有「有标题」的卡片都能展开/收起，默认展开，点标题行切换。
 * - [v20 间距返工] 用户指出"调相等不是设为0"：v19 删掉上方间距塌陷=0 是错的。
 *   现在标题上方留 7dp、标题行 bottom 7dp，二者相等且都非 0，标题整体上移。
 * - [v20 折叠记忆] 展开/收起状态按标题 key 落盘 SharedPreferences，下次打开保持。
 */
@Composable
private fun SettingsSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val key = remember(title) { context.resources.getResourceEntryName(title) }
    var expanded by rememberSaveable(key) {
        mutableStateOf(
            com.smartisan.music.data.settings.SettingsSectionExpandStore.get(context, key, true)
        )
    }
    // [v20 间距返工] 上方明确 7dp，与标题行 bottom 7dp 相等（都非 0）。
    Spacer(Modifier.height(7.dp))
    Row(
        Modifier.fillMaxWidth()
            .clickable {
                expanded = !expanded
                com.smartisan.music.data.settings.SettingsSectionExpandStore.set(context, key, expanded)
            }
            .padding(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(title),
            style = settingsTextStyle(summary = true).copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.width(5.dp))
        ExpandTriangle(expanded)
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * [1.4.23·长列表折叠] V4/AutoEQ 预设多达 20/59 项，全部摊开让用户"翻着难受"。
 * 默认折叠只显示一行标题 + 当前选中项摘要，点击展开；当前选中项属于本组时默认展开，
 * 避免用户选了却被折叠藏起来看不到高亮。
 * [v20·对齐返工] 标题行内边距与 SettingsSection 完全一致（上方 7dp、top 0、bottom 7dp），
 * 三角改为跟在标题文字后面（不再对齐两行整体居中，否则会飘到标题与摘要之间）。
 * [v20·折叠记忆] 与 SettingsSection 同一套落盘：用户点过就按用户的来，没点过才用
 * expandedByDefault（选中项在本组时自动展开）。
 */
@Composable
private fun CollapsibleSettingsSection(
    title: Int,
    summary: String,
    expandedByDefault: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val key = remember(title) { context.resources.getResourceEntryName(title) }
    var expanded by rememberSaveable(key) {
        mutableStateOf(
            com.smartisan.music.data.settings.SettingsSectionExpandStore.getOrNull(context, key)
                ?: expandedByDefault
        )
    }
    Spacer(Modifier.height(7.dp))
    Column(
        Modifier.fillMaxWidth()
            .clickable {
                expanded = !expanded
                com.smartisan.music.data.settings.SettingsSectionExpandStore.set(context, key, expanded)
            }
            .padding(start = 18.dp, end = 18.dp, top = 0.dp, bottom = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(title),
                style = settingsTextStyle(summary = true).copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(5.dp))
            ExpandTriangle(expanded)
        }
        if (!expanded && summary.isNotBlank()) {
            BasicText(summary, style = settingsTextStyle(summary = true))
        }
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsValueRow(
    title: Int,
    value: String,
    arrow: Boolean,
    shape: RowShape,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectSmartisanPressedAsState()
    val focused by source.collectIsFocusedAsState()
    SettingsRow(
        title,
        null,
        shape,
        true,
        source,
        Modifier.clickable(source, null, onClick = smartisanClick(onClick)),
    ) {
        BasicText(
            value,
            Modifier.padding(end = if (arrow) 2.dp else 10.dp),
            style =
                settingsTextStyle(summary = true)
                    .copy(
                        color =
                            smartisanStateColor(
                                R.color.blue_btn_text_color_selector,
                                pressed = pressed,
                                focused = focused,
                            )
                    ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (arrow)
            Image(
                rememberSmartisanDrawablePainter(
                    R.drawable.selector_list_content_item_arrow,
                    pressed = pressed,
                    focused = focused,
                ),
                null,
                contentScale = ContentScale.Inside,
            )
    }
}

@Composable
private fun SettingsChoiceRow(
    title: Int,
    value: String,
    summary: String?,
    shape: RowShape,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectSmartisanPressedAsState()
    val focused by source.collectIsFocusedAsState()
    SettingsRow(
        title,
        summary,
        shape,
        true,
        source,
        Modifier.clickable(source, null, onClick = smartisanClick(onClick)),
    ) {
        BasicText(
            value,
            Modifier.padding(end = 2.dp),
            style =
                settingsTextStyle(summary = true)
                    .copy(
                        color =
                            smartisanStateColor(
                                R.color.blue_btn_text_color_selector,
                                pressed = pressed,
                                focused = focused,
                            )
                    ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Image(
            rememberSmartisanDrawablePainter(
                R.drawable.selector_list_content_item_arrow,
                pressed = pressed,
                focused = focused,
            ),
            null,
            contentScale = ContentScale.Inside,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: Int,
    checked: Boolean,
    shape: RowShape,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    lockedSummary: String? = null,
    summary: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val switchState = rememberSmartisanSwitchState(checked, enabled, onChange)
    SettingsRow(
        title,
        summary,
        shape,
        enabled || lockedSummary != null,
        source,
        Modifier.toggleable(
            checked,
            source,
            null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { switchState.toggle() },
        ),
        titleAlpha = if (enabled || lockedSummary != null) 1f else 0.55f,
    ) {
        if (lockedSummary != null)
            BasicText(
                lockedSummary,
                // [1.4.24·R34] 置灰原因也走统一跑马灯：超长时滚动而不是硬切半个字。
                Modifier.padding(end = 2.dp).smartisanMarquee(),
                style = settingsTextStyle(summary = true),
                maxLines = 1,
                softWrap = false,
            )
        SmartisanSwitch(checked, onChange, Modifier.clearAndSetSemantics {}, enabled, switchState)
    }
}

/**
 * 带弹窗说明的开关行（1.2.3）：小字描述太长会被裁剪，观感差，
 * 所以行内不再放描述；切换开关时弹出与 App 风格一致的说明弹窗，
 * 显示"开启后 / 关闭后"的行为描述。
 */
@Composable
private fun SettingsSwitchDialogRow(
    title: Int,
    checked: Boolean,
    shape: RowShape,
    onChange: (Boolean) -> Unit,
    descOn: Int,
    descOff: Int,
    summary: String? = null,
    // [1.4.9·G-50] 支持按 flavor 置灰（A 版无 UAC 会话，独占性能优先开关是空转假开关）。
    enabled: Boolean = true,
    lockedSummary: String? = null,
) {
    var showDesc by remember { mutableStateOf(false) }
    var lastEnabled by remember { mutableStateOf(checked) }
    SettingsSwitchRow(
        title,
        checked,
        shape,
        { enabled ->
            lastEnabled = enabled
            onChange(enabled)
            showDesc = true
        },
        enabled = enabled,
        lockedSummary = lockedSummary,
        summary = summary,
    )
    if (showDesc) {
        SettingsDescDialog(
            title = stringResource(title),
            text = stringResource(if (lastEnabled) descOn else descOff),
            onDismiss = { showDesc = false },
        )
    }
}

/** [v15] 新引擎开启风险提示弹窗：确认/取消双按钮。 */
@Composable
private fun NewEngineRiskDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .monetContentBackground(colorResource(R.color.surface_card))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            BasicText(
                stringResource(R.string.usb_new_engine_risk_title),
                style = settingsTextStyle().copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                stringResource(R.string.usb_new_engine_risk_message),
                Modifier.fillMaxWidth(),
                style = settingsTextStyle(summary = true).copy(lineHeight = 21.sp),
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    stringResource(R.string.usb_new_engine_risk_cancel),
                    Modifier
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorResource(R.color.setting_item_text_colorlist),
                    ),
                )
                BasicText(
                    stringResource(R.string.usb_new_engine_risk_confirm),
                    Modifier
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = themedAccent(colorResource(R.color.btn_text_color_red)),
                    ),
                )
            }
        }
    }
}

/** 设置项说明弹窗：白色圆角卡片、标题加正文、右下角红色确认（与整体风格统一） */
@Composable
private fun SettingsDescDialog(title: String, text: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .monetContentBackground(colorResource(R.color.surface_card))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            BasicText(
                title,
                style = settingsTextStyle().copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text,
                Modifier.fillMaxWidth(),
                style = settingsTextStyle(summary = true).copy(lineHeight = 21.sp),
            )
            Spacer(Modifier.height(18.dp))
            BasicText(
                stringResource(R.string.settings_desc_dialog_button),
                Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = themedAccent(colorResource(R.color.btn_text_color_red)),
                ),
            )
        }
    }
}

/**
 * [v22·音量] USB 独占音量模式选择弹窗：自动 / 数字音量 / DAC 硬件音量。
 * 每个选项带一句"它到底改了什么、对 bit-perfect 有没有影响"，避免用户只知道名字。
 */
@Composable
private fun UsbVolumeModeDialog(
    current: com.smartisan.music.playback.UsbVolumeMode,
    hardwareSupported: Boolean,
    zeroLoss: Boolean,
    onSelect: (com.smartisan.music.playback.UsbVolumeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .monetContentBackground(colorResource(R.color.surface_card))
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            BasicText(
                stringResource(R.string.usb_volume_mode),
                style = settingsTextStyle().copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
            )
            Spacer(Modifier.height(6.dp))
            VolumeModeOption(
                R.string.usb_volume_mode_auto,
                R.string.usb_volume_mode_desc_auto,
                current == com.smartisan.music.playback.UsbVolumeMode.AUTO,
            ) { onSelect(com.smartisan.music.playback.UsbVolumeMode.AUTO) }
            VolumeModeOption(
                R.string.usb_volume_mode_digital,
                R.string.usb_volume_mode_desc_digital,
                current == com.smartisan.music.playback.UsbVolumeMode.DIGITAL,
            ) { onSelect(com.smartisan.music.playback.UsbVolumeMode.DIGITAL) }
            VolumeModeOption(
                R.string.usb_volume_mode_dac,
                R.string.usb_volume_mode_desc_hardware,
                current == com.smartisan.music.playback.UsbVolumeMode.HARDWARE,
            ) { onSelect(com.smartisan.music.playback.UsbVolumeMode.HARDWARE) }
            // [1.4.26·R79-c] 选了「DAC 硬件音量」但本机 DAC 不支持时，UsbVolume 会静默
            // 回退数字音量（HARDWARE -> DIGITAL）。旧版 UI 不给任何提示，用户以为在用
            // 硬件音量，实际走的是软件音量。这里把真实情况直接写在选项下面。
            if (current == com.smartisan.music.playback.UsbVolumeMode.HARDWARE && !hardwareSupported) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    stringResource(R.string.usb_volume_mode_hw_unsupported),
                    Modifier.fillMaxWidth(),
                    style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
                )
            }
            if (zeroLoss) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    stringResource(
                        if (hardwareSupported) R.string.usb_volume_mode_zero_loss_forced
                        else R.string.usb_volume_mode_zero_loss_no_hw
                    ),
                    Modifier.fillMaxWidth(),
                    style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
                )
            }
            Spacer(Modifier.height(14.dp))
            BasicText(
                stringResource(R.string.settings_desc_dialog_button),
                Modifier
                    .align(Alignment.End)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = themedAccent(colorResource(R.color.btn_text_color_red)),
                ),
            )
        }
    }
}

@Composable
private fun VolumeModeOption(
    title: Int,
    desc: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                stringResource(title),
                style =
                    settingsTextStyle().copy(
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    ),
            )
            BasicText(
                stringResource(desc),
                Modifier.fillMaxWidth(),
                style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
            )
        }
        if (selected) {
            BasicText(
                "✓",
                Modifier.padding(start = 10.dp, top = 1.dp),
                style =
                    settingsTextStyle().copy(
                        fontWeight = FontWeight.Bold,
                        color = themedAccent(colorResource(R.color.btn_text_color_red)),
                    ),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: Int,
    summary: String?,
    shape: RowShape,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    titleAlpha: Float = if (enabled) 1f else 0.62f,
    titleAccessoryGap: Dp? = null,
    /** [1.4.16·E2] 动态标题（Salt EQ 预设名），非空时优先于 stringResource(title)。 */
    titleText: String? = null,
    accessory: @Composable RowScope.() -> Unit,
) {
    val pressed by source.collectSmartisanPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val localeDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.list_item_min_height))
                .smartisanShadowBackground(
                    shape.background,
                    shape.shadow,
                    enabled,
                    pressed,
                    focused = focused,
                )
                .then(modifier)
                .padding(
                    start = dimensionResource(R.dimen.settings_row_content_margin_start),
                    end = dimensionResource(R.dimen.settings_row_accessory_margin_end),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f)
                    .padding(
                        end =
                            titleAccessoryGap
                                ?: dimensionResource(R.dimen.settings_row_title_accessory_gap)
                    )
                    .alpha(titleAlpha)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides localeDirection) {
                    BasicText(
                        titleText ?: stringResource(title),
                        Modifier.fillMaxWidth(),
                        style =
                            settingsTextStyle()
                                .copy(
                                    color =
                                        smartisanStateColor(
                                            R.color.setting_item_text_colorlist,
                                            enabled,
                                            pressed,
                                            focused = focused,
                                        )
                                ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (summary != null)
                        BasicText(
                            summary,
                            // [1.4.24·R34] 改用全 App 统一的跑马灯参数（见 Marquee.kt），
                            // 与播放栏标题/副标题保持完全一致的速度与停顿。
                            Modifier.fillMaxWidth().smartisanMarquee(),
                            style =
                                settingsTextStyle(summary = true)
                                    .copy(
                                        color =
                                            smartisanStateColor(
                                                R.color.setting_item_summary_text_colorlist,
                                                enabled,
                                                pressed,
                                                focused = focused,
                                            )
                                    ),
                            maxLines = 1,
                            softWrap = false,
                        )
                }
            }
            accessory()
        }
    }
}

@Composable
private fun settingsTextStyle(summary: Boolean = false) =
    TextStyle(
        fontSize =
            smartisanTextSize(
                if (summary) R.dimen.settings_item_tips_text_size else R.dimen.primary_text_size
            ),
        color =
            colorResource(
                if (summary) R.color.setting_item_summary_text_color
                else R.color.setting_item_text_color
            ),
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

private enum class RowShape(val background: Int, val shadow: Int) {
    Single(R.drawable.group_list_item_bg_single, R.drawable.list_content_item_single_shadow),
    Top(R.drawable.group_list_item_bg_top, R.drawable.list_content_item_top_shadow),
    Middle(R.drawable.group_list_item_bg_mid, R.drawable.list_content_item_middle_shadow),
    Bottom(R.drawable.group_list_item_bg_bottom, R.drawable.list_content_item_bottom_shadow);

    companion object {
        fun at(index: Int, count: Int) =
            when {
                count == 1 -> Single
                index == 0 -> Top
                index == count - 1 -> Bottom
                else -> Middle
            }
    }
}

@Composable
private fun AudioFxPreset.labelText(): String =
    when (this) {
        AudioFxPreset.Original -> stringResource(R.string.audio_fx_original)
        AudioFxPreset.Bass -> stringResource(R.string.audio_fx_bass)
        AudioFxPreset.Clear -> stringResource(R.string.audio_fx_clear)
        AudioFxPreset.Vocal -> stringResource(R.string.audio_fx_vocal)
        AudioFxPreset.Rock -> stringResource(R.string.audio_fx_rock)
        AudioFxPreset.Custom -> stringResource(R.string.audio_fx_custom)
        else -> saltKey?.let { com.smartisan.music.data.settings.SaltEqPresets.chineseNames[it] }
            ?: preferenceValue
    }

@Composable
private fun AudioFxPreset.summaryText(): String =
    when (this) {
        AudioFxPreset.Original -> stringResource(R.string.audio_fx_original_summary)
        AudioFxPreset.Bass -> stringResource(R.string.audio_fx_bass_summary)
        AudioFxPreset.Clear -> stringResource(R.string.audio_fx_clear_summary)
        AudioFxPreset.Vocal -> stringResource(R.string.audio_fx_vocal_summary)
        AudioFxPreset.Rock -> stringResource(R.string.audio_fx_rock_summary)
        AudioFxPreset.Custom -> stringResource(R.string.audio_fx_custom_summary)
        else -> stringResource(R.string.audio_fx_salt_summary)
    }

@Composable
private fun AudioFxCurve(
    preset: AudioFxPreset,
    enabled: Boolean,
    customGains: List<Float>,
    onChange: (List<Float>) -> Unit,
    modifier: Modifier,
    eqPresetKey: String = "",
) {
    // [1.4.6·10段EQ] 10 个控制点，横轴显示标准 10 段频率数字。
    val labels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val bandLast = AudioFxBandCount - 1  // 9
    // [1.4.22·修频响预览] V4/AutoEQ 耳机预设用的是任意频率的 biquad bands，
    // 与内置 10 段频点不重合。按对数频率把每个频点附近 band 的增益做加权平均，
    // 映射到 10 个标准频点上，让选中耳机预设时曲线真实反映其形状（不再纹丝不动）。
    fun bandsToCurve(key: String): List<Float>? {
        if (key.isBlank()) return null
        // [1.4.23·修假空壳] 与 eqSummary 一致：直接按 key 查三个数据源。
        // 旧的前缀判断让 V4 预设（key 无 v4_ 前缀）查不到→曲线全平→"预设没用"错觉。
        val bands = (com.smartisan.music.data.settings.SaltEqPresets.bands[key]
            ?: com.smartisan.music.data.settings.SaltEqCatalog.v4Bands[key]
            ?: com.smartisan.music.data.settings.SaltEqCatalog.autoEqBands[key]) ?: return null
        if (bands.isEmpty()) return null
        return AudioFxControlFrequenciesHz.mapIndexed { idx, fHz ->
            val logF = kotlin.math.ln(fHz.toDouble())
            var wsum = 0.0
            var gsum = 0.0
            bands.forEach { b ->
                // 对数域距离，sigma 取 0.6 个倍频程（ln2*0.6≈0.416）。
                val d = (kotlin.math.ln(b.frequencyHz.toDouble().coerceAtLeast(1.0)) - logF) / 0.416
                val w = kotlin.math.exp(-d * d)
                wsum += w
                gsum += w * b.gainDb
            }
            val g = if (wsum > 0.0) (gsum / wsum).toFloat() else 0f
            g.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb)
        }
    }
    val measurer = rememberTextMeasurer()
    val labelStyle =
        TextStyle(
            color = colorResource(R.color.setting_item_summary_text_color),
            fontSize = 9.5.sp,
        )
    val labelLayouts = labels.map { measurer.measure(it, labelStyle) }
    // 纵轴 dB 刻度文字（+12 / +6 / 0 / -6 / -12），画在每条水平网格线左端。
    val dbLabels = listOf("+12", "+6", "0", "-6", "-12")
    val dbLabelLayouts = dbLabels.map { measurer.measure(it, labelStyle) }
    val gridColor = colorResource(R.color.input_border)
    val handleBorder = colorResource(R.color.surface_card)
    val curveColor = if (enabled) themedAccent(Color(0xffdb3b3b)) else Color(0xffa6a6a6)
    var values by
        remember(preset, customGains, eqPresetKey) {
            mutableStateOf(
                bandsToCurve(eqPresetKey)
                    // [1.4.23·修假空壳] 内置 Salt 预设的 eqPresetKey 为空，旧代码直接回退到
                    // equalizerGainDbPoints()——它对 Salt 预设返回全 0，于是 29 个 Salt 预设
                    // 曲线纹丝不动。这里优先按 preset.saltKey 查真实参数化 band。
                    ?: preset.saltKey?.let { bandsToCurve(it) }
                    ?: if (preset == AudioFxPreset.Custom) normalizeAudioFxGainDbPoints(customGains)
                    else preset.equalizerGainDbPoints().toList()
            )
        }
    val currentValues by rememberUpdatedState(values)
    val callback by rememberUpdatedState(onChange)
    val editable = enabled && preset == AudioFxPreset.Custom
    val maxLabelWidth = labelLayouts.maxOf { it.size.width }.toFloat()
    // 纵轴刻度让出的左边距（原来只按频率标签半宽算，现在把 dB 文字宽度也算进来）。
    val maxDbLabelWidth = dbLabelLayouts.maxOf { it.size.width }.toFloat()
    val density = LocalDensity.current
    val yAxisInset = with(density) { maxDbLabelWidth + 4.dp.toPx() }
    // [1.4.6] 正在编辑的频段索引：非 null 时弹出「输入该点 dB」弹窗。
    var editingBand by remember { mutableStateOf<Int?>(null) }
    var inputDb by rememberSaveable { mutableStateOf("") }

    fun applyGain(index: Int, gain: Float) {
        val snapped = (gain.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) * 2)
            .roundToInt() / 2f
        values = currentValues.toMutableList().also { it[index] = snapped }
        callback(values)
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(editable, maxLabelWidth, yAxisInset) {
                if (!editable) return@pointerInput
                val insetLeft = yAxisInset
                val insetRight = maxOf(maxLabelWidth / 2f, 5.6.dp.toPx()) + 2.dp.toPx()
                val top = 4.dp.toPx()
                val bottom = size.height - 24.dp.toPx()
                val graphHeight = bottom - top
                val left = insetLeft
                val right = size.width - insetRight
                if (graphHeight <= 0 || right <= left) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = 22.dp.toPx()
                    if (
                        down.position.x !in (left - slop)..(right + slop) ||
                            down.position.y !in (top - slop)..(bottom + slop)
                    )
                        return@awaitEachGesture
                    down.consume()
                    val band =
                        (((down.position.x - left) / (right - left)) * bandLast)
                            .roundToInt()
                            .coerceIn(0, bandLast)
                    fun update(y: Float) {
                        val raw = ((top + bottom) / 2f - y) / (graphHeight / 2f) * AudioFxMaxGainDb
                        val gain =
                            (raw.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) * 2).roundToInt() / 2f
                        if (gain != currentValues[band]) {
                            values = currentValues.toMutableList().also { it[band] = gain }
                            callback(values)
                        }
                    }
                    update(down.position.y)
                    do {
                        val change =
                            awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        update(change.position.y)
                        change.consume()
                    } while (change.pressed)
                }
            }
        ) {
            val insetRight = maxOf(maxLabelWidth / 2f, 5.6.dp.toPx()) + 2.dp.toPx()
            val left = yAxisInset
            val right = size.width - insetRight
            val top = 4.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas
            // 竖直网格线 + 横轴频率数字
            labels.indices.forEach { index ->
                val x = left + (right - left) * index / bandLast
                drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                val label = labelLayouts[index]
                drawText(
                    label,
                    topLeft =
                        Offset(
                            (x - label.size.width / 2f).coerceAtLeast(0f),
                            size.height - 7.dp.toPx() - label.firstBaseline,
                        ),
                )
            }
            // 水平网格线 + 纵轴 dB 刻度（每条网格线旁标一个 dB 值）
            (0..4).forEach { i ->
                val y = top + (bottom - top) * i / 4
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
                val dbLabel = dbLabelLayouts[i]
                drawText(
                    dbLabel,
                    topLeft = Offset(
                        (left - dbLabel.size.width - 2.dp.toPx()).coerceAtLeast(0f),
                        (y - dbLabel.size.height / 2f).coerceAtLeast(0f),
                    ),
                )
            }
            drawLine(
                Color(0xffd8d8d8),
                Offset(left, (top + bottom) / 2),
                Offset(right, (top + bottom) / 2),
                1.2.dp.toPx(),
            )
            val points = values.mapIndexed { index, gain ->
                Offset(
                    left + (right - left) * index / bandLast,
                    (top + bottom) / 2 -
                        gain.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) / AudioFxMaxGainDb *
                            (bottom - top) / 2,
                )
            }
            val path =
                Path().apply {
                    points.forEachIndexed { i, point ->
                        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
            drawPath(
                path,
                curveColor,
                style =
                    Stroke(
                        2.1.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
            )
            if (preset == AudioFxPreset.Custom)
                points.forEach { point ->
                    drawCircle(curveColor, 5.6.dp.toPx(), point)
                    drawCircle(handleBorder, 5.6.dp.toPx(), point, style = Stroke(1.4.dp.toPx()))
                }
        }
        // [1.4.6] 横轴频率数字的可点击热区：10 等分，点任意一个弹「输入该点 dB」。
        // 与拖动并存（拖动在 Canvas 上、点击热区只在底部 24dp 标签带），互不冲突。
        if (editable)
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                labels.forEachIndexed { index, label ->
                    Spacer(
                        Modifier.weight(1f).fillMaxHeight().semantics {
                            contentDescription = "$label Hz"
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    values[index],
                                    AudioFxMinGainDb..AudioFxMaxGainDb,
                                )
                            setProgress { value ->
                                applyGain(index, value)
                                true
                            }
                        }
                    )
                }
            }
        // 底部标签带：10 个可点频率数字（覆盖在 Canvas 绘制的数字之上，专供点击输入）
        if (editable)
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = 2.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    Box(
                        Modifier.weight(1f).height(22.dp)
                            .clickable {
                                editingBand = index
                                inputDb = currentValues[index].toString()
                            },
                        contentAlignment = Alignment.Center,
                    ) { /* 透明命中区，文字由 Canvas 绘制，避免重复 */ }
                }
            }
    }

    // [1.4.6] 点频率数字弹出的 dB 输入弹窗（复用 SmartisanModal + BasicTextField）。
    val band = editingBand
    if (band != null) {
        val bi: Int = band  // 委托属性的智能转换在 lambda 里不稳，先固化成局部 Int
        val focus = remember { FocusRequester() }
        SmartisanModal(
            { editingBand = null },
            Modifier.widthIn(max = dimensionResource(R.dimen.revone_global_dialog_content_width))
                .fillMaxWidth(),
        ) {
            // [v19·键盘修正] 请求焦点显示光标后立刻 hide 输入法，只开光标不弹键盘。
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                delay(250)
                focus.requestFocus()
                keyboardController?.hide()
            }
            Column(
                Modifier.fillMaxWidth()
                    .cardTextureMonet(R.drawable.revone_global_dialog_shape_background)
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        // 例如「第 3 段 · 125 Hz」
                        "第 ${bi + 1} 段 · ${labels[bi]} Hz",
                        style = TextStyle(
                            color = colorResource(R.color.title_color),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                Column(
                    Modifier.fillMaxWidth()
                        .cardTextureMonet(R.drawable.revone_global_dialog_message_background)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp)
                            .smartisanPainterBackground(
                                rememberSmartisanDrawablePainter(R.drawable.edit_text_bg)
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dbField = rememberCursorEndField(inputDb) { inputDb = it }
                        BasicTextField(
                            dbField.first,
                            dbField.second,
                            Modifier.weight(1f)
                                .padding(horizontal = 12.dp)
                                .focusRequester(focus),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = colorResource(R.color.editor_text_color),
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(colorResource(R.color.editor_text_color)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val v = inputDb.toFloatOrNull() ?: 0f
                                applyGain(bi, v)
                                editingBand = null
                            }),
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (inputDb.isEmpty())
                                        BasicText(
                                            "-12 ~ +12 dB",
                                            style = settingsTextStyle(summary = true),
                                        )
                                    field()
                                }
                            },
                        )
                    }
                    BasicText(
                        "可直接输入该频段的增益（-12 ~ +12 dB，按 0.5 取整）",
                        Modifier.padding(top = 8.dp),
                        style = settingsTextStyle(summary = true).copy(fontSize = 12.sp),
                    )
                }
                SmartisanDialogButton(
                    stringResource(R.string.done),
                    {
                        val v = inputDb.toFloatOrNull() ?: 0f
                        applyGain(bi, v)
                        editingBand = null
                    },
                    Modifier.fillMaxWidth()
                        .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                    backgroundRes = R.drawable.revone_dialog_button_bg_selector,
                    textColorRes = R.color.blue_btn_text_color_selector,
                )
            }
        }
    }
}

@Composable
internal fun ArtistSeparatorsDialog(
    initialSeparators: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var separators by remember(initialSeparators) { mutableStateOf(initialSeparators) }
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun addInput(): Set<String> {
        val parsed = parseArtistSeparatorInput(input)
        if (parsed.isNotEmpty()) {
            separators = separators + parsed
            input = ""
        }
        return separators
    }
    SmartisanModal(
        onDismiss,
        Modifier.widthIn(max = dimensionResource(R.dimen.revone_global_dialog_content_width))
            .fillMaxWidth(),
    ) {
        // [v19·键盘修正] 请求焦点显示光标后立刻 hide 输入法，只开光标不弹键盘。
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(300)
            focus.requestFocus()
            keyboardController?.hide()
        }
        Column(
            Modifier.fillMaxWidth()
                .cardTextureMonet(R.drawable.revone_global_dialog_shape_background)
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                contentAlignment = Alignment.Center,
            ) {
                // [1.4.9·G-49] 弹窗样式统一 token：标题 17 半粗，用主题色资源。
                BasicText(
                    stringResource(R.string.artist_separators),
                    style =
                        TextStyle(
                            color = colorResource(R.color.title_color),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    .cardTextureMonet(R.drawable.revone_global_dialog_message_background)
                    .padding(horizontal = 18.dp)
            ) {
                // [护栏 G-34·1.4.7 落地] 卡片外孤行的用途说明并进点开的现成弹窗，不再单独挂卡片外。
                BasicText(
                    stringResource(R.string.artist_split_rules_summary),
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    style = settingsTextStyle(summary = true).copy(lineHeight = 19.sp),
                )
                // [1.4.10·G-57] 「可同时使用多个分隔符」与上一行说明同左对齐、同字号同色，
                // 去掉 1.4.9 的居中 + textAlign.Center（视觉断裂、字号也不一致）。
                BasicText(
                    stringResource(R.string.artist_separators_hint),
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = settingsTextStyle(summary = true).copy(fontSize = 13.sp, lineHeight = 19.sp),
                )
                Row(
                    Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (separators.isEmpty())
                        BasicText(
                            stringResource(R.string.not_set),
                            style = settingsTextStyle(summary = true).copy(fontSize = 15.sp),
                        )
                    separators.sorted().forEach { separator ->
                        Box(
                            Modifier.padding(end = 8.dp)
                                .height(36.dp)
                                .background(
                                    colorResource(R.color.surface_raised),
                                    RoundedCornerShape(5.dp),
                                )
                                .border(
                                    1.dp,
                                    colorResource(R.color.input_border),
                                    RoundedCornerShape(5.dp),
                                )
                                .clickable { separators = separators - separator }
                                .padding(start = 16.dp, end = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // [1.4.10·G-57] 删除键从字符串「×」改为带圈小图标按钮：
                            // 1.4.9 用 "$separator ×" 字面量，当分隔符本身是「×」时变成「× ×」，
                            // 两个乘号分不清哪个是删除键。带圈图标与输入框的删除图标同款，
                            // 一眼可辨「这是按钮不是字符」。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    separator,
                                    style =
                                        settingsTextStyle(summary = true)
                                            .copy(fontSize = 15.sp),
                                )
                                Image(
                                    rememberSmartisanDrawablePainter(R.drawable.quick_icon_delete),
                                    stringResource(R.string.delete),
                                    Modifier.size(18.dp).padding(start = 4.dp),
                                    contentScale = ContentScale.Inside,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f)
                            .height(40.dp)
                            .smartisanPainterBackground(
                                rememberSmartisanDrawablePainter(R.drawable.edit_text_bg)
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sepField = rememberCursorEndField(input) { input = it }
                        BasicTextField(
                            sepField.first,
                            sepField.second,
                            Modifier.weight(1f)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .focusRequester(focus),
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    color = colorResource(R.color.editor_text_color),
                                    fontSize = 15.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                                ),
                            cursorBrush = SolidColor(colorResource(R.color.editor_text_color)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addInput() }),
                            decorationBox = { field ->
                                Box {
                                    if (input.isEmpty())
                                        BasicText(
                                            stringResource(R.string.artist_custom_separator_hint),
                                            style =
                                                TextStyle(
                                                    color =
                                                        colorResource(
                                                            R.color.editor_hint_text_color
                                                        ),
                                                    fontSize = 15.sp,
                                                    platformStyle =
                                                        PlatformTextStyle(
                                                            includeFontPadding = true
                                                        ),
                                                ),
                                        )
                                    field()
                                }
                            },
                        )
                        Image(
                            rememberSmartisanDrawablePainter(R.drawable.quick_icon_delete),
                            stringResource(R.string.delete),
                            Modifier.size(32.dp).clickable { input = "" },
                            contentScale = ContentScale.Inside,
                        )
                    }
                    Box(
                        Modifier.padding(start = 8.dp)
                            .size(64.dp, 40.dp)
                            .background(
                                colorResource(R.color.surface_raised_soft),
                                RoundedCornerShape(7.dp),
                            )
                            // [1.4.9·G-49] 去硬编码边框色，走资源跟随主题。
                            .border(1.dp, colorResource(R.color.input_border), RoundedCornerShape(7.dp))
                            .clickable { addInput() },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            stringResource(R.string.add),
                            style =
                                TextStyle(
                                    // [1.4.9·G-49] 按钮统一 15 主题色。
                                    color = themedInkRes(R.color.btn_text_color_blue),
                                    fontSize = 15.sp,
                                ),
                        )
                    }
                }
            }
            SmartisanDialogButton(
                stringResource(R.string.done),
                { onConfirm(addInput()) },
                Modifier.fillMaxWidth()
                    .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                backgroundRes = R.drawable.revone_dialog_button_bg_selector,
                textColorRes = R.color.blue_btn_text_color_selector,
            )
        }
    }
}

// 1.4.11 · S12 播放引擎选择页（放进本文件以复用 private SettingsRow 等组件）。
// 三套真实引擎已编进单包；引擎是播放器地基，切换需重建 Player，故提示「重启生效」。
@Composable
internal fun EngineSettingsPage(
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current by com.smartisan.music.data.settings.EngineModeStore.mode.collectAsState()
    val modes = com.smartisan.music.data.settings.EngineModeStore.Mode.entries
    SettingsScaffold(active, stringResource(R.string.playback_engine), onClose, modifier) {
        SettingsGap()
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            modes.forEachIndexed { index, mode ->
                val source = remember { MutableInteractionSource() }
                SettingsRow(
                    mode.labelRes,
                    stringResource(mode.summaryRes),
                    RowShape.at(index, modes.size),
                    true,
                    source,
                    Modifier.selectable(
                        selected = mode == current,
                        enabled = true,
                        role = Role.RadioButton,
                        onClick = {
                            if (mode != current) {
                                com.smartisan.music.data.settings.EngineModeStore.set(context, mode)
                            }
                        },
                    ),
                    titleAccessoryGap = 10.dp,
                ) {
                    val pressed by source.collectSmartisanPressedAsState()
                    Box(Modifier.padding(end = 6.dp).size(28.dp), contentAlignment = Alignment.Center) {
                        if (mode == current) {
                            Image(
                                rememberSmartisanDrawablePainter(
                                    R.drawable.selector_radio_choice,
                                    pressed = pressed,
                                ),
                                null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Inside,
                            )
                        }
                    }
                }
            }
        }
        SettingsGap()
        // [1.4.25·R67a] 切引擎恢复后是否自动播放，默认暂停（EngineModeStore）。
        val scope = rememberCoroutineScope()
        val restoreAutoPlay by
            com.smartisan.music.data.settings.EngineModeStore.restoreAutoPlay.collectAsState()
        SettingsSwitchDialogRow(
            R.string.engine_restore_autoplay,
            restoreAutoPlay,
            RowShape.Single,
            { enabled ->
                com.smartisan.music.data.settings.EngineModeStore.setRestoreAutoPlay(context, enabled)
            },
            descOn = R.string.engine_restore_autoplay_desc_on,
            descOff = R.string.engine_restore_autoplay_desc_off,
        )
        SettingsGap()
        // [1.4.17·引擎能力可见] 切换前一眼看清各引擎的能力差异与失效功能。
        // [1.4.25·R67] 原底部 engine_restart_note 那段「会中断当前播放」提示已删除——
        // R66 修好后切引擎会恢复原歌原进度、默认暂停待播，不再"中断"，那段提示失去意义。
        BasicText(
            stringResource(R.string.engine_capability_note),
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 4.dp),
            style = settingsTextStyle(summary = true).copy(lineHeight = 20.sp),
        )
        SettingsGap()
    }
}
