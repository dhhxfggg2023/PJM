package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.SevenZipUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SevenZipUtils 解压结果归类的纯逻辑测试。
 *
 * 锁定 S2-2：无法从 URI 打开输入流（[SevenZipUtils.NotAnArchiveException]）
 * 必须被归类为 NOT_AN_ARCHIVE —— 旧实现会让空临时文件继续走 junrar，
 * 产生误导性异常甚至被当成「解压失败」向用户报错。
 */
class SevenZipOutcomeTest {
    @Test
    fun unopenableInputStreamIsClassifiedAsNotAnArchive() {
        val e = SevenZipUtils.NotAnArchiveException("Cannot open input stream for a.rar")
        assertTrue(
            "打不开输入流应归类为 NOT_AN_ARCHIVE，让调用方回退普通入库",
            SevenZipUtils.isNotArchiveError(e),
        )
    }

    @Test
    fun realIoFailureIsNotSilentlyDowngraded() {
        assertFalse(
            "真正的 IO 失败不得被静默降级为「不是压缩包」",
            SevenZipUtils.isNotArchiveError(java.io.IOException("disk failure")),
        )
    }

    @Test
    fun existingNotArchiveSignalsStillRecognized() {
        assertTrue(SevenZipUtils.isNotArchiveError(java.util.zip.ZipException("bad zip")))
        assertTrue(SevenZipUtils.isNotArchiveError(java.io.EOFException()))
        assertTrue(SevenZipUtils.isNotArchiveError(IllegalArgumentException("bad signature")))
    }
}
