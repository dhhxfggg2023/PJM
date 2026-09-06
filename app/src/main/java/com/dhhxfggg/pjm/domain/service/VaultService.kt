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
    private var currentJob: Job? = null

    /** 当前任务 id（store/encrypt），进度条独立显示 */
    private var currentTaskId: String = TASK_STORE

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

        // 核心修复：防重复触发 —— 同任务 id 已有进行中任务时忽略本次启动（连点不叠加）；
        // 不同任务（如查重/删除）允许并行，互不阻塞。
        val taskId = if (action == ACTION_ENCRYPT) TASK_ENCRYPT else TASK_STORE
        currentTaskId = taskId
        if (!VaultManager.tryBeginOperation(taskId)) {
            PjmLogger.w("VaultService", "Operation already in progress, ignoring duplicate trigger")
            return START_NOT_STICKY
        }

        // Android 14+ requirement: Specify foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Preparing operation...", 0),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Preparing operation...", 0))
        }

        currentJob?.cancel()
        currentJob =
            serviceScope.launch {
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
                        ACTION_ENCRYPT -> {
                            // 顶部横幅：加密开始（独立任务 id，可与其他任务并行）
                            VaultManager.updateProgress(0f, getString(R.string.status_encrypting), taskId = TASK_ENCRYPT)
                            val volumes =
                                repository
                                    .packAndEncrypt(uris) { progress ->
                                        updateNotification("Packing and encrypting...", (progress * 100).toInt())
                                        VaultManager.updateProgress(progress, getString(R.string.status_encrypting), taskId = TASK_ENCRYPT)
                                    }.getOrThrow()
                            // 顶部横幅：加密完成（delay(500) 后 finally 清除）
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
                    delay(500.milliseconds) // Brief delay to ensure UI consistency
                } catch (e: Exception) {
                    PjmLogger.e("VaultService", "Task failed", e)
                    VaultManager.updateProgress(0f, "Operation failed: ${e.message}", taskId = taskId, isError = true)
                    VaultManager.notifyResult(OperationResult.Error(action, e.message ?: "Unknown error"))
                    delay(2.seconds)
                } finally {
                    VaultManager.clearProgress(taskId)
                    VaultManager.endOperation(taskId) // 释放该任务（不影响其他并发任务）
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

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
        PjmLogger.w("VaultService", "Foreground service timed out for type $fgsType")
        currentJob?.cancel()
        VaultManager.updateProgress(0f, "Operation timed out", taskId = TASK_ENCRYPT, isError = true)
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
            VaultManager.updateProgress(progress / 100f, status ?: currentTaskMessage(), taskId = currentTaskId)
        } else if (status != null) {
            VaultManager.updateProgress(currentTaskProgress(), status, taskId = currentTaskId)
        }
    }

    private fun currentTaskMessage(): String =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == currentTaskId }
            ?.message ?: ""

    private fun currentTaskProgress(): Float =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == currentTaskId }
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
