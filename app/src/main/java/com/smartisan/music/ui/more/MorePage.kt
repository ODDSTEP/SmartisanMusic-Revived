package com.smartisan.music.ui.more

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.AudioFxPreset
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.ui.navigation.LocalSmartisanTitleVisible
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.navigation.SmartisanFullScreenTransition
import com.smartisan.music.ui.settings.AudioFxSettingsPage
import com.smartisan.music.ui.settings.ColorThemeSettingsPage
import com.smartisan.music.ui.settings.EngineSettingsPage
import com.smartisan.music.ui.settings.SettingsPage
import com.smartisan.music.ui.settings.SettingsSecondaryPage
import com.smartisan.music.ui.settings.SettingsTitleStack
import com.smartisan.music.ui.settings.ThemeSettingsPage
import com.smartisan.music.ui.shell.PageStackTransition

/**
 * `More` 只负责两个职责：列出当前没有固定到底栏的同级目的地，以及承载设置页。 内容目的地由主壳统一渲染，避免在这里复制一套页面栈和状态所有权。
 *
 * [1.4.23·更多页归属重构] 均衡器/播放引擎/主题/色彩主题等入口现在是「更多页自己的二级页面」：
 * 点进去直接是对应设置页，返回直接回更多页根，不再先退回「设置」主页（消除「脱裤子放屁」的
 * 两次返回）。齿轮进入的完整「设置」主页保持原样，两条路径互不干扰。
 */
