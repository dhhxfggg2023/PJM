package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 入库后「默认把指纹算好存下」，**不做任何比对/查重**。
 *
 * 用户明确要求：保存进 PJM 的文件不要自动查重（耗时且打扰），但指纹要提前算好 ——
 * 这样以后手动跑「清除重复内容」时，这一步已经是增量命中，不必把全库图片重新解码一遍。
 *
 * 三件事一次解码产出（dHash 指纹 + 32×32 灰度 + 64 宽验证签名），全部落盘缓存；
 * 已有完整缓存就跳过 —— 重复入库/重装后的增量几乎零成本。
 *
 * 并发限制 2 路：入库刚结束就解码大图，抢满 IO 线程会让列表滑动卡顿。
 */
object FingerprintWarmer {
    private const val TAG = "FingerprintWarmer"

    private val semaphore = Semaphore(2)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 异步预热，不阻塞入库流程；单张失败（含 OOM）只记日志，不影响已入库的文件 */
    fun warm(
        context: Context,
        entities: List<FileEntity>,
    ) {
        val images = entities.filter { it.isImage && FileUtils.isImageFile(it.name) }
        if (images.isEmpty()) return
        val appContext = context.applicationContext
        MainApplication.applicationScope.launch {
            images.forEach { entity ->
                if (!inFlight.add(entity.relativePath)) return@forEach
                try {
                    semaphore.withPermit {
                        val cached = ImageFingerprintCache.getFingerprint(appContext, entity)
                        val complete =
                            cached != null &&
                                ImageFingerprintCache.hasGray32(appContext, entity) &&
                                ImageFingerprintCache.hasVerifySignature(appContext, entity)
                        if (!complete) {
                            ImageFingerprintCache.computeBundle(appContext, entity)?.let { bundle ->
                                ImageFingerprintCache.saveBundle(appContext, entity, bundle)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // OOM 也要挡住：一张解不开的图不该带走整个后台作用域
                    PjmLogger.w(TAG, "指纹预热失败: ${entity.relativePath} (${e.javaClass.simpleName})")
                } finally {
                    inFlight.remove(entity.relativePath)
                }
            }
        }
    }
}
