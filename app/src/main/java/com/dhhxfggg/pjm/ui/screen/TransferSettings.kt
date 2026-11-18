package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorSmartphone
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.transfer.PcFolder
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.component.FileThumbnail
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.TextActionButton
import com.dhhxfggg.pjm.ui.viewmodel.TransferFailedItem
import com.dhhxfggg.pjm.ui.viewmodel.TransferUiState
import com.dhhxfggg.pjm.ui.viewmodel.TransferViewModel

@Composable
internal fun TransferSettings(transferViewModel: TransferViewModel = hiltViewModel()) {
    val state by transferViewModel.state.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) } // 0 = 发到电脑，1 = 从电脑取回
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
    ) {
        SettingsCategory(title = stringResource(R.string.transfer_group_usb)) {
            StatusLine(
                stringResource(R.string.transfer_pc_tool),
                stringResource(if (state.pcOnline) R.string.transfer_online else R.string.transfer_offline),
                state.pcOnline,
            )
            if (state.pcOnline) {
                StatusLine(stringResource(R.string.transfer_recv_location), state.pcRoot, true)
                StatusLine(
                    stringResource(R.string.transfer_disk),
                    stringResource(
                        R.string.transfer_disk_free,
                        state.pcFs,
                        FileUtils.formatFileSize(state.pcFree),
                        FileUtils.formatFileSize(state.pcTotal),
                    ),
                    true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { transferViewModel.probe() }, enabled = !state.checking) {
                    Text(stringResource(if (state.checking) R.string.transfer_testing else R.string.transfer_test_connection))
                }
                Text(
                    stringResource(if (state.pcOnline) R.string.transfer_probe_online else R.string.transfer_probe_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            SettingsSwitch(
                icon = Lucide.MonitorSmartphone,
                title = stringResource(R.string.transfer_start_service),
                description = stringResource(R.string.transfer_start_service_desc),
                checked = state.serverRunning,
                onCheckedChange = { transferViewModel.toggleServer() },
            )
        }

        if (!state.serverRunning) {
            SettingsCategory(title = stringResource(R.string.transfer_howto)) {
                Hint(stringResource(R.string.transfer_step1))
                Hint(stringResource(R.string.transfer_step2))
                Hint(stringResource(R.string.transfer_step3))
                Hint(stringResource(R.string.transfer_step4))
            }
        } else {
            SettingsCategory(title = stringResource(R.string.transfer_direction)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RangeChip(stringResource(R.string.transfer_dir_push), mode == 0, Modifier.weight(1f)) { mode = 0 }
                    RangeChip(stringResource(R.string.transfer_dir_pull), mode == 1, Modifier.weight(1f)) { mode = 1 }
                }
            }
            if (mode == 0) {
                ExportSection(
                    state = state,
                    onStart = { dates -> transferViewModel.startPush(dates) },
                    viewModel = transferViewModel,
                )
            } else {
                ImportSection(
                    state = state,
                    onToggle = transferViewModel::toggleFolder,
                    onToggleMany = transferViewModel::toggleFolders,
                    onImport = transferViewModel::importSelected,
                    onStop = transferViewModel::stopImport,
                )
            }
        }

        // 结果就地显示在对应按钮下面（原来只有一个页面最底部的小字，点了按钮看不到任何反馈）
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // 末尾补出底栏高度（和设置页其它子页一致，见 SettingsBottomSpacer）
        SettingsBottomSpacer()
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    good: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            maxLines = 2,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RangeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSection(
    state: TransferUiState,
    onStart: (List<String>) -> Unit,
    viewModel: TransferViewModel,
) {
    // 优先显示手机自己推送的进度；电脑端主动拉取时才回落到它回报的任务状态
    val task = state.push ?: state.task
    var showFailedDialog by remember { mutableStateOf(false) }
    SettingsCategory(title = stringResource(R.string.transfer_dir_push)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.selectedDates.isEmpty()) {
                    stringResource(R.string.transfer_send_all)
                } else {
                    stringResource(R.string.transfer_selected_days, state.selectedDates.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.refreshDates() }) { Text(stringResource(R.string.transfer_refresh_dates)) }
            if (state.selectedDates.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearDates() }) { Text(stringResource(R.string.transfer_clear)) }
            }
        }
        if (state.dates.isEmpty()) {
            Hint(stringResource(R.string.transfer_dates_hint))
        } else {
            // 按月分组：一次只显示一个月的日期，用左右按钮翻月。
            // 旧实现把最近 30 天平铺出来（每行 3 个），日期一多就把整页撑得很长。
            val months =
                remember(state.dates) {
                    state.dates
                        .map { it.substring(0, 7) }
                        .distinct()
                        .sortedDescending()
                }
            var monthIdx by remember(months) { mutableIntStateOf(0) }
            val month = months.getOrNull(monthIdx)
            val monthDays =
                remember(state.dates, month) {
                    if (month == null) emptyList() else state.dates.filter { it.startsWith(month) }
                }
            val monthAllSelected = monthDays.isNotEmpty() && monthDays.all { it in state.selectedDates }
            val monthLabel = monthTitle(month)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { if (monthIdx < months.size - 1) monthIdx++ },
                        enabled = monthIdx < months.size - 1,
                    ) { Text("‹", style = MaterialTheme.typography.titleMedium) }
                    Text(
                        monthLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { if (monthIdx > 0) monthIdx-- },
                        enabled = monthIdx > 0,
                    ) { Text("›", style = MaterialTheme.typography.titleMedium) }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.transfer_month_days, monthDays.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.toggleDates(monthDays) }) {
                        Text(stringResource(if (monthAllSelected) R.string.transfer_month_clear else R.string.transfer_month_select))
                    }
                }
                monthDays.chunked(3).forEach { rowDays ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowDays.forEach { day ->
                            val selected = day in state.selectedDates
                            Surface(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { viewModel.toggleDate(day) },
                                shape = RoundedCornerShape(10.dp),
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        day.substring(5),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        (state.dateCounts[day] ?: 0).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        repeat(3 - rowDays.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // 传输状态：手机上也能看到进度、速度、已传量和剩余数量
        task?.let { t ->
            val percent = if (t.total > 0) (t.done.toFloat() / t.total).coerceIn(0f, 1f) else 0f
            val remaining = (t.total - t.done).coerceAtLeast(0)
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (t.active) R.string.transfer_status_active else R.string.transfer_status_last),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (percent * 100).toInt().toString() + "%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.transfer_progress, t.done, t.total) +
                            " · " + FileUtils.formatFileSize(t.bytes) +
                            " · " + FileUtils.formatFileSize(t.speed) + "/s" +
                            (if (t.avgSpeed > 0) " · 平均 " + FileUtils.formatFileSize(t.avgSpeed) + "/s" else "") +
                            (if (t.active) stringResource(R.string.transfer_remaining, remaining) else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (t.skipped > 0 || t.failed > 0) {
                        Text(
                            stringResource(R.string.transfer_skipped, t.skipped, t.failed) +
                                (if (state.pushFailedItems.isNotEmpty()) "  ›" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (state.pushFailedItems.isNotEmpty()) Modifier.clickable { showFailedDialog = true } else Modifier,
                        )
                    }
                    t.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = stringResource(if (task?.active == true) R.string.transfer_in_progress else R.string.transfer_start_push),
                enabled = state.pcOnline && !state.checking && task?.active != true,
                modifier = Modifier.weight(1f),
                onClick = { onStart(state.selectedDates.toList()) },
            )
            if (task?.active == true) {
                TextButton(onClick = { viewModel.stopPush() }) { Text(stringResource(R.string.transfer_stop)) }
            }
        }
    }

    // 失败文件列表弹窗：像删除确认那样列出本批所有失败文件及其原因（只读，不提供操作）。
    // 图片/视频带缩略图 —— 只给文件名用户没法判断失败的是哪一张；文本类出不了缩略图，
    // 保留文件名 + 失败原因。
    if (showFailedDialog) {
        PjmAeroDialog(
            onDismissRequest = { showFailedDialog = false },
            title = stringResource(R.string.transfer_failed_list_title),
            confirmButton = {
                TextActionButton(text = stringResource(R.string.action_close), onClick = { showFailedDialog = false })
            },
        ) {
            Column {
                Text(
                    stringResource(R.string.transfer_failed_list_hint, state.pushFailedItems.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.pushFailedItems, key = { it.entity?.relativePath ?: it.name }) { item ->
                        FailedFileRow(item)
                    }
                }
            }
        }
    }
}

/**
 * 失败文件行：能出缩略图的（图片、视频）显示缩略图，其余类型显示文件图标。
 * 缩略图复用文件柜的 [FileThumbnail]：一级缓存（磁盘持久化缩略图）与文件柜命中同一份。
 */
@Composable
private fun FailedFileRow(item: TransferFailedItem) {
    val entity = item.entity
    val hasThumb = entity != null && (entity.isImage || FileUtils.isVideoFile(entity.name))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (entity != null && hasThumb) {
                FileThumbnail(
                    fileEntity = entity,
                    size = 160,
                    crossfade = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Lucide.File,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.reason.isNotBlank()) {
                Text(
                    item.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportSection(
    state: TransferUiState,
    onToggle: (String) -> Unit,
    onToggleMany: (List<String>) -> Unit,
    onImport: () -> Unit,
    onStop: () -> Unit,
) {
    SettingsCategory(title = stringResource(R.string.transfer_dir_pull)) {
        if (state.folders.isEmpty()) {
            Hint(
                stringResource(
                    if (state.pcOnline) R.string.transfer_pc_empty else R.string.transfer_pc_offline,
                ),
            )
        } else {
            // 电脑端按「分类 × 日期」建目录，全部平铺时日期一多就是上百行。
            // 这里先选分类，再按月份翻页：一屏只显示「当前分类 + 当前月份」的日期。
            // 勾选状态跨分类/月份保留（state.selected 是全集），翻页不会丢选择。
            val categories =
                remember(state.folders) {
                    state.folders
                        .map { it.category }
                        .distinct()
                        .sortedBy(::categoryRank)
                }
            var picked by remember(categories) { mutableStateOf(categories.firstOrNull().orEmpty()) }
            val category = categories.firstOrNull { it == picked } ?: categories.firstOrNull().orEmpty()
            val categoryFolders =
                remember(state.folders, category) {
                    state.folders.filter { it.category == category }
                }
            val months =
                remember(categoryFolders) {
                    categoryFolders
                        .map { it.date.take(7) }
                        .distinct()
                        .sortedDescending()
                }
            var monthIdx by remember(months) { mutableIntStateOf(0) }
            val safeIdx = monthIdx.coerceIn(0, (months.size - 1).coerceAtLeast(0))
            val month = months.getOrNull(safeIdx)
            val monthFolders =
                remember(categoryFolders, month) {
                    if (month == null) emptyList() else categoryFolders.filter { it.date.startsWith(month) }
                }
            val monthSelected = monthFolders.isNotEmpty() && monthFolders.all { it.key in state.selected }

            // 7 个分类横着排会挤成一团。改用 FlowRow 自动换行（一行最多 4 个），
            // 每个 chip 等宽铺满，两行就能放下全部分类，不再需要横向滚动。
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                maxItemsInEachRow = 4,
            ) {
                categories.forEach { c ->
                    RangeChip(
                        label = stringResource(categoryLabelRes(c)),
                        selected = c == category,
                        modifier = Modifier.weight(1f),
                    ) { picked = c }
                }
            }
            Text(
                stringResource(R.string.transfer_folder_count, state.folders.size) +
                    " · " + stringResource(R.string.transfer_selected_folders, state.selected.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { if (safeIdx < months.size - 1) monthIdx = safeIdx + 1 },
                    enabled = safeIdx < months.size - 1,
                ) { Text("‹", style = MaterialTheme.typography.titleMedium) }
                Text(
                    monthTitle(month),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { if (safeIdx > 0) monthIdx = safeIdx - 1 },
                    enabled = safeIdx > 0,
                ) { Text("›", style = MaterialTheme.typography.titleMedium) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.transfer_month_days, monthFolders.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onToggleMany(monthFolders.map { it.key }) }) {
                    Text(
                        stringResource(
                            if (monthSelected) R.string.transfer_month_clear else R.string.transfer_month_select,
                        ),
                    )
                }
            }
            monthFolders.forEach { folder ->
                FolderRow(folder, folder.key in state.selected) { onToggle(folder.key) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(
                    text = stringResource(if (state.importing) R.string.transfer_importing else R.string.transfer_import_to_phone),
                    enabled = !state.importing && state.selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = onImport,
                )
                // 传输中必须能停下来：大文件动辄几个 GB，等它自己传完不现实
                if (state.importing) {
                    TextButton(onClick = onStop) { Text(stringResource(R.string.transfer_stop)) }
                }
            }
        }
        if (state.importing || state.importTotal > 0) {
            val percent = if (state.importTotal > 0) state.importDone.toFloat() / state.importTotal else 0f
            LinearProgressIndicator(
                progress = { percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Hint(
                stringResource(R.string.transfer_import_progress, state.importDone, state.importTotal) +
                    " · " + FileUtils.formatFileSize(state.importBytes) +
                    " · " + FileUtils.formatFileSize(state.importSpeed) + "/s" +
                    (if (state.importAvgSpeed > 0) " · 平均 " + FileUtils.formatFileSize(state.importAvgSpeed) + "/s" else "") +
                    (state.importError?.let { " · " + it } ?: ""),
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: PcFolder,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // 分类与月份已在上面选好，行里只留「月-日」，省掉重复的分类名
            Text(
                if (folder.date.length >= 10) folder.date.substring(5) else folder.date,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(
                    R.string.transfer_folder_meta_short,
                    folder.count,
                    FileUtils.formatFileSize(folder.size),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun categoryLabelRes(category: String): Int =
    when (category) {
        "images" -> R.string.cat_short_images
        "videos" -> R.string.cat_short_videos
        "bili_videos" -> R.string.cat_short_bili_videos
        "audios" -> R.string.cat_short_audios
        "texts" -> R.string.cat_short_texts
        "pjm" -> R.string.cat_short_pjm
        else -> R.string.cat_short_others
    }

/** 分类在「取回」列表里的顺序：与文件库一致，不随电脑端返回顺序变化 */
private fun categoryRank(category: String): Int =
    when (category) {
        "images" -> 0
        "videos" -> 1
        "bili_videos" -> 2
        "audios" -> 3
        "texts" -> 4
        "pjm" -> 5
        else -> 6
    }

/** 月份标题（yyyy-MM → 本地化的「YYYY年M月」/「YYYY-MM」） */
@Composable
private fun monthTitle(month: String?): String =
    if (month == null) {
        ""
    } else {
        stringResource(
            R.string.transfer_month_label,
            month.substring(0, 4).toIntOrNull() ?: 0,
            month.substring(5, 7).toIntOrNull() ?: 0,
        )
    }

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(14.dp)) { Text(text) }
}
