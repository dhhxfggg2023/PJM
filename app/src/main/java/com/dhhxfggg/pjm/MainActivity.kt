package com.dhhxfggg.pjm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LockKeyholeOpen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.dhhxfggg.pjm.domain.util.*
import com.dhhxfggg.pjm.ui.navigation.AppNavHost
import com.dhhxfggg.pjm.ui.navigation.Screen
import com.dhhxfggg.pjm.ui.screen.PermissionScreen
import com.dhhxfggg.pjm.ui.theme.AppTheme
import com.dhhxfggg.pjm.ui.viewmodel.CryptoViewModel
import com.dhhxfggg.pjm.ui.viewmodel.SettingsViewModel
import com.dhhxfggg.pjm.ui.component.EnhancedPasswordInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val cryptoViewModel: CryptoViewModel by viewModels()

    private var permissionsGrantedState by mutableStateOf(false)
    private var shareGatewayUris by mutableStateOf<List<Uri>?>(null)
    private var passwordRequestInfo by mutableStateOf<String?>(null)
    
    private var initialRoute by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> checkPermissionsInternal() }

    private lateinit var deleteResultLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        // 开启全屏显示边缘到边缘
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        deleteResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "原文件已成功清理", Toast.LENGTH_SHORT).show()
            }
        }

        checkPermissionsInternal()
        handleIntent(intent)
        
        setContent {
            val uiState by settingsViewModel.uiState.collectAsState()
            val settings = uiState.settings
            val isInitialized = uiState.isInitialized
            val opState by VaultManager.operationState.collectAsState()
            val currentAutoDelete by rememberUpdatedState(settings.autoDeleteOriginal)

            // 监听全局加密/解密事件
            LaunchedEffect(Unit) {
                cryptoViewModel.events.collectLatest { event ->
                    when (event) {
                        is CryptoViewModel.CryptoEvent.RequestSystemOpen -> openWithSystemTool(event.uri, event.fileName)
                        is CryptoViewModel.CryptoEvent.RequestPassword -> passwordRequestInfo = event.fileName
                        is CryptoViewModel.CryptoEvent.RequestDeletePermission -> {
                            if (currentAutoDelete) {
                                triggerSystemDelete(event.uris)
                            }
                        }
                    }
                }
            }

            // 保持屏幕常亮
            LaunchedEffect(opState.isActive) {
                if (opState.isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            
            Crossfade(
                targetState = isInitialized, 
                animationSpec = tween(300), 
                label = "SplashFade"
            ) { initialized ->
                if (initialized) {
                    AppTheme(settings = settings) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent // 关键修复：确保 Surface 透明，不遮挡自定义背景
                        ) {
                            if (permissionsGrantedState) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AppNavHost(startDestination = initialRoute ?: Screen.Main.route)

                                    // 全局进度条浮层
                                    AnimatedVisibility(
                                        visible = opState.isActive,
                                        enter = fadeIn() + expandVertically(),
                                        exit = fadeOut() + shrinkVertically(),
                                        modifier = Modifier.align(Alignment.TopCenter)
                                    ) { 
                                        GlobalProgressOverlay(opState.progress, opState.message) 
                                    }

                                    // 分享/外部文件进入时的网关选择
                                    shareGatewayUris?.let { uris ->
                                        ShareGatewayDialog(
                                            onStore = { shareGatewayUris = null; cryptoViewModel.handleStore(uris) },
                                            onEncrypt = { shareGatewayUris = null; cryptoViewModel.handlePackAndEncrypt(uris) },
                                            onDismiss = { shareGatewayUris = null }
                                        )
                                    }

                                    // 密码输入弹窗
                                    passwordRequestInfo?.let { fileName ->
                                        PasswordInputDialog(
                                            fileName = fileName,
                                            onConfirm = { pwd -> passwordRequestInfo = null; cryptoViewModel.retryWithPassword(pwd) },
                                            onDismiss = { passwordRequestInfo = null }
                                        )
                                    }
                                }
                            } else {
                                PermissionScreen(
                                    onRequestPermissions = { permissionLauncher.launch(PermissionManager.REQUIRED_PERMISSIONS) },
                                    onOpenSettings = { PermissionManager.openPermissionSettings(this@MainActivity) }
                                )
                            }
                        }
                    }
                } else { 
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) 
                }
            }
        }
    }

    private fun triggerSystemDelete(uris: List<Uri>) {
        val intentSender = FileUtils.createDeleteRequest(this@MainActivity, uris)
        if (intentSender != null) {
            deleteResultLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }
    }

    @Composable
    private fun BoxScope.GlobalProgressOverlay(progress: Float, message: String) {
        // 使用动画处理进度值，确保进度条增长极其丝滑
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "SmoothProgress"
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding() // 确保在全屏模式下不被遮挡
                .align(Alignment.TopCenter),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(text = message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(text = "${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
    }

    @Composable
    fun PasswordInputDialog(fileName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Lucide.LockKeyholeOpen, null) },
            title = { Text("加密归档") },
            text = {
                Column {
                    Text("文件 $fileName 已加密，请输入密码：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    EnhancedPasswordInput(password = password, onPasswordChange = { password = it })
                }
            },
            confirmButton = { Button(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("确认") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }

    @Composable
    fun ShareGatewayDialog(onStore: () -> Unit, onEncrypt: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("分享到 PJM") },
            text = { Text("发现新文件，如何存放？") },
            confirmButton = { Button(onClick = onStore) { Text(stringResource(R.string.action_store_and_classify)) } },
            dismissButton = { TextButton(onClick = onEncrypt) { Text("🔒 打包并加密") } }
        )
    }

    private fun openWithSystemTool(uri: Uri, fileName: String) {
        try {
            val targetUri = if (uri.scheme == "file" || uri.path?.contains(filesDir.absolutePath) == true) {
                PjmContentProvider.getUriForFile(this, File(uri.path ?: ""))
            } else uri
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) { Toast.makeText(this, "无法调起外部工具", Toast.LENGTH_SHORT).show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        
        when (intent.getStringExtra("shortcutId")) {
            "cabinet" -> { initialRoute = Screen.FileViewer.createRoute(VaultManager.CAT_OTHERS); return }
            "encrypt" -> { }
        }

        val uris = when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }
            Intent.ACTION_VIEW -> intent.data?.let { listOf(it) }
            else -> null
        }?.filterNotNull() ?: return

        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        }

        if (uris.all { FileUtils.isPjmFile(FileUtils.getFileName(this, it)) }) {
            Toast.makeText(this, "识别到 PJM 加密包，自动存放中...", Toast.LENGTH_SHORT).show()
            cryptoViewModel.handleStore(uris)
        } else shareGatewayUris = uris
    }

    private fun checkPermissionsInternal() { permissionsGrantedState = PermissionManager.checkPermissions(this) }
    override fun onResume() { super.onResume(); checkPermissionsInternal() }
}
