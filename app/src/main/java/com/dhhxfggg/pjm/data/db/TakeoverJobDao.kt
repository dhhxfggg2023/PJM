package com.dhhxfggg.pjm.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.dhhxfggg.pjm.data.model.TakeoverJobEntity
import kotlinx.coroutines.flow.Flow

/**
 * 接管任务记录的读写入口。
 *
 * 失败计数用 [observeCountByStatus]（设置页的失败提示行）；
 * 去重用 [findByUri]（ContentObserver 会对同一次保存重复回调）。
 */
@Dao
interface TakeoverJobDao {
    @Upsert
    suspend fun upsert(job: TakeoverJobEntity)

    @Query("SELECT * FROM takeover_jobs WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TakeoverJobEntity?

    /**
     * 一次把同一文件可能存在的多个幂等键都查掉。
     *
     * 媒体库通道拿到的是 `content://media/...`，目录深扫拿到的是 `file:///storage/...`，
     * 只按其中一个键查会让同一文件在另一条通道里「看起来是新的」——入库两份。
     * 走主键（uri 是 PRIMARY KEY），代价与 [findByUri] 相同。
     */
    @Query("SELECT * FROM takeover_jobs WHERE uri IN (:keys) LIMIT 1")
    suspend fun findByAnyUri(keys: List<String>): TakeoverJobEntity?

    /** 崩溃残留的 PENDING 记录：删掉后重新走完整入库流程 */
    @Query("DELETE FROM takeover_jobs WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    /**
     * 只改状态，保留 createdAt。
     *
     * 旧实现每条状态变更都用 [upsert] 重建整行 —— createdAt 被覆盖成「当前时间」，
     * 失败记录的 7 天清理与展示排序都会因此漂移。行必然已由 PENDING 插入过，
     * 否则影响 0 行（不报错）。
     */
    @Query("UPDATE takeover_jobs SET status = :status WHERE uri = :uri")
    suspend fun updateStatus(
        uri: String,
        status: String,
    )

    @Query("SELECT COUNT(*) FROM takeover_jobs WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    /**
     * 清掉早已收尾的记录，避免表随着时间无限增长。
     *
     * 失败态（IMPORT_FAILED / DELETE_FAILED）也只在 7 天内保留给用户看/重试，
     * 超过后同样清掉 —— 旧实现只清 IMPORTED，失败记录会永久占表。
     */
    @Query("DELETE FROM takeover_jobs WHERE status IN (:finishedStatuses) AND createdAt < :cutoff")
    suspend fun pruneFinished(
        finishedStatuses: List<String>,
        cutoff: Long,
    )

    /** 用户手动清掉失败记录（设置页的「清除失败记录」） */
    @Query("DELETE FROM takeover_jobs WHERE status IN (:statuses)")
    suspend fun clearByStatus(statuses: List<String>)
}
