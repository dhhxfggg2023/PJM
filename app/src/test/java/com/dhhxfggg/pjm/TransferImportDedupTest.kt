package com.dhhxfggg.pjm

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「从电脑取回」是否重复下载的判定依据。
 *
 * 背景：这里原来用一份只增不减的 SharedPreferences 账本（键 = 分类|日期|文件名|大小），
 * 它只在导入成功时写入，删除文件时**从不清理**，于是：
 *   从电脑取回 → 在手机上删掉 → 想再取回来 → 被静默跳过，进度条走完文件却没进来。
 *
 * 现在改为以数据库（同一分类 + 同名 + 同大小）为准：数据库行跟着文件一起删，
 * 「删了想要回来」天然能重新下载。本测试锁住这两条语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TransferImportDedupTest {
    private lateinit var db: AppDatabase
    private lateinit var fileDao: FileDao

    @Before
    fun setUp() {
        val context: Application = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        fileDao = db.fileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 批量取回的分类快照能正确回答「这个文件在不在库里」 */
    @Test
    fun snapshot_matchesSameNameAndSize() =
        runBlocking {
            fileDao.upsert(entity(name = "a.jpg", size = 100L))
            val known = snapshotOf("images")
            assertTrue("同名同大小应判定为已在库", known.contains("a.jpg" to 100L))
            assertFalse("大小不同是另一个文件", known.contains("a.jpg" to 101L))
            assertFalse("名字不同是另一个文件", known.contains("b.jpg" to 100L))
        }

    @Test
    fun snapshot_isScopedByCategory() =
        runBlocking {
            fileDao.upsert(entity(name = "a.mp4", size = 100L, category = "videos"))
            assertFalse("别的分类里有同名同大小，不能算这个分类已有", snapshotOf("images").contains("a.mp4" to 100L))
            assertTrue(snapshotOf("videos").contains("a.mp4" to 100L))
        }

    /**
     * 核心回归：删掉文件后必须能重新取回。
     *
     * 旧账本在这里会返回「已在库」，于是取回被跳过 —— 正是用户丢掉文件的那条路径。
     */
    @Test
    fun deletingFileAllowsImportingItAgain() =
        runBlocking {
            val target = entity(name = "a.jpg", size = 100L)
            fileDao.upsert(target)
            assertTrue(snapshotOf("images").contains("a.jpg" to 100L))

            fileDao.deleteByRelativePath(target.relativePath)

            assertFalse(
                "文件已从库里删掉，再次取回时必须重新下载（否则用户永远拿不回自己删掉的东西）",
                snapshotOf("images").contains("a.jpg" to 100L),
            )
        }

    /** 复刻 ViewModel 里的做法：一次性取分类快照，建成本地去重集合 */
    private suspend fun snapshotOf(category: String): Set<Pair<String, Long>> =
        fileDao.getNameSizesByCategory(category).mapTo(HashSet()) {
            it.name to it.size
        }

    private fun entity(
        name: String,
        size: Long,
        category: String = "images",
    ) = FileEntity(
        relativePath = category + "/" + name,
        name = name,
        size = size,
        category = category,
        lastModified = 1_700_000_000_000L,
        isImage = category == "images",
        extension = name.substringAfterLast('.', ""),
        contentHash = null,
        importedAt = 1_700_000_000_000L,
    )
}
