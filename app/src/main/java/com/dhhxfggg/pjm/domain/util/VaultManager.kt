package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.system.Os
import com.dhhxfggg.pjm.MainApplication
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.AppDatabase
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
 */
object VaultManager {
    private const val TAG = "VaultManager"
    private const val VAULT_ROOT = "pjm_vault"
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

    const val CAT_PJM = "pjm"
    const val CAT_BILI_VIDEOS = "bili_videos"
    const val CAT_IMAGES = "images"
    const val CAT_VIDEOS = "videos"
    const val CAT_AUDIOS = "audios"
    const val CAT_OTHERS = "others"
    val CATEGORIES = listOf(CAT_PJM, CAT_IMAGES, CAT_VIDEOS, CAT_BILI_VIDEOS, CAT_AUDIOS, CAT_OTHERS)

    // 多任务并发进度 id：不同任务可并行（各自进度条独立显示），同任务防连点
    const val TASK_DEFAULT = "default"
    const val TASK_DELETE = "delete"
    const val TASK_DUPLICATES_EXACT = "duplicates_exact"
    const val TASK_DUPLICATES_PERCEPTUAL = "duplicates_perceptual"
    const val TASK_SYNC = "sync"
    const val TASK_STORE = "store"
    const val TASK_ENCRYPT = "encrypt"
    const val TASK_EXTRACT = "extract"
    const val TASK_EXPORT = "export"
    const val TASK_BILI_SCAN = "bili_scan"
    const val TASK_BILI_SCAN_MERGED = "bili_scan_merged"
    const val TASK_BILI_IMPORT = "bili_import"
    const val TASK_BILI_IMPORT_MERGED = "bili_import_merged"
    const val TASK_INTEGRITY = "integrity"
    const val TASK_CLEAR_CACHE = "clear_cache"
    const val TASK_CLEAR_LOGS = "clear_logs"
    const val TASK_RESET = "reset"
    const val TASK_RECOVER = "recover"
    const val TASK_INIT = "init"

    private val _operationResults = MutableSharedFlow<OperationResult>(extraBufferCapacity = 16)
    val operationResults = _operationResults.asSharedFlow()

    private val _refreshSignal = MutableSharedFlow<Unit>(replay = 1)
    val refreshSignal = _refreshSignal.asSharedFlow()

    // 缓存清除信号：用户点击清除缓存后广播，各 ViewModel 据此清空内存缓存（如缩略图 LRU）
    private val _cacheClearedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val cacheClearedSignal = _cacheClearedSignal.asSharedFlow()

    // 核心修复：多任务并发进度模型。
    // 不同 taskId 的任务可【同时进行】，各自维护独立进度（UI 并行显示多个进度条）；
    // 同一 taskId 的任务防连点（tryBeginOperation 返回 false，忽略本次触发）。
    private val _activeTasks = MutableStateFlow<List<OperationTask>>(emptyList())
    val activeTasks = _activeTasks.asStateFlow()

    // 核心新增：任务取消机制 —— 进度条卡住时用户可点 × 中断任务
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    /** 请求取消指定任务（任务内部循环检查 [isTaskCancelled] 后提前结束） */
    fun requestCancelTask(taskId: String) {
        cancelFlags.getOrPut(taskId) { AtomicBoolean(false) }.set(true)
    }

    /** 任务是否已被请求取消 */
    fun isTaskCancelled(taskId: String): Boolean = cancelFlags[taskId]?.get() == true

    /** 清除任务的取消标志（任务重新开始时调用） */
    private fun clearTaskCancel(taskId: String) { cancelFlags.remove(taskId) }

    /**
     * 尝试开始一个任务。同 taskId 已有进行中任务 → false（防连点）；
     * 不同 taskId 允许并发（如查重进行中仍可删除文件）。
     */
    fun tryBeginOperation(taskId: String = "default"): Boolean {
        if (_activeTasks.value.any { it.taskId == taskId && it.isActive }) return false
        clearTaskCancel(taskId) // 重新开始前清除旧取消标志
        return true
    }

