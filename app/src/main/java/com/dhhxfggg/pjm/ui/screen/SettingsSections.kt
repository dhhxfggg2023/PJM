package com.dhhxfggg.pjm.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.viewmodel.MainViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

@Composable
internal fun MainCategoryList(
    settings: Settings.AppSettings,
    onNavigate: (SettingsPage) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_adaptive)) {
            SettingsButton(
                icon = Lucide.Palette,
                title = stringResource(R.string.settings_title_appearance),
                description = stringResource(
                    R.string.settings_desc_appearance_summary,
                    settings.theme,
                    "%.2f".format(settings.globalUiScale)
                ),
                onClick = { onNavigate(SettingsPage.Appearance) }
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_ingestion)) {
            SettingsButton(
                icon = Lucide.Database,
                title = stringResource(R.string.settings_title_auto_ingest),
                description = stringResource(
                    R.string.settings_desc_auto_delete_grid,
                    if (settings.autoDeleteOriginal) stringResource(R.string.toggle_on) else stringResource(R.string.toggle_off),
                    settings.gridSpanCount
                ),
                onClick = { onNavigate(SettingsPage.Ingestion) }
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_bili)) {
            SettingsButton(
                icon = Lucide.MonitorPlay,
                title = stringResource(R.string.settings_title_bili_scan_strategy),
                description = stringResource(
                    R.string.settings_desc_bili_auto_delete_summary,
                    if (settings.biliAutoDelete) stringResource(R.string.toggle_on) else stringResource(R.string.toggle_off)
                ),
                onClick = { onNavigate(SettingsPage.Bilibili) }
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_maintenance)) {
            SettingsButton(
                icon = Lucide.Wrench,
                title = stringResource(R.string.settings_title_diag_maintenance),
                description = stringResource(R.string.settings_desc_diag_maintenance),
                onClick = { onNavigate(SettingsPage.Maintenance) }
            )
        }
    }
}

@Composable
internal fun AppearanceSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    onSelectBg: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_theme_colors)) {
            SettingsOptionLabel(stringResource(R.string.settings_label_theme))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)).forEach { (key, label) ->
                    FilterChip(selected = settings.theme == key, onClick = { settingsViewModel.updateSetting(Settings.KEY_THEME, key) }, label = { Text(label) })
                }
            }
            // Android 12+ 壁纸动态取色开关：关闭后使用内置海洋蓝莫兰迪配色
            SettingsSwitch(
                icon = Lucide.Palette,
                title = stringResource(R.string.settings_title_dynamic_color),
                description = stringResource(R.string.settings_desc_dynamic_color),
                checked = settings.isDynamicColorEnabled,
                onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_DYNAMIC_COLOR, it) }
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_frosted_background)) {
            SettingsSwitch(icon = Lucide.Image, title = stringResource(R.string.settings_title_custom_bg), checked = settings.isCustomBackgroundEnabled, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_ENABLED, it) })
            if (settings.isCustomBackgroundEnabled) {
                SettingsButton(icon = Lucide.Images, title = stringResource(R.string.settings_title_select_bg), description = stringResource(R.string.settings_desc_select_bg), onClick = onSelectBg)
                SettingsStepper(icon = Lucide.Contrast, title = stringResource(R.string.settings_title_bg_opacity), value = settings.backgroundOpacity, step = 0.05f, onValueChange = { settingsViewModel.updateSetting(Settings.KEY_BACKGROUND_OPACITY, it) })
            }
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_canvas_scale)) {
            SettingsStepper(icon = Lucide.Maximize, title = stringResource(R.string.settings_title_ui_scale), value = settings.globalUiScale, valueRange = 0.8f..1.2f, step = 0.01f, onValueChange = { settingsViewModel.updateSetting(Settings.KEY_GLOBAL_UI_SCALE, it) })
        }
    }
}

