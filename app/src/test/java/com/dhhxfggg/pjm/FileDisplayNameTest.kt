package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命名规则回归：图片统一为 PJM_入库时间，其它类型保留原始文件名。
 *
 * 这条规则是用户明确要求的（图片不暴露原名、其它类型方便辨认），
 * 改动 normalize 时很容易顺手把所有类型都改掉，用测试钉住。
 */
class FileDisplayNameTest {
    private fun entity(
        name: String,
        extension: String,
        isImage: Boolean,
    ) = FileEntity(
        relativePath = "images/uuid.bin",
        name = name,
        size = 1,
        category = if (isImage) "images" else "others",
        lastModified = 1_700_000_000_000L,
        isImage = isImage,
        extension = extension,
        contentHash = null,
    )

    @Test
    fun imageNameIsNormalizedToImportTime() {
        val display = FileUtils.normalizedDisplayName(entity("IMG_20260101_123456.jpg", "jpg", isImage = true))
        assertTrue("图片应统一为 PJM_ 前缀: $display", display.startsWith("PJM_"))
        assertTrue("扩展名保留: $display", display.endsWith(".jpg"))
    }

    @Test
    fun nonImageKeepsOriginalName() {
        assertEquals(
            "笔记.pdf",
            FileUtils.normalizedDisplayName(entity("笔记.pdf", "pdf", isImage = false)),
        )
        assertEquals(
            "movie.mp4",
            FileUtils.normalizedDisplayName(entity("movie.mp4", "mp4", isImage = false)),
        )
        assertEquals(
            "backup.zip",
            FileUtils.normalizedDisplayName(entity("backup.zip", "zip", isImage = false)),
        )
    }

    @Test
    fun pjmContainerKeepsItsOwnName() {
        assertEquals(
            "Export_20260101_120000.pjm.1",
            FileUtils.normalizedDisplayName(entity("Export_20260101_120000.pjm.1", "pjm", isImage = false)),
        )
    }
}
