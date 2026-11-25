package com.smartisan.music.ui.shell.tabs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.theme.currentThemePalette
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.navigation.MusicDestination

@Composable
internal fun SmartisanBottomTabItem(
    destination: MusicDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    inOverflow: Boolean = false,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectSmartisanPressedAsState()
    // [1.4.25] 主题/莫奈调色板（null=默认彩色零改动），供选中态文字与图标同色。
    val palette = currentThemePalette()
    // 1.3.9：底栏完全恢复 1.3.7 原样（selector 红/灰图标 + 原文字色），
    // 撤销 1.3.8 那套「整框主题色背景 + 全图 SrcIn 压平」。选中图标红色换主题色
    // 由 tabbar_*_selector 的 RED_ONLY 染色在 painter 层完成，只作用于非默认主题。
    val scale by
        animateFloatAsState(
            if (pressed) 1.1f else if (selected) 1f else .9f,
            tween(
                if (pressed) 120 else 200,
                easing = com.smartisan.music.ui.components.SmartisanEaseInOut,
            ),
            label = "tabScale",
        )
    Column(
        modifier
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Tab,
                enabled = enabled,
                onClick = smartisanClick(onClick),
                onLongClick = onLongClick,
                onLongClickLabel =
                    if (onLongClick != null) stringResource(R.string.navigation_editor_open_action)
                    else null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            rememberSmartisanDrawablePainter(
                if (inOverflow) destination.overflowIconRes else destination.bottomIconRes,
                enabled = enabled,
                pressed = pressed,
                selected = selected,
                checked = selected,
            ),
            contentDescription = null,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
        Spacer(Modifier.height(dimensionResource(R.dimen.smartisan_switch_bar_drawablePadding)))
        BasicText(
            stringResource(destination.labelRes),
            Modifier.fillMaxWidth().graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            style =
                TextStyle(
                    color =
                        // [1.4.25·底栏文字与图标统一] 选中项图标已被 RED_ONLY 染成 accent 容器，
                        // 选中态文字也用同源 accent 容器与图标同色；未选中保持原灰色
                        // （保持未选中态的弱化层级）；默认彩色 palette=null 零改动。
                        // [v16] surface 已改为近中性背景，accent 容器迁移到 primaryContainer。
                        if (selected && palette != null) {
                            // [v18·M3整改] 选中态文字与图标同用深色前景（M3=onPrimaryContainer，
                            // 非 M3 默认=primaryContainer 零改动），避免极淡容器色落灰底看不清。
                            palette.onPrimaryContainer
                        } else {
                            smartisanStateColor(
                                R.color.tab_bar_text_color,
                                pressed = pressed,
                                selected = selected,
                            )
                        },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
