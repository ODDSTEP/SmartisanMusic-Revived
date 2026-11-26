package com.smartisan.music.ui.theme

import androidx.compose.ui.graphics.Color
import me.tatarka.google.material.dynamiccolor.MaterialDynamicColors
import me.tatarka.google.material.hct.Hct
import me.tatarka.google.material.scheme.SchemeTonalSpot

/**
 * [v17·M3 三色主题] 严格按谷歌官方 Material 3 动态取色算法（material-color-utilities）
 * 从种子色生成整套 M3 语义角色调色板，再映射到锤子音乐现有的 [ThemePalette] 11 个 role。
 *
 * 与 v16 的近似 HSV 提纯不同：这里走官方 HCT(CAM16×L*) → 5 条 TonalPalette →
 * 29 语义角色的完整链路，`primary`/`secondary`/`tertiary` 三条不同色相调色板由官方
 * `SchemeTonalSpot` 生成（tertiary 为 +60° 相邻色），保证"严格按谷歌标准"。
 *
 * 种子色口径（用户拍板）：
 *  - 8 个传统主题：用该主题深档点缀色 themedInk；
 *  - 默认「彩色」主题：用品牌蓝 0xFF5C89F2（用户要看看 M3 重做后的彩色主题）。
 */
internal object M3Palette {

    private val dynamicColors = MaterialDynamicColors()

    /**
     * 生成 M3 palette。
     * @param seedArgb 种子色 ARGB int（传统主题=themedInk，彩色=品牌蓝）
     * @param dark 是否深色模式
     */
    fun fromSeed(seedArgb: Int, dark: Boolean): ThemePalette {
        val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
        // 官方语义角色 → compose Color
        fun role(getter: (MaterialDynamicColors) -> me.tatarka.google.material.dynamiccolor.DynamicColor): Color =
            Color(getter(dynamicColors).getArgb(scheme))

        // primary 三色：主色/辅色/第三色（不同色相，M3 核心）
        val primary = role { it.primary() }
        val primaryContainer = role { it.primaryContainer() }
        val onPrimaryContainer = role { it.onPrimaryContainer() }
        val secondaryContainer = role { it.secondaryContainer() }
        val onSecondaryContainer = role { it.onSecondaryContainer() }
        val tertiary = role { it.tertiary() }
        val tertiaryContainer = role { it.tertiaryContainer() }

        // 中性角色：背景/容器/次级文字/描边
        val surface = role { it.surface() }
        val onSurface = role { it.onSurface() }
        val surfaceContainer = role { it.surfaceContainer() }
        val onSurfaceVariant = role { it.onSurfaceVariant() }
        val outlineVariant = role { it.outlineVariant() }

        // [v18·M3整改·串色根因] v17 曾把浅色 onSurfaceTint 设成 tertiary（种子+60°相邻色），
        // 导致青磁底冒蓝字、鳩羽紫底冒红字（用户说的"青变紫/紫变青反了"）。
        // 谷歌规范：落在 primaryContainer（主色相淡容器）上的前景必须同色相 = onPrimaryContainer。
        // 统一深浅两模式都用 onPrimaryContainer，彻底消除相邻色串色。
        val accentOnContainer = onPrimaryContainer

        return ThemePalette(
            // ink = 组2点缀（开关蓝点/蓝字/主题点缀）→ M3 primary
            ink = primary,
            // surface / surfaceSoft = 背景渐变（近中性）→ M3 surface / surfaceContainerLow
            surface = surface,
            surfaceSoft = role { it.surfaceContainerLow() },
            onSurface = onSurface,
            // [v18] 带色相文字前景统一同色相，不再用 tertiary（修串色）。
            onSurfaceTint = accentOnContainer,
            onSurfaceSoft = onSurfaceVariant,
            // 中性语义角色：严格用 M3 官方角色
            outlineVariant = outlineVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainer = surfaceContainer,
            // primaryContainer = 底栏选中/顶栏方块/按钮/高亮 → M3 primaryContainer（淡容器）
            primaryContainer = primaryContainer,
            // primaryContainerSoft = 更浅一档 accent 容器 → M3 secondaryContainer（同色相降饱和）
            primaryContainerSoft = secondaryContainer,
            // [v17] 加号/圆盘点睛色 → M3 tertiary 三色（默认彩色主题下 = +60° 相邻色）
            tertiaryAccent = tertiary,
            tertiaryContainerAccent = tertiaryContainer,
            // [v18] M3 官方前景/容器角色，供底栏选中态与按钮文字按规范取色。
            onPrimaryContainer = onPrimaryContainer,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
        )
    }

    /**
     * 独立取 M3 三色里的 tertiary 点睛色（加号/新建播放列表圆盘用）。
     * 与 [fromSeed] 用同一种子，保证同一 scheme。
     */
    fun tertiaryAccent(seedArgb: Int, dark: Boolean): Color {
        val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
        return Color(dynamicColors.tertiary().getArgb(scheme))
    }

