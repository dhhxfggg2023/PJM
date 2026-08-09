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
    
    // Throttling for system notification updates
    private var lastNotificationTime = 0L

    companion object {
        private const val CHANNEL_ID = "vault_operation_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STORE = "com.dhhxfggg.pjm.ACTION_STORE"
        const val ACTION_ENCRYPT = "com.dhhxfggg.pjm.ACTION_ENCRYPT"

        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_PASSWORD = "extra_password"

        /**
         * Starts the vault storage process.
         */
        fun startStore(context: Context, uris: List<Uri>, password: String? = null) {
            val intent = Intent(context, VaultService::class.java).apply {
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
        fun startEncrypt(context: Context, uris: List<Uri>) {
            val intent = Intent(context, VaultService::class.java).apply {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java) ?: emptyList()
        val password = intent.getStringExtra(EXTRA_PASSWORD)

        // Android 14+ requirement: Specify foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification("Preparing operation...", 0),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Preparing operation...", 0))
        }

        currentJob?.cancel()
        currentJob = serviceScope.launch {
            try {
                when (action) {
                    ACTION_STORE -> {
                        repository.storeFiles(
                            uris = uris,
                            password = password,
                            onStatus = { updateNotification(it) },
                            onProgress = { updateNotification(null, (it * 100).toInt()) },
                            onUnsupported = { _, _ -> }
                        )
                        VaultManager.notifyResult(OperationResult.Success(ACTION_STORE, uris))
                    }
                    ACTION_ENCRYPT -> {
                        repository.packAndEncrypt(uris) { progress ->
                            updateNotification("Packing and encrypting...", (progress * 100).toInt())
                        }.getOrThrow()
                        VaultManager.notifyResult(OperationResult.Success(ACTION_ENCRYPT, uris))
                    }
                }
                delay(500.milliseconds) // Brief delay to ensure UI consistency
            } catch (e: Exception) {
                PjmLogger.e("VaultService", "Task failed", e)
                VaultManager.updateProgress(0f, "Operation failed: ${e.message}", isError = true)
                VaultManager.notifyResult(OperationResult.Error(action, e.message ?: "Unknown error"))
                delay(2.seconds)
            } finally {
                VaultManager.clearProgress()
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
    override fun onTimeout(startId: Int, fgsType: Int) {
        PjmLogger.w("VaultService", "Foreground service timed out for type $fgsType")
        currentJob?.cancel()
        VaultManager.updateProgress(0f, "Operation timed out", isError = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(status: String?, progress: Int = -1) {
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
            VaultManager.updateProgress(progress / 100f, status ?: VaultManager.operationState.value.message)
        } else if (status != null) {
            VaultManager.updateProgress(VaultManager.operationState.value.progress, status)
        }
    }

    private fun createNotification(content: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Vault Operations",
                NotificationManager.IMPORTANCE_LOW
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
