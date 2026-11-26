package com.smartisan.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.ui.library.cardTextureMonet
import kotlinx.coroutines.launch
import com.smartisan.music.playback.AudioOutputMonitor
import com.smartisan.music.playback.LocalAudioLibrary
import com.smartisan.music.playback.PlaybackFormatBus
import com.smartisan.music.platform.media.AudioFormatProbe
import com.smartisan.music.platform.media.FormatCache
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun infoRowStyle() =
    TextStyle(
        color = colorResource(R.color.add_nav_text_color),
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            Modifier.width(92.dp),
            style =
                TextStyle(
                    color = colorResource(R.color.playing_subtitle_text),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
        )
        BasicText(
            value,
            style = infoRowStyle(),
            maxLines = 2,
            overflow = TextOverflow.Clip,
        )
    }
}

/** 采样率：44100 -> 44.1 kHz */
internal fun formatSampleRate(hz: Long?): String {
    hz ?: return "—"
    if (hz <= 0L) return "—"
    val khz = hz / 1000.0
    val text =
        if (khz == khz.toLong().toDouble()) {
            khz.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", khz)
        }
    return "$text kHz"
}

internal fun formatBitDepth(bits: Long?): String {
    val v = bits?.takeIf { it > 0L } ?: return "—"
    return "$v bit"
}

internal fun formatBitrate(bps: Long?): String {
    val v = bps?.takeIf { it > 0L } ?: return "—"
    return "${v / 1000} kbps"
}

internal fun formatDuration(ms: Long?): String {
    val v = ms?.takeIf { it > 0L } ?: return "—"
    val totalSeconds = v / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** 字节数 → 本地化的 "12.4 MB" 形式。 */
private fun formatFileSize(context: Context, bytes: Long?): String {
    val v = bytes?.takeIf { it > 0L } ?: return "—"
    return runCatching { android.text.format.Formatter.formatShortFileSize(context, v) }
        .getOrDefault("$v B")
}

private fun MediaItem.extraLong(key: String): Long? =
    (mediaMetadata.extras?.get(key) as? Number)?.toLong()?.takeIf { it > 0L }

internal fun MediaItem.trackFormatLabel(context: Context): String {
    // 首选文件名后缀（APE/DFF/DSF 等转码格式系统探测不出 codec，后缀最直接可靠）
    val displayName =
        mediaMetadata.extras?.getString(LocalAudioLibrary.DisplayNameExtraKey)
    displayName?.substringAfterLast('.', "")?.takeIf {
        it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit)
    }?.let { ext -> return ext.uppercase() }
    val format =
        mediaMetadata.extras?.getString(LocalAudioLibrary.FormatExtraKey)?.takeIf {
            it.isNotBlank()
        }
    if (!format.isNullOrBlank()) {
        return when (format) {
            "audio/dsd" -> "DSD"
            "audio/ape" -> "APE"
            else -> format.uppercase()
        }
    }
    return mediaMetadata.extras?.getString(LocalAudioLibrary.QualityTierExtraKey)
        ?.takeIf { it.isNotBlank() }
        ?: "—"
}

private fun outputPortLabel(context: Context): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val devices =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
    val preferred =
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?:
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    return when (preferred?.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.output_port_wired)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> context.getString(R.string.output_port_bluetooth)
        AudioDeviceInfo.TYPE_USB_HEADSET -> context.getString(R.string.output_port_usb)
        else -> context.getString(R.string.output_port_speaker)
    }
}

/** 编辑保存成功后回传的新标签：壳层用它同步播放队列与媒体库列表 */
internal data class SavedTrackTags(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
)