    /** 加号/圆盘容器的 tertiary 底色。 */
    fun tertiaryContainerAccent(seedArgb: Int, dark: Boolean): Color {
        val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
        return Color(dynamicColors.tertiaryContainer().getArgb(scheme))
    }

    /**
     * [v21] M3 固化主题的色板。色值=开启 M3 三色后各主题的真实色值（见定稿文档），
     * 深浅同名配对，直接写死不再实时算 HCT。用户选这类主题即得到原 M3 效果。
     */
    fun fixedPalette(theme: ColorTheme, dark: Boolean): ThemePalette {
        fun p(
            ink: String, surface: String, surfaceSoft: String, onSurface: String,
            onSurfaceTint: String, onSurfaceSoft: String, outlineVariant: String,
            onSurfaceVariant: String, surfaceContainer: String, primaryContainer: String,
            primaryContainerSoft: String, tertiaryAccent: String,
            tertiaryContainerAccent: String, onPrimaryContainer: String,
            secondaryContainer: String, onSecondaryContainer: String,
        ) = ThemePalette(
            ink = Color(ink.toLong(16).toInt()),
            surface = Color(surface.toLong(16).toInt()),
            surfaceSoft = Color(surfaceSoft.toLong(16).toInt()),
            onSurface = Color(onSurface.toLong(16).toInt()),
            onSurfaceTint = Color(onSurfaceTint.toLong(16).toInt()),
            onSurfaceSoft = Color(onSurfaceSoft.toLong(16).toInt()),
            outlineVariant = Color(outlineVariant.toLong(16).toInt()),
            onSurfaceVariant = Color(onSurfaceVariant.toLong(16).toInt()),
            surfaceContainer = Color(surfaceContainer.toLong(16).toInt()),
            primaryContainer = Color(primaryContainer.toLong(16).toInt()),
            primaryContainerSoft = Color(primaryContainerSoft.toLong(16).toInt()),
            tertiaryAccent = Color(tertiaryAccent.toLong(16).toInt()),
            tertiaryContainerAccent = Color(tertiaryContainerAccent.toLong(16).toInt()),
            onPrimaryContainer = Color(onPrimaryContainer.toLong(16).toInt()),
            secondaryContainer = Color(secondaryContainer.toLong(16).toInt()),
            onSecondaryContainer = Color(onSecondaryContainer.toLong(16).toInt()),
        )

        // [v23·文字融背景修复] 定稿表里的色值都是 6 位 RRGGBB（不带 alpha）。
        // 原来开 M3 开关走 fromSeed() → Color(getArgb()) 是 0xFFRRGGBB、不透明；
        // 固化时若直接把它当 0xRRGGBB 解析，得到 0x00RRGGBB —— alpha=0 全透明，
        // 于是所有走 palette 的文字（按下态 onSurfaceTint、次级 onSurfaceVariant 等）
        // 都变成透明、融进背景；只有走 blend() 的背景因只读 RGB 分量而看似正常。
        // 这里补回 FF，使固化色值与原 M3 开关效果逐字节一致（不多改、不少改）。
        fun hex(s: String): String {
            val rgb = s.removePrefix("#")
            return if (rgb.length == 6) "FF$rgb" else rgb
        }
        return when {
            theme == ColorTheme.M3HatobaMurasaki -> if (dark)
                p(hex("DDB9F8"), hex("151217"), hex("1E1A20"), hex("E8E0E8"), hex("F1DAFF"), hex("CDC4CE"), hex("4B454D"), hex("CDC4CE"), hex("221E24"), hex("573A70"), hex("4E4256"), hex("F3B7BC"), hex("663A3F"), hex("F1DAFF"), hex("4E4256"), hex("EEDDF6"))
            else
                p(hex("705289"), hex("FFF7FD"), hex("FAF1F9"), hex("1E1A20"), hex("573A70"), hex("4B454D"), hex("CDC4CE"), hex("4B454D"), hex("F4EBF3"), hex("F1DAFF"), hex("EEDDF6"), hex("805156"), hex("FFDADC"), hex("573A70"), hex("EEDDF6"), hex("4E4256"))

            theme == ColorTheme.M3Ochikuri -> if (dark)
                p(hex("F8BB71"), hex("18120C"), hex("211A13"), hex("EEE0D4"), hex("FFDDB8"), hex("D4C4B5"), hex("504539"), hex("D4C4B5"), hex("251E17"), hex("653E00"), hex("57432B"), hex("BBCD9E"), hex("3D4B27"), hex("FFDDB8"), hex("57432B"), hex("FCDDBD"))
            else
                p(hex("825513"), hex("FFF8F4"), hex("FFF1E6"), hex("211A13"), hex("653E00"), hex("504539"), hex("D4C4B5"), hex("504539"), hex("F9EBE0"), hex("FFDDB8"), hex("FCDDBD"), hex("54643D"), hex("D7E9B8"), hex("653E00"), hex("FCDDBD"), hex("57432B"))

            theme == ColorTheme.M3YanagiBaiChiku -> if (dark)
                p(hex("C3CD7B"), hex("13140D"), hex("1B1C14"), hex("E5E3D6"), hex("E0E995"), hex("C8C7B7"), hex("47483B"), hex("C8C7B7"), hex("202018"), hex("434B05"), hex("46492F"), hex("A2D0C1"), hex("224E43"), hex("E0E995"), hex("46492F"), hex("E3E5C1"))
            else
                p(hex("5B631E"), hex("FCFAED"), hex("F6F4E7"), hex("1B1C14"), hex("434B05"), hex("47483B"), hex("C8C7B7"), hex("47483B"), hex("F0EEE1"), hex("E0E995"), hex("E3E5C1"), hex("3B665B"), hex("BEECDD"), hex("434B05"), hex("E3E5C1"), hex("46492F"))

            theme == ColorTheme.M3Suou -> if (dark)
                p(hex("FFB3AD"), hex("1A1110"), hex("231918"), hex("F1DEDC"), hex("FFDAD6"), hex("D8C2BF"), hex("534342"), hex("D8C2BF"), hex("271D1C"), hex("73332F"), hex("5D3F3D"), hex("E1C28C"), hex("584319"), hex("FFDAD6"), hex("5D3F3D"), hex("FFDAD6"))
            else
                p(hex("904A45"), hex("FFF8F7"), hex("FFF0EF"), hex("231918"), hex("73332F"), hex("534342"), hex("D8C2BF"), hex("534342"), hex("FCEAE8"), hex("FFDAD6"), hex("FFDAD6"), hex("725B2E"), hex("FFDEA6"), hex("73332F"), hex("FFDAD6"), hex("5D3F3D"))

            // [v24·R17] 5 个新增 M3 主题，色值=官方 fromSeed(种子,dark) 实算（见 v24 规格第 3 节）。
            // 每个色值必须保留 hex() 包裹——裸 6 位 hex 会 alpha=0 触发 v23 文字融背景 bug。
            theme == ColorTheme.M3Colorful -> if (dark)
                p(hex("B1C5FF"), hex("121318"), hex("1A1B21"), hex("E2E2E9"), hex("DAE2FF"), hex("C5C6D0"), hex("44464F"), hex("C5C6D0"), hex("1E1F25"), hex("304578"), hex("404659"), hex("E0BBDD"), hex("593D59"), hex("DAE2FF"), hex("404659"), hex("DCE2F9"))
            else
                p(hex("485D92"), hex("FAF8FF"), hex("F4F3FA"), hex("1A1B21"), hex("304578"), hex("44464F"), hex("C5C6D0"), hex("44464F"), hex("EEEDF4"), hex("DAE2FF"), hex("DCE2F9"), hex("735572"), hex("FED7F9"), hex("304578"), hex("DCE2F9"), hex("404659"))

            theme == ColorTheme.M3EnshuNezumi -> if (dark)
                p(hex("F5BC6F"), hex("18120B"), hex("211B13"), hex("EDE0D4"), hex("FFDDB5"), hex("D3C4B4"), hex("4F4539"), hex("D3C4B4"), hex("251F17"), hex("633F00"), hex("57432B"), hex("B9CDA0"), hex("3B4C29"), hex("FFDDB5"), hex("57432B"), hex("FBDEBC"))
            else
                p(hex("805611"), hex("FFF8F4"), hex("FFF1E5"), hex("211B13"), hex("633F00"), hex("4F4539"), hex("D3C4B4"), hex("4F4539"), hex("F9ECDF"), hex("FFDDB5"), hex("FBDEBC"), hex("52643F"), hex("D5EABA"), hex("633F00"), hex("FBDEBC"), hex("57432B"))

            theme == ColorTheme.M3Sekichiku -> if (dark)
                p(hex("FFB4AB"), hex("1A1110"), hex("231918"), hex("F1DEDC"), hex("FFDAD6"), hex("D8C2BF"), hex("534341"), hex("D8C2BF"), hex("271D1C"), hex("73332E"), hex("5D3F3C"), hex("E0C38C"), hex("584419"), hex("FFDAD6"), hex("5D3F3C"), hex("FFDAD6"))
            else
                p(hex("904A43"), hex("FFF8F7"), hex("FFF0EE"), hex("231918"), hex("73332E"), hex("534341"), hex("D8C2BF"), hex("534341"), hex("FCEAE7"), hex("FFDAD6"), hex("FFDAD6"), hex("715B2E"), hex("FDDFA6"), hex("73332E"), hex("FFDAD6"), hex("5D3F3C"))

            theme == ColorTheme.M3Karegusa -> if (dark)
                p(hex("ECC06C"), hex("17130B"), hex("201B13"), hex("EBE1D4"), hex("FFDEA4"), hex("D1C5B4"), hex("4E4639"), hex("D1C5B4"), hex("241F17"), hex("5D4200"), hex("53452A"), hex("B2CFA7"), hex("354D2F"), hex("FFDEA4"), hex("53452A"), hex("F6E0BB"))
            else
                p(hex("79590C"), hex("FFF8F2"), hex("FDF2E5"), hex("201B13"), hex("5D4200"), hex("4E4639"), hex("D1C5B4"), hex("4E4639"), hex("F7ECDF"), hex("FFDEA4"), hex("F6E0BB"), hex("4C6545"), hex("CEEBC2"), hex("5D4200"), hex("F6E0BB"), hex("53452A"))

            theme == ColorTheme.M3SabiSeiji -> if (dark)
                p(hex("87D6BC"), hex("0F1512"), hex("171D1A"), hex("DEE4E0"), hex("A3F2D8"), hex("BFC9C3"), hex("3F4945"), hex("BFC9C3"), hex("1B211E"), hex("005140"), hex("344C43"), hex("A8CBE2"), hex("284B5E"), hex("A3F2D8"), hex("344C43"), hex("CEE9DD"))
            else
                p(hex("126B56"), hex("F5FBF6"), hex("EFF5F1"), hex("171D1A"), hex("005140"), hex("3F4945"), hex("BFC9C3"), hex("3F4945"), hex("E9EFEB"), hex("A3F2D8"), hex("CEE9DD"), hex("416376"), hex("C4E7FF"), hex("005140"), hex("CEE9DD"), hex("344C43"))

            else -> fromSeed(theme.themedInk?.let { (((it.red * 255f).toInt() shl 24) or ((it.green * 255f).toInt() shl 16) or ((it.blue * 255f).toInt() shl 8) or (it.alpha * 255f).toInt()) } ?: 0xFF5C89F2.toInt(), dark)
        }
    }

