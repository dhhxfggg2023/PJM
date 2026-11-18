package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.TakeoverJobDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.TakeoverJobEntity
import com.dhhxfggg.pjm.data.model.TakeoverStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.*
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

object IngestionEngine {
    /**
     * PJM 容器的文件名形态：`X.pjm` 或分卷 `X.pjm.N`（N 为数字）。
     * 刻意收窄——只在「.pjm.」后面跟数字时才认，避免误伤 a.pjm.zip 这类普通文件。
     */
    private val PJM_NAME_PATTERN = Regex(""".*\.pjm(\.\d+)?$""", RegexOption.IGNORE_CASE)

    private const val TAG = "IngestionEngine"

    /** 入库任务进度 id */
    private const val TASK_STORE = "store"
    private val IO_BUFFER_SIZE = VaultManager.ADAPTIVE_BUFFER_SIZE

    /**
     * 标记载入上限：`openPjmContent` 最多预读 5 字节（magic + 版本字节），
     * 这里留出充裕余量以便失败时 `reset()` 回到流起点。
     */
    private const val MARK_LIMIT = 2048

    private const val MAX_RECURSION_DEPTH = 10

    private val jobSemaphore = Semaphore(VaultManager.MAX_PARALLEL_TASKS)

    suspend fun store(
        context: Context,
        uris: List<Uri>,
        password: String? = null,
        fileDao: FileDao,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onUnsupported: (Uri, String) -> Unit = { _, _ -> },
        /** 分享入库时传入，与接管服务共用去重账本；接管路径自己已登记，不传 */
        takeoverJobDao: TakeoverJobDao? = null,
    ): IngestionSummary =
        withContext(VaultManager.PjmDispatchers.IO) {
            val totalBytes = uris.sumOf { FileUtils.getFileSize(context, it) }.coerceAtLeast(1L)
            val globalProcessedBytes = AtomicLong(0L)
            val failedCount = AtomicInteger(0)

            val settings = SettingsManager.getSettingsFlow(context).first()
            val isAutoExtractEnabled = settings.isArchiveAutoExtractionEnabled

            // 顶部横幅进度（独立任务 id，可与其他任务并行）
            VaultManager.updateProgress(0.02f, context.getString(R.string.status_storing), taskId = TASK_STORE)

            var lastReportedProgress = -1f
            var lastReportTime = 0L

            fun reportProgress(processedInThisStep: Long) {
                if (processedInThisStep <= 0) return
                val totalProcessed = globalProcessedBytes.addAndGet(processedInThisStep)
                val currentProgress = (totalProcessed.toFloat() / totalBytes).coerceIn(0f, 1f)
                val currentTime = System.currentTimeMillis()
                if ((currentProgress - lastReportedProgress >= 0.01f) || (currentTime - lastReportTime > 500)) {
                    onProgress(currentProgress)
                    // 同步到顶部横幅
                    VaultManager.updateProgress(currentProgress, context.getString(R.string.status_storing), taskId = TASK_STORE)
                    lastReportedProgress = currentProgress
                    lastReportTime = currentTime
                }
            }

            val collectedEntities = java.util.Collections.synchronizedList(mutableListOf<FileEntity>())
            // 只记录“以普通文件入库成功”的源 uri（pjm 容器解密入库不记录，避免其进入“删除原件”询问）
            val deletableOriginals = Collections.synchronizedList(mutableListOf<Uri>())
            val passwordChars = password?.toCharArray()

            // 真正写入数据库的条数（与 collectedEntities.size 可能不同：取消/失败时不会被写入）
            var importedCount = 0
            var cancelled = false

            try {
                coroutineScope {
                    // 核心修复：用并发 Set 的 add() 返回值做原子占位。
                    // 原来是「先 contains 判断、结尾再 add」的先查后加 —— 输入列表里若有
                    // 重复 URI（分享器重复投递、多选去重失效），两个协程会同时通过检查，
                    // 同一个文件被导入两次（UUID 文件名不同，两份副本都留在库里）。
                    val processedUris = ConcurrentHashMap.newKeySet<Uri>()

                    uris.forEach { uri ->
                        launch {
                            jobSemaphore.withPermit {
                                if (VaultManager.isTaskCancelled(TASK_STORE)) {
                                    throw CancellationException("入库已取消")
                                }
                                if (!processedUris.add(uri)) return@withPermit
                                // 与接管服务共用去重账本：同一文件若已被接管路径入库，
                                // 这里直接跳过（不算失败），避免「源文件已被删→导入失败」的误报。
                                val dao = takeoverJobDao
                                val jobKey = dao?.let { FileUtils.takeoverKey(context, uri) }
                                if (dao != null && jobKey != null) {
                                    val existing = dao.findByAnyUri(listOf(jobKey, uri.toString()))
                                    if (existing != null) {
                                        PjmLogger.i(TAG, "已由接管服务处理，分享导入跳过: $uri")
                                        return@withPermit // 不增加 failedCount，也不登记为可删
                                    }
                                    // 占位：接管服务随后看到 PENDING 会跳过，避免它重复处理
                                    dao.upsert(TakeoverJobEntity(uri = jobKey, status = TakeoverStatus.PENDING))
                                }
                                val name = FileUtils.getFileName(context, uri)
                                // 只认「.pjm 结尾」或「.pjm.<数字>」分卷：旧写法 name.contains(".pjm.")
                                // 会把 a.pjm.zip / x.pjm.mp4 这类普通文件也当成容器，走 strict 分支后
                                // 解包失败直接抛错 —— 既没解密也没当普通文件入库，用户只看到「处理失败」。
                                val nameIsPjm = PJM_NAME_PATTERN.matches(name)
                                // 核心修复：分享器（微信/QQ 等）可能改名/丢失后缀，仅靠文件名判断会漏判，
                                // 导致加密容器被当成普通文件入库（无扩展名 → 存进 other/others 分类）。
                                // 对名字不像 pjm 的 URI 再做内容级魔数检测兜底，命中即按 pjm 解密。
                                val isPjm = nameIsPjm || CryptoUtils.isPjmUri(context, uri)
                                // 需求变更：导入时【不再自动过滤】库中已存在的重复文件，
                                // 所有文件一律正常入库（UUID 文件名，互不冲突）。
                                // 去重仅由用户点击"清除重复内容"按钮时手动触发。
                                // 核心修复：每个 URI 的产物先收进【本 URI 独占】的局部列表。
                                // 只有确认至少成功落盘一个实体，才并入全局 collectedEntities，并允许删除源文件。
                                // 旧实现无条件 add(uri)：流打不开 / 写盘失败时源文件被删而库里没有副本 —— 数据永久丢失。
                                val uriEntities = mutableListOf<FileEntity>()
                                try {
                                    if (isPjm) {
                                        // 新版格式：每个 .pjm.N 分卷都是独立完整的 PJM 容器（magic + 完整 ZIP，XOR 从 0 开始），
                                        // 单独解密入库即可，无需拼接；丢失其他分卷不影响本卷解密。
                                        // 核心修复：strictPjm=true —— 魔数命中但解压失败时直接报错，
                                        // 不再降级把加密原始数据当普通文件入库（否则会污染 other/others 分类）。
                                        onStatus(context.getString(R.string.status_decrypting, name))
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            val progressInput = ProgressInputStream(input) { reportProgress(it) }
                                            processRecursiveStream(
                                                context,
                                                name,
                                                progressInput,
                                                uriEntities,
                                                fileDao,
                                                onStatus,
                                                failedCount,
                                                0,
                                                strictPjm = true,
                                            )
                                        }
                                    } else {
                                        onStatus(context.getString(R.string.status_ingesting_file, name))
                                        var handled = false
                                        if (FileUtils.isArchiveFile(name) && isAutoExtractEnabled) {
                                            // 解出的条目只写进 uriEntities，整体成功才在末尾统一并入全局列表。
                                            // 解压中途失败时半成品会被下面的 shredEntities 回滚，不会污染数据库。
                                            try {
                                                val outcome =
                                                    SevenZipUtils.extractArchive(
                                                        context,
                                                        uri,
                                                        passwordChars,
                                                        onStatus,
                                                        { reportProgress(it) },
                                                    ) { entryName, inputStream ->
                                                        processRecursiveStream(
                                                            context,
                                                            entryName.substringAfterLast('/'),
                                                            inputStream,
                                                            uriEntities,
                                                            fileDao,
                                                            onStatus,
                                                            failedCount,
                                                            1,
                                                        )
                                                    }
                                                when (outcome) {
                                                    SevenZipUtils.ExtractOutcome.EXTRACTED -> {
                                                        handled = true
                                                    }
                                                    SevenZipUtils.ExtractOutcome.NOT_AN_ARCHIVE -> {
                                                        // 名字像压缩包但内容不是：丢弃局部产物，按普通文件入库
                                                        shredEntities(context, uriEntities)
                                                        uriEntities.clear()
                                                    }
                                                    SevenZipUtils.ExtractOutcome.FAILED -> {
                                                        // 解压真失败：回滚半成品，把原始压缩包作为普通文件保住，
                                                        // 并计入失败数 —— 用户必须知道它没有被展开。
                                                        shredEntities(context, uriEntities)
                                                        uriEntities.clear()
                                                        PjmLogger.e(TAG, "压缩包解压失败，已按原文件入库: $name")
                                                        onStatus(context.getString(R.string.status_archive_failed, name))
                                                        failedCount.incrementAndGet()
                                                    }
                                                }
                                            } catch (e: SevenZipUtils.EncryptedArchiveException) {
                                                shredEntities(context, uriEntities)
                                                uriEntities.clear()
                                                VaultManager.notifyResult(OperationResult.PasswordRequired(e.fileName, listOf(uri)))
                                                return@withPermit
                                            } catch (e: Exception) {
                                                shredEntities(context, uriEntities)
                                                uriEntities.clear()
                                                throw e
                                            }
                                        }
                                        if (!handled) {
                                            context.contentResolver.openInputStream(uri)?.use { input ->
                                                val progressInput = ProgressInputStream(input) { reportProgress(it) }
                                                processRecursiveStream(
                                                    context,
                                                    name,
                                                    progressInput,
                                                    uriEntities,
                                                    fileDao,
                                                    onStatus,
                                                    failedCount,
                                                    0,
                                                )
                                            }
                                        }
                                    }
                                    if (uriEntities.isNotEmpty()) {
                                        collectedEntities.addAll(uriEntities)
                                        if (!isPjm) deletableOriginals.add(uri)
                                        // 账本收尾：已入库（源文件删除由调用方按 autoDeleteOriginal 处理）
                                        if (dao != null && jobKey != null) {
                                            dao.updateStatus(jobKey, TakeoverStatus.IMPORTED)
                                        }
                                    } else {
                                        failedCount.incrementAndGet()
                                        // 入库未产出实体：撤销账本占位，让接管服务仍有机会处理
                                        if (dao != null && jobKey != null) dao.deleteByUri(jobKey)
                                        PjmLogger.w(TAG, "未产出任何实体，源 URI 不列入可删除: $name")
                                    }
                                } catch (e: Exception) {
                                    shredEntities(context, uriEntities)
                                    if (dao != null && jobKey != null) dao.deleteByUri(jobKey)
                                    if (e is CancellationException) throw e
                                    PjmLogger.e(TAG, "Processing fail: $name", e)
                                    failedCount.incrementAndGet()
                                }
                            }
                        }
                    }
                }
                if (collectedEntities.isNotEmpty()) {
                    onStatus(context.getString(R.string.status_updating_index))
                    fileDao.upsertAllLocked(collectedEntities)
                    // 只有 upsertAll 真正成功，才把这些文件算作「已入库」
                    importedCount = collectedEntities.size
                    // 默认把图片感知指纹算好存下（只计算、不比对，不触发任何查重）
                    FingerprintWarmer.warm(context, collectedEntities)
                }
            } catch (e: CancellationException) {
                // 核心修复：取消必须向上传播，并把「已落盘但未入库」的文件清理掉。
                // 旧实现把 CancellationException 当普通异常吞掉（既不清理也不 rethrow），
                // finally 仍上报「全部完成」+ 100%，返回的 imported 却是【根本没入库】的数量 ——
                // 结果是磁盘上留下用户看不见、也删不掉的孤儿明文文件，界面却显示成功。
                cancelled = true
                shredEntities(context, collectedEntities)
                // 库内副本会被物理删除，原件绝不能再交给调用方去删
                deletableOriginals.clear()
                throw e
            } catch (e: Exception) {
                PjmLogger.e(TAG, "Storage fail, cleaning up...", e)
                // 顶层失败必须回填失败计数：旧实现只打日志，finally 仍上报「全部完成」+
                // 100%，界面显示成功而实际一个都没入库。被回滚的实体也算失败，
                // 一路都没产出实体时至少记 1 次。成功路径不受影响。
                failedCount.addAndGet(collectedEntities.size.coerceAtLeast(1))
                shredEntities(context, collectedEntities)
                // 核心修复：上面已经把「已落盘」的副本全部 shred 掉了，但 deletableOriginals
                // 是在每个 URI 成功落盘时就登记的。若这里不清空，调用方会把返回的 deletableUris
                // 当成「库里有副本、原件可删」，于是删掉原件 —— 库内副本也已不存在，数据彻底丢失。
                deletableOriginals.clear()
            } finally {
                passwordChars?.let { java.util.Arrays.fill(it, '0') }
                if (!cancelled) {
                    onStatus(context.getString(R.string.status_all_tasks_complete))
                    onProgress(1f)
                    // 顶部横幅完成提示
                    VaultManager.updateProgress(1f, context.getString(R.string.status_all_tasks_complete), taskId = TASK_STORE)
                }
                VaultManager.triggerRefresh()
            }
            IngestionSummary(
                imported = importedCount,
                skipped = 0,
                failed = failedCount.get(),
                deletableUris = deletableOriginals.toList(),
            )
        }

    /**
     * 回滚一批「已写盘但尚未入库」的实体：物理删除其文件。
     *
     * 用于压缩包解压失败 / 不是有效压缩包时清理半成品，避免残缺内容进入保险库。
     */
    private fun shredEntities(
        context: Context,
        entities: List<FileEntity>,
    ) {
        entities.forEach { entity ->
            runCatching { VaultManager.shredFile(VaultManager.getFileFromEntity(context, entity)) }
        }
    }

    private suspend fun processRecursiveStream(
        context: Context,
        name: String,
        inputStream: InputStream,
        collectedEntities: MutableList<FileEntity>,
        fileDao: FileDao,
        onStatus: (String) -> Unit,
        failedCount: AtomicInteger,
        depth: Int,
        strictPjm: Boolean = false,
    ) {
        if (depth > MAX_RECURSION_DEPTH) {
            // 旧实现静默 return：嵌套过深的条目凭空消失，界面却仍显示「任务全部完成」。
            // 这里计一次失败并给出可见状态；父容器其余条目继续处理（不整包中止）。
            failedCount.incrementAndGet()
            PjmLogger.w(TAG, "递归深度超过上限（$MAX_RECURSION_DEPTH），跳过条目: $name")
            onStatus(context.getString(R.string.error_processing_failed, name))
            return
        }
        coroutineContext.ensureActive()
        val bis =
            if (inputStream is BufferedInputStream &&
                inputStream.markSupported()
            ) {
                inputStream
            } else {
                BufferedInputStream(inputStream, IO_BUFFER_SIZE)
            }
        var handled = false
        if (bis.markSupported()) {
            bis.mark(MARK_LIMIT)
            // 核心修复：容器头解析（魔数 + 版本字节对齐）统一交给 CryptoUtils.openPjmContent。
            // 原来这里手写「读 4 字节 XOR 后比对魔数，然后从位置 4 建流」——
            // 一旦容器格式加入版本字节，这里就会静默错位（把版本字节当成 ZIP 首字节）。
            val pjmStream =
                try {
                    CryptoUtils.openPjmContent(bis)
                } catch (_: Exception) {
                    null
                }
            // 非 markable 流不会进入上面的分支，也就没有读过字节，可直接按普通文件处理
            var resetOk = !bis.markSupported()
            if (pjmStream != null) {
                try {
                    extractPjmStream(context, pjmStream, collectedEntities, fileDao, onStatus, failedCount, depth + 1)
                    handled = true
                } catch (e: Exception) {
                    if (strictPjm) throw IOException("PJM container corrupted: $name", e)
                    // 核心修复：mark 只有 MARK_LIMIT 有效，而容器解包会读穿它 —— reset 必然抛异常。
                    // 旧实现把异常吞掉后继续 digestFileToEntity，等于从流中段开始写成「完整文件」，
                    // 产出的是能打开但内容损坏的垃圾。无法回退就按失败处理，绝不入库。
                    resetOk = runCatching { bis.reset() }.isSuccess
                    if (!resetOk) PjmLogger.w("IngestionEngine", "PJM 解包失败且无法回退流位置: $name", e)
                }
            } else {
                // 不是 PJM 容器（或长度不足）：回退到流的起点，按普通文件处理
                resetOk = runCatching { bis.reset() }.isSuccess
            }
            if (!handled && !resetOk) {
                throw IOException("Corrupted stream (cannot reset after container detection): $name")
            }
        }
        if (!handled) {
            if (strictPjm) throw IOException("Not a valid PJM container: $name")
            VaultManager.digestFileToEntity(context, name, bis).onSuccess { collectedEntities.add(it) }
        }
    }

    private suspend fun extractPjmStream(
        context: Context,
        inputStream: InputStream,
        collectedEntities: MutableList<FileEntity>,
        fileDao: FileDao,
        onStatus: (String) -> Unit,
        failedCount: AtomicInteger,
        depth: Int,
    ) {
        ZipArchiveInputStream(inputStream, "UTF-8", true, true).use { zais ->
            var ze = zais.nextEntry
            while (ze != null) {
                coroutineContext.ensureActive()
                if (!ze.isDirectory) {
                    // 容器条目声明了大小就按它检查空间：恶意/异常容器不会写出比声明更大的数据，
                    // 提前拒绝比写到一半 ENOSPC 更安全（损坏的半成品会被回滚，但磁盘可能已被占满）。
                    if (ze.size > 0) VaultManager.ensureDiskSpace(context, ze.size)
                    val wrapper =
                        object : FilterInputStream(zais) {
                            override fun close() {}
                        }
                    processRecursiveStream(
                        context,
                        ze.name.substringAfterLast('/'),
                        wrapper,
                        collectedEntities,
                        fileDao,
                        onStatus,
                        failedCount,
                        depth,
                    )
                }
                ze = zais.nextZipEntry
            }
        }
    }

    private class ProgressInputStream(
        val input: InputStream,
        val onBytesRead: (Long) -> Unit,
    ) : InputStream() {
        override fun read(): Int = input.read().also { if (it != -1) onBytesRead(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = input.read(b, off, len).also { if (it > 0) onBytesRead(it.toLong()) }

        override fun close() = input.close()

        override fun available(): Int = input.available()
    }
}
