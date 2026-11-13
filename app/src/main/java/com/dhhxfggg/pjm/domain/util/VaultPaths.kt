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

    /** vault 根目录（filesDir/pjm_vault），不存在时创建 */
    fun vaultRoot(context: Context): File = File(context.filesDir, VAULT_ROOT).apply { if (!exists()) mkdirs() }

    /** 分类目录（filesDir/pjm_vault/<category>），不存在时创建 */
    fun getCategoryDir(
        context: Context,
        category: String,
    ): File = File(context.filesDir, "$VAULT_ROOT/$category").apply { if (!exists()) mkdirs() }

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
        var candidate = File(dir, base + suffix)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, base + "(" + index + ")" + suffix)
            index++
        }
        return candidate
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
