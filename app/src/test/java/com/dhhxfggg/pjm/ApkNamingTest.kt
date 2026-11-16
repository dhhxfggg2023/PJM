package com.dhhxfggg.pjm

import com.dhhxfggg.pjm.domain.util.ApkNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 APK 的命名规则回归测试。
 *
 * 应用名来自系统/开发者，什么字符都可能有；它要直接变成保险库里的物理文件名，
 * 所以清洗、截断、版本段拼接这三件事必须逐条钉住。
 */
class ApkNamingTest {
    @Test
    fun keepsPlainAsciiName() {
        assertEquals("Chrome_120.0.6099.144.apk", ApkNaming.buildApkFileName("Chrome", "120.0.6099.144"))
    }

    @Test
    fun keepsChineseName() {
        assertEquals("微信_8.0.49.apk", ApkNaming.buildApkFileName("微信", "8.0.49"))
    }

    @Test
    fun replacesColonAndSlash() {
        // 冒号与斜杠都会让文件名在 Windows 上非法、或直接变成子目录
        assertEquals("A_B_1.0.apk", ApkNaming.buildApkFileName("A:B", "1.0"))
        assertEquals("A_B_1.0.apk", ApkNaming.buildApkFileName("A/B", "1.0"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("My App_1.0.apk", ApkNaming.buildApkFileName("My \n App", "1.0"))
    }

    @Test
    fun dropsLeadingAndTrailingDots() {
        // 以点开头在库里是隐藏文件；结尾的点 Windows 会静默丢弃
        assertEquals("app_1.0.apk", ApkNaming.buildApkFileName("...", "1.0"))
        assertEquals("Name_1.0.apk", ApkNaming.buildApkFileName(".Name.", "1.0"))
    }

    @Test
    fun emptyLabelFallsBackToApp() {
        assertEquals("app.apk", ApkNaming.buildApkFileName("", null))
        assertEquals("app.apk", ApkNaming.buildApkFileName("   ", ""))
    }

    @Test
    fun omitsVersionSegmentWhenBlank() {
        // 版本号为空时不能把占位符拼进去
        assertEquals("WeChat.apk", ApkNaming.buildApkFileName("WeChat", null))
        assertEquals("WeChat.apk", ApkNaming.buildApkFileName("WeChat", "   "))
    }

    @Test
    fun truncatesLongNameKeepingSuffix() {
        val longLabel = "很长的应用名字".repeat(30)
        val name = ApkNaming.buildApkFileName(longLabel, "1.0.0")
        assertTrue("必须保留 .apk 后缀: " + name, name.endsWith(".apk"))
        assertTrue("总长必须在上限内: " + name.length, name.length <= ApkNaming.MAX_FILE_NAME_LENGTH)
    }

    @Test
    fun truncationKeepsVersionVisibleWhenLabelIsLong() {
        val longLabel = "A".repeat(200)
        val name = ApkNaming.buildApkFileName(longLabel, "9.9.9")
        // 应用名先被截到 60，版本段仍然保留
        assertTrue("版本号不能被挤掉: " + name, name.contains("9.9.9"))
    }

    @Test
    fun sanitizeNeverReturnsEmpty() {
        assertEquals("app", ApkNaming.sanitizeFileName(""))
        // 斜杠被替换成下划线，清洗后不是空串，所以不会落到兜底名
        assertEquals("___", ApkNaming.sanitizeFileName("///"))
        assertEquals("app", ApkNaming.sanitizeFileName("   "))
        // 控制字符不是空白，会被逐个替换成下划线（不是空串，也不落到兜底名）
        assertEquals("__", ApkNaming.sanitizeFileName("\u0000\u0001"))
    }

    @Test
    fun sanitizeIsIdempotent() {
        val once = ApkNaming.sanitizeFileName("  A/ B:  C  ")
        val twice = ApkNaming.sanitizeFileName(once)
        assertEquals(once, twice)
    }
}
