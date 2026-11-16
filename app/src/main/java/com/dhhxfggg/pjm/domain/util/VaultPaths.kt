package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.File

/**
 * PJM 保险库在应用私有目录中的路径布局工具。
 *
 * 布局：`filesDir/pjm_vault/<category>/<UUID>.<ext>`。
 * relativePath 为相对 `pjm_vault` 的路径（如 `pjm/xxx.pjm.1`），用于数据库索引。
 */
object VaultPaths {
    /** 保险库根目录名（相对 filesDir） */
    const val VAULT_ROOT = "pjm_vault"

    /**
     * 已确认存在的目录（absolutePath）。目录只会被清理文件、不会被删掉，
     * 所以进程内只需做一次 exists()/mkdirs() —— 列表滚动时每个 item 都会调到这里，
     * 旧写法每次都 stat 一次（FUSE 上可达毫秒级），是列表卡顿的来源之一。
     */
    private val ensuredDirs =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    private fun ensureDir(dir: File): File {
        if (ensuredDirs.add(dir.absolutePath) && !dir.exists()) dir.mkdirs()
        return dir
    }

    /** vault 根目录（filesDir/pjm_vault），首次使用时创建 */
    fun vaultRoot(context: Context): File = ensureDir(File(context.filesDir, VAULT_ROOT))

    /** 分类目录（filesDir/pjm_vault/<category>），首次使用时创建 */
    fun getCategoryDir(
        context: Context,
        category: String,
    ): File = ensureDir(File(context.filesDir, "$VAULT_ROOT/$category"))

    /** 由实体定位磁盘文件（相对 pjm_vault 拼接） */
    fun getFileFromEntity(
        context: Context,
        entity: FileEntity,
    ): File = File(vaultRoot(context), entity.relativePath)

    /** 由相对路径定位磁盘文件 */
    fun getFileByRelativePath(
        context: Context,
        relativePath: String,
    ): File = File(vaultRoot(context), relativePath)

    /** 磁盘文件 → 相对 pjm_vault 的路径（供数据库索引） */
    fun getRelativePath(
        context: Context,
        file: File,
    ): String = file.absolutePath.removePrefix(vaultRoot(context).absolutePath).trimStart(File.separatorChar)

    /**
     * 生成入库目标路径（物理文件名即用户看到的名字）：
     * - 图片：`PJM_yyyyMMdd_HHmmss.ext`（不暴露原始文件名，和显示名一致）；
     * - 其他类型：保留原始文件名，重名时追加 (1)、(2)…
     *
     * @param importedAt 入库时间。命名和 entity.importedAt 用同一个时间戳，避免差一秒对不上。
     */
    fun getNextVaultPath(
        context: Context,
        category: String,
        originalName: String,
        importedAt: Long = System.currentTimeMillis(),
    ): File {
        val dir = getCategoryDir(context, category)
        val safeName = safeFileName(originalName)
        // 用原始大小写的扩展名（FileUtils.getFileExtension 会小写化，且把 .pjm.1 归成 "pjm"）
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }.orEmpty()
        val suffix = if (extension.isEmpty()) "" else "." + extension
        val base =
            if (category == VaultCategories.CAT_IMAGES) {
                // formatReadable 是 yyyyMMdd_HHmmssSSS，这里取到秒，和显示名 PJM_yyyyMMdd_HHmmss 一致
                "PJM_" + VaultNaming.formatReadable(importedAt).take(15)
            } else {
                if (extension.isEmpty()) safeName else safeName.dropLast(extension.length + 1).ifBlank { "file" }
            }
        // 核心修复：物理文件名一律追加 UUID，从根上消除并发同名导入的 TOCTOU 竞态。
        // 旧实现用 while (candidate.exists()) 轮询 (1)/(2)：IngestionEngine 以 Semaphore(4) 并发
        // 导入时，两个同名文件会同时看到 exists()==false，拿到同一目标路径，后写入者 renameTo
        // 原子覆盖先写入者（两条实体 relativePath 相同，数据库 upsert 还会塌缩成一行）—— 静默丢文件。
        // 数据库 FileEntity.name 仍保存用户可读原名，显示由 FileUtils.normalizedDisplayName 负责；
        // 只依赖物理名的判断（isImageFile / extension）都基于扩展名，UUID 不影响分类。
        return File(
            dir,
            base + "_" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(16) + suffix,
        )
    }

    /** 去掉路径分隔符/控制字符，避免原始文件名把文件写到目录外面去 */
    private fun safeFileName(name: String): String {
        val cleaned =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
                .trim()
        return cleaned.ifBlank { "unnamed" }
    }
}
