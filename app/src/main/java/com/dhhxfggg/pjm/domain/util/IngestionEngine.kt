package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

object IngestionEngine {
    private const val TAG = "IngestionEngine"
    private val XOR_KEY = CryptoUtils.getXorKey()
    private val IO_BUFFER_SIZE get() = VaultManager.ADAPTIVE_BUFFER_SIZE
    private const val KEY_SIZE_MASK = 31L
    private const val MAX_RECURSION_DEPTH = 10 

    private val jobSemaphore = Semaphore(VaultManager.MAX_PARALLEL_TASKS)

    suspend fun store(
        context: Context, 
        uris: List<Uri>, 
        password: String? = null,
        fileDao: FileDao, 
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        onUnsupported: (Uri, String) -> Unit = { _, _ -> }
    ) = withContext(VaultManager.PjmDispatchers.IO) {
        val totalBytes = uris.sumOf { FileUtils.getFileSize(context, it) }.coerceAtLeast(1L)
        val globalProcessedBytes = AtomicLong(0L)
        val duplicateCount = AtomicInteger(0)
        
        // 动态获取解压设置
        val settings = SettingsManager.getSettingsFlow(context).first()
        val isAutoExtractEnabled = settings.isArchiveAutoExtractionEnabled

        var lastReportedProgress = -1f
        var lastReportTime = 0L

        fun reportProgress(processedInThisStep: Long) {
            if (processedInThisStep <= 0) return
            val totalProcessed = globalProcessedBytes.addAndGet(processedInThisStep)
            val currentProgress = (totalProcessed.toFloat() / totalBytes).coerceIn(0f, 1f)
            val currentTime = System.currentTimeMillis()
            
            if (currentProgress - lastReportedProgress >= 0.01f || currentTime - lastReportTime > 500) {
                onProgress(currentProgress)
                lastReportedProgress = currentProgress
                lastReportTime = currentTime
            }
        }

        val collectedEntities = java.util.Collections.synchronizedList(mutableListOf<FileEntity>())
        val passwordChars = password?.toCharArray()

        try {
            coroutineScope {
                val processedUris = java.util.Collections.synchronizedSet(mutableSetOf<Uri>())
                val splitGroups = uris.filter { FileUtils.getFileName(context, it).contains(".pjm.") }
                    .groupBy { it.toString().substringBeforeLast('.') }

                uris.forEach { uri ->
                    launch {
                        jobSemaphore.withPermit {
                            if (processedUris.contains(uri)) return@withPermit
                            
                            val name = FileUtils.getFileName(context, uri)
                            val size = FileUtils.getFileSize(context, uri)

                            // 智能查重：如果是顶层文件且已存在，则直接跳过
                            if (fileDao.isDuplicate(name, size)) {
                                duplicateCount.incrementAndGet()
                                reportProgress(size)
                                processedUris.add(uri)
                                return@withPermit
                            }
                            
                            try {
                                // 1. 处理分卷
                                if (name.contains(".pjm.1")) {
                                    val groupBase = uri.toString().substringBeforeLast('.')
                                    val groupUris = splitGroups[groupBase]?.sortedBy { 
                                        it.toString().substringAfterLast('.').toIntOrNull() ?: 0 
                                    } ?: listOf(uri)
                                    
                                    onStatus("正在处理加密分卷: ${name.substringBefore(".pjm.")}")
                                    SequentialUriInputStream(context, groupUris).use { input ->
                                        val progressInput = ProgressInputStream(input) { reportProgress(it) }
                                        val xorInput = CryptoUtils.createXorStream(progressInput, 0)
                                        processRecursiveStream(context, name.substringBefore(".pjm."), xorInput, collectedEntities, fileDao, onStatus, 0)
                                    }
                                    groupUris.forEach { processedUris.add(it) }
                                    return@withPermit
                                } else if (name.contains(".pjm.") && !name.contains(".pjm.1")) {
                                    return@withPermit
                                }

                                // 2. 处理压缩包或普通文件
                                onStatus("正在入库: $name")
                                var handled = false
                                
                                if (FileUtils.isArchiveFile(name) && isAutoExtractEnabled) {
                                    try {
                                        handled = SevenZipUtils.extractArchive(
                                            context, uri, passwordChars, onStatus, 
                                            onProgress = { reportProgress(it) }
                                        ) { entryName, inputStream ->
                                            // 压缩包内部文件也进行查重（如果能获取到大小）
                                            // 注意：有些压缩流无法直接获取 entry 大小，此处保守处理
                                            processRecursiveStream(context, entryName.substringAfterLast('/'), inputStream, collectedEntities, fileDao, onStatus, 1)
                                        }
                                    } catch (e: SevenZipUtils.EncryptedArchiveException) {
                                        VaultManager.notifyResult(OperationResult.PasswordRequired(e.fileName, listOf(uri)))
                                        return@withPermit 
                                    }
                                }

                                if (!handled) {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        val progressInput = ProgressInputStream(input) { reportProgress(it) }
                                        processRecursiveStream(context, name, progressInput, collectedEntities, fileDao, onStatus, 0)
                                    }
                                }
                                processedUris.add(uri)
                            } catch (e: Exception) {
                                if (e !is CancellationException) {
                                    PjmLogger.e(TAG, "处理失败: $name", e)
                                    processedUris.add(uri)
                                    throw e // Re-throw to trigger cleanup
                                }
                            }
                        }
                    }
                }
            }
            
            if (collectedEntities.isNotEmpty()) {
                onStatus("正在更新索引...")
                fileDao.upsertAll(collectedEntities)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                PjmLogger.e(TAG, "Ingestion failed, cleaning up partial files...", e)
                collectedEntities.forEach { entity ->
                    VaultManager.shredFile(VaultManager.getFileFromEntity(context, entity))
                }
            }
            throw e
        } finally {
            passwordChars?.let { java.util.Arrays.fill(it, '0') }
        }
        
