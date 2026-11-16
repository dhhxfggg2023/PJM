package com.dhhxfggg.pjm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.ManagedAppEntity
import com.dhhxfggg.pjm.data.model.TakeoverJobEntity
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
 *
 * 风险备忘（本轮不补 v8 及更早的迁移路径）：迁移表是从 v9→v10 才开始注册的，v8 及更早的
 * 数据库没有任何迁移路径，会按上面的 fail-fast 直接崩溃。「在网均为 v9」只是注释里的说法，
 * 尚未按发布历史逐版本确认 —— 若确实还有 v8 在网，需要补 MIGRATION_8_9 或给出升级引导。
 */
@Database(
    entities = [FileEntity::class, ViewHistoryEntity::class, ManagedAppEntity::class, TakeoverJobEntity::class],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao

    abstract fun viewHistoryDao(): ViewHistoryDao

    abstract fun managedAppDao(): ManagedAppDao

    abstract fun takeoverJobDao(): TakeoverJobDao

    companion object {
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
         * v11 → v12：「应用接管」功能新增两张表。
         *
         * 同样是纯新增，不触及 files / view_history 任何一行：
         * - managed_apps：被接管的应用（包名主键）
         * - takeover_jobs：每个被接管文件的处理记录（uri 唯一索引，天然去重）
         *
         * SQL 与 Room 为实体生成的 schema 必须逐字一致（列顺序/类型/默认值/索引名），
         * 否则 appendMigrations 校验会失败。构造表后由 androidTest 的
         * DatabaseMigrationTest 用真实迁移链路验证。
         */
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `managed_apps` (" +
                            "`packageName` TEXT NOT NULL, " +
                            "`displayName` TEXT NOT NULL, " +
                            "`addedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`packageName`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `takeover_jobs` (" +
                            "`uri` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`uri`))",
                    )
                }
            }

        /**
         * v12 → v13：files 表新增"入库时间"。
         *
         * 老数据没有独立入库时间，用最后修改时间回填（历史上入库时就写的 lastModified=当时时间，
         * 所以回填值就是真实入库时间）。之后新入库的文件两列都写，互不影响。
         */
        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `files` ADD COLUMN `importedAt` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE `files` SET `importedAt` = `lastModified` WHERE `importedAt` = 0")
                }
            }

        /**
         * v13 → v14：分类列表复合索引 (category, lastModified)。
         * 打开分类的查询是 WHERE category=? ORDER BY lastModified DESC，只有单列索引时
         * SQLite 要把 4 万多行读出来再排序；复合索引直接按顺序取，只是加索引不改数据。
         */
        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_files_category_lastModified` ON `files` (`category`, `lastModified`)",
                    )
                }
            }

        /**
         * v14 → v15：为大库查询补两个索引（只加索引、不改数据）。
         *
         * - `files(category, id)`：随机抽取/键集分页是 `WHERE category=? AND id >= ? ORDER BY id`，
         *   `(category, lastModified)` 用不上这种访问路径，复合索引可直接 seek。
         * - `takeover_jobs(status)`：设置页按状态 COUNT/IN 清理，不必全表扫描。
         *
         * 两条都是 `CREATE INDEX IF NOT EXISTS`，重复执行安全。
         */
        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_category_id` ON `files` (`category`, `id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_takeover_jobs_status` ON `takeover_jobs` (`status`)")
                }
            }

        /**
         * 显式迁移注册表。
         */
        val MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
            )
    }
}
