package com.dhhxfggg.pjm.domain.reader

import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * 电子书加载器：把磁盘上的文件解析成 [BookDocument]。
 *
 * 支持 TXT / EPUB / FB2 / HTML / Markdown。全部本地解析，不联网、不引三方排版引擎。
 * 每种格式最终都收敛到「段落列表 + 章节列表」，阅读器只需面对这一种结构。
 */
object BookLoader {
    private const val TAG = "BookLoader"

    /** 纯文本最多读多少字节；超过只读前面部分并在界面上标注 */
    const val TEXT_READ_LIMIT_BYTES = 8L * 1024 * 1024

    /** 单个压缩条目的解压上限，防 zip 炸弹 */
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 单段最大字符数：超长行按这个切开，避免一段就占满整页导致排版畸形 */
    private const val MAX_PARAGRAPH_CHARS = 2000

    fun load(file: File): BookDocument? {
        // 代码 / 配置 / 日志这类文件不在「书籍格式」里，但它们本来就是纯文本：
        // 退回 TXT 分支，按纯文本打开，不要因为扩展名不认识就报「打不开」。
        val format =
            BookFormat.of(file.name)
                ?: if (FileUtils.isTextFile(file.name)) BookFormat.TXT else return null
        return runCatching {
            when (format) {
                BookFormat.TXT -> loadText(file)
                BookFormat.EPUB -> loadEpub(file)
                BookFormat.FB2 -> loadFb2(file)
                BookFormat.HTML -> loadHtml(file)
                BookFormat.MARKDOWN -> loadMarkdown(file)
            }
        }.onFailure { PjmLogger.w(TAG, "加载失败: " + file.name, it) }.getOrNull()
    }

    // ---------------- TXT ----------------

    private fun loadText(file: File): BookDocument? {
        val truncated = file.length() > TEXT_READ_LIMIT_BYTES
        val charset = TextDecoding.detectCharset(file)
        val bytes = readLimited(file, TEXT_READ_LIMIT_BYTES)
        val text = runCatching { String(bytes, charset) }.getOrDefault(TextDecoding.decode(bytes))
        val paragraphs = toParagraphs(text)
        if (paragraphs.isEmpty()) return null
        return BookDocument(
            format = BookFormat.TXT,
            paragraphs = paragraphs,
            chapters = ChapterDetection.detect(paragraphs),
            truncated = truncated,
        )
    }

    private fun readLimited(
        file: File,
        limit: Long,
    ): ByteArray {
        val length = file.length()
        if (length <= limit) return file.readBytes()
        val buffer = ByteArray(limit.toInt())
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    // ---------------- 通用规整 ----------------

    /**
     * 纯文本 → 段落。
     *
     * 中文 txt 常见两种排版：一条自然段一行，或者整章挤在一行里靠缩进/空行分隔。
     * 这里按行切，再把超长行按句末标点回卷成合适长度，保证分页不会出现巨大段。
     */
    fun toParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>(normalized.length / 40 + 8)
        for (rawLine in normalized.lineSequence()) {
            val line = rawLine.trim().trimEnd('\u3000')
            if (line.isEmpty()) continue
            if (line.length <= MAX_PARAGRAPH_CHARS) {
                out.add(line)
            } else {
                out.addAll(splitLongParagraph(line))
            }
        }
        return out
    }

    /** 超长行按句号/问号/感叹号回卷，找不到标点就硬切 */
    private fun splitLongParagraph(line: String): List<String> {
        val result = ArrayList<String>(line.length / MAX_PARAGRAPH_CHARS + 1)
        var start = 0
        while (start < line.length) {
            val end = minOf(start + MAX_PARAGRAPH_CHARS, line.length)
            if (end == line.length) {
                result.add(line.substring(start))
                break
            }
            // 从后往前找最近的句末标点，让切开的位置落在句子边界上
            var cut = -1
            for (i in end - 1 downTo start + MAX_PARAGRAPH_CHARS / 2) {
                if (line[i] in "。！？；…") {
                    cut = i + 1
                    break
                }
            }
            if (cut <= start) cut = end
            result.add(line.substring(start, cut))
            start = cut
        }
        return result
    }

    // ---------------- EPUB ----------------

