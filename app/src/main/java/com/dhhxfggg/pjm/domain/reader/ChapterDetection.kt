package com.dhhxfggg.pjm.domain.reader

/**
 * 章节识别与正文规整。
 *
 * 旧实现只有一个大正则，且对「第 X 章」开头的任何行都认，导致正文里的
 * 「第二天早上第三节课」这类句子也可能被当成章节标题。这里分三步：
 *  1. 明确不认的（太长、以句号结尾、含对话引号）；
 *  2. 强特征（顶部带「第 X 章」「Chapter N」「序章」「楔子」等，且长度受控）；
 *  3. 弱特征（单独成行的短标题，前后有空行、不含标点结尾）—— 只在前两级连续命中间隔过大时才补。
 */
object ChapterDetection {
    /** 章节标题最大长度：超过这个长度的行基本是正文 */
    private const val MAX_TITLE_LENGTH = 40

    private val STRONG =
        Regex(
            "^\\s*(?:第[0-9零一二三四五六七八九十百千两]{1,10}[章节回卷集部篇]|" +
                "[0-9]{1,4}[、.．]\\s*[^\\s]|" +
                "[（(][0-9一二三四五六七八九十]{1,3}[）)]|" +
                "Chapter\\s+[0-9IVXLC]{1,6}|" +
                "序章|序言|楔子|引子|前言|后记|尾声|终章|番外)" +
                "(?:\\s*[:：、.·—\\-]?\\s*.{0,30})?\\s*$",
            RegexOption.IGNORE_CASE,
        )

    /** 形如「卷一 风起」「上部」「第 1 部」这种卷级标题 */
    private val VOLUME =
        Regex(
            "^\\s*(?:第[0-9零一二三四五六七八九十百千两]{1,10}[卷部篇]|" +
                "卷[0-9一二三四五六七八九十]{1,3}|" +
                "[上中下]部(?:分)?)(?:\\s*[:：、.·—\\-]?\\s*.{0,30})?\\s*$",
        )

    /** 结尾是这些标点的，一律当正文（标题不会用句号/问号/感叹号收尾） */
    private val BODY_TAIL = Regex(".*[。！？；…，,;:：]$")

    /** 对话或说明性长句的特征 */
    private val DIALOGUE = Regex("^\\s*[\"“”「」『』'‘’].*|[\"“”「」『』].*[\"“”「」『』]")

    /**
     * 判断一行是否是章节标题。
     * @param allowWeak 是否允许「弱特征」判定（仅当强特征稀疏时开启）
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
            // 弱特征：短、不含句读、没有数字开头的续写感
            if (text.length <= 20 && !text.any { it in "。！？；…" } && text.count { it == ' ' } <= 3) {
                return true
            }
        }
        return false
    }

    fun isVolumeTitle(line: String): Boolean {
        val text = line.trim()
        return text.length <= MAX_TITLE_LENGTH && VOLUME.matches(text)
    }

    /**
     * 从段落列表里识别章节，生成目录。
     *
     * 强特征优先；当整本一条都识别不出来、或识别得太稀疏（平均 500 段以上才一章）时，
     * 再用弱特征补一遍 —— 很多精校 txt 的标题就是裸的「第一章」「Chapter 1」之外的短行。
     */
    fun detect(paragraphs: List<String>): List<BookChapter> {
        val strong = collect(paragraphs, allowWeak = false)
        val coverage = if (strong.isEmpty()) Int.MAX_VALUE else paragraphs.size / strong.size
        val chapters = if (strong.size >= 2 && coverage <= 500) strong else collect(paragraphs, allowWeak = true)
        return chapters.ifEmpty { strong }
    }

    private fun collect(
        paragraphs: List<String>,
        allowWeak: Boolean,
    ): List<BookChapter> {
        val out = ArrayList<BookChapter>()
        paragraphs.forEachIndexed { index, raw ->
            if (!isChapterTitle(raw, allowWeak)) return@forEachIndexed
            val title = normalizeTitle(raw)
            if (title.isEmpty()) return@forEachIndexed
            // 相邻两行完全相同（有的书每章开头重复一遍章名）只保留一条；
            // 但不能因为「挨得近」就丢弃 —— 「第一卷 风起」紧跟「第一章 开端」是正常结构。
            if (out.lastOrNull()?.title == title && out.last().startParagraph == index - 1) return@forEachIndexed
            out.add(BookChapter(title, index, if (isVolumeTitle(raw)) 0 else 1))
        }
        return out
    }

    private fun normalizeTitle(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()
}
