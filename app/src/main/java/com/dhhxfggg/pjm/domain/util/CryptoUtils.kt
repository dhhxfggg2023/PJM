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
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
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

    /** 容器头里 magic 的长度（字节） */
    private const val MAGIC_SIZE = 4

    /** 识别容器头时需要预读的字节数：magic(4) + 可能的版本字节(1) */
    private const val HEADER_PEEK_SIZE = MAGIC_SIZE + 1

    /**
     * 当前写入的容器版本号。
     *
     * 版本沿革：
     *  - **无版本字节（历史格式）**：`magic(4)` 之后直接是 XOR 后的 ZIP，ZIP 起点密钥流位置 = 4，
     *    密钥流取字节方式为 [KeyStreamMode.LEGACY_MASK]。
     *  - **0x02**：预留给「有版本字节但算法不变」的布局，从未实际产出，读端仍兼容。
     *  - **0x03（当前）**：`magic(4) + version(1)`，ZIP 起点密钥流位置 = 5，
     *    密钥流取字节方式改为 [KeyStreamMode.MODULO]。
     *
     * 判别方式：前 4 字节解码后等于 [FILE_MAGIC] 的那个模式即为该容器所用模式（两种模式
     * 不可能同时命中，见 [detectContainerHeader]）；随后第 5 字节解码后若是 ZIP 本地文件头
     * 首字节 `'P'`(0x50)，说明它是 ZIP 流的一部分（历史布局），否则是版本号。
     *
     * 读端同时支持全部版本（见 [openPjmContent]）；写端写 0x03。
     * **注意：0x03 容器无法被 1.9.3 及更早的 PJM 打开** —— 算法已不同。
     */
    const val CONTAINER_VERSION: Byte = 0x03

    /** ZIP 本地文件头首字节 'P'，用于区分历史布局 */
    private const val ZIP_HEADER_FIRST_BYTE = 0x50.toByte()

    /**
     * 密钥流取字节方式。
     *
     * 背景：密钥是 34 字节（`dhhxfggg_is_the_best_pjm_key_fixed`）。历史上代码用
     * `keyIndex = pos and (keyLen - 1)`（即 `pos and 33`）实现，本意是 `pos % 34` ——
     * 但 `x and (n-1)` 只有在 **n 是 2 的幂** 时才等价于 `x % n`，而 34 不是。
     * 结果 `pos and 33` 只保留 pos 的第 0、5 位，下标只会在 {0, 1, 32, 33} 之间跳，
     * 34 个密钥字节中只有 3 个不同字符真正参与运算 —— 等于把密钥强度削到 3 字节。
     */
    enum class KeyStreamMode {
        /**
         * 历史行为：`keyIndex = pos and (keyLen - 1)`。
         * **仅用于解开历史容器，不要用于新容器。**
         */
        LEGACY_MASK,

        /**
         * 正确行为：`keyIndex = pos % keyLen`，34 个密钥字节全部参与。
         * 0x03 及以后的容器使用本模式。
         */
        MODULO,
    }

    /** 容器头解析结果 */
    private class ContainerHeader(
        val mode: KeyStreamMode,
        /** ZIP 数据的起始密钥流位置：4 = 历史布局，5 = 带版本字节 */
        val zipStart: Long,
    )

    /**
     * 容器写入的临时文件标记：最终路径为 `<目标路径>` + [TEMP_FILE_MARKER] + 毫秒数。
     *
     * 临时文件与正式容器**同目录**（必须是同分区才能原子 rename），因此它天然会被
     * 「全量同步 / 命名迁移」的目录扫描看到。进程在写盘途中被杀时文件不会被清理，
     * 而 `getFileExtension("X.pjm.1.tmp_123")` 会返回 `"pjm"` —— 垃圾半成品就会作为
     * 正式资产出现在文件柜里、还能被分享。所以扫描侧统一用本标记过滤，启动时再清一次。
     */
    internal const val TEMP_FILE_MARKER = ".tmp_"

    private val BUFFER_SIZE get() = VaultManager.ADAPTIVE_BUFFER_SIZE
    private val XOR_KEY = "dhhxfggg_is_the_best_pjm_key_fixed".toByteArray(StandardCharsets.UTF_8)

    /** 历史 mask 模式的取模掩码（= keyLen - 1）。**不是取模**，只在 [KeyStreamMode.LEGACY_MASK] 下使用。 */
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
        mode: Int,
    )

    /**
     * Transforms bytes in-place using an XOR operation.
     * Automatically attempts to use the native JNI implementation for maximum performance.
     *
     * @param b The byte array to transform.
     * @param off The starting offset in the array.
     * @param len The number of bytes to transform.
     * @param startIndex The global position in the data stream (for XOR key synchronization).
     * @param mode 密钥流取字节方式；解历史容器必须用 [KeyStreamMode.LEGACY_MASK]。
     */
    private fun transformBytesInPlace(
        b: ByteArray,
        off: Int,
        len: Int,
        startIndex: Long,
        mode: KeyStreamMode,
    ) {
        if (isNativeAvailable) {
            try {
                transformBytesNative(b, off, len, XOR_KEY, startIndex, mode.ordinal)
                return
            } catch (e: Throwable) {
                PjmLogger.e(TAG, "Native transformation failed, disabling JNI", e)
                isNativeAvailable = false
            }
        }

        // Kotlin 兜底实现：只在 libpjm.so 加载失败时才会走到。
        // 这里刻意写成最直白的 `% keyLen`，便于人工审计 —— 它与 native 的
        // 「铺密钥流块 + 64 位异或」在数学上完全等价（同一位置用同一密钥字节），
        // 由 CryptoRoundTripTest 的模式一致性用例保证两者不会漂移。
        val keyLen = XOR_KEY.size
        var currentPos = startIndex
        when (mode) {
            KeyStreamMode.LEGACY_MASK -> {
                val mask = KEY_SIZE_MASK
                for (i in 0 until len) {
                    val keyIndex = (currentPos and mask).toInt()
                    b[off + i] = (b[off + i].toInt() xor XOR_KEY[keyIndex].toInt()).toByte()
                    currentPos++
                }
            }

            KeyStreamMode.MODULO -> {
                var keyIndex = (currentPos % keyLen).toInt()
                for (i in 0 until len) {
                    b[off + i] = (b[off + i].toInt() xor XOR_KEY[keyIndex].toInt()).toByte()
                    if (++keyIndex == keyLen) keyIndex = 0
                }
            }
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
                val tmpFile = File("$outputPath$TEMP_FILE_MARKER${System.currentTimeMillis()}")

                try {
                    tmpFile.parentFile?.mkdirs()
                    FileOutputStream(tmpFile).use { fos ->
                        var streamPos = 0L
                        // 新容器统一使用 MODULO 模式（34 个密钥字节全部参与）。
                        val mode = KeyStreamMode.MODULO
                        // 核心修复：复用同一块 scratch 缓冲。
                        // 写入必须先把调用方的字节拷出来再原地变换（不能污染调用方缓冲区），
                        // 原来每次 write 都 `copyOfRange` 新分配一个 len 大小的数组、写完再清零 ——
                        // 而 NO_COMPRESSION 路径下 ZipArchiveOutputStream 常按 1MB 批量写，
                        // 导出 10GB 就是约 1 万次 1MB 分配（≈10GB 垃圾），GC 尖峰明显。
                        // 注：变换后就地存放的是密文，因此不再需要逐次清零。
                        var scratch = ByteArray(0)

                        val xorWrapper =
                            object : FilterOutputStream(fos) {
                                // 核心修复：单字节写入必须与批量写入走【同一个】 mask 计算路径（transformBytesInPlace）。
                                // 此前单字节用 KEY_SIZE_MASK(31)，批量走 native(keyLen-1=33) 或 Kotlin(31)，
                                // 而 ZipArchiveOutputStream 会混合调用 write(int) 与 write(byte[],off,len)，
                                // 导致同一文件内不同字节用不同 mask 加密，解密端批量读取时部分字节解不开，
                                // ZIP 数据损坏 → 解密失败。
                                override fun write(b: Int) {
                                    val tmp = byteArrayOf((b and 0xFF).toByte())
                                    transformBytesInPlace(tmp, 0, 1, streamPos, mode)
                                    out.write(tmp[0].toInt() and 0xFF)
                                    streamPos++
                                }

                                override fun write(
                                    b: ByteArray,
                                    off: Int,
                                    len: Int,
                                ) {
                                    if (len <= 0) return
                                    if (scratch.size < len) scratch = ByteArray(len)
                                    System.arraycopy(b, off, scratch, 0, len)
                                    transformBytesInPlace(scratch, 0, len, streamPos, mode)
                                    out.write(scratch, 0, len)
                                    streamPos += len.toLong()
                                }

                                /**
                                 * 核心修复：只 flush，不向下传播 close。
                                 *
                                 * `ZipArchiveOutputStream.close()` 会一路 close 到最底层的
                                 * FileOutputStream，导致下面的 `fos.fd.sync()` 必须在 close
                                 * 【之前】调用 —— 而那时 ZIP 中央目录还压在 1MB 的
                                 * BufferedOutputStream 里没落盘，fsync 等于只同步了半成品。
                                 * 断电/进程被杀时容器会缺少中央目录（不可解密）。
                                 * 这里阻断 close 传播，让外层 `use` 统一关闭 fos，
                                 * 从而可以在整条链路 flush 完成之后再 fsync。
                                 */
                                override fun close() {
                                    flush()
                                }
                            }

                        val magicBuffer = ByteBuffer.allocate(MAGIC_SIZE).putInt(FILE_MAGIC).array()
                        xorWrapper.write(magicBuffer)
                        // 写入端采用「新版布局 + 新算法」：
                        //   magic(4) + version(1, = CONTAINER_VERSION) + XOR 后的 ZIP（密钥流位置 5 起）
                        // 版本字节本身也参与异或（与 ZIP 同一密钥流位置连续），读端按同一规则解出。
                        xorWrapper.write(byteArrayOf(CONTAINER_VERSION))

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
                        }
                        // 核心修复：fsync 必须放在 ZIP 输出流关闭【之后】。
                        // `ZipArchiveOutputStream.close()` 会把中央目录从 BufferedOutputStream
                        // 里冲出来（xorWrapper 已被改为不传播 close，所以 fos 仍然可用），
                        // 此时 tmpFile 才是完整的容器；在这一刻 fsync 才真正保证断电可恢复。
                        xorWrapper.flush()
                        fos.fd.sync()
                    }

                    // 核心修复：绝不「先删旧文件再改名」。
                    // 旧写法在 rename 失败（ENOSPC/EPERM/跨设备）时，旧容器已被物理销毁、
                    // 新的又只剩临时文件（随后被 catch 清掉）—— 两边全丢，不可恢复。
                    // 同分区 rename 本身就会原子覆盖目标，失败再退化为「删旧 → 改名」。
                    if (!tmpFile.renameTo(finalFile)) {
                        if (finalFile.exists() && !VaultManager.shredFile(finalFile)) {
                            throw IOException("Failed to finalize file: cannot replace existing container")
                        }
                        if (!tmpFile.renameTo(finalFile)) {
                            throw IOException("Failed to finalize file: rename failed")
                        }
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
     * 解析容器头：判定密钥流模式与 ZIP 起点。
     *
     * 判定方式：用两种模式各解一遍前 5 字节，哪个能解出 [FILE_MAGIC] 就是哪个模式。
     * 两种模式**不可能同时命中** —— 位置 2、3 在 [KeyStreamMode.LEGACY_MASK] 下取
     * `KEY[0]`/`KEY[1]`（'d'/'h'），在 [KeyStreamMode.MODULO] 下取 `KEY[2]`/`KEY[3]`（'h'/'x'），
     * 要同时等于魔数的第 3、4 字节（0x4D、0x01）是不可能的。
     *
     * 随后第 5 字节若解出 `'P'`(0x50)，说明它本属于 ZIP 流（历史布局，ZIP 起点 4）；
     * 否则它是版本字节（ZIP 起点 5）。
     *
     * @return 解析结果；不是 PJM 容器时返回 null
     */
    private fun detectContainerHeader(head: ByteArray): ContainerHeader? {
        if (head.size < HEADER_PEEK_SIZE) return null
        for (mode in KeyStreamMode.entries) {
            val decoded = head.copyOf(HEADER_PEEK_SIZE)
            transformBytesInPlace(decoded, 0, HEADER_PEEK_SIZE, 0L, mode)
            if (ByteBuffer.wrap(decoded).int != FILE_MAGIC) continue
            val zipStart =
                if (decoded[MAGIC_SIZE] == ZIP_HEADER_FIRST_BYTE) {
                    MAGIC_SIZE.toLong()
                } else {
                    HEADER_PEEK_SIZE.toLong()
                }
            return ContainerHeader(mode, zipStart)
        }
        return null
    }

    /**
     * 识别 PJM 容器头，并把输入流对齐到 ZIP 起点。
     *
     * 兼容全部历史布局（见 [CONTAINER_VERSION]）：
     *  - 无版本字节：`magic(4)` 之后直接是 XOR 后的 ZIP（密钥流位置 4 起，LEGACY_MASK）；
     *  - 有版本字节：`magic(4) + version(1)`（密钥流位置 5 起，0x03 起为 MODULO）。
     *
     * @param input 容器原始流（未解密）。若调用方依赖 mark/reset 回退，请自行 mark。
     * @return 已对齐到 ZIP 起点、按正确密钥流位置与模式解密的流；
     *   **不是** PJM 容器（魔数不符或长度不足）时返回 null —— 此时调用方应自行 reset，
     *   本函数返回 null 前可能已从 `input` 读过若干字节。
     */
    fun openPjmContent(input: InputStream): InputStream? {
        val pbin = input as? PushbackInputStream ?: PushbackInputStream(input, HEADER_PEEK_SIZE)
        val head = ByteArray(HEADER_PEEK_SIZE)
        var read = 0
        while (read < head.size) {
            val n = pbin.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < HEADER_PEEK_SIZE) return null

        val header = detectContainerHeader(head) ?: return null
        if (header.zipStart == MAGIC_SIZE.toLong()) {
            // 第 5 字节属于 ZIP 流，必须原样退回让 ZIP 读取器拿到；
            // unread 的是**原始密文**字节，随后由 createXorStream 在位置 4 重新解密。
            pbin.unread(head[MAGIC_SIZE].toInt() and 0xFF)
        }
        return createXorStream(pbin, header.zipStart, header.mode)
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
                    // 核心修复：容器头解析（含版本兼容）统一走 openPjmContent，
                    // 避免各处手写「读 4 字节比对魔数」而漏掉版本字节的对齐。
                    val zipStream = openPjmContent(fis)
                    if (zipStream == null) {
                        PjmLogger.e(TAG, "不是有效的 PJM 容器（魔数不符或文件过短）: $uri")
                        return@use
                    }

                    // 核心修复：必须允许 stored entry + data descriptor。
                    // 加密端对已压缩格式(图片/视频等)使用 Deflater.NO_COMPRESSION(stored)，
                    // 且底层输出流不可 seekable，ZipArchiveOutputStream 会为 stored entry
                    // 写入 data descriptor；默认构造(allowStoredEntriesWithDataDescriptor=false)
                    // 会导致这些条目读取失败/错位，解密出损坏或错误类型的文件。
                    ZipArchiveInputStream(zipStream, "UTF-8", true, true).use { zis ->
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
     * 内容级 PJM 容器检测：读取头部并按容器自身的版本/模式解密后比对文件魔数。
     * 不依赖文件名 —— 分享场景（微信/QQ 等）的 content URI 经常拿不到正确文件名，
     * 文件名识别会失败，必须用内容确认。
     *
     * 注意这里与 [openPjmContent] 共用 [detectContainerHeader]，因此**新旧算法的容器都能识别**；
     * 早先版本固定用 LEGACY_MASK 解魔数，会漏掉 0x03 容器。
     */
    suspend fun isPjmUri(
        context: Context,
        uri: Uri,
    ): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val header = ByteArray(HEADER_PEEK_SIZE)
                    var read = 0
                    while (read < header.size) {
                        val n = input.read(header, read, header.size - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < MAGIC_SIZE) return@use false
                    detectContainerHeader(header) != null
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
     * @param mode 密钥流取字节方式，必须与写入该数据时所用模式一致。
     * @return A wrapping [InputStream] that decrypts/transforms on the fly.
     */
    fun createXorStream(
        inputStream: InputStream,
        initialPos: Long = 0L,
        mode: KeyStreamMode = KeyStreamMode.MODULO,
    ): InputStream {
        return object : FilterInputStream(inputStream) {
            private var pos = initialPos

            // 核心修复：单字节读取与批量读取必须走【同一个】模式与位置计算路径
            // （transformBytesInPlace），与加密端 write(int)/write(byte[],off,len) 保持严格对称，
            // 否则混合读写时部分字节解不开。
            override fun read(): Int {
                val b = super.read()
                if (b == -1) return -1
                val tmp = byteArrayOf(b.toByte())
                transformBytesInPlace(tmp, 0, 1, pos, mode)
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
                    transformBytesInPlace(b, off, n, pos, mode)
                    pos += n.toLong()
                }
                return n
            }

            /**
             * 核心修复：必须覆写 skip()。
             *
             * `FilterInputStream.skip()` 是**直接委托**给底层流的，既不读取字节也不经过
             * `read()` —— 于是 `pos` 不会前进，而底层流的位置已经跳过了。之后任何一个
             * `read()` 都会用错误的密钥流位置解密，从跳过点起的所有字节全部解错。
             *
             * 这里退化为「读入后丢弃」，保证 pos 与底层位置始终同步。
             * 代价是跳过 N 字节需要真的读 N 字节，但正确性优先。
             */
            override fun skip(n: Long): Long {
                if (n <= 0) return 0
                val buf = ByteArray(minOf(n, SKIP_CHUNK).toInt())
                var remaining = n
                var skipped = 0L
                while (remaining > 0) {
                    val want = minOf(remaining, buf.size.toLong()).toInt()
                    val read = read(buf, 0, want)
                    if (read <= 0) break
                    skipped += read
                    remaining -= read
                }
                return skipped
            }
        }
    }

    /** skip() 的分块大小（1MB，与全局缓冲一致） */
    private const val SKIP_CHUNK = 1024L * 1024L
}
