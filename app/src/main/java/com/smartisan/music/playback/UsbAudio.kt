package com.smartisan.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.smartisan.music.diagnostics.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB 音频设备信息。
 *
 * [uacVersion] / [bitDepths] / [maxChannels] 来自 USB 配置描述符的直接解析
 * （[UsbDeviceConnection.getRawDescriptors]），只有拿到 USB 授权才有值；
 * 没授权时退化为 AudioManager 报告的类型与名称。
 */
data class UsbAudioDevice(
    val audioDeviceInfo: AudioDeviceInfo,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val uacVersion: Int,
    val bitDepths: List<Int>,
    val maxChannels: Int,
) {
    val isUac2: Boolean
        get() = uacVersion >= 2

    val specSummary: String
        get() {
            val protocol = when {
                isUac2 -> "UAC2"
                uacVersion == 1 -> "UAC1"
                else -> "UAC"
            }
            val depths = bitDepths.takeIf { it.isNotEmpty() }
                ?.joinToString("/") { "$it bit" }
                ?: "—"
            return "$protocol · $depths · ${maxChannels.takeIf { it > 0 } ?: 2}ch"
        }
}

/**
 * USB 音频设备在位监视。
 *
 * 判定设备是否还在**只能用 UsbManager / AudioDeviceCallback**，不能用 AudioManager.getDevices：
 * 一旦音频路由被钉死在某块 USB DAC 上，系统侧的行为在不同 ROM 上并不一致，
 * 用 getDevices 会把"正在独占"误判成"已拔出"。
 */
object UsbAudioMonitor {

    private val _devices = MutableStateFlow<List<UsbAudioDevice>>(emptyList())
    val devices: StateFlow<List<UsbAudioDevice>> = _devices.asStateFlow()

