package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.reader.BookFormat
import com.dhhxfggg.pjm.domain.reader.ChapterDetection
import com.dhhxfggg.pjm.domain.reader.Paginator
import com.dhhxfggg.pjm.domain.reader.TextDecoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 阅读器纯逻辑回归测试：格式识别、编码判定、章节识别、分页。
 *
 * 这几块以前全埋在 Compose 函数里，既测不了也改不动；抽成纯函数后
 * 「GBK 书乱码」「正文句子被当章节」「超长段把分页撑爆」这类问题都能在单测里钉住。
 */
class ReaderCoreTest {
    // ---------------- 格式识别 ----------------

    @Test
    fun bookFormat_recognizesSupportedExtensions() {
        assertEquals(BookFormat.TXT, BookFormat.of("novel.txt"))
        assertEquals(BookFormat.TXT, BookFormat.of("NOVEL.TXT"))
        assertEquals(BookFormat.EPUB, BookFormat.of("book.epub"))
        assertEquals(BookFormat.FB2, BookFormat.of("book.fb2"))
        assertEquals(BookFormat.HTML, BookFormat.of("page.xhtml"))
        assertEquals(BookFormat.MARKDOWN, BookFormat.of("notes.md"))
    }

    @Test
    fun bookFormat_ofRejectsUnknownExtensions() {
        // BookFormat 本身只认书籍格式；代码/配置由 BookLoader 退回纯文本分支
        assertNull(BookFormat.of("Main.kt"))
        assertNull(BookFormat.of("data.json"))
    }

    @Test
    fun bookFormat_rejectsCodeAndBinary() {
        // 代码/配置虽然也是文本，但用小说阅读器打开会丢结构，继续走代码查看器
        assertNull(BookFormat.of("Main.kt"))
        assertNull(BookFormat.of("data.json"))
        assertNull(BookFormat.of("movie.mp4"))
        assertNull(BookFormat.of("noext"))
    }

    // ---------------- 编码判定 ----------------

    @Test
    fun textDecoding_prefersGbkOverBrokenUtf8() {
        val gbk = Charset.forName("GB18030")
        val original = "第一章 风起\n夜色落在窗台上，他合上了书。\n".repeat(6)
        val bytes = original.toByteArray(gbk)
        val decoded = TextDecoding.decode(bytes)
        assertTrue("GBK 中文必须能正确还原: " + decoded.take(30), decoded.contains("第一章"))
        assertFalse("不能出现替换字符", decoded.contains('\uFFFD'))
    }

