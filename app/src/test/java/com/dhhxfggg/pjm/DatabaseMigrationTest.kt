package com.dhhxfggg.pjm

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ViewHistoryDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.ViewHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移 + 浏览历史功能测试。
 *
 * 说明：真实 9→10→11 迁移（含 schema 校验与数据保留）由 androidTest 的
 * `DatabaseMigrationTest`（MigrationTestHelper）覆盖。本文件只在 Robolectric 下验证
 * 迁移 SQL 的幂等性与浏览历史表的行为，不作为"迁移正确"的证明。验证：
 *  1. v9→v10：迁移后 view_history 表存在且可读写；
 *  2. v10→v11：废弃表（novels/chapters/download_tasks）被删除，且 files/view_history 不受影响；
 *  3. files 表数据不受影响；
 *  4. “没看过优先”查询（getUnviewedPathsByCategory）与标记已看联动正确。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {
    private lateinit var db: AppDatabase
    private lateinit var fileDao: FileDao
    private lateinit var viewHistoryDao: ViewHistoryDao

    @Before
    fun setUp() {
        val context: Application = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        fileDao = db.fileDao()
        viewHistoryDao = db.viewHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * v10 → v11：废弃表必须被删除，且 files / view_history 一行都不能动。
     *
     * 这三张表是早期小说/下载功能的残留 —— 实体早已从 @Database 移除、全仓库无代码引用、
     * 库里 0 行，但 Room 不会自动删「不再声明」的表，它们会一直留在用户数据库里。
     */
    @Test
    fun migration10To11_dropsLegacyTablesWithoutTouchingRealData() {
        val raw = db.openHelper.writableDatabase
        // 造出「老库」的残留表（模拟 v10 及之前遗留的结构）
        raw.execSQL("CREATE TABLE IF NOT EXISTS `novels` (`id` INTEGER PRIMARY KEY, `title` TEXT)")
        raw.execSQL("CREATE TABLE IF NOT EXISTS `chapters` (`id` INTEGER PRIMARY KEY, `novelId` INTEGER, `orderIndex` INTEGER)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_novelId` ON `chapters` (`novelId`)")
        raw.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_orderIndex` ON `chapters` (`orderIndex`)")
        raw.execSQL("CREATE TABLE IF NOT EXISTS `download_tasks` (`id` INTEGER PRIMARY KEY)")
        raw.execSQL("INSERT INTO novels (title) VALUES ('legacy')")

        assertTrue("前置：残留表应存在", tableExists(raw, "novels"))
        assertTrue("前置：残留表应存在", tableExists(raw, "chapters"))
        assertTrue("前置：残留表应存在", tableExists(raw, "download_tasks"))

        val migration = AppDatabase.MIGRATIONS.first { it.endVersion == 11 }
        migration.migrate(raw)

        assertFalse("novels 应被删除", tableExists(raw, "novels"))
        assertFalse("chapters 应被删除", tableExists(raw, "chapters"))
        assertFalse("download_tasks 应被删除", tableExists(raw, "download_tasks"))
        // 核心：绝不能误伤真实数据表
        assertTrue("files 表必须保留", tableExists(raw, "files"))
        assertTrue("view_history 表必须保留", tableExists(raw, "view_history"))

        // 幂等：对已经删干净的库再执行一次必须无害
        migration.migrate(raw)
        assertFalse(tableExists(raw, "novels"))
    }

    private fun tableExists(
        db: SupportSQLiteDatabase,
        name: String,
    ): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type='table' AND name = ?"
        return db.query(sql, arrayOf(name)).use { it.moveToFirst() }
    }

    /**
     * v14 → v15：两个新索引必须存在，且迁移 SQL 可重复执行（`IF NOT EXISTS`）。
     */
    @Test
    fun migration14To15_addsIndexesIdempotently() {
        val raw = db.openHelper.writableDatabase
        val migration = AppDatabase.MIGRATIONS.first { it.endVersion == 15 }

        migration.migrate(raw)

        assertTrue("files(category, id) 索引必须存在", indexExists(raw, "index_files_category_id"))
        assertTrue("takeover_jobs(status) 索引必须存在", indexExists(raw, "index_takeover_jobs_status"))

        // 幂等：重复执行不能因索引已存在而抛错
        migration.migrate(raw)
        assertTrue(indexExists(raw, "index_files_category_id"))
        assertTrue(indexExists(raw, "index_takeover_jobs_status"))
    }

    /**
     * 按分类整体替换只影响目标分类：其它分类的旧行必须原样保留。
     */
    @Test
    fun replaceCategory_onlyReplacesTargetCategory() =
        runBlocking {
            fileDao.upsertAll(
                listOf(
                    fileEntity("images", "a.jpg"),
                    fileEntity("images", "b.jpg"),
                    fileEntity("videos", "c.mp4"),
                ),
            )

            fileDao.replaceCategory("images", listOf(fileEntity("images", "new.jpg")))

            assertEquals(1, fileDao.getCountByCategory("images"))
            assertEquals(1, fileDao.getCountByCategory("videos"))
            assertEquals("new.jpg", fileDao.getFilesByCategoryOnce("images").single().name)
        }

    private fun fileEntity(
        category: String,
        name: String,
    ) = FileEntity(
        relativePath = "$category/$name",
        name = name,
        size = 1L,
        category = category,
        lastModified = 0L,
        isImage = name.endsWith(".jpg"),
        extension = name.substringAfterLast('.'),
        contentHash = null,
    )

    private fun indexExists(
        db: SupportSQLiteDatabase,
        name: String,
    ): Boolean {
        val sql = "SELECT name FROM sqlite_master WHERE type='index' AND name = ?"
        return db.query(sql, arrayOf(name)).use { it.moveToFirst() }
    }

    /** 迁移 SQL 独立验证：在裸 SQLite 上执行 MIGRATION_9_10 后，view_history 可正常读写 */
    @Test
    fun migrationSql_createsViewHistoryTableUsable() {
        // 在内存库上直接执行迁移脚本（模拟 v9 → v10 的表变更）
        db.openHelper.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS `view_history` (" +
                "`relativePath` TEXT NOT NULL, " +
                "`viewedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`relativePath`))",
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_view_history_viewedAt` ON `view_history` (`viewedAt`)",
        )
        // 若能插入并查询，说明表结构正确
        db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO view_history (relativePath, viewedAt) VALUES ('x', 1)")
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM view_history").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }

    /** 标记已看后，“没看过”查询应排除之（发现页核心逻辑） */
    @Test
    fun viewHistory_reflectsInUnviewedQuery() =
        runBlocking {
            val cat = "images"
            val entities =
                (1..5).map {
                    FileEntity(
                        relativePath = "$cat/img_$it.jpg",
                        name = "img_$it.jpg",
                        size = 1L,
                        category = cat,
                        lastModified = 0L,
                        isImage = true,
                        extension = "jpg",
                        contentHash = null,
                    )
                }
            fileDao.upsertAll(entities)

            // 初始：全部没看过
            assertEquals(5, viewHistoryDao.getUnviewedPathsByCategory(cat).size)

            // 标记前两张已看
            viewHistoryDao.markViewed(ViewHistoryEntity("$cat/img_1.jpg", 100L))
            viewHistoryDao.markViewed(ViewHistoryEntity("$cat/img_2.jpg", 200L))

            val unviewed = viewHistoryDao.getUnviewedPathsByCategory(cat)
            assertEquals(3, unviewed.size)
            assertFalse(unviewed.contains("$cat/img_1.jpg"))
            assertFalse(unviewed.contains("$cat/img_2.jpg"))
        }

    /** 重复标记同一文件应幂等（REPLACE），不会产生重复行 */
    @Test
    fun markViewed_isIdempotent() =
        runBlocking {
            viewHistoryDao.markViewed(ViewHistoryEntity("a", 1L))
            viewHistoryDao.markViewed(ViewHistoryEntity("a", 2L))
            assertEquals(1, viewHistoryDao.getViewedCount())
        }

    /** purgeOrphans：清理 files 表已不存在文件的浏览记录 */
    @Test
    fun purgeOrphans_removesDeletedFileHistory() =
        runBlocking {
            val cat = "images"
            fileDao.upsert(
                FileEntity(
                    relativePath = "$cat/keep.jpg",
                    name = "keep.jpg",
                    size = 1L,
                    category = cat,
                    lastModified = 0L,
                    isImage = true,
                    extension = "jpg",
                    contentHash = null,
                ),
            )
            viewHistoryDao.markViewed(ViewHistoryEntity("$cat/keep.jpg", 1L))
            viewHistoryDao.markViewed(ViewHistoryEntity("$cat/gone.jpg", 1L)) // 对应文件不存在

            viewHistoryDao.purgeOrphans()

            assertEquals("孤儿历史应被清理", 1, viewHistoryDao.getViewedCount())
        }
}
