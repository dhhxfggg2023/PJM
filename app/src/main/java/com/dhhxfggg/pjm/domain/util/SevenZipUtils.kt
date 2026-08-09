package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.PasswordRequiredException
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.Arrays
import kotlin.coroutines.coroutineContext

object SevenZipUtils {
    private const val TAG = "SevenZipUtils"

    class EncryptedArchiveException(val fileName: String) : Exception()
    class InsufficientStorageException : Exception("磁盘空间不足以进行解压预处理")

    suspend fun extractArchive(
        context: Context,
        uri: Uri,
        password: CharArray? = null,
        onStatus: (String) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onEntry: suspend (String, InputStream) -> Unit
    ): Boolean {
        val appContext = context.applicationContext
        val fileName = FileUtils.getFileName(appContext, uri)
        
        return try {
            appContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fis = java.io.FileInputStream(pfd.fileDescriptor)
                val channel = fis.channel
                
                val lowerName = fileName.lowercase()
                when {
                    lowerName.endsWith(".7z") -> extract7z(channel, password, fileName, onStatus, onProgress, onEntry)
                    lowerName.endsWith(".rar") -> {
                        processWithTempFile(appContext, uri, fileName) { tempFile ->
                            extractRar(tempFile, password, fileName, onStatus, onEntry)
                        }
                    }
                    else -> extractZip(channel, password, fileName, onStatus, onProgress, onEntry)
                }
                true
            } ?: false
        } catch (e: EncryptedArchiveException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            PjmLogger.e(TAG, "解压失败: $fileName", e)
            false
        } finally {
            password?.let { Arrays.fill(it, '0') }
        }
    }

    private suspend fun processWithTempFile(context: Context, uri: Uri, fileName: String, block: suspend (File) -> Unit) {
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

    private suspend fun extract7z(channel: FileChannel, password: CharArray?, fileName: String, onStatus: (String) -> Unit, onProgress: (Long) -> Unit, onEntry: suspend (String, InputStream) -> Unit) {
        try {
            val builder = SevenZFile.builder().setSeekableByteChannel(channel)
            if (password != null) builder.setPassword(password)
            builder.get().use { s7f ->
                var entry = s7f.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive()
                    if (!entry.isDirectory) {
                        onStatus("正在解压: ${entry.name}")
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

    private suspend fun extractZip(channel: FileChannel, password: CharArray?, fileName: String, onStatus: (String) -> Unit, onProgress: (Long) -> Unit, onEntry: suspend (String, InputStream) -> Unit) {
        try {
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
                val entries = zipFile.entries
                while (entries.hasMoreElements()) {
                    coroutineContext.ensureActive()
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        zipFile.getInputStream(entry).use { input ->
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

    private suspend fun extractRar(file: File, password: CharArray?, fileName: String, onStatus: (String) -> Unit, onEntry: suspend (String, InputStream) -> Unit) {
        try {
            Archive(file, password?.let { String(it) }).use { archive ->
                if (archive.isEncrypted) throw EncryptedArchiveException(fileName)
                var header: FileHeader? = archive.nextFileHeader()
                while (header != null) {
                    coroutineContext.ensureActive()
                    if (!header.isDirectory) {
                        val name = if (header.isUnicode) header.fileNameW else header.fileNameString
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
        e is PasswordRequiredException || e.message?.contains("password", true) == true || e.message?.contains("decrypt", true) == true

    /**
     * 性能优化：每 64KB 汇报一次进度，减少回调开销
     */
    private class BatchProgressInputStream(val input: InputStream, val onProgress: (Long) -> Unit) : InputStream() {
        private var bytesReadSinceLastReport = 0L
        override fun read(): Int = input.read().also { if (it != -1) report(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it > 0) report(it.toLong()) }
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
