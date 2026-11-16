package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.util.*
import java.util.concurrent.Executors

sealed class OperationResult {
    data class Success(
        val action: String,
        val uris: List<Uri>,
        val imported: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val volumes: Int = 0,
    ) : OperationResult()

    data class Error(
        val action: String,
        val message: String,
    ) : OperationResult()

    data class PasswordRequired(
        val fileName: String,
        val uris: List<Uri>,
    ) : OperationResult()
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
    val deletableUris: List<Uri> = emptyList(),
)

/**
 * PJM Industrial Asset Manager.
 * 门面：对外保留统一入口；纯逻辑已下沉至 VaultPaths / VaultNaming 等对象。
 */

object VaultManager {
    private const val TAG = "VaultManager"
    private const val VAULT_ROOT = VaultPaths.VAULT_ROOT

    /** 临时文件清理时间窗：只清理 1 小时前的残留（避免误删正在写入的容器） */
    private const val STALE_TEMP_MIN_AGE_MS = 60L * 60 * 1000
    private val mutex = Mutex()

    /**
     * 数据库写入互斥（核心修复）：全量同步按分类整体替换（只删当前分类再插入），
     * 与导入/打包的 upsert 并发时，「删分类 → 插入」的窗口会把刚入库的行删掉
     * （文件仍在磁盘，但界面上暂时看不见）。所有写入串行化，代价可以忽略。
     */
    internal val dbWriteMutex = Mutex()

    internal suspend fun <T> withDbWriteLock(block: suspend () -> T): T = dbWriteMutex.withLock { block() }

    object PjmDispatchers {
        val IO = Executors.newFixedThreadPool(8).asCoroutineDispatcher()

        // 核心优化：Crypto 线程池设上限（避免 i9 32 线程全开，控制功耗与竞争）
        val Crypto =
            Executors
                .newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
                ).asCoroutineDispatcher()
        val Database = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        /**
         * 图片/媒体解码专用调度器：只 3 路并发。
         *
         * 解码是 CPU + 内存双重密集（一张大图解码后就是几十 MB 堆），和哈希/复制这类
         * 普通 IO 混在同一个 8 线程池里，会同时把 CPU 与堆打满（OOM 点），还会挤占
         * 删除/导入等用户可感知的操作。这里固定 3 路，语义等同原 VaultScanner 里
         * 手写的 Semaphore(3)，但不再占用 IO 池线程。
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val Decode = Dispatchers.Default.limitedParallelism(3)
    }

    const val ADAPTIVE_BUFFER_SIZE = 1024 * 1024
    val MAX_PARALLEL_TASKS = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2

    private val byteArrayPool = Collections.synchronizedList(LinkedList<ByteArray>())

    fun acquireBuffer(): ByteArray =
        synchronized(byteArrayPool) {
            if (byteArrayPool.isEmpty()) ByteArray(ADAPTIVE_BUFFER_SIZE) else byteArrayPool.removeAt(0)
        }

    /**
     * 归还 1MB 缓冲。
     *
     * 旧实现每次归还都整块清零：digest / copy / hash 这些高频路径上，
     * 每块 1MB 的 Arrays.fill 是纯浪费（GB 级复制等于多写几 GB 内存带宽）。
     * 只有容器/密码相关的缓冲才需要擦除，调用方按需传 [secure] = true。
     */
    fun releaseBuffer(
        buffer: ByteArray,
        secure: Boolean = false,
    ) {
        if (secure) Arrays.fill(buffer, 0.toByte())
        synchronized(byteArrayPool) { if (byteArrayPool.size < 16) byteArrayPool.add(buffer) }
    }

    // 资产分类常量：委托 VaultCategories（保持门面兼容，值统一在 VaultCategories 维护）
    const val CAT_PJM = VaultCategories.CAT_PJM
    const val CAT_BILI_VIDEOS = VaultCategories.CAT_BILI_VIDEOS
    const val CAT_IMAGES = VaultCategories.CAT_IMAGES
    const val CAT_VIDEOS = VaultCategories.CAT_VIDEOS
    const val CAT_AUDIOS = VaultCategories.CAT_AUDIOS
    const val CAT_TEXTS = VaultCategories.CAT_TEXTS
    const val CAT_OTHERS = VaultCategories.CAT_OTHERS
    val CATEGORIES = VaultCategories.CATEGORIES

