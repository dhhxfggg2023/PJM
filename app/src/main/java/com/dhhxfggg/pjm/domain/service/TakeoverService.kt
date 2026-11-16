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
import android.os.PowerManager
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
 * 2. 定时器（3 分钟）→ 媒体库扫描 + 目录扫描（不写媒体库的应用也能被捡到）。
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

    /** 停服要回到主线程串行执行（与 onStartCommand 的 startForeground 互斥） */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastStartId = 0

    /** ContentObserver 会为一件事回调很多次（连拍/多选/相册刷新），用 CONFLATED 合并成一次扫描 */
    private val scanSignal = Channel<Unit>(Channel.CONFLATED)

    private val scanMutex = Mutex()
    private var lastNotifiedAppCount: Int = -1

    companion object {
        private const val TAG = "TakeoverService"

        /** 回调合并窗口：相机连拍/多选保存会在几百毫秒内连续触发多次 */
        private const val DEBOUNCE_MS = 1500L

        /** 定时兜底的基础间隔：目录深扫不便宜，3 分钟一次足够 */
        private const val TICK_MS = 180_000L

        /**
         * 空闲时的最长兜底间隔：连续几轮一个文件都没扫到就逐步拉长到 15 分钟。
         * 手机上「没人在用」的时间占绝大多数，一直 3 分钟醒一次纯属白耗电。
         */
        private const val TICK_IDLE_MAX_MS = 900_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
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
        // API 29 才引入 VOLUME_EXTERNAL 卷名常量：minSdk 24 上退回旧的 "external" 字面量
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
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
                    runScan(doSweep = true, tickIntervalMs = TICK_MS)
                }
                // 2) 媒体库变化信号：只跑媒体库通道（频繁，不能深扫目录）
                launch {
                    for (signal in scanSignal) {
                        delay(DEBOUNCE_MS.milliseconds)
                        scanMutex.withLock { runScan(doSweep = false) }
                    }
                }
                // 3) 定时兜底：目录扫描 + 媒体库补扫。
                //    周期自适应：连续几轮没扫到文件 → 3 → 6 → 12 → 15 分钟（上限），
                //    一旦有新文件立刻回到 3 分钟。
                //    熄屏时只查媒体库水位、不做目录深扫 —— 那个时间点没人手动存文件，
                //    真有下载/保存也会走上面的媒体库变化通道，而全盘遍历会白唤醒存储。
                launch {
                    var interval = TICK_MS
                    while (true) {
                        delay(interval.milliseconds)
                        val interactive = isScreenInteractive()
                        val handled =
                            scanMutex.withLock {
                                runScan(doSweep = interactive, tickIntervalMs = interval)
                            }
                        interval =
                            if (handled > 0) {
                                TICK_MS
                            } else {
                                (interval * 2).coerceAtMost(TICK_IDLE_MAX_MS)
                            }
                    }
                }
            }
    }

    /**
     * 跑一轮扫描。
     *
     * @param doSweep 是否同时做目录深扫（熄屏时降级为 false，只走媒体库水位）
     * @param tickIntervalMs 非空表示这一轮是「定时兜底心跳」，值是本轮声明的周期；
     *   心跳记账按它算空档阈值 —— 周期自适应之后固定阈值会把正常空闲误报成被冻结
     * @return 本轮真正处理掉的文件数（0 = 什么都没扫到，可以拉长间隔）
     */
    private suspend fun runScan(
        doSweep: Boolean,
        tickIntervalMs: Long? = null,
    ): Int {
        try {
            if (!TakeoverPrefs.isEnabled(this)) {
                PjmLogger.i(TAG, "接管总开关已关闭，停止服务")
                stopSelfFromWorker()
                return 0
            }
            val managed = managedAppDao.getAll()
            TakeoverPrefs.setManagedCount(this, managed.size)
            val now = System.currentTimeMillis()
            if (tickIntervalMs != null) {
                // 心跳间隔法：进程被冻结/杀死时定时器不走，恢复后第一件事就是发现这个空档。
                // 开关（后台耗电/自启动）改没改，看的就是它 —— 比任何系统标志都直接。
                val lastTick = TakeoverPrefs.lastTickAt(this)
                if (lastTick > 0L) {
                    val gap = now - lastTick
                    // 阈值取「上一次 tick 声明的周期」：空闲会拉长到 15 分钟，
                    // 用固定的 2×3 分钟判，正常空闲就变成了「后台被冻结」
                    val expected = TakeoverPrefs.lastTickGap(this).coerceAtLeast(TICK_MS)
                    // 重启设备也会造成大间隔，那不算「被系统停止」，用开机时长排除
                    if (gap > expected + expected / 2 && SystemClock.elapsedRealtime() >= gap) {
                        TakeoverPrefs.recordBackgroundGap(this, gap - expected)
                        PjmLogger.w(TAG, "后台中断: 距上次心跳 " + gap / 60_000L + " 分钟（被系统冻结或停止）")
                    }
                }
                TakeoverPrefs.markTick(this, now, tickIntervalMs)
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
                stopSelfFromWorker()
                return 0
            }
            updateServiceNotification(managed.size)
            return TakeoverScanEngine.scanOnce(this, managedAppDao, takeoverJobDao, fileDao, doSweep)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            PjmLogger.e(TAG, "接管扫描失败: " + e.javaClass.simpleName + ": " + e.message, e as? Exception)
        }
        return 0
    }

    /**
     * 从后台协程里停服：投递到主线程并带上 startId。
     *
     * 旧实现在 IO 协程里直接 `stopSelf()`：若此刻主线程正在执行新的 onStartCommand
     * （`startForeground` 还没跑完），服务会被提前停掉 —— 通知消失、观察者注销，
     * 而新的一次启动请求排队后已无人处理。`stopSelf(startId)` 只在没有更新的启动请求时才生效。
     */
    private fun stopSelfFromWorker() {
        val startId = lastStartId
        mainHandler.post { stopSelf(startId) }
    }

    /** 屏幕是否亮着：熄屏时跳过目录深扫，避免在没人用手机时白扫一遍存储 */
    private fun isScreenInteractive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive ?: true

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
