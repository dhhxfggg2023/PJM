package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.domain.util.BiliBridge
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog

@Composable
internal fun BiliScanResultDialog(
    items: List<BiliBridge.BiliCacheItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<BiliBridge.BiliCacheItem>) -> Unit,
) {
    val selectedItems = remember { mutableStateListOf<BiliBridge.BiliCacheItem>().apply { addAll(items) } }
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
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = selectedItems.contains(item)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selectedItems.remove(item) else selectedItems.add(item)
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
    val selectedItems = remember { mutableStateListOf<BiliBridge.MergedVideoItem>().apply { addAll(items) } }
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
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = selectedItems.contains(item)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSelected) selectedItems.remove(item) else selectedItems.add(item)
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
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, logFile)
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(Intent.createChooser(shareIntent, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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