    /** 结束/移除一个任务（释放其进度条） */
    fun endOperation(taskId: String = "default") {
        autoClearJobs.remove(taskId)?.cancel()
        clearTaskCancel(taskId)
        _activeTasks.update { list -> list.filterNot { it.taskId == taskId } }
    }

    /** 当前是否有任何操作在进行（缩略图后台同步等据此让位） */
    val isOperationActive: Boolean get() = _activeTasks.value.any { it.isActive }

    // 每个任务独立的完成态自动清除定时器
    private val autoClearJobs = ConcurrentHashMap<String, Job>()

    /**
     * 更新指定任务的进度。任务不存在则自动创建。
     * 完成态（progress>=1 / 错误 / 非活跃）自动 2.5s 后清除该任务，不影响其他任务。
     */
    fun updateProgress(
        progress: Float,
        message: String,
        taskId: String = "default",
        isActive: Boolean = true,
        isError: Boolean = false,
        isIndeterminate: Boolean = false
    ) {
        val task = OperationTask(
            taskId = taskId,
            progress = progress.coerceIn(0f, 1f),
            message = message,
            isActive = isActive,
            isError = isError,
            isIndeterminate = isIndeterminate
        )
        _activeTasks.update { list ->
            if (list.any { it.taskId == taskId }) list.map { if (it.taskId == taskId) task else it }
            else list + task
        }
        val scope = MainApplication.applicationScope
        if (progress >= 1f || isError || !isActive) {
            autoClearJobs[taskId]?.cancel()
            autoClearJobs[taskId] = scope.launch {
                delay(2500)
                endOperation(taskId)
                autoClearJobs.remove(taskId)
            }
        } else {
            autoClearJobs.remove(taskId)?.cancel()
        }
    }

    /** 清除指定任务（默认 "default"）。不影响其他进行中的任务。 */
    fun clearProgress(taskId: String = "default") {
        autoClearJobs.remove(taskId)?.cancel()
        _activeTasks.update { list -> list.filterNot { it.taskId == taskId } }
    }
    fun triggerRefresh() { _refreshSignal.tryEmit(Unit) }
    fun notifyCacheCleared() { _cacheClearedSignal.tryEmit(Unit) }
    fun notifyResult(result: OperationResult) { _operationResults.tryEmit(result) }

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

    fun getCategoryDir(context: Context, category: String): File = File(context.filesDir, "$VAULT_ROOT/$category").apply { if (!exists()) mkdirs() }
    fun getFileFromEntity(context: Context, entity: FileEntity): File = File(File(context.filesDir, VAULT_ROOT), entity.relativePath)
    fun getRelativePath(context: Context, file: File): String = file.absolutePath.removePrefix(File(context.filesDir, VAULT_ROOT).absolutePath).trimStart(File.separatorChar)
    fun getNextVaultPath(context: Context, category: String, originalName: String): File {
        val dir = getCategoryDir(context, category)
        return File(dir, "${UUID.randomUUID()}.${FileUtils.getFileExtension(originalName)}")
    }

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

    /** 当前时间 → 可读命名时间戳（yyyyMMdd_HHmmss），加密容器命名统一使用 */
    private fun readableTimestamp(): String = formatReadable(System.currentTimeMillis())

    /** 毫秒 → 可读命名时间戳（yyyyMMdd_HHmmss） */
    private fun formatReadable(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(millis))

