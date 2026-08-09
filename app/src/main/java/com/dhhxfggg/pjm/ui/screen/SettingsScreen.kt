package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Screen for managing application settings, personalization, and maintenance.
 *
 * @param onBack Callback for navigating back.
 * @param navController Optional navigation controller.
 * @param bottomPadding Bottom padding to accommodate UI components like navigation bars.
 * @param settingsViewModel ViewModel for settings logic.
 * @param cryptoViewModel ViewModel for cryptographic operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    navController: NavHostController? = null,
    bottomPadding: Dp = 0.dp,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    cryptoViewModel: CryptoViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by settingsViewModel.uiState.collectAsState()
    val settings = uiState.settings
    
    var integrityResults by remember { mutableStateOf<Map<String, List<FileEntity>>?>(null) }

    val bgImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_URI, it.toString())
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val fontDir = File(context.filesDir, "fonts").apply { mkdirs() }
                val fontFile = File(fontDir, "custom_font.ttf")
                context.contentResolver.openInputStream(it)?.use { input ->
                    fontFile.outputStream().use { output -> input.copyTo(output) }
                }
                settingsViewModel.updateSetting(Settings.KEY_CUSTOM_FONT_URI, fontFile.absolutePath)
                Toast.makeText(context, "自定义字体已应用，建议重启App以获得最佳效果", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "字体加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("个性化与资产管理", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { 
                    IconButton(onClick = onBack) { 
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null) 
                    } 
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsCategory(title = "个性化外观") {
                Text(
                    "显示主题", 
                    style = MaterialTheme.typography.labelSmall, 
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), 
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("system" to "系统默认", "light" to "明亮模式", "dark" to "深色模式").forEach { (key, label) ->
                        FilterChip(
                            selected = settings.theme == key,
                            onClick = { settingsViewModel.updateSetting(Settings.KEY_THEME, key) },
                            label = { Text(label) }
                        )
                    }
                }
                
                SettingsSwitch(
                    icon = Icons.Default.Image,
                    title = "启用全景背景",
                    checked = settings.isCustomBackgroundEnabled,
                    onCheckedChange = { 
                        settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_ENABLED, it) 
                    }
                )
                if (settings.isCustomBackgroundEnabled) {
                    SettingsButton(
                        icon = Icons.Default.PhotoLibrary, 
                        title = "选择背景图", 
                        description = "建议使用高分辨率图片", 
                        onClick = { bgImagePicker.launch(arrayOf("image/*")) }
                    )
                    SettingsStepper(
                        icon = Icons.Default.Opacity, 
                        title = "背景亮度", 
                        value = settings.backgroundOpacity, 
                        step = 0.05f, 
                        onValueChange = { 
                            settingsViewModel.updateSetting(Settings.KEY_BACKGROUND_OPACITY, it) 
                        }
                    )
                }
                
                SettingsButton(
                    icon = Icons.Default.FontDownload, 
                    title = "自定义应用字体", 
                    description = if (settings.customFontUri != null) "当前已启用自定义字体" else "选择 .ttf 字体文件",
                    onClick = { 
                        fontPicker.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) 
                    }
                )
                if (settings.customFontUri != null) {
                    SettingsButton(
                        icon = Icons.Default.FormatClear, 
                        title = "清除自定义字体", 
                        onClick = { settingsViewModel.updateSetting(Settings.KEY_CUSTOM_FONT_URI, null) }
                    )
                }

                SettingsStepper(
                    icon = Icons.Default.AspectRatio, 
                    title = "全局 UI 缩放", 
                    value = settings.globalUiScale, 
                    valueRange = 0.8f..1.2f, 
                    step = 0.01f,
                    onValueChange = { settingsViewModel.updateSetting(Settings.KEY_GLOBAL_UI_SCALE, it) }
                )
            }

            SettingsCategory(title = "资产深度管理") {
                Text(
                    "文件柜宫格列数", 
                    style = MaterialTheme.typography.labelSmall, 
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), 
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(1, 2, 3, 4, 6).forEach { count ->
                        FilterChip(
                            selected = settings.gridSpanCount == count,
                            onClick = { settingsViewModel.updateSetting(Settings.KEY_GRID_SPAN_COUNT, count) },
                            label = { Text(count.toString()) }
                        )
                    }
                }

                SettingsSwitch(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.setting_auto_delete_original),
                    description = stringResource(R.string.setting_auto_delete_original_desc),
                    checked = settings.autoDeleteOriginal,
                    onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_AUTO_DELETE_ORIGINAL, it) }
                )

                SettingsSwitch(
                    icon = Icons.Default.Unarchive,
                    title = "压缩包自动提取入库",
                    description = "存入 ZIP/7z/RAR 时自动将其解压并分类存储",
                    checked = settings.isArchiveAutoExtractionEnabled,
                    onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_ARCHIVE_AUTO_EXTRACTION, it) }
                )

                SettingsStepper(
                    icon = Icons.Default.Save, 
                    title = "导出分卷大小 (MB)", 
                    description = "打包导出时每个分卷的最大容量",
                    value = settings.exportSplitSize.toFloat(), 
                    valueRange = 100f..4096f, 
                    step = 100f,
                    onValueChange = { settingsViewModel.updateSetting(Settings.KEY_EXPORT_SPLIT_SIZE, it.toInt()) }
                )

                SettingsButton(
                    icon = Icons.Default.FactCheck, 
                    title = "一键清理库内重复项", 
                    description = "基于指纹识别移除内容完全一致的冗余副本",
                    onClick = { settingsViewModel.scanForDuplicates() }
                )
                
                SettingsButton(
                    icon = Icons.Default.GppGood, 
                    title = "库文件完整性校验", 
                    description = "验证库内文件物理存在及内容指纹",
                    onClick = { 
                        settingsViewModel.checkIntegrity { results ->
                            integrityResults = results
                        }
                    }
                )
            }

            SettingsCategory(title = "系统与维护") {
                SettingsButton(
                    icon = Icons.Default.CleaningServices, 
                    title = "清理缓存与临时文件", 
                    description = "释放缩略图占用及过期的日志数据",
                    onClick = { 
                        scope.launch(Dispatchers.IO) {
                            // 1. 系统缓存
                            context.cacheDir.deleteRecursively()
                            // 2. 缩略图磁盘缓存
                            context.filesDir.resolve("pjm_thumbnail_cache").deleteRecursively()
                            // 3. 业务日志
                            com.dhhxfggg.pjm.domain.util.PjmLogger.clear()
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "系统清理完成", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                SettingsButton(
                    icon = Icons.Default.BugReport, 
                    title = "导出诊断日志", 
                    onClick = { exportPjmLogs(context) }
                )
                SettingsButton(
                    icon = Icons.Default.SettingsBackupRestore, 
                    title = "恢复出厂设置", 
                    onClick = { settingsViewModel.resetAllSettings() }
                )
            }
            
            Spacer(modifier = Modifier.height(bottomPadding + 32.dp))
        }
    }

    integrityResults?.let { results ->
        val missing = results["missing"] ?: emptyList()
        val corrupted = results["corrupted"] ?: emptyList()
        
        AlertDialog(
            onDismissRequest = { integrityResults = null },
            title = { Text("自检结果") },
            text = {
                Column {
                    if (missing.isEmpty() && corrupted.isEmpty()) {
                        Text("系统一切正常，所有文件完整。")
                    } else {
                        if (missing.isNotEmpty()) {
                            Text("丢失文件: ${missing.size} 个", color = MaterialTheme.colorScheme.error)
                        }
                        if (corrupted.isNotEmpty()) {
                            Text("损坏文件: ${corrupted.size} 个", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("建议执行“同步数据库”或尝试重新导入。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { Button(onClick = { integrityResults = null }) { Text("知道了") } }
        )
    }

    uiState.duplicateFiles?.let { files ->
        if (files.isEmpty()) {
            Toast.makeText(context, "库内未发现内容重复项", Toast.LENGTH_SHORT).show()
            settingsViewModel.clearDuplicateState()
        } else {
            AlertDialog(
                onDismissRequest = { settingsViewModel.clearDuplicateState() },
                icon = { Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("清理重复内容") },
                text = {
                    Column {
                        Text("扫描完成，发现 ${files.size} 个冗余副本（指纹一致）。", fontWeight = FontWeight.Bold)
                        Text("系统将保留每组文件最早导入的一份，并物理销毁其余重复副本。", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        val totalDuplicateSize = remember(files) { files.sumOf { it.size } }
                        Text("可回收空间: ${FileUtils.formatFileSize(totalDuplicateSize)}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { settingsViewModel.performDeleteDuplicates(files) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("确认清理") }
                },
                dismissButton = {
                    TextButton(onClick = { settingsViewModel.clearDuplicateState() }) { Text("取消") }
                }
            )
        }
    }
}

/**
 * Exports the application logs to an external share intent.
 */
private fun exportPjmLogs(context: Context) {
    try {
        val logFile = com.dhhxfggg.pjm.domain.util.PjmLogger.getLogFile()
        if (logFile?.exists() == true) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享 PJM 运行日志"))
        }
    } catch (e: Exception) { 
        Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() 
    }
}

/**
 * A container for a group of settings.
 */
@Composable
fun SettingsCategory(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title, 
                style = MaterialTheme.typography.titleSmall, 
                modifier = Modifier.padding(8.dp), 
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

/**
 * A setting item with a switch toggle.
 */
@Composable
fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    title: String, 
    description: String? = null, 
    checked: Boolean, 
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(16.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { 
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) 
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A setting item that triggers an action when clicked.
 */
@Composable
fun SettingsButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    title: String, 
    description: String? = null, 
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(16.dp), 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { 
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) 
            }
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

/**
 * A setting item with a slider for numerical values.
 */
@Composable
fun SettingsStepper(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    title: String, 
    description: String? = null, 
    value: Float, 
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, 
    step: Float = 0.05f, 
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                description?.let { 
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) 
                }
            }
            Text(
                text = "%.2f".format(value), 
                style = MaterialTheme.typography.labelLarge, 
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onValueChange((value - step).coerceIn(valueRange)) }) { 
                Icon(Icons.Default.Remove, null) 
            }
            Slider(
                value = value, 
                onValueChange = onValueChange, 
                valueRange = valueRange, 
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onValueChange((value + step).coerceIn(valueRange)) }) { 
                Icon(Icons.Default.Add, null) 
            }
        }
    }
}