    private fun loadEpub(file: File): BookDocument? {
        if (file.length() <= 0L) return null
        return ZipFile(file).use { zip ->
            val container = readEntry(zip, "META-INF/container.xml") ?: return null
            val opfPath = XmlText.attr(container, "full-path") ?: return null
            val opf = readEntry(zip, opfPath) ?: return null
            val base = opfPath.substringBeforeLast('/', "")
            val meta = XmlText.parseOpfMetadata(opf)

            val manifest = HashMap<String, String>()
            XmlText.tags(opf, "item").forEach { tag ->
                val id = XmlText.attr(tag, "id") ?: return@forEach
                val href = XmlText.attr(tag, "href") ?: return@forEach
                val type = XmlText.attr(tag, "media-type").orEmpty()
                if (type.contains("html")) manifest[id] = XmlText.join(base, href)
            }
            val order = ArrayList<String>()
            XmlText.tags(opf, "itemref").forEach { tag ->
                val idref = XmlText.attr(tag, "idref") ?: return@forEach
                manifest[idref]?.let { order.add(it) }
            }

            val navTitles = readNavTitles(zip, base, opf)
            val paragraphs = ArrayList<String>()
            val chapters = ArrayList<BookChapter>()

            order.forEach { path ->
                val html = readEntry(zip, path) ?: return@forEach
                val body = HtmlText.toPlainText(html)
                if (body.isEmpty()) return@forEach
                val title =
                    navTitles[path]
                        ?: HtmlText.firstHeading(html)
                        ?: path.substringAfterLast('/').substringBeforeLast('.')
                chapters.add(BookChapter(title, paragraphs.size, HtmlText.headingLevel(html)))
                paragraphs.addAll(body)
            }
            if (paragraphs.isEmpty()) return null
            BookDocument(
                format = BookFormat.EPUB,
                paragraphs = paragraphs,
                chapters = chapters,
                title = meta.title,
                author = meta.author,
                coverBytes = readCover(zip, base, opf),
            )
        }
    }

