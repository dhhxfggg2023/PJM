package com.dhhxfggg.pjm.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhhxfggg.pjm.ui.theme.PrimaryDark

/**
 * PJM 统一按钮层级（五级）。
 *
 * 改版前的问题：同一个界面里 `Button` / `OutlinedButton` / `TextButton` 混着用，
 * 主次不分 —— 用户不知道该点哪个，破坏性操作（删除）也不够醒目。
 *
 * 现在固定成五级，**新代码请一律用本文件的组件**，不要直接调 Material 的 Button：
 *
 * | 层级 | 组件 | 用在哪 |
 * |---|---|---|
 * | 1 主操作 | [PrimaryActionButton] | 一个界面**只能有一个**（分享 / 导入 / 保存） |
 * | 2 次要 | [TonalActionButton] | 并排的次选动作 |
 * | 3 第三 | [OutlinedActionButton] | 低强调但仍需可见 |
 * | 4 弱化 | [TextActionButton] | 取消 / 跳过 |
 * | 5 危险 | [DangerActionButton] / [DangerConfirmButton] | 删除类；确认按钮用实心红 |
 *
 * 统一样式：胶囊圆角、图标与文字间距 7dp、所有按钮都带图标时图标 18dp。
 */
private val BUTTON_SHAPE = RoundedCornerShape(percent = 50)
private val BUTTON_PADDING = PaddingValues(horizontal = 20.dp, vertical = 11.dp)

/** 主操作（Filled + 主色渐变 + 投影）。一个界面只应出现一个。 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    // Button 的 containerColor 只接受纯色，因此把渐变画在 Box 上、
    // Button 本身用透明容器叠在上面 —— 这样既保留水波纹与无障碍语义，
    // 又能用上主色渐变。
    Box(
        modifier =
            modifier
                .clip(BUTTON_SHAPE)
                .background(pjmPrimaryBrush()),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = BUTTON_SHAPE,
            contentPadding = BUTTON_PADDING,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Transparent,
                ),
            elevation = null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                }
                Text(text, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 次操作（Tonal：淡主色底 + 深主色字）。 */
@Composable
fun TonalActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = BUTTON_SHAPE,
        contentPadding = BUTTON_PADDING,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 第三级（描边 + 主色字）。 */
@Composable
fun OutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = BUTTON_SHAPE,
        contentPadding = BUTTON_PADDING,
        border = BorderStroke(1.4.dp, MaterialTheme.colorScheme.outline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 弱化（纯文字）。取消 / 跳过这类「不做任何事」的出口。 */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = BUTTON_SHAPE,
        colors =
            ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 危险操作（淡红底 + 红字）。用于删除入口，**不是**最终确认。 */
@Composable
fun DangerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = BUTTON_SHAPE,
        contentPadding = BUTTON_PADDING,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 危险确认（实心红）。只用在**二次确认对话框的确认按钮**上 ——
 * 与 [DangerActionButton]（淡红，删除入口）形成明确的强度递进：
 * 「你点了删除」和「你确认真的要删」必须看起来不一样。
 */
@Composable
fun DangerConfirmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = BUTTON_SHAPE,
        contentPadding = BUTTON_PADDING,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

/** 主色渐变画刷，供需要自定义背景的按钮复用 */
@Composable
fun pjmPrimaryBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(PrimaryDark, scheme.primary))
}

/**
 * 工具栏用的图标按钮。
 *
 * ## 样式选择
 * 试过「圆角色块 + 白图标」的方案，但顶栏里四个色块并排会显得很吵，
 * 而且和左边的裸返回箭头不搭。最终采用**纯图标、无底色** ——
 * Signal / Immich / Aegis 这类应用的工具条都是这个样子：克制、不抢内容。
 *
 * 只有 [danger] 会改变图标颜色（删除用错误红），其余统一中性色。
 * 点击反馈交给 Material 自带的水波纹，不额外加容器。
 */
@Composable
fun PjmIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(42.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (danger) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
    }
}
