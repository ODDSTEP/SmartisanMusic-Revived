# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留行号，便于从日志里定位崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod,AnnotationDefault,*Annotation*

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---------- Media3 / ExoPlayer ----------
-dontwarn androidx.media3.**

# ---------- Kotlin 协程 ----------
-dontwarn kotlinx.coroutines.**

# ---------- 应用自身（避免混淆导致堆栈难以定位） ----------
-keep class com.smartisan.music.diagnostics.** { *; }
-keep class com.smartisan.music.platform.media.** { *; }

# ---------- 应用入口与跨进程组件 ----------
-keep class com.smartisan.music.MusicApplication { *; }
-keep class com.smartisan.music.playback.PlaybackService { *; }

# ---------- BASS（JNI 结构体 + native 方法）----------
# [1.4.29·R128] 必须整包 keep，且必须是 -keep（不是 -keepclasseswithmembernames）。
# 踩过的坑：AGP 默认规则 -keepclasseswithmembernames class * { native <methods>; }
# 是 allowshrinking，只禁止改名、允许删除「没人调用」的 native 方法；
# 而 com.un4seen.bass 的 BASS_CHANNELINFO / BASS_BFX_BQF / BASS_MIXER_NODE
# 是 native 侧用 GetFieldID 回写的纯数据类，Java 侧从不读字段 → R8 判定字段无用，
# 整个结构体被删空（1.4.28 实测 dex：BASS_CHANNELINFO 0 字段，freq/chans 字符串出现 0 次）。
# 结果就是 BASS_ChannelGetInfo 里 SetIntField(null) → JNI abort，开智能过渡点歌必崩。
# BASS 类总量很小，整包 keep 对体积的影响可忽略；R130 会在构建后解包 dex 复验。
-keep class com.un4seen.bass.** { *; }

# ---------- USB 独占（UAC 直出）----------
# JNI 符号是按「包名+类名+方法名」拼出来的，R8 一旦改名 UsbDac 就再也找不到
# Java_com_smartisan_music_playback_UsbDac_native*，表现为"独占打开失败"。
-keep class com.smartisan.music.playback.UsbDac { *; }
-keep class com.smartisan.music.playback.UsbDac$Session { *; }
-keepclasseswithmembernames class com.smartisan.music.playback.UsbDac {
    native <methods>;
}

# ---------- 歌曲标签读写（jaudiotagger）----------
# jaudiotagger 是桌面版 Java 库：Artwork.getImage() 会引用 java.awt / javax.imageio
# 这些类在 Android 上不存在。我们只读 binaryData / 写 binaryData，从不调用 getImage()，
# 所以这里只需要让 R8 别把"引用了不存在的类"当错误。
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.jaudiotagger.**
# 反射与 SPI 相关：标签实现靠 TagOptionSingleton + 反射实例化，避免被裁剪后写标签失败
-keep class org.jaudiotagger.** { *; }
-keepclassmembers class org.jaudiotagger.** { *; }
