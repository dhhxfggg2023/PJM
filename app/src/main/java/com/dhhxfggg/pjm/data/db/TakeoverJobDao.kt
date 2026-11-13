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

    @Query("SELECT COUNT(*) FROM takeover_jobs WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    /** 清掉早已收尾的记录，避免表随着时间无限增长（失败的记录不在此列，要留给用户看） */
    @Query("DELETE FROM takeover_jobs WHERE status IN (:finishedStatuses) AND createdAt < :cutoff")
    suspend fun pruneFinished(
        finishedStatuses: List<String>,
        cutoff: Long,
    )

    /** 用户手动清掉失败记录（设置页的「清除失败记录」） */
    @Query("DELETE FROM takeover_jobs WHERE status IN (:statuses)")
    suspend fun clearByStatus(statuses: List<String>)
}
