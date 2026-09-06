package com.dhhxfggg.pjm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dhhxfggg.pjm.data.model.FileEntity

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
    entities = [FileEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao

    companion object {
        /**
         * 当前打开的底层 SQLite 连接引用。
         * 由 AppModule 的 RoomDatabase.Callback.onOpen 写入，
         * 供备份前执行 PRAGMA wal_checkpoint(TRUNCATE) 使用，确保备份文件完整。
         */
        @Volatile
        var openDb: SupportSQLiteDatabase? = null

        /**
         * 显式迁移注册表。
         *
         * 未来 schema 变更示例（v9 → v10）：
         * ```
         * val MIGRATION_9_10 = object : Migration(9, 10) {
         *     override fun migrate(db: SupportSQLiteDatabase) {
         *         db.execSQL("ALTER TABLE files ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
         *     }
         * }
         * // 然后在下方数组中加入 MIGRATION_9_10
         * ```
         */
        val MIGRATIONS: Array<Migration> = arrayOf()
    }
}
