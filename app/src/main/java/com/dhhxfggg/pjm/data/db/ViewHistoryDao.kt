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
    /** 标记为已看（重复则更新 viewedAt，幂等） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markViewed(entity: ViewHistoryEntity)

    /** 分类内"没看过"的 relativePath 列表（排除已有浏览记录） */
    @Query(
        """
        SELECT f.relativePath FROM files AS f
        WHERE f.category = :category
        AND f.relativePath NOT IN (SELECT relativePath FROM view_history)
        """,
    )
    suspend fun getUnviewedPathsByCategory(category: String): List<String>

    /**
     * 取 [since] 之后浏览过的 relativePath。
     *
     * 用途：统计/诊断"本轮看过多少"。**发现页的判定不依赖它** ——
     * "看过没有"一律以 [getUnviewedPathsByCategory] 为准（见 DiscoveryQueue 的类注释）。
     */
    @Query("SELECT relativePath FROM view_history WHERE viewedAt >= :since")
    suspend fun getViewedPathsSince(since: Long): List<String>

    /** 分类内"看过"的 relativePath（[getUnviewedPathsByCategory] 的补集） */
    @Query(
        """
        SELECT f.relativePath FROM files AS f
        WHERE f.category = :category
        AND f.relativePath IN (SELECT relativePath FROM view_history)
        """,
    )
    suspend fun getViewedPathsByCategory(category: String): List<String>

    /**
     * 清空**某分类**的浏览记录（发现页"看完一轮自动重来"用）。
     *
     * 只删该分类：其它分类的进度不受影响，否则看图片看到一半会把视频的进度一起清掉。
     */
    @Query(
        """
        DELETE FROM view_history
        WHERE relativePath IN (SELECT relativePath FROM files WHERE category = :category)
        """,
    )
    suspend fun clearByCategory(category: String)

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
