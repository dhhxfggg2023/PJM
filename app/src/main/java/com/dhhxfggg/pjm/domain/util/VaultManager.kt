package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.system.Os
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.math.abs

sealed class OperationResult {
    data class Success(
        val action: String,
        val uris: List<Uri>,
        val imported: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val volumes: Int = 0
    ) : OperationResult()
    data class Error(val action: String, val message: String) : OperationResult()
    data class PasswordRequired(val fileName: String, val uris: List<Uri>) : OperationResult()
}

/**
 * 导入操作的统计结果（用于操作完成后的汇总反馈）。
 */
data class IngestionSummary(
    val imported: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 真正以普通文件入库、可询问“是否删除原件”的源 uri。
     *  pjm 加密容器只解密不落库，永远不进入该列表（不参与删除询问）。 */
    val deletableUris: List<Uri> = emptyList()
)

/**
 * PJM Industrial Asset Manager.
 * 门面：对外保留统一入口；纯逻辑已下沉至 VaultPaths / VaultNaming 等对象。
 */
object VaultManager {
    private const val TAG = "VaultManager"
    private const val VAULT_ROOT = VaultPaths.VAULT_ROOT
    private val mutex = Mutex()
    
    object PjmDispatchers {
        val IO = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        // 核心优化：Crypto 线程池设上限（避免 i9 32 线程全开，控制功耗与竞争）
        val Crypto = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        ).asCoroutineDispatcher()
        val Database = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    const val ADAPTIVE_BUFFER_SIZE = 1024 * 1024
    val MAX_PARALLEL_TASKS = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2

    private val byteArrayPool = Collections.synchronizedList(LinkedList<ByteArray>())
    fun acquireBuffer(): ByteArray = synchronized(byteArrayPool) {
        if (byteArrayPool.isEmpty()) ByteArray(ADAPTIVE_BUFFER_SIZE) else byteArrayPool.removeAt(0)
    }
    fun releaseBuffer(buffer: ByteArray) {
        Arrays.fill(buffer, 0.toByte())
        synchronized(byteArrayPool) { if (byteArrayPool.size < 16) byteArrayPool.add(buffer) }
    }

    // 资产分类常量：委托 VaultCategories（保持门面兼容，值统一在 VaultCategories 维护）
    const val CAT_PJM = VaultCategories.CAT_PJM
    const val CAT_BILI_VIDEOS = VaultCategories.CAT_BILI_VIDEOS
    const val CAT_IMAGES = VaultCategories.CAT_IMAGES
    const val CAT_VIDEOS = VaultCategories.CAT_VIDEOS
    const val CAT_AUDIOS = VaultCategories.CAT_AUDIOS
    const val CAT_OTHERS = VaultCategories.CAT_OTHERS
    val CATEGORIES = VaultCategories.CATEGORIES

    // ===== 多任务并发进度 + 全局信号：委托 VaultTasks（门面转发，外部调用点不变） =====
    const val TASK_DEFAULT = VaultTasks.TASK_DEFAULT
    const val TASK_DELETE = VaultTasks.TASK_DELETE
    const val TASK_DUPLICATES_EXACT = VaultTasks.TASK_DUPLICATES_EXACT
    const val TASK_DUPLICATES_PERCEPTUAL = VaultTasks.TASK_DUPLICATES_PERCEPTUAL
    const val TASK_SYNC = VaultTasks.TASK_SYNC
    const val TASK_STORE = VaultTasks.TASK_STORE
    const val TASK_ENCRYPT = VaultTasks.TASK_ENCRYPT
    const val TASK_EXTRACT = VaultTasks.TASK_EXTRACT
    const val TASK_EXPORT = VaultTasks.TASK_EXPORT
    const val TASK_BILI_SCAN = VaultTasks.TASK_BILI_SCAN
    const val TASK_BILI_SCAN_MERGED = VaultTasks.TASK_BILI_SCAN_MERGED
    const val TASK_BILI_IMPORT = VaultTasks.TASK_BILI_IMPORT
    const val TASK_BILI_IMPORT_MERGED = VaultTasks.TASK_BILI_IMPORT_MERGED
    const val TASK_INTEGRITY = VaultTasks.TASK_INTEGRITY
    const val TASK_CLEAR_CACHE = VaultTasks.TASK_CLEAR_CACHE
    const val TASK_CLEAR_LOGS = VaultTasks.TASK_CLEAR_LOGS
    const val TASK_RESET = VaultTasks.TASK_RESET
    const val TASK_RECOVER = VaultTasks.TASK_RECOVER
    const val TASK_INIT = VaultTasks.TASK_INIT

