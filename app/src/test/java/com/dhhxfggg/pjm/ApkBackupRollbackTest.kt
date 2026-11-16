package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.BackupCleanupDecision
import com.dhhxfggg.pjm.domain.util.decideCancellationCleanup
import com.dhhxfggg.pjm.domain.util.decideFailureCleanup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 安装包备份的失败清理判定测试（纯逻辑，不碰 Context / Room / 文件系统）。
 *
 * 回归点：取消异常在数据库提交之后到达时，目标文件已经是合法入库产物，
 * 此时若按普通失败处理去 shred，会留下「库里有行、磁盘无文件」的幽灵条目。
 */
class ApkBackupRollbackTest {
    @Test
    fun failureWithTargetShredsHalfCopiedFile() {
        assertEquals(BackupCleanupDecision.SHRED_TARGET, decideFailureCleanup(targetExists = true))
    }

    @Test
    fun failureWithoutTargetDoesNothing() {
        assertEquals(BackupCleanupDecision.NOTHING, decideFailureCleanup(targetExists = false))
    }

    @Test
    fun cancellationBeforeCommitShredsTarget() {
        assertEquals(
            BackupCleanupDecision.SHRED_TARGET,
            decideCancellationCleanup(committed = false, targetExists = true),
        )
    }

    @Test
    fun cancellationBeforeCommitWithoutTargetDoesNothing() {
        assertEquals(
            BackupCleanupDecision.NOTHING,
            decideCancellationCleanup(committed = false, targetExists = false),
        )
    }

    @Test
    fun cancellationAfterCommitKeepsTarget() {
        // 关键回归：DB 已提交时即便收到取消也不能删文件
        assertEquals(
            BackupCleanupDecision.NOTHING,
            decideCancellationCleanup(committed = true, targetExists = true),
        )
    }

    @Test
    fun cancellationAfterCommitWithoutTargetDoesNothing() {
        assertEquals(
            BackupCleanupDecision.NOTHING,
            decideCancellationCleanup(committed = true, targetExists = false),
        )
    }
}
