package com.dhhxfggg.pjm.domain.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    /**
     * 主线程串行化「移除 job + 判空 + 停服」。
     * onStartCommand 也在主线程写 jobs[taskId] = job，两者互斥，
     * 旧任务不会在判空后把主线程刚启动的第二个任务 stop 掉。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次 onStartCommand 的 startId（主线程写读），用于 stopSelf(startId) 判断 */
    private var lastStartId = 0

    // Throttling for system notification updates
    private var lastNotificationTime = 0L

    /**
     * 最近一次因进度而更新的通知。
     *
     * 重复触发（同 taskId 被防连点拦下、或另一类任务已在跑）时 `startForeground` 仍必须调用，
     * 但不能把运行中任务的进度通知重置成「准备中」—— 留着上一份原样再 post 一次即可。
     * 写在协程线程、读在主线程，故用 @Volatile。
     */
    @Volatile
    private var lastNotification: Notification? = null

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
        lastStartId = startId
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java) ?: emptyList()
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        // 每个 taskId 一个独立 Job：入库与加密可以真正并行，互不取消。
        val taskId = if (action == ACTION_ENCRYPT) TASK_ENCRYPT else TASK_STORE

        // 前台服务契约：必须 5 秒内 startForeground，否则系统直接杀进程。
        // 核心修复：已有任务在跑时不能把它的进度通知覆盖成「准备中」——
        // 有运行中的通知就原样复用，只有没有时才显示准备文案。
        val foregroundNotification =
            (if (jobs.isNotEmpty()) lastNotification else null)
                ?: createNotification(getString(R.string.status_processing), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                foregroundNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, foregroundNotification)
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
                                    onStatus = { updateNotification(it, taskId = taskId) },
                                    onProgress = { updateNotification(null, (it * 100).toInt(), taskId = taskId) },
                                    onUnsupported = { _, _ -> },
                                )
                            if (!VaultManager.isTaskCancelled(taskId)) {
                                VaultManager.notifyResult(
                                    OperationResult.Success(
                                        action = ACTION_STORE,
                                        // 只上报以普通文件入库的源 uri（用于“是否删除原件”询问）；
                                        // pjm 容器只解密不落库，已被 IngestionEngine 排除在 deletableUris 之外。
                                        // 再兜一层：一个都没入库成功时绝不上报可删原件 —— 否则库内副本
                                        // （已在失败回滚时被 shred）和原件会一起消失。
                                        uris = if (summary.imported > 0) summary.deletableUris else emptyList(),
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
                                        updateNotification(getString(R.string.status_encrypting), (progress * 100).toInt(), taskId = taskId)
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
                    // 文案走资源：中文系统上不再混排英文（异常无 message 时退回通用「读取失败」）
                    val reason = e.message?.takeIf { it.isNotBlank() }
                    val errorText =
                        reason?.let { getString(R.string.error_processing_failed, it) }
                            ?: getString(R.string.error_read_failed)
                    VaultManager.updateProgress(0f, errorText, taskId = taskId, isError = true)
                    VaultManager.notifyResult(OperationResult.Error(action, errorText))
                    delay(2.seconds)
                } finally {
                    VaultManager.clearProgress(taskId)
                    VaultManager.endOperation(taskId)
                    // 核心修复：移除 job / 判空 / 停服必须回到主线程串行执行，
                    // 与 onStartCommand 里的 jobs[taskId] = job 互斥；否则旧任务在
                    // 判空之后才 stopSelf()，会把主线程刚启动的并发任务一起杀掉。
                    mainHandler.post {
                        jobs.remove(taskId)
                        if (jobs.isEmpty()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            // 带最新 startId：AMS 若已收到更新的 start 请求就拒绝停止，
                            // 覆盖「start 已排队但 onStartCommand 还没执行」的窗口，
                            // 又不会像无参 stopSelf() 那样白白停掉正在等待启动的任务。
                            stopSelf(lastStartId)
                        }
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

    /** 每个任务各自上报自己的 taskId：旧实现共用 lastTaskId，并发任务进度会串台 */
    private fun updateNotification(
        status: String?,
        progress: Int = -1,
        taskId: String,
    ) {
        val currentTime = System.currentTimeMillis()

        // Throttling: UI updates via StateFlow are instant, but notification updates are rate-limited to 1s.
        // Critical states (0% or 100%) bypass throttling for immediate feedback.
        if (currentTime - lastNotificationTime > 1000 || progress == 100 || progress == 0) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = createNotification(status ?: getString(R.string.status_processing), progress)
            // 记下这一份：随后重复触发 startForeground 时原样复用，不覆盖运行中任务的进度
            lastNotification = notification
            notificationManager.notify(NOTIFICATION_ID, notification)
            lastNotificationTime = currentTime
        }

        // Always update the internal state for smooth UI progress bars
        if (progress >= 0) {
            VaultManager.updateProgress(progress / 100f, status ?: currentTaskMessage(taskId), taskId = taskId)
        } else if (status != null) {
            VaultManager.updateProgress(currentTaskProgress(taskId), status, taskId = taskId)
        }
    }

    private fun currentTaskMessage(taskId: String): String =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == taskId }
            ?.message ?: ""

    private fun currentTaskProgress(taskId: String): Float =
        VaultManager.activeTasks.value
            .firstOrNull { it.taskId == taskId }
            ?.progress ?: 0f

    private fun createNotification(
        content: String,
        progress: Int,
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.main_title))
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
                    // 渠道名/描述也走资源（中文系统上原来直接显示英文）。
                    // 描述先留空：现有 strings 里没有语义合适的 key，已列入报告待补 vault_channel_desc。
                    getString(R.string.main_title),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
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
