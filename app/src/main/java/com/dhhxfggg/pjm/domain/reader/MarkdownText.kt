package com.dhhxfggg.pjm.domain.reader

/**
 * Markdown 的极简块解析：只区分标题、代码块和普通段落。
 *
 * 不追求完整 CommonMark（阅读器不需要表格/脚注精确渲染），
 * 关键是把井号标记去掉、代码块原样保留、列表符号转成可读前缀。
 */
object MarkdownText {
    sealed interface Block {
        data class Heading(
            val level: Int,
            val text: String,
        ) : Block

        data class Paragraph(
            val lines: List<String>,
        ) : Block

        data class Code(
            val lines: List<String>,
        ) : Block
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val LIST_ITEM = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
    private val BOLD_UNDER = Regex("__([^_]+)__")
    private val ITALIC_STAR = Regex("\\*([^*\\n]+)\\*")
    private val INLINE_CODE = Regex("\u0060([^\u0060]+)\u0060")

    fun split(md: String): List<Block> {
        val blocks = ArrayList<Block>()
        val buffer = ArrayList<String>()
        val code = ArrayList<String>()
        var inCode = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                blocks.add(Block.Paragraph(buffer.toList()))
                buffer.clear()
            }
        }

        md.replace("\r\n", "\n").split('\n').forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("\u0060\u0060\u0060")) {
                if (inCode) {
                    blocks.add(Block.Code(code.toList()))
                    code.clear()
                    inCode = false
                } else {
                    flush()
                    inCode = true
                }
                return@forEach
            }
            if (inCode) {
                code.add(line)
                return@forEach
            }
            if (line.isBlank()) {
                flush()
                return@forEach
            }
            val h = HEADING.find(line)
            if (h != null) {
                flush()
                blocks.add(Block.Heading(h.groupValues[1].length, inline(h.groupValues[2]).trim()))
                return@forEach
            }
            val li = LIST_ITEM.find(line)
            buffer.add(if (li != null) "· " + inline(li.groupValues[2]) else inline(line).trim())
        }
        if (inCode && code.isNotEmpty()) blocks.add(Block.Code(code.toList()))
        flush()
        return blocks
    }

    /** 去掉行内标记，只保留可读文本 */
    private fun inline(text: String): String {
        var s = IMAGE.replace(text, "")
        s = LINK.replace(s) { it.groupValues[1] }
        s = BOLD_STAR.replace(s) { it.groupValues[1] }
        s = BOLD_UNDER.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        return s
    }
}
