package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * PJM 容器打包 / 分卷导出 / 数据库备份。
 *
 * 从 VaultManager 中拆分出的职责块，仅依赖公开路径/命名工具与信号门面。
 */
object VaultPackager {

    private const val TAG = "VaultPackager"

    /**
     * 数据库备份到 filesDir/backups（WAL checkpoint 后拷贝，保留最近 7 份）。
     */
    suspend fun backupDatabase(context: Context) = withContext(VaultManager.PjmDispatchers.Database) {
        val dbFile = context.getDatabasePath("pjm_app_database")
        if (!dbFile.exists()) return@withContext
        // 核心修复：WAL 模式下先 checkpoint，确保备份文件包含全部已提交数据
        try {
            AppDatabase.openDb?.query("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (e: Exception) {
            PjmLogger.w(TAG, "WAL checkpoint failed during backup", e)
        }
        val backupDir = File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
        val backupFile = File(backupDir, "pjm_db_backup_${System.currentTimeMillis()}.db")
        try {
            dbFile.inputStream().use { it.copyTo(backupFile.outputStream()) }
            backupDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
        } catch (e: Exception) { PjmLogger.e(TAG, "Backup failed", e) }
    }

    /**
     * 全库导出为 PJM 分卷（每个分卷独立完整容器，可单独解密）。
     * @return 分卷数量
     */
    suspend fun exportVaultToPjmModule(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}): Result<Int> =
        withContext(VaultManager.PjmDispatchers.IO) {
            runCatching {
                val settings = SettingsManager.getSettingsFlow(context).first()
                val volumeSize = settings.exportSplitSize.toLong() * 1024 * 1024
                val vaultRoot = VaultPaths.vaultRoot(context)
                val allFiles = vaultRoot.walkTopDown().filter { it.isFile && !it.absolutePath.contains("/pjm/") }.toList()
                if (allFiles.isEmpty()) throw Exception("Vault empty")
                val totalBytes = allFiles.sumOf { it.length() }.coerceAtLeast(1L)
                val baseName = "Export_" + VaultNaming.readableTimestamp()

                // 贪心分组：按文件边界累加，尽量接近分卷大小但不超；单文件超限则单独一卷
                val groups = mutableListOf<MutableList<File>>()
                var current = mutableListOf<File>()
                var currentSize = 0L
                for (file in allFiles) {
                    val len = file.length()
                    if (current.isNotEmpty() && currentSize + len > volumeSize) {
                        groups.add(current); current = mutableListOf(); currentSize = 0L
                    }
                    current.add(file); currentSize += len
                }
                if (current.isNotEmpty()) groups.add(current)

                var processed = 0L
                groups.forEachIndexed { idx, group ->
                    // 每个分卷 = 独立完整的 PJM 容器（magic + 完整 ZIP，XOR 从 0 开始），可单独解密，互不依赖
                    val volumeFile = File(VaultPaths.getCategoryDir(context, VaultCategories.CAT_PJM), "$baseName.pjm.${idx + 1}")
                    val groupSize = group.sumOf { it.length() }.coerceAtLeast(1L)
                    CryptoUtils.encryptUris(
                        context = context,
                        inputUris = group.map { Uri.fromFile(it) },
                        outputPath = volumeFile.absolutePath,
                    ) { p ->
                        onProgress(((processed + p * groupSize) / totalBytes).coerceIn(0f, 1f))
                    }
                    // 立即入库，确保导出产物在文件柜立即可见、可分享
                    fileDao.upsert(FileEntity(
                        relativePath = VaultPaths.getRelativePath(context, volumeFile),
                        name = volumeFile.name,
                        size = volumeFile.length(),
                        category = VaultCategories.CAT_PJM,
                        lastModified = System.currentTimeMillis(),
                        isImage = false,
                        extension = "pjm",
                        contentHash = null
                    ))
                    processed += groupSize
                }
                VaultManager.triggerRefresh()
                groups.size
            }
        }

    /**
     * 将一组 URI 按分卷体积切分打包并入库（B 站合并产物导出等场景）。
     * @return 分卷数量
     */
    suspend fun packUrisWithSplitting(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit
    ): Result<Int> = withContext(VaultManager.PjmDispatchers.IO) {
        runCatching {
            val settings = SettingsManager.getSettingsFlow(context).first()
            val volumeSize = settings.exportSplitSize.toLong() * 1024 * 1024
            val infos = uris.map { Triple(it, FileUtils.getFileName(context, it), FileUtils.getFileSize(context, it)) }
            val totalSize = infos.sumOf { it.third }.coerceAtLeast(1L)

            // 贪心分组：按文件边界累加，尽量接近分卷大小但不超；单文件超限则单独一卷
            val groups = mutableListOf<MutableList<Triple<Uri, String, Long>>>()
            var current = mutableListOf<Triple<Uri, String, Long>>()
            var currentSize = 0L
            for (info in infos) {
                if (current.isNotEmpty() && currentSize + info.third > volumeSize) {
                    groups.add(current); current = mutableListOf(); currentSize = 0L
                }
                current.add(info); currentSize += info.third
            }
            if (current.isNotEmpty()) groups.add(current)

            var processed = 0L
            groups.forEachIndexed { idx, group ->
                // 每个分卷 = 独立完整的 PJM 容器（magic + 完整 ZIP，XOR 从 0 开始），可单独解密，互不依赖
                val volumeFile = File(VaultPaths.getCategoryDir(context, category), "$baseName.pjm.${idx + 1}")
                val groupSize = group.sumOf { it.third }.coerceAtLeast(1L)
                CryptoUtils.encryptUris(
                    context = context,
                    inputUris = group.map { it.first },
                    outputPath = volumeFile.absolutePath,
                ) { p ->
                    onProgress(((processed + p * groupSize) / totalSize).coerceIn(0f, 1f))
                }
                // 立即入库，确保加密包在文件柜立即可见、可分享
                fileDao.upsert(FileEntity(
                    relativePath = VaultPaths.getRelativePath(context, volumeFile),
                    name = volumeFile.name,
                    size = volumeFile.length(),
                    category = category,
                    lastModified = System.currentTimeMillis(),
                    isImage = false,
                    extension = "pjm",
                    contentHash = null
                ))
                processed += groupSize
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
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(VaultManager.PjmDispatchers.IO) {
        runCatching {
            if (uris.isEmpty()) throw Exception("Empty input")
            val volumeFile = File(VaultPaths.getCategoryDir(context, category), "$baseName.pjm.1")
            CryptoUtils.encryptUris(
                context = context,
                inputUris = uris,
                outputPath = volumeFile.absolutePath,
                onProgress = onProgress
            )
            // 立即入库，文件柜立即可见、可分享
            fileDao.upsert(FileEntity(
                relativePath = VaultPaths.getRelativePath(context, volumeFile),
                name = volumeFile.name,
                size = volumeFile.length(),
                category = category,
                lastModified = System.currentTimeMillis(),
                isImage = false,
                extension = "pjm",
                contentHash = null
            ))
            VaultManager.triggerRefresh()
            volumeFile
        }
    }
}