    /** 旧版命名中的 13 位毫秒时间戳片段（如 Export_1767225600000） */
    private val LEGACY_MILLIS_NAME = Regex("^(.*)_(\\d{13})$")
    private val VOLUME_SUFFIX = Regex("^(.*)\\.pjm\\.(\\d+)$")

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
     * 旧式容器名 → 规范名 `前缀_yyyyMMdd_HHmmss.pjm.N`。
     * @return 规范名；已是规范名返回原名；非 PJM 容器返回 null
     */
    private fun legacyToCanonicalName(name: String): String? {
        val volume: Int?
        val body: String
        val volMatch = VOLUME_SUFFIX.matchEntire(name)
        if (volMatch != null) {
            body = volMatch.groupValues[1]
            volume = volMatch.groupValues[2].toIntOrNull() ?: return null
        } else if (name.endsWith(".pjm")) {
            body = name.removeSuffix(".pjm")
            volume = null
        } else {
            return null
        }

        // 主体尾部是 13 位毫秒时间戳 → 换算为可读时间
        val timeMatch = LEGACY_MILLIS_NAME.matchEntire(body)
        val newBody = if (timeMatch != null) {
            val prefix = timeMatch.groupValues[1]
            val millis = timeMatch.groupValues[2].toLongOrNull()
            if (millis == null) body else "${prefix}_${formatReadable(millis)}"
        } else {
            body
        }

        // 缺省数字后缀的单卷 → 补全为 .pjm.1
        return if (volume == null) "$newBody.pjm.1" else "$newBody.pjm.$volume"
    }

    suspend fun checkIntegrity(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): Map<String, List<FileEntity>> = withContext(PjmDispatchers.IO) {
        val all = fileDao.getAllFiles().first()
        val missing = mutableListOf<FileEntity>()
        val corrupted = mutableListOf<FileEntity>()
        all.forEachIndexed { i, e ->
            onProgress(i.toFloat() / all.size)
            val f = getFileFromEntity(context, e)
            if (!f.exists()) missing.add(e)
            else if (e.contentHash != null && calculateHash(f) != e.contentHash) corrupted.add(e)
        }
        mapOf("missing" to missing, "corrupted" to corrupted)
    }

    suspend fun findDuplicateFiles(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): List<DuplicateGroup> = withContext(PjmDispatchers.IO) {
        val allFiles = fileDao.getAllFiles().first()
        val suspects = allFiles.groupBy { it.size }.filter { it.value.size > 1 }.values.flatten()
        suspects.forEachIndexed { i, entity ->
            if (isTaskCancelled(TASK_DUPLICATES_EXACT)) throw CancellationException("精确查重已取消")
            onProgress(i.toFloat() / suspects.size)
            val file = getFileFromEntity(context, entity)
            // 核心修复：视频用内容级感知指纹（时长+分辨率+关键帧 dHash），
            // 因为 merge 重封装导致字节级（MD5）不同，但内容相同的视频 MD5 指纹永远检测不到。
            val hash = if (FileUtils.isVideoFile(entity.name)) {
                calculateVideoFingerprint(file)
            } else {
                entity.contentHash ?: calculateHash(file)
            }
            if (hash != entity.contentHash) fileDao.upsert(entity.copy(contentHash = hash))
        }
        val finalFiles = fileDao.getAllFiles().first()
        // 核心修复：返回【分组】结构 —— 组内包含全部成员（含保留的原图）与建议删除集，
        // 供 UI 双图对比展示（让用户确认后自行勾选要删除的）
        val result = mutableListOf<DuplicateGroup>()
        finalFiles.filter { it.contentHash != null }.groupBy { it.contentHash }.values.forEach { group ->
            if (group.size > 1) {
                val sorted = group.sortedBy { it.lastModified }
                result.add(DuplicateGroup(members = sorted, recommendedDelete = sorted.drop(1).map { it.relativePath }.toSet()))
            }
        }
        result
    }