    @Test
    fun textDecoding_respectsUtf8Bom() {
        val content = "序章\n这是一本 UTF-8 的书。"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8)
        val decoded = TextDecoding.decode(withBom)
        assertEquals(content, decoded)
    }

    @Test
    fun textDecoding_detectsUtf16LeWithoutBom() {
        val content = "Chapter 1 The Beginning\nIt was a dark night."
        val bytes = content.toByteArray(Charsets.UTF_16LE)
        val decoded = TextDecoding.decode(bytes)
        assertTrue("UTF-16LE 必须能识别: " + decoded.take(20), decoded.contains("Chapter 1"))
    }

    @Test
    fun scorePenalizesReplacementAndControlChars() {
        val clean = TextDecoding.score("normal english text 正常中文")
        val broken = TextDecoding.score("\uFFFD\uFFFD\uFFFD\u0001\u0002")
        assertTrue("正常文本得分必须高于乱码", clean > broken)
    }

    // ---------------- 章节识别 ----------------

    @Test
    fun chapterDetection_findsChineseChapters() {
        val paragraphs =
            listOf(
                "第一章 风起",
                "夜色落在窗台上。",
                "他合上了书。",
                "第二章 云涌",
                "天亮得很慢。",
                "第三章 雨落",
                "雨点打在玻璃上。",
            )
        val chapters = ChapterDetection.detect(paragraphs)
        assertEquals(3, chapters.size)
        assertEquals("第一章 风起", chapters[0].title)
        assertEquals(0, chapters[0].startParagraph)
        assertEquals(3, chapters[1].startParagraph)
        assertEquals(5, chapters[2].startParagraph)
    }

    @Test
    fun chapterDetection_rejectsBodySentences() {
        // 「第二章开始」这类正文里的引用不能算章节标题
        assertFalse(ChapterDetection.isChapterTitle("第二章开始的时候，他还在路上。"))
        assertFalse(ChapterDetection.isChapterTitle("他说：\"第三章写得不好。\""))
        assertTrue(ChapterDetection.isChapterTitle("第二章 云涌"))
    }

    @Test
    fun chapterDetection_marksVolumeTitlesAsLevelZero() {
        val paragraphs = listOf("第一卷 风起", "第一章 开端", "正文。", "第二章 转折", "正文。")
        val chapters = ChapterDetection.detect(paragraphs)
        val volume = chapters.firstOrNull { it.title.contains("第一卷") }
        assertNotNull("卷标题应被识别", volume)
        assertEquals(0, volume!!.level)
        assertEquals(1, chapters.first { it.title.contains("第一章") }.level)
    }

    @Test
    fun chapterDetection_emptyWhenNoTitles() {
        val paragraphs = List(20) { "这是一段普通正文，没有任何章节标记。" }
        assertTrue(ChapterDetection.detect(paragraphs).isEmpty())
    }

    @Test
    fun chapterDetection_recognizesVariedFormats() {
        // 三种常见写法都要认出来：中文数字、阿拉伯数字、英文 Chapter
        assertTrue(ChapterDetection.isChapterTitle("第 12 章 夜谈"))
        assertTrue(ChapterDetection.isChapterTitle("第零章 序"))
        assertTrue(ChapterDetection.isChapterTitle("12、雨落"))
        assertTrue(ChapterDetection.isChapterTitle("（三）风起"))
        assertTrue(ChapterDetection.isChapterTitle("Chapter One 开端"))
        assertTrue(ChapterDetection.isChapterTitle("Chapter Twelve 深冬"))
    }

    @Test
    fun chapterDetection_rejectsBodyWithNumberInside() {
        // 正文里提到章节号不能算标题
        assertFalse(ChapterDetection.isChapterTitle("他翻到第三章的时候天已经黑了。"))
        assertFalse(ChapterDetection.isChapterTitle("这一章写得真好，我很喜欢。"))
        assertFalse(ChapterDetection.isChapterTitle("第一章里发生过很多事，后来都忘了。"))
        assertFalse(ChapterDetection.isChapterTitle("他说：第一章结束了。"))
    }

    @Test
    fun chapterDetection_clustersPlainNumberTitles() {
        // 裸数字标题：旧实现一条都认不出，靠聚类识别
        val paragraphs =
            listOf(
                "1 雨落",
                "这是第一段正文。",
                "2 风起",
                "这是第二段正文。",
                "3 云涌",
                "这是第三段正文。",
            )
        val chapters = ChapterDetection.detect(paragraphs)
        assertEquals(3, chapters.size)
        assertEquals("1 雨落", chapters[0].title)
        assertEquals(2, chapters[1].startParagraph)
    }

    @Test
    fun chapterDetection_doesNotClusterBodyShortSentences() {
        // 一堆格式各异的短正文句不能被当成章节
        val paragraphs =
            listOf(
                "他笑了。",
                "天很蓝",
                "她转身离开",
                "门开了",
                "没有回答",
                "风停了",
            )
        assertTrue(ChapterDetection.detect(paragraphs).isEmpty())
    }

    // ---------------- 分页 ----------------

    @Test
    fun paginator_respectsPageHeight() {
        val heights = FloatArray(10) { 20f } // 每段 20px
        val starts = Paginator.paginate(heights, 100f) // 每页正好 5 段
        // 10 段塞成 2 页：第 3 页没有内容，不该出现空页起点
        assertEquals(listOf(0, 5), starts)
    }

    @Test
    fun paginator_givesOversizedParagraphItsOwnPage() {
        // 中间一段比整页还高：必须独占一页，不能把它后面的内容全推歪
        val heights = floatArrayOf(20f, 20f, 500f, 20f, 20f)
        val starts = Paginator.paginate(heights, 100f)
        assertTrue("超长段应单独起页", starts.contains(2))
        // 每页起始点必须严格递增
        starts.zipWithNext().forEach { (a, b) -> assertTrue("起始点必须递增: " + starts, b > a) }
    }

    @Test
    fun paginator_givesEachOversizedParagraphItsOwnPage() {
        // 连续两段都超过整页：必须各自独占一页。
        // 旧实现在 used==0 时不补起点，两段被塞进同一页，第二段整段被裁掉且翻不到。
        val heights = floatArrayOf(20f, 500f, 600f, 20f)
        val starts = Paginator.paginate(heights, 100f)
        // 两个超高段都要有各自的起始页
        assertTrue("超高段 1 必须独占一页: " + starts, starts.count { it == 1 } >= 1)
        assertTrue("超高段 2 必须独占一页: " + starts, starts.count { it == 2 } >= 1)
        starts.zipWithNext().forEach { (a, b) -> assertTrue("起始点必须递增: " + starts, b > a) }
        // 超高段之后的那一段也必须另起一页
        assertTrue("超高段后的段落必须另起页: " + starts, starts.contains(3))
    }

    @Test
    fun paginator_pageOfMapsParagraphToPage() {
        val starts = listOf(0, 5, 10)
        assertEquals(0, Paginator.pageOf(starts, 0))
        assertEquals(0, Paginator.pageOf(starts, 4))
        assertEquals(1, Paginator.pageOf(starts, 5))
        assertEquals(2, Paginator.pageOf(starts, 99))
    }

    @Test
    fun paginator_handlesEmptyInput() {
        assertEquals(listOf(0), Paginator.paginate(FloatArray(0), 100f))
        assertEquals(1, Paginator.pageCount(listOf(0)))
    }
}
