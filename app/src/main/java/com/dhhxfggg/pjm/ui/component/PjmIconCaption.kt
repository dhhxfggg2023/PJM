package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 图标按钮 + 功能小字。
 *
 * 用户反馈「按钮样子不一定能看出来功能」，所以纯图标按钮下面补一行 10sp 小字。
 * 规格沿用音频播放器底部那一排（图标 + 模式名小字）的视觉：字号 10sp、单行、
 * 颜色是同色系但更淡 —— 不新起一套样式，否则同一屏里会出现两种「注释字」。
 *
 * 无障碍：小字只是给看得见的人看的，读屏已经被图标自己的 contentDescription 播报过了。
 * 所以小字上用 [clearAndSetSemantics] 清空语义，避免同一个词被读两遍。
 *
 * 注意：本身带文字标签的按钮（底部导航、带文字的 TextButton 等）不要套这个组件，
 * 否则一屏里字比图标还多。
 */
@Composable
fun PjmIconWithCaption(
    icon: ImageVector,
    contentDescription: String?,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color = Color.White,
    captionColor: Color = iconTint.copy(alpha = 0.7f),
    iconSize: Dp = 24.dp,
    buttonSize: Dp = 44.dp,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // size 是精确约束，会让 IconButton 的 48dp 最小触控区失效（30/34dp 的调用点尤其明显）。
        // 外层 Box 撑到 48dp 保证可点，视觉尺寸仍由 buttonSize 控制。
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(buttonSize)) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        PjmButtonCaption(caption = caption, color = captionColor)
    }
}

/**
 * 只要那行小字：按钮本身有特殊外观（实心圆、带底色）时，手写按钮再用它统一字号与语义。
 */
@Composable
fun PjmButtonCaption(
    caption: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = caption,
        color = color,
        fontSize = 10.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        // 读屏重复播报的规避见 KDoc：语义已由图标提供
        modifier = modifier.clearAndSetSemantics {},
    )
}
