package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.BiliBridge
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.ShareUtils
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog

@Composable
internal fun BiliScanResultDialog(
    items: List<BiliBridge.BiliCacheItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<BiliBridge.BiliCacheItem>) -> Unit,
) {
    // 核心修复：remember 必须以 items 为 key —— 否则重新扫描得到新结果时，
    // 选中集合仍是上一批的陈旧对象（既勾不上新项，又可能把已不存在的项带进导入列表）。
    val selectedItems = remember(items) { mutableStateListOf<BiliBridge.BiliCacheItem>().apply { addAll(items) } }
    // 勾选判定用路径集合，避免行内对 SnapshotStateList 做线性扫描
    val selectedKeys by remember { derivedStateOf { selectedItems.mapTo(HashSet()) { it.videoM4s.toString() } } }
    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_title_bili_detected),
        confirmButton = {
            Button(onClick = {
                onConfirm(selectedItems.toList())
            }, enabled = selectedItems.isNotEmpty()) { Text(stringResource(R.string.action_import_bili_now)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dialog_msg_bili_found_count, items.size), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 核心修复：用 items(key = ...) 而不是 items(count) ——
                // 索引身份在列表变化时会让行状态错位，且无法复用已组合的项。
                items(items, key = { it.videoM4s.toString() }) { item ->
                    val isSelected = item.videoM4s.toString() in selectedKeys
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val index = selectedItems.indexOfFirst { it.videoM4s == item.videoM4s }
                                    if (index >= 0) selectedItems.removeAt(index) else selectedItems.add(item)
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.partName?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BiliMergedResultDialog(
    items: List<BiliBridge.MergedVideoItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<BiliBridge.MergedVideoItem>) -> Unit,
) {
    // 核心修复：同 BiliScanResultDialog —— remember 以 items 为 key，且用稳定 key 标识行。
    val selectedItems = remember(items) { mutableStateListOf<BiliBridge.MergedVideoItem>().apply { addAll(items) } }
    val selectedKeys by remember { derivedStateOf { selectedItems.mapTo(HashSet()) { it.uri.toString() } } }
    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_title_bili_merged_detected),
        confirmButton = {
            Button(onClick = {
                onConfirm(selectedItems.toList())
            }, enabled = selectedItems.isNotEmpty()) { Text(stringResource(R.string.action_import_bili_merged_now)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dialog_msg_bili_merged_found_count, items.size), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.uri.toString() }) { item ->
                    val isSelected = item.uri.toString() in selectedKeys
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val index = selectedItems.indexOfFirst { it.uri == item.uri }
                                    if (index >= 0) selectedItems.removeAt(index) else selectedItems.add(item)
                                }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

internal fun exportPjmLogs(
    context: Context,
    chooserTitle: String,
    errorMsg: String,
) {
    try {
        val logFile = PjmLogger.getLogFile()
        if (logFile != null && logFile.exists() && logFile.length() > 0) {
            val chooser = ShareUtils.createShareChooser(context, logFile, chooserTitle, "text/plain")
            if (chooser == null) {
                Toast.makeText(context, context.getString(R.string.toast_share_nothing_available), Toast.LENGTH_SHORT).show()
                return
            }
            context.startActivity(chooser.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } else {
            Toast.makeText(context, context.getString(R.string.toast_log_not_generated), Toast.LENGTH_SHORT).show()
        }
    } catch (
        e: Exception,
    ) {
        PjmLogger.e("SettingsScreen", "Log export failed", e)
        Toast.makeText(context, "$errorMsg: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
