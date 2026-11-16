package com.dhhxfggg.pjm.domain.reader

/**
 * 阅读器支持的电子书格式。
 *
 * 选取原则：只用纯本地解析能稳妥读出来的格式，不引入需要完整排版引擎的重格式。
 * 带 DRM 的（AZW3/MOBI 的加密变体）一律不支持 —— 与其读到乱码，不如明确告诉用户。
 */
enum class BookFormat(
    /** 归到「文本」分类、能用阅读器打开的扩展名 */
    val extensions: Set<String>,
    /** 是否是「有结构」的格式（自带章节/元数据），false 表示按行切段 + 正则识别章节 */
    val structured: Boolean,
) {
    /** 纯文本：按编码检测解码后切段，章节靠正则识别 */
    TXT(setOf("txt", "text", "log"), false),

    /** EPUB：zip + OPF，spine 顺序即章节顺序，自带元数据与封面 */
    EPUB(setOf("epub"), true),

    /** FB2（FictionBook）：单文件 XML，自带 section 层级与书名/作者 */
    FB2(setOf("fb2"), true),

    /** HTML / XHTML：单文件网页，按标题标签切章 */
    HTML(setOf("html", "htm", "xhtml"), true),

    /** Markdown：标题行切章，去掉标记符号 */
    MARKDOWN(setOf("md", "markdown", "mdown"), false),

    ;

    companion object {
        /** 阅读器可打开的全部扩展名 */
        val allExtensions: Set<String> = entries.flatMap { it.extensions }.toSet()

        /**
         * 按文件名判格式。
         *
         * 纯文本这一支故意收得很窄：json / kt / xml 这类代码和配置文件虽然也是文本，
         * 但用「小说阅读器」打开只会把结构信息全丢掉，它们继续走代码查看器更合适。
         */
        fun of(fileName: String): BookFormat? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}
