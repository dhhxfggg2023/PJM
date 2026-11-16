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
        // 核心修复：过期条目必须 remove，否则每分类的 List<FileEntity>（4 万行）
        // 会一直静态持有到进程结束；invalidate() 旧实现也零调用，「清空缓存」清不掉它。
        val stale =
            entry.version != VaultTasks.currentDataVersion() ||
                System.currentTimeMillis() - entry.at > TTL_MS
        if (stale) {
            cache.remove(category, entry)
            return null
        }
        return entry.files
    }

    fun put(
        category: String,
        files: List<FileEntity>,
    ) {
        // 核心修复：过期条目原先只在对应分类被 get 时才移除 —— 冷启动预热的分类
        // 只要用户一直不点，4 万行列表就会静态持有到进程结束。put 时顺带扫一遍
        // （分类数只有个位数，代价可忽略）。
        sweep()
        cache[category] = Entry(System.currentTimeMillis(), VaultTasks.currentDataVersion(), files)
    }

    /**
     * 主动清理过期条目。
     *
     * ConcurrentHashMap 的迭代是弱一致的，边遍历边 remove 是安全的；
     * 这样也不用依赖 API 24+ 的 Collection.removeIf。
     */
    private fun sweep() {
        val now = System.currentTimeMillis()
        val version = VaultTasks.currentDataVersion()
        for ((key, entry) in cache) {
            if (entry.version != version || now - entry.at > TTL_MS) {
                cache.remove(key, entry)
            }
        }
    }

    fun invalidate() {
        cache.clear()
    }
}
