package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhhxfggg.pjm.ui.viewmodel.VaultHeatmapViewModel
import com.dhhxfggg.pjm.ui.viewmodel.formatEpochDay

/**
 * 入库热力图（按天网格着色）。
 *
 * 入口：首页「保险库占用」卡片长按；单击仍是刷新封面的小彩蛋。
 * 整块格子用一张 Canvas 画完 —— 53×7=371 个格子如果各建一个 Composable，
 * 进页面那一下就会掉帧。
 */
@Composable
fun VaultHeatmapScreen(
    onBack: () -> Unit,
    bottomPadding: Dp = 0.dp,
    viewModel: VaultHeatmapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var selectedDay by remember { mutableStateOf<Long?>(null) }

    // 热力图必须压在纯白底上：格子是用主色的透明度叠出来的，
    // 直接铺在壁纸上就会和背景混色，深浅根本分不出来。
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (darkTheme) MaterialTheme.colorScheme.surface else Color.White)
                .padding(bottom = bottomPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            Text("入库热力图", style = MaterialTheme.typography.titleLarge)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatBlock("总计", state.total.toString())
            StatBlock("最近 30 天", state.last30.toString())
            StatBlock("连续天数", state.currentStreak.toString())
            StatBlock("最长连续", state.longestStreak.toString())
        }

        if (!state.isLoading) {
            HeatmapGrid(
                counts = state.counts,
                startDay = state.startDay,
                selectedDay = selectedDay,
                onSelect = { selectedDay = it },
            )
        }

        Spacer(Modifier.height(12.dp))
        val dayCount = selectedDay?.let { state.counts[it] ?: 0 } ?: 0
        val info =
            selectedDay?.let { formatEpochDay(it) + " · 入库 " + dayCount + " 个" }
                ?: "点格子看当天入库数量"
        Text(
            text = info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(16.dp))
        HeatmapLegend()
        Spacer(Modifier.height(14.dp))
        MonthlyStats(counts = state.counts, today = state.today)
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeatmapGrid(
    counts: Map<Long, Int>,
    startDay: Long,
    selectedDay: Long?,
    onSelect: (Long) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val scroll = rememberScrollState()
    // 格子放大到 18dp：原来 12dp 太碎，一屏看下来像一排小点
    val cell = 18.dp
    val gap = 4.dp
    val step = cell + gap
    val labelHeight = 22.dp
    val cols = 53
    val rows = 7
    val gridWidth = step * cols - gap
    val gridHeight = labelHeight + step * rows - gap

    val emptyColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val baseColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.width(26.dp).height(gridHeight)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                listOf(0 to "一", 2 to "三", 4 to "五").forEach { (row, text) ->
                    val layout = measurer.measure(text, TextStyle(fontSize = 10.sp, color = onSurface))
                    drawText(
                        layout,
                        topLeft =
                            Offset(
                                0f,
                                labelHeight.toPx() + row * step.toPx() + (cell.toPx() - layout.size.height) / 2f,
                            ),
                    )
                }
            }
        }
        Box(modifier = Modifier.horizontalScroll(scroll)) {
            Canvas(
                modifier =
                    Modifier
                        .size(gridWidth, gridHeight)
                        .pointerInput(counts) {
                            detectTapGestures { offset ->
                                val y = offset.y - labelHeight.toPx()
                                if (y >= 0f) {
                                    val col = (offset.x / step.toPx()).toInt()
                                    val row = (y / step.toPx()).toInt()
                                    if (col in 0 until cols && row in 0 until rows) {
                                        onSelect(startDay + col * 7L + row)
                                    }
                                }
                            }
                        },
            ) {
                val cellPx = cell.toPx()
                val stepPx = step.toPx()
                // 满格 = 这片网格里单日入库最多的那天，其余按比例给深浅。
                // 以前按固定阈值分级（1-2、3-5…），跨过阈值就是同一个颜色，肉眼分不出 3 和 5。
                var maxCount = 1
                for (col in 0 until cols) {
                    for (row in 0 until rows) {
                        val c = counts[startDay + col * 7L + row] ?: 0
                        if (c > maxCount) maxCount = c
                    }
                }
                var lastMonth = -1
                for (col in 0 until cols) {
                    val weekStart = startDay + col * 7L
                    val month = formatEpochDay(weekStart).substring(5, 7).toInt()
                    if (month != lastMonth) {
                        lastMonth = month
                        val layout = measurer.measure(month.toString() + "月", TextStyle(fontSize = 11.sp, color = onSurface))
                        drawText(layout, topLeft = Offset(col * stepPx, 0f))
                    }
                    for (row in 0 until rows) {
                        val day = weekStart + row
                        val count = counts[day] ?: 0
                        val color = if (count <= 0) emptyColor else baseColor.copy(alpha = cellAlpha(count, maxCount))
                        val topLeft = Offset(col * stepPx, labelHeight.toPx() + row * stepPx)
                        if (day == selectedDay) {
                            drawRoundRect(
                                color = baseColor.copy(alpha = 0.35f),
                                topLeft = topLeft,
                                size = Size(cellPx, cellPx),
                                cornerRadius = CornerRadius(4f, 4f),
                            )
                        }
                        drawRoundRect(
                            color = color,
                            topLeft = topLeft,
                            size = Size(cellPx, cellPx),
                            cornerRadius = CornerRadius(5f, 5f),
                        )
                    }
                }
            }
        }
    }
}

/** 色阶图例：少 → 多 */
@Composable
private fun HeatmapLegend() {
    val base = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        listOf(0f, 0.20f, 0.39f, 0.57f, 0.76f, 0.94f).forEach { alpha ->
            Box(
                modifier =
                    Modifier
                        .padding(end = 3.dp)
                        .size(14.dp)
                        .background(if (alpha == 0f) empty else base.copy(alpha = alpha), RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 近 6 个月的入库量：把网格下方的空位用上，顺便给个趋势 */
@Composable
private fun MonthlyStats(
    counts: Map<Long, Int>,
    today: Long,
) {
    if (counts.isEmpty()) return
    val months =
        remember(counts, today) {
            val result = LinkedHashMap<String, Int>()
            for (back in 5 downTo 0) {
                result[formatEpochDay(today - back * 30L).substring(0, 7)] = 0
            }
            counts.forEach { (day, count) ->
                val key = formatEpochDay(day).substring(0, 7)
                if (result.containsKey(key)) result[key] = (result[key] ?: 0) + count
            }
            result
        }
    val max = (months.values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "最近 6 个月",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        months.forEach { (key, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((value.toFloat() / max).coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                    )
                }
                Text(
                    text = " " + value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

/**
 * 单日浓度：等比例映射到 [0.20, 0.94] 的透明度。
 *
 * 当天最多 = 满色，1 个文件也有能看见的淡色，中间按 count / maxCount 连续过渡，
 * 不再按固定阈值分档（分档后相邻两档看起来一样深）。
 */
private fun cellAlpha(
    count: Int,
    maxCount: Int,
): Float {
    if (count <= 0) return 0f
    val max = maxCount.coerceAtLeast(1)
    return (0.20f + 0.74f * (count.toFloat() / max)).coerceIn(0.20f, 0.94f)
}
