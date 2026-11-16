package com.dhhxfggg.pjm.domain.reader

/**
 * 轻量 XHTML/XML 辅助：只做阅读器需要的那几件事。
 *
 * 不引入 XML 解析器的原因：电子书里的 XHTML 经常不是合法 XML（未闭合的 br/img、
 * 裸的 & 符号），严格解析器会整本解析失败；这里的正则/手写扫描对脏数据更宽容。
 */
object XmlText {
    private val TAG = Regex("<[^>]+>")
    private val SCRIPT_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE = Regex("</(p|div|h[1-6]|li|tr|section|article|blockquote|pre)>", RegexOption.IGNORE_CASE)
    private val LI_OPEN = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val WS = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")
    private val NUM_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")

    data class OpfMetadata(
        val title: String? = null,
        val author: String? = null,
    )

    fun tags(
        xml: String,
        name: String,
    ): List<String> = Regex("<$name\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }.toList()

    /** 取标签文本（含嵌套），如 dc:title */
    fun firstTagText(
        xml: String,
        name: String,
    ): String? =
        Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.let { stripTags(it).trim() }
            ?.ifBlank { null }

    fun parseOpfMetadata(opf: String): OpfMetadata {
        val title = firstTagText(opf, "dc:title") ?: firstTagText(opf, "title")
        val creator = firstTagText(opf, "dc:creator") ?: firstTagText(opf, "creator")
        return OpfMetadata(title, creator)
    }

    /** 从标签文本里取属性值：手写查找，避免正则引号把 Kotlin 字符串写坏 */
    fun attr(
        tag: String,
        name: String,
    ): String? {
        var i = 0
        while (true) {
            val idx = tag.indexOf(name, i, ignoreCase = true)
            if (idx < 0) return null
            // 裸子串匹配会命中属性值里的同名文本；真正的属性名前面必须是空白
            if (idx > 0 && tag[idx - 1].isWhitespace()) {
                var v = idx + name.length
                while (v < tag.length && tag[v].isWhitespace()) v++
                if (v < tag.length && tag[v] == '=') {
                    v++
                    while (v < tag.length && tag[v].isWhitespace()) v++
                    val quote = if (v < tag.length) tag[v] else ' '
                    if (v < tag.length && (quote == '"' || quote == '\'')) {
                        val end = tag.indexOf(quote, v + 1)
                        if (end > v) return tag.substring(v + 1, end)
                    } else {
                        var end = v
                        while (end < tag.length && !tag[end].isWhitespace() && tag[end] != '>') end++
                        if (end > v) return tag.substring(v, end)
                    }
                }
            }
            i = idx + name.length
        }
    }

    /** 相对路径拼接（OPF 与各章都在同一个包目录下） */
    fun join(
        base: String,
        href: String,
    ): String {
        val clean = href.substringBefore('#')
        return if (base.isEmpty()) clean else base.trimEnd('/') + "/" + clean
    }

    fun stripTags(s: String): String = s.replace(TAG, "")

    /**
     * HTML/XHTML → 段落列表。
     * 块级标签结束处断行，空行丢弃，实体还原，连续空白压成一个空格。
     */
    fun toParagraphs(html: String): List<String> {
        var s = html.replace(SCRIPT_STYLE, " ")
        s = s.replace(BR, "\\n")
        s = s.replace(BLOCK_CLOSE, "\\n")
        s = s.replace(LI_OPEN, "· ")
        s = stripTags(s)
        s = unescape(s)
        s = s.replace(WS, " ")
        s = s.replace(BLANK_LINES, "\\n\\n")
        return s
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun unescape(s: String): String =
        s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&ldquo;", "\u201C")
            .replace("&rdquo;", "\u201D")
            .replace("&lsquo;", "\u2018")
            .replace("&rsquo;", "\u2019")
            .replace("&amp;", "&")
            .replace(HEX_ENTITY) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            }.replace(NUM_ENTITY) { m ->
                m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            }
}