@Composable
internal fun IngestionSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_view_config)) {
            SettingsOptionLabel(stringResource(R.string.settings_label_grid_columns))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(1, 2, 3, 4, 6).forEach { count ->
                    FilterChip(selected = settings.gridSpanCount == count, onClick = { settingsViewModel.updateSetting(Settings.KEY_GRID_SPAN_COUNT, count) }, label = { Text(count.toString()) })
                }
            }
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_auto_rules)) {
            SettingsSwitch(icon = Lucide.Trash2, title = stringResource(R.string.setting_auto_delete_original), description = stringResource(R.string.setting_auto_delete_original_desc), checked = settings.autoDeleteOriginal, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_AUTO_DELETE_ORIGINAL, it) })
            SettingsSwitch(icon = Lucide.FileUp, title = stringResource(R.string.settings_title_auto_extract), description = stringResource(R.string.settings_desc_auto_extract), checked = settings.isArchiveAutoExtractionEnabled, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_ARCHIVE_AUTO_EXTRACTION, it) })
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_split_policy)) {
            SettingsOptionLabel(stringResource(R.string.settings_title_split_size))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(500 to "500MB", 1024 to "1GB", 2048 to "2GB", 4096 to "4GB").forEach { (size, label) ->
                    FilterChip(selected = settings.exportSplitSize == size, onClick = { settingsViewModel.updateSetting(Settings.KEY_EXPORT_SPLIT_SIZE, size) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
internal fun BilibiliSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    onOpenPicker: () -> Unit,
    onOpenMergedPicker: () -> Unit
) {
    val context = LocalContext.current
    var showPrivilegedAdbDialog by remember { mutableStateOf(false) }

    // 检测 PJM 内置特权服务（shell 身份）是否在线
    val privilegedReady by produceState(initialValue = false) {
        while (true) {
            value = EmbeddedPrivilegedIo.isAvailable(context)
            delay(3000)
        }
    }
    // 状态变化时用横幅提示（就绪/未运行），不做成按钮
    var lastPrivilegedReady by remember { mutableStateOf(false) }
    LaunchedEffect(privilegedReady) {
        if (privilegedReady != lastPrivilegedReady) {
            lastPrivilegedReady = privilegedReady
            val msg = if (privilegedReady) context.getString(R.string.toast_privileged_ready_all)
                else context.getString(R.string.toast_privileged_not_running)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_privileged_access)) {
            // 状态卡（非按钮，仅展示）+ 横幅提示
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (privilegedReady) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Lucide.ShieldCheck,
                        null,
                        tint = if (privilegedReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (privilegedReady) stringResource(R.string.settings_status_privileged_ready)
                            else stringResource(R.string.settings_status_privileged_not_running),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (privilegedReady) stringResource(R.string.settings_desc_privileged_ready_detail)
                            else stringResource(R.string.settings_desc_privileged_not_running_detail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 电脑 adb 启动命令（带设备序列号，避免开着模拟器时发错设备）
            SettingsButton(
                icon = Lucide.Terminal,
                title = stringResource(R.string.settings_title_adb_start_service),
                description = stringResource(R.string.settings_desc_adb_start_service),
                onClick = {
                    EmbeddedPrivilegedIo.prepareStartScript(context)
                    showPrivilegedAdbDialog = true
                }
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_data_access)) {
            // 导入未合并视频（选择目录，识别并合并 B站 分离的 m4s 音视频）
            SettingsButton(
                icon = Lucide.FolderDown,
                title = stringResource(R.string.settings_title_import_unmerged),
                description = stringResource(R.string.settings_desc_import_unmerged),
                onClick = onOpenPicker,
                onLongClick = {
                    context.contentResolver.persistedUriPermissions.forEach {
                        try { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                    }
                    settingsViewModel.updateSetting(Settings.KEY_BILI_ROOT_URI, null)
                    Toast.makeText(context, context.getString(R.string.toast_auth_records_destroyed), Toast.LENGTH_SHORT).show()
                }
            )
            SettingsButton(
                icon = Lucide.Clapperboard,
                title = stringResource(R.string.settings_title_bili_merged_import),
                description = stringResource(R.string.settings_desc_bili_merged_import),
                onClick = onOpenMergedPicker
            )
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_post_process)) {
            // 核心修复：两个自动删除开关合并为一个 —— 未合并/已合并视频导入后统一删除源文件
            SettingsSwitch(
                icon = Lucide.Eraser,
                title = stringResource(R.string.settings_title_bili_auto_delete),
                description = stringResource(R.string.settings_desc_bili_auto_delete),
                checked = settings.biliAutoDelete,
                onCheckedChange = { checked ->
                    // 同时同步两个键，保证未合并/已合并两条导入路径行为一致
                    settingsViewModel.updateSetting(Settings.KEY_BILI_AUTO_DELETE, checked)
                    settingsViewModel.updateSetting(Settings.KEY_BILI_MERGED_AUTO_DELETE, checked)
                }
            )
        }
    }

    // 电脑 adb 启动 PJM 内置特权服务命令对话框（命令带设备序列号，避免模拟器混淆）
    if (showPrivilegedAdbDialog) {
        val adbCommand = EmbeddedPrivilegedIo.getStartCommand(context)
        PjmAeroDialog(
            onDismissRequest = { showPrivilegedAdbDialog = false },
            title = stringResource(R.string.settings_title_adb_start_service_dialog),
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("pjm_start", adbCommand))
                    showPrivilegedAdbDialog = false
                    Toast.makeText(context, context.getString(R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.action_copy_command)) }
            },
            dismissButton = { TextButton(onClick = { showPrivilegedAdbDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Column {
                Text(stringResource(R.string.dialog_label_steps), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.dialog_msg_adb_steps), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)
                ) {
                    Text(adbCommand, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dialog_msg_adb_multi_device_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}

@Composable
internal fun MaintenanceSettings(
    settingsViewModel: SettingsViewModel,
    mainViewModel: MainViewModel,
    onShowExportConfirm: () -> Unit,
    onCheckUpdate: () -> Unit,
    onCheckIntegrity: () -> Unit,
    onExportLogs: () -> Unit,
    onClearCache: () -> Unit,
    onClearLogs: () -> Unit,
    onFactoryReset: () -> Unit,
    onRandomShare: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = stringResource(R.string.settings_cat_asset_maintenance)) {
            SettingsButton(icon = Lucide.SquareCheck, title = stringResource(R.string.settings_title_clean_duplicates), description = stringResource(R.string.settings_desc_clean_duplicates), onClick = { settingsViewModel.scanForDuplicates() })
            SettingsButton(icon = Lucide.ShieldCheck, title = stringResource(R.string.settings_title_integrity_check), description = stringResource(R.string.settings_desc_integrity_check), onClick = onCheckIntegrity)
            SettingsButton(icon = Lucide.CloudUpload, title = stringResource(R.string.settings_title_export_pjm_volumes), description = stringResource(R.string.settings_desc_export_pjm_volumes), onClick = onShowExportConfirm)
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_update)) {
            SettingsButton(icon = Lucide.Download, title = stringResource(R.string.settings_title_check_update), description = stringResource(R.string.settings_desc_check_update), onClick = onCheckUpdate)
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_random_share)) {
            SettingsButton(icon = Lucide.Shuffle, title = stringResource(R.string.settings_title_random_share), description = stringResource(R.string.settings_desc_random_share), onClick = onRandomShare)
        }
        SettingsCategory(title = stringResource(R.string.settings_cat_diagnostics)) {
            // 核心修复：清除缓存与清除日志分离
            SettingsButton(icon = Lucide.Brush, title = stringResource(R.string.settings_title_clear_cache_all), description = stringResource(R.string.settings_desc_clear_cache_only_tmp), onClick = onClearCache)
            SettingsButton(icon = Lucide.FileX, title = stringResource(R.string.settings_title_clear_logs), description = stringResource(R.string.settings_desc_clear_logs), onClick = onClearLogs)
            SettingsButton(icon = Lucide.Bug, title = stringResource(R.string.settings_title_export_logs), onClick = onExportLogs)
            SettingsButton(icon = Lucide.RotateCcw, title = stringResource(R.string.settings_title_factory_reset), onClick = onFactoryReset)
        }
    }
}
