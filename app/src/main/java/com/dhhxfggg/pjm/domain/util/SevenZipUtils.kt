package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.dhhxfggg.pjm.R
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.Arrays
import kotlin.coroutines.coroutineContext

object SevenZipUtils {
    private const val TAG = "SevenZipUtils"

    /** 解压总体积上限 / 压缩包体积 的倍数（压缩炸弹防护） */
    private const val MAX_EXPANSION_RATIO = 50L

    class EncryptedArchiveException(
        val fileName: String,
    ) : Exception()

    class InsufficientStorageException(
        context: Context,
    ) : Exception(context.getString(R.string.error_insufficient_storage_extract))

    /**
     * 解压结果。
     *
     * 核心修复：原来 `extractArchive` 只返回 `Boolean`，把两种完全不同的情况都压成 `false` ——
     *  1. 「这个文件不是我能解开的压缩包」（应回退为按普通文件入库）；
     *  2. 「解压到一半失败了」（损坏 / 空间不足，应报错，绝不能静默降级）。
     * 调用方无法区分，于是把失败当成功：已解出的部分条目入库、原始压缩包又存了一份，
     * 而 `failedCount` 不增加，用户界面显示「全部成功」。
     */
    enum class ExtractOutcome {
        /** 已按压缩包解出内容 */
        EXTRACTED,

        /** 不是本工具支持的压缩包（或根本打不开）→ 调用方应按普通文件入库 */
        NOT_AN_ARCHIVE,

        /** 解压过程中失败 → 调用方必须回滚已解出的条目并向用户报错 */
        FAILED,
    }

