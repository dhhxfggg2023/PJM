package com.dhhxfggg.pjm.domain.reader

/** HTML 专属的两件事：取标题层级、取第一个标题（EPUB 单章没进 nav 时兜底） */
object HtmlText {
    private val H_TAG =
        Regex("<h([1-6])[^>]*>(.*?)</h\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TITLE_TAG =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun firstHeading(html: String): String? =
        H_TAG
            .find(html)
            ?.groupValues
            ?.get(2)
            ?.let { XmlText.stripTags(it).trim() }
            ?.ifBlank { null }
            ?: TITLE_TAG
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let { XmlText.stripTags(it).trim() }
                ?.ifBlank { null }

    fun headingLevel(html: String): Int =
        H_TAG
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceIn(1, 3)
            ?.minus(1) ?: 1

    /** 单文件 HTML 的目录：所有 h1–h3，按它们在纯文本里的出现位置定位 */
    fun headings(html: String): List<BookChapter> {
        val plain = XmlText.toParagraphs(html)
        val chapters = ArrayList<BookChapter>()
        val usedIndexes = HashSet<Int>()
        H_TAG.findAll(html).forEach { m ->
            val title = XmlText.stripTags(m.groupValues[2]).trim()
            if (title.isEmpty() || title.length > 60) return@forEach
            for (i in plain.indices) {
                if (i in usedIndexes) continue
                if (plain[i] == title) {
                    usedIndexes.add(i)
                    chapters.add(BookChapter(title, i, m.groupValues[1].toInt().coerceIn(1, 3) - 1))
                    break
                }
            }
        }
        return chapters.sortedBy { it.startParagraph }
    }

    fun toPlainText(html: String): List<String> = XmlText.toParagraphs(html)
}
