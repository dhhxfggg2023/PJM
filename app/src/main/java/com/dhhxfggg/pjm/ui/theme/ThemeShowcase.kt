package com.dhhxfggg.pjm.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 纯 UI 主题展示（仅用于 @Preview 渲染，不依赖任何业务/ViewModel）。
 * 用来快速核对新版莫兰迪配色方案在明/暗两套下的实际观感。
 */
@Composable
private fun ThemeShowcaseContent(dark: Boolean) {
    val scheme = if (dark) DarkColorScheme else LightColorScheme
    var chipSelected by remember { mutableStateOf(true) }

    MaterialTheme(colorScheme = scheme, typography = pjmTypography(scheme.onBackground)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 大标题
                Text("PJM 资源柜", style = MaterialTheme.typography.headlineMedium)
                Text("完整色槽：secondary / tertiary / container / outline 不再与默认紫打架", style = MaterialTheme.typography.bodyMedium)

                // 分类莫兰迪色卡
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryDot("图库", PresetForest)
                    CategoryDot("视频", PresetAmber)
                    CategoryDot("音频", PresetRose)
                    CategoryDot("B站", PresetBiliPink)
                    CategoryDot("其他", PresetDustyBlue)
                    CategoryDot("加密", MaterialTheme.colorScheme.primary)
                }

                // 主按钮行（展示 tonal/outlined/filled 一致性）
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {}) { Text("加密") }
                    FilledTonalButton(onClick = {}) { Text("提取") }
                    OutlinedButton(onClick = {}) { Text("删除") }
                }

                // 表单控件（Switch + FilterChip + 错误按钮）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = chipSelected, onCheckedChange = { chipSelected = it })
                    Spacer(Modifier.width(10.dp))
                    FilterChip(
                        selected = chipSelected,
                        onClick = { chipSelected = !chipSelected },
                        label = { Text("收藏") },
                    )
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {}) { Text("取消", color = MaterialTheme.colorScheme.error) }
                }

                // 卡片层级抬升（surfaceContainer 各级，替代默认灰紫）
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("图片库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("共 18,632 个项目 · 31.34 GB", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { 0.68f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDot(
    label: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f)),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Preview(name = "PJM 主题 - 浅色", showBackground = true)
@Composable
private fun ThemeShowcaseLightPreview() = ThemeShowcaseContent(dark = false)

@Preview(name = "PJM 主题 - 深色", showBackground = true, backgroundColor = 0xFF111318)
@Composable
private fun ThemeShowcaseDarkPreview() = ThemeShowcaseContent(dark = true)