    private fun readNavTitles(
        zip: ZipFile,
        base: String,
        opf: String,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        val navTag =
            XmlText.tags(opf, "item").firstOrNull { XmlText.attr(it, "properties")?.contains("nav") == true }
        val navHref = navTag?.let { XmlText.attr(it, "href") } ?: return out
        val nav = readEntry(zip, XmlText.join(base, navHref)) ?: return out
        val re = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"'#]+)[^\"']*[\"'][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        re.findAll(nav).forEach { m ->
            val href = XmlText.join(base, m.groupValues[1].trim())
            val title = XmlText.stripTags(m.groupValues[2]).trim()
            if (title.isNotEmpty()) out.putIfAbsent(href, title)
        }
        return out
    }

    private fun readCover(
        zip: ZipFile,
        base: String,
        opf: String,
    ): ByteArray? {
        val coverId =
            XmlText
                .tags(opf, "meta")
                .firstOrNull { XmlText.attr(it, "name") == "cover" }
                ?.let { XmlText.attr(it, "content") }
        val item =
            XmlText.tags(opf, "item").firstOrNull { tag ->
                val props = XmlText.attr(tag, "properties").orEmpty()
                props.contains("cover-image") || (coverId != null && XmlText.attr(tag, "id") == coverId)
            } ?: return null
        val href = XmlText.attr(item, "href") ?: return null
        return readEntryBytes(zip, XmlText.join(base, href))
    }

    // ---------------- FB2 ----------------

    private fun loadFb2(file: File): BookDocument? {
        val text = TextDecoding.decode(readLimited(file, TEXT_READ_LIMIT_BYTES))
        if (text.isBlank()) return null
        val title = XmlText.firstTagText(text, "book-title")
        val author =
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { XmlText.firstTagText(text, it) }
                .joinToString(" ")
                .trim()
                .ifBlank { null }
        val paragraphs = ArrayList<String>()
        val chapters = ArrayList<BookChapter>()
        // 按 section 切章：FB2 的 section/title 就是天然目录
        val sectionRe = Regex("<section[^>]*>(.*?)</section>", RegexOption.DOT_MATCHES_ALL)
        val bodyRe = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
        val body = bodyRe.find(text)?.groupValues?.get(1) ?: text
        sectionRe.findAll(body).forEach { m ->
            val chunk = m.groupValues[1]
            val sectionTitle = XmlText.firstTagText(chunk, "title")?.replace(Regex("\\s+"), " ")?.trim()
            val plain = HtmlText.toPlainText(chunk)
            if (plain.isEmpty()) return@forEach
            if (sectionTitle != null && sectionTitle.isNotBlank()) {
                chapters.add(BookChapter(sectionTitle, paragraphs.size))
            }
            paragraphs.addAll(plain)
        }
        if (paragraphs.isEmpty()) {
            val plain = HtmlText.toPlainText(body)
            if (plain.isEmpty()) return null
            paragraphs.addAll(plain)
        }
        return BookDocument(
            format = BookFormat.FB2,
            paragraphs = paragraphs,
            chapters = chapters.ifEmpty { ChapterDetection.detect(paragraphs) },
            title = title,
            author = author,
            coverBytes = readFb2Cover(text),
            // 截断标记必须和实际读法一致：这里同样只读了前 8MB，
            // 不标注的话用户会以为整本就这么长（旧实现只有 TXT 分支标了）。
            truncated = file.length() > TEXT_READ_LIMIT_BYTES,
        )
    }

    private fun readFb2Cover(text: String): ByteArray? {
        val binaryRe = Regex("<binary[^>]*id\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</binary>", RegexOption.DOT_MATCHES_ALL)
        val coverHref =
            XmlText
                .firstTagText(text, "coverpage")
                ?.let { null }
        // 常见结构：<coverpage><image l:href="#cover.jpg"/></coverpage>
        val coverId =
            Regex("<coverpage[^>]*>(.*?)</coverpage>", RegexOption.DOT_MATCHES_ALL)
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { HexRef.find(it) }
        val id = coverId ?: coverHref ?: return null
        val m = binaryRe.find(text) ?: return null
        if (m.groupValues[1].trim() != id) return null
        return runCatching { android.util.Base64.decode(m.groupValues[2].filterNot { it.isWhitespace() }, android.util.Base64.DEFAULT) }.getOrNull()
    }

    // ---------------- HTML / Markdown ----------------

    private fun loadHtml(file: File): BookDocument? {
        val html = TextDecoding.decode(readLimited(file, TEXT_READ_LIMIT_BYTES))
        if (html.isBlank()) return null
        val title = XmlText.firstTagText(html, "title")
        val chapters = HtmlText.headings(html)
        val paragraphs = HtmlText.toPlainText(html)
        if (paragraphs.isEmpty()) return null
        return BookDocument(
            format = BookFormat.HTML,
            paragraphs = paragraphs,
            chapters = chapters.ifEmpty { ChapterDetection.detect(paragraphs) },
            title = title,
            truncated = file.length() > TEXT_READ_LIMIT_BYTES,
        )
    }

    private fun loadMarkdown(file: File): BookDocument? {
        val md = TextDecoding.decode(readLimited(file, TEXT_READ_LIMIT_BYTES))
        if (md.isBlank()) return null
        val paragraphs = ArrayList<String>()
        val chapters = ArrayList<BookChapter>()
        var inCodeFence = false
        MarkdownText.split(md).forEach { block ->
            when (block) {
                is MarkdownText.Block.Code -> {
                    paragraphs.addAll(block.lines)
                    inCodeFence = false
                }
                is MarkdownText.Block.Heading -> {
                    chapters.add(BookChapter(block.text, paragraphs.size, block.level.coerceAtMost(3) - 1))
                    paragraphs.add(block.text)
                }
                is MarkdownText.Block.Paragraph -> paragraphs.addAll(block.lines)
            }
        }
        if (paragraphs.isEmpty()) return null
        return BookDocument(
            format = BookFormat.MARKDOWN,
            paragraphs = paragraphs,
            chapters = chapters,
            title = chapters.firstOrNull { it.level <= 0 }?.title ?: file.nameWithoutExtension,
            truncated = file.length() > TEXT_READ_LIMIT_BYTES,
        )
    }

    // ---------------- zip 读取 ----------------

    private fun readEntry(
        zip: ZipFile,
        path: String,
    ): String? = readEntryBytes(zip, path)?.let { TextDecoding.decode(it) }

    private fun readEntryBytes(
        zip: ZipFile,
        path: String,
    ): ByteArray? =
        runCatching {
            val entry = zip.getEntry(path) ?: zip.getEntry(path.replace('\\', '/')) ?: return null
            if (entry.size > MAX_ENTRY_BYTES) return null
            zip.getInputStream(entry).use { input ->
                // 声明大小不可信（ZIP64 / 流式条目 size 可能是 -1）：按块限长读，超限放弃
                val initial = if (entry.size in 1L..MAX_ENTRY_BYTES) entry.size.toInt() else 0
                val out = ByteArrayOutputStream(minOf(initial, 1 shl 20))
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()

    /** 供外部（缩略图/封面）复用的一次性编码判定 */
    fun charsetOf(file: File): Charset = TextDecoding.detectCharset(file)
}

/** FB2 里 l:href="#id" 的引用 */
private object HexRef {
    private val re = Regex("href\\s*=\\s*[\"']#([^\"']+)[\"']")

    fun find(html: String): String? = re.find(html)?.groupValues?.get(1)
}
