package com.dhhxfggg.pjm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * S3-3：随机 offset 采样（稀疏 Fisher–Yates）的纯逻辑测试。
 *
 * 旧实现用 `ORDER BY RANDOM()` 对全表排序；新实现走 COUNT + OFFSET，
 * 采样算法本身必须保证：不重复、边界正确、不物化 O(total) 内存。
 * 这里复制同一算法（private 函数不便反射），锁定其不变量。
 */
class RandomOffsetSamplingTest {
    private fun sample(
        total: Int,
        wanted: Int,
        rnd: Random,
    ): List<Int> {
        val n = wanted.coerceAtMost(total)
        if (n <= 0) return emptyList()
        val picked = HashMap<Int, Int>(n * 2)
        for (j in 0 until n) {
            val i = j + rnd.nextInt(total - j)
            val a = picked[j] ?: j
            val b = picked[i] ?: i
            picked[j] = b
            picked[i] = a
        }
        return (0 until n).map { j -> picked[j] ?: j }
    }

    @Test
    fun sampleIsDistinctAndWithinBounds() {
        val rnd = Random(42)
        repeat(200) {
            val out = sample(40_000, 50, rnd)
            assertEquals(50, out.size)
            assertEquals("不得重复", 50, out.toSet().size)
            assertTrue("越界 offset: ${out.minOrNull()}", out.all { it in 0 until 40_000 })
        }
    }

    @Test
    fun sampleNeverExceedsVaultSize() {
        val out = sample(3, 50, Random(1))
        assertEquals(3, out.size)
        assertEquals(setOf(0, 1, 2), out.toSet())
    }

    @Test
    fun emptyVaultYieldsNoOffsets() {
        assertTrue(sample(0, 10, Random(1)).isEmpty())
    }
}
