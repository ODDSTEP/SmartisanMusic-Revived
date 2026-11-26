package com.smartisan.music.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * [补实现·参考包0.3.0] 桌面图标切换。
 *
 * 背景：这两个测试（AppIconManagerTest）是移植时「删了实现、漏删了测试」留下的，
 * 导致 compileDebugUnitTestKotlin 整体失败。按用户「补实现」决定，从 0.3.0 参考包
 * 原样移植本文件，让用户能在设置里切换原版/现代两款桌面图标（不是补成死代码）。
 */
internal enum class AppIcon(
    internal val aliasClassName: String,
    internal val enabledByDefault: Boolean,
) {
    Original(
        aliasClassName = "com.smartisan.music.launcher.OriginalIconAlias",
        enabledByDefault = false,
    ),
    Modern(
        aliasClassName = "com.smartisan.music.launcher.ModernIconAlias",
        enabledByDefault = true,
    ),
}

/**
 * Owns the launcher component state used for the user-selectable application icon.
 *
 * Component state is the persisted source of truth. Keeping a second DataStore preference would
 * allow it to disagree with PackageManager after an app restore, upgrade, or launcher recovery.
 */
internal class AppIconManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun currentIcon(): AppIcon {
        val enabledIcons = AppIcon.entries.filterTo(mutableSetOf(), ::isEffectivelyEnabled)
        return resolveAppIcon(enabledIcons)
    }

    fun setIcon(icon: AppIcon): Result<AppIcon> = runCatching {
        if (currentIcon() != icon || !hasExactlyOneEnabledAlias()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAliasesAtomically(icon)
            } else {
                setAliasesCompat(icon)
            }
        }
        icon
    }

    private fun hasExactlyOneEnabledAlias(): Boolean {
        return AppIcon.entries.count(::isEffectivelyEnabled) == 1
    }

    private fun isEffectivelyEnabled(icon: AppIcon): Boolean {
        return when (packageManager.getComponentEnabledSetting(icon.componentName())) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false

            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.enabledByDefault
            else -> icon.enabledByDefault
        }
    }

    private fun setAliasesCompat(selectedIcon: AppIcon) {
        // Enable first so API 27-32 never observe a package with no launcher entry.
        packageManager.setComponentEnabledSetting(
            selectedIcon.componentName(),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIcon.entries
            .filterNot { it == selectedIcon }
            .forEach { icon ->
                packageManager.setComponentEnabledSetting(
                    icon.componentName(),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }

    private fun AppIcon.componentName(): ComponentName {
        return ComponentName(appContext.packageName, aliasClassName)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setAliasesAtomically(selectedIcon: AppIcon) {
        packageManager.setComponentEnabledSettings(
            AppIcon.entries.map { icon ->
                PackageManager.ComponentEnabledSetting(
                    icon.componentName(),
                    if (icon == selectedIcon) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP,
                )
            },
        )
    }
}

internal fun resolveAppIcon(enabledIcons: Set<AppIcon>): AppIcon {
    return enabledIcons.singleOrNull() ?: AppIcon.Modern
}
