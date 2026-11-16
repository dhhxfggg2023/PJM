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
     * 按天聚合入库数量，给首页的热力图用。
     *
     * importedAt 为 0 的老记录退回 lastModified；按本地时区取日期。
     */
    @Query(
        "SELECT date(COALESCE(NULLIF(importedAt, 0), lastModified) / 1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count FROM files GROUP BY day ORDER BY day",
    )
    suspend fun getImportCountsByDay(): List<ImportDayCount>

    /**
     * 库里出现过的「入库日期」（本地时区，yyyy-MM-dd），新的在前。
     * 「发到电脑」按日期挑选时用它列出可选日期，而不是固定给「今天/近 7 天」。
     */
    @Query(
        "SELECT DISTINCT date(COALESCE(NULLIF(importedAt, 0), lastModified) / 1000, 'unixepoch', 'localtime') AS day " +
            "FROM files ORDER BY day DESC LIMIT :limit",
    )
    suspend fun getRecentDates(limit: Int): List<String>

    /**
     * Retrieves all files within a specific category, ordered by modification date descending.
     * @param category The folder/category name to filter by.
     * @return A [Flow] emitting the list of files whenever the database changes.
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY lastModified DESC, id DESC")
    fun getFilesByCategory(category: String): Flow<List<FileEntity>>

    /** 一次性读取（迁移/批处理用，不走 Flow） */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY lastModified DESC, id DESC")
    suspend fun getFilesByCategoryOnce(category: String): List<FileEntity>

    /**
     * 分类 + 单个文件大小区间（左闭右开）的列表流，供热力图「大小分布」点某一档后查看。
     *
     * 排序与 [getFilesByCategory] 严格一致：列表页按 lastModified 分日期表头，
     * 任何排序差异都会让筛选后的列表看起来「顺序乱了」。
     *
     * 上界用 [Long.MAX_VALUE] 表示「无上限」（最后一档 X 以上）：Room 的 Long 形参不接受 null，
     * 用哨兵值比再拆一个「只有下界」的重载更省，而且 size < MAX_VALUE 恒真，语义不变。
     *
     * size 上没有索引（不新增索引；已有 index_files_category_id 先把范围收敛到单个分类），
     * 分类内的行数在万级以下，走 WHERE 过滤完全够用 —— 因此这里不退回内存过滤，
     * 免得把整分类实体读进内存再筛。
     */
    @Query("SELECT * FROM files WHERE category = :category AND size >= :minSize AND size < :maxSize ORDER BY lastModified DESC, id DESC")
    fun getFilesByCategoryAndSizeRange(
        category: String,
        minSize: Long,
        maxSize: Long,
    ): Flow<List<FileEntity>>

    /** 只改库内显示名；磁盘文件名（relativePath）保持不变 */
    @Query("UPDATE files SET name = :name WHERE relativePath = :relativePath")
    suspend fun updateDisplayName(
        relativePath: String,
        name: String,
    )

    /** 只改分类标签，不动磁盘路径（relativePath 才是定位依据） */
    @Query("UPDATE files SET category = :category WHERE relativePath = :relativePath")
    suspend fun updateCategory(
        relativePath: String,
        category: String,
    )

    /**
     * Retrieves every file stored in the vault database.
     * @return A [Flow] of all [FileEntity] objects.
     */
    @Query("SELECT * FROM files ORDER BY lastModified DESC, id DESC")
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
    suspend fun isDuplicate(
        name: String,
        size: Long,
    ): Boolean

    /**
     * Finds an existing file with the same name and size (candidate for content comparison).
     * Used to verify real duplicates via content hash before skipping ingestion.
     */
    @Query("SELECT * FROM files WHERE name = :name AND size = :size LIMIT 1")
    suspend fun findDuplicateCandidate(
        name: String,
        size: Long,
    ): FileEntity?

    /**
     * Deletes a specific file record by its path.
     */
    @Query("DELETE FROM files WHERE relativePath = :relativePath")
    suspend fun deleteByRelativePath(relativePath: String)

    @Query("DELETE FROM files WHERE relativePath IN (:paths)")
    suspend fun _internalDeleteByRelativePaths(paths: List<String>)

    @Query("UPDATE files SET relativePath = :newPath, name = :newName WHERE relativePath = :oldPath")
    suspend fun _internalUpdateRelativePath(
        oldPath: String,
        newPath: String,
        newName: String,
    ): Int

    /**
     * 单事务重命名：把一条记录的 relativePath/name 迁移到新值（其余字段保持不变）。
     *
     * 核心修复：命名迁移原来用 `deleteByRelativePath(old)` + `upsert(new)` 两条独立语句，
     * 中间进程被杀就会变成「磁盘文件已改名，数据库却一行都没有」—— UI 里完全看不到，
     * 必须手动触发全量同步才能找回。
     *
     * 目标路径若已存在陈旧记录，先移除再更新，避免 `relativePath` 的唯一索引冲突
     * （旧实现用 `@Upsert` 的 REPLACE 语义，同样会覆盖，这里保持一致但保证原子）。
     *
     * @return 实际被更新的行数（0 表示原本就没有该路径的记录，不算失败）
     */
    @Transaction
    suspend fun renameRelativePath(
        oldPath: String,
        newPath: String,
        newName: String,
    ): Int {
        _internalDeleteByRelativePaths(listOf(newPath))
        return _internalUpdateRelativePath(oldPath, newPath, newName)
    }

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
    fun getCategoryCountsFlow(): Flow<
        Map<
            @MapColumn(columnName = "category")
            String,
            @MapColumn(columnName = "count")
            Int,
        >,
    >

    /**
     * Provides a map of categories to their total storage size in bytes.
     */
    @Query("SELECT category, SUM(size) as totalSize FROM files GROUP BY category")
    fun getCategorySizesFlow(): Flow<
        Map<
            @MapColumn(columnName = "category")
            String,
            @MapColumn(columnName = "totalSize")
            Long,
        >,
    >

    /**
     * Synchronous count for a specific category.
     */
    @Query("SELECT size FROM files WHERE category = :category")
    suspend fun getSizesByCategory(category: String): List<Long>

    @Query("SELECT COUNT(*) FROM files WHERE category = :category")
    suspend fun getCountByCategory(category: String): Int

    /** 总文件数：启动时判断库是否为空（避免把 4 万条实体全读进内存） */
    @Query("SELECT COUNT(*) FROM files")
    suspend fun getTotalFileCount(): Long

    /**
     * Real-time aggregate of total vault size.
     */
    @Query("SELECT SUM(size) FROM files")
    fun getTotalSizeFlow(): Flow<Long?>

    /**
     * 按分类内第 N 条（id 升序）取一批：由调用方生成「随机 offset 集合」后逐个取。
     *
     * 旧实现 `ORDER BY RANDOM()` 会对整个分类全表排序 + 建临时表，
     * 4 万行时每次「随机图片」都卡数秒；且索引完全用不上。
     * 现在复用 [getCountByCategory] + [getFileByOffset] 的 O(log n) 路径，
     * 由调用方负责去重与不足量回退。
     */
    @Query("SELECT * FROM files WHERE category = :category ORDER BY id LIMIT 1 OFFSET :offset")
    suspend fun getFileByOffset(
        category: String,
        offset: Int,
    ): FileEntity?

    /**
     * 随机抽取用的键集游标：取分类内 id >= [startId] 的第一条。
     *
     * 大库优化：没有 `(category, id)` 复合索引时，`ORDER BY id LIMIT 1 OFFSET n`
     * 要沿分类索引逐条跳过 n 条（一次抽 50 张 = 50 次分类扫描）；
     * 改成 `id >= :startId` 后可以从 (category, id) 位置直接 seek，O(log n)。
     */
    @Query("SELECT * FROM files WHERE category = :category AND id >= :startId ORDER BY id LIMIT 1")
    suspend fun getFirstFileAtOrAfterId(
        category: String,
        startId: Long,
    ): FileEntity?

    /** 分类内最小 id（随机起点的下界 + 游标到尾部时的绕回） */
    @Query("SELECT MIN(id) FROM files WHERE category = :category")
    suspend fun getMinIdByCategory(category: String): Long?

    /** 分类内最大 id（随机起点的上界） */
    @Query("SELECT MAX(id) FROM files WHERE category = :category")
    suspend fun getMaxIdByCategory(category: String): Long?

    /**
     * 大库优化：基于主键游标的分页查询（按 id 顺序取页），
     * 避免 ORDER BY RANDOM() 在全表排序（大库时极慢）。
     * 用于发现页"已看完全部后"的翻页浏览。
     */
    @Query("SELECT * FROM files WHERE category = :category AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getFilesByCategoryPage(
        category: String,
        afterId: Long,
        limit: Int,
    ): List<FileEntity>

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

    /**
     * 按分类整体替换：只删该分类的旧行，再插入新行（单事务）。
     *
     * 全量同步旧实现用 [replaceAll]（clearAll + 插入全库）——一次「修复/找回文件」要重写
     * 4 万行，且任何分类读取失败都会连带清掉其它分类。改成按分类替换后，每个分类各自原子，
     * 未参与本次同步的分类一行都不动。
     */
    @Transaction
    suspend fun replaceCategory(
        category: String,
        entities: List<FileEntity>,
    ) {
        _internalDeleteByCategory(category)
        upsertAll(entities)
    }

    @Query("DELETE FROM files WHERE category = :category")
    suspend fun _internalDeleteByCategory(category: String)
}
