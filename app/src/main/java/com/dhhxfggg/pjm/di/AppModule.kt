package com.dhhxfggg.pjm.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dhhxfggg.pjm.data.db.AppDatabase
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.data.repository.FileRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块，提供全局单例对象。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    /**
     * 将 [FileRepositoryImpl] 绑定到 [FileRepository] 接口。
     */
    @Binds
    @Singleton
    abstract fun bindFileRepository(fileRepositoryImpl: FileRepositoryImpl): FileRepository

    companion object {
        /**
         * 提供 Room 数据库实例。
         * 启用了 WAL 模式以支持高效的读写并发。
         *
         * 安全策略：不再使用 fallbackToDestructiveMigration ——
         * 未来 schema 变更必须注册显式 Migration（见 AppDatabase.MIGRATIONS），
         * 否则版本不一致会 fail-fast 崩溃，而绝不静默删库重建（保护用户索引/数据）。
         */
        @Provides
        @Singleton
        fun provideAppDatabase(
            @ApplicationContext context: Context,
        ): AppDatabase =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    "pjm_app_database",
                ).setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            AppDatabase.openDb = db
                        }
                    },
                ).build()

        /**
         * 提供数据库访问对象 [FileDao]。
         */
        @Provides
        @Singleton
        fun provideFileDao(database: AppDatabase): FileDao = database.fileDao()
    }
}
