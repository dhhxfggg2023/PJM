package com.dhhxfggg.pjm.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.composables.icons.lucide.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.provider.DocumentsContract
import androidx.compose.animation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.util.BiliBridge
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.UpdateChecker
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.PjmDuplicateCompareDialog
import com.dhhxfggg.pjm.ui.component.PjmFolderPickerDialog
import com.dhhxfggg.pjm.ui.viewmodel.MainViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SettingsPage {
    Appearance, Ingestion, Bilibili, Maintenance
}

/**
 * 工业级分级设置页面 - 解决功能膨胀，提升操作效率。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    bottomPadding: Dp = 0.dp,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by settingsViewModel.uiState.collectAsState()
    val settings = uiState.settings
    
    var currentPage by remember { mutableStateOf<SettingsPage?>(null) }

    // 处理物理返回键
    BackHandler(enabled = currentPage != null) {
        currentPage = null
    }

    // 资源加载
    val vaultExportedMsg = stringResource(R.string.toast_vault_exported)
    val systemCleanedMsg = stringResource(R.string.toast_system_cleaned)
    val noDuplicatesMsg = stringResource(R.string.toast_no_duplicates)
    val shareLogsTitle = stringResource(R.string.chooser_title_share_logs)
    val exportFailedMsg = stringResource(R.string.error_export_failed_simple)

    // Launchers
    var showPjmFolderPicker by remember { mutableStateOf(false) }
    var showMergedFolderPicker by remember { mutableStateOf(false) }
    // 授权后的目标动作："cache"=回到缓存选择器 / "merged"=回到已合并选择器 / null=同样回到缓存选择器
    var pendingSafAction by remember { mutableStateOf<String?>(null) }
    // 核心修复：刚授权成功的 tree URI（会话内有效，即使未持久化），
    // 传给选择器用于穿透进入应用目录 —— 解决"授权后打不开应用内部文件夹"。
    var lastSafTreeUri by remember { mutableStateOf<Uri?>(null) }
    val biliAuthLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val action = pendingSafAction
                var persisted = false
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                    persisted = true
                    settingsViewModel.updateSetting(Settings.KEY_BILI_ROOT_URI, uri.toString())
                } catch (e: Exception) {
                    // 核心修复：Android 13+ 禁止持久化 Android/data 子树的权限，
                    // 此异常是预期的 —— 但授权在【本会话内有效】，uri 仍可用于穿透浏览。
                    PjmLogger.w("SettingsScreen", "Android/data 授权无法持久化（13+ 预期），会话内仍有效", e)
                    settingsViewModel.updateSetting(Settings.KEY_BILI_ROOT_URI, uri.toString())
                }
                // 记录会话内授权树，重开选择器时传入，用户可立即点开应用内部文件夹
                lastSafTreeUri = uri
                // 回到对应选择器（用户可自由浏览应用内部，选目录后确定扫描）
                val reopenPicker: () -> Unit = when (action) {
                    "merged" -> { showMergedFolderPicker = true; { showMergedFolderPicker = true } }
                    else -> { showPjmFolderPicker = true; { showPjmFolderPicker = true } }
                }
                showPjmFolderPicker = false
                showMergedFolderPicker = false
                scope.launch {
                    kotlinx.coroutines.delay(300)
                    reopenPicker()
                }
                Toast.makeText(context, if (persisted) "授权成功，可浏览应用目录" else "已授权（本会话有效），可浏览应用目录", Toast.LENGTH_SHORT).show()
                pendingSafAction = null
            }
        }
    }

    // 核心修复：MT 管理器同款授权方式 —— 最朴素的 ACTION_OPEN_DOCUMENT_TREE。
    // 之前反复失败的根因：
    //   1. setPackage("com.google.android.documentsui") 强制指定 AOSP 包名，
    //      国产 ROM（MIUI/ColorOS/EMUI 等）的定制文件管理器不认，污染 intent。
    //   2. EXTRA_INITIAL_URI 定位参数，厂商定制文件管理器大多不支持，
    //      收到后反而打开到错误位置/空白，看不到 Android 目录。
    // MT 的做法就是不指定包、不传初始定位，让系统文件管理器用默认行为，
    // 用户自己导航到「内部存储 → Android」目录后授权。
    val launchSafAuth: (String?) -> Unit = { _ ->
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            // 不 setPackage、不传 EXTRA_INITIAL_URI —— 让系统默认文件管理器决定
        }
        biliAuthLauncher.launch(intent)
    }

    val bgImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    // 核心修复：背景图文件名带时间戳，确保每次选择后 URI 都不同，
                    // 从而让 AppTheme 的 remember 依赖失效、Coil 缓存键变化，真正更换背景。
                    // （原来固定写 custom_background.jpg，URI 不变 → 换图后仍显示旧图）
                    val bgFile = File(context.filesDir, "custom_background_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(it)?.use { input ->
                        bgFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    // 清理旧背景文件，避免堆积
                    context.filesDir.listFiles()
                        ?.filter { f -> f.name.startsWith("custom_background_") && f.name != bgFile.name }
                        ?.forEach { it.delete() }
                    withContext(Dispatchers.Main) {
                        settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_URI, bgFile.toUri().toString())
                        Toast.makeText(context, R.string.toast_bg_set, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    PjmLogger.e("SettingsScreen", "Failed to copy background image", e)
                }
            }
        }
    }

    // 对话框状态
    var showExportConfirm by remember { mutableStateOf(false) }
    var integrityResults by remember { mutableStateOf<Map<String, List<FileEntity>>?>(null) }
    var showBiliAuthExplanation by remember { mutableStateOf(false) }
    var showFactoryResetConfirm by remember { mutableStateOf(false) }
    var showRandomShareDialog by remember { mutableStateOf(false) }

    // 检查更新状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }

    // 应用内下载状态
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        text = when(currentPage) {
                            SettingsPage.Appearance -> "界面自适应"
                            SettingsPage.Ingestion -> "通用入库策略"
                            SettingsPage.Bilibili -> "Bilibili 内容适配"
                            SettingsPage.Maintenance -> "高级维护工具"
                            null -> stringResource(R.string.settings_title)
                        }, 
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = { 
                    IconButton(onClick = { if (currentPage == null) onBack() else currentPage = null }) { 
                        Icon(if (currentPage == null) Lucide.ArrowLeft else Lucide.ChevronLeft, null) 
                    } 
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        // 核心修复：bottomPadding（BottomNavBar 高度）此前传入但从未使用，
        // 导致设置页底部内容被底部导航遮挡，最下方的功能无法点击。
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(bottom = bottomPadding)) {
            AnimatedContent(
                targetState = currentPage,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "PageNav"
            ) { page ->
                when (page) {
                    null -> MainCategoryList(
                        settings = settings,
                        onNavigate = { currentPage = it }
                    )
                    SettingsPage.Appearance -> AppearanceSettings(
                        settings = settings,
                        settingsViewModel = settingsViewModel,
                        onSelectBg = { bgImagePicker.launch(arrayOf("image/*")) }
                    )
                    SettingsPage.Ingestion -> IngestionSettings(
                        settings = settings,
                        settingsViewModel = settingsViewModel
                    )
                    SettingsPage.Bilibili -> BilibiliSettings(
                        settings = settings,
                        settingsViewModel = settingsViewModel,
                        onOpenPicker = { showPjmFolderPicker = true },
                        onOpenMergedPicker = { showMergedFolderPicker = true }
                    )
                    SettingsPage.Maintenance -> MaintenanceSettings(
                        settingsViewModel = settingsViewModel,
                        mainViewModel = mainViewModel,
                        onShowExportConfirm = { showExportConfirm = true },
                        onCheckUpdate = {
                            if (isCheckingUpdate) return@MaintenanceSettings
                            isCheckingUpdate = true
                            updateCheckResult = null
                            scope.launch {
                                val result = UpdateChecker.checkForUpdate(context)
                                updateCheckResult = result
                                isCheckingUpdate = false
                            }
                        },
                        onCheckIntegrity = {
                            scope.launch(VaultManager.PjmDispatchers.IO) {
                                settingsViewModel.checkIntegrity { results -> integrityResults = results }
                            }
                        },
                        onExportLogs = { exportPjmLogs(context, shareLogsTitle, exportFailedMsg) },
                        onClearCache = {
                            scope.launch(VaultManager.PjmDispatchers.IO) {
                                // 核心修复：多任务并发 —— 同任务防连点，其他任务可并行
                                if (!VaultManager.tryBeginOperation(VaultManager.TASK_CLEAR_CACHE)) return@launch
                                try {
                                    // 顶部横幅：与其它功能一致的完成提示
                                    VaultManager.updateProgress(0.5f, context.getString(R.string.status_clearing_cache), taskId = VaultManager.TASK_CLEAR_CACHE)
                                    // 核心修复：清除缓存【只清临时缓存目录 cacheDir】——
                                    // 绝不删除 filesDir 下的半永久缓存：
                                    //   · thumbnails/（视频/图片缩略图）
                                    //   · pjm_thumbnail_cache/（Coil 磁盘缓存）
                                    //   · image_fingerprints/（感知指纹 .txt + 灰度 .g32）
                                    // 这些是耗时生成的数据，删除后需重新解码/计算（用户痛点）。
                                    context.cacheDir.deleteRecursively()
                                    VaultManager.updateProgress(1f, systemCleanedMsg, taskId = VaultManager.TASK_CLEAR_CACHE)
                                    withContext(Dispatchers.Main) { Toast.makeText(context, systemCleanedMsg, Toast.LENGTH_SHORT).show() }
                                    delay(1200)
                                    VaultManager.clearProgress(VaultManager.TASK_CLEAR_CACHE)
                                } finally {
                                    VaultManager.endOperation(VaultManager.TASK_CLEAR_CACHE)
                                }
                            }
                        },
                        onClearLogs = {
                            scope.launch(VaultManager.PjmDispatchers.IO) {
                                // 核心修复：多任务并发 —— 同任务防连点，其他任务可并行
                                if (!VaultManager.tryBeginOperation(VaultManager.TASK_CLEAR_LOGS)) return@launch
                                try {
                                    // 清除日志（与清除缓存分离）
                                    VaultManager.updateProgress(0.5f, context.getString(R.string.status_clearing_logs), taskId = VaultManager.TASK_CLEAR_LOGS)
                                    PjmLogger.clear()
                                    VaultManager.updateProgress(1f, context.getString(R.string.status_logs_cleared), taskId = VaultManager.TASK_CLEAR_LOGS)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, context.getString(R.string.status_logs_cleared), Toast.LENGTH_SHORT).show()
                                    }
                                    delay(1200)
                                    VaultManager.clearProgress(VaultManager.TASK_CLEAR_LOGS)
                                } finally {
                                    VaultManager.endOperation(VaultManager.TASK_CLEAR_LOGS)
                                }
                            }
                        },
                        onFactoryReset = { showFactoryResetConfirm = true },
                        onRandomShare = { showRandomShareDialog = true }
                    )
                }
            }
        }
    }

    // 全局对话框
    if (showBiliAuthExplanation) {
        PjmAeroDialog(
            onDismissRequest = { showBiliAuthExplanation = false },
            title = "目录访问授权",
            icon = { Icon(Lucide.ShieldAlert, null, tint = MaterialTheme.colorScheme.primary) },
            confirmButton = {
                Button(onClick = {
                    showBiliAuthExplanation = false
                    launchSafAuth(null)
                }) { Text("前往授权") }
            },
            dismissButton = { TextButton(onClick = { showBiliAuthExplanation = false }) { Text("取消") } }
        ) {
            Column {
                Text("授权步骤（与 MT 管理器一致）：", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("1. 点下方「前往授权」打开系统文件选择器\n2. 导航到「内部存储 → Android」目录（注意：不是 Android/data，也不是根目录）\n3. 选中「Android」目录 → 点「使用此文件夹」授权", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("系统禁止直接授权 Android/data 或根目录（会提示保护隐私），授权 Android 目录后即可穿透访问应用内部文件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showPjmFolderPicker) {
        PjmFolderPickerDialog(
            onDismiss = { showPjmFolderPicker = false },
            extraTreeUri = lastSafTreeUri,
            onFolderSelected = { selectedUri ->
                showPjmFolderPicker = false
                settingsViewModel.scanBiliCache(selectedUri)
            },
            onRequestSafAuth = { pkg ->
                pendingSafAction = if (pkg != null) "cache" else null
                launchSafAuth(pkg)
            }
        )
    }

    if (showMergedFolderPicker) {
        PjmFolderPickerDialog(
            onDismiss = { showMergedFolderPicker = false },
            extraTreeUri = lastSafTreeUri,
            onFolderSelected = { selectedUri ->
                showMergedFolderPicker = false
                settingsViewModel.scanBiliMerged(selectedUri)
            },
            onRequestSafAuth = { pkg ->
                pendingSafAction = if (pkg != null) "merged" else null
                launchSafAuth(pkg)
            }
        )
    }

    if (showExportConfirm) {
        PjmAeroDialog(
            onDismissRequest = { showExportConfirm = false },
            title = stringResource(R.string.dialog_title_export_vault),
            confirmButton = {
                Button(onClick = {
                    showExportConfirm = false
                    mainViewModel.startVaultExport { success -> if (success) Toast.makeText(context, vaultExportedMsg, Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(R.string.action_confirm_export)) }
            },
            dismissButton = { TextButton(onClick = { showExportConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Text(stringResource(R.string.dialog_msg_export_vault), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showFactoryResetConfirm) {
        PjmAeroDialog(
            onDismissRequest = { showFactoryResetConfirm = false },
            title = stringResource(R.string.dialog_title_factory_reset_confirm),
            confirmButton = {
                Button(
                    onClick = {
                        showFactoryResetConfirm = false
                        // 顶部横幅：与其它功能一致的完成提示
                        scope.launch(VaultManager.PjmDispatchers.IO) {
                            // 核心修复：多任务并发 —— 同任务防连点，其他任务可并行
                            if (!VaultManager.tryBeginOperation(VaultManager.TASK_RESET)) return@launch
                            try {
                                VaultManager.updateProgress(0.5f, context.getString(R.string.status_resetting), taskId = VaultManager.TASK_RESET)
                                settingsViewModel.resetAllSettings()
                                VaultManager.updateProgress(1f, context.getString(R.string.status_reset_done), taskId = VaultManager.TASK_RESET)
                                delay(1200)
                                VaultManager.clearProgress(VaultManager.TASK_RESET)
                            } finally {
                                VaultManager.endOperation(VaultManager.TASK_RESET)
                            }
                        }
                        Toast.makeText(context, R.string.toast_settings_reset, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.action_confirm_reset)) }
            },
            dismissButton = { TextButton(onClick = { showFactoryResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Text(stringResource(R.string.dialog_msg_factory_reset_confirm), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showRandomShareDialog) {
        var count by remember { mutableStateOf(10) }
        PjmAeroDialog(
            onDismissRequest = { showRandomShareDialog = false },
            title = stringResource(R.string.dialog_title_random_share),
            confirmButton = {
                Button(
                    onClick = {
                        showRandomShareDialog = false
                        settingsViewModel.randomPickAndEncrypt(count)
                    }
                ) { Text(stringResource(R.string.action_start_random)) }
            },
            dismissButton = { TextButton(onClick = { showRandomShareDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dialog_msg_random_share), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.label_random_count), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                // 常用数量快捷选择（核心修复：加横向滚动，避免 UI 缩放/窄屏时 chips 溢出挤压）
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 20, 50).forEach { n ->
                        FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n 张") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 数量输入
                OutlinedTextField(
                    value = count.toString(),
                    onValueChange = { input -> count = input.filter { it.isDigit() }.take(3).toIntOrNull()?.coerceIn(1, 500) ?: count },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_random_count)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
        }
    }

    // 检查更新弹窗（检查中 / 结果展示）
    if (isCheckingUpdate) {
        PjmAeroDialog(
            onDismissRequest = { },
            title = "检查更新",
            icon = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            confirmButton = {},
            dismissButton = {}
        ) {
            Text("正在连接更新服务器…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    updateCheckResult?.let { result ->
        PjmAeroDialog(
            onDismissRequest = { updateCheckResult = null },
            title = when (result) {
                is UpdateChecker.CheckResult.UpdateAvailable -> "发现新版本"
                is UpdateChecker.CheckResult.UpToDate -> "已是最新版本"
                is UpdateChecker.CheckResult.Failed -> "检查失败"
            },
            confirmButton = {
                if (result is UpdateChecker.CheckResult.UpdateAvailable) {
                    Button(onClick = {
                        val apkUrl = result.apkUrl
                        updateCheckResult = null
                        // 应用内直接下载（不进浏览器）
                        scope.launch {
                            isDownloadingUpdate = true
                            downloadProgress = 0f
                            downloadError = null
                            // Compose snapshot state 支持跨线程写入，进度直接在 IO 回调里更新
                            val dlResult = UpdateChecker.downloadApk(context, apkUrl) { progress ->
                                downloadProgress = progress
                            }
                            isDownloadingUpdate = false
                            when (dlResult) {
                                is UpdateChecker.DownloadResult.Success -> {
                                    val ok = UpdateChecker.installApk(context, dlResult.apkFile)
                                    if (!ok) {
                                        downloadError = "下载完成，但无法自动拉起安装，请到系统设置中允许「安装未知应用」后重试。"
                                    }
                                }
                                is UpdateChecker.DownloadResult.Error -> {
                                    downloadError = dlResult.message
                                }
                            }
                        }
                    }) { Text("下载并安装") }
                } else {
                    Button(onClick = { updateCheckResult = null }) { Text(stringResource(R.string.action_dismiss)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { updateCheckResult = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            when (result) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    Column(Modifier.fillMaxWidth()) {
                        Text("当前版本：v${result.currentVersion}", style = MaterialTheme.typography.bodyMedium)
                        Text("最新版本：v${result.latestVersion}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("点击「下载并安装」将直接在本应用内下载安装包（约 4~5 MB），完成后自动拉起安装。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (result.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text("更新内容：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(result.releaseNotes, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    Text("您已在使用最新版本 (v${result.currentVersion}) ✅", style = MaterialTheme.typography.bodyMedium)
                }
                is UpdateChecker.CheckResult.Failed -> {
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // 应用内下载进度弹窗
    if (isDownloadingUpdate) {
        PjmAeroDialog(
            onDismissRequest = { },
            title = "正在下载更新",
            confirmButton = {},
            dismissButton = {}
        ) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(4.dp))
                Text("下载完成后将自动拉起安装，请勿关闭本页面…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // 下载失败/安装引导弹窗
    downloadError?.let { err ->
        PjmAeroDialog(
            onDismissRequest = { downloadError = null },
            title = "下载未完成",
            confirmButton = {
                Button(onClick = {
                    downloadError = null
                    updateCheckResult = null
                    UpdateChecker.openReleasePage(context)
                }) { Text("前往浏览器下载") }
            },
            dismissButton = {
                TextButton(onClick = { downloadError = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        ) {
            Text(err, style = MaterialTheme.typography.bodyMedium)
        }
    }

    integrityResults?.let { results ->
        val missing = results["missing"] ?: emptyList()
        val corrupted = results["corrupted"] ?: emptyList()
        PjmAeroDialog(
            onDismissRequest = { integrityResults = null },
            title = stringResource(R.string.dialog_title_integrity_results),
            confirmButton = { 
                if (missing.isEmpty() && corrupted.isEmpty()) Button(onClick = { integrityResults = null }) { Text(stringResource(R.string.action_dismiss)) }
                else Button(onClick = { integrityResults = null; settingsViewModel.syncDatabase() }) { Text(stringResource(R.string.action_repair_now)) }
            },
            dismissButton = { TextButton(onClick = { integrityResults = null }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Column {
                if (missing.isEmpty() && corrupted.isEmpty()) Text(stringResource(R.string.msg_integrity_ok))
                else {
                    if (missing.isNotEmpty()) Text(stringResource(R.string.msg_integrity_missing, missing.size), color = MaterialTheme.colorScheme.error)
                    if (corrupted.isNotEmpty()) Text(stringResource(R.string.msg_integrity_corrupted, corrupted.size), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.msg_integrity_advice), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    uiState.duplicateFiles?.let { groups ->
        if (groups.isEmpty()) { Toast.makeText(context, noDuplicatesMsg, Toast.LENGTH_SHORT).show(); settingsViewModel.clearDuplicateState() }
        else {
            // 核心修复：对比确认弹窗 —— 每行两张并排展示，确认无误后再勾选删除
            PjmDuplicateCompareDialog(
                groups = groups,
                onDismiss = { settingsViewModel.clearDuplicateState() },
                onConfirm = { selected -> settingsViewModel.performDeleteDuplicates(selected) }
            )
        }
    }

    uiState.biliItems?.let { items ->
        BiliScanResultDialog(items = items, onDismiss = { settingsViewModel.clearBiliState() }, onConfirm = { selected -> settingsViewModel.importBiliItems(selected) })
    }

    uiState.biliMergedVideos?.let { items ->
        BiliMergedResultDialog(items = items, onDismiss = { settingsViewModel.clearBiliMergedState() }, onConfirm = { selected -> settingsViewModel.importBiliMergedVideos(selected) })
    }
}

@Composable
private fun MainCategoryList(
    settings: Settings.AppSettings,
    onNavigate: (SettingsPage) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = "界面自适应") {
            SettingsButton(
                icon = Lucide.Palette,
                title = "外观与个性化",
                description = "当前主题: ${settings.theme}, UI 缩放: ${"%.2f".format(settings.globalUiScale)}",
                onClick = { onNavigate(SettingsPage.Appearance) }
            )
        }
        SettingsCategory(title = "通用入库策略") {
            SettingsButton(
                icon = Lucide.Database,
                title = "自动化入库配置",
                description = "自动删除原件: ${if(settings.autoDeleteOriginal) "开启" else "关闭"}, 网格: ${settings.gridSpanCount}列",
                onClick = { onNavigate(SettingsPage.Ingestion) }
            )
        }
        SettingsCategory(title = "Bilibili 内容适配") {
            SettingsButton(
                icon = Lucide.MonitorPlay,
                title = "离线视频扫描策略",
                description = "导入后清理缓存: ${if(settings.biliAutoDelete) "开启" else "关闭"}",
                onClick = { onNavigate(SettingsPage.Bilibili) }
            )
        }
        SettingsCategory(title = "高级维护工具") {
            SettingsButton(
                icon = Lucide.Wrench,
                title = "系统诊断与维护",
                description = "同步数据库、检查完整性及导出日志",
                onClick = { onNavigate(SettingsPage.Maintenance) }
            )
        }
    }
}

@Composable
private fun AppearanceSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel,
    onSelectBg: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = "色彩与主题") {
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
                title = "壁纸动态取色 (Material You)",
                description = "开启：跟随系统壁纸配色；关闭：使用 PJM 专属海洋蓝莫兰迪配色",
                checked = settings.isDynamicColorEnabled,
                onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_DYNAMIC_COLOR, it) }
            )
        }
        SettingsCategory(title = "磨砂透明背景") {
            SettingsSwitch(icon = Lucide.Image, title = stringResource(R.string.settings_title_custom_bg), checked = settings.isCustomBackgroundEnabled, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_CUSTOM_BACKGROUND_ENABLED, it) })
            if (settings.isCustomBackgroundEnabled) {
                SettingsButton(icon = Lucide.Images, title = stringResource(R.string.settings_title_select_bg), description = stringResource(R.string.settings_desc_select_bg), onClick = onSelectBg)
                SettingsStepper(icon = Lucide.Contrast, title = stringResource(R.string.settings_title_bg_opacity), value = settings.backgroundOpacity, step = 0.05f, onValueChange = { settingsViewModel.updateSetting(Settings.KEY_BACKGROUND_OPACITY, it) })
            }
        }
        SettingsCategory(title = "画布比例") {
            SettingsStepper(icon = Lucide.Maximize, title = stringResource(R.string.settings_title_ui_scale), value = settings.globalUiScale, valueRange = 0.8f..1.2f, step = 0.01f, onValueChange = { settingsViewModel.updateSetting(Settings.KEY_GLOBAL_UI_SCALE, it) })
        }
    }
}

@Composable
private fun IngestionSettings(
    settings: Settings.AppSettings,
    settingsViewModel: SettingsViewModel
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = "视图配置") {
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
        SettingsCategory(title = "自动化规则") {
            SettingsSwitch(icon = Lucide.Trash2, title = stringResource(R.string.setting_auto_delete_original), description = stringResource(R.string.setting_auto_delete_original_desc), checked = settings.autoDeleteOriginal, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_AUTO_DELETE_ORIGINAL, it) })
            SettingsSwitch(icon = Lucide.FileUp, title = stringResource(R.string.settings_title_auto_extract), description = stringResource(R.string.settings_desc_auto_extract), checked = settings.isArchiveAutoExtractionEnabled, onCheckedChange = { settingsViewModel.updateSetting(Settings.KEY_ARCHIVE_AUTO_EXTRACTION, it) })
        }
        SettingsCategory(title = "分卷策略") {
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
private fun BilibiliSettings(
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
            kotlinx.coroutines.delay(3000)
        }
    }
    // 状态变化时用横幅提示（就绪/未运行），不做成按钮
    var lastPrivilegedReady by remember { mutableStateOf(false) }
    LaunchedEffect(privilegedReady) {
        if (privilegedReady != lastPrivilegedReady) {
            lastPrivilegedReady = privilegedReady
            val msg = if (privilegedReady) "特权访问已就绪，可直接访问所有应用目录" else "特权服务未运行，请通过下方「通过电脑启动服务」启动"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategory(title = "特权访问（电脑 adb，免目录授权）") {
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
                            if (privilegedReady) "特权访问已就绪" else "特权服务未运行",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (privilegedReady) "已以 shell 身份运行，可直接访问所有应用目录"
                            else "通过下方「通过电脑启动服务」执行 adb 命令（一次性）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 电脑 adb 启动命令（带设备序列号，避免开着模拟器时发错设备）
            SettingsButton(
                icon = Lucide.Terminal,
                title = "通过电脑启动服务",
                description = "USB 连电脑执行 adb 命令，启动 PJM 内置特权服务（无需装 Shizuku）",
                onClick = {
                    EmbeddedPrivilegedIo.prepareStartScript(context)
                    showPrivilegedAdbDialog = true
                }
            )
        }
        SettingsCategory(title = "数据穿透") {
            // 导入未合并视频（选择目录，识别并合并 B站 分离的 m4s 音视频）
            SettingsButton(
                icon = Lucide.FolderDown,
                title = "导入未合并视频",
                description = "选择目录，识别 B站 分离的 m4s 音视频并合并导入",
                onClick = onOpenPicker,
                onLongClick = {
                    context.contentResolver.persistedUriPermissions.forEach {
                        try { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                    }
                    settingsViewModel.updateSetting(Settings.KEY_BILI_ROOT_URI, null)
                    Toast.makeText(context, "所有授权记录已物理销毁", Toast.LENGTH_SHORT).show()
                }
            )
            SettingsButton(
                icon = Lucide.Clapperboard,
                title = stringResource(R.string.settings_title_bili_merged_import),
                description = stringResource(R.string.settings_desc_bili_merged_import),
                onClick = onOpenMergedPicker
            )
        }
        SettingsCategory(title = "后置处理") {
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
            title = "通过电脑启动特权服务",
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("pjm_start", adbCommand))
                    showPrivilegedAdbDialog = false
                    Toast.makeText(context, "命令已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制命令") }
            },
            dismissButton = { TextButton(onClick = { showPrivilegedAdbDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) {
            Column {
                Text("步骤：", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("1. 手机 USB 连接电脑，开启开发者选项 + USB 调试\n2. 在电脑终端执行下面这条命令（已带你的设备序列号）\n3. 服务启动后即可以 shell 身份访问所有应用目录", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)
                ) {
                    Text(adbCommand, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "若 adb 提示多个设备，请先运行 adb devices 查看你手机的序列号，再手动替换命令中的 -s 参数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}

@Composable
private fun MaintenanceSettings(
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
        SettingsCategory(title = "资产维护") {
            SettingsButton(icon = Lucide.SquareCheck, title = stringResource(R.string.settings_title_clean_duplicates), description = stringResource(R.string.settings_desc_clean_duplicates), onClick = { settingsViewModel.scanForDuplicates() })
            SettingsButton(icon = Lucide.ShieldCheck, title = stringResource(R.string.settings_title_integrity_check), description = stringResource(R.string.settings_desc_integrity_check), onClick = onCheckIntegrity)
            SettingsButton(icon = Lucide.CloudUpload, title = "全库导出为 PJM 分卷", description = "打包所有私有资产为加密备份文件", onClick = onShowExportConfirm)
        }
        SettingsCategory(title = "软件更新") {
            SettingsButton(icon = Lucide.Download, title = "检查更新", description = "检查 GitHub 上是否有新版本安装包", onClick = onCheckUpdate)
        }
        SettingsCategory(title = "随机分享") {
            SettingsButton(icon = Lucide.Shuffle, title = stringResource(R.string.settings_title_random_share), description = stringResource(R.string.settings_desc_random_share), onClick = onRandomShare)
        }
        SettingsCategory(title = "系统诊断") {
            // 核心修复：清除缓存与清除日志分离
            SettingsButton(icon = Lucide.Brush, title = stringResource(R.string.settings_title_clear_cache_all), description = "仅清临时缓存，保留缩略图", onClick = onClearCache)
            SettingsButton(icon = Lucide.FileX, title = stringResource(R.string.settings_title_clear_logs), description = stringResource(R.string.settings_desc_clear_logs), onClick = onClearLogs)
            SettingsButton(icon = Lucide.Bug, title = stringResource(R.string.settings_title_export_logs), onClick = onExportLogs)
            SettingsButton(icon = Lucide.RotateCcw, title = stringResource(R.string.settings_title_factory_reset), onClick = onFactoryReset)
        }
    }
}

@Composable
fun BiliScanResultDialog(
    items: List<BiliBridge.BiliCacheItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<BiliBridge.BiliCacheItem>) -> Unit,
) {
    val selectedItems = remember { mutableStateListOf<BiliBridge.BiliCacheItem>().apply { addAll(items) } }
    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_title_bili_detected),
        confirmButton = { Button(onClick = { onConfirm(selectedItems.toList()) }, enabled = selectedItems.isNotEmpty()) { Text(stringResource(R.string.action_import_bili_now)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dialog_msg_bili_found_count, items.size), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = selectedItems.contains(item)
                    Row(modifier = Modifier.fillMaxWidth().clickable { if (isSelected) selectedItems.remove(item) else selectedItems.add(item) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.partName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BiliMergedResultDialog(
    items: List<BiliBridge.MergedVideoItem>,
    onDismiss: () -> Unit,
    onConfirm: (List<BiliBridge.MergedVideoItem>) -> Unit,
) {
    val selectedItems = remember { mutableStateListOf<BiliBridge.MergedVideoItem>().apply { addAll(items) } }
    PjmAeroDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_title_bili_merged_detected),
        confirmButton = { Button(onClick = { onConfirm(selectedItems.toList()) }, enabled = selectedItems.isNotEmpty()) { Text(stringResource(R.string.action_import_bili_merged_now)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dialog_msg_bili_merged_found_count, items.size), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = selectedItems.contains(item)
                    Row(modifier = Modifier.fillMaxWidth().clickable { if (isSelected) selectedItems.remove(item) else selectedItems.add(item) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSelected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun exportPjmLogs(context: Context, chooserTitle: String, errorMsg: String) {
    try {
        val logFile = PjmLogger.getLogFile()
        if (logFile != null && logFile.exists() && logFile.length() > 0) {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(Intent.createChooser(shareIntent, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } else Toast.makeText(context, "日志文件尚未生成或为空", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { PjmLogger.e("SettingsScreen", "Log export failed", e); Toast.makeText(context, "$errorMsg: ${e.message}", Toast.LENGTH_SHORT).show() }
}

@Composable
fun SettingsCategory(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun SettingsOptionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp, top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsSwitch(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsButton(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String? = null, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
fun SettingsStepper(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String? = null, value: Float, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, step: Float = 0.05f, onValueChange: (Float) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(text = "%.2f".format(sliderValue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { val newValue = (sliderValue - step).coerceIn(valueRange); sliderValue = newValue; onValueChange(newValue) }) { Icon(Lucide.Minus, null) }
            Slider(value = sliderValue, onValueChange = { sliderValue = it }, onValueChangeFinished = { onValueChange(sliderValue) }, valueRange = valueRange, modifier = Modifier.weight(1f))
            IconButton(onClick = { val newValue = (sliderValue + step).coerceIn(valueRange); sliderValue = newValue; onValueChange(newValue) }) { Icon(Lucide.Plus, null) }
        }
    }
}
