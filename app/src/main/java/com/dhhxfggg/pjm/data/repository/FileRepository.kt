package com.dhhxfggg.pjm.data.repository

import android.net.Uri
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.IngestionSummary
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

    /** 只改库内显示名（磁盘上的 UUID 文件名不动） */
    suspend fun updateDisplayName(
        relativePath: String,
        name: String,
    )

    suspend fun getRandomFileByCategory(category: String): FileEntity?

    /**
     * 取一张封面。
     *
     * @param daySeed 非空时按「日期种子」固定挑选（同一天封面不变，实现"每日一换"）；
     *                为空时就是原来的随机挑。
     */
    suspend fun getCoverFile(
        category: String,
        daySeed: Int?,
    ): FileEntity?

    /** 库内文件总数（启动判断用，不加载实体） */
    suspend fun totalFileCount(): Long

    /** 大库优化：取分类最近文件作封面（替代 RANDOM，O(log n)） */
    suspend fun getLatestFileByCategory(category: String): FileEntity?

    suspend fun storeFiles(
        uris: List<Uri>,
        password: String?,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onUnsupported: (Uri, String) -> Unit,
    ): IngestionSummary

    suspend fun packAndEncrypt(
        uris: List<Uri>,
        onProgress: (Float) -> Unit,
    ): Result<Int>

    suspend fun getNextVaultPath(
        category: String,
        originalName: String,
    ): File

    suspend fun extractPjmToVault(entity: FileEntity)

    suspend fun getFileByPath(relativePath: String): FileEntity?

    suspend fun performIntegrityCheck(onProgress: (Float) -> Unit): Map<String, List<FileEntity>>
}
