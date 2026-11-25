import java.io.InputStreamReader
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.smartisan.music"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.chuizi.music"
        minSdk = 27
        targetSdk = 36
        // [1.4.29] 积压批：全页面背景莫奈化 + 切引擎崩溃 + 智能过渡 BASS 结构体 +
        // 未知专辑返回箭头 + USB native EQ 收口。1.4.28 已出包（92），本轮递增到 93。
        versionCode = 93
        versionName = "1.4.29"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "锤子音乐"

        // 1.4.11 · S12：三包收敛为 1 个正式版。原 B/A/C 三套真实播放引擎全部编进本包，
        // 由 EngineModeStore 做运行时模式切换（设置里选，重启生效），功能一个不丢。
        // 保留 PLAYBACK_ENGINE/ENGINE_LABEL 两个 BuildConfig 字段（历史 30+ 引用点不动），
        // 值固定 official；真正的当前引擎由 FlavorDecodeFrontend/FlavorPlaybackEngine 门面分派。
        buildConfigField("String", "PLAYBACK_ENGINE", "\"official\"")
        buildConfigField("String", "ENGINE_LABEL", "\"正式版·三引擎可选(v1.4.24)\"")
        // 整改版本标识：来自 gradle.properties 单点定义，日志头会打印，用于区分 v9/v10/v11 各轮。
        // [v21] 修正中文标识乱码：java.util.Properties 规范按 ISO-8859-1 读，直接 findProperty
        // 拿到的是"æ›º"这类乱码（v19 包内 build= 已实测是乱码）。这里显式按 UTF-8 读文件，
        // 再把非 ASCII 字符转成 \uXXXX 写进 Java 字符串字面量，日志头的 build= 才是正常中文。
        val buildLabel: String =
            run {
                val f = rootProject.file("gradle.properties")
                val p = Properties()
                if (f.exists()) {
                    f.inputStream().use { p.load(InputStreamReader(it, Charsets.UTF_8)) }
                }
                p.getProperty("music.buildLabel") ?: "dev"
            }
        val buildLabelLiteral =
            buildLabel
                .map { c ->
                    when {
                        c == '"' -> "\\\""
                        c == '\\' -> "\\\\"
                        c.code in 32..126 -> c.toString()
                        else -> String.format("\\u%04x", c.code)
                    }
                }
                .joinToString("")
        buildConfigField("String", "BUILD_LABEL", "\"$buildLabelLiteral\"")
    }

    // 发布签名只从环境变量读取：仓库里不放 keystore，也不内置任何口令。
    // 未提供 SMARTISAN_KEYSTORE（或文件不存在）时，release 构建保持未签名，
    // assembleDebug、单元测试和 lint 不受影响。
    val releaseKeystorePath = System.getenv("SMARTISAN_KEYSTORE")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("SMARTISAN_STORE_PASS")
                keyAlias = System.getenv("SMARTISAN_KEY_ALIAS") ?: "chuizi"
                keyPassword = System.getenv("SMARTISAN_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    // 关键：让 .so 以「解压落盘」方式安装到 nativeLibraryDir。
    // targetSdk>=29 有 W^X 限制，app 数据目录（filesDir 等）里的二进制禁止 exec；
    // 而 nativeLibraryDir 是系统信任的可执行目录。APE 转码需要以独立进程运行内置
    // ffmpeg，因此必须让 libffmpegbin.so 落盘到这里，才能被 ProcessBuilder 直接执行。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // USB 独占（UAC 直出）的原生层：usbfs 等时传输必须走 native，
    // Java/Kotlin 侧拿不到 usbfs 的 fd ioctl 能力。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        externalNativeBuild {
            cmake {
                // 只编 64 位：NDK r26 + 16KB 页对齐，armeabi-v7a 无实际收益且增加体积
                abiFilters += listOf("arm64-v8a")
            }
        }
        // nativeLibraryDir 里只保留 arm64-v8a，与上面 CMake 的编译口径一致。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    ndkVersion = "26.1.10909125"
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "ChuiziMusic-$versionName.apk"
                }
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    // 音频标签读写（编辑歌曲信息直接写回文件本身）
    implementation(libs.jaudiotagger)
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    constraints {
        implementation(libs.androidx.dynamicanimation) {
            because("Align the transitive transition runtime with the title-bar reference test")
        }
    }
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.dynamicanimation)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ============================================================================
// [1.4.25·R76] BASS native 调用隔离 —— 构建期强制检查
// ----------------------------------------------------------------------------
// 背景：1.4.24 的「点歌闪退」(fid==null on BASS_ChannelGetInfo) 根因是 Kotlin 侧
// 用 by lazy 每次访问都去 native 重读句柄，close() 之后仍有代码去读 —— 典型的
// C 句柄 use-after-free。BASS 是裸 Int 句柄 + C API：没有所有权、没有 RAII、
// 没有编译器保护。调用点一旦散落，这类崩溃只能靠人记，迟早复发。
//
// 硬规则：app/src/main/java 下，除 playback/BassEngine.kt 外，禁止出现任何
// BASS native 函数调用。
//   · 放行：全大写标识符 = 常量或纯数据构造
//           （BASS_FX.BASS_BFX_BQF_PEAKINGEQ、BASSmix.BASS_MIXER_NODE(...)）
//   · 拦截：含小写字母的 PascalCase 标识符 = native 函数
//           （BASS_ChannelGetInfo / BASS_FX_TempoCreate / BASS_Mixer_ChannelRemove）
// 命中即让 preBuild 失败，因此 assembleRelease / bundleRelease 全部跑不过。
// 正确改法：在 BassEngine 里加一个 withLock + closed 短路的封装方法，外部只调它。
// ============================================================================
val bassIsolationCheck =
    tasks.register("verifyBassIsolation") {
        group = "verification"
        description = "R76：BASS native 调用必须只出现在 BassEngine.kt"
        val srcRoot = file("src/main/java")
        val allowedPath =
            file("src/main/java/com/smartisan/music/playback/BassEngine.kt").canonicalPath
        val reportFile = layout.buildDirectory.file("reports/bass-isolation.txt")
        inputs.dir(srcRoot)
            .withPropertyName("bassIsolationSources")
            .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        outputs.file(reportFile)
        doLast {
            val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            val lineComment = Regex("""//[^\n]*""")
            // 类名限定调用：BASS.xxx( / BASSmix.xxx( / BASS_FX.xxx(
            // 注意：前面允许是 "."，因为全限定写法 com.un4seen.bass.BASS.BASS_xxx(...)
            // 必须能拦到；用 \b 保证不会误伤 MyBASS.Foo( 这类自定义标识符。
            val qualified =
                Regex(
                    """\b(BASS|BASS_FX|BASSmix|BASSFLAC|BASSOPUS|BASS_DSD|BASSWASAPI|BASSDirect)""" +
                        """\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
                )
            // 裸调用：import 后直接 BASS_xxx(
            val bare = Regex("""\b(BASS_[A-Za-z0-9_]+)\s*\(""")

            val violations = mutableListOf<String>()
            srcRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { f ->
                    if (f.canonicalPath == allowedPath) return@forEach
                    // 剥注释，但保留换行数，保证报出的行号是源码真实行号。
                    val noBlock =
                        blockComment.replace(f.readText()) { m ->
                            "\n".repeat(m.value.count { it == '\n' })
                        }
                    val code = lineComment.replace(noBlock, "")
                    fun lineOf(idx: Int) = code.substring(0, idx).count { it == '\n' } + 1
                    fun isFunction(name: String) = name.any { it.isLowerCase() }

                    // 先扫限定调用，记下已覆盖的区间，避免同一次调用被 bare 规则重复报一遍。
                    val covered = mutableListOf<IntRange>()
                    qualified.findAll(code).forEach { m ->
                        if (isFunction(m.groupValues[2])) {
                            covered += m.range
                            violations +=
                                "${f.relativeTo(srcRoot)}:${lineOf(m.range.first)} → ${m.value.trim()}"
                        }
                    }
                    bare.findAll(code).forEach { m ->
                        val inside = covered.any { m.range.first >= it.first && m.range.last <= it.last }
                        if (!inside && isFunction(m.groupValues[1])) {
                            violations +=
                                "${f.relativeTo(srcRoot)}:${lineOf(m.range.first)} → ${m.value.trim()}"
                        }
                    }
                }

            val out = reportFile.get().asFile
            out.parentFile?.mkdirs()
            out.writeText(
                if (violations.isEmpty()) {
                    "OK: BASS native calls are confined to BassEngine.kt\n"
                } else {
                    violations.joinToString("\n", postfix = "\n")
                },
            )

            if (violations.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "R76 硬规则被破坏：BASS native 调用只允许出现在 BassEngine.kt。\n" +
                        violations.joinToString("\n") +
                        "\n改法：在 BassEngine 内新增 withLock + closed 短路的封装方法，外部只调封装。" +
                        "\n依据：docs/下版本提醒事项.md · R76；技术文档总集 §9.9",
                )
            }
        }
    }

tasks.named("preBuild") { dependsOn(bassIsolationCheck) }

// ============================================================================
// [1.4.29·R130] 构建后 JNI 符号校验
// ----------------------------------------------------------------------------
// 为什么要有这一步：R128 的 BASS 结构体被 R8 删空，整条构建链没有任何检查，
// 直到真机点歌 SIGABRT 才暴露。这里在 assembleRelease 之后解包 APK，
// 直接读 classes*.dex 的字符串池，断言 native 侧 GetFieldID 要用的字段名
// 与 JNI 方法名都还在；少一个就让构建失败。
// 与 R76 的 verifyBassIsolation 互补：那条查源码调用边界，这条查产物。
// ============================================================================
fun dexStringPool(bytes: ByteArray): Set<String> {
    fun u32(off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)
    if (bytes.size < 0x40) return emptySet()
    val count = u32(0x38)
    val base = u32(0x3C)
    if (count <= 0 || base <= 0 || base >= bytes.size) return emptySet()
    val out = HashSet<String>(count.coerceAtLeast(16))
    for (i in 0 until count) {
        val idOff = base + i * 4
        if (idOff + 3 >= bytes.size) break
        var p = u32(idOff)
        if (p <= 0 || p >= bytes.size) continue
        // string_data_item: uleb128 utf16_size, 然后 MUTF-8 字节，0 结尾。
        while (p < bytes.size) {
            val b = bytes[p].toInt() and 0xFF
            p++
            if (b and 0x80 == 0) break
        }
        val start = p
        while (p < bytes.size && bytes[p].toInt() != 0) p++
        // 断言用的名字全是 ASCII；非 ASCII 字符串按 Latin-1 读出也不会误判成目标名。
        out += String(bytes, start, p - start, Charsets.ISO_8859_1)
    }
    return out
}

val verifyReleaseJniSymbols =
    tasks.register("verifyReleaseJniSymbols") {
        group = "verification"
        description = "R130：解包 release APK，断言 JNI 结构体字段与 native 方法未被 R8 删除"
        mustRunAfter("assembleRelease")
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val apk =
                apkDir.listFiles()
                    ?.filter { it.isFile && it.extension == "apk" }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw org.gradle.api.GradleException(
                        "R130 找不到 release APK，请先运行 assembleRelease",
                    )
            val required =
                listOf(
                    // BASS_CHANNELINFO（BASS_ChannelGetInfo 回写）
                    "freq", "chans", "ctype", "origres", "plugin", "sample",
                    // BASS_BFX_BQF（BASS_FXSetParameters 回写；EQ 每一段都靠它）
                    "lFilter", "fCenter", "fGain", "fBandwidth", "fQ", "fS", "lChannel",
                    // BASS_MIXER_NODE（交叉淡化混音节点）
                    "pos",
                    // USB 独占 JNI：这些方法必须在，否则独占打不开/写不进 PCM
                    "nativeOpen", "nativeWrite", "nativeStart", "nativeDrain",
                    "nativeClose", "nativeSetVolume", "nativeVolumeRange",
                )
            val pool = HashSet<String>()
            ZipFile(apk).use { zip ->
                zip.entries()
                    .asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { entry ->
                        zip.getInputStream(entry).use { pool += dexStringPool(it.readBytes()) }
                    }
            }
            val missing = required.filter { it !in pool }
            val report = layout.buildDirectory.file("reports/jni-symbols.txt").get().asFile
            report.parentFile?.mkdirs()
            report.writeText(
                buildString {
                    appendLine("APK: ${apk.name}")
                    appendLine("dex strings: ${pool.size}")
                    if (missing.isEmpty()) {
                        appendLine("OK: all JNI symbols present")
                    } else {
                        missing.forEach { appendLine("MISSING: $it") }
                    }
                },
            )
            if (missing.isNotEmpty()) {
                throw org.gradle.api.GradleException(
                    "R130 JNI 符号校验失败：${missing.joinToString()} 在 dex 字符串池中不存在。\n" +
                        "说明 proguard keep 规则失效，或对应字段/方法已被删除。\n" +
                        "先查 app/proguard-rules.pro 的 BASS / USB keep 段；报告见 ${report.path}",
                )
            }
        }
    }

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(verifyReleaseJniSymbols)
}
