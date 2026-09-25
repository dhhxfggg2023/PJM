package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
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
                                if (!processedUris.add(uri)) return@withPermit
                                val name = FileUtils.getFileName(context, uri)
                                val nameIsPjm = (name.contains(".pjm.") || name.endsWith(".pjm"))
                                // 核心修复：分享器（微信/QQ 等）可能改名/丢失后缀，仅靠文件名判断会漏判，
                                // 导致加密容器被当成普通文件入库（无扩展名 → 存进 other/others 分类）。
                                // 对名字不像 pjm 的 URI 再做内容级魔数检测兜底，命中即按 pjm 解密。
                                val isPjm = nameIsPjm || CryptoUtils.isPjmUri(context, uri)
                                // 需求变更：导入时【不再自动过滤】库中已存在的重复文件，
                                // 所有文件一律正常入库（UUID 文件名，互不冲突）。
                                // 去重仅由用户点击"清除重复内容"按钮时手动触发。
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
                                                collectedEntities,
                                                fileDao,
                                                onStatus,
                                                0,
                                                strictPjm = true,
                                            )
                                        }
                                    } else {
                                        onStatus(context.getString(R.string.status_ingesting_file, name))
                                        var handled = false
                                        if (FileUtils.isArchiveFile(name) && isAutoExtractEnabled) {
                                            // 核心修复：本次压缩包解出的条目先收集到【局部列表】，
                                            // 只有整体成功才并入 collectedEntities。
                                            // 旧实现直接把半成品写进 collectedEntities：解压中途失败时
                                            // 已解出的条目照样入库，且原始压缩包又被存了一份 —— 既重复又是残缺内容。
                                            val localEntities = mutableListOf<FileEntity>()
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
                                                            localEntities,
                                                            fileDao,
                                                            onStatus,
                                                            1,
                                                        )
                                                    }
                                                when (outcome) {
                                                    SevenZipUtils.ExtractOutcome.EXTRACTED -> {
                                                        collectedEntities.addAll(localEntities)
                                                        handled = true
                                                    }
                                                    SevenZipUtils.ExtractOutcome.NOT_AN_ARCHIVE -> {
                                                        // 名字像压缩包但内容不是：丢弃局部产物，按普通文件入库
                                                        shredEntities(context, localEntities)
                                                    }
                                                    SevenZipUtils.ExtractOutcome.FAILED -> {
                                                        // 解压真失败：回滚半成品，把原始压缩包作为普通文件保住，
                                                        // 并计入失败数 —— 用户必须知道它没有被展开。
                                                        shredEntities(context, localEntities)
                                                        PjmLogger.e(TAG, "压缩包解压失败，已按原文件入库: $name")
                                                        onStatus(context.getString(R.string.status_archive_failed, name))
                                                        failedCount.incrementAndGet()
                                                    }
                                                }
                                            } catch (e: SevenZipUtils.EncryptedArchiveException) {
                                                shredEntities(context, localEntities)
                                                VaultManager.notifyResult(OperationResult.PasswordRequired(e.fileName, listOf(uri)))
                                                return@withPermit
                                            } catch (e: Exception) {
                                                shredEntities(context, localEntities)
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
                                                    collectedEntities,
                                                    fileDao,
                                                    onStatus,
                                                    0,
                                                )
                                            }
                                        }
                                        deletableOriginals.add(uri)
                                    }
                                } catch (e: Exception) {
                                    if (e !is CancellationException) {
                                        PjmLogger.e(TAG, "Processing fail: $name", e)
                                        failedCount.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                }
                if (collectedEntities.isNotEmpty()) {
                    onStatus(context.getString(R.string.status_updating_index))
                    fileDao.upsertAll(collectedEntities)
                    // 只有 upsertAll 真正成功，才把这些文件算作「已入库」
                    importedCount = collectedEntities.size
                }
            } catch (e: CancellationException) {
                // 核心修复：取消必须向上传播，并把「已落盘但未入库」的文件清理掉。
                // 旧实现把 CancellationException 当普通异常吞掉（既不清理也不 rethrow），
                // finally 仍上报「全部完成」+ 100%，返回的 imported 却是【根本没入库】的数量 ——
                // 结果是磁盘上留下用户看不见、也删不掉的孤儿明文文件，界面却显示成功。
                cancelled = true
                shredEntities(context, collectedEntities)
                throw e
            } catch (e: Exception) {
                PjmLogger.e(TAG, "Storage fail, cleaning up...", e)
                shredEntities(context, collectedEntities)
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
        depth: Int,
        strictPjm: Boolean = false,
    ) {
        if (depth > MAX_RECURSION_DEPTH) return
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
            if (pjmStream != null) {
                try {
                    extractPjmStream(context, pjmStream, collectedEntities, fileDao, onStatus, depth + 1)
                    handled = true
                } catch (e: Exception) {
                    if (strictPjm) throw IOException("PJM container corrupted: $name", e)
                    try {
                        bis.reset()
                    } catch (_: Exception) {
                    }
                }
            } else {
                // 不是 PJM 容器（或长度不足）：回退到流的起点，按普通文件处理
                try {
                    bis.reset()
                } catch (_: Exception) {
                }
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
        depth: Int,
    ) {
        ZipArchiveInputStream(inputStream, "UTF-8", true, true).use { zais ->
            var ze = zais.nextEntry
            while (ze != null) {
                coroutineContext.ensureActive()
                if (!ze.isDirectory) {
                    val wrapper =
                        object : FilterInputStream(zais) {
                            override fun close() {}
                        }
                    processRecursiveStream(context, ze.name.substringAfterLast('/'), wrapper, collectedEntities, fileDao, onStatus, depth)
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
