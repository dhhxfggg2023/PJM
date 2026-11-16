package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.viewmodel.SizeBucket
import com.dhhxfggg.pjm.ui.viewmodel.SizeHistogram
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
    /**
     * 点某个大小档 → 打开「文件库」的筛选视图（复用 FileViewerScreen，功能完全一致）。
     *
     * 三个参数依次是：分类、大小下界（含）、大小上界（不含）。
     * 最后一档没有上界，传 [Long.MAX_VALUE]，由调用方翻译成路由里「无上限」的表示。
     * 路由由 Navigation 统一接线，这里只暴露回调；默认空实现 = 点击无反应。
     */
    onOpenSizeFilter: (category: String, sizeMin: Long, sizeMax: Long) -> Unit = { _, _, _ -> },
    viewModel: VaultHeatmapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    // 每次回到本页都重算一次：ViewModel 只在 init 里 load 过，做完一次 APK 备份或删掉
    // 几百个文件再回来，热力图和大小分布还停在旧数字上。
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                // 顶部必须让开状态栏：本页没有 TopAppBar，之前直接从 y=0 开始画，
                // 标题和统计数字被状态栏挖走一块
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding + 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
            Text(stringResource(R.string.heatmap_title), style = MaterialTheme.typography.titleLarge)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatBlock(stringResource(R.string.heatmap_total), state.total.toString())
            StatBlock(stringResource(R.string.heatmap_last30), state.last30.toString())
            StatBlock(stringResource(R.string.heatmap_current_streak), state.currentStreak.toString())
            StatBlock(stringResource(R.string.heatmap_longest_streak), state.longestStreak.toString())
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
            selectedDay?.let { stringResource(R.string.heatmap_day_info, formatEpochDay(it), dayCount) }
                ?: stringResource(R.string.heatmap_tap_hint)
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
        SizeDistribution(
            histograms = state.sizeHistograms,
            onOpenSizeFilter = onOpenSizeFilter,
        )
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

/**
 * 五档颜色（淡 → 浓）。
 *
 * 原来五档全是「同一个主色改透明度」，浅档在白底上几乎看不出差别，
 * 眼睛看到的就是一整片同色块 —— 和页面背景也分不开。
 * 现在浅档用更低的透明度但换成带色相的低饱和底，深档直接到满饱和主色，
 * 档与档之间亮度差更大，扫一眼就能看出深浅。
 */
