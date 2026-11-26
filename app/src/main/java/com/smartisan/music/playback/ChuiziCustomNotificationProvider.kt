@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.smartisan.music.R

/**
 * [1.4.24·R33/R34/R40 自绘通知] 全量接管通知内容区。
 *
 * 为什么要另起一个 Provider：
 *  - Android 13 起，系统媒体卡片的按钮布局由 SystemUI 按 PlaybackState 写死，
 *    app 既改不了按钮尺寸也改不了热区；media3 1.10.1 的 `MediaStyle.apply()`
 *    无条件 `setStyle(MediaStyle)`，连它自己的 layout 都不参与渲染（`javap` 可证）。
 *  - 唯一能真正放大热区的办法：**不设 MediaStyle**，用 `setCustomContentView` +
 *    `setCustomBigContentView` 自己排。代价是通知栏里不再是系统媒体卡片样式，
 *    但 MediaSession 照旧存在，锁屏控制 / 蓝牙线控 / 音量键都不受影响。
 *
 * R34 实机修正（用户截图：三个按钮变成实心黑圆点、收起态文字整行看不见）：
 *  1. **不再对整张位图 setColorFilter**。原厂按钮是「白圆底 + 灰图标」的位图，
 *     整图染色会把圆底和图标染成同色 → 变成实心圆点，形状全丢。
 *     改用 `tools/extract_notification_icons.py` 从原厂位图里抠出的**透明底扁平图标**
 *     （`notif_flat_*` / `notif_big_*`，日间深灰 / 夜间浅灰两套），颜色由资源系统
 *     按通知底色自动切换。
 *  2. **不再在代码里猜通知底色**。App 主题与系统通知底色可以不一致（App 强制深色、
 *     系统浅色时就会反色），文字与进度条颜色一律交给 `@color/notif_text_*` 与
 *     `drawable/notif_progress` 的 night 变体，由系统按当前 UI 模式解析。
 *
 * R40 实机修正（用户：「收起态比正常通知高、按钮小得跟鸡屁一样」「又显示专辑封面，
 * 又显示通知图标」「收起后没位置放收藏键吗」）：
 *  1. **收起态 48dp → 44dp**，去掉与普通通知相比多出来的高度。
 *  2. **收起态补上收藏键**（notif_btn_favorite），四个键一字排开。
 *  3. **封面改由系统 large icon 承载**（`setLargeIcon`），自绘布局里不再画封面。
 *  4. **按钮图标尺寸修正**：这些 PNG 之前落在 `drawable-nodpi`（1px=1dp），
 *     96px 的图被当成 96dp 再缩到 26dp 内容区，图形实际只剩 ~7dp —— 这才是
 *     「按钮小得跟鸡屁一样」的根因。现在改放 `drawable-xxhdpi`（3x），并按 bbox
 *     裁掉透明边，flat=24dp 视觉、big=32dp 视觉，图形撑满按钮。
 *
 * [1.4.25·R60/R59/R62/R69 第二轮实机修正]：
 *  - **R60 高度**：收起态再压到 40dp、图标 24dp→22dp；展开态第一行 44→40dp、
 *    大按钮行 72→60dp、进度行 24→22dp。系统装饰头（App 名 + 展开箭头）在
 *    targetSdk≥31 下去不掉，所以只能压自绘那一层，尽量逼近普通通知高度。
 *  - **R60 图标**：不再 `setLargeIcon`，系统头部固定显示 App 图标（用户拍板）。
 *  - **R59 颜色**：全部 notif_* 图标重新抠图，颜色统一取通知主文字色
 *    `notif_text_primary`（浅色模式 #101010 / 深色 #ffffff），无白边残留。
 *  - **R62 模式键**：改用 `notif_big_mode_*`（裁到 bbox、96px 撑满），
 *    padding 12dp → 6dp，与收藏键同规格。
 *  - **R69 模式键状态**：循环/单曲/随机开着时给图标叠品牌红（`notif_mode_active`），
 *    全关时用主文字色；配合 PlaybackService 的 refreshNotificationButtons()，
 *    在通知栏里切模式后图标与"下一首"按钮都能实时变。
 */
