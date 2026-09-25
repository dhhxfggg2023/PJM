package com.dhhxfggg.pjm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.ViewHistoryEntity

/**
 * PJM Room 数据库。
 *
 * ### Schema 演进规则（重要，防止静默清库）
 * 1. 任何实体/DAO 的结构变更都必须：version +1，并在 [MIGRATIONS] 中注册对应的
 *    [Migration]（从旧版本迁移到新版本），绝不使用 destructive fallback。
 * 2. [exportSchema] 已开启：每次编译会生成 `app/schemas/…/N.json`，
 *    请把生成的 JSON 一并提交到版本库，用于编写与测试迁移。
 * 3. 未注册迁移而版本不一致时，App 会直接崩溃（fail-fast）——这是有意为之：
 *    宁可启动崩溃提示，也绝不静默删库重建导致用户以为文件全部丢失。
 *
 * 历史说明：v9 之前的 schema 演进曾长期依赖 destructive migration（无历史 JSON），
 * 无法补写；当前所有在网安装的数据库均为 v9。自 v9 起启用严格迁移策略。
 */
@Database(
    entities = [FileEntity::class, ViewHistoryEntity::class],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao

    abstract fun viewHistoryDao(): ViewHistoryDao

    companion object {
        /**
         * 当前打开的底层 SQLite 连接引用。
         * 由 AppModule 的 RoomDatabase.Callback.onOpen 写入，
         * 供备份前执行 PRAGMA wal_checkpoint(TRUNCATE) 使用，确保备份文件完整。
         */
        @Volatile
        var openDb: SupportSQLiteDatabase? = null

        /**
         * v9 → v10：新增浏览历史表（发现页“没看过优先”）。
         * 仅新增表，不触及既有 files 表，对用户数据零影响。
         */
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `view_history` (" +
                            "`relativePath` TEXT NOT NULL, " +
                            "`viewedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`relativePath`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_view_history_viewedAt` ON `view_history` (`viewedAt`)")
                }
            }

        /**
         * v10 → v11：清掉三张已废弃的残留表（novels / chapters / download_tasks）。
         *
         * 这三张表属于早期的小说阅读与下载管理功能，对应实体早已从 [entities] 中移除，
         * 全仓库已无任何代码引用（`NovelEntity`/`ChapterEntity`/`DownloadTask` 均搜不到），
         * 库里也都是 0 行。但 Room 不会自动删除「不再声明」的表 —— 它们连同两个索引
         * 一直留在用户的数据库文件里，每次写迁移都要考虑它们。
         *
         * 用 `DROP TABLE IF EXISTS` + `DROP INDEX IF EXISTS`，对不存在的库执行也无害，
         * 因此对全新安装、对已升级过的库都安全；**不触及 files / view_history 任何一行**。
         */
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS `index_chapters_novelId`")
                    db.execSQL("DROP INDEX IF EXISTS `index_chapters_orderIndex`")
                    db.execSQL("DROP TABLE IF EXISTS `chapters`")
                    db.execSQL("DROP TABLE IF EXISTS `novels`")
                    db.execSQL("DROP TABLE IF EXISTS `download_tasks`")
                }
            }

        /**
         * 显式迁移注册表。
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_9_10, MIGRATION_10_11)
    }
}