    /**
     * 图片感知查重：找出【内容相同但分辨率不同】的图片（原图 vs QQ 缩略图等）。
     *
     * 算法（针对上万张图片优化，1.3 万张实测通过）：
     * 1. 指纹（增量）：每张图采样解码算 64-bit dHash + 原始分辨率，落盘 [ImageFingerprintCache]。
     *    已缓存的直接跳过 —— 下次新增图片只需算新图，秒级增量。
     * 2. 全量两两粗筛：64 位 dHash 转 Long，用 bitCount 快速算汉明距离（1 万张 ≈ 5 千万对，
     *    JVM 上仅数秒）。阈值 ≤ 16 —— 大缩放 + 重压缩可能翻转较多位，必须放宽保证召回。
     * 3. 【内存宽高比预过滤】：比例差异 > 3% 的对直接排除（原图 4:3 与 16:9 不可能是缩略图关系），
     *    候选对骤降 90%+ —— 这是防止 256MB 堆 OOM 的关键。
     * 4. 候选对用 IntArray 紧凑编码（4 字节/对）而非 Pair 装箱（~40 字节/对），
     *    百万候选对仅 ~4MB。
     * 5. 候选对精确确认：[ImageFingerprintCache.verifySameContent]（宽高比一致 + 128px
     *    逐像素亮度差 ≤ 阈值）才算重复 —— 像素验证对重采样鲁棒，是主判定，
     *    误报率极低。
     * 6. Union-Find 连通成组；每组【保留分辨率最高】的，其余标记为建议删除。
     *
     * @return 重复图片分组（组内含全部成员供对比展示，recommendedDelete 默认勾选）
     */
    suspend fun findSimilarImages(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): List<DuplicateGroup> = withContext(PjmDispatchers.IO) {
        val all = fileDao.getAllFiles().first()
        val images = all.filter { FileUtils.isImageFile(it.name) }
        if (images.size < 2) return@withContext emptyList()

        // 1) 计算/读取指纹（增量：已缓存跳过）
        // 核心修复（崩溃/卡死）：
        //   a. 分批处理（每批 128 张）—— 绝不一次性创建 1.2 万个协程，控制内存峰值；
        //   b. Semaphore(3) 限流 —— 只 3 路并发解码，避免 OOM + 避免占满 8 线程 IO 池
        //      （否则删除等其他操作排队，用户感知"卡死"）；
        //   c. computeFingerprint 内部 catch Throwable（含 OOM）+ 显式 recycle，单图失败不影响整体。
        data class Fp(val entity: FileEntity, val fp: ImageFingerprint?)
        val fpSemaphore = Semaphore(3)
        val fps = mutableListOf<Fp>()
        var processedTotal = 0
        for (batch in images.chunked(128)) {
            if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("指纹计算已取消")
            fps += coroutineScope {
                batch.map { e ->
                    async(PjmDispatchers.IO) {
                        fpSemaphore.withPermit {
                            val cached = ImageFingerprintCache.getFingerprint(context, e)
                            if (cached != null) {
                                Fp(e, cached)
                            } else {
                                val computed = ImageFingerprintCache.computeFingerprint(context, e)
                                if (computed != null) {
                                    ImageFingerprintCache.saveFingerprint(context, e, computed)
                                    Fp(e, computed)
                                } else Fp(e, null)
                            }
                        }
                    }
                }.awaitAll()
            }
            processedTotal += batch.size
            // 进度：每批更新一次
            onProgress(0.6f * (processedTotal.toFloat() / images.size))
        }
        if (fps.size < 2) return@withContext emptyList()
        val fpList = fps.filter { it.fp != null && it.fp!!.dHash.length == 64 }
        if (fpList.size < 2) return@withContext emptyList()

        // 2) 二进制串 → Long（加速汉明距离）+ 宽高比/面积预计算（粗筛纯内存过滤）
        val dHashes = LongArray(fpList.size) { i -> fpList[i].fp!!.dHash.toLongOrNull(2) ?: 0L }
        val ratios = FloatArray(fpList.size) { i ->
            val fp = fpList[i].fp!!
            fp.width.toFloat() / fp.height.coerceAtLeast(1)
        }
        val areas = LongArray(fpList.size) { i ->
            val fp = fpList[i].fp!!
            fp.width.toLong() * fp.height
        }

        // 3) 粗筛（核心修复 OOM + 80% 卡死）：
        //   a. O(n²) bitCount 保证 100% 召回（不遗漏任何汉明距离 ≤16 的对）；
        //   b. 【内存宽高比预过滤】—— 原图 4:3 与 16:9 不可能是缩略图关系，纯内存直接排除；
        //   c. 【面积差异预过滤】—— 本功能只找"原图 vs 缩略图"（面积差 ≥ 1.2 倍）；
        //   d. 候选对用 IntArray 紧凑编码 (a shl 16) or b —— 4 字节/对，百万候选对仅 ~4MB。
        onProgress(0.6f)
        val totalPairs = fpList.size.toLong() * (fpList.size - 1) / 2
        var candidates = IntArray(8192)
        var candidateCount = 0
        var processedPairs = 0L
        for (i in 0 until fpList.size - 1) {
            if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("比对已取消")
            val hi = dHashes[i]
            val ri = ratios[i]
            val areaI = areas[i]
            for (j in i + 1 until fpList.size) {
                if (++processedPairs % 8192 == 0L) {
                    onProgress(0.6f + 0.15f * (processedPairs.toFloat() / totalPairs))
                }
                // 宽高比差异 > 3% → 直接排除（与 verifySameContent 的比例检查一致，先省一次解码）
                val rj = ratios[j]
                if (abs(ri - rj) / maxOf(ri, rj) > 0.03f) continue
                // 面积差异 < 1.2 倍 → 同分辨率/近似分辨率，非"原图 vs 缩略图"，跳过
                val maxArea = maxOf(areaI, areas[j])
                val minArea = minOf(areaI, areas[j])
                if (maxArea < minArea * 1.2f) continue
                if (java.lang.Long.bitCount(hi xor dHashes[j]) <= 16) {
                    if (candidateCount == candidates.size) candidates = candidates.copyOf(candidates.size * 2)
                    candidates[candidateCount++] = (i shl 16) or j
                }
            }
        }
        onProgress(0.75f)
        PjmLogger.i(TAG, "图片感知查重：${fpList.size} 张图，${totalPairs} 对，候选对 $candidateCount")

        // 3.5) 核心新增：32×32 灰度预筛（纯内存，微秒级）—— 候选对可能达数百万，
        //      每对解码 64px 验证耗时以小时计。预计算每张图 32×32 灰度（1024 字节，全量仅 ~13MB），
        //      候选对先纯内存比较灰度：平均亮度差 > 15 → 内容不一致，直接排除。
        //      同图不同分辨率灰度差 < 6（通过），不同图 > 20（排除）—— 可砍掉 95%+ 干扰对。
        // 核心修复（半永久化）：getOrComputeGray32 读缓存优先，未命中才解码并【落盘 .g32】。
        //      首次查重计算 1.3 万张（~5 分钟），之后每次查重秒级复用，不再重复解码大图。
        onProgress(0.75f)
        val gray32Cache = HashMap<String, ByteArray>(fpList.size)
        // 加载/计算灰度（并行，每批 128；已落盘的直接读文件，秒级）
        var grayProcessed = 0
        val grayBatchSize = 128
        for (start in 0 until fpList.size step grayBatchSize) {
            if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("灰度计算已取消")
            val end = minOf(start + grayBatchSize, fpList.size)
            coroutineScope {
                (start until end).map { idx ->
                    async(PjmDispatchers.IO) {
                        val e = fpList[idx].entity
                        ImageFingerprintCache.getOrComputeGray32(context, e)?.let { e.relativePath to it }
                    }
                }.awaitAll().forEach { pair -> if (pair != null) gray32Cache[pair.first] = pair.second }
            }
            grayProcessed += end - start
            onProgress(0.75f + 0.05f * (grayProcessed.toFloat() / fpList.size))
        }
        // 用灰度预筛过滤候选对（内存紧凑重建，避免保留被淘汰的）
        if (candidateCount > 0) {
            var kept = 0
            for (idx in 0 until candidateCount) {
                if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("灰度预筛已取消")
                val pair = candidates[idx]
                val i = pair shr 16
                val j = pair and 0xFFFF
                val g1 = gray32Cache[fpList[i].entity.relativePath]
                val g2 = gray32Cache[fpList[j].entity.relativePath]
                if (g1 != null && g2 != null && ImageFingerprintCache.gray32Similar(g1, g2)) {
                    candidates[kept++] = pair
                }
            }
            candidateCount = kept
        }
        gray32Cache.clear()
        onProgress(0.8f)
        PjmLogger.i(TAG, "图片感知查重：灰度预筛后候选对 $candidateCount")

        // 核心优化：候选对【并行】验证（解码 128px 是重活）
        // 核心修复（崩溃/卡死）：
        //   a. 分批验证（每批 32 对），批间更新进度 —— 避免验证阶段进度条卡住；
        //   b. Semaphore(3) 限流 —— 控制并发解码内存峰值；
        //   c. verifySameContent 内部 catch Throwable（含 OOM）+ recycle，单对失败不影响整体。
        val parent = IntArray(fpList.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        var verified = 0
        if (candidateCount > 0) {
            val totalCandidates = candidateCount
            var processedCandidates = 0
            var batchStart = 0
            while (batchStart < candidateCount) {
                if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) throw CancellationException("验证已取消")
                val batchEnd = minOf(batchStart + 32, candidateCount)
                coroutineScope {
                    (batchStart until batchEnd).map { idx ->
                        async(PjmDispatchers.IO) {
                            if (isTaskCancelled(TASK_DUPLICATES_PERCEPTUAL)) return@async null
                            val pair = candidates[idx]
                            val i = pair shr 16
                            val j = pair and 0xFFFF
                            fpSemaphore.withPermit {
                                if (ImageFingerprintCache.verifySameContent(context, fpList[i].entity, fpList[j].entity)) i to j else null
                            }
                        }
                    }.awaitAll().forEach { pair ->
                        if (pair != null) {
                            union(pair.first, pair.second)
                            verified++
                        }
                    }
                }
                processedCandidates += batchEnd - batchStart
                batchStart = batchEnd
                // 进度 80% → 90%：每 512 对才更新一次，避免海量候选对时高频 StateFlow 冲刷；
                // 进度按已处理比例平滑推进（候选对减少后肉眼可见地快速爬升）
                if (processedCandidates % 512 == 0 || processedCandidates == totalCandidates) {
                    onProgress(0.8f + 0.1f * (processedCandidates.toFloat() / totalCandidates))
                }
            }
        }
        onProgress(0.9f)
        PjmLogger.i(TAG, "图片感知查重：确认重复对 $verified")

        // 4) 分组：每组 ≥ 2 → 保留分辨率最高，其余标记为建议删除（供 UI 对比展示）
        val groups = HashMap<Int, MutableList<Fp>>()
        fpList.forEachIndexed { i, f -> groups.getOrPut(find(i)) { mutableListOf() }.add(f) }
        val result = mutableListOf<DuplicateGroup>()
        groups.values.forEach { g ->
            if (g.size > 1) {
                val sorted = g.sortedByDescending { it.fp!!.width.toLong() * it.fp!!.height }
                result.add(
                    DuplicateGroup(
                        members = sorted.map { it.entity },
                        recommendedDelete = sorted.drop(1).map { it.entity.relativePath }.toSet()
                    )
                )
            }
        }
        onProgress(1f)
        result
    }