        val finalMsg = if (duplicateCount.get() > 0) "入库完成 (跳过 ${duplicateCount.get()} 个重复文件)" else "任务全部完成"
        onStatus(finalMsg)
        onProgress(1f)
        VaultManager.triggerRefresh()
    }

    private suspend fun processRecursiveStream(context: Context, name: String, inputStream: InputStream, collectedEntities: MutableList<FileEntity>, fileDao: FileDao, onStatus: (String) -> Unit, depth: Int) {
        if (depth > MAX_RECURSION_DEPTH) return
        coroutineContext.ensureActive()

        val bis = if (inputStream is BufferedInputStream && inputStream.markSupported()) inputStream 
                  else BufferedInputStream(inputStream, IO_BUFFER_SIZE)
        
        var handled = false
        if (bis.markSupported()) {
            bis.mark(2048)
            val header = ByteArray(4)
            val read = try { bis.read(header) } catch (e: Exception) { 0 }
            
            if (read >= 4) {
                val dec = ByteArray(4) { i -> (header[i].toInt() xor XOR_KEY[(i.toLong() and KEY_SIZE_MASK).toInt()].toInt()).toByte() }
                if (java.nio.ByteBuffer.wrap(dec).int == CryptoUtils.FILE_MAGIC) {
                    try {
                        extractPjmStream(context, CryptoUtils.createXorStream(bis, 4), collectedEntities, fileDao, onStatus, depth + 1)
                        handled = true
                    } catch (e: Exception) { 
                        try { bis.reset() } catch (re: Exception) {}
                    }
                } else { try { bis.reset() } catch (re: Exception) {} }
            } else if (read > 0) { try { bis.reset() } catch (re: Exception) {} }
        }
        
        if (!handled) {
            // 注意：对于从 InputStream 进来的流，如果不知道确切大小，查重可能不准确
            // 目前主要针对顶层导入的文件进行“同名同大小”查重
            val entity = VaultManager.digestFileToEntity(context, name, bis)
            collectedEntities.add(entity)
        }
    }

    private suspend fun extractPjmStream(context: Context, inputStream: InputStream, collectedEntities: MutableList<FileEntity>, fileDao: FileDao, onStatus: (String) -> Unit, depth: Int) {
        ZipArchiveInputStream(inputStream, "UTF-8", true, true).use { zais ->
            var ze = zais.nextZipEntry
            while (ze != null) {
                coroutineContext.ensureActive()
                if (!ze.isDirectory) {
                    val wrapper = object : FilterInputStream(zais) { override fun close() {} }
                    // 内部 PJM 流解压查重逻辑（可选）
                    processRecursiveStream(context, ze.name.substringAfterLast('/'), wrapper, collectedEntities, fileDao, onStatus, depth)
                }
                ze = zais.nextZipEntry
            }
        }
    }

    private class ProgressInputStream(val input: InputStream, val onBytesRead: (Long) -> Unit) : InputStream() {
        override fun read(): Int = input.read().also { if (it != -1) onBytesRead(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return input.read(b, off, len).also { if (it > 0) onBytesRead(it.toLong()) }
        }
        override fun close() = input.close()
        override fun available(): Int = input.available()
    }

    private class SequentialUriInputStream(val context: Context, val uris: List<Uri>) : InputStream() {
        private var currentIdx = 0
        private var currentStream: InputStream? = null

        private fun ensureStream(): InputStream? {
            if (currentStream != null) return currentStream
            if (currentIdx >= uris.size) return null
            currentStream = try {
                context.contentResolver.openInputStream(uris[currentIdx++])?.let {
                    BufferedInputStream(it, 1024 * 512)
                }
            } catch (e: Exception) { null }
            return currentStream
        }

        override fun read(): Int {
            while (true) {
                val stream = ensureStream() ?: return -1
                val b = try { stream.read() } catch (e: Exception) { -1 }
                if (b != -1) return b
                closeCurrent()
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val stream = ensureStream() ?: return -1
                val n = try { stream.read(b, off, len) } catch (e: Exception) { -1 }
                if (n != -1) return n
                closeCurrent()
            }
        }

        private fun closeCurrent() {
            try { currentStream?.close() } catch (e: Exception) {}
            currentStream = null
        }

        override fun close() {
            closeCurrent()
            super.close()
        }
    }
}
