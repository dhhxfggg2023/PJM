package com.dhhxfggg.pjm.domain.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB 取文：解包 → 读 OPF → 按 spine 顺序把每章的 XHTML 转成纯文本。
 *
 * 只做「读」，不改文件本身。返回值第二项是「段落序号 → 章节标题」，
 * 交给阅读器当目录用，这样书里标题不带「第 X 章」时目录也不会空。
 */
object EpubExtractor {
    data class Result(
        val text: String,
        val chapters: Map<Int, String>,
    )

    private val ITEM = Regex("""<item\s[^>]*>""")
    private val ITEMREF = Regex("""<itemref\s[^>]*>""")
    private val ATTR_ID = Regex("""\bid\s*=\s*"([^"]*)" """.trim())
    private val ATTR_HREF = Regex("""href\s*=\s*"([^"]*)" """.trim())

    // 逐章转换会反复调用，Regex 建一次即可（每次 new 都要重新编译模式，开销可观）
    private val SCRIPT_STYLE_RE = Regex("""(?is)<(script|style)[^>]*>.*?</\1>""")
    private val BR_RE = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val BLOCK_CLOSE_RE = Regex("""</(p|div|h[1-6]|li|tr|section|article)>""", RegexOption.IGNORE_CASE)
    private val LI_OPEN_RE = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE)
    private val WS_RE = Regex("[ \t\u00A0]+")
    private val BLANK_LINES_RE = Regex("""\n{3,}""")
    private val TAG_RE = Regex("""<[^>]+>""")
    private val NUM_ENTITY_RE = Regex("""&#(\d+);""")
    private val H_RE = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", RegexOption.DOT_MATCHES_ALL)
    private val NAV_HREF_RE = Regex("""<a\s[^>]*href\s*=\s*"([^"#]+)[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    /** 单个 zip 条目上限；超过视为 zip 炸弹，直接放弃该条目 */
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    fun extract(file: File): Result? {
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val container = readEntry(zip, "META-INF/container.xml") ?: return null
                val opfPath = attr(container, "full-path") ?: return null
                val opf = readEntry(zip, opfPath) ?: return null
                val base = opfPath.substringBeforeLast('/', "")

                val manifest = HashMap<String, String>()
                ITEM.findAll(opf).forEach { m ->
                    val tag = m.value
                    val id = attr(tag, "id") ?: return@forEach
                    val href = attr(tag, "href") ?: return@forEach
                    val type = attr(tag, "media-type").orEmpty()
                    if (type.contains("html")) manifest[id] = join(base, href)
                }

                val order = ArrayList<String>()
                ITEMREF.findAll(opf).forEach { m ->
                    val idref = attr(m.value, "idref") ?: return@forEach
                    manifest[idref]?.let { order.add(it) }
                }

                val titles = readTitles(zip, base, opf)
                val lines = ArrayList<String>()
                val chapterMap = HashMap<Int, String>()

                order.forEach { path ->
                    val html = readEntry(zip, path) ?: return@forEach
                    val body = htmlToText(html)
                    if (body.isBlank()) return@forEach
                    val title =
                        titles[path]
                            ?: firstHeading(html)
                            ?: path.substringAfterLast('/').substringBeforeLast('.')
                    chapterMap[lines.size] = title
                    lines.add(title)
                    body.split('\n').forEach { line -> if (line.isNotBlank()) lines.add(line.trim()) }
                }

                if (lines.isEmpty()) return null
                Result(lines.joinToString("\n"), chapterMap)
            }
        }.getOrNull()
    }

    private fun readEntry(
        zip: ZipFile,
        path: String,
    ): String? =
        runCatching {
            val entry = zip.getEntry(path) ?: zip.getEntry(path.replace('\\', '/')) ?: return null
            if (entry.size > MAX_ENTRY_BYTES) return null
            zip.getInputStream(entry).use { input ->
                // 大小未知（size == -1，ZIP64/流式）时不能 readBytes() 全量读入 ——
                // 被篡改的压缩包可声明极小压缩体积、解出几十 GB 直接打爆内存。
                // 这里按块限长读取，超过上限立即放弃该条目。
                val initialSize = if (entry.size in 1L..MAX_ENTRY_BYTES) entry.size.toInt() else 0
                val out = ByteArrayOutputStream(minOf(initialSize, 1 shl 20))
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY_BYTES) return null
                    out.write(buf, 0, n)
                }
                decode(out.toByteArray())
            }
        }.getOrNull()

    /** 先按 UTF-8 解，出现替换字符再按 GB18030 —— 不少中文书的 XHTML 是 GBK */
    private fun decode(bytes: ByteArray): String {
        val utf8 = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
        if (utf8 != null && utf8.none { it == '\uFFFD' }) return utf8
        return runCatching { bytes.toString(charset("GB18030")) }.getOrDefault(utf8 ?: "")
    }

    /** 从标签文本里取属性值：手写查找，避免正则里引号把 Kotlin 字符串写坏 */
    private fun attr(
        tag: String,
        name: String,
    ): String? {
        var i = 0
        val key = name
        while (true) {
            val idx = tag.indexOf(key, i, ignoreCase = true)
            if (idx < 0) return null
            // 裸子串匹配会命中属性值里的同名文本（如 href="...id=..." 里的 id）；
            // 真正的属性名前面必须是空白（标签名与第一个属性之间、属性与属性之间）。
            if (idx > 0 && tag[idx - 1].isWhitespace()) {
                var v = idx + key.length
                while (v < tag.length && tag[v].isWhitespace()) v++
                if (v < tag.length && tag[v] == '=') {
                    v++
                    while (v < tag.length && tag[v].isWhitespace()) v++
                    if (v < tag.length && (tag[v] == '"' || tag[v] == '\'')) {
                        val quote = tag[v]
                        val end = tag.indexOf(quote, v + 1)
                        if (end > v) return tag.substring(v + 1, end)
                    } else {
                        var end = v
                        while (end < tag.length && !tag[end].isWhitespace() && tag[end] != '>') end++
                        if (end > v) return tag.substring(v, end)
                    }
                }
            }
            i = idx + key.length
        }
    }

    /** 相对路径拼接（OPF 与 XHTML 都在同一个包目录下） */
    private fun join(
        base: String,
        href: String,
    ): String {
        val clean = href.substringBefore('#')
        return if (base.isEmpty()) clean else base.trimEnd('/') + "/" + clean
    }

    private fun readTitles(
        zip: ZipFile,
        base: String,
        opf: String,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        val navHref =
            Regex("""<item\s[^>]*properties\s*=\s*"[^"]*nav[^"]*"[^>]*>""").find(opf)?.value?.let { attr(it, "href") }
        if (navHref != null) {
            readEntry(zip, join(base, navHref))?.let { nav ->
                NAV_HREF_RE
                    .findAll(nav)
                    .forEach { m ->
                        val href = join(base, m.groupValues[1].trim())
                        val title = stripTags(m.groupValues[2]).trim()
                        if (title.isNotEmpty()) out.putIfAbsent(href, title)
                    }
            }
        }
        return out
    }

    private fun firstHeading(html: String): String? =
        H_RE
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let { stripTags(it).trim().ifBlank { null } }

    private fun htmlToText(html: String): String {
        var s = html.replace(SCRIPT_STYLE_RE, " ")
        s = s.replace(BR_RE, "\n")
        s = s.replace(BLOCK_CLOSE_RE, "\n")
        s = s.replace(LI_OPEN_RE, "· ")
        s = stripTags(s)
        s = unescape(s)
        s = s.replace(WS_RE, " ")
        s = s.lines().joinToString("\n") { it.trim() }
        s = s.replace(BLANK_LINES_RE, "\n\n")
        return s.trim()
    }

    private fun stripTags(s: String): String = s.replace(TAG_RE, "")

    private fun unescape(s: String): String =
        s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(NUM_ENTITY_RE) { m ->
                m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
}
