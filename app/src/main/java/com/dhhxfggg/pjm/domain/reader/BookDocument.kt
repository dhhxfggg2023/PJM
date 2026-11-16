package com.dhhxfggg.pjm.domain.reader

/** 一章（或一卷）：标题 + 在段落列表里的起始下标 */
data class BookChapter(
    val title: String,
    val startParagraph: Int,
    /** 层级：0 = 卷/部，1 = 章，2 = 节。目录里按层级缩进 */
    val level: Int = 1,
)

/**
 * 一本已经解析好的书。
 *
 * 段落是阅读器唯一的排版单位：一自然段一行。
 * 加载阶段就把内容规整好（去掉多余空白、把超长行按上限切开），排版阶段只量高。
 */
data class BookDocument(
    val format: BookFormat,
    val paragraphs: List<String>,
    val chapters: List<BookChapter> = emptyList(),
    val title: String? = null,
    val author: String? = null,
    /** 封面原始字节（EPUB/FB2 自带）；解码交给 UI，避免领域层依赖 Bitmap */
    val coverBytes: ByteArray? = null,
    /** 是否只加载了前一部分（超大文件），界面上要明确标注 */
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = paragraphs.isEmpty()

    /** 当前段落落在第几章：给「上一章/下一章」和目录高亮用 */
    fun chapterIndexAt(paragraphIndex: Int): Int {
        if (chapters.isEmpty()) return -1
        var low = 0
        var high = chapters.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (chapters[mid].startParagraph <= paragraphIndex) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
