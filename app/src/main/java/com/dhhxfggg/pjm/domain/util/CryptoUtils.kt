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
    private const val KEY_SIZE_MASK = 31L 

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

    private external fun transformBytesNative(data: ByteArray, off: Int, len: Int, key: ByteArray, startPos: Long)

    /**
     * Transforms bytes in-place using an XOR operation.
     * Automatically attempts to use the native JNI implementation for maximum performance.
     *
     * @param b The byte array to transform.
     * @param off The starting offset in the array.
     * @param len The number of bytes to transform.
     * @param startIndex The global position in the data stream (for XOR key synchronization).
     */
    private fun transformBytesInPlace(b: ByteArray, off: Int, len: Int, startIndex: Long) {
        if (isNativeAvailable) {
            try {
                transformBytesNative(b, off, len, XOR_KEY, startIndex)
                return
            } catch (e: Throwable) {
                PjmLogger.e(TAG, "Native transformation failed, disabling JNI", e)
                isNativeAvailable = false 
            }
        }
        
        // Kotlin Fallback implementation
        var currentPos = startIndex
        for (i in 0 until len) {
            val keyIndex = (currentPos and KEY_SIZE_MASK).toInt()
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
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(VaultManager.PjmDispatchers.Crypto) {
        runCatching {
            val totalSize = inputUris.sumOf { FileUtils.getFileSize(context, it) }.coerceAtLeast(1L)
            VaultManager.ensureDiskSpace(context, totalSize)

            val finalFile = File(outputPath)
            val tmpFile = File("${outputPath}.tmp_${System.currentTimeMillis()}")
            
            try {
                tmpFile.parentFile?.mkdirs()
                FileOutputStream(tmpFile).use { fos ->
                    var streamPos = 0L 

                    val xorWrapper = object : FilterOutputStream(fos) {
                        override fun write(b: Int) {
                            val keyIndex = (streamPos and KEY_SIZE_MASK).toInt()
                            out.write(b xor XOR_KEY[keyIndex].toInt())
                            streamPos++
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
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
                        zos.setEncoding("UTF-8")
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
        onEntry: suspend (String, InputStream) -> Unit
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
                    
                    ZipArchiveInputStream(xorStream).use { zis ->
                        var entry: ZipArchiveEntry?
                        while (zis.nextZipEntry.also { entry = it } != null) {
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
     * Creates an XOR-transformed InputStream wrapper.
     *
     * @param inputStream The source stream.
     * @param initialPos The initial position for XOR key sync.
     * @return A wrapping [InputStream] that decrypts/transforms on the fly.
     */
    fun createXorStream(inputStream: InputStream, initialPos: Long = 0L): InputStream {
        return object : FilterInputStream(inputStream) {
            private var pos = initialPos
            override fun read(): Int {
                val b = super.read()
                if (b == -1) return -1
                val res = (b xor XOR_KEY[(pos and KEY_SIZE_MASK).toInt()].toInt()) and 0xFF
                pos++
                return res
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
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