@Composable
internal fun TrackInfoOverlay(
    visible: Boolean,
    item: MediaItem?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onSaved: (SavedTrackTags) -> Unit = {},
) {
    var retained by remember { mutableStateOf(item) }
    LaunchedEffect(visible, item) { if (visible) retained = item }
    val displayed = if (visible) item else retained
    val context = LocalContext.current
    var showEdit by remember { mutableStateOf(false) }
    // 文件名（带后缀）：优先 MediaItem extras（DFF/DSF 等补录行），否则按 mediaId 查 MediaStore
    var fileName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(displayed?.mediaId) {
        val mediaId = displayed?.mediaId ?: return@LaunchedEffect
        val appContext = context.applicationContext
        fileName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            displayed?.mediaMetadata?.extras
                ?.getString(LocalAudioLibrary.DisplayNameExtraKey)
                ?.takeIf { it.isNotBlank() }
                ?: com.smartisan.music.data.tags.SongTagEditor.resolveFileName(appContext, mediaId)
        }
    }
    TrackInfoEditOverlay(
        visible = visible && showEdit,
        item = displayed,
        onDismiss = { showEdit = false },
        onSaved = { saved ->
            showEdit = false
            onDismissRequest()
            onSaved(saved)
        },
    )
    SmartisanAnimatedSheet(
        if (visible) !showEdit else false,
        onDismissRequest,
        modifier,
        scrim = colorResource(R.color.transparent_black),
        hiddenPanelAlpha = 0.92f,
    ) {
        SmartisanMenuTitleBar(stringResource(R.string.action_song_info), onDismissRequest)
        displayed?.let { track ->
            // 1.2.26：DFF/DSF/APE 的时长与规格由后台探测写入 FormatCache，MediaItem 的 extras
            // 里可能仍是空的（服务端刻意不 replaceMediaItem，避免断音）。这里查一次缓存兜底，
            // 否则「歌曲信息」永远显示「—」。
            val formatRevision by PlaybackFormatBus.revision.collectAsState()
            val cachedSpec by
                produceState<AudioFormatProbe.Result?>(null, track.mediaId, formatRevision) {
                    value =
                        withContext(Dispatchers.IO) {
                            val uri = track.localConfiguration?.uri?.toString()
                                ?: return@withContext null
                            runCatching { FormatCache.get(uri, 0L) }.getOrNull()
                        }
                }
            fun Long.orCached(cached: Int): Long =
                if (this > 0L) this else cached.toLong()
            val shownDurationMs =
                (track.mediaMetadata.durationMs ?: 0L).orCached(
                    cachedSpec?.durationMs?.toInt() ?: 0,
                )
            val shownSampleRate =
                (track.extraLong(LocalAudioLibrary.SampleRateHzExtraKey) ?: 0L)
                    .orCached(cachedSpec?.sampleRateHz ?: 0)
            val shownBitDepth =
                (track.extraLong(LocalAudioLibrary.BitsPerSampleExtraKey) ?: 0L)
                    .orCached(cachedSpec?.bitsPerSample ?: 0)
            val shownBitrate =
                (track.extraLong(LocalAudioLibrary.BitrateBpsExtraKey) ?: 0L)
                    .orCached(cachedSpec?.bitrateBps ?: 0)
            Column(
                Modifier.fillMaxWidth()
                    .cardTextureMonet(R.drawable.menu_dialog_background)
                    .padding(horizontal = dimensionResource(R.dimen.menu_dialog_horizontal_distance))
                    .padding(top = 10.dp, bottom = 18.dp),
            ) {
                InfoRow(stringResource(R.string.info_label_title), track.mediaMetadata.title?.toString() ?: "—")
                InfoRow(stringResource(R.string.info_label_artist), track.mediaMetadata.artist?.toString() ?: "—")
                InfoRow(stringResource(R.string.info_label_album), track.mediaMetadata.albumTitle?.toString() ?: "—")
                InfoRow(stringResource(R.string.info_label_duration), formatDuration(shownDurationMs))
                InfoRow(stringResource(R.string.info_label_format), track.trackFormatLabel(LocalContext.current))
                InfoRow(
                    stringResource(R.string.info_label_sample_rate),
                    formatSampleRate(shownSampleRate),
                )
                InfoRow(
                    stringResource(R.string.info_label_bit_depth),
                    formatBitDepth(shownBitDepth),
                )
                InfoRow(
                    stringResource(R.string.info_label_bitrate),
                    formatBitrate(shownBitrate),
                )
                InfoRow(
                    stringResource(R.string.info_label_file_size),
                    formatFileSize(LocalContext.current, track.extraLong(LocalAudioLibrary.FileSizeExtraKey)),
                )
                InfoRow(
                    stringResource(R.string.info_label_folder),
                    track.mediaMetadata.extras
                        ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                        ?.takeIf { it.isNotBlank() }
                        ?: "—",
                )
                InfoRow(
                    stringResource(R.string.info_label_file_name),
                    fileName ?: "—",
                )
                SmartisanDialogButton(
                    stringResource(R.string.edit_song_info),
                    { showEdit = true },
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
internal fun AudioOutputOverlay(
    visible: Boolean,
    item: MediaItem?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retained by remember { mutableStateOf(item) }
    LaunchedEffect(visible, item) { if (visible) retained = item }
    val displayed = if (visible) item else retained
    val outputSpec by AudioOutputMonitor.spec.collectAsState()
    // [1.4.20·P0] 增益可视化：订阅 DSP 实时统计，面板打开时驱动节流发布。
    val gainStats by com.smartisan.music.playback.GainMonitor.stats.collectAsState()
    val gainEnabled = com.smartisan.music.playback.GainMonitor.enabled
    // [1.4.21·P2] 音量平滑接力读回快照（服务未注入 handoff 时为 null，隐藏该行）
    val handoffFlow = com.smartisan.music.playback.UsbVolume.handoff?.snapshot
    val volHandoff = handoffFlow?.collectAsState()?.value
    LaunchedEffect(visible) {
        while (visible) {
            com.smartisan.music.playback.GainMonitor.publishIfDue()
            kotlinx.coroutines.delay(250L)
        }
    }
    SmartisanAnimatedSheet(
        visible,
        onDismissRequest,
        modifier,
        scrim = colorResource(R.color.transparent_black),
        hiddenPanelAlpha = 0.92f,
    ) {
        SmartisanMenuTitleBar(stringResource(R.string.audio_output), onDismissRequest)
        displayed?.let { track ->
            val context = LocalContext.current
            val inputSampleRate = track.extraLong(LocalAudioLibrary.SampleRateHzExtraKey)
            val inputBitDepth = track.extraLong(LocalAudioLibrary.BitsPerSampleExtraKey)
            val inputChannels = track.extraLong(LocalAudioLibrary.ChannelCountExtraKey)
            val spec = outputSpec
            Column(
                Modifier.fillMaxWidth()
                    .cardTextureMonet(R.drawable.menu_dialog_background)
                    .padding(horizontal = dimensionResource(R.dimen.menu_dialog_horizontal_distance))
                    .padding(top = 10.dp, bottom = 18.dp),
            ) {
                InfoRow(stringResource(R.string.audio_output_input_sample_rate), formatSampleRate(inputSampleRate))
                InfoRow(
                    stringResource(R.string.audio_output_output_sample_rate),
                    spec?.sampleRateHz?.let { formatSampleRate(it.toLong()) } ?: "—",
                )
                InfoRow(stringResource(R.string.audio_output_input_bit_depth), formatBitDepth(inputBitDepth))
                InfoRow(stringResource(R.string.audio_output_output_bit_depth), spec?.bitDepthLabel ?: "—")
                InfoRow(stringResource(R.string.audio_output_output_port), outputPortLabel(context))
                InfoRow(
                    stringResource(R.string.audio_output_input_channels),
                    if (inputChannels != null) {
                        context.getString(R.string.output_channels_count, inputChannels.toInt())
                    } else {
                        "—"
                    },
                )
                InfoRow(
                    stringResource(R.string.audio_output_output_channels),
                    spec?.let { context.getString(R.string.output_channels_count, it.channelCount) } ?: "—",
                )
                InfoRow(stringResource(R.string.audio_output_format), track.trackFormatLabel(context))
                // [1.4.20·P0] 增益结构可视化（开关由均衡器页控制，打开才显示）
                if (gainEnabled) {
                    InfoRow(
                        stringResource(R.string.gain_peak),
                        if (gainStats.hasSignal) com.smartisan.music.playback.GainMonitor.formatDb(gainStats.peakDbfs)
                        else stringResource(R.string.gain_no_signal),
                    )
                    InfoRow(
                        stringResource(R.string.gain_headroom),
                        if (gainStats.hasSignal) com.smartisan.music.playback.GainMonitor.formatDb(gainStats.headroomDb)
                        else stringResource(R.string.gain_no_signal),
                    )
                    InfoRow(
                        stringResource(R.string.gain_rms),
                        if (gainStats.hasSignal) com.smartisan.music.playback.GainMonitor.formatDb(gainStats.rmsDbfs)
                        else stringResource(R.string.gain_no_signal),
                    )
                    InfoRow(
                        stringResource(R.string.gain_clipped),
                        stringResource(R.string.gain_clipped_value, gainStats.clippedSamples),
                    )
                }
                // [1.4.21·P2] 音量接力读回：用户可见「目标→实际→是否生效」
                if (volHandoff != null) {
                    val stateLabel = when {
                        volHandoff.ramping -> stringResource(R.string.gain_vol_handoff_pending)
                        volHandoff.readback < 0f -> stringResource(R.string.gain_vol_handoff_na)
                        kotlin.math.abs(volHandoff.readback - volHandoff.target) <= 0.02f ->
                            stringResource(R.string.gain_vol_handoff_ok)
                        else -> stringResource(R.string.gain_vol_handoff_na)
                    }
                    InfoRow(
                        stringResource(R.string.gain_vol_handoff),
                        stringResource(
                            R.string.gain_vol_handoff_value,
                            (volHandoff.target * 100f + 0.5f).toInt(),
                            ((if (volHandoff.readback >= 0f) volHandoff.readback else volHandoff.applied) * 100f + 0.5f).toInt(),
                            stateLabel,
                        ),
                    )
                }
                if (spec != null && (spec.isCompressedPassthrough || spec.sampleRateHz > 48_000)) {
                    InfoRow(
                        stringResource(R.string.audio_output_effects_note),
                        context.getString(R.string.audio_output_effects_note_value),
                    )
                }
            }
        }
    }
}

/** 把相册图片压成适合内嵌的封面（最长边 1024、JPEG 92）：避免把十几 MB 的原图塞进音频文件 */
private fun compressImageFromUri(
    context: Context,
    uri: android.net.Uri,
): Pair<android.graphics.Bitmap, ByteArray>? =
    runCatching {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val opts =
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
        val bitmap =
            resolver.openInputStream(uri)!!.use { input ->
                android.graphics.BitmapFactory.decodeStream(input, null, opts)
            } ?: return null
        bitmap to compressForTag(bitmap)
    }.getOrNull()

private fun compressForTag(bitmap: android.graphics.Bitmap): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)
    return output.toByteArray()
}

/**
 * 编辑歌曲信息弹层：标题 / 歌手 / 专辑 / 专辑封面，保存时直接写回歌曲文件本身。
 * Android 10+ 对非本应用写入的音频文件需要系统「写入授权」弹窗（createWriteRequest），
 * 旧版本则需要 WRITE_EXTERNAL_STORAGE 运行时权限——授权通过后才真正落盘。
 */
@Composable
private fun TrackInfoEditOverlay(
    visible: Boolean,
    item: MediaItem?,
    onDismiss: () -> Unit,
    onSaved: (SavedTrackTags) -> Unit,
) {
    var retained by remember { mutableStateOf(item) }
    LaunchedEffect(visible, item) { if (visible) retained = item }
    val track = if (visible) item else retained
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var resolvedPath by remember { mutableStateOf<String?>(null) }
    // 1.2.19：保存写授权要用文件自己的 Uri（补录行是 Files 表 Uri，不是 audio/media）
    var resolvedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var currentArtwork by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var newArtwork by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf<String?>(null) }
    var hintIsError by remember { mutableStateOf(false) }
    var pendingSaveAfterGrant by remember { mutableStateOf(false) }

    // 打开时载入文件里的现有标签与封面
    LaunchedEffect(visible, track?.mediaId) {
        if (!visible) return@LaunchedEffect
        hintText = null
        newArtwork = null
        val mediaId = track?.mediaId ?: return@LaunchedEffect
        val loaded =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val resolved =
                    com.smartisan.music.data.tags.SongTagEditor.resolve(context, mediaId)
                        ?: return@withContext null
                val path = resolved.second
                Triple(
                    resolved.first,
                    path,
                    com.smartisan.music.data.tags.SongTagEditor.load(path) to
                        com.smartisan.music.data.tags.SongTagEditor.loadArtworkBytes(path),
                )
            }
        resolvedUri = loaded?.first
        resolvedPath = loaded?.second
        val tags = loaded?.third?.first
        title = tags?.title ?: track.mediaMetadata.title?.toString().orEmpty()
        artist = tags?.artist ?: track.mediaMetadata.artist?.toString().orEmpty()
        album = tags?.album ?: track.mediaMetadata.albumTitle?.toString().orEmpty()
        currentArtwork =
            loaded?.third?.second?.let { bytes ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
    }

    // 1) 先定义写盘逻辑（局部函数，后面的 launcher 回调可以引用它）
    fun doWrite() {
        val path = resolvedPath ?: return
        saving = true
        hintText = null
        scope.launch {
            val artworkBytes =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    newArtwork?.let { compressForTag(it) }
                }
            val result =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.smartisan.music.data.tags.SongTagEditor.write(
                        path = path,
                        title = title,
                        artist = artist,
                        album = album,
                        artworkBytes = artworkBytes,
                    )
                }
            saving = false
            result
                .onSuccess {
                    // 0) 写入应用内标签覆盖层：让各列表/播放条立即显示新值，
                    //    不等媒体扫描器（MediaStore.update 在部分系统对
                    //    ARTIST/ALBUM 静默失败，覆盖层是确定性兜底）
                    val mediaId = track?.mediaId.orEmpty()
                    if (mediaId.isNotBlank()) {
                        val stableKey = track?.mediaMetadata?.extras
                            ?.getString(com.smartisan.music.playback.LocalAudioLibrary.StableKeyExtraKey)
                        runCatching {
                            com.smartisan.music.data.tags.TagOverrideStore.put(
                                context.applicationContext,
                                mediaId,
                                stableKey,
                                title,
                                artist,
                                album,
                            )
                        }
                    }
                    // 1) 直接把新标题/歌手/专辑写进 MediaStore：同步生效，歌曲页等列表
                    //    重查时立刻能拿到新值，不必等媒体扫描器异步完成
                    if (mediaId.isNotBlank()) {
                        runCatching {
                            val uri = android.net.Uri.withAppendedPath(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                mediaId,
                            )
                            val values = android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Audio.Media.TITLE, title)
                                put(android.provider.MediaStore.Audio.Media.ARTIST, artist)
                                put(android.provider.MediaStore.Audio.Media.ALBUM, album)
                            }
                            context.contentResolver.update(uri, values, null, null)
                        }
                        // 2) 通知媒体扫描器重新提取文件元数据，保证与文件内标签长期一致
                        runCatching {
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(path),
                                null,
                                null,
                            )
                        }
                        // 3) 回传新标签：壳层同步播放队列（小播放条/大播放页立即改名）并刷新列表
                        onSaved(
                            SavedTrackTags(
                                mediaId = mediaId,
                                title = title,
                                artist = artist,
                                album = album,
                            )
                        )
                    }
                }
                .onFailure { error ->
                    hintIsError = true
                    hintText =
                        context.getString(R.string.edit_song_info_failed, error.message ?: "—")
                }
        }
    }

    // 2) 系统写入授权 / 旧版权限的 launcher（回调里调用 doWrite，已在其前声明）
    val writeRequestLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (pendingSaveAfterGrant) {
                    pendingSaveAfterGrant = false
                    doWrite()
                }
            } else {
                pendingSaveAfterGrant = false
                hintIsError = true
                hintText = context.getString(R.string.edit_song_info_need_permission)
            }
        }

    val legacyWritePermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                if (pendingSaveAfterGrant) {
                    pendingSaveAfterGrant = false
                    doWrite()
                }
            } else {
                pendingSaveAfterGrant = false
                hintIsError = true
                hintText = context.getString(R.string.edit_song_info_need_permission)
            }
        }

    // 3) 相册选图
    val chooseCoverLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val compressed =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        compressImageFromUri(context, uri)
                    }
                if (compressed != null) {
                    newArtwork = compressed.first
                }
            }
        }

    // 4) 保存入口：先拿写权限再落盘
    fun requestSave() {
        if (!saving && resolvedPath != null) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // 1.2.19：补录行（DFF/DSF/WV…）拿不到 audio/media Uri 时，
                // 用文件真实路径向系统申请写授权，否则编辑这些格式会静默失败
                val uri = resolvedUri ?: return
                pendingSaveAfterGrant = true
                runCatching {
                    val pender =
                        android.provider.MediaStore.createWriteRequest(
                            context.contentResolver,
                            listOf(uri),
                        )
                    writeRequestLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(
                            pender.intentSender
                        ).build()
                    )
                }.onFailure {
                    pendingSaveAfterGrant = false
                    hintIsError = true
                    hintText = context.getString(R.string.edit_song_info_failed, it.message ?: "—")
                }
            } else {
                val granted =
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) {
                    doWrite()
                } else {
                    pendingSaveAfterGrant = true
                    legacyWritePermissionLauncher.launch(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                }
            }
        }
    }

    SmartisanAnimatedSheet(
        visible,
        onDismiss,
        scrim = colorResource(R.color.transparent_black),
        hiddenPanelAlpha = 0.92f,
    ) {
        SmartisanMenuTitleBar(
            stringResource(R.string.edit_song_info),
            onDismiss,
            onConfirm = ::requestSave,
            confirmEnabled = !saving,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .cardTextureMonet(R.drawable.menu_dialog_background)
                .padding(horizontal = dimensionResource(R.dimen.menu_dialog_horizontal_distance))
                .padding(top = 10.dp, bottom = 18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 封面行：当前封面 / 新选封面预览 + 选择图片按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    stringResource(R.string.edit_song_info_cover),
                    Modifier.width(92.dp),
                    style =
                        TextStyle(
                            color = colorResource(R.color.playing_subtitle_text),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
                val preview = newArtwork ?: currentArtwork
                Box(
                    Modifier
                        .size(72.dp)
                        .smartisanPainterBackground(
                            rememberSmartisanDrawablePainter(R.drawable.noalbumcover_120)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                SmartisanDialogButton(
                    stringResource(R.string.edit_song_info_choose_cover),
                    { chooseCoverLauncher.launch("image/*") },
                    Modifier.weight(1f).height(36.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            TagEditField(stringResource(R.string.info_label_title), title, { title = it })
            TagEditField(stringResource(R.string.info_label_artist), artist, { artist = it })
            TagEditField(stringResource(R.string.info_label_album), album, { album = it })
            if (hintText != null) {
                BasicText(
                    hintText!!,
                    Modifier.padding(top = 10.dp),
                    style =
                        TextStyle(
                            color =
                                if (hintIsError) colorResource(R.color.playing_red)
                                else colorResource(R.color.add_nav_text_color),
                            fontSize = 12.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
            }
        }
    }
}

/** 编辑弹层的单行输入框 */
@Composable
private fun TagEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            Modifier.width(92.dp),
            style =
                TextStyle(
                    color = colorResource(R.color.playing_subtitle_text),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
        )
        Box(
            Modifier.weight(1f).heightIn(min = 34.dp).smartisanPainterBackground(
                rememberSmartisanDrawablePainter(R.drawable.search_field)
            ).padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = infoRowStyle(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
