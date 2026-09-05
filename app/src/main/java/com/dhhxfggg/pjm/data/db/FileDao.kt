package com.dhhxfggg.pjm.data.db

import androidx.room.*
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for PJM Vault files.
 * Provides modern Room integration with support for large-scale operations
 * and real-time data flow using Coroutines and Flow.
 *
 * @author PJM Industrial Standards 2026
 */
@Dao
interface FileDao {

    /**
     * Retrieves all files within a specific category, ordered by modification date descending.
     * @param category The folder/category name to filter by.
     * @return A [Flow] emitting the list of files whenever the database changes.
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY lastModified DESC")
    fun getFilesByCategory(category: String): Flow<List<FileEntity>>

    /**
     * Retrieves every file stored in the vault database.
     * @return A [Flow] of all [FileEntity] objects.
     */
    @Query("SELECT * FROM files ORDER BY lastModified DESC")
    fun getAllFiles(): Flow<List<FileEntity>>

    /**
     * Inserts or updates multiple files in a single transaction.
     * Uses [Upsert] introduced in Room 2.5 for efficient conflict resolution.
     */
    @Upsert
    suspend fun upsertAll(files: List<FileEntity>)

    /**
     * Inserts or updates a single file.
     */
    @Upsert
    suspend fun upsert(file: FileEntity)

    /**
     * Finds a file by its unique relative path.
     * @param relativePath The path relative to the vault root.
     * @return The [FileEntity] if found, null otherwise.
     */
    @Query("SELECT * FROM files WHERE relativePath = :relativePath LIMIT 1")
    suspend fun findByRelativePath(relativePath: String): FileEntity?

    /**
     * Checks if a file with the same name and size already exists.
     * Used for smart duplicate detection.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM files WHERE name = :name AND size = :size)")
    suspend fun isDuplicate(name: String, size: Long): Boolean

    /**
     * Finds an existing file with the same name and size (candidate for content comparison).
     * Used to verify real duplicates via content hash before skipping ingestion.
     */
    @Query("SELECT * FROM files WHERE name = :name AND size = :size LIMIT 1")
    suspend fun findDuplicateCandidate(name: String, size: Long): FileEntity?

    /**
     * Deletes a specific file record by its path.
     */
    @Query("DELETE FROM files WHERE relativePath = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM files WHERE relativePath IN (:paths)")
    suspend fun _internalDeleteByRelativePaths(paths: List<String>)

    /**
     * Batch deletion of files with SQLite variable limit handling (chunking).
     */
    @Transaction
    suspend fun deleteByRelativePaths(paths: List<String>) {
        paths.chunked(900).forEach { chunk ->
            _internalDeleteByRelativePaths(chunk)
        }
    }

    /**
     * Wipes the entire database.
     */
    @Query("DELETE FROM files")
    suspend fun clearAll()

    /**
     * Provides a map of categories to their respective file counts.
     */
    @Query("SELECT category, COUNT(*) as count FROM files GROUP BY category")
    fun getCategoryCountsFlow(): Flow<Map<@MapColumn(columnName = "category") String, @MapColumn(columnName = "count") Int>>

    /**
     * Provides a map of categories to their total storage size in bytes.
     */
    @Query("SELECT category, SUM(size) as totalSize FROM files GROUP BY category")
    fun getCategorySizesFlow(): Flow<Map<@MapColumn(columnName = "category") String, @MapColumn(columnName = "totalSize") Long>>

    /**
     * Synchronous count for a specific category.
     */
    @Query("SELECT COUNT(*) FROM files WHERE category = :category")
    suspend fun getCountByCategory(category: String): Int

    /**
     * Real-time aggregate of total vault size.
     */
    @Query("SELECT SUM(size) FROM files")
    fun getTotalSizeFlow(): Flow<Long?>

    /**
     * Picks a random file from a category, useful for "Shuffle" features.
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomFileByCategory(category: String): FileEntity?

    /**
     * Retrieves a set of random files from a category.
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomFilesByCategory(category: String, limit: Int): List<FileEntity>

    /**
     * Retrieves random files while excluding specific paths.
     */
    @Query("SELECT * FROM files WHERE category = :category AND relativePath NOT IN (:excludePaths) ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomFilesByCategoryExcluding(category: String, excludePaths: List<String>, limit: Int): List<FileEntity>

    /**
     * 大库优化：基于主键游标的分页查询（按 id 顺序取页），
     * 避免 ORDER BY RANDOM() 在全表排序（50GB/万级记录时极慢）。
     * 用于发现页"已看完全部后"的翻页浏览。
     */
    @Query("SELECT * FROM files WHERE category = :category AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getFilesByCategoryPage(category: String, afterId: Long, limit: Int): List<FileEntity>

    /**
     * 获取分类内最大 id（用于分页游标起点判断）。
     */
    @Query("SELECT MAX(id) FROM files WHERE category = :category")
    suspend fun getMaxIdByCategory(category: String): Long?

    /**
     * 大库优化：取分类内最近导入的一个文件作为封面（走 lastModified 索引，O(log n)），
     * 替代 ORDER BY RANDOM()（全表排序，万级记录时卡顿）。
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY id DESC LIMIT 1")
    suspend fun getLatestFileByCategory(category: String): FileEntity?

    /**
     * Retrieves all relative paths for a category.
     */
    @Query("SELECT relativePath FROM files WHERE category = :category")
    suspend fun getAllPathsByCategory(category: String): List<String>

    /**
     * Retrieves file entities for a given list of relative paths.
     */
    @Query("SELECT * FROM files WHERE relativePath IN (:paths)")
    suspend fun getFilesByPaths(paths: List<String>): List<FileEntity>

    /**
     * Replaces all database content with a new set of entities in a single atomic transaction.
     */
    @Transaction
    suspend fun replaceAll(entities: List<FileEntity>) {
        clearAll()
        upsertAll(entities)
    }
}
