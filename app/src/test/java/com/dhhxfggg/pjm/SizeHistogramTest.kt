package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.ui.viewmodel.buildSizeHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 热力图「单个文件大小分布」直方图的纯逻辑测试。
 *
 * 回归点：旧实现先把每个分类的 size 等距抽样到最多 200 条再算直方图，
 * 界面上「共 N 个文件」显示的就是样本数（永远 ≤ 200），和真实文件数对不上。
 * 现在 buildSizeHistogram 必须吃**全量**数据：total 恒等于输入条数，
 * 各档 count 之和也恒等于 total。
 */
class SizeHistogramTest {
    /** 造一批覆盖多个数量级的 size（1 B ~ 几十 MB），条数由 total 决定 */
    private fun sizes(
        total: Int,
        seed: Int = 7,
    ): List<Long> {
        val rnd = Random(seed)
        return List(total) {
            // 1 B ~ 约 50 MB 之间随机，保证有多个数量级
            (1L shl rnd.nextInt(0, 26)) + rnd.nextLong(0, 1024)
        }
    }

    @Test
    fun totalEqualsInputSizeEvenBeyondSampleLimit() {
        // 5000 条远超旧实现的 200 条抽样上限：这是本 bug 的回归点
        val input = sizes(5000)
        val histogram = buildSizeHistogram(input)
        assertEquals("总数必须是真实条数，不能被抽样截断", 5000, histogram.total)
        assertEquals(5000, histogram.buckets.sumOf { it.count })
    }

    @Test
    fun bucketCountsSumToTotalForVariousSizes() {
        listOf(0, 1, 2, 3, 17, 199, 200, 201, 9999).forEach { total ->
            val histogram = buildSizeHistogram(sizes(total))
            assertEquals("total 字段错误 size=$total", total, histogram.total)
            assertEquals("各档之和应等于 total（size=$total）", total, histogram.buckets.sumOf { it.count })
        }
    }

    @Test
    fun emptyInputYieldsEmptyHistogram() {
        val histogram = buildSizeHistogram(emptyList())
        assertEquals(0, histogram.total)
        assertTrue("空输入不应产生任何档位", histogram.buckets.isEmpty())
        assertEquals(0, histogram.maxCount)
    }

    @Test
    fun identicalValuesCollapseToSingleBucket() {
        val histogram = buildSizeHistogram(List(500) { 4096L })
        assertEquals(500, histogram.total)
        assertEquals("全部相同值只出一档", 1, histogram.buckets.size)
        val only = histogram.buckets.single()
        assertEquals(500, only.count)
        assertTrue("唯一一档就是最后一档", only.isLast)
        assertEquals(4096L, only.start)
        assertEquals(4096L, only.end)
    }

    @Test
    fun mergedBucketsHaveNoZeroCount() {
        // 稀疏数据：几个数量级上各落一条，中间必然产生空档，应被合并掉
        val sparse = listOf(1L, 2L, 3L, 1L shl 20, 1L shl 22, 1L shl 24, 1L shl 26)
        val histogram = buildSizeHistogram(sparse)
        assertEquals(sparse.size, histogram.total)
        assertTrue("合并空档后每档 count 必须 > 0", histogram.buckets.all { it.count > 0 })
        assertEquals(sparse.size, histogram.buckets.sumOf { it.count })
        // 只有最后一档 isLast=true
        assertEquals(1, histogram.buckets.count { it.isLast })
        assertTrue("最后一档必须是列表末尾", histogram.buckets.last().isLast)
        assertFalse("前面的档不能标记为最后一档", histogram.buckets.dropLast(1).any { it.isLast })
    }

    @Test
    fun bucketRangesAreOrderedAndCoverInput() {
        val input = sizes(1234)
        val histogram = buildSizeHistogram(input)
        assertTrue("档位数应有上界", histogram.buckets.size <= 6)
        val starts = histogram.buckets.map { it.start }
        assertEquals("档位按 start 单调递增", starts.sorted(), starts)
        assertEquals("第一档从最小值开始", input.min(), histogram.buckets.first().start)
        assertEquals("最后一档到最大值结束", input.max(), histogram.buckets.last().end)
        assertTrue("档位区间左闭右开，end 必须大于 start", histogram.buckets.all { it.end > it.start })
    }

    @Test
    fun bucketBoundaryValueCountsIntoMatchingBucket() {
        // 回归点：恰好等于某档下界的文件必须计入该档，而点击该档用 [start, end) 查询要能查到它。
        // 旧实现命中边界时统一减 1，计数进了上一档、查询落在下一档，两边对不上。
        val sizes = listOf(1L, 2L, 4L, 10L, 21L, 46L, 100L)
        val histogram = buildSizeHistogram(sizes)
        assertEquals("各档计数之和必须等于总数", sizes.size, histogram.buckets.sumOf { it.count })
        histogram.buckets.forEach { bucket ->
            // 每一档的 [start, end) 里必须真的有对应数量的输入（末档是 [start, +∞)）
            val actual =
                if (bucket.isLast) {
                    sizes.count { it >= bucket.start }
                } else {
                    sizes.count { it >= bucket.start && it < bucket.end }
                }
            assertEquals("档 " + bucket.start + ".." + bucket.end + " 的计数与区间内容不一致", actual, bucket.count)
        }
    }

    @Test
    fun maxCountMatchesLargestBucket() {
        val histogram = buildSizeHistogram(sizes(3000))
        assertEquals(histogram.buckets.maxOf { it.count }, histogram.maxCount)
    }
}
