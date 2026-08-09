package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.net.Uri
import android.system.Os
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.PjmLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import java.util.zip.Deflater

/**
 * State of a long-running vault operation.
 */
data class OperationState(
    val progress: Float = 0f,
    val message: String = "",
    val isActive: Boolean = false,
    val isError: Boolean = false
)

/**
 * Result of a vault operation.
 */
sealed class OperationResult {
    data class Success(val action: String, val uris: List<Uri>) : OperationResult()
    data class Error(val action: String, val message: String) : OperationResult()
    data class PasswordRequired(val fileName: String, val uris: List<Uri>) : OperationResult()
}

/**
 * Core Vault Manager for the PJM project.
 * Orchestrates file ingestion, database synchronization, container export, and vault integrity.
 * Implements high-performance parallel processing and resource pooling.
 */
object VaultManager {
    private const val TAG = "VaultManager"
    private const val VAULT_ROOT = "pjm_vault"
    private val mutex = Mutex()
    private val XOR_KEY = CryptoUtils.getXorKey()
    
    private val UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*$", Pattern.CASE_INSENSITIVE)

    /** Adaptive buffer size based on hardware capabilities. */
    val ADAPTIVE_BUFFER_SIZE = if (Runtime.getRuntime().availableProcessors() >= 8) 1024 * 1024 else 256 * 1024
    
    /** Maximum parallel tasks for I/O operations. */
    val MAX_PARALLEL_TASKS = if (Runtime.getRuntime().availableProcessors() >= 8) 4 else 2
    
    // CATEGORY CONSTANTS
    const val CAT_PJM = "pjm"
    const val CAT_IMAGES = "images"
    const val CAT_VIDEOS = "videos"
    const val CAT_AUDIOS = "audios"
    const val CAT_OTHERS = "others"

    /** Standard categories for file organization. */
    val CATEGORIES = listOf(CAT_PJM, CAT_IMAGES, CAT_VIDEOS, CAT_AUDIOS, CAT_OTHERS)

    private val bufferPool = Collections.synchronizedList(LinkedList<ByteArray>())

    /** Acquires a byte array from the pool or creates a new one. */
    fun acquireBuffer(): ByteArray = synchronized(bufferPool) {
        if (bufferPool.isEmpty()) ByteArray(ADAPTIVE_BUFFER_SIZE) else bufferPool.removeAt(0)
    }

    /** Releases a byte array back to the pool. */
    fun releaseBuffer(buffer: ByteArray) {
        synchronized(bufferPool) {
            if (bufferPool.size < 16) bufferPool.add(buffer)
        }
    }

    private val _refreshSignal = MutableSharedFlow<Unit>(replay = 1)
    val refreshSignal = _refreshSignal.asSharedFlow()

    private val _operationState = MutableStateFlow(OperationState())
    val operationState = _operationState.asStateFlow()

    private val _operationResults = MutableSharedFlow<OperationResult>(extraBufferCapacity = 16)
    val operationResults = _operationResults.asSharedFlow()

    /** Updates the current operation progress. */
    fun updateProgress(progress: Float, message: String, isActive: Boolean = true, isError: Boolean = false) {
        _operationState.value = OperationState(progress.coerceIn(0f, 1f), message, isActive, isError)
    }

    /** Resets the progress state. */
    fun clearProgress() { _operationState.value = OperationState() }
    
    /** Emits a result notification. */
    fun notifyResult(result: OperationResult) { _operationResults.tryEmit(result) }
    
    /** Signals that the UI should refresh its file list. */
    fun triggerRefresh() { _refreshSignal.tryEmit(Unit) }

