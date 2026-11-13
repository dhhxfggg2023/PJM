package com.dhhxfggg.pjm

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhhxfggg.pjm.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实迁移测试（核心修复）。
 *
 * 旧实现的 "迁移测试" 是在已经建到目标版本的库上手工执行迁移 SQL —— 同义反复，
 * 既没经过 Room 的迁移链路，也没验证数据保留。这里用 MigrationTestHelper：
 *   1. 按 app/schemas/N.json 真正建出对应版本的老库；
 *   2. 写入一行 files 数据；
 *   3. 跑 AppDatabase.MIGRATIONS，由 Room 校验最终 schema 与目标版本 JSON 完全一致；
 *   4. 断言老数据仍在、新表已建好、废弃表不存在。
 *
 * 需要设备/模拟器（androidTest），CI 至少用 assembleDebugAndroidTest 保证其可编译。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList<AutoMigrationSpec>(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrate9To12_keepsDataAndAddsAllTables() {
        val dbName = "pjm-migration-test"
        helper.createDatabase(dbName, 9).apply {
            execSQL(
                "INSERT INTO files " +
                    "(id, relativePath, name, size, category, lastModified, isImage, extension, contentHash) " +
                    "VALUES (1, 'images/a.jpg', 'a.jpg', 10, 'images', 1000, 1, 'jpg', NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM files").use { cursor ->
            cursor.moveToFirst()
            assertEquals("v9 的 files 数据必须保留", 1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM view_history").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM managed_apps").use { cursor ->
            cursor.moveToFirst()
            assertEquals("v12 新表必须可用", 0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM takeover_jobs").use { cursor ->
            cursor.moveToFirst()
            assertEquals("v12 新表必须可用", 0, cursor.getInt(0))
        }
        val legacyTables =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('novels','chapters','download_tasks')",
            )
        legacyTables.use { cursor ->
            assertEquals("废弃表必须被清掉", 0, cursor.count)
        }
        db.close()
    }

    @Test
    fun migrate10To12_isSafeOnEmptyLegacyDb() {
        val dbName = "pjm-migration-test-10"
        helper.createDatabase(dbName, 10).close()
        val db = helper.runMigrationsAndValidate(dbName, 12, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM files").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    /**
     * v11 → v12 单独一条：这是本次功能真正引入的迁移，
     * 必须证明它只加表、不动 files / view_history。
     */
    @Test
    fun migrate11To12_keepsFilesAndAddsTakeoverTables() {
        val dbName = "pjm-migration-test-11"
        helper.createDatabase(dbName, 11).apply {
            execSQL(
                "INSERT INTO files " +
                    "(id, relativePath, name, size, category, lastModified, isImage, extension, contentHash) " +
                    "VALUES (7, 'videos/b.mp4', 'b.mp4', 20, 'videos', 2000, 0, 'mp4', NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 12, true, *AppDatabase.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM files WHERE id = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM managed_apps").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
