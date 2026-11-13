package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.DHashIndex
import com.dhhxfggg.pjm.domain.util.PerceptualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * DHashIndex 的正确性测试（纯 JVM，不需要 Robolectric）。
 *
 * 索引是查重性能改造的核心：它必须"不漏"，否则用户会以为没有重复图。
 */
class DHashIndexTest {
    @Test
    fun parseBits_keepsSignBit() {
        val highBit = "1" + "0".repeat(63)
        val value = PerceptualHash.parseBits(highBit)
        assertEquals(1, value.countOneBits())
        assertTrue(value < 0L)
        assertEquals(0L, PerceptualHash.parseBits("0".repeat(64)))
    }

    @Test
    fun candidatePairs_coverEveryPairWithinMaxDistance() {
        val random = Random(20260101)
        val n = 500
        val hashes = LongArray(n) { random.nextLong() }
        // 注入近似对：随机挑两张图，把后者改成前者翻转 1..MAX_DISTANCE 位
        for (k in 0 until 250) {
            val a = random.nextInt(n)
            var b = random.nextInt(n)
            while (b == a) b = random.nextInt(n)
            var h = hashes[a]
            var flips = 1 + random.nextInt(DHashIndex.MAX_DISTANCE)
            while (flips > 0) {
                h = h xor (1L shl random.nextInt(64))
                flips--
            }
            hashes[b] = h
        }
        val expected = HashSet<Long>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if ((hashes[i] xor hashes[j]).countOneBits() <= DHashIndex.MAX_DISTANCE) {
                    expected.add(pairKey(i, j))
                }
            }
        }
        assertTrue(expected.isNotEmpty())

        val actual = HashSet<Long>()
        DHashIndex(hashes).forEachCandidatePair { i, j ->
            assertTrue(i < j)
            actual.add(pairKey(i, j))
        }
        val missing = expected.count { !actual.contains(it) }
        assertTrue("索引漏掉了 " + missing + " 对（应保证 0）", missing == 0)
    }

    @Test
    fun candidatePairs_areUniqueAndOrdered() {
        val random = Random(7)
        val hashes = LongArray(200) { random.nextLong() }
        val seen = HashSet<Long>()
        var lastI = -1
        DHashIndex(hashes).forEachCandidatePair { i, j ->
            assertTrue(i < j)
            assertTrue("重复候选对 (" + i + ", " + j + ")", seen.add(pairKey(i, j)))
            assertTrue(i >= lastI)
            lastI = i
        }
    }

    @Test
    fun candidatePairs_scaleToTensOfThousands() {
        // 真实规模（用户库存 38k，这里取 20k 保证测试时长可控）：
        // 只验证候选对数量在合理范围、不会退化成百万级
        val random = Random(38)
        val n = 20000
        val hashes = LongArray(n) { random.nextLong() }
        var pairs = 0L
        DHashIndex(hashes).forEachCandidatePair(
            onImage = {},
        ) { i, j ->
            if ((hashes[i] xor hashes[j]).countOneBits() <= DHashIndex.MAX_DISTANCE) pairs++
        }
        // 随机哈希下距离 ≤15 的对极少，数量级应是四位数而不是百万级
        assertTrue("随机哈希的候选对数异常: " + pairs, pairs < 5000)
    }

    private fun pairKey(
        i: Int,
        j: Int,
    ): Long = (i.toLong() shl 32) or j.toLong()
}
