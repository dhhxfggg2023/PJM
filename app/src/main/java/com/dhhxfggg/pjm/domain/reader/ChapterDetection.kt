package com.dhhxfggg.pjm.domain.reader

/**
 * 章节识别：从段落列表里生成目录。
 *
 * 分两层：
 *  1. 强特征——「第X章」「Chapter 1」「序章」这类明确标记，单行即可判定；
 *  2. 聚类——强特征认不出时的兜底。把行首编号归一化（「1 」「2 」都变成「# 」），
 *     同一种编号模式出现两次以上才认作章节。
 *
 * 为什么不再用「短行就算标题」：正文里到处是「他笑了。」这种短句，单凭长度和
 * 无标点根本分不开，结果就是目录里塞满正文。聚类要求「同一个模式反复出现」，
 * 正文短句格式各异凑不够数，而「1 雨落 / 2 风起」这种规整标题能稳定识别。
 */
object ChapterDetection {
    /** 章节标题最大长度：超过这个长度的行基本是正文 */
    private const val MAX_TITLE_LENGTH = 40

    private const val CN_NUM = "零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾"
    private const val NUM = "[0-9$CN_NUM]"

    /** 英文序数词，覆盖 Chapter One / Chapter Twenty 这类写法 */
    private const val EN_NUM =
        "One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|" +
            "Eleven|Twelve|Thirteen|Fourteen|Fifteen|Sixteen|Seventeen|Eighteen|Nineteen|Twenty"

    /** 强特征：明确的章节标记，标题部分不允许出现句读（避免正文被当成标题） */
    private val STRONG =
        Regex(
            "^\\s*(?:" +
                "第\\s*$NUM{1,12}\\s*[章节回集篇部卷]" +
                "|[（(]\\s*$NUM{1,4}\\s*[）)]" +
                "|[0-9]{1,4}[、.．]\\s*[^\\s]" +
                "|[Cc]hapter\\s+(?:[0-9IVXLCDM]{1,8}|$EN_NUM)\\b" +
                "|序章|序言|楔子|引子|前言|后记|尾声|终章|番外|开篇|大结局|完结篇" +
                ")[ \\t\u3000]*[:：、.·—\\-]?[ \\t\u3000]*[^，。！？；：,.!?;:]{0,28}\\s*$",
            RegexOption.IGNORE_CASE,
        )

    /** 卷/部级标题，目录里缩进一层 */
    private val VOLUME =
        Regex(
            "^\\s*(?:第\\s*$NUM{1,12}\\s*[卷部篇]|卷\\s*$NUM{1,4}|[上中下]部(?:分)?)" +
                "[ \\t\u3000]*[:：、.·—\\-]?[ \\t\u3000]*[^，。！？；：,.!?;:]{0,28}\\s*$",
        )

    /**
     * 编号标记：用于聚类时归一化。只取行首的编号部分，后面的自由标题文字不参与签名，
     * 这样「第一章 风起」「第二章 云涌」能归成同一类。
     */
    private val MARKERS =
        listOf(
            Regex("第\\s*$NUM{1,12}\\s*[章节回集篇部卷节]"),
            Regex("[（(]\\s*$NUM{1,4}\\s*[）)]"),
            Regex("[Cc]hapter\\s+(?:[0-9IVXLCDM]{1,8}|$EN_NUM)\\b", RegexOption.IGNORE_CASE),
            Regex("卷$NUM{1,4}"),
            Regex("[0-9]{1,4}[、.．]"),
            Regex("[0-9]{1,4}(?=\\s|\u3000)"),
        )

    /** 以句末标点收尾的一律当正文 */
    private val BODY_TAIL = Regex(".*[。！？；…，,;:：]$")

    /** 对话（以引号开头）——标题不会这样写 */
    private val DIALOGUE = Regex("^[\\\"“”「」『』'‘’]")

