@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.smartisan.music.R

/**
 * 通知中心播放控制。
 *
 * 目标是"标准尺寸 + 五种控制"：
 *  - 紧凑视图只放 上一首 / 播放暂停 / 下一首（就是普通通知那三个键，不会把通知撑宽）；
 *  - 展开后多出 播放顺序 与 喜欢 / 取消喜欢 两个键；
 *  - 进度条交给系统的 MediaStyle（随 MediaSession 的位置刷新），不自己画大图。
 */
internal class ChuiziNotificationProvider(
    context: Context,
) : DefaultMediaNotificationProvider(context) {

    private val appContext = context.applicationContext

    @Volatile
    private var favoriteIds: Set<String> = emptySet()

    init {
        setSmallIcon(R.drawable.ic_notification_small)
    }

    fun setFavoriteIds(ids: Set<String>) {
        favoriteIds = ids
    }

    /** [1.4.20·问题8] 供 toggle 后立即增量更新，避免等异步 collect。 */
    fun favoriteIdsSnapshot(): Set<String> = favoriteIds

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> = buildButtons(session, playerCommands, showPauseButton)

    /**
     * [1.4.20·问题8] 生成当前完整按钮列表。收藏变化后由 PlaybackService 调
     * session.setMediaButtonPreferences(buildButtons(...)) 推给 media3——
     * 这是官方触发通知重建的 API，比 setPlayer(同一实例) 可靠（后者走 fast path 不重建）。
     */
    fun buildButtons(
        session: MediaSession,
        playerCommands: Player.Commands = session.player.availableCommands,
        showPauseButton: Boolean = session.player.playWhenReady,
    ): ImmutableList<CommandButton> {
        val builder = ImmutableList.builder<CommandButton>()
        builder.add(buildPlaybackModeButton(session))
        if (
            playerCommands.containsAny(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        ) {
            builder.add(
                CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .setDisplayName(appContext.getString(R.string.previous_song))
                    .build(),
            )
        }
        if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE)) {
            builder.add(
                CommandButton.Builder(
                    if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
                )
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setDisplayName(
                        appContext.getString(
                            if (showPauseButton) R.string.pause else R.string.play,
                        ),
                    )
                    .build(),
            )
        }
        if (
            playerCommands.containsAny(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )
        ) {
            builder.add(
                CommandButton.Builder(CommandButton.ICON_NEXT)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .setDisplayName(appContext.getString(R.string.next_song))
                    .build(),
            )
        }
        builder.add(buildFavoriteButton(session))
        return builder.build()
    }

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: androidx.core.app.NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        // 复用父类把动作加进 builder 的逻辑，只改紧凑视图里显示哪几个。
        super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
        return CompactIndices.filter { index -> index < mediaButtons.size }.toIntArray()
    }

    /** [1.4.24·R33 自绘通知] 供 [ChuiziCustomNotificationProvider] 复用同一份按钮定义。 */
    fun buttonFor(session: MediaSession, slot: NotificationSlot): CommandButton? = when (slot) {
        NotificationSlot.PlaybackMode -> buildPlaybackModeButton(session)
        NotificationSlot.Favorite -> buildFavoriteButton(session)
        NotificationSlot.PlayPause -> buildPlayPauseButton(session)
        NotificationSlot.Next -> buildNextButton(session)
        NotificationSlot.Previous -> buildPreviousButton(session)
    }

    /**
     * [1.4.32·验收12] 下一首/上一首键改走自定义 session command（同 R134 play/pause）。
     * 用 media3 内置 ICON_NEXT / ICON_PREVIOUS 作占位（与系统默认键视觉一致）；
     * 自绘通知实际显示的是布局里的 notif_vec_next/prev（见 ChuiziCustomNotificationProvider）。
     * 这里只要能拿到一个绑了自定义 SessionCommand 的 PendingIntent 即可，图标/文案不影响。
     */
    private fun buildNextButton(session: MediaSession): CommandButton =
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(NotificationNextCommand)
            .setDisplayName("下一首")
            .build()

    private fun buildPreviousButton(session: MediaSession): CommandButton =
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setSessionCommand(NotificationPreviousCommand)
            .setDisplayName("上一首")
            .build()

    /**
     * [1.4.30·R134] 自绘通知的播放/暂停键专用按钮（走自定义 session command）。
     *
     * 只在自绘通知里用；系统媒体卡片的三个默认键仍由 media3 自己按 Player.COMMAND_PLAY_PAUSE
     * 生成，不受影响（`buildButtons()` 保持不变）。
     */
    fun buildPlayPauseButton(session: MediaSession): CommandButton {
        val playing = runCatching { session.player.playWhenReady }.getOrDefault(false)
        return CommandButton.Builder(
            if (playing) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
        )
            .setSessionCommand(NotificationPlayPauseCommand)
            .setDisplayName(
                appContext.getString(if (playing) R.string.pause else R.string.play),
            )
            .build()
    }

    /**
     * [1.4.24·R33 自绘通知] 展开态右上角模式键当前该用哪张图。
     *
     * [1.4.25·R62] 从原厂九宫图 `btn_playing_*` 换成抠图重制的 `notif_big_mode_*`：
     * 原厂 .9.png 画布 169×167 但图形只占中间一小块，塞进 44dp 框再叠 12dp padding，
     * 实际图形只剩几 dp（用户："小到几乎看不见"）。新图已裁到 alpha 包围盒并
     * 按 96px 撑满，和收藏/播放键同一套规格。
     */
    fun modeIconRes(session: MediaSession): Int {
        val player = session.player
        val shuffle = runCatching { player.shuffleModeEnabled }.getOrDefault(false)
        val repeat = runCatching { player.repeatMode }.getOrDefault(Player.REPEAT_MODE_OFF)
        // [1.4.26] 自绘通知模式键换 24dp 矢量（旧 notif_big_mode_* 是位图抠图）。
        // 列表循环开/关共用同一张 repeat 矢量，靠 Provider 的 setColorFilter 区分
        // （开=品牌红，关=半透明主文字色），不再准备两张形状不同的图。
        return when {
            shuffle -> R.drawable.notif_vec_shuffle
            repeat == Player.REPEAT_MODE_ONE -> R.drawable.notif_vec_repeat_one
            else -> R.drawable.notif_vec_repeat
        }
    }

    /**
     * [1.4.25·R69] 模式键当前是否处于"已开启"（随机 / 单曲 / 列表循环任一）。
     * 关闭态的原厂图自带 40% 透明，只靠深浅区分用户实测"颜色都一样"，
     * 所以自绘通知还会按这个布尔值给图标叠一层红色滤色（见 ChuiziCustomNotificationProvider）。
     */
    fun isModeActive(session: MediaSession): Boolean {
        val player = session.player
        val shuffle = runCatching { player.shuffleModeEnabled }.getOrDefault(false)
        val repeat = runCatching { player.repeatMode }.getOrDefault(Player.REPEAT_MODE_OFF)
        return shuffle || repeat != Player.REPEAT_MODE_OFF
    }

    fun isFavorite(session: MediaSession): Boolean {
        val mediaId = runCatching { session.player.currentMediaItem?.mediaId }.getOrNull()
        return mediaId != null && mediaId in favoriteIds
    }

    private fun buildPlaybackModeButton(session: MediaSession): CommandButton {
        // [1.4.9·G-46] 通知栏复用 App 内现成原厂图标：大播放页 shuffle/repeat 用的是
        // btn_playing_shuffle_* / btn_playing_cycle_*，通知栏原先另手绘一套 vector，
        // 线宽/形状/重量与中间三个系统默认按钮对不上。改为统一复用原厂资源。
        // [1.4.10·G-56] 回退到统一 24dp 矢量：1.4.9 复用的 btn_playing_* 是 9-patch
        // （169×167、内容区内缩），在通知栏实测只显示 27×19，比上一首/播放/下一首小一圈。
        // ic_notif_* 是一套 24dp 等尺寸矢量，与系统默认三个键视觉一致（launcher 图标未动）。
        // [1.4.25·R69] 四态分开：随机 / 单曲 / 列表循环开 / 列表循环关。
        // 原来 ALL 与 OFF 都返回 ic_notif_repeat，切循环图标毫无变化，
        // 用户只能看到"点了没反应"。OFF 改用 40% 透明的 ic_notif_repeat_off。
        val icon = when {
            runCatching { session.player.shuffleModeEnabled }.getOrDefault(false) ->
                R.drawable.ic_notif_shuffle
            runCatching { session.player.repeatMode }.getOrDefault(Player.REPEAT_MODE_OFF) ==
                Player.REPEAT_MODE_ONE -> R.drawable.ic_notif_repeat_one
            runCatching { session.player.repeatMode }.getOrDefault(Player.REPEAT_MODE_OFF) ==
                Player.REPEAT_MODE_ALL -> R.drawable.ic_notif_repeat
            else -> R.drawable.ic_notif_repeat_off
        }
        return CommandButton.Builder()
            .setSessionCommand(NotificationPlaybackModeCommand)
            .setCustomIconResId(icon)
            .setDisplayName(appContext.getString(R.string.notification_action_playback_mode))
            .build()
    }

    private fun buildFavoriteButton(session: MediaSession): CommandButton {
        val mediaId = runCatching { session.player.currentMediaItem?.mediaId }.getOrNull()
        val favorite = mediaId != null && mediaId in favoriteIds
        return CommandButton.Builder()
            .setSessionCommand(NotificationFavoriteCommand)
            // [1.4.11·S10] 收藏用实心爱心+变色：未收藏=实心白（对齐 App 现成
            // playing_btn_favorite_add 的实心语义），真收藏=实心红 #e64040。
            // 1.4.10 G-56 误把未收藏做成空心描边，与本意不符，本轮修正。保持 24dp 等尺寸。
            .setCustomIconResId(
                if (favorite) R.drawable.ic_notif_favorite_filled else R.drawable.ic_notif_favorite,
            )
            .setDisplayName(
                appContext.getString(
                    if (favorite) R.string.notification_action_unfavorite
                    else R.string.notification_action_favorite,
                ),
            )
            .build()
    }

    private companion object {
        val CompactIndices = intArrayOf(1, 2, 3)
    }
}

/** [1.4.24·R33 自绘通知] 需要单独取用 PendingIntent 的自定义按键。 */
internal enum class NotificationSlot {
    PlaybackMode,
    Favorite,
    PlayPause,
    Next,
    Previous,
}
