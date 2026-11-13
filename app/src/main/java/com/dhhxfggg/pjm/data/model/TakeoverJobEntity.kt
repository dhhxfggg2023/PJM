package com.dhhxfggg.pjm.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 接管任务的落库记录（一次「某个媒体文件被接管」就是一行）。
 *
 * 为什么用 uri 当主键而不是自增 id：
 * MediaStore 的 ContentObserver 会对同一次保存回调多次（连拍/多选/相册刷新），
 * uri 作为主键让 [androidx.room.Upsert] 天然幂等 —— 同一个文件永远只有一行，
 * 不会出现「同一个文件被导入两遍」或「失败记录刷出一堆重复」。
 *
 * 为什么要落库而不是只放内存：
 * 1. 失败要能被用户看见 —— 设置页显示「有 N 个文件入库成功但原文件删不掉」，
 *    点失败通知还能回到删除确认流程。进程被系统杀掉后这些记录不能丢。
 * 2. 幂等去重的依据必须跨进程存活（见上一条）。
 *
 * @property uri       媒体文件的 content:// uri（主键）
 * @property status    见 [TakeoverStatus]
 * @property createdAt 首次登记时间（失败记录的展示与清理都按它排序）
 */
@Entity(tableName = "takeover_jobs")
data class TakeoverJobEntity(
    @PrimaryKey val uri: String,
    val status: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 接管任务状态。
 *
 * - [PENDING]        已登记、正在处理（进程被杀会留下这个状态，按失败计）
 * - [IMPORTED]       已入库且原文件已删除/移入回收站 —— 正常收尾
 * - [DELETE_FAILED]  已入库，但原文件既删不掉也移不进回收站（权限问题）→ 通知用户处理
 * - [IMPORT_FAILED]  入库失败，原文件保持不动（绝不删掉没进库的东西）
 */
object TakeoverStatus {
    const val PENDING = "pending"
    const val IMPORTED = "imported"
    const val DELETE_FAILED = "delete_failed"
    const val IMPORT_FAILED = "import_failed"
}