    /**
     * 单行强特征判定。
     * @param allowWeak 兼容旧调用：额外认「行首编号 + 极短标题」，但不认裸短句。
     */
    fun isChapterTitle(
        line: String,
        allowWeak: Boolean = false,
    ): Boolean {
        val text = line.trim()
        if (text.isEmpty() || text.length > MAX_TITLE_LENGTH) return false
        if (BODY_TAIL.matches(text)) return false
        if (DIALOGUE.containsMatchIn(text)) return false
        if (STRONG.matches(text)) return true
        if (allowWeak) {
            val marker = firstMarker(text) ?: return false
            val rest = text.substring(marker.length).trim(' ', '\u3000', ':', '：', '-', '—')
            if (rest.length <= 16 && !rest.any { it in "，。！？；：,.!?;:" }) return true
        }
        return false
    }

    fun isVolumeTitle(line: String): Boolean {
        val text = line.trim()
        return text.length <= MAX_TITLE_LENGTH && VOLUME.matches(text)
    }

    /**
     * 从段落列表识别章节。强特征优先；认不出或过于稀疏时用聚类补。
     * 最终结果至少两章才返回——一本没有目录的书不该凭一两行巧合生成假目录。
     */
    fun detect(paragraphs: List<String>): List<BookChapter> {
        if (paragraphs.isEmpty()) return emptyList()
        val strong = collect(paragraphs) { isChapterTitle(it, false) }
        val coverage = if (strong.isEmpty()) Int.MAX_VALUE else paragraphs.size / strong.size
        if (strong.size >= 2 && coverage <= 500) return dedupe(strong)

        val clustered = collectClusters(paragraphs)
        val merged = LinkedHashMap<Int, BookChapter>()
        strong.forEach { merged[it.startParagraph] = it }
        clustered.forEach { merged.putIfAbsent(it.startParagraph, it) }
        val result = merged.values.toList()
        return if (result.size >= 2) dedupe(result) else strong
    }

    private fun collect(
        paragraphs: List<String>,
        predicate: (String) -> Boolean,
    ): List<BookChapter> {
        val out = ArrayList<BookChapter>()
        paragraphs.forEachIndexed { index, raw ->
            if (predicate(raw)) {
                val title = normalizeTitle(raw)
                if (title.isNotEmpty()) {
                    out.add(BookChapter(title, index, if (isVolumeTitle(raw)) 0 else 1))
                }
            }
        }
        return out
    }

    /** 聚类：同一编号模式出现两次以上才算章节 */
    private fun collectClusters(paragraphs: List<String>): List<BookChapter> {
        val sigByIndex = HashMap<Int, String>()
        val counts = HashMap<String, Int>()
        paragraphs.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX_TITLE_LENGTH) return@forEachIndexed
            if (BODY_TAIL.matches(text) || DIALOGUE.containsMatchIn(text)) return@forEachIndexed
            val sig = markerSignature(text) ?: return@forEachIndexed
            sigByIndex[index] = sig
            counts[sig] = (counts[sig] ?: 0) + 1
        }
        val valid = counts.filter { it.value >= 2 }.keys
        if (valid.isEmpty()) return emptyList()
        val out = ArrayList<BookChapter>()
        sigByIndex.forEach { (index, sig) ->
            if (sig in valid) {
                out.add(
                    BookChapter(
                        normalizeTitle(paragraphs[index]),
                        index,
                        if (isVolumeTitle(paragraphs[index])) 0 else 1,
                    ),
                )
            }
        }
        return out
    }

    /** 返回行首编号标记的原始文本；没匹配到返回 null */
    private fun firstMarker(text: String): String? = MARKERS.firstNotNullOfOrNull { regex -> regex.find(text)?.takeIf { it.range.first == 0 }?.value }

    /** 把行首编号里的数字换成 #，作为聚类签名 */
    private fun markerSignature(text: String): String? {
        val marker = firstMarker(text) ?: return null
        val normalized = StringBuilder()
        for (ch in marker) {
            normalized.append(if (ch.isDigit() || ch in CN_NUM) '#' else ch)
        }
        return normalized.toString().lowercase()
    }

    /** 相邻两行标题完全相同（有的书每章开头重复一遍章名）只留一条 */
    private fun dedupe(chapters: List<BookChapter>): List<BookChapter> {
        val out = ArrayList<BookChapter>()
        for (chapter in chapters.sortedBy { it.startParagraph }) {
            val last = out.lastOrNull()
            if (last != null && last.title == chapter.title && chapter.startParagraph == last.startParagraph + 1) {
                continue
            }
            out.add(chapter)
        }
        return out
    }

    private fun normalizeTitle(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()
}
