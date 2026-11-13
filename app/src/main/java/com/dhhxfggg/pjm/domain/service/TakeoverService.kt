package com.dhhxfggg.pjm.domain.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.dhhxfggg.pjm.MainActivity
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ManagedAppDao
import com.dhhxfggg.pjm.data.db.TakeoverJobDao
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * 「应用接管」的常驻观察者（前台服务）。扫描逻辑在 [TakeoverScanEngine]，
 * 这里只负责：进程别被回收、MediaStore 一有变化就触发一轮、定时兜底。
 *
 * 两条触发通道：
 * 1. ContentObserver（相册/下载一有变化就回调）→ 只跑媒体库扫描；
 * 2. 定时器（3 分钟）→ 媒体库扫描 + 目录扫描（Telegram/pixiv 这类不写媒体库的应用）。
 *
 * 另外 JobScheduler 还有一条独立兜底（[TakeoverJobService]）：ROM 冻结后台进程时，
 * 它会在自己进程里直接跑同一套扫描，不依赖本服务能否被拉起。
 */
@AndroidEntryPoint
class TakeoverService : Service() {
    @Inject
    lateinit var managedAppDao: ManagedAppDao

    @Inject
    lateinit var takeoverJobDao: TakeoverJobDao

    @Inject
    lateinit var fileDao: FileDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var scanJob: Job? = null

    /** ContentObserver 会为一件事回调很多次（连拍/多选/相册刷新），用 CONFLATED 合并成一次扫描 */
    private val scanSignal = Channel<Unit>(Channel.CONFLATED)

    private val scanMutex = Mutex()
    private var lastNotifiedAppCount: Int = -1

    companion object {
        private const val TAG = "TakeoverService"

        /** 回调合并窗口：相机连拍/多选保存会在几百毫秒内连续触发多次 */
        private const val DEBOUNCE_MS = 1500L

        /** 定时兜底间隔：目录深扫不便宜，3 分钟一次足够 */
        private const val TICK_MS = 180_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        TakeoverNotifications.ensureChannels(this)
        PjmLogger.i(
            TAG,
            "接管服务启动: 应用数=" +
                TakeoverPrefs.managedCount(this) +
                ", 后台白名单=" +
                TakeoverBackgroundPermission.isExempt(this),
        )
        startForegroundWithNotification()
        if (scanJob == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                PjmLogger.w(TAG, "系统低于 Android 10，应用接管不生效")
                stopSelf()
                return START_NOT_STICKY
            }
            startWatching()
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        // 通知文案用上次扫描缓存的数量：onStartCommand 在主线程，不能查 Room。
        // 缓存为 0 才说明列表真的空，已接管时绝不能显示「等待选择应用」。
        val cachedCount = TakeoverPrefs.managedCount(this)
        lastNotifiedAppCount = cachedCount
        val notification = buildServiceNotification(appCount = cachedCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TakeoverNotifications.NOTIFICATION_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TakeoverNotifications.NOTIFICATION_ID_SERVICE, notification)
        }
    }

    private fun startWatching() {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val watcher =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scanSignal.trySend(Unit)
                }

                override fun onChange(
                    selfChange: Boolean,
                    uri: Uri?,
                ) {
                    scanSignal.trySend(Unit)
                }
            }
        observer = watcher
        // 个别 ROM 上注册相册观察者会抛 SecurityException，不能因为它连定时兜底也停掉
        runCatching { contentResolver.registerContentObserver(collection, true, watcher) }
            .onFailure { e -> PjmLogger.w(TAG, "注册相册观察者失败: " + e.message) }

        scanJob =
            scope.launch {
                // 1) 启动即补扫一轮（含目录），把服务不在时保存的文件找回来
                scanMutex.withLock {
                    runScan(doSweep = true)
                }
                // 2) 媒体库变化信号：只跑媒体库通道（频繁，不能深扫目录）
                launch {
                    for (signal in scanSignal) {
                        delay(DEBOUNCE_MS.milliseconds)
                        scanMutex.withLock { runScan(doSweep = false) }
                    }
                }
                // 3) 定时兜底：目录扫描 + 媒体库补扫
                launch {
                    while (true) {
                        delay(TICK_MS.milliseconds)
                        scanMutex.withLock { runScan(doSweep = true) }
                    }
                }
            }
    }

    private suspend fun runScan(doSweep: Boolean) {
        try {
            if (!TakeoverPrefs.isEnabled(this)) {
                PjmLogger.i(TAG, "接管总开关已关闭，停止服务")
                stopSelf()
                return
            }
            val managed = managedAppDao.getAll()
            TakeoverPrefs.setManagedCount(this, managed.size)
            val now = System.currentTimeMillis()
            if (doSweep) {
                // 心跳间隔法：进程被冻结/杀死时定时器不走，恢复后第一件事就是发现这个空档。
                // 开关（后台耗电/自启动）改没改，看的就是它 —— 比任何系统标志都直接。
                val lastTick = TakeoverPrefs.lastTickAt(this)
                if (lastTick > 0L) {
                    val gap = now - lastTick
                    // 重启设备也会造成大间隔，那不算「被系统停止」，用开机时长排除
                    if (gap > TICK_MS * 2 && SystemClock.elapsedRealtime() >= gap) {
                        TakeoverPrefs.recordBackgroundGap(this, gap - TICK_MS)
                        PjmLogger.w(TAG, "后台中断: 距上次心跳 " + gap / 60_000L + " 分钟（被系统冻结或停止）")
                    }
                }
                TakeoverPrefs.markTick(this, now)
            }
            TakeoverPrefs.markScan(this)
            // 深扫一轮本来就少，记 I；相册一变就打一次的那种记 D，别把日志刷爆
            if (doSweep) {
                PjmLogger.i(TAG, "触发扫描: doSweep=true, 应用数=" + managed.size)
            } else {
                PjmLogger.d(TAG, "触发扫描: doSweep=false, 应用数=" + managed.size)
            }
            if (managed.isEmpty()) {
                PjmLogger.i(TAG, "没有需要接管的应用，停止服务")
                stopSelf()
                return
            }
            updateServiceNotification(managed.size)
            TakeoverScanEngine.scanOnce(this, managedAppDao, takeoverJobDao, fileDao, doSweep)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PjmLogger.e(TAG, "接管扫描失败: " + e.javaClass.simpleName + ": " + e.message, e as? Exception)
        }
    }

    private fun buildServiceNotification(appCount: Int): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text =
            if (appCount > 0) {
                getString(R.string.takeover_service_text, appCount)
            } else {
                getString(R.string.takeover_service_text_idle)
            }
        return NotificationCompat
            .Builder(this, TakeoverNotifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.takeover_service_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateServiceNotification(appCount: Int) {
        if (appCount == lastNotifiedAppCount) return
        lastNotifiedAppCount = appCount
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(TakeoverNotifications.NOTIFICATION_ID_SERVICE, buildServiceNotification(appCount)) }
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        PjmLogger.w(TAG, "接管服务超时（type=" + fgsType + "），主动停止")
        stopSelf()
    }

    override fun onDestroy() {
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        scanSignal.close()
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
