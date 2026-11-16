package com.dhhxfggg.pjm

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import com.dhhxfggg.pjm.domain.service.TakeoverStarter
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.DiscoveryPlayerPool
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.FrameMonitor
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
import kotlinx.coroutines.delay
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
         * 后台补齐缩略图、命名迁移、后台删除等都在这个作用域里跑，
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
        // 单元测试（Robolectric）不跑冷启动维护任务：它们会对真实磁盘/偏好文件做读写，
        // 与同一 JVM 里并发的用例互相干扰（例如完整性抽查用真实时间写 last_run_at，
        // 会让「24 小时门控」这类用例变得不确定）。生产构建永远不满足该条件。
        val isUnitTest = Build.FINGERPRINT.contains("robolectric")
        // 调试版慢帧统计（release 自动禁用），用于定位具体页面的掉帧
        FrameMonitor.start(this)
        // 核心修复：FrameMonitor 的 Choreographer 回调此前从不 stop —— 进程活着就每帧回调，
        // 全项目没有一处 stop() 调用。现在由 Activity 生命周期驱动：全部 Activity 都 onStop 时
        // stop()，任一 onStart 时 start()（start 本身幂等，不会重复注册回调）。
        registerActivityLifecycleCallbacks(PjmActivityCallbacks())
        // 核心修复：发现页 ExoPlayer 池从不响应系统内存压力。onTrimMemory 时回收：
        // 空闲实例 release、在用实例只 stop（见 DiscoveryPlayerPool.trimMemory）。
        registerComponentCallbacks(PjmMemoryCallbacks())

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

        // 冷启动错峰（核心修复）：原来 6 个后台任务在 onCreate 里同时起跑，磁盘 IO 互相争抢，
        // 首屏那几秒的卡顿主要来自这里。现在按「轻→重」用 delay 拉开（都在后台协程里，
        // 不阻塞主线程），总跨度约 10 秒；最重的完整性抽查排在最后。
        //
        // 冷启动后清理浏览历史中已不存在文件的孤儿记录（避免历史表膨胀）
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            if (isUnitTest) return@launch
            delay(800)
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
            if (isUnitTest) return@launch
            delay(2000)
            runCatching { VaultManager.cleanupStaleTempFiles(this@MainApplication) }
                .onFailure { e -> PjmLogger.w("MainApplication", "容器临时文件清理跳过: ${e.message}") }
        }

        // 每日完整性抽查：几十 GB 长期放在闪存上，静默损坏是真实存在的。
        // 每 24 小时抽查一批（500 个）文件的哈希，轮流推进、后台静默，
        // **只读 + 只报告，绝不改动任何文件**（详见 IntegritySweeper）。
        // 只有发现问题时才提示，且提示只说明数量与去处，不弹窗打断。
        applicationScope.launch(VaultManager.PjmDispatchers.IO) {
            if (isUnitTest) return@launch
            delay(8000)
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

        // 一次性分类迁移：把「其他」里能当文本读的（txt/md/日志/代码）挪到新的「文本」分类。
        // 只改数据库标签，不动磁盘文件 —— FileEntity.relativePath 才是定位依据。
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            if (isUnitTest) return@launch
            delay(3200)
            runCatching {
                val prefs = getSharedPreferences("pjm_misc_prefs", MODE_PRIVATE)
                if (prefs.getBoolean("texts_category_migrated", false)) return@launch
                val entry = EntryPointAccessors.fromApplication(this@MainApplication, MainAppEntryPoint::class.java)
                val dao = entry.fileDao()
                var moved = 0
                dao.getFilesByCategoryOnce(VaultManager.CAT_OTHERS).forEach { entity ->
                    if (FileUtils.isTextFile(entity.name)) {
                        dao.updateCategory(entity.relativePath, VaultManager.CAT_TEXTS)
                        moved++
                    }
                }
                prefs.edit().putBoolean("texts_category_migrated", true).apply()
                if (moved > 0) PjmLogger.i("MainApplication", "文本分类迁移完成: " + moved + " 个文件")
            }.onFailure { e -> PjmLogger.w("MainApplication", "文本分类迁移跳过: " + e.message) }
        }

        // 一次性命名迁移：把旧命名规则的加密容器统一为最新规范
        // `前缀_yyyyMMdd_HHmmss.pjm.N`（如旧式 Export_<毫秒>.pjm.1、X.pjm 单卷缺数字）
        // 使用版本化标志（v2），保证在旧迁移已置位的情况下本次也会执行一次；
        // 迁移幂等、失败不写标志，下次启动自动重试，且不会阻塞启动。
        applicationScope.launch(VaultManager.PjmDispatchers.Database) {
            if (isUnitTest) return@launch
            delay(4800)
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

        // 一次性清理：应用内数据库备份功能已移除（用户确认不再需要备份），
        // 删掉历史遗留的 pjm_db_backup_*.db（每个都是整库副本，占空间且永不更新），
        // 顺手清掉只为该功能存在的 pjm_backup_prefs。目录空了连目录一起删。
        applicationScope.launch(VaultManager.PjmDispatchers.IO) {
            if (isUnitTest) return@launch
            delay(10000)
            runCatching {
                val backupDir = java.io.File(filesDir, "backups")
                val stale =
                    backupDir
                        .listFiles()
                        ?.filter { it.isFile && it.name.startsWith("pjm_db_backup_") }
                        .orEmpty()
                val reclaimed = stale.sumOf { it.length() }
                stale.forEach { it.delete() }
                if (backupDir.exists() && backupDir.listFiles()?.isEmpty() == true) backupDir.delete()
                java.io.File(filesDir.parentFile, "shared_prefs/pjm_backup_prefs.xml").delete()
                if (stale.isNotEmpty()) {
                    PjmLogger.i("MainApplication", "已清理历史数据库备份：${stale.size} 个文件，释放 ${reclaimed / 1024 / 1024} MB")
                }
            }.onFailure { e -> PjmLogger.w("MainApplication", "历史备份清理跳过: ${e.message}") }
        }

        // 「应用接管」：被接管列表非空时确保观察者服务在跑。
        // 冷启动兜底（服务被系统回收/用户强杀后重新拉起）；开机广播会先于这里触发一次。
        TakeoverStarter.startIfEnabled(this)
    }

    /**
     * Activity 可见性驱动的慢帧统计开关。
     *
     * 用计数而不是布尔：Activity 切换时 onStart(B) 可能先于 onStop(A)，
     * 只在「从 0 变 1」与「从 1 变 0」时切换，避免中途 stop 掉仍在使用的监控。
     * 回调都跑在主线程，普通 Int 即可（无需原子类）。
     */
    private class PjmActivityCallbacks : Application.ActivityLifecycleCallbacks {
        private var startedCount = 0

        override fun onActivityStarted(activity: Activity) {
            if (startedCount++ == 0) FrameMonitor.start(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedCount > 0 && --startedCount == 0) FrameMonitor.stop()
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * 系统内存压力回调：回收发现页 ExoPlayer。
     *
     * 只在应用不可见（UI_HIDDEN 及更严重）时回收：trimMemory() 会 stop 正在使用的实例，
     * 前台可见时调用会直接打断用户正在看的视频。
     */
    private class PjmMemoryCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) DiscoveryPlayerPool.trimMemory()
        }

        override fun onLowMemory() {
            DiscoveryPlayerPool.trimMemory()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }
}
