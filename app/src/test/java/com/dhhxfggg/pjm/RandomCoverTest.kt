package com.dhhxfggg.pjm

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 随机封面查询的回归测试。
 *
 * 背景：此前"随机封面"用「随机 id 下界 + id>= 定位」实现，遇到全局自增 id 存在大空洞的分类
 * （如视频库经大量删除/其他分类穿插）会频繁落空、回退到首条，导致封面看起来"不变"。
 * 修复后改用「按记录数均匀随机 offset」。
 *
 * 本测试锁定修复的核心机制：DAO 的 offset 查询能取到分类内任意位置的记录，
 * 且 count 与 offset 边界正确 —— 保证随机封面能覆盖全部记录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RandomCoverTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FileDao

    private val category = "videos"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.fileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(name: String) =
        FileEntity(
            relativePath = "$category/$name",
            name = name,
            size = 1024L,
            category = category,
            lastModified = 0L,
            isImage = false,
            extension = "mp4",
            contentHash = null,
        )

    /** 分类内无记录时 count 为 0，offset 查询返回 null（随机封面应安全返回 null 而非崩溃） */
    @Test
    fun emptyCategory_returnsZeroAndNull() =
        runBlocking {
            assertEquals(0, dao.getCountByCategory(category))
            assertNull(dao.getFileByOffset(category, 0))
        }

    /** 每条记录都能通过某个 offset 取到（覆盖 0..count-1），保证随机能命中全部 */
    @Test
    fun everyRecordReachableByOffset() =
        runBlocking {
            val names = (1..25).map { "video_$it.mp4" }
            dao.upsertAll(names.map { entity(it) })

            val count = dao.getCountByCategory(category)
            assertEquals(25, count)

            val reached = mutableSetOf<String>()
            for (offset in 0 until count) {
                val e = dao.getFileByOffset(category, offset)
                assertTrue("offset $offset 应能取到记录", e != null)
                e?.let { reached.add(it.name) }
            }
            // 所有 25 条都被覆盖到，无遗漏、无重复
            assertEquals("应覆盖全部记录", names.toSet(), reached)
        }

    /** id 存在大空洞时，offset 查询仍能均匀覆盖（模拟删除后 id 不连续的真实场景） */
    @Test
    fun worksWithIdGaps() =
        runBlocking {
            // 先插入 40 条并删除中间 30 条，制造 id 空洞
            val all = (1..40).map { "g_$it.mp4" }
            dao.upsertAll(all.map { entity(it) })
            val survivors = all.filter { it.endsWith("1.mp4") || it.endsWith("0.mp4") } // 保留 1,10,11,20,21,30,31,40
            dao.deleteByRelativePaths(all.filter { it !in survivors }.map { "$category/$it" })

            val count = dao.getCountByCategory(category)
            val reached = mutableSetOf<String>()
            for (offset in 0 until count) {
                dao.getFileByOffset(category, offset)?.let { reached.add(it.name) }
            }
            assertEquals("id 空洞下仍应覆盖全部保留记录", survivors.toSet(), reached)
        }
}