    suspend fun extractArchive(
        context: Context,
        uri: Uri,
        password: CharArray? = null,
        onStatus: (String) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onEntry: suspend (String, InputStream) -> Unit,
    ): ExtractOutcome {
        val appContext = context.applicationContext
        val fileName = FileUtils.getFileName(appContext, uri)

        return try {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // 核心修复：显式关闭这个 FileInputStream。
                // 原来它被创建后从不关闭，而外层 `pfd.use` 关闭的是同一个 fd ——
                // 该流会持有已失效的 fd 号，GC 终结时可能关掉被复用的其它 fd。
                // 现在所有者的顺序是明确的：ZipFile/SevenZFile 先关 channel，
                // 然后 fis 自己关（幂等），最后由 pfd 关 fd。
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val lowerName = fileName.lowercase()
                    when {
                        lowerName.endsWith(".7z") ->
                            extract7z(appContext, channel, password, fileName, onStatus, onProgress, onEntry)
                        lowerName.endsWith(".rar") -> {
                            processWithTempFile(appContext, uri, fileName) { tempFile ->
                                extractRar(tempFile, password, fileName, onStatus, onEntry)
                            }
                        }
                        else -> extractZip(appContext, channel, password, fileName, onStatus, onProgress, onEntry)
                    }
                    ExtractOutcome.EXTRACTED
                }
            } ?: ExtractOutcome.NOT_AN_ARCHIVE
        } catch (e: EncryptedArchiveException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            PjmLogger.e(TAG, "解压失败: $fileName", e)
            // 关键：区分「根本不是压缩包」与「解压中失败」。
            // ZipException/SecurityException/EOF 等说明文件本身不是有效的压缩包，回退普通入库；
            // 其余（IO 错误、空间不足、压缩炸弹）属于真失败，必须上报。
            if (isNotArchiveError(e)) ExtractOutcome.NOT_AN_ARCHIVE else ExtractOutcome.FAILED
        } finally {
            password?.let { Arrays.fill(it, '0') }
        }
    }

    /** 判断异常是否意味着「这个文件压根不是有效的压缩包」 */
    private fun isNotArchiveError(e: Exception): Boolean =
        e is java.util.zip.ZipException ||
            e is java.io.EOFException ||
            e is IllegalArgumentException ||
            e.message?.contains("not a valid", ignoreCase = true) == true ||
            e.message?.contains("Bad signature", ignoreCase = true) == true

    /**
     * zip 解压前校验可用空间与膨胀比。
     *
     * 核心修复：此前只有 rar 路径做了磁盘空间预检，zip/7z 完全没有 ——
     * 压缩包把 filesDir 写满后会退化成「部分解压成功」的静默假成功。
     */
    private fun precheckZip(
        context: Context,
        zipFile: ZipFile,
        archiveSize: Long,
    ) {
        var declaredTotal = 0L
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.size > 0) declaredTotal += entry.size
        }
        if (archiveSize > 0 && declaredTotal > archiveSize * MAX_EXPANSION_RATIO) {
            throw InsufficientStorageException(context)
        }
        VaultManager.ensureDiskSpace(context, declaredTotal)
    }

    private suspend fun processWithTempFile(
        context: Context,
        uri: Uri,
        fileName: String,
        block: suspend (File) -> Unit,
    ) {
        val fileSize = FileUtils.getFileSize(context, uri)
        VaultManager.ensureDiskSpace(context, fileSize)

        val tempFile = File(context.cacheDir, "ext_idx_${System.nanoTime()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output, VaultManager.ADAPTIVE_BUFFER_SIZE) }
            }
            block(tempFile)
        } finally {
            VaultManager.shredFile(tempFile)
        }
    }

    private suspend fun extract7z(
        context: Context,
        channel: FileChannel,
        password: CharArray?,
        fileName: String,
        onStatus: (String) -> Unit,
        onProgress: (Long) -> Unit,
        onEntry: suspend (String, InputStream) -> Unit,
    ) {
        try {
            val builder = SevenZFile.builder().setSeekableByteChannel(channel)
            password?.let { builder.setPassword(it) }
            builder.get().use { s7f ->
                var entry = s7f.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    if (!entry.isDirectory) {
                        onStatus(context.getString(R.string.status_extracting, entry.name))
                        val entryStream = s7f.getInputStream(entry)
                        val progressStream = BatchProgressInputStream(entryStream, onProgress)
                        progressStream.use { onEntry(entry.name ?: "unk", it) }
                    }
                    entry = s7f.nextEntry
                }
            }
        } catch (e: Exception) {
            if (isPasswordError(e)) throw EncryptedArchiveException(fileName)
            throw e
        }
    }

    private suspend fun extractZip(
        context: Context,
        channel: FileChannel,
        _password: CharArray?,
        fileName: String,
        onStatus: (String) -> Unit,
        onProgress: (Long) -> Unit,
        onEntry: suspend (String, InputStream) -> Unit,
    ) {
        try {
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                precheckZip(context, zipFile, channel.size())
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zipFile.getInputStream(entry).use { input ->
                            onStatus(context.getString(R.string.status_extracting, entry.name))
                            onEntry(entry.name, BatchProgressInputStream(input, onProgress))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isPasswordError(e)) throw EncryptedArchiveException(fileName)
            throw e
        }
    }

    private suspend fun extractRar(
        file: File,
        password: CharArray?,
        fileName: String,
        _onStatus: (String) -> Unit,
        onEntry: suspend (String, InputStream) -> Unit,
    ) {
        try {
            Archive(file, password?.let { String(it) }).use { archive ->
                if (archive.isEncrypted) throw EncryptedArchiveException(fileName)
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    coroutineContext.ensureActive()
                    if (!header.isDirectory) {
                        val name = header.fileName ?: "unk"
                        archive.getInputStream(header).use { onEntry(name, it) }
                    }
                    header = archive.nextFileHeader()
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("password", true) == true) throw EncryptedArchiveException(fileName)
            throw e
        }
    }

    private fun isPasswordError(e: Exception): Boolean =
        (e is PasswordRequiredException) ||
            (
                e.message?.contains(
                    "password",
                    ignoreCase = true,
                ) == true
            ) ||
            (e.message?.contains("decrypt", ignoreCase = true) == true)

    /**
     * 性能优化：每 64KB 汇报一次进度，减少回调开销
     */
    private class BatchProgressInputStream(
        val input: InputStream,
        val onProgress: (Long) -> Unit,
    ) : InputStream() {
        private var bytesReadSinceLastReport = 0L

        override fun read(): Int = input.read().also { if (it != -1) report(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = input.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        private fun report(n: Long) {
            bytesReadSinceLastReport += n
            if (bytesReadSinceLastReport >= 65536) {
                onProgress(bytesReadSinceLastReport)
                bytesReadSinceLastReport = 0
            }
        }

        override fun close() {
            if (bytesReadSinceLastReport > 0) onProgress(bytesReadSinceLastReport)
            input.close()
        }
    }
}