    /**
     * Performs a physical migration of files from legacy Chinese directory names 
     * to modern English internal keys.
     */
    suspend fun performPhysicalMigration(context: Context) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, VAULT_ROOT)
        if (!root.exists()) return@withContext
        
        PjmLogger.i(TAG, "Checking for legacy Chinese directories...")
        
        val migrationMap = mapOf(
            "PJM 归档" to "pjm",
            "相册照片" to "images",
            "视频影像" to "videos",
            "音乐音频" to "audios",
            "其它杂项" to "others",
            "文档日志" to "others",
            "documents" to "others",
            "audio" to "audios"
        )

        migrationMap.forEach { (oldName, newKey) ->
            val oldDir = File(root, oldName)
            if (oldDir.exists() && oldDir.isDirectory && oldName != newKey) {
                val newDir = getCategoryDir(context, newKey)
                PjmLogger.i(TAG, "Migrating $oldName -> $newKey")
                oldDir.listFiles()?.forEach { file ->
                    val targetFile = File(newDir, file.name)
                    if (!targetFile.exists()) {
                        file.renameTo(targetFile)
                    } else {
                        file.delete() 
                    }
                }
                oldDir.delete()
            }
        }
    }

    /** Returns the directory for a specific category, creating it if necessary. */
    fun getCategoryDir(context: Context, category: String): File = File(context.filesDir, "$VAULT_ROOT/$category").apply { if (!exists()) mkdirs() }
    
    /** Resolves the actual [File] object for a given [FileEntity]. */
    fun getFileFromEntity(context: Context, entity: FileEntity): File = File(File(context.filesDir, VAULT_ROOT), entity.relativePath)
    
    /** Converts a file path to a vault-relative path. */
    fun getRelativePath(context: Context, file: File): String = file.absolutePath.removePrefix(File(context.filesDir, VAULT_ROOT).absolutePath).trimStart(File.separatorChar)

    /** Generates a new unique vault path for an incoming file. */
    fun getNextVaultPath(context: Context, category: String, originalName: String): File {
        val dir = getCategoryDir(context, category)
        val ext = FileUtils.getFileExtension(originalName)
        return File(dir, "${UUID.randomUUID()}.$ext")
    }

    private fun fastDelete(file: File) {
        if (file.exists()) {
            if (!file.delete()) {
                PjmLogger.w(TAG, "Failed to delete file: ${file.absolutePath}")
            }
        }
    }

    /**
     * Digests an input stream into the vault and returns a [FileEntity].
     * Uses [Os.posix_fallocate] when possible to optimize disk allocation.
     */
    suspend fun digestFileToEntity(context: Context, fileName: String, inputStream: InputStream, expectedSize: Long = 0L): FileEntity = withContext(Dispatchers.IO) {
        val category = FileUtils.getCategory(fileName)
        val targetFile = getNextVaultPath(context, category, fileName)
        PjmLogger.i(TAG, "Digesting $fileName to vault...")
        try {
            FileOutputStream(targetFile).use { fos ->
                val outChannel = fos.channel
                if (expectedSize > 0) {
                    try { Os.posix_fallocate(fos.fd, 0, expectedSize) } catch (e: Exception) { 
                        PjmLogger.d(TAG, "fallocate not supported: ${e.message}")
                    }
                }
                
                var handled = false
                if (inputStream is FileInputStream) {
                    val inChannel = inputStream.channel
                    inChannel.transferTo(0, inChannel.size(), outChannel)
                    handled = true
                }
                
                if (!handled) {
                    val buffer = acquireBuffer()
                    try {
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outChannel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                        }
                    } finally { releaseBuffer(buffer) }
                }
                outChannel.force(false)
            }
            FileEntity(
                relativePath = getRelativePath(context, targetFile),
                name = fileName,
                size = targetFile.length(),
                category = category,
                lastModified = System.currentTimeMillis(),
                isImage = FileUtils.isImageFile(fileName),
                extension = FileUtils.getFileExtension(fileName),
                contentHash = null
            )
        } catch (e: Exception) { 
            PjmLogger.e(TAG, "Failed to digest file: $fileName", e)
            fastDelete(targetFile)
            throw e 
        }
    }

    /** Digests a file and inserts it into the database. */
    suspend fun digestFile(context: Context, fileName: String, inputStream: InputStream, fileDao: FileDao, silent: Boolean = false, expectedSize: Long = 0L) {
        val entity = digestFileToEntity(context, fileName, inputStream, expectedSize)
        fileDao.upsert(entity)
        if (!silent) triggerRefresh()
    }

    /** Extracts contents of a PJM container into the vault. */
    suspend fun extractPjmToVault(context: Context, entity: FileEntity, fileDao: FileDao) = withContext(Dispatchers.IO) {
        val file = getFileFromEntity(context, entity)
        PjmLogger.i(TAG, "Extracting PJM: ${entity.name}")
        CryptoUtils.decryptPjmToEntries(
            context = context,
            uris = listOf(Uri.fromFile(file)),
            onEntry = { name, input -> digestFile(context, name, input, fileDao, silent = true) }
        )
        triggerRefresh()
    }

    /** Deletes a single file from the vault and database. */
    suspend fun deleteFile(context: Context, relativePath: String, fileDao: FileDao) = withContext(Dispatchers.IO) {
        mutex.withLock { 
            val file = File(File(context.filesDir, VAULT_ROOT), relativePath)
            fastDelete(file)
            fileDao.deleteByRelativePath(relativePath)
            triggerRefresh() 
        }
    }

    /** Deletes multiple files in parallel. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun deleteFiles(context: Context, relativePaths: List<String>, fileDao: FileDao) = withContext(Dispatchers.IO) {
        if (relativePaths.isEmpty()) return@withContext
        PjmLogger.i(TAG, "Deleting ${relativePaths.size} files...")
        mutex.withLock {
            val root = File(context.filesDir, VAULT_ROOT)
            relativePaths.asFlow()
                .flatMapMerge(concurrency = MAX_PARALLEL_TASKS) { path ->
                    flow { fastDelete(File(root, path)); emit(Unit) }
                }
                .collect()
            fileDao.deleteByRelativePaths(relativePaths)
            triggerRefresh()
        }
    }

    /** Performs a full scan of the vault directory and synchronizes the database. */
    suspend fun fullSyncDatabase(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        PjmLogger.i(TAG, "Starting full database sync...")
        performPhysicalMigration(context) // Ensure folders are aligned
        
        mutex.withLock {
            val entities = mutableListOf<FileEntity>()
            val scanList = mutableListOf<Pair<File, String>>()
            
            CATEGORIES.forEach { cat -> 
                val dir = getCategoryDir(context, cat)
                val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".thumbnail") }
                PjmLogger.d(TAG, "Scanning folder [$cat]: ${dir.absolutePath}, found ${files?.size ?: 0} files")
                files?.forEach { scanList.add(it to cat) } 
            }
            
            if (scanList.isEmpty()) { 
                fileDao.clearAll()
                PjmLogger.i(TAG, "Vault is empty, database cleared.")
                return@withLock 
            }
            
            scanList.forEachIndexed { i, (f, c) ->
                if (f.length() > 0) {
                    val originalNameOnDisk = f.name
                    val oldRelativePath = getRelativePath(context, f)
                    var finalFile = f
                    
                    // If file is not yet renamed to UUID, rename it but keep original name for DB
                    if (!UUID_PATTERN.matcher(originalNameOnDisk).matches()) {
                        val ext = FileUtils.getFileExtension(originalNameOnDisk)
                        val newFile = File(f.parentFile, "${UUID.randomUUID()}.$ext")
                        if (f.renameTo(newFile)) {
                            finalFile = newFile
                            PjmLogger.d(TAG, "Renamed legacy file: $originalNameOnDisk -> ${newFile.name}")
                        }
                    }
                    
                    val newRelativePath = getRelativePath(context, finalFile)
                    val oldEntity = fileDao.findByRelativePath(oldRelativePath)
                    
                    // Priority for Name: 1. DB Record, 2. Original Disk Name (if not UUID), 3. Current Disk Name
                    val finalDisplayName = oldEntity?.name 
                        ?: if (!UUID_PATTERN.matcher(originalNameOnDisk).matches()) originalNameOnDisk 
                           else finalFile.name

                    entities.add(FileEntity(
                        relativePath = newRelativePath,
                        name = finalDisplayName,
                        size = finalFile.length(),
                        category = c,
                        lastModified = finalFile.lastModified(),
                        isImage = FileUtils.isImageFile(finalDisplayName),
                        extension = FileUtils.getFileExtension(finalDisplayName),
                        contentHash = oldEntity?.contentHash
                    ))
                }
                onProgress(i.toFloat() / scanList.size)
            }
            fileDao.replaceAll(entities)
            PjmLogger.i(TAG, "Sync complete. Indexed ${entities.size} files.")
            triggerRefresh()
        }
    }

    /**
     * Packs multiple Uris into encrypted PJM modules with volume splitting.
     * Uses atomic writing: files are first written to cacheDir and moved on success.
     */
    suspend fun packUrisWithSplitting(
        context: Context,
        uris: List<Uri>,
        category: String,
        baseName: String,
        fileDao: FileDao,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = SettingsManager.getSettingsFlow(context).first()
            val volumeSize = settings.exportSplitSize.toLong() * 1024 * 1024
            val totalSize = uris.sumOf { FileUtils.getFileSize(context, it) }.coerceAtLeast(1L)
            val written = AtomicLong(0)
            
            // Step 1: Prepare temp directory in cache
            val tempDir = File(context.cacheDir, "pjm_pack_${System.currentTimeMillis()}").apply { mkdirs() }
            val tempFiles = mutableListOf<File>()
            
            try {
                SplitXorOutputStream(context, category, volumeSize, XOR_KEY, baseName, tempDir) { tempFiles.add(it) }.use { xorOut ->
                    xorOut.write(ByteBuffer.allocate(4).putInt(CryptoUtils.FILE_MAGIC).array())
                    ZipArchiveOutputStream(xorOut).use { zos ->
                        zos.setUseZip64(Zip64Mode.AsNeeded)
                        zos.setLevel(Deflater.NO_COMPRESSION)
                        val buffer = acquireBuffer()
                        val usedNames = mutableSetOf<String>()
                        
                        try {
                            uris.forEach { uri ->
                                val originalName = FileUtils.getFileName(context, uri)
                                var entryName = originalName
                                var counter = 1
                                while (usedNames.contains(entryName)) {
                                    val ext = originalName.substringAfterLast('.', "")
                                    val base = originalName.substringBeforeLast('.')
                                    entryName = if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${originalName}_$counter"
                                    counter++
                                }
                                usedNames.add(entryName)
                                
                                zos.putArchiveEntry(ZipArchiveEntry(entryName))
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    var l: Int
                                    while (input.read(buffer).also { l = it } > 0) {
                                        zos.write(buffer, 0, l)
                                        onProgress(written.addAndGet(l.toLong()).toFloat() / totalSize)
                                    }
                                }
                                zos.closeArchiveEntry()
                            }
                            zos.finish()
                        } finally { releaseBuffer(buffer) }
                    }
                }
                
                // Step 2: Move files to final destination
                val finalDir = getCategoryDir(context, category)
                tempFiles.forEach { tempFile ->
                    val finalFile = File(finalDir, tempFile.name)
                    if (tempFile.renameTo(finalFile)) {
                        fileDao.upsert(FileEntity(
                            relativePath = getRelativePath(context, finalFile),
                            name = finalFile.name,
                            size = finalFile.length(),
                            category = category,
                            lastModified = finalFile.lastModified(),
                            isImage = false,
                            extension = "pjm",
                            contentHash = null
                        ))
                    } else {
                        throw Exception("Failed to move $tempFile to $finalFile")
                    }
                }
            } catch (e: Exception) {
                tempFiles.forEach { it.delete() }
                tempDir.deleteRecursively()
                throw e
            } finally {
                tempDir.deleteRecursively()
            }
            triggerRefresh()
        }
    }

    /**
     * Performs a full integrity check on the vault.
     * Verifies physical existence and matches content hashes if available.
     */
    suspend fun checkIntegrity(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit): Map<String, List<FileEntity>> = withContext(Dispatchers.IO) {
        val allFiles = fileDao.getAllFiles().first()
        val missing = mutableListOf<FileEntity>()
        val corrupted = mutableListOf<FileEntity>()
        
        allFiles.forEachIndexed { index, entity ->
            onProgress(index.toFloat() / allFiles.size)
            val file = getFileFromEntity(context, entity)
            if (!file.exists()) {
                missing.add(entity)
            } else if (entity.contentHash != null) {
                val currentHash = calculateHash(file)
                if (currentHash != entity.contentHash) {
                    corrupted.add(entity)
                }
            }
        }
        
        mapOf("missing" to missing, "corrupted" to corrupted)
    }

    /** Exports non-container vault contents into encrypted PJM modules. */
    suspend fun exportVaultToPjmModule(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        PjmLogger.i(TAG, "Exporting vault to PJM modules...")
        mutex.withLock {
            runCatching {
                val settings = SettingsManager.getSettingsFlow(context).first()
                val volumeSize = settings.exportSplitSize.toLong() * 1024 * 1024 
                val vaultRoot = File(context.filesDir, VAULT_ROOT)
                val allFiles = vaultRoot.walkTopDown()
                    .filter { it.isFile && !it.absolutePath.contains("/pjm/") }
                    .toList()
                
                if (allFiles.isEmpty()) throw Exception("Vault is empty")
                
                val totalBytes = allFiles.sumOf { it.length() }.coerceAtLeast(1L)
                val written = AtomicLong(0L)
                val created = mutableListOf<File>()
                
                SplitXorOutputStream(context, "pjm", volumeSize, XOR_KEY, null, null) { created.add(it) }.use { xorOut ->
                    xorOut.write(ByteBuffer.allocate(4).putInt(CryptoUtils.FILE_MAGIC).array())
                    ZipArchiveOutputStream(xorOut).use { zos ->
                        zos.setUseZip64(Zip64Mode.AsNeeded) 
                        zos.setLevel(Deflater.NO_COMPRESSION)
                        val buffer = acquireBuffer()
                        try {
                            allFiles.forEach { file ->
                                val entity = fileDao.findByRelativePath(getRelativePath(context, file))
                                val entryName = entity?.name ?: file.name
                                zos.putArchiveEntry(ZipArchiveEntry("${file.parentFile?.name}/$entryName"))
                                FileInputStream(file).use { fis ->
                                    var l: Int
                                    while (fis.read(buffer).also { l = it } > 0) {
                                        zos.write(buffer, 0, l)
                                        onProgress(written.addAndGet(l.toLong()).toFloat() / totalBytes)
                                    }
                                }
                                zos.closeArchiveEntry()
                            }
                            zos.finish()
                        } finally { releaseBuffer(buffer) }
                    }
                }
                
                created.forEach { f -> 
                    fileDao.upsert(FileEntity(getRelativePath(context, f), f.name, f.length(), "pjm", f.lastModified(), false, "pjm", null)) 
                }
                PjmLogger.i(TAG, "Export successful. Created ${created.size} modules.")
                triggerRefresh()
            }
        }
    }
    
    /** Finds duplicate files in the vault using MD5 hashing. */
    suspend fun findDuplicateFiles(context: Context, fileDao: FileDao, onProgress: (Float) -> Unit = {}): List<FileEntity> = withContext(Dispatchers.IO) {
        val allFiles = fileDao.getAllFiles().first()
        val groupsBySize = allFiles.groupBy { it.size }
        val suspects = groupsBySize.filter { it.value.size > 1 }.values.flatten()
        val duplicates = mutableListOf<FileEntity>()
        
        if (suspects.isEmpty()) return@withContext emptyList()
        
        suspects.forEachIndexed { i, entity ->
            onProgress(i.toFloat() / suspects.size)
            val finalHash = entity.contentHash ?: calculateHash(getFileFromEntity(context, entity))
            if (finalHash != entity.contentHash) { 
                fileDao.upsert(entity.copy(contentHash = finalHash)) 
            }
        }
        
        val finalFiles = fileDao.getAllFiles().first()
        val hashGroups = finalFiles.filter { it.contentHash != null }.groupBy { it.contentHash!! }
        hashGroups.forEach { (_, entities) -> 
            if (entities.size > 1) {
                duplicates.addAll(entities.sortedBy { it.lastModified }.drop(1)) 
            }
        }
        duplicates
    }

    private fun calculateHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = acquireBuffer()
            try {
                FileInputStream(file).use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) { 
                        digest.update(buffer, 0, bytesRead) 
                    }
                }
            } finally { releaseBuffer(buffer) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { 
            PjmLogger.e(TAG, "Hash calculation failed for ${file.name}", e)
            null 
        }
    }
}