private fun cellColors(primary: Color): List<Color> =
    listOf(
        primary.copy(alpha = 0.18f),
        primary.copy(alpha = 0.38f),
        primary.copy(alpha = 0.60f),
        primary.copy(alpha = 0.80f),
        primary,
    )

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

    val dowLabels =
        listOf(
            0 to stringResource(R.string.heatmap_dow_mon),
            2 to stringResource(R.string.heatmap_dow_wed),
            4 to stringResource(R.string.heatmap_dow_fri),
        )
    val monthLabels = stringArrayResource(R.array.heatmap_months)
    val emptyColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val baseColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = cellColors(baseColor)

    // 分档基准用「排名」而不是最大值：某天一次性导入特别多时，
    // 按最大值等比会被那一天压扁，其余全都接近最浅色。
    val cuts = remember(counts) { quantileCuts(counts.values.filter { it > 0 }.sorted()) }

    // 默认停在「最近」这一端：网格是按周从左到右排的，最左边是 53 周前，
    // 进页面先盯着一堆一年前的空格子毫无意义。只在第一次量到宽度时滚一次，
    // 之后用户自己拖到哪就停在哪（旋转/分屏重排才会再对齐一次）。
    var pinnedToLatest by remember { mutableStateOf(false) }
    LaunchedEffect(scroll.maxValue) {
        if (!pinnedToLatest && scroll.maxValue > 0) {
            scroll.scrollTo(scroll.maxValue)
            pinnedToLatest = true
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                // 只把格子这一块垫一层不透明底：色块是主色透明度叠出来的，铺在壁纸上会混色。
                // 关键是不能写死白色 —— 深色主题下白底配浅色月份/星期文字，字直接看不见。
                // 用主题的 surface（不透明）保证明暗两套主题都有对比。
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.width(26.dp).height(gridHeight)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    dowLabels.forEach { (row, text) ->
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
                            val layout = measurer.measure(monthLabels[month - 1], TextStyle(fontSize = 11.sp, color = onSurface))
                            drawText(layout, topLeft = Offset(col * stepPx, 0f))
                        }
                        for (row in 0 until rows) {
                            val day = weekStart + row
                            val count = counts[day] ?: 0
                            val color =
                                if (count <= 0) {
                                    emptyColor
                                } else {
                                    colors[bandOf(count, cuts)]
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
    val colors = cellColors(MaterialTheme.colorScheme.primary)
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Text(stringResource(R.string.heatmap_legend_less), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        colors.forEach { color ->
            Box(
                modifier =
                    Modifier
                        .padding(end = 3.dp)
                        .size(14.dp)
                        .background(color, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(3.dp))
        Text(stringResource(R.string.heatmap_legend_more), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.size(14.dp).background(empty, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.heatmap_legend_none), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        VaultManager.CAT_IMAGES to R.string.cat_short_images,
        VaultManager.CAT_VIDEOS to R.string.cat_short_videos,
        VaultManager.CAT_BILI_VIDEOS to R.string.cat_short_bili_videos,
        VaultManager.CAT_AUDIOS to R.string.cat_short_audios,
        VaultManager.CAT_TEXTS to R.string.cat_short_texts,
        VaultManager.CAT_OTHERS to R.string.cat_short_others,
        VaultManager.CAT_PJM to R.string.cat_short_pjm,
    )

/** 档位文案：`1.2 MB – 3.4 MB` / 最后一档 `3.4 MB 以上`（模板走资源，英文界面不会冒出中文） */
@Composable
private fun SizeBucket.label(): String {
    val head = FileUtils.formatFileSize(start)
    if (isLast) return stringResource(R.string.heatmap_size_range_last, head)
    return stringResource(R.string.heatmap_size_range, head, FileUtils.formatFileSize(end))
}

/**
 * 各分类里单个文件的大小直方图：看「图片大多落在多大之间」这类问题。
 *
 * 直方图由 ViewModel 用**全量** size 算好后传入（见 buildSizeHistogram），
 * 这里只负责渲染：界面上「共 N 个文件」取 [SizeHistogram.total]（真实文件数），
 * 各档数字也来自全量统计，不再受任何抽样影响。
 *
 * 分类以前是一排横向滚动的 FilterChip：6 个分类要横向滚才看得全，下面还压着一行
 * 「共 N 个文件」。现在收进一个下拉框（只显示「分类名 · 文件数」），
 * 把「共 N 个文件」挪到下拉框右边同一行 —— 高度少一行，分类再多也放得下。
 */
@Composable
private fun SizeDistribution(
    histograms: Map<String, SizeHistogram>,
    onOpenSizeFilter: (category: String, sizeMin: Long, sizeMax: Long) -> Unit,
) {
    val available =
        remember(histograms) {
            SIZE_CATEGORIES.filter { (histograms[it.first]?.total ?: 0) > 0 }
        }
    if (available.isEmpty()) return
    var selected by remember(available) { mutableStateOf(available.firstOrNull()?.first) }
    var menuExpanded by remember { mutableStateOf(false) }
    val histogram = selected?.let { histograms[it] } ?: SizeHistogram.EMPTY
    val maxCount = histogram.maxCount.coerceAtLeast(1)
    val total = histogram.total
    val selectedLabelRes = available.firstOrNull { it.first == selected }?.second

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.heatmap_size_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 用 OutlinedButton + DropdownMenu 而不是 ExposedDropdownMenuBox：
            // 后者是实验 API，且 menuAnchor 的签名在 Material3 各版本间反复变，
            // 这里只需要「按钮弹一个列表」，稳定 API 就够。
            Box {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.heatmap_size_category_item,
                                selectedLabelRes?.let { stringResource(it) } ?: "",
                                total,
                            ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Lucide.ChevronDown,
                        // 按钮上的分类名已经念出来了，这里补的是「点它会干什么」
                        contentDescription = stringResource(R.string.heatmap_size_category_cd),
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    available.forEach { (key, labelRes) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.heatmap_size_category_item,
                                        stringResource(labelRes),
                                        histograms[key]?.total ?: 0,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            onClick = {
                                selected = key
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.heatmap_size_total, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        histogram.buckets.forEach { bucket ->
            SizeBucketRow(
                label = bucket.label(),
                count = bucket.count,
                maxCount = maxCount,
                // 最后一档没有上界：抓不到长整数的上界就传 MAX_VALUE，调用方据此走「X 以上」
                onClick = {
                    selected?.let { onOpenSizeFilter(it, bucket.start, if (bucket.isLast) Long.MAX_VALUE else bucket.end) }
                },
            )
        }
    }
}

/**
 * 一行大小档：文案 + 占比条 + 计数 + 右侧 chevron。
 *
 * 整行可点（带 ripple）：点了就是「看这一档到底有哪些文件」，
 * 所以右边给一个 chevron —— 否则一行柱状图看起来只是统计图示，没人会想到能点。
 */
@Composable
private fun SizeBucketRow(
    label: String,
    count: Int,
    maxCount: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                // 先 clip 再 clickable：否则 ripple 会溢出圆角，画到相邻两行上
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 3.dp, horizontal = 2.dp),
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
        Icon(
            Lucide.ChevronRight,
            contentDescription = stringResource(R.string.heatmap_size_open_bucket_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
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
            text = stringResource(R.string.heatmap_monthly_title),
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
