package com.dhhxfggg.pjm.domain.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.dhhxfggg.pjm.MainActivity
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.OperationResult
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Foreground Service responsible for long-running vault operations such as file ingestion
 * and batch encryption.
 *
 * This service is compliant with Android 15 (API 35) standards, including:
 * - Proper `foregroundServiceType` declaration (DATA_SYNC).
 * - Implementation of [onTimeout] to handle the 6-hour execution limit.
 * - Throttled notification updates to optimize system performance.
 *
 * @author PJM Industrial Standards 2026
 */
@AndroidEntryPoint
class VaultService : Service() {
    @Inject
    lateinit var repository: FileRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 每个 taskId 一个独立 Job（核心修复）。
     * 旧实现只有一个 currentJob，新任务一到就 cancel 旧的 —— 入库与加密并行时
     * 先跑的那个会被静默杀掉；旧任务的 stopSelf() 还会把新任务一起带走。
     */
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 最近一次启动的任务 id（仅用于前台通知文案，任务本身互相独立） */
    private var lastTaskId: String = TASK_STORE

    // Throttling for system notification updates
    private var lastNotificationTime = 0L

    companion object {
        private const val CHANNEL_ID = "vault_operation_channel"
        private const val NOTIFICATION_ID = 1001

        /** 加密/入库任务进度 id（独立进度条，可与其他任务并行） */
        private const val TASK_ENCRYPT = "encrypt"

        const val ACTION_STORE = "com.dhhxfggg.pjm.ACTION_STORE"
        const val ACTION_ENCRYPT = "com.dhhxfggg.pjm.ACTION_ENCRYPT"

        /** 入库任务进度 id */
        private const val TASK_STORE = "store"

        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_PASSWORD = "extra_password"

        /**
         * Starts the vault storage process.
         */
        fun startStore(
            context: Context,
            uris: List<Uri>,
            password: String? = null,
        ) {
            val intent =
                Intent(context, VaultService::class.java).apply {
                    action = ACTION_STORE
                    putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                    putExtra(EXTRA_PASSWORD, password)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Starts the vault encryption/packing process.
         */
        fun startEncrypt(
            context: Context,
            uris: List<Uri>,
        ) {
            val intent =
                Intent(context, VaultService::class.java).apply {
                    action = ACTION_ENCRYPT
                    putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java) ?: emptyList()
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        // 每个 taskId 一个独立 Job：入库与加密可以真正并行，互不取消。
        val taskId = if (action == ACTION_ENCRYPT) TASK_ENCRYPT else TASK_STORE
        lastTaskId = taskId

        // 前台服务契约：必须 5 秒内 startForeground，否则系统直接杀进程。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Preparing operation...", 0),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Preparing operation...", 0))
        }

        if (!VaultManager.tryBeginOperation(taskId)) {
            PjmLogger.w("VaultService", "Operation already in progress, taskId=" + taskId)
            // 核心修复：同 taskId 的第二次触发不再静默丢弃 —— 明确告知用户。
            VaultManager.notifyResult(
                OperationResult.Error(action, getString(R.string.toast_operation_in_progress)),
            )
            if (jobs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val job =
            serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    when (action) {
                        ACTION_STORE -> {
                            val summary =
                                repository.storeFiles(
                                    uris = uris,
                                    password = password,
                                    onStatus = { updateNotification(it) },
                                    onProgress = { updateNotification(null, (it * 100).toInt()) },
                                    onUnsupported = { _, _ -> },
                                )
                            if (!VaultManager.isTaskCancelled(taskId)) {
                                VaultManager.notifyResult(
                                    OperationResult.Success(
                                        action = ACTION_STORE,
                                        // 只上报以普通文件入库的源 uri（用于“是否删除原件”询问）；
                                        // pjm 容器只解密不落库，已被 IngestionEngine 排除在 deletableUris 之外。
                                        uris = summary.deletableUris,
                                        imported = summary.imported,
                                        skipped = summary.skipped,
                                        failed = summary.failed,
                                    ),
                                )
                            }
                        }
                        ACTION_ENCRYPT -> {
                            VaultManager.updateProgress(0f, getString(R.string.status_encrypting), taskId = TASK_ENCRYPT)
                            val volumes =
                                repository
                                    .packAndEncrypt(uris) { progress ->
                                        // 通知栏也走资源：写死英文时中文系统上仍是英文
                                        updateNotification(getString(R.string.status_encrypting), (progress * 100).toInt())
                                        VaultManager.updateProgress(progress, getString(R.string.status_encrypting), taskId = TASK_ENCRYPT)
                                    }.getOrThrow()
                            if (!VaultManager.isTaskCancelled(taskId)) {
                                VaultManager.updateProgress(1f, getString(R.string.status_encrypt_success), taskId = TASK_ENCRYPT)
                                VaultManager.notifyResult(
                                    OperationResult.Success(
                                        action = ACTION_ENCRYPT,
                                        uris = uris,
                                        volumes = volumes,
                                    ),
                                )
                            }
                        }
                    }
                    delay(500.milliseconds) // Brief delay to ensure UI consistency
                } catch (e: CancellationException) {
                    PjmLogger.i("VaultService", "Task cancelled: " + taskId)
                } catch (e: Exception) {
                    PjmLogger.e("VaultService", "Task failed", e)
                    VaultManager.updateProgress(
                        0f,
                        "Operation failed: " + (e.message ?: "unknown"),
                        taskId = taskId,
                        isError = true,
                    )
                    VaultManager.notifyResult(OperationResult.Error(action, e.message ?: "Unknown error"))
                    delay(2.seconds)
                } finally {
                    VaultManager.clearProgress(taskId)
                    VaultManager.endOperation(taskId)
                    jobs.remove(taskId)
                    // 只有所有任务都结束了才停掉服务；停早了会 onDestroy → cancel 掉其它任务
                    if (jobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        jobs[taskId] = job
        job.start()

        return START_NOT_STICKY
    }

    /**
     * Android 15 (API 35) Requirement: Handle foreground service timeouts.
     * The system allows a maximum of 6 hours for DATA_SYNC services.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        PjmLogger.w("VaultService", "Foreground service timed out for type " + fgsType)
        // 超时同样必须释放所有任务槽位，否则 taskId 永久停在 isActive=true，
        // 之后同类型任务全被防连点拦下，只能重启应用。
        jobs.keys.toList().forEach { id ->
            VaultManager.updateProgress(0f, "Operation timed out", taskId = id, isError = true)
            VaultManager.endOperation(id)
        }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(
        status: String?,
        progress: Int = -1,
    ) {
        val currentTime = System.currentTimeMillis()

        // Throttling: UI updates via StateFlow are instant, but notification updates are rate-limited to 1s.
        // Critical states (0% or 100%) bypass throttling for immediate feedback.
        if (currentTime - lastNotificationTime > 1000 || progress == 100 || progress == 0) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(status ?: "Processing...", progress))
            lastNotificationTime = currentTime
        }

        // Always update the internal state for smooth UI progress bars
        if (progress >= 0) {
            VaultManager.updateProgress(progress / 100f, status ?: currentTaskMessage(), taskId = lastTaskId)
        } else if (status != null) {
            VaultManager.updateProgress(currentTaskProgress(), status, taskId = lastTaskId)
        }
    }

    private fun currentTaskMessage(): String =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == lastTaskId }
            ?.message ?: ""

    private fun currentTaskProgress(): Float =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == lastTaskId }
            ?.progress ?: 0f

    private fun createNotification(
        content: String,
        progress: Int,
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("PJM Vault Operation")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress, progress < 0)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Vault Operations",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress for file ingestion and encryption"
                    setShowBadge(false)
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
