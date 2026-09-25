package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 统一的空状态组件。
 *
 * 一个界面「什么都没有」时，纯文字提示会让人以为页面坏了。这里给三样东西：
 * **一个视觉锚点（渐变圆环 + 图标）+ 一句说明「为什么空」+ 一个可执行的出口动作**。
 *
 * 三种语义（图标从 [com.dhhxfggg.pjm.ui.theme.IconPack] 取，保持风格统一）：
 *  - 完全没内容 → `stateEmpty`（inbox）
 *  - 筛选/搜索无结果 → `stateSearchEmpty`（search-x）
 *  - 加载失败 → `stateWarning`（triangle-alert）
 *
 * 无网络/无权限等场景请用 `stateWarning`，不要复用「空」的图标 —— 语义不同，
 * 用户看到 inbox 会以为「只是还没放东西」，而实际是出错了。
 *
 * **自带半透明表面**：本应用 Scaffold 透明且支持自定义壁纸，文字直接铺在壁纸上
 * 会糊掉（实测浅色花纹壁纸下几乎读不出来）。空状态区域没有其它内容帮用户定位
 * 视线，所以这里必须比普通卡片更实。
 *
 * @param actionLabel 与 [onAction] 同时提供时才显示按钮；没有可执行动作时留空即可。
 */
@Composable
fun PjmEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(scheme.primaryContainer, scheme.secondaryContainer),
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )

            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                PrimaryActionButton(
                    text = actionLabel,
                    icon = actionIcon,
                    onClick = onAction,
                )
            }
        }
    }
}