    val operationResults = VaultTasks.operationResults
    val refreshSignal = VaultTasks.refreshSignal
    val cacheClearedSignal = VaultTasks.cacheClearedSignal
    val activeTasks = VaultTasks.activeTasks
    val isOperationActive: Boolean get() = VaultTasks.isOperationActive

    fun requestCancelTask(taskId: String) = VaultTasks.requestCancelTask(taskId)
    fun isTaskCancelled(taskId: String): Boolean = VaultTasks.isTaskCancelled(taskId)
    fun tryBeginOperation(taskId: String = "default"): Boolean = VaultTasks.tryBeginOperation(taskId)
    fun endOperation(taskId: String = "default") = VaultTasks.endOperation(taskId)
    fun updateProgress(
        progress: Float,
        message: String,
        taskId: String = "default",
        isActive: Boolean = true,
        isError: Boolean = false,
        isIndeterminate: Boolean = false
    ) = VaultTasks.updateProgress(progress, message, taskId, isActive, isError, isIndeterminate)

    fun clearProgress(taskId: String = "default") = VaultTasks.clearProgress(taskId)
    fun triggerRefresh() = VaultTasks.triggerRefresh()
    fun notifyCacheCleared() = VaultTasks.notifyCacheCleared()
    fun notifyResult(result: OperationResult) = VaultTasks.notifyResult(result)

    fun ensureDiskSpace(context: Context, requiredSize: Long = 0) {
        if (context.filesDir.usableSpace < (requiredSize + 100 * 1024 * 1024L)) {
            throw IOException(context.getString(R.string.error_insufficient_storage_extract))
        }
    }

    suspend fun digestFileToEntity(
        context: Context, fileName: String, inputStream: InputStream, 
        expectedSize: Long = 0L, overrideCategory: String? = null
    ): PjmResult<FileEntity> = withContext(PjmDispatchers.IO) {
        val category = overrideCategory ?: FileUtils.getCategory(fileName)
        val targetFile = getNextVaultPath(context, category, fileName)
        val tempFile = File(context.cacheDir, "digest_${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                if (expectedSize > 0) try { Os.posix_fallocate(fos.fd, 0, expectedSize) } catch (_: Exception) { }
                if (inputStream is FileInputStream) {
                    inputStream.channel.transferTo(0, inputStream.channel.size(), fos.channel)
                } else {
                    val buffer = acquireBuffer()
                    try {
                        var n: Int
                        while (inputStream.read(buffer).also { n = it } != -1) fos.write(buffer, 0, n)
                    } finally { releaseBuffer(buffer) }
                }
                fos.channel.force(true)
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.inputStream().use { it.copyTo(targetFile.outputStream()) }
                tempFile.delete()
            }
            
            // 核心加固：强制刷新系统媒体库，确保删除弹窗能识别到入库后的文件
            // 我们需要等待扫描完成，或者至少确保它被触发
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null) { path, uri ->
                PjmLogger.d(TAG, "Media scan completed for $path -> $uri")
            }

