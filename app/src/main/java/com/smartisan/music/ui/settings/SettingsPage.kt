package com.smartisan.music.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import com.smartisan.music.R
import com.smartisan.music.data.settings.*
import com.smartisan.music.launcher.AppIcon
import com.smartisan.music.launcher.AppIconManager
import com.smartisan.music.ui.library.libraryTexture
import com.smartisan.music.ui.shell.PageStackTransition

@Composable
internal fun SettingsPage(
    active: Boolean,
    secondaryPage: SettingsSecondaryPage?,
    onSecondaryPageChange: (SettingsSecondaryPage?) -> Unit,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    onClose: () -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    onArtistSeparatorsChange: (Set<String>) -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingArtistSeparators by remember { mutableStateOf(false) }
    var artistSeparatorsInitialValues by remember { mutableStateOf(emptySet<String>()) }
    val latestOnArtistSeparatorsChange by rememberUpdatedState(onArtistSeparatorsChange)

    // [补实现·参考包0.3.0] 桌面图标切换：manager 以组件状态为准，selectedIcon 仅驱动 UI。
    val context = androidx.compose.ui.platform.LocalContext.current
    val appIconManager = remember(context) { AppIconManager(context) }
    var selectedAppIcon by remember { mutableStateOf(AppIcon.Modern) }
    LaunchedEffect(active) {
        if (active) selectedAppIcon = appIconManager.currentIcon()
    }

    BackHandler(enabled = active && secondaryPage != null) {
        onSecondaryPageChange(null)
    }
    BackHandler(enabled = active && secondaryPage == null) {
        onClose()
    }

    PageStackTransition(
        secondaryKey = secondaryPage,
        modifier = modifier.fillMaxSize().libraryTexture(pageDeep = true),
        label = "settings page stack",
        primaryContent = {
            SettingsRootPage(
                active = active,
                playbackSettings = playbackSettings,
                artistSettings = artistSettings,
                navigationSettings = navigationSettings,
                themeMode = themeMode,
                onClose = onClose,
                onScratchEnabledChange = onScratchEnabledChange,
                onHidePlayerAxisEnabledChange = onHidePlayerAxisEnabledChange,
                onPopcornSoundEnabledChange = onPopcornSoundEnabledChange,
                onAudioFxClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.AudioFx)
                },
                onArtistSeparatorsClick = {
                    artistSeparatorsInitialValues = artistSettings.separators
                    editingArtistSeparators = true
                },
    onNavigationClick = {
        onSecondaryPageChange(SettingsSecondaryPage.Navigation)
    },
    onMemoryClick = {
        onSecondaryPageChange(SettingsSecondaryPage.Memory)
    },
                onEngineClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.Engine)
                },
                onThemeClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.Theme)
                },
                onColorThemeClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.ColorTheme)
                },
                onAppIconClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.AppIcon)
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
        secondaryContent = { page ->
            when (page) {
                SettingsSecondaryPage.AudioFx ->
                    AudioFxSettingsPage(
                        active = active,
                        playbackSettings = playbackSettings,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onAudioFxEnabledChange = onAudioFxEnabledChange,
                        onAudioFxPresetChange = onAudioFxPresetChange,
                        onAudioFxCustomGainDbPointsChange = onAudioFxCustomGainDbPointsChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Navigation ->
                    NavigationSettingsPage(
                        active = active,
                        navigationSettings = navigationSettings,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onTabPinnedChange = onTabPinnedChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Theme ->
                    ThemeSettingsPage(
                        active = active,
                        themeMode = themeMode,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onThemeModeChange = onThemeModeChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Memory ->
                    MemoryPage(
                        active = active,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.ColorTheme ->
                    ColorThemeSettingsPage(
                        active = active,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Engine ->
                    EngineSettingsPage(
                        active = active,
                        onClose = { onSecondaryPageChange(null) },
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.AppIcon ->
                    AppIconSettingsPage(
                        active = active,
                        selectedIcon = selectedAppIcon,
                        onClose = { onSecondaryPageChange(null) },
                        onIconSelected = { icon ->
                            val result = appIconManager.setIcon(icon)
                            if (result.isSuccess) selectedAppIcon = icon
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        },
    )

    if (editingArtistSeparators) {
        ArtistSeparatorsDialog(
            initialSeparators = artistSeparatorsInitialValues,
            onDismiss = { editingArtistSeparators = false },
            onConfirm = { separators ->
                editingArtistSeparators = false
                latestOnArtistSeparatorsChange(separators)
            },
        )
    }
}

internal enum class SettingsSecondaryPage {
    AudioFx,
    Navigation,
    Theme,
    ColorTheme,
    Memory,
    Engine,
    AppIcon,
}
