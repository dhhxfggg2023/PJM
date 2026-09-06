package com.dhhxfggg.pjm.data.repository

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.IngestionEngine
import com.dhhxfggg.pjm.domain.util.IngestionSummary
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Implementation of the [FileRepository] providing a bridge between the Vault data sources
 * and the domain layer.
 *
 * ### Data Synchronization Flow:
 * 1. **Initialization:** On app startup, [initialize] triggers a full file system scan via [VaultManager].
 * 2. **File Ingestion:** When files are added ([storeFiles] or [packAndEncrypt]), the [IngestionEngine]
 *    handles the physical file movement and encryption, then updates the [FileDao].
 * 3. **Reactive Updates:** All UI components observe data through [Flow] streams (e.g., [allFiles]).
 *    The database acts as the single source of truth (SSOT).
 * 4. **Buffering:** Flows are buffered to handle rapid database changes during large batch operations.
 *
 * @author PJM Industrial Standards 2026
 */
@Singleton
class FileRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileDao: FileDao,
        private val settingsManager: SettingsManager,
    ) : FileRepository {
        // 上次随机选中的每个分类文件 id（用于封面刷新时避免连续两次相同）
        private val lastRandomFileId = ConcurrentHashMap<String, Long>()

        override val allFiles: Flow<List<FileEntity>> =
            fileDao
                .getAllFiles()
                .buffer(64) // Optimization for large file lists

        override val categoryCounts: Flow<Map<String, Int>> =
            fileDao
                .getCategoryCountsFlow()
                .buffer(8)

        override val categorySizes: Flow<Map<String, Long>> =
            fileDao
                .getCategorySizesFlow()
                .map { it ?: emptyMap() }
                .buffer(8)

        override val totalSize: Flow<Long> =
            fileDao
                .getTotalSizeFlow()
                .map { it ?: 0L }
                .buffer(8)

        override fun getFilesByCategory(category: String): Flow<List<FileEntity>> = fileDao.getFilesByCategory(category).buffer(32)

        override suspend fun deleteFile(entity: FileEntity) {
            VaultManager.deleteFile(context, entity.relativePath, fileDao)
        }

        override suspend fun deleteFiles(entities: List<FileEntity>) {
            val paths = entities.map { it.relativePath }
            VaultManager.deleteFiles(context, paths, fileDao)
        }

        override suspend fun initialize(onProgress: (Float) -> Unit) {
            VaultManager.fullSyncDatabase(context, fileDao, onProgress)
        }

        override suspend fun syncDatabase() {
            VaultManager.fullSyncDatabase(context, fileDao)
        }

        override suspend fun exportVault(onProgress: (Float) -> Unit): Result<Int> = VaultManager.exportVaultToPjmModule(context, fileDao, onProgress)

        override suspend fun getRandomFileByCategory(category: String): FileEntity? {
            // 核心修复（所有分类封面都要能随机变）：
            // 旧实现“随机 id 下界 + id>= 定位”在 id 存在大空洞的分类（如视频库经大量删除/穿插）
            // 会频繁落空回退到首条 → 封面看起来不变。
            // 现改为按【记录数】均匀随机：count → 随机 offset → 按 id 取第 offset 条。
            // 并在记录数 > 1 时避开上次选中的同一条，保证点一下封面确实变化。
            val count = fileDao.getCountByCategory(category)
            if (count <= 0) return null
            var offset = Random.nextInt(count)
            val lastId = lastRandomFileId[category]
            if (lastId != null && count > 1) {
                // 先取随机 offset 对应记录；若恰好是上次那条则顺移一条
                val candidate = fileDao.getFileByOffset(category, offset)
                if (candidate != null && candidate.id == lastId) {
                    offset = (offset + 1) % count
                }
            }
            val picked = fileDao.getFileByOffset(category, offset) ?: return null
            lastRandomFileId[category] = picked.id
            return picked
        }

        override suspend fun getLatestFileByCategory(category: String): FileEntity? = fileDao.getLatestFileByCategory(category)

        override suspend fun storeFiles(
            uris: List<Uri>,
            password: String?,
            onStatus: (String) -> Unit,
            onProgress: (Float) -> Unit,
            onUnsupported: (Uri, String) -> Unit,
        ): IngestionSummary =
            IngestionEngine.store(
                context = context,
                uris = uris,
                password = password,
                fileDao = fileDao,
                onStatus = onStatus,
                onProgress = onProgress,
                onUnsupported = onUnsupported,
            )

        override suspend fun packAndEncrypt(
            uris: List<Uri>,
            onProgress: (Float) -> Unit,
        ): Result<Int> {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = "Pack_$timeStamp"
            return VaultManager.packUrisWithSplitting(
                context = context,
                uris = uris,
                category = "pjm",
                baseName = baseName,
                fileDao = fileDao,
                onProgress = onProgress,
            )
        }

        override suspend fun getNextVaultPath(
            category: String,
            originalName: String,
        ): File = VaultManager.getNextVaultPath(context, category, originalName)

        override suspend fun extractPjmToVault(entity: FileEntity) {
            VaultManager.extractPjmToVault(context, entity, fileDao)
        }

        override suspend fun getFileByPath(relativePath: String): FileEntity? = fileDao.findByRelativePath(relativePath)

        override suspend fun performIntegrityCheck(onProgress: (Float) -> Unit): Map<String, List<FileEntity>> = VaultManager.checkIntegrity(context, fileDao, onProgress)
    }
