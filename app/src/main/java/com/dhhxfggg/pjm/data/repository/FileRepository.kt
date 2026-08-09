package com.dhhxfggg.pjm.data.repository

import android.net.Uri
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

interface FileRepository {
    val allFiles: Flow<List<FileEntity>>
    val categoryCounts: Flow<Map<String, Int>>
    val categorySizes: Flow<Map<String, Long>>
    val totalSize: Flow<Long>

    fun getFilesByCategory(category: String): Flow<List<FileEntity>>
    
    suspend fun deleteFile(entity: FileEntity)
    
    suspend fun deleteFiles(entities: List<FileEntity>)
    
    // 允许传入进度监听，用于启动时的自动找回展示
    suspend fun initialize(onProgress: (Float) -> Unit = {})
    
    suspend fun syncDatabase()
    suspend fun exportVault(onProgress: (Float) -> Unit): Result<Unit>
    
    suspend fun getRandomFileByCategory(category: String): FileEntity?
    
    suspend fun storeFiles(
        uris: List<Uri>,
        password: String?,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onUnsupported: (Uri, String) -> Unit
    )
    
    suspend fun packAndEncrypt(uris: List<Uri>, onProgress: (Float) -> Unit): Result<Unit>
    
    suspend fun getNextVaultPath(category: String, originalName: String): File

    suspend fun extractPjmToVault(entity: FileEntity)

    suspend fun getFileByPath(relativePath: String): FileEntity?

    suspend fun performIntegrityCheck(onProgress: (Float) -> Unit): Map<String, List<FileEntity>>
}
