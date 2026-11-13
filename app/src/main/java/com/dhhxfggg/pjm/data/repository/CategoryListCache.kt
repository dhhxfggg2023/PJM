package com.dhhxfggg.pjm.data.repository

import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.VaultTasks
import java.util.concurrent.ConcurrentHashMap

/**
 * 分类列表内存缓存。
 *
 * 目的：切换分类/重新进入文件库时先用缓存立刻渲染，再等数据库最新结果覆盖，
 * 避免每次都对着 4 万行的查询等 1 秒。
 *
 * 只缓存实体引用（几 MB），进程结束自然释放；超过 TTL 视为过期，
 * 避免入库/删除后长时间看到旧列表。
 */
object CategoryListCache {
    private const val TTL_MS = 45_000L

    private data class Entry(
        val at: Long,
        val version: Long,
        val files: List<FileEntity>,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(category: String): List<FileEntity>? {
        val entry = cache[category] ?: return null
        // 版本号变了（入库/删除/刷新）就当作过期，避免看到旧列表
        if (entry.version != VaultTasks.currentDataVersion()) return null
        return if (System.currentTimeMillis() - entry.at <= TTL_MS) entry.files else null
    }

    fun put(
        category: String,
        files: List<FileEntity>,
    ) {
        cache[category] = Entry(System.currentTimeMillis(), VaultTasks.currentDataVersion(), files)
    }

    fun invalidate() {
        cache.clear()
    }
}
