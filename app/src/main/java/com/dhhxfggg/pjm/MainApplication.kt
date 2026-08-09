package com.dhhxfggg.pjm

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.video.VideoFrameDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.request.allowHardware
import okio.Path.Companion.toPath
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用程序主入口，负责初始化全局组件和第三方 SDK。
 * 实现了 [SingletonImageLoader.Factory] 以提供高性能的 Coil 3 图片加载器。
 */
@HiltAndroidApp
class MainApplication : Application(), SingletonImageLoader.Factory {
    
    companion object {
        /** 全局 IO 协程作用域 */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        /** 是否开启 7z 兼容层支持 */
        const val isSevenZipEnabled: Boolean = true
    }

    /**
     * 创建 Coil 3 的全局 ImageLoader 单例。
     * 配置了内存缓存、磁盘缓存、视频帧解码支持，并禁用了交叉淡入以优化瞬间展示体验。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // 占用可用内存的 25%
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.filesDir.resolve("pjm_thumbnail_cache").absolutePath.toPath())
                    .maxSizeBytes(512 * 1024 * 1024) // 512MB 磁盘缓存
                    .build()
            }
            .components {
                // 添加视频缩略图支持
                add(VideoFrameDecoder.Factory())
            }
            .allowHardware(true)
            .crossfade(false) 
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // 初始化日志系统
        PjmLogger.init(this)
        PjmLogger.i("MainApplication", "PJM 应用引擎已启动，兼容层支持: $isSevenZipEnabled")
    }
}
