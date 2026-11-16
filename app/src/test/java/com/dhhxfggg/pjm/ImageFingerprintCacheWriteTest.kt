package com.dhhxfggg.pjm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.ImageFingerprint
import com.dhhxfggg.pjm.domain.util.ImageFingerprintCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 指纹缓存写盘的回归测试（S2-3）。
 *
 * 旧实现把写失败完全吞掉，可能留下半截文件；而 hasGray32 / hasVerifySignature
 * 只做 stat 探测，会把不完整缓存当成命中 —— 全库查重退化成每轮重新解码。
 * 新实现先写 .tmp 再 rename，失败则清理残留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageFingerprintCacheWriteTest {
    private fun entity(name: String) =
        FileEntity(
            relativePath = "images/$name",
            name = name,
            size = 1024L,
            category = "images",
            lastModified = 1_700_000_000_000L,
            isImage = true,
            extension = "jpg",
            contentHash = null,
        )

    @Test
    fun successfulSaveLeavesNoTempFilesAndMarksCacheComplete() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val e = entity("ok.jpg")
        val dir = ImageFingerprintCache.fingerprintDir(context)
        val names = ImageFingerprintCache.fingerprintFileNames(e)

        val fp = ImageFingerprint("0".repeat(64), 100, 100)
        ImageFingerprintCache.saveFingerprint(context, e, fp)
        ImageFingerprintCache.saveGray32(context, e, ByteArray(1024))
        ImageFingerprintCache.saveBundle(
            context,
            e,
            ImageFingerprintCache.PerceptualData(fp, ByteArray(1024), ByteArray(1028)),
        )

        assertTrue(ImageFingerprintCache.hasFingerprint(context, e))
        assertTrue(ImageFingerprintCache.hasGray32(context, e))
        assertTrue(ImageFingerprintCache.hasVerifySignature(context, e))
        for (n in names) {
            assertFalse("不应残留 .tmp 文件: $n", File(dir, "$n.tmp").exists())
        }
    }

    @Test
    fun preExistingHalfWrittenCacheIsNotTreatedAsComplete() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val e = entity("half.jpg")
        val dir = ImageFingerprintCache.fingerprintDir(context)
        val g32 = File(dir, ImageFingerprintCache.fingerprintFileNames(e)[1])
        g32.parentFile?.mkdirs()
        g32.writeBytes(ByteArray(10)) // 模拟旧实现留下的半截文件

        assertFalse(
            "长度不足 1024 的 .g32 不得被当成完整缓存",
            ImageFingerprintCache.hasGray32(context, e),
        )
        g32.delete()
    }
}
