package com.dhhxfggg.pjm.domain.util

import android.content.Context
import com.dhhxfggg.pjm.data.model.FileEntity
import java.io.File
import java.util.UUID

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
    fun vaultRoot(context: Context): File =
        File(context.filesDir, VAULT_ROOT).apply { if (!exists()) mkdirs() }

    /** 分类目录（filesDir/pjm_vault/<category>），不存在时创建 */
    fun getCategoryDir(context: Context, category: String): File =
        File(context.filesDir, "$VAULT_ROOT/$category").apply { if (!exists()) mkdirs() }

    /** 由实体定位磁盘文件（相对 pjm_vault 拼接） */
    fun getFileFromEntity(context: Context, entity: FileEntity): File =
        File(vaultRoot(context), entity.relativePath)

    /** 由相对路径定位磁盘文件 */
    fun getFileByRelativePath(context: Context, relativePath: String): File =
        File(vaultRoot(context), relativePath)

    /** 磁盘文件 → 相对 pjm_vault 的路径（供数据库索引） */
    fun getRelativePath(context: Context, file: File): String =
        file.absolutePath.removePrefix(vaultRoot(context).absolutePath).trimStart(File.separatorChar)

    /** 生成入库目标路径：分类目录下 `<UUID>.<原扩展名>`（pjm 分卷需自行指定文件名） */
    fun getNextVaultPath(context: Context, category: String, originalName: String): File {
        val dir = getCategoryDir(context, category)
        return File(dir, "${UUID.randomUUID()}.${FileUtils.getFileExtension(originalName)}")
    }
}
