package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorSmartphone
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.transfer.PcFolder
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.viewmodel.TransferUiState
import com.dhhxfggg.pjm.ui.viewmodel.TransferViewModel

@Composable
internal fun TransferSettings(transferViewModel: TransferViewModel = hiltViewModel()) {
    val state by transferViewModel.state.collectAsState()
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
                    onSelectAll = transferViewModel::selectAllFolders,
                    onImport = transferViewModel::importSelected,
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
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
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
            // 小块日期：每行 3 个，一眼看全，不用在一条超长列表里滚
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                state.dates.take(30).chunked(3).forEach { rowDays ->
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
                if (state.dates.size > 30) {
                    Hint(stringResource(R.string.transfer_last30_hint))
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
                            (if (t.active) stringResource(R.string.transfer_remaining, remaining) else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (t.skipped > 0 || t.failed > 0) {
                        Text(
                            stringResource(R.string.transfer_skipped, t.skipped, t.failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun ImportSection(
    state: TransferUiState,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsCategory(title = stringResource(R.string.transfer_dir_pull)) {
        if (state.folders.isEmpty()) {
            Hint(
                stringResource(
                    if (state.pcOnline) R.string.transfer_pc_empty else R.string.transfer_pc_offline,
                ),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.transfer_folder_count, state.folders.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.transfer_select_all)) }
            }
            state.folders.forEach { folder ->
                FolderRow(folder, folder.key in state.selected) { onToggle(folder.key) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                PrimaryButton(
                    text = stringResource(if (state.importing) R.string.transfer_importing else R.string.transfer_import_to_phone),
                    enabled = !state.importing && state.selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = onImport,
                )
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
            Text(folder.date, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                stringResource(
                    R.string.transfer_folder_meta,
                    stringResource(categoryLabelRes(folder.category)),
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

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(14.dp)) { Text(text) }
}
