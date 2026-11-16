package com.dhhxfggg.pjm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.domain.util.VaultCategories
import com.dhhxfggg.pjm.domain.util.VaultPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * getNextVaultPath 并发安全回归测试。
 *
 * 旧实现用 while(candidate.exists()) 轮询追加 (1)/(2)：并发导入两个同名文件时，
 * 两者会拿到同一目标路径，后者覆盖前者并让数据库两行塌缩成一行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultPathsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sameNameDifferentCalls_produceDistinctPaths() {
        val a = VaultPaths.getNextVaultPath(context, VaultCategories.CAT_VIDEOS, "movie.mp4", 1000L)
        val b = VaultPaths.getNextVaultPath(context, VaultCategories.CAT_VIDEOS, "movie.mp4", 1000L)
        assertNotEquals("同名文件必须拿到不同的物理路径", a.absolutePath, b.absolutePath)
        assertEquals("扩展名必须保留", "mp4", a.extension)
        assertEquals("分类目录必须保留", VaultCategories.CAT_VIDEOS, a.parentFile?.name)
    }
}
