package com.dhhxfggg.pjm.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * PJM 核心资产实体。
 * 增加了复合索引以支撑万级文件的高性能检索。
 */
@Entity(
    tableName = "files",
    indices = [
        Index(value = ["relativePath"], unique = true),
        Index(value = ["category"]),
        Index(value = ["contentHash"]),
        Index(value = ["lastModified"]),
        // 分类列表是 WHERE category=? ORDER BY lastModified DESC，复合索引直接出顺序，省掉 4 万行排序
        Index(value = ["category", "lastModified"]),
        // 随机抽取 / 键集分页是 WHERE category=? AND id >= ? ORDER BY id，复合索引直接 seek
        Index(value = ["category", "id"]),
    ],
)
@Immutable
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val relativePath: String, // 库内相对路径 (UUID格式)
    val name: String, // 原始文件名
    val size: Long, // 字节大小
    val category: String, // 资产分类 (images, videos, bili_videos etc.)
    val lastModified: Long, // 最后修改时间
    val isImage: Boolean, // 快捷识别是否为图片
    val extension: String, // 后缀名
    val contentHash: String?, // MD5 指纹 (延迟计算，用于去重)
    // 入库时间（毫秒）：与 lastModified（文件自身时间）分开记，命名/统计都用这个
    val importedAt: Long = 0,
)
