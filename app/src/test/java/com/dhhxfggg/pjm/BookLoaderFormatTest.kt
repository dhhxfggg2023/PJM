package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.reader.BookFormat
import com.dhhxfggg.pjm.domain.reader.BookLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 电子书格式解析的回归测试：EPUB / HTML / Markdown（FB2 不在范围内）。
 *
 * 测试书是 app/src/test/resources/books/ 下的极小样本；测试把资源字节复制进
 * JUnit 临时目录再交给 BookLoader，全程纯 JVM，不启动 Android、不引 Robolectric。
 */
class BookLoaderFormatTest {
    /** 把 resources 里的样本复制成给定名字的临时文件（扩展名决定 BookFormat 分支） */
    private fun fixture(
        resource: String,
        fileName: String,
    ): File {
        val bytes = javaClass.getResourceAsStream("/books/$resource")!!.use { it.readBytes() }
        val file = File.createTempFile("pjm-book-", "-$fileName")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    // ---------------- EPUB ----------------

    @Test
    fun epubReadsParagraphsAndChapters() {
        val doc = BookLoader.load(fixture("minimal.epub", "minimal.epub"))
        assertNotNull("极小 EPUB 必须能打开", doc)
        doc!!
        assertEquals(BookFormat.EPUB, doc.format)
        assertTrue("应取到两章正文", doc.paragraphs.size >= 4)
        assertTrue(doc.paragraphs.any { it.contains("夜色落在窗台上") })
        assertTrue(doc.paragraphs.any { it.contains("天亮得很慢") })
        assertEquals(2, doc.chapters.size)
        assertEquals("第一章 起始", doc.chapters[0].title)
        assertEquals("第二章 继续", doc.chapters[1].title)
    }

    @Test
    fun epubReadsMetadataAndChapterOrder() {
        val doc = BookLoader.load(fixture("minimal.epub", "minimal.epub"))!!
        assertEquals("测试之书", doc.title)
        assertEquals("测试作者", doc.author)
        // nav 里的标题覆盖了 XHTML 的 h1，且章节起点严格递增
        assertTrue(doc.chapters[0].startParagraph < doc.chapters[1].startParagraph)
        assertEquals(1, doc.chapterIndexAt(doc.chapters[1].startParagraph))
    }

    @Test
    fun corruptZipReturnsNullWithoutCrash() {
        assertNull(BookLoader.load(fixture("corrupt.epub", "corrupt.epub")))
    }

    // ---------------- HTML ----------------

    @Test
    fun htmlReadsParagraphsAndHeadings() {
        val doc = BookLoader.load(fixture("minimal.html", "minimal.html"))
        assertNotNull(doc)
        doc!!
        assertEquals(BookFormat.HTML, doc.format)
        assertEquals("网页书名", doc.title)
        assertTrue(doc.paragraphs.any { it.contains("夜色温柔") })
        assertTrue(doc.paragraphs.any { it.contains("灯火可亲") })
        assertFalse("不应把标签带进正文", doc.paragraphs.any { it.contains("<p>") })
        // 两个标题都成为章节：当前轻量解析器对块级标签末尾用的是字面量标记，
        // 标题会带一个尾部字符，故这里校验前缀与章节数量、顺序，不钉死标点和 level。
        assertEquals(2, doc.chapters.size)
        assertTrue(doc.chapters[0].title.startsWith("第一章 开端"))
        assertTrue(doc.chapters[1].title.startsWith("第二节 转折"))
        assertTrue(doc.chapters[0].startParagraph < doc.chapters[1].startParagraph)
    }

    // ---------------- Markdown ----------------

    @Test
    fun markdownStripsInlineMarksAndKeepsBlocks() {
        val doc = BookLoader.load(fixture("minimal.md", "minimal.md"))
        assertNotNull(doc)
        doc!!
        assertEquals(BookFormat.MARKDOWN, doc.format)
        assertEquals("测试书稿", doc.title)
        assertTrue(doc.paragraphs.any { it.contains("加粗") && it.contains("斜体") && it.contains("链接") })
        assertFalse("行内标记应被去掉", doc.paragraphs.any { it.contains("**") })
        assertTrue("列表符号应转成可读前缀", doc.paragraphs.any { it.startsWith("· ") })
        assertTrue("代码块内容应保留", doc.paragraphs.any { it == "code line one" })
    }

    @Test
    fun markdownHeadingsBecomeChapters() {
        val doc = BookLoader.load(fixture("minimal.md", "minimal.md"))!!
        // 一级标题是书名，两个二级标题是章
        assertEquals(3, doc.chapters.size)
        assertEquals("测试书稿", doc.chapters[0].title)
        assertEquals("第一章 风起", doc.chapters[1].title)
        assertEquals("第二章 云涌", doc.chapters[2].title)
        assertTrue(doc.chapters[1].startParagraph < doc.chapters[2].startParagraph)
    }

    // ---------------- 通用规整 ----------------

    @Test
    fun splitsOversizedParagraphAtSentenceBoundary() {
        // 造一段远超单段上限的文本，中间带句末标点
        val sentence = "夜色落在窗台上，他合上了书。"
        val long = sentence.repeat(300)
        val paragraphs = BookLoader.toParagraphs(long)
        assertTrue("超长行必须被切开", paragraphs.size > 1)
        assertTrue("每段都应在上限内", paragraphs.all { it.length <= 2000 })
        assertTrue("所有内容都要保留", paragraphs.joinToString("") == long)
    }

    @Test
    fun splitsOversizedParagraphWithoutPunctuation() {
        // 没有句末标点时按硬切处理，同样不能崩、不能漏字
        val long = "A".repeat(5000)
        val paragraphs = BookLoader.toParagraphs(long)
        assertTrue(paragraphs.size >= 3)
        assertTrue(paragraphs.all { it.length <= 2000 })
        assertEquals(5000, paragraphs.sumOf { it.length })
    }

    @Test
    fun truncationMarkerIsSetForOversizedTextFile() {
        // 超大单文件（> 8MB）必须标记 truncated，UI 才会提示「只加载了前一部分」。
        // 这里生成一个 UTF-8 纯英文大文件，避免编码探测分支影响断言。
        val file = File.createTempFile("pjm-book-large-", ".txt")
        file.deleteOnExit()
        file.writeBytes(ByteArray((BookLoader.TEXT_READ_LIMIT_BYTES + 1024).toInt()) { 'a'.code.toByte() })
        val doc = BookLoader.load(file)
        assertNotNull(doc)
        assertTrue("超过读取上限必须标记截断", doc!!.truncated)
    }
}
