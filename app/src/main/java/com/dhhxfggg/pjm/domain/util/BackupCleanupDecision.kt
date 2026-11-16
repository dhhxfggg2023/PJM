package com.dhhxfggg.pjm.domain.util

/**
 * 应用安装包备份的失败清理判定（纯 Kotlin，不依赖 Context / Room）。
 *
 * 为什么单独抽出来：这里的两条规则靠读代码很难一眼看全 —— 普通失败后要 shred 半截文件，
 * 但取消异常在数据库已经提交后才到达时，那个文件已经是合法入库产物，再 shred 就会
 * 留下「有行、无文件」的幽灵条目。把判定做成纯函数后，回归测试无需任何 Android 模拟。
 *
 * 普通分支统一尝试清理：复制过程自己会清临时文件，这里只负责删掉可能已经 rename 出来的目标。
 */
enum class BackupCleanupDecision {
    /** 什么都不删：文件已提交入库，或者压根没有写过文件 */
    NOTHING,

    /** 删掉本次复制残留的目标文件（半截 APK / 未入库的完整 APK） */
    SHRED_TARGET,
}

/**
 * 普通失败（IO / 空间不足 / 建实体失败）后的清理决策。
 *
 * [targetExists] 由调用方检查；纯函数不碰文件系统。
 */
fun decideFailureCleanup(targetExists: Boolean): BackupCleanupDecision = if (targetExists) BackupCleanupDecision.SHRED_TARGET else BackupCleanupDecision.NOTHING

/**
 * 取消时的清理决策。
 *
 * @param committed 数据库写入是否已经提交；提交之后到达的取消信号不能删文件。
 * @param targetExists 目标文件是否存在；不存在时没有东西可删。
 */
fun decideCancellationCleanup(
    committed: Boolean,
    targetExists: Boolean,
): BackupCleanupDecision = if (!committed && targetExists) BackupCleanupDecision.SHRED_TARGET else BackupCleanupDecision.NOTHING
