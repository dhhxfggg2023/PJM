package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.VaultNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VaultNaming 容器命名规范的纯 JVM 单元测试（不依赖 Android / 网络）。
 *
 * 覆盖：毫秒→可读时间换算、缺省卷号补全、规范名保持不变、非容器返回 null。
 * 注意：时间换算依赖默认时区，因此只断言"毫秒样本换算后与同毫秒的 formatReadable 一致"，
 * 不写死具体字符串，保证任何时区/机器下稳定。
 */
class VaultNamingTest {
    // 固定毫秒样本（任意值，仅用于自洽断言）
    private val sampleMillis = 1767225600000L

    // ---------- 毫秒 → 可读时间换算 ----------
    @Test
    fun legacyMillis_convertToReadable() {
        val readable = VaultNaming.formatReadable(sampleMillis)
        // 格式必须 yyyyMMdd_HHmmss：14 位数字
        assertEquals(15, readable.length) // 8 + 1 + 6
        assertEquals("_", readable[8].toString())
        assertNull("应全为数字（除第 9 位下划线）", readable.filterIndexed { i, c -> i != 8 && !c.isDigit() }.takeIf { it.isNotEmpty() })

        val converted = VaultNaming.legacyToCanonicalName("Export_$sampleMillis.pjm.1")
        assertEquals("Export_$readable.pjm.1", converted)
    }

    // ---------- 缺省卷号补全 ----------
    @Test
    fun missingVolumeSuffix_appendsDot1() {
        assertEquals("Random_20260715_183000.pjm.1", VaultNaming.legacyToCanonicalName("Random_20260715_183000.pjm"))
    }

    @Test
    fun hasVolumeSuffix_keepsVolume() {
        assertEquals("Pack_20260715_183000.pjm.3", VaultNaming.legacyToCanonicalName("Pack_20260715_183000.pjm.3"))
    }

    // ---------- 已是规范名 → 原样返回 ----------
    @Test
    fun canonicalName_returnsItself() {
        assertEquals("Export_20260715_183000.pjm.1", VaultNaming.legacyToCanonicalName("Export_20260715_183000.pjm.1"))
        assertEquals("single.pjm.1", VaultNaming.legacyToCanonicalName("single.pjm.1"))
    }

    // ---------- 毫秒 + 缺卷号组合 ----------
    @Test
    fun legacyMillis_withoutVolume_appendsDot1() {
        val readable = VaultNaming.formatReadable(sampleMillis)
        assertEquals(
            "Export_$readable.pjm.1",
            VaultNaming.legacyToCanonicalName("Export_$sampleMillis.pjm"),
        )
    }

    // ---------- 非容器 → null ----------
    @Test
    fun nonContainer_returnsNull() {
        assertNull(VaultNaming.legacyToCanonicalName("photo.jpg"))
        assertNull(VaultNaming.legacyToCanonicalName("notes.txt"))
        assertNull(VaultNaming.legacyToCanonicalName("archive.zip"))
    }

    // ---------- 毫秒字段非法（非 13 位数字）→ 视为普通主体，仅补卷号 ----------
    @Test
    fun malformedMillis_treatedAsPlainBody() {
        // "176722560000" 只有 12 位 → 不被当作毫秒，仅补 .pjm.1
        assertEquals("Export_176722560000.pjm.1", VaultNaming.legacyToCanonicalName("Export_176722560000.pjm"))
    }

    // ---------- 卷号非法 ----------
    @Test
    fun malformedVolume_returnsNull() {
        assertNull(VaultNaming.legacyToCanonicalName("Export_20260715_183000.pjm.x"))
    }
}
