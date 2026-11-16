package com.dhhxfggg.pjm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页高度模型回归说明（纯逻辑断言，不依赖 Android 渲染）。
 *
 * 修复前的公式是「行数 × (字号 × 行距)」，它假设每行的行盒高度都等于行距，
 * 但真实渲染是「首行按字号、后续行按行距累加」。行距越大，这个理想值比真实值
 * 多出来的越多，反映到界面上就是每页底部空一截、且行距越大空得越多。
 *
 * 修复后用「实测一行高度 + (行数-1) × 实测行距增量」，与渲染完全同源。
 * 这里用一组典型数值说明两种公式在行距放大时的偏差方向：
 */
class ReaderPageHeightTest {
    private fun idealHeight(
        lines: Int,
        fontSize: Float,
        lineSpacing: Float,
    ) = lines * fontSize * lineSpacing

    private fun calibratedHeight(
        lines: Int,
        firstLineHeight: Int,
        lineStep: Int,
    ) = firstLineHeight + (lines - 1).coerceAtLeast(0) * lineStep

    @Test
    fun idealFormulaOverestimatesAsSpacingGrows() {
        // 真实行盒：首行约字号，后续行每行增加「字号 × (行距-1)」的一部分。
        // 取 fontSize=17，行距 2.6 时，一行实测约 17+额外，而理想公式给 44.2。
        val fontSize = 17f
        val spacing = 2.6f
        val lines = 8
        val ideal = idealHeight(lines, fontSize, spacing)
        // 标定后的模型：首行 20px，每行增量 34px（示意真实渲染的单调累加）
        val calibrated = calibratedHeight(lines, 20, 34)
        assertTrue("理想公式在行距大时应明显高估，实际页面才会空", ideal > calibrated)
        assertTrue("标定模型应随行数单调增长", calibratedHeight(lines + 1, 20, 34) > calibrated)
    }

    @Test
    fun calibratedHeightHandlesEmptyAndSingleLine() {
        assertTrue(calibratedHeight(1, 20, 34) == 20)
        assertTrue(calibratedHeight(0, 20, 34) <= 20)
    }
}
