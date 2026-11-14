package com.dhhxfggg.pjm.ui.screen

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorSmartphone
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.viewmodel.TransferUiState
import com.dhhxfggg.pjm.ui.viewmodel.TransferViewModel
import java.util.Calendar

@Composable
internal fun TransferSettings(transferViewModel: TransferViewModel = hiltViewModel()) {
    val state by transferViewModel.state.collectAsState()
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
            ExportSection(
                state = state,
                onStart = { category, from, to -> transferViewModel.startPull(category, from, to) },
                viewModel = transferViewModel,
            )
        }

        state.message?.let { Hint(it) }
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

@Composable
private fun ExportSection(
    state: TransferUiState,
    onStart: (String, Long, Long) -> Unit,
    viewModel: TransferViewModel,
) {
    var range by remember { mutableIntStateOf(0) }
    val task = state.task
    SettingsCategory(title = "发到电脑") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("全部", "今天", "近 7 天").forEachIndexed { index, label ->
                RangeChip(label, range == index, Modifier.weight(1f)) { range = index }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = if (task?.active == true) "传输中…" else "开始传到电脑",
                enabled = state.pcOnline && task?.active != true,
                modifier = Modifier.weight(1f),
                onClick = {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    val today = cal.timeInMillis
                    val from =
                        when (range) {
                            1 -> today
                            2 -> today - 6L * 24 * 60 * 60 * 1000
                            else -> 0L
                        }
                    onStart("", from, 0L)
                },
            )
            if (task?.active == true) {
                TextButton(onClick = { viewModel.stopPull() }) { Text("停止") }
            }
        }
        task?.let {
            val percent = if (it.total > 0) it.done.toFloat() / it.total else 0f
            LinearProgressIndicator(
                progress = { percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Hint(
                (if (it.active) "传输中" else "上次") + " · " + it.done + "/" + it.total +
                    " · " + FileUtils.formatFileSize(it.bytes) +
                    " · " + FileUtils.formatFileSize(it.speed) + "/s" +
                    (if (it.skipped > 0) " · 跳过 " + it.skipped else "") +
                    (if (it.failed > 0) " · 失败 " + it.failed else "") +
                    (it.error?.let { e -> " · " + e } ?: ""),
            )
        }
    }
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
