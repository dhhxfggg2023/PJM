package com.dhhxfggg.pjm.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 数据库中的文件实体。
 * 
 * @property relativePath 文件的相对路径，作为唯一标识符 (主键)
 * @property name 文件名
 * @property size 文件大小
 * @property category 文件柜分类
 * @property lastModified 最后修改时间戳
 * @property isImage 是否为图像
 * @property extension 扩展名
 * @property contentHash 文件内容摘要 (用于去重)
 */
@Entity(
    tableName = "files",
    indices = [
        Index(value = ["category"]),
        Index(value = ["name"])
    ]
)
data class FileEntity(
    @PrimaryKey val relativePath: String,
    val name: String,
    val size: Long,
    val category: String,
    val lastModified: Long,
    val isImage: Boolean,
    val extension: String,
    val contentHash: String? = null
)
