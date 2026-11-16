package com.dhhxfggg.pjm.data.repository

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.IngestionEngine
import com.dhhxfggg.pjm.domain.util.IngestionSummary
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultNaming
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
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

        // 列表流用 conflate 而不是 buffer：一次批量删除会触发 N 次 InvalidationTracker 通知，
        // 每份通知都要完整物化一遍列表。buffer 只是移除背压，中间态一个都不会丢（最多积压
        // N 份全量列表）；conflate 只保留最新一份，UI 少走 N-1 次 diff/重组。
        override val allFiles: Flow<List<FileEntity>> =
            fileDao
                .getAllFiles()
                .conflate()
                .distinctUntilChanged()

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

        // 同上：分类列表是最大的那种快照，批量变更时只保留最新一份
        override fun getFilesByCategory(category: String): Flow<List<FileEntity>> = fileDao.getFilesByCategory(category).conflate().distinctUntilChanged()

        override fun getFilesByCategoryAndSizeRange(
            category: String,
            minSize: Long,
            maxSize: Long,
        ): Flow<List<FileEntity>> = fileDao.getFilesByCategoryAndSizeRange(category, minSize, maxSize).conflate().distinctUntilChanged()

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

        override suspend fun totalFileCount(): Long = fileDao.getTotalFileCount()

        override suspend fun updateDisplayName(
            relativePath: String,
            name: String,
        ) {
            if (name.isBlank()) return
            fileDao.updateDisplayName(relativePath, name.trim())
        }

        override suspend fun getRandomFileByCategory(category: String): FileEntity? {
            // 核心修复（所有分类封面都要能随机变）：
            // 旧实现“随机 id 下界 + id>= 定位”在 id 存在大空洞的分类（如视频库经大量删除/穿插）
            // 会频繁落空回退到首条 → 封面看起来不变；现按【记录数】均匀随机取 offset。
            //
            // 大库性能补齐：offset 版是 O(n) 扫描。id 区间没有被删出大空洞时（span <= count*4，
            // 平均空洞 <= 4），改用键集游标随机起点，O(log n) 且分布仍然均匀；
            // 空洞过大时保留原 offset 方案，保证封面随机性不退化。
            val count = fileDao.getCountByCategory(category)
            if (count <= 0) return null
            val minId = fileDao.getMinIdByCategory(category)
            val maxId = fileDao.getMaxIdByCategory(category)
            if (minId != null && maxId != null && maxId > minId && maxId - minId <= count * 4L) {
                val picked = pickByCursor(category, count, minId, maxId) ?: return null
                lastRandomFileId[category] = picked.id
                return picked
            }
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

        /**
         * 键集游标抽一张：分类内随机主键 → 取 id >= 起点 的第一条；
         * 正好是上次那条时从它的下一条再取（到尾部就绕回分类最小 id）。
         */
        private suspend fun pickByCursor(
            category: String,
            count: Int,
            minId: Long,
            maxId: Long,
        ): FileEntity? {
            val start = minId + Random.nextLong(maxId - minId + 1)
            val first =
                fileDao.getFirstFileAtOrAfterId(category, start)
                    ?: fileDao.getFirstFileAtOrAfterId(category, minId)
                    ?: return null
            if (count <= 1) return first
            val lastId = lastRandomFileId[category]
            if (lastId == null || first.id != lastId) return first
            return fileDao.getFirstFileAtOrAfterId(category, first.id + 1)
                ?: fileDao.getFirstFileAtOrAfterId(category, minId)
        }

        /**
         * 音频库内改名：把「显示名 = 磁盘 UUID」的条目换成标签标题。
         * 磁盘上的文件从不重命名，只改数据库里的显示名。
         */
        override suspend fun backfillAudioNames() {
            val audios = runCatching { fileDao.getFilesByCategoryOnce(VaultManager.CAT_AUDIOS) }.getOrDefault(emptyList())
            if (audios.isEmpty()) return
            var changed = 0
            audios.forEach { entity ->
                // 已经有可读名（这次或以前改过）就跳过；
                // 哈希/UUID/纯数字这类机器名一律当「没名字」处理
                if (!FileUtils.isMachineName(entity)) return@forEach
                val file = runCatching { VaultManager.getFileFromEntity(context, entity) }.getOrNull() ?: return@forEach
                if (!file.exists() || file.length() <= 0L) return@forEach
                val title =
                    runCatching {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever
                                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.getOrNull()
                if (title == null) return@forEach
                val ext = entity.extension.lowercase().trim('.')
                val newName = if (ext.isEmpty()) title else title + "." + ext
                if (newName.length > 120) return@forEach
                runCatching { fileDao.updateDisplayName(entity.relativePath, newName) }.onSuccess { changed++ }
            }
        }

        override suspend fun getCoverFile(
            category: String,
            daySeed: Int?,
        ): FileEntity? {
            if (daySeed == null) return getRandomFileByCategory(category)
            val count = fileDao.getCountByCategory(category)
            if (count <= 0) return null
            // 日期种子 + 分类名 → 稳定 offset：同一天永远同一张，跨天自动换
            val offset = Math.floorMod(daySeed * 31 + category.hashCode(), count)
            // 大库优化：把 offset 按 id 区间比例换算成键集起点，避免 OFFSET 逐行扫描；
            // 同一天仍稳定映射到同一条；id 空洞过大时回退原方案。
            val minId = fileDao.getMinIdByCategory(category)
            val maxId = fileDao.getMaxIdByCategory(category)
            if (minId != null && maxId != null && maxId > minId && maxId - minId <= count * 8L) {
                val span = maxId - minId + 1
                val start = minId + (offset.toLong() * span / count).coerceIn(0L, span - 1)
                return fileDao.getFirstFileAtOrAfterId(category, start)
                    ?: fileDao.getFirstFileAtOrAfterId(category, minId)
            }
            return fileDao.getFileByOffset(category, offset)
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
            // 毫秒精度：同一秒内两次打包不会算出同名容器互相覆盖
            val baseName = "Pack_" + VaultNaming.readableTimestamp()
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