internal class ChuiziCustomNotificationProvider(
    private val context: Context,
    private val buttons: ChuiziNotificationProvider,
    private val onOpenApp: () -> PendingIntent,
) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(context).apply {
        setSmallIcon(R.drawable.ic_notification_small)
    }

    private val channelInfo = delegate.notificationChannelInfo

    override fun createNotification(
        session: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        ensureChannel()
        val player = session.player
        val metadata = player.mediaMetadata
        // [1.4.32·验收13] 图标绑「实际是否在播」而非 playWhenReady。
        // 旧值 playWhenReady 在自然播完（STATE_ENDED）时仍为 true、但 isPlaying=false，
        // 图标停在暂停键不刷新；改为 isPlaying && playWhenReady，播完即显示播放键。
        val playing = player.isPlaying && player.playWhenReady

        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_song_title)
        val subtitle = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: ""

        val collapsed = RemoteViews(context.packageName, R.layout.notification_custom_collapsed)
        val expanded = RemoteViews(context.packageName, R.layout.notification_custom_expanded)

        // ---- 收起态：标题 + 收藏 / 上一首 / 播放暂停 / 下一首 ----
        collapsed.setTextViewText(R.id.notif_title, title)
        // [1.4.26] 播放/暂停换 24dp 矢量（旧 notif_flat_* 位图边缘锯齿、三角不对称）。
        collapsed.setImageViewResource(
            R.id.notif_btn_play_pause,
            if (playing) R.drawable.notif_vec_pause else R.drawable.notif_vec_play,
        )

        // ---- 展开态：标题/副标题 + 模式/收藏 + 进度 + 三个大键 ----
        expanded.setTextViewText(R.id.notif_title, title)
        expanded.setTextViewText(R.id.notif_subtitle, subtitle)
        expanded.setImageViewResource(
            R.id.notif_btn_play_pause,
            if (playing) R.drawable.notif_vec_pause else R.drawable.notif_vec_play,
        )

        // ---- 事件 ----
        // [1.4.30·R134] 播放/暂停**不再**走 createMediaActionPendingIntent。
        // 暂停态下 media3 会把它做成前台服务类型 PendingIntent，覆盖安装后首次冷启、
        // App 在后台时 SystemUI 发不出来（真机日志：服务 45 秒内零命令）。改走与收藏/模式键
        // 同一条自定义 session command 通道，实测可达。取不到时再回退旧路径，不留白键。
        // [1.4.32·验收12] 上一首/下一首同样改走自定义 session command（R134 只修了 play/pause）。
        // 冷启后台、未建立 playWhenReady 会话时，media3 标准的媒体动作 PendingIntent 是前台服务
        // 类型，SystemUI 投递不到；自定义命令通道实测可达。取不到按钮时回退系统默认，不留白键。
        val prevButton = buttons.buttonFor(session, NotificationSlot.Previous)
        val prevIntent = prevButton?.let { button ->
            runCatching {
                actionFactory.createCustomActionFromCustomCommandButton(session, button).actionIntent
            }.getOrNull()
        } ?: actionFactory.createMediaActionPendingIntent(
            session,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        val nextButton = buttons.buttonFor(session, NotificationSlot.Next)
        val nextIntent = nextButton?.let { button ->
            runCatching {
                actionFactory.createCustomActionFromCustomCommandButton(session, button).actionIntent
            }.getOrNull()
        } ?: actionFactory.createMediaActionPendingIntent(
            session,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
        val toggleButton = buttons.buttonFor(session, NotificationSlot.PlayPause)
        val toggleIntent = toggleButton?.let { button ->
            runCatching {
                actionFactory.createCustomActionFromCustomCommandButton(session, button).actionIntent
            }.getOrNull()
        } ?: actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_PLAY_PAUSE)
        listOf(collapsed, expanded).forEach { rv ->
            rv.setOnClickPendingIntent(R.id.notif_btn_prev, prevIntent)
            rv.setOnClickPendingIntent(R.id.notif_btn_play_pause, toggleIntent)
            rv.setOnClickPendingIntent(R.id.notif_btn_next, nextIntent)
        }
        // [1.4.26] 媒体键统一染成通知主文字色：矢量 path 里写死白色是为了
        // setColorFilter 可覆盖，实际显示色由日夜资源 notif_text_primary 决定。
        val primaryColor = context.getColor(R.color.notif_text_primary)
        listOf(collapsed, expanded).forEach { rv ->
            rv.setInt(R.id.notif_btn_prev, "setColorFilter", primaryColor)
            rv.setInt(R.id.notif_btn_play_pause, "setColorFilter", primaryColor)
            rv.setInt(R.id.notif_btn_next, "setColorFilter", primaryColor)
        }
        // [1.4.26·点击跳大播放页根治] 只设 builder.setContentIntent 不够：
        // 媒体通知的 sessionActivity（跳大播放页）会覆盖它。这里给自绘区域的
        // 根布局、标题、副标题、进度行逐项显式绑定"回到 App 上次页面"，
        // 点文字和空白处都走 onOpenApp，不再依赖系统默认路径。
        val openAppIntent = onOpenApp()
        collapsed.setOnClickPendingIntent(R.id.notif_collapsed_root, openAppIntent)
        collapsed.setOnClickPendingIntent(R.id.notif_title, openAppIntent)
        expanded.setOnClickPendingIntent(R.id.notif_expanded_root, openAppIntent)
        expanded.setOnClickPendingIntent(R.id.notif_title, openAppIntent)
        expanded.setOnClickPendingIntent(R.id.notif_subtitle, openAppIntent)
        expanded.setOnClickPendingIntent(R.id.notif_progress_row, openAppIntent)

        // ---- 收藏键：收起态 + 展开态都挂上，图标随收藏状态切换 ----
        val modeButton = buttons.buttonFor(session, NotificationSlot.PlaybackMode)
        val favoriteButton = buttons.buttonFor(session, NotificationSlot.Favorite)
        // [1.4.26] 收藏换单张矢量，状态靠颜色区分：未收藏=主文字色，已收藏=品牌红。
        // 旧实现用两张形状不同的位图（add/cancel），抠图形状本身就很丑。
        val favorite = buttons.isFavorite(session)
        collapsed.setImageViewResource(R.id.notif_btn_favorite, R.drawable.notif_vec_favorite)
        expanded.setImageViewResource(R.id.notif_btn_favorite, R.drawable.notif_vec_favorite)
        val favoriteColor =
            if (favorite) context.getColor(R.color.notif_mode_active) else primaryColor
        collapsed.setInt(R.id.notif_btn_favorite, "setColorFilter", favoriteColor)
        expanded.setInt(R.id.notif_btn_favorite, "setColorFilter", favoriteColor)
        expanded.setImageViewResource(R.id.notif_btn_mode, buttons.modeIconRes(session))
        // [1.4.25·R69] 模式键"已开启"时染成品牌红：循环开/关的原厂图是同一造型、
        // 只差 40% 透明度，用户实测「无论开还是关图标颜色都一样」。加一档色相差之后，
        // 红色=循环/单曲/随机开着，灰=全关，扫一眼就能判断。
        // [1.4.26] 列表循环开/关共用同一张 repeat 矢量：开=品牌红，关=55% 主文字色。
        // 旧实现靠"两张形状不同的位图 + 40% 透明"区分，形状差异大且都很难看。
        val modeOffColor =
            android.graphics.Color.argb(
                140,
                android.graphics.Color.red(primaryColor),
                android.graphics.Color.green(primaryColor),
                android.graphics.Color.blue(primaryColor),
            )
        expanded.setInt(
            R.id.notif_btn_mode,
            "setColorFilter",
            if (buttons.isModeActive(session)) context.getColor(R.color.notif_mode_active)
            else modeOffColor,
        )
        modeButton?.let { button ->
            runCatching {
                actionFactory.createCustomActionFromCustomCommandButton(session, button).actionIntent
            }.getOrNull()?.let { expanded.setOnClickPendingIntent(R.id.notif_btn_mode, it) }
        }
        favoriteButton?.let { button ->
            runCatching {
                actionFactory.createCustomActionFromCustomCommandButton(session, button).actionIntent
            }.getOrNull()?.let { intent ->
                collapsed.setOnClickPendingIntent(R.id.notif_btn_favorite, intent)
                expanded.setOnClickPendingIntent(R.id.notif_btn_favorite, intent)
            }
        }

        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val position = player.currentPosition.coerceIn(0L, duration.coerceAtLeast(0L))
        val permille = if (duration > 0) (position * 1000 / duration).toInt() else 0
        expanded.setProgressBar(R.id.notif_progress, 1000, permille, false)
        expanded.setTextViewText(R.id.notif_position, formatTime(position))
        expanded.setTextViewText(R.id.notif_duration, formatTime(duration))

        // ---- [1.4.25·R60] 不再塞 large icon，系统头部回到 App 图标 ----
        // R40 当时为了"别同时出现封面和通知图标"把封面交给了 large icon；但实测
        // 没有内嵌封面的歌拿到的是同源黑胶占位图，用户原话「我看不出它跟 App 图标的区别，
        // 如果区别不大的话，那就换回 App 图标」。而且多这一张图也让通知整体更高。
        // 现在统一不设 large icon：头部那枚就是 App 图标，展开态只有系统头部一行图。
        // 想要封面请看 App 内大播放页（那里是 1:1 高清封面，不受通知尺寸限制）。

        val builder = NotificationCompat.Builder(context, channelInfo.id)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentIntent(onOpenApp())
            .setContentTitle(title)
            .setContentText(subtitle)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(playing)

        return MediaNotification(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            builder.build(),
        )
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: android.os.Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo = channelInfo

    /** 直接用 media3 的默认渠道，用户不会在系统设置里看到两个「播放中」渠道。 */
    private fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelInfo.id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(channelInfo.id, channelInfo.name, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
