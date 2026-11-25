package com.smartisan.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiSettingsDataStore by preferencesDataStore(name = "ui_settings")

/** 界面辅助类设置（与播放、曲库无关）。 */
class UiSettingsStore(private val context: Context) {

    private object Keys {
        val IconNameHint = booleanPreferencesKey("icon_name_hint")
        val ImmersiveMode = booleanPreferencesKey("immersive_mode")

        /** [1.4.24·R36] 专辑页是否显示「播放全部 / 随机播放」栏，默认显示。 */
        val AlbumPlayActionsVisible = booleanPreferencesKey("album_play_actions_visible")

        /** [1.4.24·R33] 通知栏是否用自绘大按钮（关掉则回到系统媒体卡片样式），默认自绘。 */
        val NotificationCustomLayout = booleanPreferencesKey("notification_custom_layout")

        /** [1.4.25·R53] 库页顶栏排序栏与播放栏位置对调，默认关。 */
        val SortPlayBarSwap = booleanPreferencesKey("sort_play_bar_swap")

        /** [1.4.25·R44] 文字过长是否滚动显示，默认开（关则直接裁切、无省略号）。 */
        val TextMarquee = booleanPreferencesKey("text_marquee")
    }

    /** 是否开启「长按图标按钮显示名称」。1.2.32 起默认开启。 */
    val iconNameHintEnabled: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.IconNameHint] ?: true
        }

    suspend fun setIconNameHintEnabled(enabled: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.IconNameHint] = enabled
        }
    }

    /** 1.3.7：沉浸模式，默认开启。关闭后播放操作行不再随列表滚动收起。 */
    val immersiveModeEnabled: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.ImmersiveMode] ?: true
        }

    suspend fun setImmersiveModeEnabled(enabled: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.ImmersiveMode] = enabled
        }
    }

    /**
     * [1.4.24·R36] 专辑页顶部「播放全部 / 随机播放」栏是否显示，默认 true。
     * 关闭后专辑列表直接顶到顶栏下方，多显示一行；开关在「设置 → 界面 → 外观」卡片。
     */
    val albumPlayActionsVisible: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.AlbumPlayActionsVisible] ?: true
        }

    suspend fun setAlbumPlayActionsVisible(visible: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.AlbumPlayActionsVisible] = visible
        }
    }

    /**
     * [1.4.24·R33] 通知栏布局：true = 自绘大按钮（上一首/播放/下一首 各占整行 1/3），
     * false = 系统媒体卡片（按钮 48dp，但保留系统进度拖动与播放器切换）。
     * 由 PlaybackService 实时监听并重建通知，不用重启。
     */
    val notificationCustomLayout: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.NotificationCustomLayout] ?: true
        }

    suspend fun setNotificationCustomLayout(custom: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.NotificationCustomLayout] = custom
        }
    }

    /** [1.4.25·R53] 排序栏与播放栏位置对调，默认 false（排序栏在上）。 */
    val sortPlayBarSwap: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.SortPlayBarSwap] ?: false
        }

    suspend fun setSortPlayBarSwap(swapped: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.SortPlayBarSwap] = swapped
        }
    }

    /** [1.4.25·R44] 文字滚动显示，默认 true；关闭则超宽文字直接裁切、不滚动无省略号。 */
    val textMarquee: Flow<Boolean> =
        context.uiSettingsDataStore.data.map { preferences ->
            preferences[Keys.TextMarquee] ?: true
        }

    suspend fun setTextMarquee(enabled: Boolean) {
        context.uiSettingsDataStore.edit { preferences ->
            preferences[Keys.TextMarquee] = enabled
        }
    }
}
