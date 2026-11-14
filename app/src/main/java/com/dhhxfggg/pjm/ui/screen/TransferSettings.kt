package com.dhhxfggg.pjm.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.MonitorSmartphone
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
        SettingsCategory(title = "USB 直连（只走线缆，不花流量）") {
            StatusLine("电脑工具", if (state.pcOnline) "在线" else "未连接", state.pcOnline)
            if (state.pcOnline) {
                StatusLine("接收位置", state.pcRoot, true)
                StatusLine(
                    "磁盘",
                    state.pcFs + " · 剩余 " + FileUtils.formatFileSize(state.pcFree) + " / " + FileUtils.formatFileSize(state.pcTotal),
                    true,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { transferViewModel.probe() }, enabled = !state.checking) {
                    Text(if (state.checking) "测试中…" else "测试连接")
                }
                Text(
                    if (state.pcOnline) "点一下重新探测电脑端" else "连不上时点这里重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.checking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            SettingsSwitch(
                icon = Lucide.MonitorSmartphone,
                title = "开启传输服务",
                description = "手机只监听 127.0.0.1，电脑端 PJM-Link 通过 adb 隧道连接",
                checked = state.serverRunning,
                onCheckedChange = { transferViewModel.toggleServer() },
            )
        }

        if (!state.serverRunning) {
            SettingsCategory(title = "怎么用") {
                Hint("1. 手机连数据线，打开开发者选项里的 USB 调试，并在手机上点「允许」")
                Hint("2. 电脑上双击 PJM-Link 文件夹里的 PJM-Link.cmd")
                Hint("3. 回到这里打开上面的开关，再到电脑上点「接收手机文件」")
                Hint("全程走线缆：不开 Wi-Fi、不共享网络、不产生手机流量，手机也不产生副本")
            }
        } else {
            SettingsCategory(title = "方向") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RangeChip("发到电脑", mode == 0, Modifier.weight(1f)) { mode = 0 }
                    RangeChip("从电脑取回", mode == 1, Modifier.weight(1f)) { mode = 1 }
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
    SettingsCategory(title = "发到电脑") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.selectedDates.isEmpty()) "传全部文件" else "已选 " + state.selectedDates.size + " 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.refreshDates() }) { Text("刷新日期") }
            if (state.selectedDates.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearDates() }) { Text("清空") }
            }
        }
        if (state.dates.isEmpty()) {
            Hint("这里会列出库里出现过的入库日期；一条记录都没有时只能传全部")
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
                    Hint("只列最近 30 天，更早的选「全部」一起传")
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
                            if (t.active) "正在传输" else "上次传输",
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
                        "已传 " + t.done + "/" + t.total +
                            " · " + FileUtils.formatFileSize(t.bytes) +
                            " · " + FileUtils.formatFileSize(t.speed) + "/s" +
                            (if (t.active) " · 还剩 " + remaining + " 个" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (t.skipped > 0 || t.failed > 0) {
                        Text(
                            "跳过 " + t.skipped + " · 失败 " + t.failed,
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
                text = if (task?.active == true) "传输中…" else "开始传到电脑",
                enabled = state.pcOnline && !state.checking && task?.active != true,
                modifier = Modifier.weight(1f),
                onClick = { onStart(state.selectedDates.toList()) },
            )
            if (task?.active == true) {
                TextButton(onClick = { viewModel.stopPush() }) { Text("停止") }
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
    SettingsCategory(title = "从电脑取回") {
        if (state.folders.isEmpty()) {
            Hint(
                if (state.pcOnline) {
                    "电脑端还没有文件：先把文件传过去，或把文件放进 PJM-Link 里的 PJM-Transfer"
                } else {
                    "电脑端未连接：确认 PJM-Link 已打开并连上 USB"
                },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "共 " + state.folders.size + " 个文件夹",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll) { Text("全选 / 取消") }
            }
            state.folders.forEach { folder ->
                FolderRow(folder, folder.key in state.selected) { onToggle(folder.key) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                PrimaryButton(
                    text = if (state.importing) "导入中…" else "导入到手机",
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
                "已导入 " + state.importDone + "/" + state.importTotal +
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
                categoryLabel(folder.category) + " · " + folder.count + " 个 · " + FileUtils.formatFileSize(folder.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun categoryLabel(category: String): String =
    when (category) {
        "images" -> "图片"
        "videos" -> "视频"
        "bili_videos" -> "B站视频"
        "audios" -> "音频"
        "texts" -> "文本"
        "pjm" -> "PJM 容器"
        else -> "其它"
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
