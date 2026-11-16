package com.dhhxfggg.pjm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.composables.icons.lucide.File
import com.composables.icons.lucide.LockKeyholeOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.X
import com.dhhxfggg.pjm.domain.service.TakeoverNotifications
import com.dhhxfggg.pjm.domain.util.*
import com.dhhxfggg.pjm.ui.component.EnhancedPasswordInput
import com.dhhxfggg.pjm.ui.component.PjmAeroDialog
import com.dhhxfggg.pjm.ui.component.UpdateNoticeBanner
import com.dhhxfggg.pjm.ui.navigation.AppNavHost
import com.dhhxfggg.pjm.ui.navigation.Screen
import com.dhhxfggg.pjm.ui.screen.PermissionScreen
import com.dhhxfggg.pjm.ui.theme.AppTheme
import com.dhhxfggg.pjm.ui.theme.PresetAmber
import com.dhhxfggg.pjm.ui.theme.PresetBiliPink
import com.dhhxfggg.pjm.ui.theme.PresetDustyBlue
import com.dhhxfggg.pjm.ui.theme.PresetForest
import com.dhhxfggg.pjm.ui.theme.PresetLavender
import com.dhhxfggg.pjm.ui.theme.PresetRose
import com.dhhxfggg.pjm.ui.theme.PresetSage
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * PJM 终端控制器 - 安全与稳定性核心。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val cryptoViewModel: CryptoViewModel by viewModels()

    private var permissionsGrantedState by mutableStateOf(false)
    private var shareGatewayUris by mutableStateOf<List<Uri>?>(null)
    private var passwordRequestInfo by mutableStateOf<String?>(null)
    private val initialRoute = MutableStateFlow<String?>(null)
    private var isShieldActive by mutableStateOf(true)

    private var deletePendingUris by mutableStateOf<List<Uri>?>(null)

    // 权限门：Android 11+ 的「所有文件访问」允许用户跳过（保险库本身不需要它）
    private var permissionBypassed by mutableStateOf(false)
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var deleteCandidateUris: List<Uri>? = null // 核心修复：增加候选池，防止系统/自定义弹窗冲突
    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        PjmLogger.i("MainActivity", "Session cold start")

        // 核心加固：默认开启安全标志
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        deleteResultLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
                if (res.resultCode == RESULT_OK) {
                    PjmLogger.i("MainActivity", "System delete confirmed by user")
                    deleteCandidateUris = null
                    deletePendingUris = null
                    Toast.makeText(this, getString(R.string.toast_original_files_deleted), Toast.LENGTH_SHORT).show()
                } else {
                    PjmLogger.w("MainActivity", "System delete rejected: ${res.resultCode}")
                    // 核心修复：如果系统弹窗被拦截（Code 0），则从候选池激活 PJM 自定义确认框
                    if (res.resultCode == 0 && deleteCandidateUris != null) {
                        PjmLogger.i("MainActivity", "System rejection detected, falling back to PJM dialog.")
                        deletePendingUris = deleteCandidateUris
                    } else {
                        deletePendingUris = null
                    }
                    deleteCandidateUris = null
                }
            }

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                checkPermissionsInternal()
            }
        checkPermissionsInternal()
        requestMissingPermissions()
        handleIntent(intent)

        setContent {
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val settings = uiState.settings
            // 核心修复：多任务进度模型 —— 并行的后台任务各自独立进度条
            val activeTasks by VaultManager.activeTasks.collectAsStateWithLifecycle()
            // 同一时间只显示一条进度：多个任务叠三根进度条既挤又互相遮挡
            val activeList = activeTasks.filter { it.isActive }
            val opTasks = activeList.take(1)
            val otherTaskCount = (activeList.size - 1).coerceAtLeast(0)
            val opState = opTasks.firstOrNull()

            // 实时同步全局焦点
            val isShieldVisible = isShieldActive

            // 核心修复：后台/隐私模式物理级高强度磨砂 (解决白屏/实色问题)
            val contentBlur by animateDpAsState(targetValue = if (isShieldVisible) 80.dp else 0.dp, label = "ShieldBlur")

            LaunchedEffect(Unit) {
                // 核心修复：collectLatest 会在后一事件到来时取消前一事件的处理块，
                // 而该块内含 IO 计算删除候选，连续两次结果会丢前一次。改用 collect 保证逐个处理完。
                cryptoViewModel.events.collect { event ->
                    when (event) {
                        is CryptoViewModel.CryptoEvent.RequestPassword -> passwordRequestInfo = event.fileName
                        is CryptoViewModel.CryptoEvent.RequestDeletePermission -> {
                            // 核心修复：直接从 ViewModel 获取最新设置，不再信任闭包捕获的 stale 值
                            val currentSettings = settingsViewModel.uiState.value.settings
                            PjmLogger.i(
                                "MainActivity",
                                "Caught RequestDeletePermission. Auto-delete: ${currentSettings.autoDeleteOriginal}",
                            )
                            if (currentSettings.autoDeleteOriginal && event.uris.isNotEmpty()) {
                                // 需求变更：pjm 加密容器不是原始资源（是加密文件），
                                // 分享入库后【不参与】"是否删除原件"的询问/删除逻辑。
                                // 分享器常改名/丢后缀，仅靠文件名不可靠；名字不像 pjm 的候选再做内容级魔数兜底，
                                // 确保解密入库的 pjm 源文件永远不会进入删除候选（与 IngestionEngine 的判定一致）。
                                val nonPjmUris =
                                    withContext(Dispatchers.IO) {
                                        event.uris.filter { uri ->
                                            val name = FileUtils.getFileName(this@MainActivity, uri)
                                            if (FileUtils.isPjmFile(name)) return@filter false
                                            !CryptoUtils.isPjmUri(this@MainActivity, uri)
                                        }
                                    }
                                if (nonPjmUris.isNotEmpty()) {
                                    triggerSystemDelete(nonPjmUris)
                                }
                            }
                        }
                    }
                }
            }

            // 保持屏幕常亮（任一任务进行中即常亮）
            LaunchedEffect(opState?.isActive) {
                if (opState?.isActive == true) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            AppTheme(settings = settings) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    if (permissionsGrantedState || permissionBypassed) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 终极隐私加固：当处于后台或隐私盾开启时，直接在内容层应用高强度模糊
                            Box(modifier = Modifier.fillMaxSize().blur(radius = contentBlur)) {
                                AppNavHost(
                                    startDestination = Screen.Main.route,
                                    pendingRoute = initialRoute,
                                )
                            }

                            // 顶部全局进度卡片：核心修复——并行的多个任务各自独立显示进度条（紧凑堆叠，不产生错位）
                            Column(
                                modifier = Modifier.align(Alignment.TopCenter),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                opTasks.forEach { task ->
                                    AnimatedVisibility(
                                        visible = true,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically(),
                                    ) {
                                        GlobalProgressOverlay(
                                            progress = task.progress,
                                            message = task.message,
                                            isIndeterminate = task.isIndeterminate,
                                            stages = task.stages,
                                            onCancel = { VaultManager.requestCancelTask(task.taskId) },
                                        )
                                    }
                                }
                            }

                            // 顶部版本更新提示：仅在无任务进度时展示，避免与进度卡重叠
                            if (opTasks.isEmpty()) {
                                UpdateNoticeBanner(modifier = Modifier.align(Alignment.TopCenter))
                            }

                            // 网关与对话框组件 (UI 分离)
                            GatewaysOverlay()

                            // 核心修复：自定义删除原件确认弹窗 (当系统弹窗无法弹出时)
                            deletePendingUris?.let { uris ->
                                PjmAeroDialog(
                                    onDismissRequest = { deletePendingUris = null },
                                    title = stringResource(R.string.dialog_title_delete_original),
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                val toDelete = deletePendingUris ?: emptyList()
                                                deletePendingUris = null
                                                performFinalDelete(toDelete)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        ) { Text(stringResource(R.string.action_confirm_delete)) }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = { deletePendingUris = null },
                                        ) { Text(stringResource(R.string.action_keep_original)) }
                                    },
                                ) {
                                    // 核心修复：与"清理重复文件"对话框一致 —— 展示可滚动的文件列表（缩略图+文件名+大小），
                                    // 数量多时可上下滚动查看全部，不会因为数量多而看不到要删除哪些。
                                    val context = LocalContext.current
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            stringResource(R.string.dialog_msg_delete_original_count, uris.size),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            // key 用 uri 字符串：列表重排/插入时让 Compose 认出同一个条目，
                                            // 避免缩略图和状态跟着位置乱跳。
                                            items(uris, key = { it.toString() }) { uri ->
                                                DeleteCandidateRow(context, uri)
                                            }
                                        }
                                    }
                                }
                            }

                            // 终极隐私层 (覆盖所有内容)
                            if (isShieldVisible) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 仅展示一个大图标引导
                                    Icon(
                                        Lucide.ShieldCheck,
                                        null,
                                        Modifier.size(120.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    )
                                }
                            }
                        }
                    } else {
                        PermissionScreen(
                            onOpenSettings = { PermissionManager.openPermissionSettings(this@MainActivity) },
                            // Android 11+ 只把「所有文件访问」当作可选能力：本地保险库本身不需要它。
                            // Android 10 及以下是运行时权限，由 requestMissingPermissions() 直接弹系统框。
                            onSkip =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    { permissionBypassed = true }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }

    /** 隐私护盾 V7：针对 Android 15 焦点震荡优化 */
    override fun onResume() {
        super.onResume()
        // 只有当真正获得焦点且处于前台时才解锁
        if (hasWindowFocus()) {
            isShieldActive = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(true)
        }
        checkPermissionsInternal()
    }

    override fun onPause() {
        super.onPause()
        // 核心加固：进入后台前立即激活隐私磨砂，并强制加锁
        isShieldActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // 尝试触发一次视觉刷新，确保 Snapshot 抓取到的是模糊层
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 核心修复：防止 Recents 界面焦点反复震荡导致标志位被清除
        // 只有在 Activity 处于 Resumed 状态且获得焦点时，才允许清除安全标志
        if (hasFocus && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            isShieldActive = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (!hasFocus) {
            // 如果是因为弹出内部 Dialog 导致的焦点丢失，不应该显示 Shield
            // 我们通过检查是否有 Dialog 正在显示来优化 (此处简化处理，主要由 onPause 负责真正的隐私保护)
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                // Activity 仍处于前台，只是失去了焦点（可能是有弹窗）
                // 此时保持 FLAG_SECURE 但不显示覆盖层以允许用户操作弹窗
                isShieldActive = false
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                isShieldActive = true
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private fun triggerSystemDelete(uris: List<Uri>) {
        // 核心修复：过滤掉 PJM 自身的日志文件，防止误删调试信息
        val logAuthority = "$packageName.fileprovider"
        val filteredUris = uris.filter { it.authority != logAuthority }

        if (filteredUris.isEmpty()) {
            PjmLogger.d("MainActivity", "All URIs are internal logs, skipping delete prompt.")
            return
        }

        PjmLogger.i("MainActivity", "Triggering delete for ${filteredUris.size} URIs")
        val intentSender = FileUtils.createDeleteRequest(this, filteredUris)
        if (intentSender != null) {
            PjmLogger.d("MainActivity", "MediaStore intent generated successfully")
            deleteCandidateUris = filteredUris
            deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } else {
            PjmLogger.w("MainActivity", "MediaStore dialog unavailable. Showing custom delete dialog.")
            deletePendingUris = filteredUris
            deleteCandidateUris = null
        }
    }

    private fun performFinalDelete(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            uris.forEach { uri ->
                try {
                    val isDoc = android.provider.DocumentsContract.isDocumentUri(this@MainActivity, uri)
                    if (isDoc) {
                        if (android.provider.DocumentsContract.deleteDocument(contentResolver, uri)) deletedCount++
                    } else if (uri.scheme == "file") {
                        val path = uri.path
                        if (path != null && File(path).delete()) deletedCount++
                    } else if (uri.scheme == "content") {
                        // 普通 media/content URI：让 provider 自己删（MediaStore 等允许时返回删除行数）
                        deletedCount += contentResolver.delete(uri, null, null)
                    }
                } catch (e: Exception) {
                    PjmLogger.e("MainActivity", "Delete failed for $uri", e)
                }
            }
            if (deletedCount > 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_cleaned_originals, deletedCount), Toast.LENGTH_SHORT).show()
                }
            } else {
                // 核心修复：普通 content:// 既不是 document 也不是 file 时，旧实现什么都不做、
                // 连提示都没有，用户以为已删除。现在明确告知需要手动删除。
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_delete_original_manual), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    private fun GatewaysOverlay() {
        shareGatewayUris?.let { uris ->
            ShareGatewayDialog(
                onStore = {
                    shareGatewayUris = null
                    cryptoViewModel.handleStore(uris)
                },
                onEncrypt = {
                    shareGatewayUris = null
                    cryptoViewModel.handlePackAndEncrypt(uris)
                },
                onDismiss = { shareGatewayUris = null },
            )
        }
        passwordRequestInfo?.let { name ->
            PasswordInputDialog(
                fileName = name,
                onConfirm = { pwd ->
                    passwordRequestInfo = null
                    cryptoViewModel.retryWithPassword(pwd)
                },
                onDismiss = { passwordRequestInfo = null },
            )
        }
    }

    @Composable
    private fun GlobalProgressOverlay(
        progress: Float,
        message: String,
        isIndeterminate: Boolean = false,
        stages: List<Float> = emptyList(),
        onCancel: (() -> Unit)? = null,
    ) {
        val animatedProgress by animateFloatAsState(targetValue = progress, label = "SmoothProg")
        Card(
            // 核心修复：卡片紧凑化 —— 上下仅 2dp 外边距，多个进度条堆叠时不产生大间距/错位
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).statusBarsPadding(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isIndeterminate) {
                        // 不确定进度：无限循环圆环，不显示百分比
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!isIndeterminate) {
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    if (onCancel != null) {
                        Spacer(Modifier.width(4.dp))
                        // 核心新增：任务取消按钮 —— 进度条卡住时可随时中断
                        IconButton(onClick = onCancel, modifier = Modifier.size(26.dp)) {
                            Icon(
                                Lucide.X,
                                contentDescription = stringResource(R.string.content_desc_cancel_task),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    isIndeterminate ->
                        // 不确定进度：无限循环线性条
                        LinearProgressIndicator(Modifier.fillMaxWidth())

                    stages.size >= 2 ->
                        // 分阶段任务：一条进度条切成彩色分段，依次点亮，进度只会向前
                        StagedProgressBar(progress = animatedProgress, stages = stages)

                    else ->
                        LinearProgressIndicator(progress = { animatedProgress }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    /**
     * 分阶段进度条：把一条进度条按 [stages] 的累计边界切成若干彩色分段。
     *
     * 轨道色表示尚未完成，各分段按自身完成比例填充自己的颜色 ——
     * 用户看到「精确查重 / 图片指纹 / 候选比对 / 分组收尾」依次点亮，
     * 而不是对着一条单色进度条猜现在卡在哪。
     */
    @Composable
    private fun StagedProgressBar(
        progress: Float,
        stages: List<Float>,
        modifier: Modifier = Modifier,
    ) {
        // 分阶段配色：色相彼此拉开（蓝 / 橙 / 玫红 / 绿 / 紫 / 粉 / 深绿），
        // 相邻阶段一眼能区分；任务阶段多于 7 个时循环使用。
        val colors =
            listOf(
                PresetDustyBlue,
                PresetAmber,
                PresetRose,
                PresetSage,
                PresetLavender,
                PresetBiliPink,
                PresetForest,
            )
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)),
        ) {
            Row(Modifier.fillMaxSize()) {
                stages.forEachIndexed { index, boundary ->
                    val start = if (index == 0) 0f else stages[index - 1]
                    val end = boundary.coerceIn(start, 1f)
                    val span = end - start
                    if (span > 0f) {
                        // 段与段之间留 1dp 缝隙，让「分类」在视觉上是硬的边界而不是渐变的色块
                        Box(
                            Modifier
                                .weight(span)
                                .fillMaxHeight()
                                .padding(end = if (index == stages.lastIndex) 0.dp else 1.dp),
                        ) {
                            val filled = ((progress - start) / span).coerceIn(0f, 1f)
                            if (filled > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(filled)
                                        .fillMaxHeight()
                                        .background(colors[index % colors.size]),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PasswordInputDialog(
        fileName: String,
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var password by remember { mutableStateOf("") }
        PjmAeroDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.dialog_title_encrypted_archive),
            icon = { Icon(Lucide.LockKeyholeOpen, null, tint = MaterialTheme.colorScheme.primary) },
            confirmButton = {
                Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            Column {
                Text(stringResource(R.string.dialog_msg_password_required, fileName), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                EnhancedPasswordInput(password = password, onPasswordChange = { password = it })
            }
        }
    }

    @Composable
    fun ShareGatewayDialog(
        onStore: () -> Unit,
        onEncrypt: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        PjmAeroDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.share_to_pjm),
            confirmButton = {
                Button(
                    onClick = onStore,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) { Text(stringResource(R.string.action_store_and_classify)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onEncrypt,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) { Text(stringResource(R.string.action_pack_and_encrypt)) }
            },
        ) {
            Text(stringResource(R.string.dialog_msg_new_file_found), style = MaterialTheme.typography.bodyMedium)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // 「应用接管」删除失败通知：把「已入库但原文件没删掉」的 uri 送回原来的删除确认弹窗，
        // 由用户走系统删除流程重试（后台服务自己拿不到授权弹窗，只能借前台这一步）。
        val takeoverFailedUris = intent.getStringArrayExtra(TakeoverNotifications.EXTRA_FAILED_URIS)
        if (!takeoverFailedUris.isNullOrEmpty()) {
            val uris = takeoverFailedUris.mapNotNull { raw -> runCatching { Uri.parse(raw) }.getOrNull() }.take(20)
            if (uris.isNotEmpty()) {
                deletePendingUris = uris
                PjmLogger.i("MainActivity", "接管失败通知：${uris.size} 个原文件待处理")
                return
            }
        }
        if (intent.getStringExtra("shortcutId") ==
            "cabinet"
        ) {
            initialRoute.value = Screen.FileViewer.createRoute(VaultManager.CAT_OTHERS)
            return
        }
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                Intent.ACTION_VIEW -> intent.data?.let { listOf(it) }
                else -> null
            } ?: return
        // 核心加固：外部 Intent 是不可信输入 —— 限制数量与 scheme，避免被第三方
        // 利用 MANAGE_EXTERNAL_STORAGE 触发批量导入/填满存储（confused deputy）。
        val safeUris = uris.take(200).filter { it.scheme == "content" || it.scheme == "file" }
        if (safeUris.isEmpty()) return
        // 核心修复：拿持久化读权限最多是 200 次跨进程调用，原先在主线程同步跑（分享大量文件时
        // 首帧明显卡顿）。现在放到下面协程的 IO 段里、且排在判定与入库之前，保证时序不变。
        // 拆分 PJM 与其他文件：
        // - PJM 加密包（含 .pjm.N 分卷）→ 自动解密并导入，无需确认
        // - 其他文件 → 弹出选择（合并入库 / 打包加密）
        //
        // 核心修复：所有判定合并到【一次】handleStore 调用。
        // 原实现先按文件名送走一批 pjm，再异步按魔数送走第二批 —— 同一次分享可能触发
        // 两次 VaultService.startStore，而第二个会被同 taskId 的防连点直接忽略：
        // 第二批文件静默不入库，用户毫无感知。
        lifecycleScope.launch {
            val (allPjm, rest) =
                withContext(Dispatchers.IO) {
                    // 权限必须先于后面的判空/入库拿到，同一段串行代码天然有序
                    grantPersistableRead(safeUris)
                    val (namedPjm, remaining) =
                        safeUris.partition { FileUtils.isPjmFile(FileUtils.getFileName(this@MainActivity, it)) }
                    val (detectedPjm, others) =
                        remaining.partition { CryptoUtils.isPjmUri(this@MainActivity, it) }
                    (namedPjm + detectedPjm) to others
                }
            if (allPjm.isNotEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_pjm_archive_detected), Toast.LENGTH_SHORT).show()
                cryptoViewModel.handleStore(allPjm)
            }
            if (rest.isNotEmpty()) shareGatewayUris = rest
        }
    }

    /**
     * 批量拿持久化读权限（跨进程调用，放 IO 线程执行）。
     *
     * 单个 uri 失败（provider 不支持 / 授权已失效）只忽略该条，不影响其它 uri 与后续导入。
     */
    private fun grantPersistableRead(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                if (uri.authority != "$packageName.fileprovider") {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkPermissionsInternal() {
        permissionsGrantedState = PermissionManager.checkPermissions(this)
    }

    /**
     * 主动请求缺失的运行时权限（核心修复）。
     * 原实现从不调用 requestPermissions：Android 7~10 上 READ/WRITE_EXTERNAL_STORAGE
     * 永远拿不到，权限页又只有一个"打开设置"按钮，等于把整个应用锁死。
     * Android 13+ 的 POST_NOTIFICATIONS 同样从未请求，前台服务进度通知因此不显示。
     */
    private fun requestMissingPermissions() {
        if (!PermissionManager.checkPermissions(this) && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissionLauncher.launch(PermissionManager.REQUIRED_PERMISSIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}

/**
 * 删除原件确认弹窗中的单行条目：缩略图 + 文件名 + 大小。
 * 与"清理重复文件"对话框条目风格一致（可滚动列表，数量多时也能查看全部）。
 */
@Composable
private fun DeleteCandidateRow(
    context: android.content.Context,
    uri: Uri,
) {
    // 核心修复：getFileName / getFileSize 都是 ContentResolver query（跨进程调用），
    // 原先在组合期同步跑 —— 一次弹 200 行的删除确认会把首帧卡住。
    // 改为 remember(uri) + IO 异步补齐：先用 uri 末段兜底，拿到真实名字/大小再刷新。
    var fileName by remember(uri) { mutableStateOf(uri.lastPathSegment?.substringAfterLast('/').orEmpty()) }
    var fileSize by remember(uri) { mutableStateOf(-1L) }
    LaunchedEffect(uri) {
        val (name, size) =
            withContext(Dispatchers.IO) {
                val resolvedName = runCatching { FileUtils.getFileName(context, uri) }.getOrNull()
                val resolvedSize = runCatching { FileUtils.getFileSize(context, uri) }.getOrDefault(-1L)
                resolvedName to resolvedSize
            }
        if (!name.isNullOrBlank()) fileName = name
        if (size >= 0L) fileSize = size
    }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    val isVideo =
        listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts")
            .any { fileName.lowercase().endsWith(it) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (loadFailed) {
                Icon(Lucide.File, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
            } else {
                AsyncImage(
                    model =
                        if (isVideo) {
                            ImageRequest
                                .Builder(context)
                                .data(uri)
                                .decoderFactory(VideoFrameDecoder.Factory())
                                .size(104, 104)
                                .build()
                        } else {
                            ImageRequest
                                .Builder(context)
                                .data(uri)
                                .size(104, 104)
                                .crossfade(true)
                                .build()
                        },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state -> if (state is AsyncImagePainter.State.Error) loadFailed = true },
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (fileSize >= 0L) FileUtils.formatFileSize(fileSize) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
