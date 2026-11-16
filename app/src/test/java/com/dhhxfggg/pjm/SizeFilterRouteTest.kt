package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.ui.navigation.SizeFilterRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 热力图大小筛选路由的纯拼装/解析测试。
 *
 * 末档（Long.MAX_VALUE）不能带 sizeMax —— 屏幕侧会把它当成「无上限」；
 * 同时解析侧必须把空参数/非法数字归一成 null，决定「清除筛选」是否出现。
 */
class SizeFilterRouteTest {
    @Test
    fun lastBucketOmitsSizeMax() {
        val route = SizeFilterRoute.build("others", 1_000L, Long.MAX_VALUE)
        assertEquals("file_viewer/others?sizeMin=1000", route)
        assertNull(SizeFilterRoute.parseSizeMax(route))
    }

    @Test
    fun normalBucketIncludesBothParams() {
        val route = SizeFilterRoute.build("others", 1L, 99L)
        assertEquals("file_viewer/others?sizeMin=1&sizeMax=99", route)
        assertEquals(1L, SizeFilterRoute.parseSizeMin(route))
        assertEquals(99L, SizeFilterRoute.parseSizeMax(route))
    }

    @Test
    fun encodedCategoryIsPreserved() {
        // 调用方先做 Uri.encode，这里只是原样拼进路径，不再二次编码
        val route = SizeFilterRoute.build("video%2Fa", 0L, 10L)
        assertEquals("file_viewer/video%2Fa?sizeMin=0&sizeMax=10", route)
    }

    @Test
    fun parseReturnsNullWhenParamsMissing() {
        assertNull(SizeFilterRoute.parseSizeMin("file_viewer/others"))
        assertNull(SizeFilterRoute.parseSizeMax("file_viewer/others"))
        assertNull(SizeFilterRoute.parseSizeMax("file_viewer/others?sizeMin=5"))
    }

    @Test
    fun parseNormalizesBlankAndInvalidNumbers() {
        val route = "file_viewer/others?sizeMin=&sizeMax=not_a_number"
        assertNull(SizeFilterRoute.parseSizeMin(route))
        assertNull(SizeFilterRoute.parseSizeMax(route))
    }

    @Test
    fun parseIgnoresUnknownParams() {
        val route = "file_viewer/others?foo=1&sizeMax=42&bar=2"
        assertNull(SizeFilterRoute.parseSizeMin(route))
        assertEquals(42L, SizeFilterRoute.parseSizeMax(route))
    }

    @Test
    fun lastBucketMinStillParses() {
        val route = SizeFilterRoute.build("others", Long.MAX_VALUE - 1, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE - 1, SizeFilterRoute.parseSizeMin(route))
        assertNull(SizeFilterRoute.parseSizeMax(route))
    }
}