            PjmResult.Success(FileEntity(
                relativePath = getRelativePath(context, targetFile), name = fileName, 
                size = targetFile.length(), category = category, lastModified = System.currentTimeMillis(), 
                isImage = FileUtils.isImageFile(fileName), extension = FileUtils.getFileExtension(fileName), contentHash = null
            ))
        } catch (e: Exception) {
            tempFile.delete()
            PjmResult.Failure("Ingest failed", exception = e)
        }
    }

    fun getCategoryDir(context: Context, category: String): File = VaultPaths.getCategoryDir(context, category)
    fun getFileFromEntity(context: Context, entity: FileEntity): File = VaultPaths.getFileFromEntity(context, entity)
    fun getRelativePath(context: Context, file: File): String = VaultPaths.getRelativePath(context, file)
    fun getNextVaultPath(context: Context, category: String, originalName: String): File = VaultPaths.getNextVaultPath(context, category, originalName)

    suspend fun extractPjmToVault(context: Context, entity: FileEntity, fileDao: FileDao) = withContext(PjmDispatchers.IO) {
        val file = getFileFromEntity(context, entity)
        // 核心修复：提取过程必须有可见反馈（上方横幅 GlobalProgressOverlay），
        // 否则用户点击"立即提取"后静默执行，成功/失败都无法感知。
        updateProgress(0.1f, context.getString(R.string.status_extracting, entity.name), taskId = TASK_EXTRACT)
        var count = 0
        var failed = false
        try {
            CryptoUtils.decryptPjmToEntries(context, listOf(Uri.fromFile(file))) { name, input ->
                digestFileToEntity(context, name, input).onSuccess {
                    fileDao.upsert(it)
                    count++
                    VaultManager.updateProgress(
                        0.1f + 0.8f * (count / 100f).coerceAtMost(1f),
                        context.getString(R.string.status_extracting, entity.name),
                        taskId = TASK_EXTRACT
                    )
                }
            }
        } catch (e: Exception) {
            failed = true
            PjmLogger.e(TAG, "Extract failed for ${entity.name}", e)
        }
        if (failed || count == 0) {
            updateProgress(0f, context.getString(R.string.error_extract_failed, entity.name), taskId = TASK_EXTRACT, isError = true)
        } else {
            updateProgress(1f, context.getString(R.string.status_extract_complete, count), taskId = TASK_EXTRACT)
        }
        delay(1500)
        clearProgress(TASK_EXTRACT)
        VaultManager.triggerRefresh()
    }

    suspend fun deleteFile(context: Context, relativePath: String, fileDao: FileDao) = withContext(PjmDispatchers.IO) {
        mutex.withLock {
            // 核心修复：删除前同步清理对应缩略图 + 感知指纹（图片/视频），避免孤儿残留
            try { fileDao.findByRelativePath(relativePath)?.let { ThumbnailCache.delete(context, it); ImageFingerprintCache.delete(context, it) } } catch (_: Exception) {}
            shredFile(File(File(context.filesDir, VAULT_ROOT), relativePath))
            fileDao.deleteByRelativePath(relativePath)
            triggerRefresh()
        }
    }

    suspend fun deleteFiles(context: Context, relativePaths: List<String>, fileDao: FileDao) = withContext(PjmDispatchers.IO) {
        if (relativePaths.isEmpty()) return@withContext
        mutex.withLock {
            val root = File(context.filesDir, VAULT_ROOT)
            relativePaths.forEach { relPath ->
                // 核心修复：删除前同步清理对应缩略图 + 感知指纹（图片/视频），避免孤儿残留
                try { fileDao.findByRelativePath(relPath)?.let { ThumbnailCache.delete(context, it); ImageFingerprintCache.delete(context, it) } } catch (_: Exception) {}
                shredFile(File(root, relPath))
            }
            fileDao.deleteByRelativePaths(relativePaths)
            triggerRefresh()
        }
    }

    /**
     * 删除文件。
     * 核心修复：直接物理删除（快速）—— 之前固定 7 次 SecureRandom 覆写 + 每次 fd.sync，
     * GB 级大文件删除耗时以分钟计，进度条长时间卡在中间，体验极差。
     * 闪存介质上覆写也无法真正抹除数据，故统一为直接删除。
     */
    internal fun shredFile(file: File) {
        if (!file.exists()) return
        try { file.delete() } catch (_: Exception) {}
    }

    suspend fun fullSyncDatabase(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}) = withContext(PjmDispatchers.IO) {
        mutex.withLock {
            val list = mutableListOf<Pair<File, String>>()
            CATEGORIES.forEach { cat -> getCategoryDir(context, cat).listFiles()?.filter { it.isFile }?.forEach { list.add(it to cat) } }
            val entities = list.mapIndexed { i, (f, c) ->
                onProgress(i.toFloat() / list.size)
                FileEntity(relativePath = getRelativePath(context, f), name = f.name, size = f.length(), category = c, lastModified = f.lastModified(), isImage = FileUtils.isImageFile(f.name), extension = FileUtils.getFileExtension(f.name), contentHash = null)
            }
            fileDao.replaceAll(entities)
        }
    }

    /**
     * 一次性命名迁移：把旧命名规则的 PJM 容器统一为最新规范
     * `前缀_yyyyMMdd_HHmmss.pjm.N`（可读时间戳，N 从 1 开始）。
     *
     * 处理的旧格式：
     *  1. 名字主体时间部分是 13 位毫秒（旧导出风格，如 `Export_1767225600000.pjm.1`）
     *     → 换算为可读时间 `Export_20260101_000000.pjm.1`；
     *  2. 单卷文件缺省数字后缀（旧风格 `X.pjm`）→ 补全为 `X.pjm.1`。
     *
     * - 目标名已存在时不覆盖、跳过（幂等，不视为失败）；
     * - 物理改名或数据库索引更新失败则抛异常（不写 migration_done，下次启动重试）；
     * - 同步更新数据库索引（relativePath/name），保留其余字段（如 contentHash）。
     *
     * @return 实际迁移的文件数
     */
    suspend fun migrateLegacyPjmNaming(context: Context, fileDao: FileDao): Int = withContext(PjmDispatchers.IO) {
        mutex.withLock {
            val root = File(context.filesDir, VAULT_ROOT)
            // 收集所有含 .pjm 的文件（容器 X.pjm / X.pjm.N）；非容器由 legacyToCanonicalName 原样返回自然跳过
            val containers = runCatching {
                root.walkTopDown().filter { it.isFile && it.name.contains(".pjm") }.toList()
            }.getOrDefault(emptyList())
            if (containers.isEmpty()) return@withLock 0

            var count = 0
            var failed = 0
            containers.forEach { file ->
                val dir = file.parentFile ?: run { failed++; return@forEach }
                val legacyName = file.name
                val canonicalName = legacyToCanonicalName(legacyName) ?: return@forEach
                if (canonicalName == legacyName) return@forEach // 已是规范命名
                val target = File(dir, canonicalName)
                if (target.exists()) {
                    PjmLogger.w(TAG, "命名迁移跳过（目标已存在，避免覆盖）: $legacyName -> $canonicalName")
                    return@forEach
                }
                val oldRel = getRelativePath(context, file)
                val renamed = runCatching { file.renameTo(target) }.getOrDefault(false)
                if (!renamed) {
                    failed++
                    PjmLogger.w(TAG, "命名迁移失败（重命名失败）: $legacyName -> $canonicalName")
                    return@forEach
                }
                // 同步数据库索引：旧路径记录删除，新路径记录保留原字段写入。
                // 关键：若索引更新失败，必须把文件改回原名回滚，保证磁盘与数据库一致，
                // 否则下次重试会因文件已是新名而跳过，数据库将永远指向旧路径（UI 读不到文件）。
                val dbOk = runCatching {
                    fileDao.findByRelativePath(oldRel)?.let { entity ->
                        fileDao.deleteByRelativePath(oldRel)
                        fileDao.upsert(entity.copy(relativePath = getRelativePath(context, target), name = target.name))
                    }
                }.isSuccess
                if (!dbOk) {
                    val rollbackOk = runCatching { target.renameTo(file) }.getOrDefault(false)
                    if (!rollbackOk) {
                        // 回滚也失败：文件已在新名但数据库仍指向旧路径，属严重不一致，必须让用户知晓
                        failed++
                        PjmLogger.e(TAG, "命名迁移严重错误：$legacyName 已改名但索引更新失败且回滚失败，文件与数据库不一致！")
                    } else {
                        PjmLogger.w(TAG, "命名迁移已回滚（索引更新失败）: $legacyName")
                    }
                    failed++
                    return@forEach
                }
                count++
            }
            if (failed > 0) {
                // 抛出让调用方不要写入 migration_done，下次启动自动重试剩余文件（已改名的不受影响，幂等）
                throw IOException("命名迁移未完成：$failed 个文件失败（已成功 $count 个）")
            }
            if (count > 0) {
                triggerRefresh()
                PjmLogger.i(TAG, "命名迁移完成：$count 个旧命名文件已统一为 前缀_yyyyMMdd_HHmmss.pjm.N 格式")
            }
            count
        }
    }

    /**
     * 旧式容器名 → 规范名（委托 [VaultNaming.legacyToCanonicalName]）。
     * @return 规范名；已是规范名返回原名；非 PJM 容器返回 null
     */
    private fun legacyToCanonicalName(name: String): String? = VaultNaming.legacyToCanonicalName(name)

    // ===== 健康扫描（完整性/查重/指纹）：委托 VaultScanner（门面转发） =====
    suspend fun checkIntegrity(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): Map<String, List<FileEntity>> =
        VaultScanner.checkIntegrity(context, fileDao, onProgress)

    suspend fun findDuplicateFiles(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): List<DuplicateGroup> =
        VaultScanner.findDuplicateFiles(context, fileDao, onProgress)

    /**
     * 图片感知查重：找出【内容相同但分辨率不同】的图片（原图 vs QQ 缩略图等）。
     * 实现已迁移至 [VaultScanner.findSimilarImages]。
     */
    suspend fun findSimilarImages(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): List<DuplicateGroup> =
        VaultScanner.findSimilarImages(context, fileDao, onProgress)

    // ===== 数据库备份 / 打包导出：委托 VaultPackager（门面转发） =====
    suspend fun backupDatabase(context: Context) = VaultPackager.backupDatabase(context)

    suspend fun exportVaultToPjmModule(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}): Result<Int> =
        VaultPackager.exportVaultToPjmModule(context, fileDao, onProgress)

    suspend fun packUrisWithSplitting(context: Context, uris: List<Uri>, category: String, baseName: String, fileDao: FileDao, onProgress: (Float) -> Unit): Result<Int> =
        VaultPackager.packUrisWithSplitting(context, uris, category, baseName, fileDao, onProgress)

    suspend fun packUrisSingle(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = VaultPackager.packUrisSingle(context, uris, category, baseName, fileDao, onProgress)

    fun calculateHash(file: File): String? = VaultScanner.calculateHash(file)

    fun calculateHash(input: InputStream): String? = VaultScanner.calculateHash(input)

    fun calculateVideoFingerprint(file: File): String? = VaultScanner.calculateVideoFingerprint(file)

    /** 64-bit dHash：缩放 9x8 灰度，逐像素比较生成感知哈希（图片指纹与视频指纹共用） */
    internal fun dHash64(bitmap: Bitmap): String = VaultScanner.dHash64(bitmap)


    private fun InputStream.copyTo(os: OutputStream) {
        val buf = acquireBuffer()
        try {
            var r: Int
            while (read(buf).also { r = it } != -1) os.write(buf, 0, r)
        } finally { releaseBuffer(buf) }
    }
}

/**
 * 单个并发任务的进度状态（多任务进度模型）。
 * @property taskId 任务唯一标识（同 id 防连点；不同 id 可并行显示）
 */
data class OperationTask(
    val taskId: String = "default",
    val progress: Float = 0f,
    val message: String = "",
    val isActive: Boolean = false,
    val isError: Boolean = false,
    val isIndeterminate: Boolean = false // 不确定进度（如扫描遍历），UI 显示无限循环条而非百分比
)

/**
 * 重复内容分组（查重结果）。
 *
 * 用于 UI 双图对比展示：组内包含【全部成员】（含建议保留的原图），
 * 用户可并排查看两张图是否真的一样，再决定勾选哪些删除。
 *
 * @property members 组内全部文件（按保留优先级排序：第一位为推荐保留项）
 * @property recommendedDelete 建议删除的文件 relativePath 集合（UI 默认勾选这些）
 */
data class DuplicateGroup(
    val members: List<FileEntity>,
    val recommendedDelete: Set<String>
)
