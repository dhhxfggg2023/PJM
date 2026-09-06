package com.dhhxfggg.pjm

import android.app.Application
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
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.ThumbnailSyncManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        /** 全局 IO 协程作用域 */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
