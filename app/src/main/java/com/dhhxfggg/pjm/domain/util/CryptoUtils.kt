package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.zip.Deflater

/**
 * Advanced Cryptography Utilities for the PJM project.
 * Handles XOR-based stream transformation, PJM container management, and atomic file operations.
 * Optimized for high-performance processing of large files (>2GB) using 64-bit offsets.
 */
object CryptoUtils {
    private const val TAG = "CryptoUtils"

    /**
     * File Magic Number for PJM containers (PJM\x01).
     */
    const val FILE_MAGIC = 0x504A4D01

    private val BUFFER_SIZE get() = VaultManager.ADAPTIVE_BUFFER_SIZE
    private val XOR_KEY = "dhhxfggg_is_the_best_pjm_key_fixed".toByteArray(StandardCharsets.UTF_8)

    // 核心修复：mask 必须与 native 实现（keyLen-1）保持一致。
    // 此前 Kotlin fallback 用 31（假设 key 为 32 字节），但 key 实际 34 字节，
    // native 用 keyLen-1=33，导致跨设备（native 可用性不同）加解密错乱。
    private val KEY_SIZE_MASK: Long get() = (XOR_KEY.size - 1).toLong()

    private var isNativeAvailable = false

    init {
        try {
            System.loadLibrary("pjm")
            isNativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            PjmLogger.w(TAG, "Native library 'libpjm.so' not found. Falling back to Kotlin implementation.")
        } catch (e: Throwable) {
            PjmLogger.e(TAG, "Unexpected error loading native library", e)
        }
    }

    private external fun transformBytesNative(
        data: ByteArray,
        off: Int,
        len: Int,
        key: ByteArray,
        startPos: Long,
    )

    /**
     * Transforms bytes in-place using an XOR operation.
     * Automatically attempts to use the native JNI implementation for maximum performance.
     *
     * @param b The byte array to transform.
     * @param off The starting offset in the array.
     * @param len The number of bytes to transform.
     * @param startIndex The global position in the data stream (for XOR key synchronization).
     */
    private fun transformBytesInPlace(
        b: ByteArray,
        off: Int,
        len: Int,
        startIndex: Long,
    ) {
        if (isNativeAvailable) {
            try {
                transformBytesNative(b, off, len, XOR_KEY, startIndex)
                return
            } catch (e: Throwable) {
                PjmLogger.e(TAG, "Native transformation failed, disabling JNI", e)
                isNativeAvailable = false
            }
        }

        // Kotlin Fallback implementation（mask 与 native 的 keyLen-1 一致）
        val mask = KEY_SIZE_MASK
        var currentPos = startIndex
        for (i in 0 until len) {
            val keyIndex = (currentPos and mask).toInt()
            b[off + i] = (b[off + i].toInt() xor XOR_KEY[keyIndex].toInt()).toByte()
            currentPos++
        }
    }

    /**
     * Returns a copy of the XOR key.
     */
    fun getXorKey(): ByteArray = XOR_KEY.copyOf()

