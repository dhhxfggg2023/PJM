package com.dhhxfggg.pjm.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dhhxfggg.pjm.data.model.ViewHistoryEntity

/**
 * 浏览历史 DAO：供发现页实现"没看过的优先"。
 */
@Dao
interface ViewHistoryDao {
    /** 单个标记为已看（重复则更新 viewedAt） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markViewed(entity: ViewHistoryEntity)

    /** 批量标记为已看 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markViewedAll(entities: List<ViewHistoryEntity>)

    /** 分类内"没看过"的 relativePath 列表（排除已有浏览记录） */
    @Query(
        """
        SELECT f.relativePath FROM files AS f
        WHERE f.category = :category
        AND f.relativePath NOT IN (SELECT relativePath FROM view_history)
        """,
    )
    suspend fun getUnviewedPathsByCategory(category: String): List<String>

    /** 分类内总记录数 */
    @Query("SELECT COUNT(*) FROM files WHERE category = :category")
    suspend fun getCategoryCount(category: String): Int

    /** 已看记录总数（用于"全部看完"判定，可选） */
    @Query("SELECT COUNT(*) FROM view_history")
    suspend fun getViewedCount(): Int

    /** 清空全部浏览历史（"重新来过"用） */
    @Query("DELETE FROM view_history")
    suspend fun clearAll()

    /** 清理已不存在文件的浏览记录（源文件删除后调用，避免历史表膨胀） */
    @Query("DELETE FROM view_history WHERE relativePath NOT IN (SELECT relativePath FROM files)")
    suspend fun purgeOrphans()
}
