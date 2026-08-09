package com.dhhxfggg.pjm.di

import android.content.Context
import androidx.room.Room
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
         */
        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "pjm_app_database"
            )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .fallbackToDestructiveMigration()
            .build()
        }

        /**
         * 提供数据库访问对象 [FileDao]。
         */
        @Provides
        @Singleton
        fun provideFileDao(database: AppDatabase): FileDao {
            return database.fileDao()
        }
    }
}
