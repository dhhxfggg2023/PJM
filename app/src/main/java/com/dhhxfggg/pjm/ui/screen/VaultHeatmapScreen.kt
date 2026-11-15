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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.viewmodel.VaultHeatmapViewModel
import com.dhhxfggg.pjm.ui.viewmodel.formatEpochDay

/**
 * 入库热力图（按天网格着色）+ 各分类的单个文件大小分布。
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

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding + 16.dp),
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

        Spacer(Modifier.height(12.dp))
        HeatmapLegend()
        Spacer(Modifier.height(18.dp))
        MonthlyStats(counts = state.counts, today = state.today)
        Spacer(Modifier.height(20.dp))
        SizeDistribution(samples = state.sizeSamples)
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

/** 五档透明度：淡 → 浓 */
private val CELL_ALPHAS = floatArrayOf(0.22f, 0.40f, 0.58f, 0.76f, 0.94f)

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

    val emptyColor = Color(0xFFE4E4E7)
    val baseColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant

    // 分档基准用「排名」而不是最大值：某天一次性导入特别多时，
    // 按最大值等比会被那一天压扁，其余全都接近最浅色。
    val cuts = remember(counts) { quantileCuts(counts.values.filter { it > 0 }.sorted()) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                // 只把格子这一块垫成纯白：色块是主色透明度叠出来的，铺在壁纸上会混色
                .background(Color.White)
                .padding(vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
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
                            val color =
                                if (count <= 0) {
                                    emptyColor
                                } else {
                                    baseColor.copy(alpha = CELL_ALPHAS[bandOf(count, cuts)])
                                }
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
}

/** 色阶图例：少 → 多 */
@Composable
private fun HeatmapLegend() {
    val base = MaterialTheme.colorScheme.primary
    val empty = Color(0xFFE4E4E7)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        CELL_ALPHAS.forEach { alpha ->
            Box(
                modifier =
                    Modifier
                        .padding(end = 3.dp)
                        .size(14.dp)
                        .background(base.copy(alpha = alpha), RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text("多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.size(14.dp).background(empty, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text("无入库", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 分成五档的四个分界值：把非零数量从小到大排好，按排名在 20% / 40% / 60% / 80%
 * 处取线性插值，等价于「拟合成一条直线、等间距切四刀」。
 */
private fun quantileCuts(sorted: List<Int>): FloatArray {
    if (sorted.isEmpty()) return floatArrayOf(1f, 2f, 3f, 4f)
    return FloatArray(4) { i ->
        val pos = (sorted.size - 1).toFloat() * (i + 1) / 5f
        val lo = pos.toInt().coerceIn(0, sorted.lastIndex)
        val hi = (lo + 1).coerceIn(0, sorted.lastIndex)
        sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}

/** 落在第几档：0 最淡 … 4 最浓 */
private fun bandOf(
    count: Int,
    cuts: FloatArray,
): Int {
    var band = 0
    for (cut in cuts) {
        if (count > cut) band++ else break
    }
    return band
}

// ---------------- 单个文件大小分布 ----------------

private val SIZE_CATEGORIES =
    listOf(
        VaultManager.CAT_IMAGES to "图片",
        VaultManager.CAT_VIDEOS to "视频",
        VaultManager.CAT_BILI_VIDEOS to "B站视频",
        VaultManager.CAT_AUDIOS to "音频",
        VaultManager.CAT_TEXTS to "文档",
        VaultManager.CAT_OTHERS to "其他",
        VaultManager.CAT_PJM to "容器",
    )

private val SIZE_BUCKET_LABELS =
    listOf("< 0.5 MB", "0.5–1 MB", "1–2 MB", "2–5 MB", "5–10 MB", "10–50 MB", "50–100 MB", "100–500 MB", "0.5–1 GB", "≥ 1 GB")

private val SIZE_BUCKET_EDGES =
    longArrayOf(512L * 1024, 1L shl 20, 2L shl 20, 5L shl 20, 10L shl 20, 50L shl 20, 100L shl 20, 500L shl 20, 1L shl 30)

/** 各分类里单个文件的大小直方图：看「图片在 1MB 以上的有多少」这类问题 */
@Composable
private fun SizeDistribution(samples: Map<String, List<Long>>) {
    if (samples.values.all { it.isEmpty() }) return
    val available = remember(samples) { SIZE_CATEGORIES.filter { !samples[it.first].isNullOrEmpty() } }
    var selected by remember(available) { mutableStateOf(available.firstOrNull()?.first) }
    val sizes = selected?.let { samples[it] }.orEmpty()

    val histogram = remember(sizes) {
        val counts = IntArray(SIZE_BUCKET_LABELS.size)
        sizes.forEach { size ->
            var index = SIZE_BUCKET_EDGES.indexOfFirst { size < it }
            if (index < 0) index = SIZE_BUCKET_LABELS.lastIndex
            counts[index]++
        }
        counts
    }
    val maxCount = (histogram.maxOrNull() ?: 0).coerceAtLeast(1)
    val total = sizes.size

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text("单个文件大小分布", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            available.forEach { (key, label) ->
                FilterChip(
                    selected = key == selected,
                    onClick = { selected = key },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "共 " + total + " 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        histogram.indices.forEach { index ->
            if (histogram[index] > 0 || index <= histogram.indexOfLast { it > 0 }) {
                SizeBucketRow(
                    label = SIZE_BUCKET_LABELS[index],
                    count = histogram[index],
                    maxCount = maxCount,
                )
            }
        }
    }
}

@Composable
private fun SizeBucketRow(
    label: String,
    count: Int,
    maxCount: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
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
                        .fillMaxWidth((count.toFloat() / maxCount).coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
            )
        }
        Text(
            text = " " + count,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
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
