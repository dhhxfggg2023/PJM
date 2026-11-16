package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PJM 容器打包 / 分卷导出。
 *
 * 从 VaultManager 中拆分出的职责块，仅依赖公开路径/命名工具与信号门面。
 */
object VaultPackager {
    private const val TAG = "VaultPackager"

    /**
     * 单卷体积上限（MB）。
     *
     * 旧版是用户可配置项（设置页「分卷大小」），该入口已下线：分卷行为本身保留
     * （单个 .pjm 容器过大时仍按文件边界切成多卷），但容量固定为 1GB，不再可配置。
     */
    private const val VOLUME_SIZE_MB = 1024L

    /**
     * 将一组 URI 按分卷体积切分打包并入库（B 站合并产物导出等场景）。
     *
     * @param volumeSizeMb 单卷体积上限（MB），默认即生产固定值 [VOLUME_SIZE_MB]；
     *        仅为单元测试用小体积触发多卷而保留，UI/设置项不可配置。
     * @return 分卷数量
     */
    suspend fun packUrisWithSplitting(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
        volumeSizeMb: Long = VOLUME_SIZE_MB,
    ): Result<Int> =
        withContext(VaultManager.PjmDispatchers.IO) {
            runCatching {
                val volumeSize = volumeSizeMb * 1024 * 1024
                // 只需要 (uri, size)：显示名由容器内部按 uri 自己解析，这里再查一次纯属多余
                // （.second 从未被使用，却为每个 uri 多跑一次 DISPLAY_NAME 查询）。
                val infos = uris.map { it to FileUtils.getFileSize(context, it) }
                val totalSize = infos.sumOf { it.second }.coerceAtLeast(1L)

                // 贪心分组：按文件边界累加，尽量接近分卷大小但不超；单文件超限则单独一卷
                val groups = mutableListOf<MutableList<Pair<Uri, Long>>>()
                var current = mutableListOf<Pair<Uri, Long>>()
                var currentSize = 0L
                for (info in infos) {
                    if (current.isNotEmpty() && currentSize + info.second > volumeSize) {
                        groups.add(current)
                        current = mutableListOf()
                        currentSize = 0L
                    }
                    current.add(info)
                    currentSize += info.second
                }
                if (current.isNotEmpty()) groups.add(current)

                var processed = 0L
                // 已建（可能已入库）的分卷：中途失败必须回滚，不能留下「前几卷看着完整、
                // 实际缺内容」的半套加密包 —— 那比彻底失败更危险。
                val created = mutableListOf<Pair<File, String>>()
                try {
                    groups.forEachIndexed { idx, group ->
                        // 每个分卷 = 独立完整的 PJM 容器（magic + 完整 ZIP，XOR 从 0 开始），可单独解密，互不依赖
                        val volumeFile = File(VaultPaths.getCategoryDir(context, category), "$baseName.pjm.${idx + 1}")
                        val relativePath = VaultPaths.getRelativePath(context, volumeFile)
                        // 先登记再写：写入中途异常时也能把这个半截文件删掉
                        created.add(volumeFile to relativePath)
                        val groupSize = group.sumOf { it.second }.coerceAtLeast(1L)
                        // 核心修复：encryptUris 把 IO/空间不足/重名条目都收成 Result.failure 返回，
                        // 旧实现丢弃返回值继续入库 —— 加密失败时容器根本没生成，却插入一条 size=0
                        // 的索引行，外层 catch 也永远不会触发回滚，界面还显示「已生成 N 卷」。
                        // 这里必须 getOrThrow，让下面的 catch 真正回滚并把失败抛给调用方。
                        CryptoUtils
                            .encryptUris(
                                context = context,
                                inputUris = group.map { it.first },
                                outputPath = volumeFile.absolutePath,
                                isCancelled = { VaultManager.isTaskCancelled(VaultManager.TASK_ENCRYPT) },
                            ) { p ->
                                onProgress(((processed + p * groupSize) / totalSize).coerceIn(0f, 1f))
                            }.getOrThrow()
                        // 再兜一层：容器必须真的存在且非空，否则同样按失败回滚
                        if (!volumeFile.isFile || volumeFile.length() <= 0L) {
                            throw java.io.IOException("容器未生成或为空: " + volumeFile.name)
                        }
                        // 立即入库，确保加密包在文件柜立即可见、可分享
                        fileDao.upsertLocked(
                            FileEntity(
                                relativePath = relativePath,
                                name = volumeFile.name,
                                size = volumeFile.length(),
                                category = category,
                                lastModified = System.currentTimeMillis(),
                                isImage = false,
                                extension = "pjm",
                                contentHash = null,
                            ),
                        )
                        processed += groupSize
                    }
                } catch (t: Throwable) {
                    if (created.isNotEmpty()) {
                        created.forEach { (file, relativePath) ->
                            runCatching { VaultManager.shredFile(file) }
                            runCatching { fileDao.deleteByRelativePath(relativePath) }
                        }
                        PjmLogger.w(TAG, "分卷导出失败，已回滚 ${created.size} 个已生成分卷")
                    }
                    throw t
                }
                VaultManager.triggerRefresh()
                groups.size
            }
        }

    /**
     * 将一组 URI 打包成【单个】PJM 容器（不分卷），存入指定分类并入库。
     * 命名统一为 `X.pjm.1`（编号从 1 开始），与分卷导出保持一致。
     * @return 生成的容器文件
     */
    suspend fun packUrisSingle(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit = {},
    ): Result<File> =
        withContext(VaultManager.PjmDispatchers.IO) {
            runCatching {
                if (uris.isEmpty()) throw Exception("Empty input")
                val volumeFile = File(VaultPaths.getCategoryDir(context, category), "$baseName.pjm.1")
                // 同 packUrisWithSplitting：失败必须上抛，否则会在没有容器的情况下入库一条幽灵记录
                CryptoUtils
                    .encryptUris(
                        context = context,
                        inputUris = uris,
                        outputPath = volumeFile.absolutePath,
                        onProgress = onProgress,
                    ).getOrThrow()
                if (!volumeFile.isFile || volumeFile.length() <= 0L) {
                    throw java.io.IOException("容器未生成或为空: " + volumeFile.name)
                }
                // 立即入库，文件柜立即可见、可分享
                fileDao.upsertLocked(
                    FileEntity(
                        relativePath = VaultPaths.getRelativePath(context, volumeFile),
                        name = volumeFile.name,
                        size = volumeFile.length(),
                        category = category,
                        lastModified = System.currentTimeMillis(),
                        isImage = false,
                        extension = "pjm",
                        contentHash = null,
                    ),
                )
                VaultManager.triggerRefresh()
                volumeFile
            }
        }
}
