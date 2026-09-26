package com.dhhxfggg.pjm

import android.app.Application
import android.widget.Toast
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ViewHistoryDao
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.IntegritySweeper
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.ThumbnailSyncManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * 应用程序主入口，负责初始化全局组件和第三方 SDK。
 * 实现了 [SingletonImageLoader.Factory] 以提供高性能的 Coil 3 图片加载器。
 */
@HiltAndroidApp
class MainApplication :
    Application(),
    SingletonImageLoader.Factory {
    companion object {
        /**
         * 全局 IO 协程作用域。
         *
         * 核心修复：补上 [CoroutineExceptionHandler]。
         * `SupervisorJob` 只保证「一个子任务失败不影响兄弟任务」，**不会**捕获异常 ——
         * 未捕获的异常会沿作用域冒泡到线程的默认处理器，直接让整个应用崩溃。
         * 后台补齐缩略图、数据库备份、命名迁移、后台删除等都在这个作用域里跑，
         * 任何一处漏掉的异常都会变成用户看到的闪退。
         */
        val applicationScope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO +
                    CoroutineExceptionHandler { _, throwable ->
                        // 记录后吞掉：后台任务失败不应该带走整个应用。
                        // PjmLogger 未初始化时内部会安全跳过（业务日志文件为 null）。
                        PjmLogger.e("AppScope", "后台任务未捕获异常（已拦截）: ${throwable.javaClass.name}", throwable)
                    },
            )

        /** 是否开启 7z 兼容层支持 */
        const val IS_SEVEN_ZIP_ENABLED: Boolean = true
    }

    /**
     * 非组件（Application）获取 Hilt 单例的入口。
     * 用于在冷启动阶段拉起缩略图后台同步。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MainAppEntryPoint {
        fun thumbnailSyncManager(): ThumbnailSyncManager

        fun fileDao(): FileDao

        fun viewHistoryDao(): ViewHistoryDao

        fun settingsManager(): SettingsManager
    }

    /**
     * 创建 Coil 3 的全局 ImageLoader 单例。
     * 配置了内存缓存、磁盘缓存、视频帧解码支持，并禁用了交叉淡入以优化瞬间展示体验。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, 0.25) // 占用可用内存的 25%
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(
                        context.filesDir
                            .resolve("pjm_thumbnail_cache")
                            .absolutePath
                            .toPath(),
                    ).maxSizeBytes(512 * 1024 * 1024) // 512MB 磁盘缓存
                    .build()
            }.components {
                // 添加视频缩略图支持
                add(VideoFrameDecoder.Factory())
            }.allowHardware(enable = true)
            .crossfade(enable = false)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 核心修复：显式初始化日志引擎，确保文件物理落盘
        PjmLogger.init(this)

        // 安装全局崩溃捕获：未捕获异常写入 error 日志，便于事后定位
        PjmLogger.installCrashHandler()

        // 初始化 Shizuku 桥接（检测服务/授权状态，用于突破 Android/data 访问限制）
        ShizukuBridge.init(this)

        // 核心修复：冷启动后自动补齐缺失缩略图（半永久缓存，空闲时后台生成，不抢进度条）
        try {
            EntryPointAccessors
                .fromApplication(this, MainAppEntryPoint::class.java)
                .thumbnailSyncManager()
                .scheduleSync(initialDelayMs = 3000)
        } catch (e: Exception) {
            PjmLogger.w("MainApplication", "ThumbnailSyncManager 初始化失败: ${e.message}")
        }

        PjmLogger.i("MainApplication", "PJM 应用引擎已启动，兼容层支持: $IS_SEVEN_ZIP_ENABLED")

        // 冷启动后清理浏览历史中已不存在文件的孤儿记录（避免历史表膨胀）
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            runCatching {
                EntryPointAccessors
                    .fromApplication(this@MainApplication, MainAppEntryPoint::class.java)
                    .viewHistoryDao()
                    .purgeOrphans()
            }.onFailure { e -> PjmLogger.w("MainApplication", "浏览历史清理跳过: ${e.message}") }
        }

        // 冷启动清理：进程被强杀时残留的容器临时文件（`*.tmp_*`）。
        // 放在命名迁移之前 —— 否则迁移扫描会先看到这些半成品。
        applicationScope.launch(VaultManager.PjmDispatchers.IO) {
            runCatching { VaultManager.cleanupStaleTempFiles(this@MainApplication) }
                .onFailure { e -> PjmLogger.w("MainApplication", "容器临时文件清理跳过: ${e.message}") }
        }

        // 每日完整性抽查：几十 GB 长期放在闪存上，静默损坏是真实存在的。
        // 每 24 小时抽查一批（500 个）文件的哈希，轮流推进、后台静默，
        // **只读 + 只报告，绝不改动任何文件**（详见 IntegritySweeper）。
        // 只有发现问题时才提示，且提示只说明数量与去处，不弹窗打断。
        applicationScope.launch(VaultManager.PjmDispatchers.IO) {
            runCatching {
                val entry = EntryPointAccessors.fromApplication(this@MainApplication, MainAppEntryPoint::class.java)
                val report = IntegritySweeper.sweepIfDue(this@MainApplication, entry.fileDao())
                if (report != null && (report.missing > 0 || report.corrupted > 0)) {
                    val n = report.missing + report.corrupted
                    withContext(Dispatchers.Main) {
                        val text = getString(R.string.toast_integrity_sweep_found, n)
                        Toast.makeText(this@MainApplication, text, Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { e -> PjmLogger.w("MainApplication", "完整性抽查跳过: ${e.message}") }
        }

        // 一次性命名迁移：把旧命名规则的加密容器统一为最新规范
        // `前缀_yyyyMMdd_HHmmss.pjm.N`（如旧式 Export_<毫秒>.pjm.1、X.pjm 单卷缺数字）
        // 使用版本化标志（v2），保证在旧迁移已置位的情况下本次也会执行一次；
        // 迁移幂等、失败不写标志，下次启动自动重试，且不会阻塞启动。
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            runCatching {
                val entry = EntryPointAccessors.fromApplication(this@MainApplication, MainAppEntryPoint::class.java)
                val settingsManager = entry.settingsManager()
                if (!settingsManager.isPjmNamingMigrationDone()) {
                    val migratedCount = VaultManager.migrateLegacyPjmNaming(this@MainApplication, entry.fileDao())
                    if (migratedCount > 0) {
                        PjmLogger.i("MainApplication", "PJM 命名迁移完成：$migratedCount 个旧命名文件已统一")
                    }
                    settingsManager.setPjmNamingMigrationDone(true)
                }
            }.onFailure { e -> PjmLogger.w("MainApplication", "PJM 命名迁移跳过: ${e.message}") }
        }

        // 每日自动备份数据库
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            val prefs = getSharedPreferences("pjm_backup_prefs", MODE_PRIVATE)
            val lastBackup = prefs.getLong("last_backup_time", 0L)
            val currentTime = System.currentTimeMillis()
            if ((currentTime - lastBackup) > 24 * 60 * 60 * 1000L) {
                VaultManager.backupDatabase(this@MainApplication)
                prefs.edit { putLong("last_backup_time", currentTime) }
            }
        }
    }
}
