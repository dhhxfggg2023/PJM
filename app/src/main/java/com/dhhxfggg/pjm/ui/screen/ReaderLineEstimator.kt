package com.dhhxfggg.pjm.ui.screen

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 阅读器段落行数估算器。
 *
 * 为什么不再逐段构建 StaticLayout：十万段就是十万次完整断行测量加大量临时对象，
 * 首次打开会卡几秒。这里改成「按字符宽度估算 + 少量精确测量兜底」：
 *  - 纯全角 CJK（汉字/全角标点）等宽，行数 = ceil(字数 / 每行字数)，与
 *    StaticLayout 的贪心断行一致，连 breakText 都不用调；
 *  - 含半角/空白的段落用 [TextPaint.breakText] 逐行推进，仍是 O(字数) 的轻量扫描；
 *  - 只有估算恰好把禁则标点甩到行首/行尾时才做一次精确 StaticLayout，
 *    这类段落占比很小，既保证分页不系统性偏移，又保住打开速度。
 *
 * 精确测量与最终渲染共用同一套参数：FontFamily.Serif → Typeface.SERIF（由调用方设置的
 * TextPaint 带进来），includeFontPadding=false → setIncludePad(false)，
 * 行距倍数不影响断行位置，由调用方在算高度时单独乘。
 */
internal class ReaderLineEstimator(
    private val paint: TextPaint,
    private val widthPx: Int,
    private val cache: ReaderLineCache,
    private val cacheKey: String,
) {
    // 全角字符宽度就是一个字号；用它算每行能塞多少字
    private val fullWidthPx = paint.measureText(UNIFORM_SAMPLE).coerceAtLeast(1f)
    private val charsPerLine = (widthPx / fullWidthPx).toInt().coerceAtLeast(1)

    /** 估算一段占几行；空串返回 0，由调用方按空段规则算高度 */
    fun measure(text: String): Int {
        if (text.isEmpty()) return 0

        if (isUniformCjk(text)) {
            // 禁则检查放在缓存查询之前：同样长度的段落标点位置不同，结果可能不同，
            // 所以只缓存「确认安全」的纯等宽估算，危险段落每次都精确测量。
            if (hasBreakRisk(text, charsPerLine)) return exact(text)
            cache.uniformLineCount(cacheKey, text.length)?.let { return it }
            val lines = ceil(text.length.toFloat() / charsPerLine).toInt().coerceAtLeast(1)
            cache.storeUniformLineCount(cacheKey, text.length, lines)
            return lines
        }

        // 半角/空白/emoji 等混合文本：breakText 按字符贪心填充，和 SIMPLE 断行策略同构
        var lines = 0
        var pos = 0
        var risky = false
        while (pos < text.length) {
            val consumed = paint.breakText(text, pos, text.length, true, widthPx.toFloat(), null)
            // 单字符宽度必然小于行宽（charsPerLine >= 1），防御性退出避免死循环
            if (consumed <= 0) break
            pos += consumed
            lines++
            if (pos < text.length && isBreakRiskyAt(text, pos)) risky = true
        }
        if (risky) return exact(text)
        return lines.coerceAtLeast(1)
    }

    private fun hasBreakRisk(
        text: String,
        step: Int,
    ): Boolean {
        var boundary = step
        while (boundary < text.length) {
            if (isBreakRiskyAt(text, boundary)) return true
            boundary += step
        }
        return false
    }

    private fun isBreakRiskyAt(
        text: String,
        boundary: Int,
    ): Boolean {
        if (boundary <= 0 || boundary >= text.length) return false
        // 行首不允许出现闭标点，行尾不允许出现开标点；命中时 Minikin 会挪断点，
        // 纯宽度公式 / breakText 都模拟不了这一步，只能交回 StaticLayout。
        return text[boundary] in NO_LINE_START || text[boundary - 1] in NO_LINE_END
    }

    /** 与渲染一致的精确测量：只在少数风险段落上调用 */
    @Suppress("WrongConstant") // BREAK_STRATEGY_SIMPLE 值为 0，与 LineBreaker 的同名常量一致；LineBreaker 是 API 29 才有，minSdk 24 引用不了
    private fun exact(text: String): Int =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(SIMPLE_BREAK_STRATEGY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
            .lineCount

    companion object {
        private const val UNIFORM_SAMPLE = "汉"

        /**
         * Layout.BREAK_STRATEGY_SIMPLE 的值就是 0；LineBreaker.BREAK_STRATEGY_SIMPLE
         * 是 API 29 才有的类，minSdk 24 引用不了，所以这里沿用常量值。
         */
        @Suppress("WrongConstant")
        private const val SIMPLE_BREAK_STRATEGY = Layout.BREAK_STRATEGY_SIMPLE

        // 闭标点不能出现在行首，开标点不能出现在行尾
        private const val NO_LINE_START = "、。，．・：；？！）〕］｝〉》」』】〗〙〟’”％＠,.:;?!)]}>"
        private const val NO_LINE_END = "（［｛〈《「『【〔〖“‘([{<"

        private fun isUniformCjk(text: String): Boolean {
            for (ch in text) {
                val c = ch.code
                // 浊点/半浊点是零宽组合符，混进来就不再等宽
                if (c == 0x3099 || c == 0x309A || c == 0x309B || c == 0x309C) return false
                if (c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF) continue // CJK 汉字
                if (c in 0x3000..0x303F) continue // CJK 标点与符号（全角）
                if (c in 0xFF01..0xFF60) continue // 全角 ASCII 变体
                return false
            }
            return true
        }
    }
}

/**
 * 行数缓存。key 由「字号 px + 行宽 px」组成：
 * 行距、主题、书签这些变化都不改行数，可以直接复用；字号或页宽变了才换桶重算。
 *
 * 并发读写：测量在 Dispatchers.Default 上按核数切片并行，所以用 ConcurrentHashMap。
 */
internal class ReaderLineCache {
    private val byKey = ConcurrentHashMap<String, IntArray>()
    private val byLength = ConcurrentHashMap<String, ConcurrentHashMap<Int, Int>>()

    /** 整本的行数结果；行距变化走这条分支，一次断行都不用做 */
    fun lines(
        key: String,
        size: Int,
    ): IntArray? = byKey[key]?.takeIf { it.size == size }

    fun store(
        key: String,
        lines: IntArray,
    ) {
        byKey[key] = lines
    }

    /** 同一排版桶内，等宽 CJK 段落按长度共享行数 */
    fun uniformLineCount(
        key: String,
        length: Int,
    ): Int? = byLength[key]?.get(length)

    fun storeUniformLineCount(
        key: String,
        length: Int,
        lines: Int,
    ) {
        byLength.computeIfAbsent(key) { ConcurrentHashMap() }[length] = lines
    }
}
