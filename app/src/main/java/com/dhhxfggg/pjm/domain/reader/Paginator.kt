package com.dhhxfggg.pjm.domain.reader

/**
 * 分页算法（纯逻辑，可单测）。
 *
 * 输入是「每段在给定排版参数下的高度」（由 UI 用 StaticLayout 量出来），
 * 输出是每页起始段落下标。与测量解耦之后，翻页、跳章、换字号都不必再关心文字内容。
 */
object Paginator {
    /**
     * @param heights 每段渲染高度（px），长度 = 段落数
     * @param pageHeight 单页可用高度（px）
     * @return 每页第一个段落的下标；至少返回 [0]
     */
    fun paginate(
        heights: FloatArray,
        pageHeight: Float,
    ): List<Int> {
        if (heights.isEmpty()) return listOf(0)
        val limit = pageHeight.coerceAtLeast(1f)
        val starts = ArrayList<Int>(heights.size / 4 + 1)
        starts.add(0)
        var used = 0f
        // 上一段是否已经「独占一页」：用来保证超高段之后的那一段必须另起一页，
        // 否则超高段会和它的下一段挤在同一页里，下一段被裁掉且翻不到。
        var prevWasOversized = false
        for (i in heights.indices) {
            val h = heights[i].coerceAtLeast(0f)
            if (h > limit) {
                // 当前页已有内容 → 先关掉这一页；然后让本段自己起一页
                if (used > 0f) starts.add(i)
                if (starts.last() != i) starts.add(i)
                used = 0f
                prevWasOversized = true
                continue
            }
            if (prevWasOversized) {
                // 超高段的下一页一定是它自己，下一段必须从这里重新起页
                starts.add(i)
                used = 0f
                prevWasOversized = false
            } else if (used + h > limit && used > 0f) {
                starts.add(i)
                used = 0f
            }
            used += h
        }
        return starts
    }

    /** 页数 = 起始点数量（最后一页由末尾兜住） */
    fun pageCount(starts: List<Int>): Int = starts.size.coerceAtLeast(1)

    /** 段落下标 → 页下标 */
    fun pageOf(
        starts: List<Int>,
        paragraphIndex: Int,
    ): Int {
        if (starts.isEmpty()) return 0
        var low = 0
        var high = starts.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= paragraphIndex) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