    // ===== 多任务并发进度 + 全局信号：委托 VaultTasks（门面转发，外部调用点不变） =====
    const val TASK_DEFAULT = VaultTasks.TASK_DEFAULT
    const val TASK_DELETE = VaultTasks.TASK_DELETE
    const val TASK_DUPLICATES = VaultTasks.TASK_DUPLICATES
    const val TASK_SYNC = VaultTasks.TASK_SYNC
    const val TASK_STORE = VaultTasks.TASK_STORE
    const val TASK_ENCRYPT = VaultTasks.TASK_ENCRYPT
    const val TASK_EXTRACT = VaultTasks.TASK_EXTRACT
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
        isIndeterminate: Boolean = false,
        stages: List<Float>? = null,
    ) = VaultTasks.updateProgress(progress, message, taskId, isActive, isError, isIndeterminate, stages)

    fun clearProgress(taskId: String = "default") = VaultTasks.clearProgress(taskId)

    fun triggerRefresh() = VaultTasks.triggerRefresh()

    fun notifyCacheCleared() = VaultTasks.notifyCacheCleared()

    fun notifyResult(result: OperationResult) = VaultTasks.notifyResult(result)

    fun ensureDiskSpace(
        context: Context,
        requiredSize: Long = 0,
    ) {
        if (context.filesDir.usableSpace < (requiredSize + 100 * 1024 * 1024L)) {
            throw IOException(context.getString(R.string.error_insufficient_storage_extract))
        }
    }

    suspend fun digestFileToEntity(
        context: Context,
        fileName: String,
        inputStream: InputStream,
        expectedSize: Long = 0L,
        overrideCategory: String? = null,
    ): PjmResult<FileEntity> =
        withContext(PjmDispatchers.IO) {
            val category = overrideCategory ?: FileUtils.getCategory(fileName)
            // 命名和入库时间用同一个时间戳：图片物理名/显示名都按它来
            val importedAt = System.currentTimeMillis()
            val targetFile = getNextVaultPath(context, category, fileName, importedAt)
            val tempFile = File(context.cacheDir, "digest_${UUID.randomUUID()}.tmp")
            // 已知大小时先查空间：原实现只在加密/解压路径检查，入库直接写盘，
            // 分享进来的超大文件（或恶意 .pjm 条目）可以把 filesDir 写满。
            if (expectedSize > 0) ensureDiskSpace(context, expectedSize)
            try {
                FileOutputStream(tempFile).use { fos ->
                    if (expectedSize > 0) {
                        try {
                            Os.posix_fallocate(fos.fd, 0, expectedSize)
                        } catch (_: Exception) {
                        }
                    }
                    if (inputStream is FileInputStream) {
                        // 核心修复：FileChannel.transferTo 不保证一次传完（可能只传一部分就返回）。
                        // 旧实现只调一次且忽略返回值，随后按 fos.channel.position() 截断，
                        // 等于把「短传后的长度」当成真实内容固化 —— GB 级合并视频会静默损坏。
                        // 循环传输并强校验：任何短传都抛异常走失败回滚，绝不产出残缺文件。
                        val srcChannel = inputStream.channel
                        val srcSize = srcChannel.size()
                        var pos = 0L
                        while (pos < srcSize) {
                            val n = srcChannel.transferTo(pos, srcSize - pos, fos.channel)
                            if (n <= 0) throw IOException("transferTo stalled at " + pos + " / " + srcSize)
                            pos += n
                        }
                        if (pos != srcSize) throw IOException("transferTo short write: " + pos + " / " + srcSize)
                    } else {
                        val buffer = acquireBuffer()
                        try {
                            var n: Int
                            var writtenTotal = 0L
                            var nextDiskCheck = 16L * 1024 * 1024
                            while (inputStream.read(buffer).also { n = it } != -1) {
                                fos.write(buffer, 0, n)
                                writtenTotal += n
                                // 长度未知的流（ZIP entry / 分享 URI）：边写边看剩余空间，
                                // 避免恶意或异常数据把 filesDir 写满导致系统级故障。
                                if (writtenTotal >= nextDiskCheck) {
                                    nextDiskCheck += 16L * 1024 * 1024
                                    if (context.filesDir.usableSpace < 64L * 1024 * 1024) {
                                        throw IOException("Not enough storage while ingesting " + fileName)
                                    }
                                }
                            }
                        } finally {
                            releaseBuffer(buffer)
                        }
                    }
                    // 核心修复：posix_fallocate 会先把文件长度撑到 expectedSize。
                    // 若实际写入的字节更少（源流偏短，或 MediaStore 报的 SIZE 已过期），
                    // 尾部会留下一大段 0，且入库 size 也按被撑大的长度记录 ——
                    // 静默产出一个「长度对得上、内容尾部全零」的损坏文件。
                    // 这里按实际写入量截断，保证 length() 与实际内容一致。
                    val written = fos.channel.position()
                    if (expectedSize > 0 && written < expectedSize) {
                        fos.channel.truncate(written)
                    }
                    fos.channel.force(true)
                }
                if (!tempFile.renameTo(targetFile)) {
                    // 核心修复：`targetFile.outputStream()` 此前是内联创建、从不关闭 ——
                    // 每次走到这条 rename 回退路径都会泄漏一个 FileOutputStream（fd 耗尽后
                    // 整个入库流程崩溃）。改为 use{} 包裹并显式 flush + fsync。
                    FileOutputStream(targetFile).use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out) }
                        out.flush()
                        out.fd.sync()
                    }
                    tempFile.delete()
                }

                // 注意：这里**不要**再调用 MediaScannerConnection.scanFile。
                // 保险库位于应用私有目录，媒体库不可能收录它（每次回调均为 uri=null），
                // 每入库一个文件就白白多一次跨进程扫描 + 一条日志。

                PjmResult.Success(
                    FileEntity(
                        relativePath = getRelativePath(context, targetFile),
                        name = fileName,
                        size = targetFile.length(),
                        category = category,
                        lastModified = importedAt,
                        isImage = FileUtils.isImageFile(fileName),
                        extension = FileUtils.getFileExtension(fileName),
                        contentHash = null,
                        importedAt = importedAt,
                    ),
                )
            } catch (e: Exception) {
                tempFile.delete()
                // 核心修复：rename 回退复制中途失败时，半截 targetFile 会留在保险库，
                // 下次全量同步又会把它当正常文件索引（幽灵条目、占空间、可被分享）。
                runCatching { if (targetFile.exists()) shredFile(targetFile) }
                PjmResult.Failure("Ingest failed", exception = e)
            } finally {
                // 核心修复：本函数消费完输入流后必须关闭它。
                // BiliSettingsController 的两处调用（contentResolver 流、merge 临时文件的
                // inputStream）原来都不关闭 —— 批量导入上百个视频后 fd 耗尽（EMFILE）。
                // 调用方即使再 close 一次也无害（InputStream.close 约定幂等）。
                runCatching { inputStream.close() }
            }
        }

    fun getCategoryDir(
        context: Context,
        category: String,
    ): File = VaultPaths.getCategoryDir(context, category)

    fun getFileFromEntity(
        context: Context,
        entity: FileEntity,
    ): File = VaultPaths.getFileFromEntity(context, entity)

    fun getRelativePath(
        context: Context,
        file: File,
    ): String = VaultPaths.getRelativePath(context, file)

    /** 是否为容器写入过程中的临时文件（不应被索引、不应参与迁移）。 */
    internal fun isTransientFile(file: File): Boolean = file.name.contains(CryptoUtils.TEMP_FILE_MARKER)

    /**
     * 冷启动清理的时间窗：只删 1 小时前的残留。
     *
     * 只按文件名判断会与「正在进行的导出」并发：导出线程刚建好 .tmp_ 容器还没写完，
     * 冷启动清理就把它删掉（导出随后失败，且没有任何提示）。真正的崩溃残留必然早于 1 小时。
     */
    private fun isStaleTemp(file: File): Boolean = System.currentTimeMillis() - file.lastModified() > STALE_TEMP_MIN_AGE_MS

    /**
     * 冷启动清理：删除崩溃/被杀进程残留的容器临时文件。
     *
     * 这些文件正常情况下在 rename 成功或 catch 分支里就被删了；只有进程异常终止才会留下。
     * 它们占空间、会被误索引，且内容多半是不完整容器，没有任何保留价值。
     *
     * @return 实际清理的数量
     */
    suspend fun cleanupStaleTempFiles(context: Context): Int =
        withContext(PjmDispatchers.IO) {
            var removed = 0
            CATEGORIES.forEach { cat ->
                getCategoryDir(context, cat).listFiles()?.forEach { f ->
                    if (f.isFile && isTransientFile(f) && isStaleTemp(f) && shredFile(f)) removed++
                }
            }
            // 特权文件服务/Shizuku 会把解密后的音视频复制到 getExternalFilesDir/tmp 供 shell 读取。
            // 这些副本不属于保险库、也没有任何清理路径，会长期留在外部私有目录。
            // 同样加时间窗：可能正被某个进行中的播放/导出任务读取，太新的不动。
            context.getExternalFilesDir(null)?.let { ext ->
                File(ext, "tmp").listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.startsWith("embedded_") || f.name.startsWith("shizuku_")) && isStaleTemp(f)) {
                        if (shredFile(f)) removed++
                    }
                }
            }
            if (removed > 0) PjmLogger.i(TAG, "已清理 $removed 个残留临时文件")
            removed
        }

    fun getNextVaultPath(
        context: Context,
        category: String,
        originalName: String,
        importedAt: Long = System.currentTimeMillis(),
    ): File = VaultPaths.getNextVaultPath(context, category, originalName, importedAt)

    suspend fun extractPjmToVault(
        context: Context,
        entity: FileEntity,
        fileDao: FileDao,
    ) = withContext(PjmDispatchers.IO) {
        val file = getFileFromEntity(context, entity)
        // 核心修复：提取过程必须有可见反馈（上方横幅 GlobalProgressOverlay），
        // 否则用户点击"立即提取"后静默执行，成功/失败都无法感知。
        updateProgress(0.1f, context.getString(R.string.status_extracting, entity.name), taskId = TASK_EXTRACT)
        var count = 0
        var failed = false
        try {
            CryptoUtils.decryptPjmToEntries(context, listOf(Uri.fromFile(file))) { name, input ->
                digestFileToEntity(context, name, input).onSuccess {
                    fileDao.upsertLocked(it)
                    count++
                    VaultManager.updateProgress(
                        0.1f + 0.8f * (count / 100f).coerceAtMost(1f),
                        context.getString(R.string.status_extracting, entity.name),
                        taskId = TASK_EXTRACT,
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

    suspend fun deleteFile(
        context: Context,
        relativePath: String,
        fileDao: FileDao,
    ) = withContext(PjmDispatchers.IO) {
        mutex.withLock {
            // 核心修复：顺序改为「先删物理文件 → 成功后再删缓存与索引」。
            // 旧实现无论物理删除是否成功都会删掉数据库行，删除失败时明文文件会永久
            // 残留在 pjm_vault 中且不再被任何界面/扫描看到。
            val target = File(File(context.filesDir, VAULT_ROOT), relativePath)
            if (!shredFile(target)) {
                notifyDeleteFailure(context, 1)
                return@withLock
            }
            try {
                fileDao.findByRelativePath(relativePath)?.let {
                    ThumbnailCache.delete(context, it)
                    ImageFingerprintCache.delete(context, it)
                }
            } catch (e: Exception) {
                // 缓存删不掉不影响文件已经删掉这件事，但必须留痕，否则只能看到空间对不上
                PjmLogger.w(TAG, "清理缩略图/指纹失败: $relativePath", e)
            }
            // 核心修复：DB 变更必须与写入路径共用同一把锁。旧实现只拿 mutex，而导入/打包/
            // 查重回写走 dbWriteMutex —— 两者互不排斥，查重那批「删除前算出来的 upsert」
            // 会把刚删掉的行重新插回去，产生「有行、无文件」的幽灵条目。
            withDbWriteLock { fileDao.deleteByRelativePath(relativePath) }
            triggerRefresh()
        }
    }

    suspend fun deleteFiles(
        context: Context,
        relativePaths: List<String>,
        fileDao: FileDao,
    ) = withContext(PjmDispatchers.IO) {
        if (relativePaths.isEmpty()) return@withContext
        mutex.withLock {
            val root = File(context.filesDir, VAULT_ROOT)
            val failedPaths = mutableListOf<String>()
            relativePaths.forEach { relPath ->
                // 核心修复：同 deleteFile —— 物理删除失败的文件保留索引与缓存，
                // 避免「界面已删除、明文仍在磁盘」的隐私漏洞与空间泄漏。
                if (!shredFile(File(root, relPath))) {
                    PjmLogger.e(TAG, "物理删除失败，保留索引: $relPath")
                    failedPaths.add(relPath)
                    return@forEach
                }
                try {
                    fileDao.findByRelativePath(relPath)?.let {
                        ThumbnailCache.delete(context, it)
                        ImageFingerprintCache.delete(context, it)
                    }
                } catch (e: Exception) {
                    PjmLogger.w(TAG, "清理缩略图/指纹失败: $relPath", e)
                }
            }
            val deletedPaths = relativePaths - failedPaths.toSet()
            if (deletedPaths.isNotEmpty()) withDbWriteLock { fileDao.deleteByRelativePaths(deletedPaths) }
            if (failedPaths.isNotEmpty()) notifyDeleteFailure(context, failedPaths.size)
            triggerRefresh()
        }
    }

    /**
     * 物理删除失败时的用户可见反馈。
     *
     * 不能抛异常：调用方（FileViewerViewModel / SettingsViewModel）都在 viewModelScope 里
     * 直接 await，抛出去会变成未捕获异常直接崩溃；也不能静默 —— 否则用户以为文件已删除，
     * 实际明文仍在磁盘上。这里用 Toast 在主线报告知，数据库行保持不动，用户可重试。
     */
    private fun notifyDeleteFailure(
        context: Context,
        failedCount: Int,
    ) {
        PjmLogger.e(TAG, "物理删除失败 $failedCount 个，已保留索引以便重试")
        android.os
            .Handler(android.os.Looper.getMainLooper())
            .post {
                android.widget.Toast
                    .makeText(
                        context.applicationContext,
                        context.getString(R.string.toast_delete_failed, failedCount),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
            }
    }

    /**
     * 删除文件。
     * 核心修复：直接物理删除（快速）—— 之前固定 7 次 SecureRandom 覆写 + 每次 fd.sync，
     * GB 级大文件删除耗时以分钟计，进度条长时间卡在中间，体验极差。
     * 闪存介质上覆写也无法真正抹除数据，故统一为直接删除。
     *
     * @return 调用结束后文件是否确实已不存在。此前该函数吞掉 `File.delete()` 的返回值，
     *   调用方因此会在「物理删除失败」时依然删掉数据库行与缩略图 —— 明文文件永久残留在
     *   `pjm_vault` 目录中，用户以为已删除却再也找不到它，既违背隐私承诺也无法回收空间。
     */
    internal fun shredFile(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            file.delete() || !file.exists()
        } catch (_: Exception) {
            !file.exists()
        }
    }

    suspend fun fullSyncDatabase(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit = {},
    ) = withContext(PjmDispatchers.IO) {
        mutex.withLock {
            // 按分类收集文件：后续可以「逐分类取旧行 → 逐分类生成实体」，
            // 不再需要一次性把全库旧行（4 万行）materialize 到内存里。
            val filesByCategory = LinkedHashMap<String, MutableList<File>>()
            // 核心修复：跳过容器写入过程中残留的临时文件（`*.tmp_*`）。
            // 它们与正式容器同目录，进程被杀时不会被清理，而 getFileExtension 会把
            // `X.pjm.1.tmp_123` 判成 "pjm" —— 半成品容器就会被当成正式资产索引进文件柜。
            var listingFailed = false
            CATEGORIES.forEach { cat ->
                val files = getCategoryDir(context, cat).listFiles()
                if (files == null) {
                    // listFiles() 返回 null 表示目录不可读/IO 失败，绝不能当成"空目录"。
                    listingFailed = true
                    return@forEach
                }
                // 成功列举的空目录也要进 map：本分类会被替换成 0 行，清掉库里已不存在文件的
                // 残留索引；而列举失败、未进入 map 的分类完全不动。
                val categoryFiles = filesByCategory.getOrPut(cat) { mutableListOf() }
                files.filter { it.isFile && !isTransientFile(it) }.forEach { f -> categoryFiles.add(f) }
            }
            val totalCount = filesByCategory.values.sumOf { it.size }

            // 核心修复：重建索引时**保留未变化文件的 contentHash**。
            //
            // 旧实现一律写 null，而全量同步是「先清表再插入」—— 于是每次点「修复/找回文件」
            // 都把全部 MD5 / 视频感知指纹抹掉，下次查重要把整个库（可能几十 GB）重新读一遍。
            // 用户为了清掉一两条幽灵记录点一次修复，代价是十几分钟的重算，非常不合理。
            //
            // 匹配规则：relativePath 相同**且** size、lastModified 都相同，才认为文件未变、
            // 指纹依然有效。任一项不同就不沿用 —— 宁可重算，也绝不误用过期指纹去判重。
            // 核心修复：目录列举失败时绝不清空索引。
            // 「先清表再插入」时，entities 为空会把 name / importedAt / contentHash 全部抹掉；
            // 文件还在磁盘上，但原始文件名与入库时间永久丢失。
            // 旧实现为此把整库旧行读进内存；现在只需一个 COUNT 就知道旧索引是否非空。
            if (listingFailed && fileDao.getTotalFileCount() > 0L) {
                PjmLogger.w(TAG, "fullSyncDatabase aborted: category listing failed, keep existing rows")
                return@withLock
            }
            // 逐分类构造实体，再统一在写锁内按分类替换：每个分类各自一个事务，
            // 未参与本次同步的分类一行都不动（replaceAll 的全局清表不再使用）。
            val entitiesByCategory = LinkedHashMap<String, List<FileEntity>>()
            var done = 0
            filesByCategory.forEach { (category, files) ->
                // 旧行 map 按分类分批构建、用完即弃：峰值从「全库旧行 + 全库新行」
                // 降到「单分类旧行 + 全库新行」，4 万行时省下的是成百 MB 的实体拷贝。
                val previous = fileDao.getFilesByCategoryOnce(category).associateBy { it.relativePath }
                val categoryEntities = ArrayList<FileEntity>(files.size)
                files.forEach { f ->
                    // totalCount 为 0（所有分类都空）时不做除法，避免 NaN 进度
                    if (totalCount > 0) onProgress(done.toFloat() / totalCount)
                    done++
                    val relativePath = getRelativePath(context, f)
                    val size = f.length()
                    val lastModified = f.lastModified()
                    val old = previous[relativePath]
                    val carriedHash =
                        old?.contentHash?.takeIf {
                            old.size == size && old.lastModified == lastModified
                        }
                    categoryEntities.add(
                        FileEntity(
                            relativePath = relativePath,
                            // 核心修复：重建索引绝不能把 name 改回磁盘文件名。
                            // 命名规范里磁盘名是 UUID / PJM_时间，而 name 存的是"导入时的原名"，
                            // 之前这里写 f.name，一次「修复/找回文件」就把全库原名清成 UUID，
                            // 库里的文件从此全变成看不出是什么的乱码名。
                            name = old?.name?.takeIf { it.isNotBlank() } ?: f.name,
                            size = size,
                            category = category,
                            lastModified = lastModified,
                            isImage = FileUtils.isImageFile(f.name),
                            extension = FileUtils.getFileExtension(f.name),
                            contentHash = carriedHash,
                            // 入库时间同理：丢了就只能退回文件时间，图片的 PJM_时间 命名会跟着漂
                            importedAt = old?.importedAt ?: 0L,
                        ),
                    )
                }
                entitiesByCategory[category] = categoryEntities
            }
            withDbWriteLock {
                entitiesByCategory.forEach { (category, entities) -> fileDao.replaceCategory(category, entities) }
            }
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
    suspend fun migrateLegacyPjmNaming(
        context: Context,
        fileDao: FileDao,
    ): Int =
        withContext(PjmDispatchers.IO) {
            mutex.withLock {
                val root = File(context.filesDir, VAULT_ROOT)
                // 收集所有含 .pjm 的文件（容器 X.pjm / X.pjm.N）；非容器由 legacyToCanonicalName 原样返回自然跳过
                val containers =
                    runCatching {
                        root.walkTopDown().filter { it.isFile && it.name.contains(".pjm") && !isTransientFile(it) }.toList()
                    }.getOrDefault(emptyList())
                if (containers.isEmpty()) return@withLock 0

                var count = 0
                var failed = 0
                containers.forEach { file ->
                    val dir =
                        file.parentFile ?: run {
                            failed++
                            return@forEach
                        }
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
                    // 同步数据库索引：单事务内把 oldRel 更新为 newPath（其余字段保持不变）。
                    // 关键：若索引更新失败，必须把文件改回原名回滚，保证磁盘与数据库一致，
                    // 否则下次重试会因文件已是新名而跳过，数据库将永远指向旧路径（UI 读不到文件）。
                    val dbOk =
                        runCatching {
                            fileDao.renameRelativePath(oldRel, getRelativePath(context, target), target.name)
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
    suspend fun checkIntegrity(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): Map<String, List<FileEntity>> = VaultScanner.checkIntegrity(context, fileDao, onProgress)

    suspend fun findDuplicateFiles(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): List<DuplicateGroup> = VaultScanner.findDuplicateFiles(context, fileDao, onProgress)

    /**
     * 图片感知查重：找出【内容相同但分辨率不同】的图片（原图 vs 聊天软件缩略图等）。
     * 实现已迁移至 [VaultScanner.findSimilarImages]。
     */
    suspend fun findSimilarImages(
        context: Context,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): List<DuplicateGroup> = VaultScanner.findSimilarImages(context, fileDao, onProgress)

    suspend fun packUrisWithSplitting(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit,
    ): Result<Int> = VaultPackager.packUrisWithSplitting(context, uris, category, baseName, fileDao, onProgress)

    suspend fun packUrisSingle(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit = {},
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
        } finally {
            releaseBuffer(buf)
        }
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
    val isIndeterminate: Boolean = false, // 不确定进度（如扫描遍历），UI 显示无限循环条而非百分比
    // 阶段边界（累计百分比，升序，最后一项为 1f）。非空时 UI 把一条进度条切成若干彩色分段：
    // 已完成的分段各自着色，用户一眼看出「现在处在哪个阶段、还剩哪些阶段」。
    // 空列表 = 普通单色进度条（大多数任务）。
    val stages: List<Float> = emptyList(),
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
    val recommendedDelete: Set<String>,
)

/**
 * 带全局写锁的 upsert 扩展（核心修复）：全量同步的按分类替换与导入/打包的写入串行，
 * 避免「删分类 → 插入」窗口把刚入库的行删掉。
 */
internal suspend fun FileDao.upsertLocked(entity: FileEntity) = VaultManager.withDbWriteLock { upsert(entity) }

internal suspend fun FileDao.upsertAllLocked(entities: List<FileEntity>) = VaultManager.withDbWriteLock { upsertAll(entities) }