    /**
     * Encrypts a list of Uris into a single PJM container file.
     * Uses atomic write (temp file + rename) to ensure data integrity.
     */
    suspend fun encryptUris(
        context: Context,
        inputUris: List<Uri>,
        outputPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> =
        withContext(VaultManager.PjmDispatchers.Crypto) {
            runCatching {
                val totalSize = inputUris.sumOf { FileUtils.getFileSize(context, it) }.coerceAtLeast(1L)
                VaultManager.ensureDiskSpace(context, totalSize)

                val finalFile = File(outputPath)
                val tmpFile = File("$outputPath.tmp_${System.currentTimeMillis()}")

                try {
                    tmpFile.parentFile?.mkdirs()
                    FileOutputStream(tmpFile).use { fos ->
                        var streamPos = 0L

                        val xorWrapper =
                            object : FilterOutputStream(fos) {
                                // 核心修复：单字节写入必须与批量写入走【同一个】 mask 计算路径（transformBytesInPlace）。
                                // 此前单字节用 KEY_SIZE_MASK(31)，批量走 native(keyLen-1=33) 或 Kotlin(31)，
                                // 而 ZipArchiveOutputStream 会混合调用 write(int) 与 write(byte[],off,len)，
                                // 导致同一文件内不同字节用不同 mask 加密，解密端批量读取时部分字节解不开，
                                // ZIP 数据损坏 → 解密失败。
                                override fun write(b: Int) {
                                    val tmp = byteArrayOf((b and 0xFF).toByte())
                                    transformBytesInPlace(tmp, 0, 1, streamPos)
                                    out.write(tmp[0].toInt() and 0xFF)
                                    streamPos++
                                }

                                override fun write(
                                    b: ByteArray,
                                    off: Int,
                                    len: Int,
                                ) {
                                    if (len <= 0) return
                                    val copy = b.copyOfRange(off, off + len)
                                    try {
                                        transformBytesInPlace(copy, 0, len, streamPos)
                                        out.write(copy, 0, len)
                                        streamPos += len.toLong()
                                    } finally {
                                        Arrays.fill(copy, 0.toByte()) // Zero out sensitive data copy
                                    }
                                }
                            }

                        val magicBuffer = ByteBuffer.allocate(4).putInt(FILE_MAGIC).array()
                        xorWrapper.write(magicBuffer)

                        ZipArchiveOutputStream(BufferedOutputStream(xorWrapper as OutputStream, BUFFER_SIZE)).use { zos ->
                            zos.setUseZip64(Zip64Mode.AsNeeded)
                            zos.encoding = "UTF-8"
                            val buffer = VaultManager.acquireBuffer()
                            var processedBytes = 0L
                            try {
                                for (uri in inputUris) {
                                    val fileName = FileUtils.getFileName(context, uri)
                                    val entry = ZipArchiveEntry(fileName)
                                    zos.setLevel(if (FileUtils.shouldCompress(fileName)) Deflater.BEST_SPEED else Deflater.NO_COMPRESSION)
                                    zos.putArchiveEntry(entry)
                                    context.contentResolver.openInputStream(uri)?.use { fis ->
                                        var len: Int
                                        while (fis.read(buffer).also { len = it } > 0) {
                                            zos.write(buffer, 0, len)
                                            processedBytes += len
                                            onProgress(processedBytes.toFloat() / totalSize)
                                        }
                                    }
                                    zos.closeArchiveEntry()
                                }
                                zos.finish()
                            } finally {
                                VaultManager.releaseBuffer(buffer)
                            }
                            xorWrapper.flush()
                            fos.fd.sync()
                        }
                    }

                    if (finalFile.exists()) VaultManager.shredFile(finalFile)
                    if (!tmpFile.renameTo(finalFile)) {
                        throw IOException("Failed to finalize file: rename failed")
                    }
                    PjmLogger.i(TAG, "Successfully encrypted ${inputUris.size} files to $outputPath")
                } catch (e: Exception) {
                    VaultManager.shredFile(tmpFile)
                    PjmLogger.e(TAG, "Encryption failed for $outputPath", e)
                    throw e
                }
            }
        }

    /**
     * Decrypts PJM containers and provides entries via a callback.
     *
     * @param context Android context.
     * @param uris List of Uris pointing to PJM files.
     * @param onEntry Callback invoked for each entry in the container.
     */
    suspend fun decryptPjmToEntries(
        context: Context,
        uris: List<Uri>,
        onEntry: suspend (String, InputStream) -> Unit,
    ) = withContext(VaultManager.PjmDispatchers.Crypto) {
        for (uri in uris) {
            try {
                context.contentResolver.openInputStream(uri)?.use { fis ->
                    val xorStream = createXorStream(fis, 0L)
                    val magicBuffer = ByteArray(4)
                    val read = xorStream.read(magicBuffer)
                    if (read < 4) {
                        PjmLogger.w(TAG, "File too small to be a PJM container: $uri")
                        return@use
                    }
                    val magic = ByteBuffer.wrap(magicBuffer).int
                    if (magic != FILE_MAGIC) {
                        PjmLogger.e(TAG, "Invalid PJM magic: 0x${Integer.toHexString(magic)} at $uri")
                        return@use
                    }

                    // 核心修复：必须允许 stored entry + data descriptor。
                    // 加密端对已压缩格式(图片/视频等)使用 Deflater.NO_COMPRESSION(stored)，
                    // 且底层输出流不可 seekable，ZipArchiveOutputStream 会为 stored entry
                    // 写入 data descriptor；默认构造(allowStoredEntriesWithDataDescriptor=false)
                    // 会导致这些条目读取失败/错位，解密出损坏或错误类型的文件。
                    ZipArchiveInputStream(xorStream, "UTF-8", true, true).use { zis ->
                        var entry: ZipArchiveEntry?
                        while (zis.nextEntry.also { entry = it } != null) {
                            entry?.name?.let { onEntry(it, zis) }
                        }
                    }
                }
            } catch (e: Exception) {
                PjmLogger.e(TAG, "Decryption failed for $uri", e)
            }
        }
    }

    /**
     * 内容级 PJM 容器检测：读取前 4 字节并 XOR 解密后比对文件魔数。
     * 不依赖文件名 —— 分享场景（微信/QQ 等）的 content URI 经常拿不到正确文件名，
     * 文件名识别会失败，必须用内容确认。
     */
    suspend fun isPjmUri(
        context: Context,
        uri: Uri,
    ): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val header = ByteArray(4)
                    val read = input.read(header)
                    if (read < 4) return@use false
                    val tmp = header.copyOf()
                    transformBytesInPlace(tmp, 0, 4, 0L)
                    ByteBuffer.wrap(tmp).int == FILE_MAGIC
                } ?: false
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Creates an XOR-transformed InputStream wrapper.
     *
     * @param inputStream The source stream.
     * @param initialPos The initial position for XOR key sync.
     * @return A wrapping [InputStream] that decrypts/transforms on the fly.
     */
    fun createXorStream(
        inputStream: InputStream,
        initialPos: Long = 0L,
    ): InputStream {
        return object : FilterInputStream(inputStream) {
            private var pos = initialPos

            // 核心修复：单字节读取与批量读取必须走【同一个】 mask 计算路径（transformBytesInPlace），
            // 与加密端 write(int)/write(byte[],off,len) 保持严格对称，否则混合读写时部分字节解不开。
            override fun read(): Int {
                val b = super.read()
                if (b == -1) return -1
                val tmp = byteArrayOf(b.toByte())
                transformBytesInPlace(tmp, 0, 1, pos)
                pos++
                return tmp[0].toInt() and 0xFF
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                val n = super.read(b, off, len)
                if (n > 0) {
                    transformBytesInPlace(b, off, n, pos)
                    pos += n.toLong()
                }
                return n
            }
        }
    }
}
