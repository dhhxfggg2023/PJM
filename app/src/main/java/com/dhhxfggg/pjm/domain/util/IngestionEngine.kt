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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

object IngestionEngine {
    private const val TAG = "IngestionEngine"

    /** 入库任务进度 id */
    private const val TASK_STORE = "store"
    private val XOR_KEY = CryptoUtils.getXorKey()
    private val IO_BUFFER_SIZE = VaultManager.ADAPTIVE_BUFFER_SIZE

    // 动态 mask，与 CryptoUtils/native 的 keyLen-1 保持一致，避免魔数检测错位
    private val KEY_SIZE_MASK: Long get() = (XOR_KEY.size - 1).toLong()
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

            try {
                coroutineScope {
                    val processedUris = java.util.Collections.synchronizedSet(mutableSetOf<Uri>())

                    uris.forEach { uri ->
                        launch {
                            jobSemaphore.withPermit {
                                if (processedUris.contains(uri)) return@withPermit
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
                                        processedUris.add(uri)
                                    } else {
                                        onStatus(context.getString(R.string.status_ingesting_file, name))
                                        var handled = false
                                        if (FileUtils.isArchiveFile(name) && isAutoExtractEnabled) {
                                            try {
                                                handled =
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
                                                            collectedEntities,
                                                            fileDao,
                                                            onStatus,
                                                            1,
                                                        )
                                                    }
                                            } catch (e: SevenZipUtils.EncryptedArchiveException) {
                                                VaultManager.notifyResult(OperationResult.PasswordRequired(e.fileName, listOf(uri)))
                                                return@withPermit
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
                                        processedUris.add(uri)
                                        deletableOriginals.add(uri)
                                    }
                                } catch (e: Exception) {
                                    if (e !is CancellationException) {
                                        PjmLogger.e(TAG, "Processing fail: $name", e)
                                        failedCount.incrementAndGet()
                                        processedUris.add(uri)
                                    }
                                }
                            }
                        }
                    }
                }
                if (collectedEntities.isNotEmpty()) {
                    onStatus(context.getString(R.string.status_updating_index))
                    fileDao.upsertAll(collectedEntities)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    PjmLogger.e(TAG, "Storage fail, cleaning up...", e)
                    collectedEntities.forEach { VaultManager.shredFile(VaultManager.getFileFromEntity(context, it)) }
                }
            } finally {
                passwordChars?.let { java.util.Arrays.fill(it, '0') }
                onStatus(context.getString(R.string.status_all_tasks_complete))
                onProgress(1f)
                // 顶部横幅完成提示
                VaultManager.updateProgress(1f, context.getString(R.string.status_all_tasks_complete), taskId = TASK_STORE)
                VaultManager.triggerRefresh()
            }
            IngestionSummary(
                imported = collectedEntities.size,
                skipped = 0,
                failed = failedCount.get(),
                deletableUris = deletableOriginals.toList(),
            )
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
            bis.mark(2048)
            val header = ByteArray(4)
            val read =
                try {
                    bis.read(header)
                } catch (_: Exception) {
                    0
                }
            if (read >= 4) {
                val dec = ByteArray(4) { i -> (header[i].toInt() xor XOR_KEY[(i.toLong() and KEY_SIZE_MASK).toInt()].toInt()).toByte() }
                if (java.nio.ByteBuffer
                        .wrap(dec)
                        .int == CryptoUtils.FILE_MAGIC
                ) {
                    try {
                        extractPjmStream(context, CryptoUtils.createXorStream(bis, 4), collectedEntities, fileDao, onStatus, depth + 1)
                        handled = true
                    } catch (e: Exception) {
                        if (strictPjm) throw IOException("PJM container corrupted: $name", e)
                        try {
                            bis.reset()
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    try {
                        bis.reset()
                    } catch (_: Exception) {
                    }
                }
            } else if (read > 0) {
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
