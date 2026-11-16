package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示名回归测试。
 *
 * 磁盘物理名为了并发安全带 `_<16位hex>` 尾巴，但用户在库里必须看到原始文件名。
 * 这里钉住三件事：剥尾巴、不误伤用户自己的同类命名、图片仍按入库时间。
 */
class DisplayNameTest {
    private fun entity(
        name: String,
        relativePath: String,
        isImage: Boolean = false,
        extension: String = relativePath.substringAfterLast('.', ""),
    ) = FileEntity(
        relativePath = relativePath,
        name = name,
        size = 1024L,
        category = if (isImage) "images" else "videos",
        lastModified = 1_700_000_000_000L,
        isImage = isImage,
        extension = extension,
        contentHash = null,
        importedAt = 1_700_000_000_000L,
    )

    @Test
    fun stripsDiskSuffixFromStoredName() {
        val e = entity(name = "我的视频_a1b2c3d4e5f60718.mp4", relativePath = "videos/我的视频_a1b2c3d4e5f60718.mp4")
        assertEquals("我的视频.mp4", FileUtils.normalizedDisplayName(e))
    }

    @Test
    fun stripsDiskSuffixFromDiskNameToo() {
        // 老库里 name 存的是 UUID，只能从磁盘名恢复可读名
        val e = entity(name = "0eb14345-1111-2222-3333-444455556666", relativePath = "videos/纪录片_0123456789abcdef.mp4")
        assertEquals("纪录片.mp4", FileUtils.normalizedDisplayName(e))
    }

    @Test
    fun keepsUserNameThatOnlyLooksSimilar() {
        // 16 位大写十六进制 / 长度不对 / 空格分隔，都不是我们追加的尾巴，必须原样保留
        val upper = entity(name = "报告_20260101ABCDEF01.pdf", relativePath = "texts/报告_20260101ABCDEF01.pdf")
        assertEquals("报告_20260101ABCDEF01.pdf", FileUtils.normalizedDisplayName(upper))
        val short = entity(name = "笔记_abc123.txt", relativePath = "texts/笔记_abc123.txt")
        assertEquals("笔记_abc123.txt", FileUtils.normalizedDisplayName(short))
    }

    @Test
    fun imageStillUsesImportedTime() {
        val e = entity(name = "IMG_0001.jpg", relativePath = "images/PJM_20231114_221320.jpg", isImage = true, extension = "jpg")
        val shown = FileUtils.normalizedDisplayName(e)
        assertTrue("图片显示名应是入库时间而非原名: " + shown, shown.startsWith("2023") || shown.startsWith("PJM_").not())
        assertTrue("必须保留扩展名: " + shown, shown.endsWith(".jpg"))
    }

    @Test
    fun pureUuidNameFallsBackToTimestamp() {
        val e = entity(name = "a1b2c3d4e5f60718", relativePath = "videos/a1b2c3d4e5f60718", extension = "mp4")
        val shown = FileUtils.normalizedDisplayName(e)
        assertTrue("剥完为空时必须退回时间命名: " + shown, shown.endsWith(".mp4") && !shown.contains("a1b2c3d4"))
    }

    @Test
    fun stripHelperIsExact() {
        assertEquals("原名.ext", FileUtils.stripDiskSuffix("原名_0123456789abcdef.ext"))
        assertEquals("原名", FileUtils.stripDiskSuffix("原名_0123456789abcdef"))
        // 大写十六进制不是我们生成的形态，必须原样保留
        assertEquals("原名_0123456789ABCDEF.ext", FileUtils.stripDiskSuffix("原名_0123456789ABCDEF.ext"))
        // 中间带尾巴、后面还有内容的，不匹配（只剥结尾那一段）
        assertEquals("a_0123456789abcdef_b.ext", FileUtils.stripDiskSuffix("a_0123456789abcdef_b.ext"))
    }
}
