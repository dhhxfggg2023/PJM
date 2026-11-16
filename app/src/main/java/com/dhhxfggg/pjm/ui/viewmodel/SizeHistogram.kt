package com.dhhxfggg.pjm.ui.viewmodel

import kotlin.math.exp
import kotlin.math.ln

/**
 * 单个档位：左闭右开到 [end]，[isLast] 为 true 时表示"最后一档"（文案用"X 以上"）。
 */
data class SizeBucket(
    val start: Long,
    val end: Long,
    val count: Int,
    val isLast: Boolean,
)

/**
 * 某个分类下所有文件的「大小分布」统计结果。
 *
 * [total] 是**真实**参与统计的文件条数（即传入列表的 size），不是抽样后的样本数；
 * 各档 [SizeBucket.count] 之和恒等于 [total]。界面上的「共 N 个文件」直接用 [total]。
 */
data class SizeHistogram(
    val total: Int,
    val buckets: List<SizeBucket>,
) {
    /** 各档中的最大计数（空直方图为 0），供进度条求比例用 */
    val maxCount: Int
        get() = buckets.maxOfOrNull { it.count } ?: 0

    companion object {
        val EMPTY = SizeHistogram(total = 0, buckets = emptyList())
    }
}

/**
 * 按数据本身切档，而不是写死「<0.5 MB / 0.5–1 MB / …」那一套。
 *
 * 固定档位有两个毛病：
 *  1. 没有代表性 —— 图片几乎全在 1 MB 以下，用固定的十档，前两档就吃掉 95%，
 *     后八档永远接近 0，看不出"到底集中在哪里"；
 *  2. 浪费显示空间 —— 中间夹着若干计数为 0 的档，白白占掉好几行。
 *
 * 这里改成：取真实的最小/最大字节数，在两者之间按**几何级数**（对数刻度）等分，
 * 再把计数为 0 的档并进邻居里 —— 每行都有数据，且档宽随数量级自适应。
 *
 * 纯函数：输入**全量** size 列表，输出直方图，不抽样、不丢数据；
 * 调用方（ViewModel）算完即丢原始列表，UI State 里只留 [SizeHistogram]。
 */
fun buildSizeHistogram(
    sizes: List<Long>,
    targetBuckets: Int = 6,
): SizeHistogram {
    if (sizes.isEmpty()) return SizeHistogram.EMPTY
    val total = sizes.size
    val sorted = sizes.sorted()
    val min = sorted.first().coerceAtLeast(1L)
    val max = sorted.last().coerceAtLeast(min)
    if (min == max) return SizeHistogram(total, listOf(SizeBucket(min, max, total, isLast = true)))

    // 不同取值太少时不必强行切满，避免出现「同一档里只有一个值」的空档感
    val distinct = sorted.distinct().size
    val bucketCount = targetBuckets.coerceAtMost(distinct).coerceAtLeast(2)

    val logMin = ln(min.toDouble())
    val logMax = ln(max.toDouble())
    val step = (logMax - logMin) / bucketCount
    val edges = LongArray(bucketCount + 1)
    for (i in 0..bucketCount) {
        edges[i] =
            if (i == bucketCount) {
                max
            } else {
                exp(logMin + step * i)
                    .toLong()
                    .coerceAtLeast(min)
            }
    }
    edges[0] = min
    // 几何分档可能因为取整产生相邻相同的边界：去掉重复，避免出现 0.9–0.9 这种档
    val unique = edges.distinct().sorted().toLongArray()
    if (unique.size < 2) return SizeHistogram(total, listOf(SizeBucket(min, max, total, isLast = true)))
    val counts = IntArray(unique.size - 1)
    sorted.forEach { size ->
        // 分档语义是 [unique[i], unique[i+1])，与点击后 SQL 的 size >= start AND size < end 一致。
        // binarySearch 命中时返回的就是 i（该值恰为下界，属于第 i 档），必须直接用 i；
        // 旧实现统一再减 1，导致「正好落在档边界上」的文件被计入下一档的计数，
        // 但点进去用该档区间查不到它（计数与内容对不上）。
        val hit = unique.binarySearch(size)
        val index =
            if (hit >= 0) {
                hit.coerceAtMost(counts.lastIndex)
            } else {
                (-hit - 2).coerceIn(0, counts.lastIndex)
            }
        counts[index]++
    }
    // 合并空档：把 0 计数的档并进前一个有数据的档，保证每一行都代表真实数据
    val merged = mutableListOf<SizeBucket>()
    for (i in counts.indices) {
        val start = unique[i]
        val end = unique[i + 1]
        if (counts[i] == 0) {
            if (merged.isNotEmpty()) {
                val last = merged.removeAt(merged.lastIndex)
                merged.add(SizeBucket(last.start, end, last.count, isLast = false))
            }
            continue
        }
        merged.add(SizeBucket(start, end, counts[i], isLast = false))
    }
    if (merged.isEmpty()) return SizeHistogram(total, emptyList())
    val lastIndex = merged.lastIndex
    return SizeHistogram(total, merged.mapIndexed { index, bucket -> bucket.copy(isLast = index == lastIndex) })
}
