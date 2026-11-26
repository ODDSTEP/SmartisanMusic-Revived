package com.smartisan.music.diagnostics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.smartisan.music.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [1.4.24·免 ADB 环境取证] 导出日志时实时生成一份"当时这台机器长什么样"的快照。
 *
 * 为什么不能只靠 app log：用户报「没声音 / 独占没生效 / 声音怪」时，九成取决于
 * 运行环境（系统媒体音量、当前音频设备、USB 权限、采样率属性、剩余存储），
 * 而这些在崩溃或重启后无法复原。快照在**导出那一刻**采集，避免事后猜。
 */
internal object DiagnosticsSnapshot {

    /** 写入 logs/env-<stamp>.txt 并返回该文件（失败返回 null）。 */
    fun write(context: Context): File? {
        val dir = AppLog.logsDirectory() ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "env-$stamp.txt")
        val text = runCatching { build(context) }.getOrElse { "生成失败: ${it.javaClass.simpleName} ${it.message}" }
        return runCatching {
            file.writeText(text)
            file
        }.getOrNull()
    }

    private fun build(context: Context): String = buildString {
        appendLine("==== 环境快照（导出日志时实时采集） ====")
        appendLine("time=${Date()} uptimeMs=${SystemClock.elapsedRealtime()}")
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("app=${pkg?.versionName} (${pkg?.longVersionCode}) pkg=${context.packageName}")
        appendLine("build=${BuildConfig.BUILD_LABEL} engine=${BuildConfig.PLAYBACK_ENGINE}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) board=${Build.BOARD}")
        appendLine("rom=${Build.DISPLAY} incremental=${Build.VERSION.INCREMENTAL}")
        appendLine("sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("locale=${Locale.getDefault()} timezone=${java.util.TimeZone.getDefault().id}")
        appendLine(
            "lowRam=" + runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
            }.getOrNull(),
        )

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        appendLine("---- AudioManager ----")
        if (am == null) {
            appendLine("AudioManager=null")
        } else {
            appendLine("outputSampleRate=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}")
            appendLine("outputFramesPerBuffer=${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
            appendLine("isMusicActive=${am.isMusicActive} mode=${am.mode} ringerMode=${am.ringerMode}")
            for (stream in intArrayOf(
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.STREAM_SYSTEM,
            )) {
                val min = runCatching { am.getStreamMinVolume(stream) }.getOrDefault(-1)
                val max = runCatching { am.getStreamMaxVolume(stream) }.getOrDefault(-1)
                val cur = runCatching { am.getStreamVolume(stream) }.getOrDefault(-1)
                val muted = runCatching { am.isStreamMute(stream) }.getOrDefault(false)
                appendLine("stream[$stream] vol=$cur/$max min=$min mute=$muted")
            }
            // 音频设备全量列表：判断「切到 USB 后系统里还有没有这个设备」的关键证据。
            val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_ALL) }.getOrNull().orEmpty()
            appendLine("audioDevices=${devices.size}")
            devices.forEach { d ->
                appendLine(
                    "  dev type=${d.type}(${typeName(d.type)}) id=${d.id} name=${d.productName} " +
                        "src=${d.isSource} sink=${d.isSink} " +
                        "sampleRates=${d.sampleRates.joinToString(",")} channelCounts=${d.channelCounts.joinToString(",")}",
                )
            }
        }

        appendLine("---- USB ----")
        runCatching {
            val usb = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            if (usb == null) {
                appendLine("UsbManager=null")
            } else {
                val list = usb.deviceList.values
                appendLine("usbDevices=${list.size}")
                list.forEach { d ->
                    appendLine(
                        "  dev ${d.deviceName} vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)} " +
                            "cls=${d.deviceClass}/${d.deviceSubclass} perm=${usb.hasPermission(d)} " +
                            "ifaces=${d.interfaceCount}",
                    )
                    for (i in 0 until d.interfaceCount) {
                        val itf = d.getInterface(i)
                        appendLine("    iface[$i] cls=${itf.interfaceClass} sub=${itf.interfaceSubclass} proto=${itf.interfaceProtocol}")
                    }
                }
            }
        }.onFailure { appendLine("USB 读取失败: ${it.message}") }

        appendLine("---- 存储 ----")
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            appendLine("dataFreeMB=${stat.availableBytes / 1024 / 1024} dataTotalMB=${stat.totalBytes / 1024 / 1024}")
            val logs = AppLog.logsDirectory()
            appendLine("logsDir=${logs?.absolutePath} logsSizeKB=${logs?.listFiles()?.sumOf { it.length() }?.div(1024)}")
        }.onFailure { appendLine("存储读取失败: ${it.message}") }

        appendLine("---- 关键开关（只读快照；播放服务内的实时值见同包 State 帧） ----")
        runCatching {
            val ctx = context.applicationContext
            appendLine("usbZeroLoss=${com.smartisan.music.data.settings.UsbZeroLossStore.current()}")
            appendLine("usbNewEngine=${com.smartisan.music.data.settings.UsbEngineStore.current()}")
            appendLine("monet=${com.smartisan.music.data.settings.MonetStore.current()}")
            appendLine("engineMode=${com.smartisan.music.playback.DecodeRouter.engineId}")
            appendLine("usbGuardDisabled=${com.smartisan.music.playback.UsbExclusiveGuard.isDisabled(ctx)}")
            appendLine("usbDacActive=${com.smartisan.music.playback.UsbDac.isActive}")
        }.onFailure { appendLine("开关快照失败: ${it.message}") }

        appendLine("==== 快照结束 ====")
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        else -> "TYPE_$type"
    }
}
