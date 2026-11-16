package com.dhhxfggg.pjm.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.ShareUtils
import com.dhhxfggg.pjm.domain.util.UpdateChecker
import com.dhhxfggg.pjm.domain.util.UpdateController
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.PjmButtonCaption
import com.dhhxfggg.pjm.ui.component.PjmDuplicateCompareDialog
import com.dhhxfggg.pjm.ui.component.PjmFolderPickerDialog
import com.dhhxfggg.pjm.ui.viewmodel.RandomShareMode
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import com.dhhxfggg.pjm.ui.viewmodel.TakeoverViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class SettingsPage {
    Appearance,
    Ingestion,
    Bilibili,
    Discovery,
    AppsTakeover,
    Audio,
    Transfer,
    Maintenance,
}

/**
 * 工业级分级设置页面 - 解决功能膨胀，提升操作效率。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    bottomPadding: Dp = 0.dp,
    // 备份已安装应用是独立路由（需要自己的搜索框与整屏列表），设置页只负责给出口
    onOpenAppBackup: () -> Unit = {},
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // 直接分享源文件需要 Activity 才能 startActivity（Context 可能不是 Activity）
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    // 应用接管：主页卡片要显示已接管数量，页面本身也复用同一个 ViewModel
    val takeoverViewModel: TakeoverViewModel = hiltViewModel()
    val managedApps by takeoverViewModel.managedApps.collectAsStateWithLifecycle()

    var currentPage by remember { mutableStateOf<SettingsPage?>(null) }

    // 清理缓存/日志后让诊断页立刻重新算占用，不用退出再进来
    var maintenanceRefresh by remember { mutableIntStateOf(0) }

    // 正在显示（或正在滑出）的二级页面：关闭后等滑出动画跑完再撤掉
    var lastPage by remember { mutableStateOf<SettingsPage?>(null) }

    // 二级页面开合进度：0 = 完全在屏幕右侧外面，1 = 完全打开。
    // 只在 graphicsLayer 的 draw 阶段读取，手势过程中不触发任何重组。
    val pageOpen = remember { Animatable(0f) }
    LaunchedEffect(currentPage) {
        if (currentPage != null) {
            lastPage = currentPage
            // 打开：从右侧外面滑进来
            runCatching {
                pageOpen.snapTo(0f)
                pageOpen.animateTo(1f, tween(220))
            }
        } else {
            // 关闭：先滑出去再撤掉。即使动画被打断，lastPage 也一定会被清掉，
            // 所以不存在"卡在某页出不去"的状态。
            runCatching { pageOpen.animateTo(0f, tween(160)) }
            if (currentPage == null) lastPage = null
        }
    }
    // 返回键、左上角按钮：同步改状态，不等任何动画
    val closePage: () -> Unit = { currentPage = null }
    if (settings.predictiveBackEnabled) {
        PredictiveBackHandler(enabled = currentPage != null) { progress ->
            try {
                progress.collect { event -> pageOpen.snapTo(1f - event.progress) }
                // 手势提交后这个协程会被系统立刻取消，所以这里只做同步状态切换，
                // 滑出动画交给上面的 LaunchedEffect
                currentPage = null
            } catch (cancel: CancellationException) {
                // 手势取消：页面滑回打开状态
                scope.launch {
                    if (currentPage != null) runCatching { pageOpen.animateTo(1f, tween(160)) }
                }
                throw cancel
            }
        }
    } else {
        BackHandler(enabled = currentPage != null) { closePage() }
    }

    // 资源加载（在组合期取好：用 LocalContext.getString 不会跟随语言/配置变化）
    val systemCleanedMsg = stringResource(R.string.toast_system_cleaned)
    val noDuplicatesMsg = stringResource(R.string.toast_no_duplicates)
    val shareLogsTitle = stringResource(R.string.chooser_title_share_logs)
    val exportFailedMsg = stringResource(R.string.error_export_failed_simple)
    val authGrantedMsg = stringResource(R.string.toast_auth_granted)
    val authGrantedSessionMsg = stringResource(R.string.toast_auth_granted_session)
    val clearingCacheMsg = stringResource(R.string.status_clearing_cache)
    val clearingLogsMsg = stringResource(R.string.status_clearing_logs)
    val logsClearedMsg = stringResource(R.string.status_logs_cleared)
    val resettingMsg = stringResource(R.string.status_resetting)
    val resetDoneMsg = stringResource(R.string.status_reset_done)

    // Launchers
    var showPjmFolderPicker by remember { mutableStateOf(false) }
    var showMergedFolderPicker by remember { mutableStateOf(false) }
    // 授权后的目标动作："cache"=回到缓存选择器 / "merged"=回到已合并选择器 / null=同样回到缓存选择器
    var pendingSafAction by remember { mutableStateOf<String?>(null) }
    // 核心修复：刚授权成功的 tree URI（会话内有效，即使未持久化），
    // 传给选择器用于穿透进入应用目录 —— 解决"授权后打不开应用内部文件夹"。
    var lastSafTreeUri by remember { mutableStateOf<Uri?>(null) }
    val biliAuthLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                    // 注意：这里只构造「稍后要执行」的 lambda。原实现在分支里先赋值再返回 lambda，
                    // 而下面两行立刻把两个 picker 都置 false —— 那次赋值是永远被覆盖的死代码。
                    val reopenPicker: () -> Unit =
                        when (action) {
                            "merged" -> {
                                { showMergedFolderPicker = true }
                            }
                            else -> {
                                { showPjmFolderPicker = true }
                            }
                        }
                    showPjmFolderPicker = false
                    showMergedFolderPicker = false
                    scope.launch {
                        kotlinx.coroutines.delay(300)
                        reopenPicker()
                    }
                    Toast
                        .makeText(
                            context,
                            if (persisted) authGrantedMsg else authGrantedSessionMsg,
                            Toast.LENGTH_SHORT,
                        ).show()
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
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                // 不 setPackage、不传 EXTRA_INITIAL_URI —— 让系统默认文件管理器决定
            }
        biliAuthLauncher.launch(intent)
    }

    val bgImagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
                        context.filesDir
                            .listFiles()
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
    var integrityResults by remember { mutableStateOf<Map<String, List<FileEntity>>?>(null) }
    var showBiliAuthExplanation by remember { mutableStateOf(false) }
    var showFactoryResetConfirm by remember { mutableStateOf(false) }
    var showRandomShareDialog by remember { mutableStateOf(false) }

    // 检查更新：同一个弹窗承担「检查中 → 结果」两个阶段（见下方弹窗）
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }

    // 应用内下载状态：统一由进程级的 UpdateController 持有。
    // 原来这些状态都在本页的 remember 里，离开设置页/旋转屏幕就丢，
    // 且下载协程绑在 rememberCoroutineScope() 上会被直接取消。
    val updateDownloadProgress by UpdateController.downloadProgress.collectAsStateWithLifecycle()
    val updateDownloadMessage by UpdateController.message.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text =
                            when (currentPage) {
                                SettingsPage.Appearance -> stringResource(R.string.settings_cat_adaptive)
                                SettingsPage.Ingestion -> stringResource(R.string.settings_cat_ingestion)
                                SettingsPage.Bilibili -> stringResource(R.string.settings_cat_bili)
                                SettingsPage.Discovery -> stringResource(R.string.settings_title_discovery)
                                SettingsPage.AppsTakeover -> stringResource(R.string.settings_title_app_takeover)
                                SettingsPage.Audio -> stringResource(R.string.settings_title_audio_playback)
                                SettingsPage.Transfer -> stringResource(R.string.settings_title_transfer)
                                SettingsPage.Maintenance -> stringResource(R.string.settings_cat_maintenance)
                                null -> stringResource(R.string.settings_title)
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    // 顶栏左侧只有一颗箭头，补一行「返回」小字让功能一眼可见；
                    // 顶栏高度固定 64dp，所以按钮视觉尺寸收到 36dp，给下面那行小字留出位置。
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(
                            onClick = { if (currentPage == null) onBack() else closePage() },
                            modifier = Modifier.size(36.dp),
                        ) {
                            // 下方小字被 PjmButtonCaption 清了语义，图标必须自己带 contentDescription，
                            // 否则 TalkBack 只念「按钮」，读屏用户不知道这是返回。
                            Icon(
                                if (currentPage == null) Lucide.ArrowLeft else Lucide.ChevronLeft,
                                stringResource(R.string.action_back),
                            )
                        }
                        PjmButtonCaption(
                            caption = stringResource(R.string.hint_back),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        // 和首页保持一致：内容铺满整屏、一直滚到底，
        // 每条列表末尾用 SettingsBottomSpacer() 补出底栏高度。
        //
        // 之前这里是把内容区整体裁到「底栏胶囊上方」—— 底栏那条永远是空白壁纸，
        // 三个按钮看起来像单独浮在外面，和下面的内容不是一张纸。
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
        ) {
            // 返回手势进行中，下层把「上一页（设置列表）」一起滑进来：
            // 上层二级页面同时右移并垫上不透明底色，避免两层文字叠在一起。
            // 列表常驻在下层；平时整体移出屏幕左侧（不可见），手势时跟手滑入。
            // 注意：这里只读 graphicsLayer 的 draw 阶段状态，不读进组合 —— 否则每个手势事件
            // 都会重组合整个设置页，拖拽时会掉帧甚至白屏。
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 没有二级页面时，列表无条件停在原位（不依赖任何动画进度），
                            // 这样"设置页一片空白/出不来"从结构上不可能出现
                            translationX = if (lastPage == null) 0f else -size.width * pageOpen.value
                        },
            ) {
                MainCategoryList(
                    settings = settings,
                    settingsViewModel = settingsViewModel,
                    managedAppCount = managedApps.size,
                    onNavigate = { currentPage = it },
                    onOpenAppBackup = onOpenAppBackup,
                )
            }
            // 二级页面：只有真的打开了页面才组合。之前这里还组合了一份设置列表
            // （AnimatedContent 的 null 分支），返回时两层列表同时滑入 = 同一画面闪两次。
            // 二级页面层：打开时从右侧滑入，关闭时滑出去再撤掉
            val shownPage = lastPage
            if (shownPage != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = size.width * (1f - pageOpen.value)
                            },
                ) {
                    // 二级页面自己不再画背景：底下的列表层已经有同一张壁纸，
                    // 预测性返回拖到一半时两层都画，就会看到"两个同样的自定义背景叠在一起"。
                    // 这里保持透明，动的是上面这层内容本身。
                    when (shownPage) {
                        SettingsPage.Appearance ->
                            AppearanceSettings(
                                settings = settings,
                                settingsViewModel = settingsViewModel,
                                onSelectBg = { bgImagePicker.launch(arrayOf("image/*")) },
                            )
                        SettingsPage.Ingestion ->
                            IngestionSettings(
                                settings = settings,
                                settingsViewModel = settingsViewModel,
                            )
                        SettingsPage.Bilibili ->
                            BilibiliSettings(
                                settings = settings,
                                settingsViewModel = settingsViewModel,
                                onOpenPicker = { showPjmFolderPicker = true },
                                onOpenMergedPicker = { showMergedFolderPicker = true },
                            )
                        SettingsPage.Discovery ->
                            DiscoverySettings(settings = settings, settingsViewModel = settingsViewModel)
                        SettingsPage.AppsTakeover ->
                            AppsTakeoverSettings(takeoverViewModel = takeoverViewModel)
                        SettingsPage.Transfer ->
                            TransferSettings()
                        SettingsPage.Audio ->
                            AudioPlaybackSettings()
                        SettingsPage.Maintenance ->
                            MaintenanceSettings(
                                settingsViewModel = settingsViewModel,
                                refreshToken = maintenanceRefresh,
                                onCheckUpdate = {
                                    if (isCheckingUpdate) return@MaintenanceSettings
                                    // 先显示弹窗，再开始网络检查
                                    showUpdateDialog = true
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
                                            VaultManager.updateProgress(
                                                0.5f,
                                                clearingCacheMsg,
                                                taskId = VaultManager.TASK_CLEAR_CACHE,
                                            )
                                            // 核心修复：清除缓存【只清临时缓存目录 cacheDir】——
                                            // 绝不删除 filesDir 下的半永久缓存：
                                            //   · thumbnails/（视频/图片缩略图）
                                            //   · pjm_thumbnail_cache/（Coil 磁盘缓存）
                                            //   · image_fingerprints/（感知指纹 .txt + 灰度 .g32）
                                            // 这些是耗时生成的数据，删除后需重新解码/计算（用户痛点）。
                                            context.cacheDir.deleteRecursively()
                                            // 磁盘目录删了不代表内存里没有：Coil 的内存缓存还留着旧缩略图，
                                            // 不清掉的话列表看起来「清了缓存还是老样子」。
                                            runCatching {
                                                val loader = coil3.SingletonImageLoader.get(context)
                                                loader.memoryCache?.clear()
                                                loader.diskCache?.clear()
                                            }
                                            VaultManager.updateProgress(1f, systemCleanedMsg, taskId = VaultManager.TASK_CLEAR_CACHE)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, systemCleanedMsg, Toast.LENGTH_SHORT).show()
                                                maintenanceRefresh++
                                            }
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
                                            VaultManager.updateProgress(
                                                0.5f,
                                                clearingLogsMsg,
                                                taskId = VaultManager.TASK_CLEAR_LOGS,
                                            )
                                            PjmLogger.clear()
                                            VaultManager.updateProgress(
                                                1f,
                                                logsClearedMsg,
                                                taskId = VaultManager.TASK_CLEAR_LOGS,
                                            )
                                            withContext(Dispatchers.Main) {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        logsClearedMsg,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                maintenanceRefresh++
                                            }
                                            delay(1200)
                                            VaultManager.clearProgress(VaultManager.TASK_CLEAR_LOGS)
                                        } finally {
                                            VaultManager.endOperation(VaultManager.TASK_CLEAR_LOGS)
                                        }
                                    }
                                },
                                onFactoryReset = { showFactoryResetConfirm = true },
                                onRandomShare = { showRandomShareDialog = true },
                            )
                    }
                }
            }
        }
    }

    // 全局对话框
    if (showBiliAuthExplanation) {
        PjmAeroDialog(
            onDismissRequest = { showBiliAuthExplanation = false },
            title = stringResource(R.string.dialog_title_dir_auth),
            icon = { Icon(Lucide.ShieldAlert, null, tint = MaterialTheme.colorScheme.primary) },
            confirmButton = {
                Button(onClick = {
                    showBiliAuthExplanation = false
                    launchSafAuth(null)
                }) { Text(stringResource(R.string.action_goto_grant)) }
            },
            dismissButton = { TextButton(onClick = { showBiliAuthExplanation = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Column {
                Text(
                    stringResource(R.string.dialog_label_auth_steps),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.dialog_msg_auth_steps), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dialog_msg_auth_restriction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            },
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
            },
        )
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
                                VaultManager.updateProgress(
                                    0.5f,
                                    resettingMsg,
                                    taskId = VaultManager.TASK_RESET,
                                )
                                settingsViewModel.resetAllSettings()
                                VaultManager.updateProgress(
                                    1f,
                                    resetDoneMsg,
                                    taskId = VaultManager.TASK_RESET,
                                )
                                delay(1200)
                                VaultManager.clearProgress(VaultManager.TASK_RESET)
                            } finally {
                                VaultManager.endOperation(VaultManager.TASK_RESET)
                            }
                        }
                        Toast.makeText(context, R.string.toast_settings_reset, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_confirm_reset)) }
            },
            dismissButton = { TextButton(onClick = { showFactoryResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Text(stringResource(R.string.dialog_msg_factory_reset_confirm), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showRandomShareDialog) {
        var count by remember { mutableStateOf(10) }
        var shareMode by remember { mutableStateOf(RandomShareMode.PACKAGE_PJM) }
        PjmAeroDialog(
            onDismissRequest = { showRandomShareDialog = false },
            title = stringResource(R.string.dialog_title_random_share),
            confirmButton = {
                Button(
                    onClick = {
                        showRandomShareDialog = false
                        when (shareMode) {
                            RandomShareMode.PACKAGE_PJM -> settingsViewModel.randomPickAndEncrypt(count)
                            RandomShareMode.DIRECT_FILES ->
                                settingsViewModel.randomPickForDirectShare(count) { result ->
                                    shareRandomPickedFiles(activity, result.paths, result.skipped)
                                }
                        }
                    },
                ) { Text(stringResource(R.string.action_start_random)) }
            },
            dismissButton = { TextButton(onClick = { showRandomShareDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dialog_msg_random_share), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.label_random_mode), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = shareMode == RandomShareMode.PACKAGE_PJM,
                        onClick = { shareMode = RandomShareMode.PACKAGE_PJM },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.mode_random_package)) }
                    SegmentedButton(
                        selected = shareMode == RandomShareMode.DIRECT_FILES,
                        onClick = { shareMode = RandomShareMode.DIRECT_FILES },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.mode_random_direct)) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text =
                        stringResource(
                            if (shareMode == RandomShareMode.PACKAGE_PJM) {
                                R.string.hint_random_package
                            } else {
                                R.string.hint_random_direct
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.label_random_count), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                // 常用数量快捷选择（核心修复：加横向滚动，避免 UI 缩放/窄屏时 chips 溢出挤压）
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 10, 20, 50).forEach { n ->
                        FilterChip(
                            selected = count == n,
                            onClick = { count = n },
                            label = { Text(stringResource(R.string.label_unit_count, n)) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 数量输入
                OutlinedTextField(
                    value = count.toString(),
                    onValueChange = { input ->
                        count = input
                            .filter { it.isDigit() }
                            .take(3)
                            .toIntOrNull()
                            ?.coerceIn(1, SettingsViewModel.MAX_RANDOM_PICK) ?: count
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_random_count)) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                )
            }
        }
    }

    // 检查更新：一个弹窗贯穿「检查中 → 结果」。
    // 先显示（点按钮立刻弹窗、弹窗里转圈），再加载（结果回填到同一个弹窗里）——
    // 原实现是「检查中弹窗」+「结果弹窗」两个：前者消失、后者弹出，视觉上闪两次窗口。
    if (showUpdateDialog) {
        val result = updateCheckResult
        PjmAeroDialog(
            onDismissRequest = {
                showUpdateDialog = false
                // 检查尚未结束时结果到达只更新状态，不再自动弹窗
                if (!isCheckingUpdate) updateCheckResult = null
            },
            title =
                when {
                    isCheckingUpdate -> stringResource(R.string.dialog_title_check_update)
                    result is UpdateChecker.CheckResult.UpdateAvailable -> stringResource(R.string.dialog_title_update_available)
                    result is UpdateChecker.CheckResult.UpToDate -> stringResource(R.string.dialog_title_update_uptodate)
                    else -> stringResource(R.string.dialog_title_update_failed)
                },
            icon =
                if (isCheckingUpdate) {
                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            confirmButton = {
                when (result) {
                    null -> Unit
                    is UpdateChecker.CheckResult.UpdateAvailable ->
                        Button(onClick = {
                            showUpdateDialog = false
                            updateCheckResult = null
                            // 下载交给进程级的 UpdateController：离开设置页也不会被取消。
                            // 横幅的「可用更新」状态与设置页检查结果共用同一条数据。
                            UpdateController.publishAvailable(result)
                            UpdateController.startDownload(context)
                        }) { Text(stringResource(R.string.action_download_install)) }

                    else ->
                        Button(onClick = {
                            showUpdateDialog = false
                            updateCheckResult = null
                        }) { Text(stringResource(R.string.action_dismiss)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    updateCheckResult = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            when {
                isCheckingUpdate ->
                    Text(stringResource(R.string.dialog_msg_checking_update), style = MaterialTheme.typography.bodyMedium)
                result is UpdateChecker.CheckResult.UpdateAvailable -> {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.label_update_current, result.currentVersion),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.label_update_latest, result.latestVersion),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.msg_update_inapp_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (result.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.label_update_release_notes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                result.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                result is UpdateChecker.CheckResult.UpToDate ->
                    Text(stringResource(R.string.msg_update_uptodate, result.currentVersion), style = MaterialTheme.typography.bodyMedium)
                result is UpdateChecker.CheckResult.Failed ->
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
        }
    }

    // 应用内下载进度弹窗
    updateDownloadProgress?.let { progress ->
        PjmAeroDialog(
            onDismissRequest = { },
            title = stringResource(R.string.dialog_title_downloading),
            confirmButton = {},
            dismissButton = {},
        ) {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.msg_downloading_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 下载失败/安装引导弹窗
    updateDownloadMessage?.let { err ->
        PjmAeroDialog(
            onDismissRequest = { UpdateController.consumeMessage() },
            title = stringResource(R.string.dialog_title_download_failed),
            confirmButton = {
                Button(onClick = {
                    UpdateController.consumeMessage()
                    updateCheckResult = null
                    UpdateChecker.openReleasePage(context)
                }) { Text(stringResource(R.string.action_browser_download)) }
            },
            dismissButton = {
                TextButton(onClick = { UpdateController.consumeMessage() }) { Text(stringResource(R.string.action_cancel)) }
            },
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
                if (missing.isEmpty() && corrupted.isEmpty()) {
                    Button(onClick = { integrityResults = null }) { Text(stringResource(R.string.action_dismiss)) }
                } else {
                    Button(onClick = {
                        integrityResults = null
                        settingsViewModel.syncDatabase()
                    }) { Text(stringResource(R.string.action_repair_now)) }
                }
            },
            dismissButton = { TextButton(onClick = { integrityResults = null }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            Column {
                if (missing.isEmpty() && corrupted.isEmpty()) {
                    Text(stringResource(R.string.msg_integrity_ok))
                } else {
                    if (missing.isNotEmpty()) {
                        Text(
                            stringResource(R.string.msg_integrity_missing, missing.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (corrupted.isNotEmpty()) {
                        Text(
                            stringResource(R.string.msg_integrity_corrupted, corrupted.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.msg_integrity_advice), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    val duplicateGroups = uiState.duplicateFiles
    // 核心修复：副作用必须移出组合函数体。
    // 原写法在组合期直接 Toast + 写状态：组合是可重入/可丢弃的，在状态传播回来之前
    // 每次重组都会再弹一次 Toast，且在组合期写被本组合读取的状态属于 Compose 禁忌
    // （可能触发额外重组循环）。
    LaunchedEffect(duplicateGroups) {
        if (duplicateGroups != null && duplicateGroups.isEmpty()) {
            Toast.makeText(context, noDuplicatesMsg, Toast.LENGTH_SHORT).show()
            settingsViewModel.clearDuplicateState()
        }
    }

    duplicateGroups?.let { groups ->
        if (groups.isNotEmpty()) {
            // 核心修复：对比确认弹窗 —— 每行两张并排展示，确认无误后再勾选删除
            PjmDuplicateCompareDialog(
                groups = groups,
                onDismiss = { settingsViewModel.clearDuplicateState() },
                onConfirm = { selected -> settingsViewModel.performDeleteDuplicates(selected) },
            )
        }
    }

    uiState.biliItems?.let { items ->
        BiliScanResultDialog(items = items, onDismiss = { settingsViewModel.clearBiliState() }, onConfirm = { selected ->
            settingsViewModel.importBiliItems(selected)
        })
    }

    uiState.biliMergedVideos?.let { items ->
        BiliMergedResultDialog(items = items, onDismiss = { settingsViewModel.clearBiliMergedState() }, onConfirm = { selected ->
            settingsViewModel.importBiliMergedVideos(selected)
        })
    }
}

/**
 * 把随机抽取到的源文件交给系统分享。
 *
 * 与"打包成 .pjm"的区别只在最后一步：这里不改动文件字节，接收方拿到的是原始图片。
 * 读权限通过 [ShareUtils]（FileProvider + ClipData）授予，直接传 `file://` 在
 * Android 7+ 会抛 FileUriExposedException。
 *
 * 抽到但已不在磁盘上的会先被告知，避免"以为发了 N 张、实际少了"。
 */
private fun shareRandomPickedFiles(
    activity: Activity?,
    filePaths: List<String>,
    skipped: Int,
) {
    if (activity == null || filePaths.isEmpty()) return
    if (skipped > 0) {
        val skippedToast =
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_random_share_skipped, skipped),
                Toast.LENGTH_SHORT,
            )
        skippedToast.show()
    }
    val (uris, failed) = ShareUtils.toShareUris(activity, filePaths.map(::File))
    if (failed.isNotEmpty()) {
        val partialToast =
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_share_partial_failed, failed.size),
                Toast.LENGTH_SHORT,
            )
        partialToast.show()
    }
    val chooser =
        ShareUtils.createShareChooser(
            activity,
            uris,
            activity.getString(R.string.chooser_title_share_content),
        )
    if (chooser == null) {
        val nothingToast =
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_share_nothing_available),
                Toast.LENGTH_SHORT,
            )
        nothingToast.show()
        return
    }
    runCatching { activity.startActivity(chooser) }
        .onFailure {
            PjmLogger.e("SettingsScreen", "No activity to handle share", it)
            val failedToast =
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_share_nothing_available),
                    Toast.LENGTH_SHORT,
                )
            failedToast.show()
        }
}