/**
 * Custom OutputStream that performs XOR encryption and splits data into multi-part volumes.
 */
private class SplitXorOutputStream(
    private val context: Context, 
    private val category: String, 
    private val volumeLimit: Long, 
    private val xorKey: ByteArray, 
    private val baseFileNameInput: String? = null,
    private val overrideDir: File? = null,
    private val onVolumeCreated: (File) -> Unit
) : OutputStream() {
    private var volumeIndex = 1
    private var volumeWritten = 0L
    private var totalPos = 0L
    private var currentOut: OutputStream? = null
    private var baseFileName: String? = baseFileNameInput
    private var xorWorkBuffer: ByteArray? = null
    private var isClosed = false

    private fun ensureVolume() {
        if (currentOut != null && volumeWritten < volumeLimit) return
        currentOut?.flush()
        currentOut?.close()
        if (baseFileName == null) baseFileName = "Export_${System.currentTimeMillis()}"
        val dir = overrideDir ?: VaultManager.getCategoryDir(context, category)
        val file = File(dir, "${baseFileName}.pjm.${volumeIndex}")
        currentOut = BufferedOutputStream(FileOutputStream(file), VaultManager.ADAPTIVE_BUFFER_SIZE)
        onVolumeCreated(file)
        volumeIndex++
        volumeWritten = 0
    }

    override fun write(b: Int) {
        if (isClosed) return
        ensureVolume()
        currentOut?.write((b xor xorKey[(totalPos and 31L).toInt()].toInt()) and 0xFF)
        volumeWritten++
        totalPos++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (isClosed) return
        if (xorWorkBuffer == null) xorWorkBuffer = VaultManager.acquireBuffer()
        val workBuf = xorWorkBuffer!!
        var rem = len
        var curOff = off
        while (rem > 0) {
            ensureVolume()
            val toWrite = minOf(rem.toLong(), volumeLimit - volumeWritten, workBuf.size.toLong()).toInt()
            for (i in 0 until toWrite) { 
                workBuf[i] = (b[curOff + i].toInt() xor xorKey[((totalPos + i) and 31L).toInt()].toInt()).toByte() 
            }
            currentOut?.write(workBuf, 0, toWrite)
            volumeWritten += toWrite
            totalPos += toWrite
            curOff += toWrite
            rem -= toWrite
        }
    }

    override fun flush() { if (!isClosed) currentOut?.flush() }
    override fun close() {
        if (isClosed) return
        isClosed = true
        xorWorkBuffer?.let { VaultManager.releaseBuffer(it); xorWorkBuffer = null }
        try { currentOut?.flush(); currentOut?.close() } finally { currentOut = null }
    }
}