@Composable
internal fun MorePage(
    active: Boolean,
    overflowDestinations: List<MusicDestination>,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    onDestinationSelected: (MusicDestination) -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    onArtistSeparatorsChange: (Set<String>) -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSettingsPageActiveChanged: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 齿轮入口 → 完整「设置」主页（保持原样）
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsSecondaryPage by rememberSaveable { mutableStateOf<SettingsSecondaryPage?>(null) }
    var settingsMounted by remember { mutableStateOf(false) }
    // [1.4.23·更多页归属重构] 更多页自己的二级页面栈（与「设置」主页完全独立）
    var moreSubPage by rememberSaveable { mutableStateOf<SettingsSecondaryPage?>(null) }

    val latestSettingsActiveChanged by rememberUpdatedState(onSettingsPageActiveChanged)
    val anySubPageActive = moreSubPage != null
    LaunchedEffect(active, settingsVisible, settingsMounted, anySubPageActive) {
        latestSettingsActiveChanged(active && (settingsVisible || settingsMounted || anySubPageActive))
    }
    DisposableEffect(Unit) {
        onDispose { latestSettingsActiveChanged(false) }
    }

    // 返回键：优先关掉更多页自己的二级页 → 直接回更多页根。
    BackHandler(enabled = active && moreSubPage != null) { moreSubPage = null }

    Box(modifier = modifier.fillMaxSize()) {
        SmartisanMoreRootPage(
            active = active && moreSubPage == null,
            destinations = overflowDestinations,
            onDestinationSelected = onDestinationSelected,
            onSettingsClick = { settingsVisible = true },
            onSearchClick = onSearchClick,
            // [1.4.23·更多页归属重构] 入口直接打开「更多页自己的二级页面」，不走设置主页。
            onShortcutClick = { page -> moreSubPage = page },
            modifier = Modifier.fillMaxSize(),
        )

        // ===== 更多页自己的二级页面（均衡器/播放引擎/主题/色彩主题）=====
        SmartisanFullScreenTransition(visible = active && moreSubPage != null) {
            val titleHeight =
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    dimensionResource(R.dimen.title_bar_height)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(titleHeight).zIndex(1f)) {
                    MoreSubPageTitleStack(moreSubPage, onBack = { moreSubPage = null })
                }
                CompositionLocalProvider(LocalSmartisanTitleVisible provides false) {
                    PageStackTransition(
                        secondaryKey = moreSubPage,
                        modifier = Modifier.fillMaxSize(),
                        label = "more sub page stack",
                        primaryContent = { /* 根页已在下层渲染，这里仅占位 */ },
                        secondaryContent = { page ->
                            when (page) {
                                SettingsSecondaryPage.AudioFx ->
                                    AudioFxSettingsPage(
                                        active = active,
                                        playbackSettings = playbackSettings,
                                        onClose = { moreSubPage = null },
                                        onAudioFxEnabledChange = onAudioFxEnabledChange,
                                        onAudioFxPresetChange = onAudioFxPresetChange,
                                        onAudioFxCustomGainDbPointsChange = onAudioFxCustomGainDbPointsChange,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                SettingsSecondaryPage.Engine ->
                                    EngineSettingsPage(
                                        active = active,
                                        onClose = { moreSubPage = null },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                SettingsSecondaryPage.Theme ->
                                    ThemeSettingsPage(
                                        active = active,
                                        themeMode = themeMode,
                                        onClose = { moreSubPage = null },
                                        onThemeModeChange = onThemeModeChange,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                SettingsSecondaryPage.ColorTheme ->
                                    ColorThemeSettingsPage(
                                        active = active,
                                        onClose = { moreSubPage = null },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                // 下列页面不属于「更多」入口，理论上不会出现；保险起见回退到设置主页处理。
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }

        // ===== 齿轮入口 → 完整「设置」主页（保持原样）=====
        SmartisanFullScreenTransition(visible = active && settingsVisible) {
            DisposableEffect(Unit) {
                settingsMounted = true
                onDispose {
                    settingsMounted = false
                    settingsSecondaryPage = null
                }
            }
            val titleHeight =
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    dimensionResource(R.dimen.title_bar_height)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(titleHeight).zIndex(1f)) {
                    SettingsTitleStack(
                        settingsSecondaryPage,
                        onClose = { settingsVisible = false },
                        onBack = { settingsSecondaryPage = null },
                    )
                }
                CompositionLocalProvider(LocalSmartisanTitleVisible provides false) {
                    SettingsPage(
                        secondaryPage = settingsSecondaryPage,
                        onSecondaryPageChange = { settingsSecondaryPage = it },
                        active = active,
                        playbackSettings = playbackSettings,
                        artistSettings = artistSettings,
                        navigationSettings = navigationSettings,
                        themeMode = themeMode,
                        onClose = { settingsVisible = false },
                        onScratchEnabledChange = onScratchEnabledChange,
                        onHidePlayerAxisEnabledChange = onHidePlayerAxisEnabledChange,
                        onPopcornSoundEnabledChange = onPopcornSoundEnabledChange,
                        onAudioFxEnabledChange = onAudioFxEnabledChange,
                        onAudioFxPresetChange = onAudioFxPresetChange,
                        onAudioFxCustomGainDbPointsChange = onAudioFxCustomGainDbPointsChange,
                        onArtistSeparatorsChange = onArtistSeparatorsChange,
                        onTabPinnedChange = onTabPinnedChange,
                        onThemeModeChange = onThemeModeChange,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * [1.4.23·更多页归属重构] 更多页二级页的标题栏：一个返回箭头 + 对应标题。
 * 阴影规格完全沿用 [SettingsTitleStack]（同样的 title_bar_shadow_height / offset / drawable），
 * 不改变任何高度、位置、大小——只换「返回目标」为更多页根，而非设置主页。
 */
@Composable
private fun MoreSubPageTitleStack(
    page: SettingsSecondaryPage?,
    onBack: () -> Unit,
) {
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
    Box(Modifier.fillMaxSize()) {
        SmartisanTitleBar(
            title =
                stringResource(
                    when (page) {
                        SettingsSecondaryPage.AudioFx -> R.string.audio_fx
                        SettingsSecondaryPage.Engine -> R.string.playback_engine
                        SettingsSecondaryPage.Theme -> R.string.theme_settings
                        SettingsSecondaryPage.ColorTheme -> R.string.color_theme
                        else -> R.string.tab_more
                    }
                ),
            navigationIcon =
                SmartisanTitleBarAction(
                    R.drawable.standard_icon_back_selector,
                    stringResource(R.string.back),
                    onBack,
                ),
            showShadow = false,
        )
        TitleBarShadow(
            Modifier.align(Alignment.BottomCenter)
                .offset(y = shadowHeight)
                .fillMaxWidth()
                .height(shadowHeight)
        )
    }
}
