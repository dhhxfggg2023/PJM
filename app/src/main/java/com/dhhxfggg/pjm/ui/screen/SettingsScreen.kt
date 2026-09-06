package com.dhhxfggg.pjm.ui.screen

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

internal enum class SettingsPage {
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
