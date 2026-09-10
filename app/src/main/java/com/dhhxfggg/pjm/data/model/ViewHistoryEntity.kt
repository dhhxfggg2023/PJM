package com.dhhxfggg.pjm.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览历史（发现页"看过"记录）。
 *
 * 独立于 [FileEntity] 主表，避免污染核心资产索引。
 * 用于发现页"没看过的优先"：以 relativePath 为主键（与 FileEntity.relativePath 对应）。
 *
 * @property relativePath 库内相对路径（与 FileEntity.relativePath 对应）
 * @property viewedAt 最近一次浏览时间（epoch millis）
 */
@Entity(
    tableName = "view_history",
    indices = [Index(value = ["viewedAt"])],
)
data class ViewHistoryEntity(
    @PrimaryKey val relativePath: String,
    val viewedAt: Long,
)
