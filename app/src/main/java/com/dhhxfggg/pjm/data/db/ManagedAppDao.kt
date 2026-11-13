package com.dhhxfggg.pjm.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.dhhxfggg.pjm.data.model.ManagedAppEntity
import kotlinx.coroutines.flow.Flow

/**
 * 被接管应用的读写入口。
 *
 * 读操作有两种形态：
 * - [observeAll] 给设置页用（列表随增删实时刷新）
 * - [getAll] 给后台接管服务用（每次扫描前取一次快照，不做常驻订阅）
 *   服务需要 addedAt 做「只接管加入之后新保存的文件」的判定，所以取整行而不是只取包名
 */
@Dao
interface ManagedAppDao {
    @Query("SELECT * FROM managed_apps ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<ManagedAppEntity>>

    @Query("SELECT * FROM managed_apps ORDER BY addedAt ASC")
    suspend fun getAll(): List<ManagedAppEntity>

    @Query("SELECT COUNT(*) FROM managed_apps")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(app: ManagedAppEntity)

    @Query("DELETE FROM managed_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