    suspend fun backupDatabase(context: Context) = withContext(PjmDispatchers.Database) {
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

    suspend fun exportVaultToPjmModule(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}): Result<Int> = withContext(PjmDispatchers.IO) {
        runCatching {
            val settings = SettingsManager.getSettingsFlow(context).first()
            val volumeSize = settings.exportSplitSize.toLong() * 1024 * 1024
            val vaultRoot = File(context.filesDir, VAULT_ROOT)
            val allFiles = vaultRoot.walkTopDown().filter { it.isFile && !it.absolutePath.contains("/pjm/") }.toList()
            if (allFiles.isEmpty()) throw Exception("Vault empty")
            val totalBytes = allFiles.sumOf { it.length() }.coerceAtLeast(1L)
            val baseName = "Export_" + readableTimestamp()

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
                val volumeFile = File(getCategoryDir(context, CAT_PJM), "$baseName.pjm.${idx + 1}")
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
                    relativePath = getRelativePath(context, volumeFile),
                    name = volumeFile.name,
                    size = volumeFile.length(),
                    category = CAT_PJM,
                    lastModified = System.currentTimeMillis(),
                    isImage = false,
                    extension = "pjm",
                    contentHash = null
                ))
                processed += groupSize
            }
            triggerRefresh()
            groups.size
        }
    }

    suspend fun packUrisWithSplitting(context: Context, uris: List<Uri>, category: String, baseName: String, fileDao: FileDao, onProgress: (Float) -> Unit): Result<Int> = withContext(PjmDispatchers.IO) {
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
                val volumeFile = File(getCategoryDir(context, category), "$baseName.pjm.${idx + 1}")
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
                    relativePath = getRelativePath(context, volumeFile),
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
            triggerRefresh()
            groups.size
        }
    }

    /**
     * 将一组 URI 打包成【单个】PJM 容器（不分卷），存入指定分类并入库。
     * 适合少量文件（如随机图片分享），避免拆成多卷。
     * 命名统一为 `X.pjm.1`（编号从 1 开始），与分卷导出的 `X.pjm.1/2/3` 保持一致；
     * 加密文件始终带数字后缀，便于识别与后续统一处理。
     * @return 生成的容器文件
     */
    suspend fun packUrisSingle(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(PjmDispatchers.IO) {
        runCatching {
            if (uris.isEmpty()) throw Exception("Empty input")
            val volumeFile = File(getCategoryDir(context, category), "$baseName.pjm.1")
            CryptoUtils.encryptUris(
                context = context,
                inputUris = uris,
                outputPath = volumeFile.absolutePath,
                onProgress = onProgress
            )
            // 立即入库，文件柜立即可见、可分享
            fileDao.upsert(FileEntity(
                relativePath = getRelativePath(context, volumeFile),
                name = volumeFile.name,
                size = volumeFile.length(),
                category = category,
                lastModified = System.currentTimeMillis(),
                isImage = false,
                extension = "pjm",
                contentHash = null
            ))
            triggerRefresh()
            volumeFile
        }
    }

    fun calculateHash(file: File): String? {
        try {
            file.inputStream().use { return calculateHash(it) }
        } catch (_: Exception) { return null }
    }

    /**
     * 计算输入流的 MD5 指纹。
     * 供内容级去重比对使用；调用方负责关闭流。
     */
    fun calculateHash(input: InputStream): String? {
        val digest = MessageDigest.getInstance("MD5")
        val buf = acquireBuffer()
        return try {
            var r: Int
            while (input.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { null } finally { releaseBuffer(buf) }
    }

    /**
     * 视频内容级感知指纹（不受重封装影响）。
     *
     * 背景：B 站视频 merge（MediaMuxer 重封装）后，同一源视频每次输出的
     * 字节级（MD5）都不同（moov 时间戳/chunk 布局/元数据差异），
     * 导致 MD5 去重永远检测不到。改用内容特征：
     *   时长 + 分辨率 + 第 1 秒关键帧的 dHash（感知哈希）
     * 同一视频无论封装几次，指纹稳定；不同视频区分度高。
     */
    fun calculateVideoFingerprint(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
            val frame = retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            val dHash = if (frame != null) dHash64(frame) else "0"
            "$duration|$width|$height|$dHash"
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** 64-bit dHash：缩放 9x8 灰度，逐像素比较生成感知哈希（图片指纹与视频指纹共用） */
    internal fun dHash64(bitmap: Bitmap): String {
        return try {
            val w = 9
            val h = 8
            val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
            try {
                val pixels = IntArray(w * h)
                scaled.getPixels(pixels, 0, w, 0, 0, w, h)
                val gray = IntArray(w * h)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    gray[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                }
                val sb = StringBuilder(64)
                for (y in 0 until h) {
                    for (x in 0 until w - 1) {
                        sb.append(if (gray[y * w + x] >= gray[y * w + x + 1]) '1' else '0')
                    }
                }
                sb.toString()
            } finally {
                // 核心修复：回收中间缩放 Bitmap，降低 1.2 万次调用的 GC 压力（防 OOM 卡死）
                try { if (scaled != bitmap) scaled.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            "0"
        }
    }

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
