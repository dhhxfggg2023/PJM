package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.data.db.FileDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缩略图后台同步管理器 —— 半永久缩略图自动补齐。
 *
 * 需求：
 * 1. 打开 App 后自动生成缺失的缩略图（图片+视频），一次生成落盘为半永久缓存，
 *    命中缓存则不重复生成（不每次全量重扫/重解码）。
 * 2. 仅在没有其他任务（[VaultManager.isOperationActive] == false）时运行，
 *    让位于用户操作（导入/删除/扫描等），被抢占则等待下一轮。
 * 3. 每次对齐：库内缺缩略图的 → 生成；磁盘上孤儿缩略图（源已删/不在库中）→ 清理。
 * 4. 库变化（导入/删除/同步触发 refreshSignal）后自动重新调度（带防抖）。
 */
@Singleton
class ThumbnailSyncManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileDao: FileDao,
    ) {
        private var syncJob: Job? = null

        /** 上次清扫孤儿指纹的时间（全量图片集合构建不便宜，限频） */
        private var lastFingerprintSweepAt = 0L

        private companion object {
            const val FINGERPRINT_SWEEP_INTERVAL_MS = 30L * 60 * 1000
        }

        /** 解码并发限制（避免与浏览列表解码抢 IO） */
        private val decodeSemaphore = Semaphore(3)

        /** 每批最多生成数量（分批让位，避免长时间独占 IO） */
        private val BATCH_SIZE = 40

        init {
            // 库变化后自动重新调度（防抖：refreshSignal 高频触发时合并）
            MainApplication.applicationScope.launch {
                VaultManager.refreshSignal.collect {
                    scheduleSync(initialDelayMs = 2500)
                }
            }
        }

        /**
         * 调度一轮缩略图对齐（幂等：已有任务进行中会忽略本次调用）。
         * @param initialDelayMs 启动延迟（App 冷启动时让位首屏加载）
         */
        fun scheduleSync(initialDelayMs: Long = 2000) {
            if (syncJob?.isActive == true) return
            syncJob =
                MainApplication.applicationScope.launch {
                    delay(initialDelayMs)
                    // 有其他任务进行中 → 等待让位，不抢占进度条
                    while (isActive && VaultManager.isOperationActive) delay(10_000)
                    while (isActive) {
                        if (VaultManager.isOperationActive) {
                            delay(10_000)
                            continue
                        }
                        val more = syncOnce()
                        if (!more) break
                        delay(1500)
                    }
                }
        }

        /**
         * 执行一轮对齐：清理孤儿缩略图 + 生成缺失缩略图。
         * @return true 表示还有剩余未生成（下一轮继续）；false 表示已对齐
         */
        private suspend fun syncOnce(): Boolean {
            if (VaultManager.isOperationActive) return false

            // 缩略图只对视频有意义：图片由 Coil 直接解码并走它自己的磁盘缓存，
            // ThumbnailCache 里的图片缩略图从来没有读取方。
            // 旧实现每轮都把 4 万张图逐条 stat、还生成没人读的图片缩略图 —— 纯浪费 IO。
            val videos =
                try {
                    fileDao.getFilesByCategoryOnce(VaultManager.CAT_VIDEOS) +
                        fileDao.getFilesByCategoryOnce(VaultManager.CAT_BILI_VIDEOS)
                } catch (_: Exception) {
                    return false
                }

            // 1) 清理孤儿缩略图（只保留视频对应的）
            try {
                val validNames = videos.map { ThumbnailCache.thumbName(it) }.toHashSet()
                ThumbnailCache.thumbDir(context).listFiles()?.forEach { f ->
                    if (f.name !in validNames) {
                        try {
                            f.delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 1b) 清理孤儿感知指纹：需要遍历全部图片构建路径集合，半小时最多一次
            val now = System.currentTimeMillis()
            if (now - lastFingerprintSweepAt > FINGERPRINT_SWEEP_INTERVAL_MS) {
                lastFingerprintSweepAt = now
                try {
                    val images = fileDao.getFilesByCategoryOnce(VaultManager.CAT_IMAGES)
                    if (images.isNotEmpty()) {
                        val validNames = HashSet<String>(images.size * 2)
                        images.forEach { validNames.addAll(ImageFingerprintCache.fingerprintFileNames(it)) }
                        ImageFingerprintCache.fingerprintDir(context).listFiles()?.forEach { f ->
                            if (f.name !in validNames) {
                                try {
                                    f.delete()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2) 只找缺缩略图的视频（几十到一千，不再是四万）
            val missing = videos.filter { !ThumbnailCache.hasThumbnail(context, it) }
            if (missing.isEmpty()) return false

            // 3) 分批生成；期间若用户操作抢占则让位，下一轮继续
            val batch = missing.take(BATCH_SIZE)
            batch.forEach { entity ->
                if (VaultManager.isOperationActive) return true
                decodeSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        try {
                            ThumbnailCache.generateVideoThumbnail(context, entity)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            return missing.size > BATCH_SIZE
        }
    }