    @Volatile
    private var attached = false
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            AppLog.i("Usb", "audio devices added: ${describe(addedDevices)}")
            // [v18·#115] 补输入设备（麦克风）状态：判定"关闭独占后麦克风失效、需重插拔才恢复"。
            logInputDevices("added")
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            AppLog.i("Usb", "audio devices removed: ${describe(removedDevices)}")
            logInputDevices("removed")
            refresh()
        }
    }

    /**
     * [v18·#115] 枚举并打印当前所有输入设备（麦克风）的类型/名称，记录设备变化后麦克风是否可见。
     */
    private fun logInputDevices(event: String) {
        val am = audioManager ?: return
        runCatching {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val desc = inputs.joinToString("; ") { d ->
                "type=${d.type} source=${d.isSource} name=${d.productName}"
            }
            AppLog.i("Usb", "op=麦克风状态 event=$event 输入设备数=${inputs.size} [$desc]")
        }.onFailure { t ->
            AppLog.w("Usb", "op=麦克风状态 event=$event 枚举失败: ${t.message}")
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (
                action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                AppLog.i("Usb", "usb broadcast $action")
                refresh()
            }
        }
    }

    fun start(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (attached) {
            refresh()
            return
        }
        audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                application,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { error ->
            AppLog.w("Usb", "register usb receiver failed: ${error.message}")
        }
        attached = true
        refresh()
    }

    fun stop() {
        val application = appContext ?: return
        if (!attached) return
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { application.unregisterReceiver(usbReceiver) }
        attached = false
    }

    fun refresh() {
        val application = appContext ?: return
        val manager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        val list = outputs.filter(::isUsbAudioOutput).map { info -> describe(application, info) }
        if (list.isEmpty() && _devices.value.isNotEmpty()) {
            AppLog.i("Usb", "no usb audio device")
        }
        _devices.value = list
    }

    private fun isUsbAudioOutput(info: AudioDeviceInfo): Boolean = when (info.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> true
        else -> false
    }

    private fun describe(context: Context, info: AudioDeviceInfo): UsbAudioDevice {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val usbDevice = usbManager?.deviceList?.values?.firstOrNull { device ->
            // AudioDeviceInfo 不暴露 vendor/product，用名字做一次弱匹配；匹配不上就按名字展示。
            info.productName.toString().contains(device.productName ?: "", ignoreCase = true)
        }
        var uacVersion = 0
        var depths = emptyList<Int>()
        var channels = 0
        if (usbDevice != null && usbManager.hasPermission(usbDevice)) {
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            try {
                connection = usbManager.openDevice(usbDevice)
                val raw = connection?.rawDescriptors
                if (raw != null) {
                    val parsed = parseUacDescriptors(raw)
                    uacVersion = parsed.first
                    depths = parsed.second
                    channels = parsed.third
                }
            } catch (error: Throwable) {
                AppLog.w("Usb", "descriptor read failed: ${error.message}")
            } finally {
                runCatching { connection?.close() }
            }
        }
        val name = info.productName.toString().ifBlank {
            usbDevice?.productName ?: "USB audio"
        }
        return UsbAudioDevice(
            audioDeviceInfo = info,
            name = name,
            vendorId = usbDevice?.vendorId ?: 0,
            productId = usbDevice?.productId ?: 0,
            uacVersion = uacVersion,
            bitDepths = depths,
            maxChannels = channels,
        )
    }

    /**
     * [v19·USB日志补全·C设备路由] 设备回调记全字段：type/productName/address/channelCounts/
     * sampleRates/encodings（联网核对：插拔"连接状态≠路由就绪"要靠这些字段判断）。
     * 各字段 API 版本不一，逐项 runCatching 保护，取不到就标 n/a，不影响回调。
     */
    private fun describe(devices: Array<out AudioDeviceInfo>): String =
        devices.joinToString { d ->
            fun <T> safe(block: () -> T): String = runCatching { block().toString() }.getOrDefault("n/a")
            "type=${d.type} name=${d.productName} addr=${safe { d.address }} " +
                "ch=${safe { d.channelCounts?.joinToString(",") }} " +
                "rate=${safe { d.sampleRates?.joinToString(",") }} " +
                "enc=${safe { d.encodings?.joinToString(",") }}"
        }

    /**
     * 解析 USB 配置描述符里的音频类信息。
     *
     * 关键：**UAC2 与 UAC1 的声道数字段位置相反** —— UAC2 的 Format Type I 固定 6 字节、
     * 没有 bNrChannels，声道数在 AS General（subtype 1）的 offset 10；UAC1 恰好相反。
     * 读反会让所有 UAC2 声卡解析失败（参考实现踩过的 P0）。
     */
    internal fun parseUacDescriptors(raw: ByteArray): Triple<Int, List<Int>, Int> {
        var uacVersion = 0
        var maxChannels = 0
        val depths = sortedSetOf<Int>()
        var offset = 0
        var interfaceProtocol = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xFF
            if (length < 2) break
            val type = raw[offset + 1].toInt() and 0xFF
            if (type == DescriptorInterface && length >= 9) {
                val clazz = raw[offset + 5].toInt() and 0xFF
                val protocol = raw[offset + 7].toInt() and 0xFF
                interfaceProtocol = if (clazz == ClassAudio) protocol else 0
                if (clazz == ClassAudio) {
                    val version = when (protocol) {
                        ProtocolUac2 -> 2
                        ProtocolUac1 -> 1
                        else -> 0
                    }
                    if (version > uacVersion) uacVersion = version
                }
            } else if (type == DescriptorCsInterface && length >= 6 && interfaceProtocol != 0) {
                val subtype = raw[offset + 2].toInt() and 0xFF
                when (subtype) {
                    // AS General
                    AsGeneral -> if (interfaceProtocol == ProtocolUac2 && length >= 11) {
                        maxChannels = maxOf(maxChannels, raw[offset + 10].toInt() and 0xFF)
                    }
                    // Format Type I
                    FormatType -> if (interfaceProtocol == ProtocolUac2) {
                        // 6 字节：bFormatType@3 / bSubslotSize@4 / bBitResolution@5
                        depths += raw[offset + 5].toInt() and 0xFF
                    } else if (length >= 8) {
                        // UAC1：bNrChannels@4 / bSubslotSize@5 / bBitResolution@6
                        maxChannels = maxOf(maxChannels, raw[offset + 4].toInt() and 0xFF)
                        depths += raw[offset + 6].toInt() and 0xFF
                    }
                }
            }
            offset += length
        }
        return Triple(uacVersion, depths.filter { it in 8..32 }, maxChannels)
    }

    private const val DescriptorInterface = 0x04
    private const val DescriptorCsInterface = 0x24
    private const val ClassAudio = 0x01
    private const val ProtocolUac1 = 0x10
    private const val ProtocolUac2 = 0x20
    private const val AsGeneral = 0x01
    private const val FormatType = 0x02
}
