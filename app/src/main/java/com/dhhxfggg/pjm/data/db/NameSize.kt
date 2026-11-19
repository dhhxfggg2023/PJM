package com.dhhxfggg.pjm.data.db

/**
 * 「从电脑取回」判断重复时的轻量投影：只要名字与大小。
 *
 * 用具名投影而不是整行 [com.dhhxfggg.pjm.data.model.FileEntity]：
 * 一个分类可能有 4 万条记录，为了建一个 (名字, 大小) 去重集合
 * 没必要把 relativePath / contentHash 等字段一并读进内存。
 */
data class NameSize(
    val name: String,
    val size: Long,
)
