package com.smartisan.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.smartisan.music.R

/**
 * 1.3.7 色彩主题：默认「彩色」= 保持现状、一个色都不改；其余 8 个传统色只把
 * 界面里能稳定染的「点缀位」（正在播放的红、搜索命中高亮、底部导航选中色、
 * 强调按钮文字等代码色值）统一换成主题色，背景、未选中文字、箭头、分隔线与
 * 所有 PNG 图片资源一律保持原样。夜间模式下同样生效。
 */
enum class ColorTheme(
    val preferenceValue: String,
    val labelRes: Int,
    /** null = 彩色（默认，零改动） */
    val accent: Color?,
    /**
     * 1.4.0 U1 / §8-P1：该主题**专用**的点缀色（实算后 8 组两两可辨，
     * 最小曼哈顿 ΔRGB 从 1 提升到 45）。null = 默认彩色。
     * 旧实现把饱和度归一 0.30–0.62、明度一律 0.58，导致遠州鼠/落栗/枯草/
     * 苏芳/石竹整片黄褐红系被压成同一个色；这里改为各主题按分档明度/饱和度
     * 取自己的色值，20 处点缀位全部跟随生效。
     */
    val themedInk: Color? = null,
    /**
     * [v21] true = M3 固化主题：色板不再按算法实时算，而是固定为「开启 M3 三色后」的
     * 真实色值（深浅各一套）。这类主题选中后即呈现原 M3 三色效果，取代已删除的 M3 开关。
     * accent/themedInk 仅用于主题列表色点等纯展示位，实际界面取色走 [M3Palette.fixedPalette]。
     */
    val isM3Fixed: Boolean = false,
) {
    Colorful("colorful", R.string.color_theme_colorful, null),
    EnshuNezumi("enshu_nezumi", R.string.color_theme_enshu_nezumi, Color(0xFFD4BB9C), Color(0xFFBDA384)),
    Ochikuri("ochikuri", R.string.color_theme_ochikuri, Color(0xFF93816D), Color(0xFF755C3F)),
    Suou("suou", R.string.color_theme_suou, Color(0xFFB35E59), Color(0xFF99423D)),
    Sekichiku("sekichiku", R.string.color_theme_sekichiku, Color(0xFFF1C4BE), Color(0xFFD19D97)),
    Karegusa("karegusa", R.string.color_theme_karegusa, Color(0xFFEDD094), Color(0xFFC79B40)),
    YanagiBaiChiku("yanagi_baichiku", R.string.color_theme_yanagi_baichiku, Color(0xFFA7AB86), Color(0xFF989E68)),
    SabiSeiji("sabi_seiji", R.string.color_theme_sabi_seiji, Color(0xFFB8D3CA), Color(0xFF6FA895)),
    HatobaMurasaki("hatoba_murasaki", R.string.color_theme_hatoba_murasaki, Color(0xFF8A7E94), Color(0xFF86689E)),
    // [v21] 4 个 M3 固化主题，排在原 9 个之后；色点沿用各自传统主题色，仅作列表标识。
    M3HatobaMurasaki("m3_hatoba_murasaki", R.string.color_theme_m3_hatoba_murasaki, Color(0xFF86689E), Color(0xFF86689E), true),
    M3Ochikuri("m3_ochikuri", R.string.color_theme_m3_ochikuri, Color(0xFF755C3F), Color(0xFF755C3F), true),
    M3YanagiBaiChiku("m3_yanagi_baichiku", R.string.color_theme_m3_yanagi_baichiku, Color(0xFF989E68), Color(0xFF989E68), true),
    M3Suou("m3_suou", R.string.color_theme_m3_suou, Color(0xFF99423D), Color(0xFF99423D), true),
    // [v24·R17] 5 个新增 M3 固化主题，严格照搬上面 4 个的做法（种子=传统 themedInk，
    // M3彩色用品牌蓝）。accent/themedInk 填种子占位，实际色板走 M3Palette.fixedPalette，
    // 列表色点按 R15 拍板改用 primaryContainer 代表色。
    M3Colorful("m3_colorful", R.string.color_theme_m3_colorful, Color(0xFF5C89F2), Color(0xFF5C89F2), true),
    M3EnshuNezumi("m3_enshu_nezumi", R.string.color_theme_m3_enshu_nezumi, Color(0xFFBDA384), Color(0xFFBDA384), true),
    M3Sekichiku("m3_sekichiku", R.string.color_theme_m3_sekichiku, Color(0xFFD19D97), Color(0xFFD19D97), true),
    M3Karegusa("m3_karegusa", R.string.color_theme_m3_karegusa, Color(0xFFC79B40), Color(0xFFC79B40), true),
    M3SabiSeiji("m3_sabi_seiji", R.string.color_theme_m3_sabi_seiji, Color(0xFF6FA895), Color(0xFF6FA895), true),
    ;

    val isColorful: Boolean get() = accent == null

    /** 以主题色为底的高亮条上的文字/勾色：浅底用深字、深底用浅字，保证可读。 */
    val onAccent: Color
        get() {
            val c = accent ?: return Color.White
            // 感知亮度近似（0..1），不引入额外依赖
            val brightness = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
            return if (brightness > 0.55f) Color(0xFF2B2B2B) else Color.White
        }

    companion object {
        fun fromPreference(value: String?): ColorTheme =
            entries.firstOrNull { it.preferenceValue == value } ?: Colorful
    }
}

val LocalColorTheme = staticCompositionLocalOf { ColorTheme.Colorful }

/**
 * 1.3.8：彩色主题返回原色（零改动）；非默认主题把点缀位换成组 2 的统一深度主题色
 * （与开关蓝点、蓝色文字同深度），不再直接用原始传统色值，避免浅色主题看不清。
 */
@Composable
fun themedAccent(base: Color): Color = themedInk(base)
