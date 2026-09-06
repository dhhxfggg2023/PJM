package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateChecker 版本比较逻辑的纯 JVM 单元测试（不依赖 Android / 网络）。
 *
 * 覆盖 `isNewer` 的边界：常规递增、跨位数（1.9 vs 1.10）、等长、不等长、非数字段、相同版本。
 */
class UpdateCheckerTest {

    // ---------- 明显更新 ----------
    @Test
    fun newer_whenMinorBumps() {
        assertTrue("1.9.0 should be newer than 1.8.8", UpdateChecker.isNewer("1.9.0", "1.8.8"))
    }

    @Test
    fun newer_whenMajorBumps() {
        assertTrue("2.0.0 should be newer than 1.8.8", UpdateChecker.isNewer("2.0.0", "1.8.8"))
    }

    @Test
    fun newer_whenPatchBumps() {
        assertTrue("1.8.9 should be newer than 1.8.8", UpdateChecker.isNewer("1.8.9", "1.8.8"))
    }

    // ---------- 跨位数（经典陷阱：字典序会出错，数值比较必须正确） ----------
    @Test
    fun newer_crossesDigitBoundary() {
        assertTrue("1.10.0 should be newer than 1.9.9", UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue("1.10 should be newer than 1.2", UpdateChecker.isNewer("1.10", "1.2"))
    }

    @Test
    fun newer_whenShorterButBiggerMajor() {
        assertTrue("2.0 should be newer than 1.999.999", UpdateChecker.isNewer("2.0", "1.999.999"))
    }

    // ---------- 不等长 ----------
    @Test
    fun newer_whenExtraTrailingZeroSegment() {
        // 1.8 == 1.8.0 → 不应判定为更新
        assertFalse("1.8.0 should NOT be newer than 1.8", UpdateChecker.isNewer("1.8.0", "1.8"))
        assertFalse("1.8 should NOT be newer than 1.8.0", UpdateChecker.isNewer("1.8", "1.8.0"))
    }

    @Test
    fun newer_whenExtraNonZeroSegment() {
        assertTrue("1.8.1 should be newer than 1.8", UpdateChecker.isNewer("1.8.1", "1.8"))
    }

    // ---------- 非数字段 ----------
    @Test
    fun newer_ignoresNonNumericSuffix() {
        // 非数字段被忽略：1.8.7-beta 按 1.8.7 处理
        assertFalse("1.8.7-beta should NOT be newer than 1.8.7", UpdateChecker.isNewer("1.8.7-beta", "1.8.7"))
        assertTrue("1.8.8 should be newer than 1.8.7-beta", UpdateChecker.isNewer("1.8.8", "1.8.7-beta"))
    }

    @Test
    fun newer_whenFirstSegmentIsNonNumeric() {
        // 调用方 checkForUpdate 已做 removePrefix("v")，此处验证 v 前缀残留时首段被忽略、不崩溃。
        // "v1.8.8" → 非数字段 "v1" 被过滤 → [8,8] 与 "1.8.8" [1,8,8] 相比首段 8>1 → true（视为更新）。
        // 这是已剥离前缀路径下的防御性行为记录，不是推荐输入。
        assertTrue(UpdateChecker.isNewer("v1.8.8", "1.8.8"))
    }

    // ---------- 相同版本 ----------
    @Test
    fun sameVersion_isNotNewer() {
        assertFalse("equal versions must not be newer", UpdateChecker.isNewer("1.8.8", "1.8.8"))
    }

    // ---------- 全非数字 ----------
    @Test
    fun allNonNumeric_comparesAsZero() {
        assertFalse("non-numeric should be treated as 0", UpdateChecker.isNewer("beta", "1.0.0"))
        assertFalse("both non-numeric equal", UpdateChecker.isNewer("abc", "xyz"))
    }

    // ---------- 空串 ----------
    @Test
    fun emptyStrings_comparesAsZero() {
        assertFalse("empty vs empty", UpdateChecker.isNewer("", ""))
        assertFalse("empty should not be newer than 1.0", UpdateChecker.isNewer("", "1.0"))
    }
}
