package com.dhhxfggg.pjm.domain.util

/**
 * 64 位感知哈希的候选对倒排索引（替代 O(n²) 全量两两比较）。
 *
 * ## 为什么需要它
 * 38,000 张图片两两比较是 7.2 亿次；即使每次只做一次汉明距离，也要数十秒到数分钟，
 * 而原实现还要把候选对物化进 LongArray、并对每一对读 4 个缓存文件 —— 内存与 I/O 同时爆。
 *
 * ## 做法
 * 把 64 位 dHash 切成 [CHUNK_COUNT] 个 [CHUNK_BITS] 位块，对每个块建立
 * 「块值 → 图片下标」倒排表（CSR，紧凑 int 数组，大库约 1.6MB）。
 * 查询时枚举与当前块值汉明距离 ≤ [RADIUS] 的所有键（预生成的 697 个掩码）。
 *
 * **召回保证**：若一对图片的总汉明距离 ≤ [MAX_DISTANCE]，则 4 个块中必有一块
 * 差异 ≤ [RADIUS]（否则总差至少 4×(RADIUS+1) = 16），该块一定被枚举到。
 * 索引只负责"不漏"，不负责"不多"—— 距离超标、宽高比/面积不符的对由调用方过滤。
 *
 * ## 复杂度
 * 建表 O(CHUNK_COUNT × n)；查询 O(n × CHUNK_COUNT × |PROBE_MASKS|)，大库
 * 约 1.06 亿次定长数组区间扫描，与 n 近似线性。
 */
internal class DHashIndex(
    private val hashes: LongArray,
) {
    companion object {
        const val CHUNK_BITS: Int = 16
        const val CHUNK_COUNT: Int = 4
        const val RADIUS: Int = 3

        /** 索引保证能覆盖的最大汉明距离（含）。 */
        const val MAX_DISTANCE: Int = CHUNK_COUNT * (RADIUS + 1) - 1

        private const val TABLE_SIZE: Int = 1 shl CHUNK_BITS

        /** 所有汉明重量 ≤ [RADIUS] 的 16 位掩码（697 个）。 */
        private val PROBE_MASKS: IntArray by lazy { buildProbeMasks() }

        private fun buildProbeMasks(): IntArray {
            val masks = ArrayList<Int>(700)
            for (mask in 0 until TABLE_SIZE) {
                if (mask.countOneBits() <= RADIUS) masks.add(mask)
            }
            return masks.toIntArray()
        }
    }

    /**
     * 遍历所有"至少共享一个 16 位块（容差 [RADIUS] 位）"的无序下标对。
     *
     * @param onImage 每开始处理一张图时回调（用于进度显示与取消检查），参数为图片下标
     * @param action 收到候选对 (i, j)，保证 i < j、同一对只回调一次
     */
    fun forEachCandidatePair(
        onImage: (Int) -> Unit = {},
        action: (Int, Int) -> Unit,
    ) {
        val n = hashes.size
        if (n < 2) return

        val chunkValues = Array(CHUNK_COUNT) { IntArray(n) }
        val offsets = Array(CHUNK_COUNT) { IntArray(TABLE_SIZE + 1) }
        for (i in 0 until n) {
            val hash = hashes[i]
            for (c in 0 until CHUNK_COUNT) {
                val value = ((hash ushr (c * CHUNK_BITS)) and 0xFFFFL).toInt()
                chunkValues[c][i] = value
                offsets[c][value]++
            }
        }
        for (c in 0 until CHUNK_COUNT) {
            val off = offsets[c]
            var sum = 0
            for (key in 0 until TABLE_SIZE) {
                val count = off[key]
                off[key] = sum
                sum += count
            }
            off[TABLE_SIZE] = sum
        }
        val cursor = Array(CHUNK_COUNT) { offsets[it].copyOf(TABLE_SIZE) }
        val buckets = Array(CHUNK_COUNT) { IntArray(n) }
        for (i in 0 until n) {
            for (c in 0 until CHUNK_COUNT) {
                val value = chunkValues[c][i]
                buckets[c][cursor[c][value]++] = i
            }
        }

        // 同一对可能被多个块命中：用「本次查询的图片下标」做标记去重，避免额外哈希集合
        val seen = IntArray(n) { -1 }
        val masks = PROBE_MASKS
        for (i in 0 until n) {
            onImage(i)
            for (c in 0 until CHUNK_COUNT) {
                val base = chunkValues[c][i]
                val off = offsets[c]
                val bucket = buckets[c]
                for (m in 0 until masks.size) {
                    val key = base xor masks[m]
                    var pos = off[key]
                    val end = off[key + 1]
                    while (pos < end) {
                        val j = bucket[pos++]
                        if (j <= i || seen[j] == i) continue
                        seen[j] = i
                        action(i, j)
                    }
                }
            }
        }
    }
}

/** dHash 位串与 Long 的互转（无符号位模式语义）。 */
internal object PerceptualHash {
    /**
     * 把 64 位二进制串（'0'/'1'）按**位模式**解析为 Long。
     *
     * 不能用 `String.toLongOrNull(2)`：它按有符号数解析，首位为 '1' 时溢出返回 null，
     * 调用方若回退成 0L，约一半图片的哈希就全塌缩成同一个值（原实现的实际 bug）。
     */
    fun parseBits(bits: String): Long {
        var value = 0L
        var count = 0
        for (ch in bits) {
            if (ch != '0' && ch != '1') continue
            if (count == 64) break
            value = (value shl 1) or (if (ch == '1') 1L else 0L)
            count++
        }
        // 不足 64 位时左对齐补零，与 dHash 的 64 位语义一致
        while (count < 64) {
            value = value shl 1
            count++
        }
        return value
    }
}
