package com.dhhxfggg.pjm.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dhhxfggg.pjm.data.model.FileEntity

@Database(
    entities = [FileEntity::class],
    version = 8, // 升级到 8：新增 category 和 name 字段的数据库索引
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileDao(): FileDao
}
