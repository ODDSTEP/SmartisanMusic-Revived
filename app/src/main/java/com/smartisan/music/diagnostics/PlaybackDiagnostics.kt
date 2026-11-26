package com.smartisan.music.diagnostics

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import com.smartisan.music.R
import com.smartisan.music.playback.LocalAudioLibrary

/**
 * 播放排障辅助。
 *
 * 上一版日志里只有"启动"和"格式缓存"两类记录，出现"某首歌播不出来"时
 * 既不知道它是什么文件、也不知道卡在链路的哪一段。这里补齐三件事：
 *  1. [trackLine] —— 曲目的完整身份（id / 路径 / 扩展名 / 探测到的规格）；
 *  2. [fingerprint] —— 文件头十六进制，容器被误判时能一眼看出真实格式；
 *  3. [errorLine] / [userMessageRes] —— 错误码到"人话"的映射。
 */
object PlaybackDiagnostics {

    /** 一行说清一首歌是谁、什么规格。 */
    fun trackLine(item: MediaItem?): String {
        if (item == null) return "item=null"
        val extras = item.mediaMetadata.extras
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        val path = extras?.getString(LocalAudioLibrary.RelativePathExtraKey).orEmpty()
        val ext = uri.substringAfterLast('.', "").takeIf { it.length in 1..6 }.orEmpty()
        val sampleRate = extras?.getInt(LocalAudioLibrary.SampleRateHzExtraKey) ?: 0
        val bits = extras?.getInt(LocalAudioLibrary.BitsPerSampleExtraKey) ?: 0
        val bitrate = extras?.getInt(LocalAudioLibrary.BitrateBpsExtraKey) ?: 0
        val size = extras?.getLong(LocalAudioLibrary.FileSizeExtraKey) ?: 0L
        val codec = extras?.getString(LocalAudioLibrary.FormatExtraKey).orEmpty()
        return "id=${item.mediaId} ext=$ext codec=$codec src=${sampleRate}Hz/${bits}bit/${bitrate}bps" +
            " size=$size mime=${item.localConfiguration?.mimeType} path=$path uri=$uri"
    }

    /**
     * 读取文件头并转成十六进制。容器/编码被误判时，这一行能直接看出真实格式
     * （例如 `52 49 46 46 | RIFF` 是 WAV，`66 4C 61 43 | fLaC` 是 FLAC）。
     */
    fun fingerprint(context: Context, item: MediaItem?): String {
        val uri = item?.localConfiguration?.uri ?: return "no uri"
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val head = ByteArray(32)
                val read = stream.read(head)
                if (read <= 0) "empty" else AppLog.hex(head.copyOf(read))
            } ?: "open failed"
        }.getOrElse { "read failed: ${it.message}" }
    }

    /** 错误码 + 完整 cause 链，一行足够定位。 */
    fun errorLine(error: PlaybackException): String {
        val chain = StringBuilder()
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 4) {
            chain.append("\n  caused by: ${cause.javaClass.simpleName}: ${cause.message}")
            cause = cause.cause
            depth++
        }
        return "code=${error.errorCode}(${error.errorCodeName}) msg=${error.message}" +
            " ts=${error.timestampMs}$chain"
    }

    /** 给用户看的失败原因。[item] 用于识别特殊格式的容器（如 APE）给出更明确的提示。 */
    fun userMessageRes(error: PlaybackException, item: MediaItem? = null): Int {
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
            isApeFile(item)
        ) {
            // Android 全系统（MediaCodec/ExoPlayer）都没有 APE 解码器，指明原因比"格式不支持"更有用
            return R.string.playback_error_ape_unsupported
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> R.string.playback_error_unsupported_format
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                -> R.string.playback_error_decode_failed
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                -> R.string.playback_error_output_failed
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                -> R.string.playback_error_io
            else -> R.string.playback_error_generic
        }
    }

    private fun isApeFile(item: MediaItem?): Boolean {
        val uri = item?.localConfiguration?.uri?.toString()?.lowercase() ?: return false
        val mime = item.localConfiguration?.mimeType?.lowercase().orEmpty()
        return uri.substringBefore('?').endsWith(".ape") ||
            mime.contains("ape") ||
            mime.contains("x-monkeys-audio")
    }
}