    /** M3 固化主题的「分隔栏最深档」固定色（原 M3 模式下 surfaceContainerHighest）。 */
    fun fixedSeparatorColor(theme: ColorTheme, dark: Boolean): Color = when (theme) {
        ColorTheme.M3HatobaMurasaki -> Color(if (dark) 0xFF383339.toInt() else 0xFFE8E0E8.toInt())
        ColorTheme.M3Ochikuri -> Color(if (dark) 0xFF3B332B.toInt() else 0xFFEEE0D4.toInt())
        ColorTheme.M3YanagiBaiChiku -> Color(if (dark) 0xFF35352D.toInt() else 0xFFE5E3D6.toInt())
        ColorTheme.M3Suou -> Color(if (dark) 0xFF3D3231.toInt() else 0xFFF1DEDC.toInt())
        // [v24·R17] 5 个新增 M3 主题的分隔栏最深档（0xFF 前缀自带 alpha，防 v23 复发）。
        ColorTheme.M3Colorful -> Color(if (dark) 0xFF33343A.toInt() else 0xFFE2E2E9.toInt())
        ColorTheme.M3EnshuNezumi -> Color(if (dark) 0xFF3B342B.toInt() else 0xFFEDE0D4.toInt())
        ColorTheme.M3Sekichiku -> Color(if (dark) 0xFF3D3231.toInt() else 0xFFF1DEDC.toInt())
        ColorTheme.M3Karegusa -> Color(if (dark) 0xFF3A342B.toInt() else 0xFFEBE1D4.toInt())
        ColorTheme.M3SabiSeiji -> Color(if (dark) 0xFF303633.toInt() else 0xFFDEE4E0.toInt())
        else -> Color(0xFFE8E0E8.toInt())
    }

    /**
     * [v18·分隔栏三档] 走官方 HCT 库取精确的 surfaceContainer 档位色。
     * @param depth 0=标准(surfaceContainer) 1=较深(surfaceContainerHigh) 2=最深(surfaceContainerHighest)
     * 官方 light 对应 tone94/92/90，dark 对应 tone12/17/22，全部由算法生成，非 HSV 近似。
     * 返回 Compose Color。
     */
    fun separatorColor(seedArgb: Int, dark: Boolean, depth: Int): Color {
        val scheme = SchemeTonalSpot(Hct.fromInt(seedArgb), dark, 0.0)
        val argb = when (depth.coerceIn(0, 2)) {
            1 -> dynamicColors.surfaceContainerHigh().getArgb(scheme)
            2 -> dynamicColors.surfaceContainerHighest().getArgb(scheme)
            else -> dynamicColors.surfaceContainer().getArgb(scheme)
        }
        return Color(argb)
    }
}
