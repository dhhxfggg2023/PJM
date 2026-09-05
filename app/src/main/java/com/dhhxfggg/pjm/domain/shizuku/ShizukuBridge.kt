package com.dhhxfggg.pjm.domain.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.dhhxfggg.pjm.IFileBridgeService
import com.dhhxfggg.pjm.domain.util.PjmLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File

/**
 * Shizuku 桥接核心封装。
 *
 * 负责：
 * 1. Shizuku 服务可用性检测（pingBinder / getUid）
 * 2. 运行时授权流程（checkSelfPermission / requestPermission）
 * 3. UserService 生命周期管理（bindUserService / 自动重连）
 * 4. 高层文件操作 API（列目录 / 复制 / 删除 / 读文本），供 BiliBridge 调用
 *
 * 所有对 Android/data 的文件 I/O 都在 UserService（shell/root 身份）进程内执行。
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val REQUEST_CODE = 10001

    /** Shizuku 管理器的包名（官方包名，用于检测是否已安装 + 定位 start.sh） */
    const val SHIZUKU_MANAGER_PKG = "moe.shizuku.privileged.api"

    private var appContext: Context? = null

    /**
     * 授权状态机。
     */
    sealed class AuthState {
        /** Shizuku App 未安装 */
        data object NotInstalled : AuthState()
        /** 已安装但服务未运行（需在 Shizuku App 中启动） */
        data object NotRunning : AuthState()
        /** 服务运行中但 PJM 未获授权 */
        data object NoPermission : AuthState()
        /** 已授权就绪，可用 */
        data object Ready : AuthState()
        /** 进程为 root（uid=0），天然可用 */
        data object Root : AuthState()
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotInstalled)
    /** 暴露给 UI 观察的授权状态 */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // UserService 绑定状态
    private val _serviceReady = MutableStateFlow(false)
    val serviceReady: StateFlow<Boolean> = _serviceReady.asStateFlow()

    private var boundService: IFileBridgeService? = null

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            PjmLogger.i(TAG, "Permission result: granted=$grantResult")
            refreshState()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        PjmLogger.i(TAG, "Binder received, refreshing state")
        refreshState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        PjmLogger.i(TAG, "Binder dead, clearing state")
        _authState.value = AuthState.NotRunning
        _serviceReady.value = false
        boundService = null
    }

    /** Shizuku 13.x 使用标准 android.content.ServiceConnection */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            PjmLogger.i(TAG, "UserService connected: $name")
            boundService = service?.let { IFileBridgeService.Stub.asInterface(it) }
            _serviceReady.value = boundService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            PjmLogger.i(TAG, "UserService disconnected: $name")
            boundService = null
            _serviceReady.value = false
        }
    }

    private var userServiceArgs: Shizuku.UserServiceArgs? = null

    /**
     * 初始化：注册监听器并刷新状态。在 Application.onCreate 调用。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        try { ShizukuProvider.enableMultiProcessSupport(false) } catch (_: Throwable) {}

        Shizuku.addRequestPermissionResultListener(requestPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 判断是否已安装 Shizuku 管理器
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PKG, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            _authState.value = AuthState.NotInstalled
            return
        }
        refreshState()
    }

    /**
     * 刷新当前授权状态。
     */
    fun refreshState() {
        val state = try {
            if (!Shizuku.pingBinder()) {
                AuthState.NotRunning
            } else {
                when {
                    Shizuku.getUid() == 0 -> AuthState.Root
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> AuthState.Ready
                    else -> AuthState.NoPermission
                }
            }
        } catch (e: Throwable) {
            PjmLogger.e(TAG, "refreshState failed", e)
            AuthState.NotInstalled
        }
        _authState.value = state
        if (state == AuthState.Ready || state == AuthState.Root) {
            bindService()
        }
    }

    /**
     * 发起授权请求（需在 UI 线程/Activity 上下文调用）。
     */
    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                PjmLogger.i(TAG, "Permission rationale should be shown")
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "requestPermission failed", e)
        }
    }

    private fun bindService() {
        if (boundService != null || _serviceReady.value) return
        val ctx = appContext ?: return
        if (userServiceArgs == null) {
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(ctx, FileBridgeService::class.java)
            )
                .daemon(false)
                .processNameSuffix("file_bridge")
                .version(1)
        }
        try {
            Shizuku.bindUserService(userServiceArgs!!, serviceConnection)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "bindService failed", e)
        }
    }

    /**
     * 在服务可用时执行操作，返回结果或 null。
     */
    private suspend fun <T> withService(block: (IFileBridgeService) -> T?): T? {
        var service = boundService
        if (service == null) {
            bindService()
            // 等待绑定（最多 2 秒）
            repeat(20) {
                if (boundService != null) return@repeat
                kotlinx.coroutines.delay(100)
            }
            service = boundService
        }
        if (service == null) return null
        return try {
            block(service)
        } catch (e: Exception) {
            PjmLogger.e(TAG, "Service call failed", e)
            null
        }
    }

    // ============ 高层文件 API（供 BiliBridge 调用） ============
    // 统一入口：优先内置特权服务（无需 Shizuku App），其次 Shizuku API，最后返回 null。

    /** 简易文件信息（复用 EmbeddedPrivilegedIo.FileInfo） */
    typealias FileInfo = EmbeddedPrivilegedIo.FileInfo

    /**
     * 列出目录内容（shell 身份）。
     * 优先内置服务，其次 Shizuku。
     */
    suspend fun listFiles(context: Context, path: String): List<FileInfo>? = withContext(Dispatchers.IO) {
        // 内置模式优先
        val ctx = appContext ?: context
        val embeddedResult = EmbeddedPrivilegedIo.listFiles(ctx, path)
        if (embeddedResult != null) return@withContext embeddedResult
        // Shizuku 回退
        withService { svc ->
            svc.listFiles(path)?.mapNotNull { line ->
                try {
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        FileInfo(parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L, parts[0] == "D")
                    } else null
                } catch (_: Exception) { null }
            }
        }
    }

    /**
     * 递归查找目录下所有文件（shell 身份，用于扫描 B 站缓存）。
     */
    suspend fun walkFiles(context: Context, path: String, maxDepth: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.walkFiles(ctx, path, maxDepth)
        }
        val results = mutableListOf<String>()
        suspend fun walk(p: String, depth: Int) {
            if (depth > maxDepth) return
            val entries = runBlockingList(p) ?: return
            for (e in entries) {
                val full = "$p/${e.name}"
                if (e.isDirectory) walk(full, depth + 1)
                else results.add(full)
            }
        }
        walk(path, 0)
        results
    }

    /** 供内部递归使用的同步列目录（避免递归中 suspend 限制） */
    private suspend fun runBlockingList(path: String): List<FileInfo>? = withService { svc ->
        svc.listFiles(path)?.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size >= 2) {
                    FileInfo(parts[1], parts.getOrNull(2)?.toLongOrNull() ?: 0L, parts[0] == "D")
                } else null
            } catch (_: Exception) { null }
        }
    }

    /**
     * 复制文件到目标路径（shell 身份）。
     * @return 复制的字节数，失败返回 -1
     */
    suspend fun copyFile(context: Context, srcPath: String, destPath: String): Long = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.copyFile(ctx, srcPath, destPath)
        }
        withService { it.copyFile(srcPath, destPath) } ?: -1
    }

    /**
     * 删除文件或目录（shell 身份，递归）。
     */
    suspend fun deletePath(context: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.deletePath(ctx, path)
        }
        withService { it.deletePath(path) } ?: false
    }

    /**
     * 检查路径是否存在。
     */
    suspend fun exists(context: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.exists(ctx, path)
        }
        withService { it.exists(path) } ?: false
    }

    /**
     * 读取小文本文件（shell 身份）。
     */
    suspend fun readTextFile(context: Context, path: String): String? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.readTextFile(ctx, path)
        }
        withService { it.readTextFile(path) }
    }

    /**
     * 将 Android/data 下的文件复制到应用 cacheDir（主进程可读）。
     * @return 复制后的本地文件，失败返回 null
     */
    suspend fun copyToCache(context: Context, srcPath: String): File? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context
        // 内置模式优先：复制到双方共享目录（外部私有目录，shell 可写）
        if (EmbeddedPrivilegedIo.isAvailable(ctx)) {
            return@withContext EmbeddedPrivilegedIo.copyToShared(ctx, srcPath)
        }
        // Shizuku 模式：UserService 以 shell 身份运行，可写 cacheDir 吗？不行——同样用共享目录
        val externalDir = context.getExternalFilesDir(null) ?: return@withContext null
        val tmpDir = File(externalDir, "tmp").apply { mkdirs() }
        val srcName = srcPath.substringAfterLast('/')
        val dest = File(tmpDir, "shizuku_${System.nanoTime()}_$srcName")
        val copied = copyFile(context, srcPath, dest.absolutePath)
        if (copied > 0 && dest.exists()) dest else { dest.delete(); null }
    }

    /**
     * 判断当前是否处于可用状态（内置特权服务 或 Shizuku 任一可用）。
     */
    fun isAvailable(): Boolean {
        // 内置服务可用性（不阻塞，快速探测）
        return _authState.value == AuthState.Ready || _authState.value == AuthState.Root
    }

    /**
     * 判断内置特权服务（无需 Shizuku App）是否可用。
     */
    suspend fun isEmbeddedAvailable(context: Context): Boolean {
        val ctx = appContext ?: context
        return EmbeddedPrivilegedIo.isAvailable(ctx)
    }
}
