package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileUtils 纯函数逻辑的 JVM 单元测试。
 * 只覆盖不触碰 Android/磁盘状态的文件名/分类/格式化逻辑，离线可跑。
 */
class FileUtilsTest {
    // ---------- getFileExtension ----------
    @Test
    fun extension_commonCases() {
        assertEquals("jpg", FileUtils.getFileExtension("photo.jpg"))
        assertEquals("mp4", FileUtils.getFileExtension("clip.MP4")) // 统一小写
        assertEquals("", FileUtils.getFileExtension("noext"))
        assertEquals("", FileUtils.getFileExtension("trailing."))
    }

    @Test
    fun extension_pjmMultiVolume() {
        // 多分卷 pjm：Export_20260101_000000.pjm.1 → pjm
        assertEquals("pjm", FileUtils.getFileExtension("Export_20260101_000000.pjm.1"))
        assertEquals("pjm", FileUtils.getFileExtension("Pack_20260101_000000.pjm.3"))
        assertEquals("pjm", FileUtils.getFileExtension("single.pjm"))
    }

    // ---------- 分类判定 ----------
    @Test
    fun imageVideoAudioClassification() {
        assertTrue(FileUtils.isImageFile("a.jpg"))
        assertTrue(FileUtils.isImageFile("b.PNG"))
        assertTrue(FileUtils.isImageFile("c.webp"))
        assertFalse(FileUtils.isImageFile("d.mp4"))

        assertTrue(FileUtils.isVideoFile("d.mp4"))
        assertTrue(FileUtils.isVideoFile("e.mkv"))
        assertTrue(FileUtils.isVideoFile("f.MOV"))
        assertFalse(FileUtils.isVideoFile("a.jpg"))

        assertTrue(FileUtils.isAudioFile("g.mp3"))
        assertTrue(FileUtils.isAudioFile("h.flac"))
        assertFalse(FileUtils.isAudioFile("d.mp4"))
    }

    @Test
    fun archiveDetection() {
        assertTrue(FileUtils.isArchiveFile("x.zip"))
        assertTrue(FileUtils.isArchiveFile("x.rar"))
        assertTrue(FileUtils.isArchiveFile("x.7z"))
        assertTrue(FileUtils.isArchiveFile("x.tar.gz"))
        assertTrue(FileUtils.isArchiveFile("x.xz"))
        assertFalse(FileUtils.isArchiveFile("x.jpg"))
        assertFalse(FileUtils.isArchiveFile("x.pjm")) // pjm 是容器不是可自动解压归档
    }

    @Test
    fun pjmDetection() {
        assertTrue(FileUtils.isPjmFile("a.pjm"))
        assertTrue(FileUtils.isPjmFile("Export_20260101_000000.pjm.2"))
        assertFalse(FileUtils.isPjmFile("a.jpg"))
    }

    // ---------- getCategory ----------
    @Test
    fun category_byExtension() {
        assertEquals("pjm", FileUtils.getCategory("x.pjm"))
        assertEquals("pjm", FileUtils.getCategory("Export_20260101_000000.pjm.1"))
        assertEquals("images", FileUtils.getCategory("a.jpg"))
        assertEquals("videos", FileUtils.getCategory("b.mp4"))
        assertEquals("audios", FileUtils.getCategory("c.mp3"))
        // 文本已从「其他」独立成「文本」分类（配合小说式阅读器）
        assertEquals("texts", FileUtils.getCategory("d.txt"))
        assertEquals("texts", FileUtils.getCategory("e.md"))
        assertEquals("texts", FileUtils.getCategory("f.json"))
        assertEquals("texts", FileUtils.getCategory("g.log"))
        assertEquals("others", FileUtils.getCategory("noext"))
        assertEquals("others", FileUtils.getCategory("h.docx"))
    }

    // ---------- shouldCompress ----------
    @Test
    fun shouldCompress_skipsAlreadyCompressed() {
        assertFalse("jpg already compressed", FileUtils.shouldCompress("a.jpg"))
        assertFalse("mp4 already compressed", FileUtils.shouldCompress("b.mp4"))
        assertFalse("zip already compressed", FileUtils.shouldCompress("c.zip"))
        assertFalse("pjm container not re-compressed", FileUtils.shouldCompress("d.pjm"))
        assertTrue("txt should compress", FileUtils.shouldCompress("e.txt"))
        assertTrue("unknown ext should compress", FileUtils.shouldCompress("f.xyz"))
    }

    // ---------- formatFileSize ----------
    @Test
    fun formatFileSize_units() {
        assertEquals("0 B", FileUtils.formatFileSize(0))
        assertEquals("999 B", FileUtils.formatFileSize(999))
        assertEquals("1.00 KB", FileUtils.formatFileSize(1000))
        assertEquals("1.00 MB", FileUtils.formatFileSize(1_000_000))
        assertEquals("1.00 GB", FileUtils.formatFileSize(1_000_000_000))
    }

    // ---------- getFileFingerprint ----------
    @Test
    fun fingerprint_stableFormat() {
        val e =
            FileEntity(
                relativePath = "images/x.jpg",
                name = "x.jpg",
                size = 1234L,
                category = "images",
                lastModified = 5678L,
                isImage = true,
                extension = "jpg",
                contentHash = null,
            )
        assertEquals("jpg_1234_5678", FileUtils.getFileFingerprint(e))
    }

    // ---------- normalizedDisplayName ----------
    @Test
    fun normalizedDisplayName_pjmKeepsOriginal() {
        val e =
            FileEntity(
                relativePath = "pjm/Export_20260715_183000.pjm.1",
                name = "Export_20260715_183000.pjm.1",
                size = 1,
                category = "pjm",
                lastModified = 0,
                isImage = false,
                extension = "pjm",
                contentHash = null,
            )
        // pjm 容器保持原样
        assertEquals("Export_20260715_183000.pjm.1", FileUtils.normalizedDisplayName(e))
    }

    @Test
    fun normalizedDisplayName_imageUsesImportTimeOnly() {
        val e =
            FileEntity(
                relativePath = "images/photo.jpg",
                name = "photo.jpg",
                size = 1,
                category = "images",
                lastModified = 1784981057941L, // 2026-07-25（示例）
                isImage = true,
                extension = "jpg",
                contentHash = null,
            )
        val name = FileUtils.normalizedDisplayName(e)
        // 只有 yyyyMMdd_HHmmss.扩展名，不带 PJM_ 前缀（与老库里的图片名一致）
        assertTrue("should be time-only name, got: $name", Regex("^\\d{8}_\\d{6}\\.jpg$").matches(name))
        assertFalse("should not keep a PJM_ prefix: $name", name.startsWith("PJM_"))
    }

    @Test
    fun normalizedDisplayName_prefersImportedAt() {
        fun entityAt(
            importedAt: Long,
            lastModified: Long,
        ) = FileEntity(
            relativePath = "images/time.jpg",
            name = "time.jpg",
            size = 1,
            category = "images",
            lastModified = lastModified,
            importedAt = importedAt,
            isImage = true,
            extension = "jpg",
            contentHash = null,
        )
        // 入库时间优先：文件自身时间变了，显示名不该跟着变
        assertEquals(
            FileUtils.normalizedDisplayName(entityAt(importedAt = 1_784_981_057_941L, lastModified = 1_600_000_000_000L)),
            FileUtils.normalizedDisplayName(entityAt(importedAt = 1_784_981_057_941L, lastModified = 1_700_000_000_000L)),
        )
    }
}
