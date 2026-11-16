package com.dhhxfggg.pjm.domain.reader

import java.io.File
import java.nio.charset.Charset

/**
 * 文本编码识别。
 *
 * 中文 txt 的现实情况：GBK/GB18030 占很大比例；UTF-16 有 BOM 时好认，
 * 没有 BOM 的 UTF-16 和纯 ASCII 又长得像。这里的策略是「先看 BOM，再看字节规律，
 * 最后用候选编码解码后比谁更像正常中文」，比原来「UTF-8 失败才退回 GBK」准得多，
 * 也不会把 UTF-8 里的少数替换字符误判成 GBK 从而整本乱码。
 */
object TextDecoding {
    /** 一段文本里「像正常内容」的得分：常用字符加分，控制字符和替换字符大幅扣分 */
    fun score(text: String): Double {
        if (text.isEmpty()) return Double.NEGATIVE_INFINITY
        var score = 0.0
        // 只看前 8K 字符就够判断了，大文件不必整本扫
        val limit = minOf(text.length, 8192)
        for (i in 0 until limit) {
            val c = text[i]
            when {
                c == '\uFFFD' -> score -= 12.0
                c == '\u0000' -> score -= 20.0
                c.code < 0x09 -> score -= 8.0
                c.code in 0x0E..0x1F -> score -= 8.0
                // 常用中文标点 / 汉字区间
                c.code in 0x4E00..0x9FFF -> score += 3.0
                c.code in 0x3000..0x303F -> score += 3.0
                c.code in 0xFF00..0xFFEF -> score += 1.0
                c.code in 0x20..0x7E -> score += 1.0
                c == '\n' || c == '\r' || c == '\t' -> score += 1.0
                else -> score += 0.2
            }
        }
        return score / limit
    }

    private fun isUtf8Bom(head: ByteArray) = head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()

    /** BOM 识别（优先于一切猜测） */
    fun bomCharset(head: ByteArray): Charset? =
        when {
            isUtf8Bom(head) -> Charsets.UTF_8
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> null
        }

    fun bomLength(head: ByteArray): Int =
        when {
            isUtf8Bom(head) -> 3
            head.size >= 2 && (head[0] == 0xFF.toByte() || head[0] == 0xFE.toByte()) -> 2
            else -> 0
        }

    /**
     * 无 BOM 时的 UTF-16 猜测：UTF-16LE 的 ASCII 字符低字节在前、高字节是 0，
     * BE 则相反。中文正文里 ASCII 通常不少（数字、标点、英文），这个规律够可靠。
     */
    private fun guessUtf16(
        bytes: ByteArray,
        limit: Int,
    ): Charset? {
        // LE 的 ASCII 是「低字节在前、高字节 0」，所以第二个字节（index+1）为 0 才说明是 LE；
        // BE 反过来，是第一个字节为 0。旧实现把两者写反了，UTF-16LE 会被判成 BE 导致整本乱码。
        var zeroAtHighByteOfLe = 0
        var zeroAtHighByteOfBe = 0
        var samples = 0
        var i = 0
        while (i + 1 < limit && samples < 4096) {
            if (bytes[i + 1].toInt() == 0) zeroAtHighByteOfLe++
            if (bytes[i].toInt() == 0) zeroAtHighByteOfBe++
            i += 2
            samples++
        }
        if (samples == 0) return null
        return when {
            zeroAtHighByteOfLe > samples * 3 / 10 && zeroAtHighByteOfBe < samples / 20 -> Charsets.UTF_16LE
            zeroAtHighByteOfBe > samples * 3 / 10 && zeroAtHighByteOfLe < samples / 20 -> Charsets.UTF_16BE
            else -> null
        }
    }

    /** 候选编码：GB18030 是 GBK 的超集，先试它即可覆盖 GBK / GB2312 */
    private val candidates: List<Charset?> = listOf(Charsets.UTF_8, runCatching { Charset.forName("GB18030") }.getOrNull())

    /** 解码一段字节（自动识别编码） */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        bomCharset(bytes)?.let { bom ->
            val skip = bomLength(bytes)
            return runCatching { String(bytes, skip, bytes.size - skip, bom) }.getOrDefault("")
        }
        guessUtf16(bytes, bytes.size)?.let { cs ->
            return runCatching { String(bytes, cs) }.getOrDefault("")
        }
        var best = ""
        var bestScore = Double.NEGATIVE_INFINITY
        for (cs in candidates) {
            if (cs == null) continue
            val text = runCatching { String(bytes, cs) }.getOrNull() ?: continue
            val s = score(text)
            if (s > bestScore) {
                bestScore = s
                best = text
            }
        }
        return best
    }

    /** 只读文件开头一小段判断编码，再整本按该编码解码（避免整本试错两遍） */
    fun detectCharset(
        file: File,
        sampleBytes: Int = 64 * 1024,
    ): Charset {
        val size = minOf(file.length(), sampleBytes.toLong()).toInt().coerceAtLeast(1)
        val head = ByteArray(size)
        runCatching { file.inputStream().use { it.read(head) } }
        bomCharset(head)?.let { return it }
        guessUtf16(head, head.size)?.let { return it }
        var best: Charset = Charsets.UTF_8
        var bestScore = Double.NEGATIVE_INFINITY
        for (cs in candidates) {
            if (cs == null) continue
            val text = runCatching { String(head, cs) }.getOrNull() ?: continue
            val s = score(text)
            if (s > bestScore) {
                bestScore = s
                best = cs
            }
        }
        return best
    }
}
