package com.smartisan.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.smartisan.music.data.settings.ColorThemeStore
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.ui.artwork.AlbumArtworkBrowserHost
import com.smartisan.music.ui.components.SmartisanDialogButton
import com.smartisan.music.ui.components.SmartisanMenuTitleBar
import com.smartisan.music.ui.components.SmartisanModal
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.shell.MusicAppShell
import com.smartisan.music.ui.splash.StartupSplash
import com.smartisan.music.ui.theme.LocalColorTheme
import com.smartisan.music.ui.theme.MusicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var playbackLaunchRequest by mutableIntStateOf(0)
    private var externalAudioLaunchRequestId by mutableIntStateOf(0)
    private var externalAudioLaunchRequest by mutableStateOf<ExternalAudioLaunchRequest?>(null)
    private var shortcutDestinationRequestId by mutableIntStateOf(0)
    private var shortcutDestinationRequest by mutableStateOf<ShortcutDestinationRequest?>(null)
    private var startupContentReady = false
    // 1.4.3 自定义开屏（方案① 左对齐 + 轻淡入 + 苏芳色名称 + slogan）：
    // 用 Compose state 控制覆盖层，动画播完自行淡出并移除。
    private var showStartupSplash by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettingsStore = ThemeSettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(themeSettingsStore.currentMode().appCompatNightMode)
        val splashScreen = installSplashScreen()
        // 1.4.3：系统 SplashScreen 只留冷启动那一瞬的纯白底（图标透明占位），立即让位，
        // 真正的开屏由 App 内 StartupSplash 覆盖层接管——系统级遮罩不支持左对齐 + 文字 +
        // slogan + 分档轻淡入。setKeepOnScreenCondition 恒 false 让系统白底尽快撤掉，
        // 避免它盖住我们自定义开屏的入场动画。
        splashScreen.setKeepOnScreenCondition { false }
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            delay(StartupSplashTimeoutMillis)
            startupContentReady = true
        }
        consumeLaunchIntent(intent)
        // 音量记忆：恢复上次的软件/硬件音量档位（硬件档位在独占会话建立时自动写回 DAC）
        com.smartisan.music.playback.UsbVolume.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            // 1.4.4 N-02：按「当前生效的深浅模式」给开屏选配色。深浅切换会 setDefaultNightMode
            // → uiMode 变更 → Activity 重建，本 Composable 重新执行，isSystemInDarkTheme()
            // 自动取到切换后的新值：浅→深播深色版、深→浅播浅色版、冷启动按当前模式。
            // 不需要记录「从哪个模式来」，也不引入跨重建的状态机（诊断页第 6 张卡口径）。
            val systemDark = isSystemInDarkTheme()
            // 1.3.7：色彩主题全树提供。彩色 = 保持现状；单色主题只染点缀位，夜间模式同样生效。
            val colorTheme by ColorThemeStore.theme.collectAsState()
            // [1.4.23·莫奈取色] 开关打开时刷新取色缓存（深色/浅色重建 Activity 也会执行）。
            // [1.4.28·注释纠偏] 取色种子是「当前色彩主题各 role 自己的原色」，不是系统壁纸；
            // MonetColors.refresh() 目前是 no-op，保留调用只为旧的刷新时机语义。
            val monetContext = LocalContext.current
            val monetEnabled by com.smartisan.music.data.settings.MonetStore.enabled.collectAsState()
            LaunchedEffect(monetEnabled) {
                if (monetEnabled) {
                    com.smartisan.music.ui.theme.MonetColors.refresh(monetContext)
                }
            }
            // [护栏 G-37·1.4.8 落地] 状态栏沉浸开关：开=同色(现状)，关=黑底+浅色图标。
            val statusBarImmersive by
                com.smartisan.music.data.settings.StatusBarStyleStore.immersive.collectAsState()
            val statusView = LocalView.current
            // [1.4.14·S08] 真正隐藏状态栏开关：开=全屏隐藏，关=恢复显示。
            val statusBarHidden by
                com.smartisan.music.data.settings.StatusBarStyleStore.hidden.collectAsState()
            DisposableEffect(statusBarImmersive, systemDark, statusBarHidden) {
                val window = (statusView.context as Activity).window
                val controller = WindowCompat.getInsetsController(window, statusView)
                controller.isAppearanceLightStatusBars = if (statusBarImmersive) !systemDark else false
                if (statusBarHidden) {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
                onDispose {
                    // 离开时恢复，避免切到别的 Activity 状态栏仍隐藏
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
            }
            MusicTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalColorTheme provides colorTheme) {
                    RequestAudioPermissionOnLaunch()
                    RequestAllFilesAccessOnLaunch()
                    Box {
                        // [护栏 G-37·1.4.8 落地] 遮罩法：仅在状态栏那一条画黑底，
                        // 不动任何页面的 WindowInsets padding；关闭沉浸时才显示。
                        if (!statusBarImmersive) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBars)
                                    .background(Color.Black)
                                    .align(Alignment.TopCenter)
                                    .zIndex(10f)
                            )
                        }
                        AlbumArtworkBrowserHost {
                            MusicAppShell(
                                playbackLaunchRequest = playbackLaunchRequest,
                                externalAudioLaunchRequest = externalAudioLaunchRequest,
                                onExternalAudioLaunchConsumed = ::clearExternalAudioLaunchRequest,
                                shortcutDestinationRequest = shortcutDestinationRequest,
                                onShortcutDestinationConsumed = ::clearShortcutDestinationRequest,
                                onStartupReady = {
                                    startupContentReady = true
                                },
                                onThemeModeChange = { mode ->
                                    themeSettingsStore.setMode(mode)
                                    AppCompatDelegate.setDefaultNightMode(mode.appCompatNightMode)
                                },
                            )
                        }
                        // 1.4.3：自定义开屏覆盖层放在最后绘制，盖住主界面；
                        // 动画播完 onFinished 置 false 自动移除，不阻塞主界面加载。
                        if (showStartupSplash) {
                            StartupSplash(
                                onFinished = { showStartupSplash = false },
                                isDark = systemDark,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 独占期间也把音量键绑定到媒体音量流，这样按音量键会弹出手机系统自带的音量条
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
    }

    override fun onPause() {
        super.onPause()
    }

    /**
     * 音量键不再由 App 拦截：独占期间音量类型已报成 LOCAL，系统会弹出手机自带的音量条
     * 并直接改系统媒体音量；UAC 输出的软件增益监听 `ACTION_VOLUME_CHANGED` 跟随系统音量，
     * 二者始终一致。自定义音量弹窗/硬件音量已在 1.2.6 移除。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        val launchIntent = intent ?: return
        if (isPlaybackLaunchIntent(launchIntent) && !isConsumedPlaybackLaunchIntent(launchIntent)) {
            playbackLaunchRequest += 1
            launchIntent.putExtra(ExtraOpenPlaybackConsumed, true)
            return
        }
        if (isShortcutLaunchIntent(launchIntent) && !isConsumedShortcutLaunchIntent(launchIntent)) {
            val destination = launchIntent.getStringExtra(ExtraShortcutDestination)
            if (!destination.isNullOrBlank()) {
                shortcutDestinationRequestId += 1
                shortcutDestinationRequest =
                    ShortcutDestinationRequest(shortcutDestinationRequestId, destination)
                launchIntent.putExtra(ExtraShortcutConsumed, true)
                return
            }
        }

        val externalAudioMimeType = launchIntent.resolveType(contentResolver)
        if (
            isExternalAudioLaunchIntent(launchIntent, externalAudioMimeType) &&
                !isConsumedExternalAudioLaunchIntent(launchIntent)
        ) {
            externalAudioLaunchRequestId += 1
            externalAudioLaunchRequest =
                ExternalAudioLaunchRequest(
                    requestId = externalAudioLaunchRequestId,
                    uri = requireNotNull(launchIntent.data),
                    mimeType = externalAudioMimeType,
                    displayName = resolveExternalAudioDisplayName(launchIntent.data),
                )
            launchIntent.putExtra(ExtraExternalAudioConsumed, true)
        }
    }

    private fun clearExternalAudioLaunchRequest(requestId: Int) {
        if (externalAudioLaunchRequest?.requestId == requestId) {
            externalAudioLaunchRequest = null
        }
    }

    private fun clearShortcutDestinationRequest(requestId: Int) {
        if (shortcutDestinationRequest?.requestId == requestId) {
            shortcutDestinationRequest = null
        }
    }

    private fun resolveExternalAudioDisplayName(uri: Uri?): String? {
        uri ?: return null
        if (uri.scheme == ContentScheme) {
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            }
                .getOrNull()
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) {
                            return cursor.getString(column)?.takeIf(String::isNotBlank)
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
    }

    companion object {
        private const val ActionOpenPlayback = "com.smartisan.music.action.OPEN_PLAYBACK"
        private const val ExtraOpenPlayback = "com.smartisan.music.extra.OPEN_PLAYBACK"
        private const val ExtraOpenPlaybackConsumed =
            "com.smartisan.music.extra.OPEN_PLAYBACK_CONSUMED"
        private const val ExtraExternalAudioConsumed =
            "com.smartisan.music.extra.EXTERNAL_AUDIO_CONSUMED"
        private const val ActionShortcut = "com.smartisan.music.action.SHORTCUT"
        private const val ExtraShortcutDestination =
            "com.smartisan.music.extra.SHORTCUT_DESTINATION"
        private const val ExtraShortcutConsumed =
            "com.smartisan.music.extra.SHORTCUT_CONSUMED"
        private const val ContentScheme = "content"
        private const val FileScheme = "file"
        private const val StartupSplashTimeoutMillis = 2_500L

        fun createOpenPlaybackIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ActionOpenPlayback
                putExtra(ExtraOpenPlayback, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        private fun isPlaybackLaunchIntent(intent: Intent?): Boolean {
            return intent?.action == ActionOpenPlayback ||
                intent?.getBooleanExtra(ExtraOpenPlayback, false) == true
        }

        private fun isConsumedPlaybackLaunchIntent(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(ExtraOpenPlaybackConsumed, false) == true
        }

        private fun isShortcutLaunchIntent(intent: Intent?): Boolean {
            return intent?.action == ActionShortcut
        }

        private fun isConsumedShortcutLaunchIntent(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(ExtraShortcutConsumed, false) == true
        }

        private fun isExternalAudioLaunchIntent(intent: Intent?, mimeType: String?): Boolean {
            val uri = intent?.data ?: return false
            val normalizedMimeType = mimeType?.lowercase() ?: return false
            return intent.action == Intent.ACTION_VIEW &&
                uri.scheme in setOf(ContentScheme, FileScheme) &&
                (normalizedMimeType.startsWith("audio/") ||
                    normalizedMimeType == "application/ogg" ||
                    normalizedMimeType == "application/x-ogg" ||
                    normalizedMimeType == "application/itunes")
        }

        private fun isConsumedExternalAudioLaunchIntent(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(ExtraExternalAudioConsumed, false) == true
        }
    }
}

/** 桌面长按 App 图标弹出的 shortcut 请求：直达歌曲/专辑/喜欢/搜索。 */
data class ShortcutDestinationRequest(
    val requestId: Int,
    val destination: String,
)

@Composable
private fun RequestAudioPermissionOnLaunch() {    val context = LocalContext.current
    val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(permission) {
        if (
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(permission)
        }
    }
    // Android 13+ 起，通知中心要显式授权，否则播放时不会出通知。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notificationLauncher =
            rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) {}
        LaunchedEffect(Unit) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * 「所有文件访问」引导（Android 11+）：DSD(DFF/DSF)/DTS/WavPack 等格式不被系统
 * 媒体扫描器归类为音频，READ_MEDIA_AUDIO 读不到它们的 MediaStore Files uri，
 * 转码播放会静默失败。未授权时弹一次说明，可跳系统设置开启；拒绝后不再打扰。
 */
@Composable
private fun RequestAllFilesAccessOnLaunch() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val context = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1400) // 避开启动时的音频/通知权限系统弹窗
        val prefs = context.getSharedPreferences("all_files_access", Context.MODE_PRIVATE)
        if (!android.os.Environment.isExternalStorageManager() && !prefs.getBoolean("asked", false)) {
            showPrompt = true
        }
    }
    if (!showPrompt) return
    val appContext = context.applicationContext
    fun markAsked() {
        appContext.getSharedPreferences("all_files_access", Context.MODE_PRIVATE)
            .edit().putBoolean("asked", true).apply()
    }
    SmartisanModal(
        onDismiss = {
            markAsked()
            showPrompt = false
        },
        modifier = Modifier.fillMaxWidth(),
        bottom = true,
    ) {
        SmartisanMenuTitleBar(
            stringResource(com.smartisan.music.R.string.all_files_access_title),
            onDismiss = {
                markAsked()
                showPrompt = false
            },
        )
        Column(
            Modifier.fillMaxWidth()
                .smartisanPainterBackground(
                    rememberSmartisanDrawablePainter(com.smartisan.music.R.drawable.menu_dialog_background)
                )
                .padding(horizontal = dimensionResource(com.smartisan.music.R.dimen.menu_dialog_horizontal_distance))
                .padding(top = 14.dp, bottom = 18.dp),
        ) {
            BasicText(
                stringResource(com.smartisan.music.R.string.all_files_access_message),
                Modifier.padding(bottom = 14.dp),
                style = TextStyle(
                    color = colorResource(com.smartisan.music.R.color.list_text_color_small),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
            )
            SmartisanDialogButton(
                stringResource(com.smartisan.music.R.string.all_files_access_go),
                {
                    markAsked()
                    runCatching {
                        context.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    showPrompt = false
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}
