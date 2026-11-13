package com.dhhxfggg.pjm.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「应用接管」里被接管的第三方应用。
 *
 * 存在即启用 —— 没有单独的开关字段：
 * 用户在设置页加进来就是要接管，移除即停止。少一个状态位，就少一种
 * 「开关开着但列表里没有」的自相矛盾状态。
 *
 * @property packageName 包名（主键，同一个应用只会有一行）
 * @property displayName 加入时的应用名快照（应用改名/卸载后仍能显示，不至于变成空白行）
 * @property addedAt     加入时间（列表排序用，先加的在前）
 */
@Entity(tableName = "managed_apps")
data class ManagedAppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
)
