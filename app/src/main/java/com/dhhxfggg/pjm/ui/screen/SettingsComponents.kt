package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.ui.theme.PresetAmber
import com.dhhxfggg.pjm.ui.theme.PresetBiliPink
import com.dhhxfggg.pjm.ui.theme.PresetDustyBlue
import com.dhhxfggg.pjm.ui.theme.PresetForest
import com.dhhxfggg.pjm.ui.theme.PresetLavender
import com.dhhxfggg.pjm.ui.theme.PresetRose
import com.dhhxfggg.pjm.ui.theme.PresetSage
import kotlin.math.absoluteValue

@Composable
internal fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        // 与主页统一：同一套 surface @ 0.82，而不是另一套 surfaceVariant @ 0.78。
        // （原为 alpha 0.5，在浅色花纹壁纸下卡片边界几乎不可见、文字发飘。）
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = title,
                // 与主页统一：分组标题不再用「主色 + Black + titleLarge」那种抢眼样式，
                // 改成克制的次级标签（小号、中性色、加字距）—— 让内容而不是标题成为视觉重点。
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

/**
 * 设置项左侧的图标块 —— 与主页分类卡完全一致的视觉语言：
 * 彩色渐变圆角方块 + 白色图标。
 *
 * 这是「设置页和主页不统一」最主要的来源：主页是彩色块，设置页是裸图标。
 *
 * ## 颜色怎么来
 * 设置页有二十多个条目，**逐个手写颜色既容易漏、新增条目时又会退回单色**。
 * 所以这里做「从图标名派生」：同一个图标永远得到同一种颜色（稳定），
 * 不同图标大概率得到不同颜色（有变化），新增条目自动获得配色。
 *
 * 调用方仍可通过 [accent] 显式指定（顶层四个入口就用了语义色：
 * 外观=紫 / 入库=绿 / B站=粉 / 维护=橙）。
 */
@Composable
private fun SettingsIconBox(
    icon: ImageVector,
    accent: Color?,
) {
    // 只在显式指定时用固定色；否则按图标名从调色板里取。
    // 列表顺序刻意把冷暖色交错排布，避免相邻条目撞成同色。
    val palette =
        listOf(
            PresetDustyBlue,
            PresetSage,
            PresetAmber,
            PresetRose,
            PresetLavender,
            PresetForest,
            PresetBiliPink,
        )
    val color =
        accent ?: palette[(icon.name.hashCode().absoluteValue) % palette.size]

    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.70f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SettingsOptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp, top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SettingsSwitch(
    icon: ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    MaterialTheme.shapes.medium,
                ).toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
                .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBox(icon, accent)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsButton(
    icon: ImageVector,
    title: String,
    description: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    accent: Color? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    MaterialTheme.shapes.medium,
                ).combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBox(icon, accent)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Lucide.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun SettingsStepper(
    icon: ImageVector,
    title: String,
    description: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> =
        0f..1f,
    step: Float = 0.05f,
    onValueChange: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon, null)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(text = "%.2f".format(sliderValue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val newValue = (sliderValue - step).coerceIn(valueRange)
                sliderValue = newValue
                onValueChange(newValue)
            }) { Icon(Lucide.Minus, null) }
            Slider(value = sliderValue, onValueChange = {
                sliderValue = it
            }, onValueChangeFinished = { onValueChange(sliderValue) }, valueRange = valueRange, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val newValue = (sliderValue + step).coerceIn(valueRange)
                sliderValue = newValue
                onValueChange(newValue)
            }) { Icon(Lucide.Plus, null) }
        }
    }
}
