package com.dhhxfggg.pjm.domain.util

import android.content.Context
import android.content.pm.PackageManager
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 「备份已安装应用」：把应用的安装包复制进保险库的「其他」分类。
 *
 * 为什么直接复制 sourceDir 而不是用 PackageManager 导包：
 * Android 没有公开 API 能把已安装应用的 APK 交出来，能拿到的只有
 * `ApplicationInfo.sourceDir` 指向的只读文件；直接流式复制既快，
 * 也不依赖 root/Shizuku。代价是应用私有数据不在备份范围内，所以界面文案写的是「安装包」。
 *
 * 单个应用失败（应用刚被卸载、文件路径消失、空间不足）不能中断整批 ——
 * 用户勾了 20 个应用，不该因为第 3 个出问题就全部白做。成功/失败与原因由 [BackupOutcome] 汇总。
 */
object ApkExporter {
    private const val TAG = "ApkExporter"

    /** 256 KB：一次读写能带走大多数 APK，又不会在低端机上长期占着堆 */
    private const val COPY_BUFFER_SIZE = 256 * 1024

    /** 进度回调节流：每 256KB 都回调的话，进度条一秒钟要重组几十次 */
    private const val PROGRESS_MIN_INTERVAL_MS = 120L

    /**
     * 单个应用的备份结果。
     *
     * @property entities 已落库的实体（一个应用一条）
     * @property failure 失败原因；null 表示该应用的安装包已成功入库
     */
    data class AppBackupResult(
        val packageName: String,
        val label: String,
        val entities: List<FileEntity>,
        val failure: String? = null,
    ) {
        val isSuccess: Boolean get() = failure == null && entities.isNotEmpty()
    }

    /** 整批备份的结果汇总 */
    data class BackupOutcome(
        val results: List<AppBackupResult>,
    ) {
        val successCount: Int get() = results.count { it.isSuccess }

        val failureCount: Int get() = results.size - successCount

        /** 真正入库的 APK 文件数 */
        val apkCount: Int get() = results.sumOf { it.entities.size }

        /** 失败原因（去重后），给结果弹窗逐条展示 */
        val failureReasons: List<String> get() = results.mapNotNull { it.failure }.distinct()
    }

    /**
     * 逐个提取选中的应用。
     *
     * @param onProgress (当前是第几个应用, 总数, 应用名, 当前应用已写字节, 当前应用总字节)：
     *   只报「当前应用」的进度，跨应用的累计由调用方自己累加 —— 这里不去猜总大小。
     */
    suspend fun exportApps(
        context: Context,
        packageNames: List<String>,
        fileDao: FileDao,
        onProgress: (index: Int, total: Int, label: String, appLoadedBytes: Long, appTotalBytes: Long) -> Unit = { _, _, _, _, _ -> },
    ): BackupOutcome =
        withContext(VaultManager.PjmDispatchers.IO) {
            val results = ArrayList<AppBackupResult>(packageNames.size)
            packageNames.forEachIndexed { index, packageName ->
                coroutineContext.ensureActive()
                results +=
                    exportOne(context, packageName, fileDao) { label, loaded, total ->
                        onProgress(index + 1, packageNames.size, label, loaded, total)
                    }
            }
            BackupOutcome(results)
        }

    private suspend fun exportOne(
        context: Context,
        packageName: String,
        fileDao: FileDao,
        onBytes: (label: String, loaded: Long, total: Long) -> Unit,
    ): AppBackupResult {
        val packageManager = context.packageManager
        val packageInfo =
            runCatching { packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA) }.getOrNull()
                ?: return AppBackupResult(packageName, packageName, emptyList(), context.getString(R.string.app_backup_error_not_installed))
        val appInfo =
            packageInfo.applicationInfo
                ?: return AppBackupResult(packageName, packageName, emptyList(), context.getString(R.string.app_backup_error_no_apk))
        val label =
            runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                // 应用名里的换行会原封不动变成文件名的一部分（ApkNaming 只折空白不清它），
                // 这里先折成空格再交给命名规则。
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
        // 只备份 base APK：一个应用就一份文件，点开能装、也能直接分享。
        // （部分商店分发会把应用拆成多个 APK，真机上从没遇到过，不做兼容。）
        val sourcePath = appInfo.sourceDir?.trim().orEmpty()
        val source = File(sourcePath)
        if (sourcePath.isEmpty() || !source.isFile) {
            return AppBackupResult(packageName, label, emptyList(), context.getString(R.string.app_backup_error_no_apk))
        }

        val fileName = ApkNaming.buildApkFileName(label, packageInfo.versionName)
        val importedAt = System.currentTimeMillis()
        val totalBytes = source.length().coerceAtLeast(0L)
        var loadedBytes = 0L
        var lastEmit = 0L

        val target = VaultManager.getNextVaultPath(context, VaultManager.CAT_OTHERS, fileName, importedAt)
        // 数据库写入是否已经提交：Room 的挂起写操作可能在提交后才抛出 CancellationException，
        // 那种情况下文件已经是「库里合法的 APK」，再 shred 就会留下「有行、无文件」的幽灵条目。
        var committed = false
        try {
            // 空间不足要在开始写之前发现：写到一半再失败的半截 APK 既不入库、又要回收，
            // 用户看到的只是一个莫名其妙的失败。
            VaultManager.ensureDiskSpace(context, totalBytes)
            copyWithProgress(source, target) { delta ->
                loadedBytes += delta
                val now = System.currentTimeMillis()
                if (now - lastEmit >= PROGRESS_MIN_INTERVAL_MS) {
                    lastEmit = now
                    onBytes(label, loadedBytes, totalBytes)
                }
            }
            val entity = entityFor(context, target, fileName, importedAt)
            fileDao.upsertLocked(entity)
            committed = true
            onBytes(label, loadedBytes, totalBytes)
            return AppBackupResult(packageName = packageName, label = label, entities = listOf(entity))
        } catch (cancel: CancellationException) {
            // 取消（离开页面/进程被回收）：半截文件必须删掉，否则它会以「不在数据库里的
            // 孤儿文件」形态留在保险库目录中，既占空间又会被下次全量同步当正常文件索引。
            // 但若 DB 已提交（取消信号在提交之后才到达），文件就是合法入库产物，不能删。
            if (decideCancellationCleanup(committed, target.exists()) == BackupCleanupDecision.SHRED_TARGET) {
                runCatching { VaultManager.shredFile(target) }
            }
            throw cancel
        } catch (e: Exception) {
            if (decideFailureCleanup(target.exists()) == BackupCleanupDecision.SHRED_TARGET) {
                runCatching { VaultManager.shredFile(target) }
            }
            PjmLogger.e(TAG, "备份 $packageName 失败", e)
            return AppBackupResult(
                packageName = packageName,
                label = label,
                entities = emptyList(),
                failure = e.message ?: context.getString(R.string.app_backup_error_copy_failed, fileName),
            )
        }
    }

    /**
     * 流式复制 + 进度回调。
     *
     * 先写 `<目标名>.tmp_<时间戳>` 再 rename：写入中途被杀进程时，保险库目录里留下的是
     * 带临时标记的文件，会被既有的残留清理/迁移逻辑识别（[VaultManager.isTransientFile]），
     * 而不是一个「看着完整、实际截断」的 APK —— 后者装不上去，用户也看不出为什么。
     */
    private suspend fun copyWithProgress(
        source: File,
        target: File,
        onDelta: (Long) -> Unit,
    ) {
        val tempFile = File(target.parentFile, target.name + CryptoUtils.TEMP_FILE_MARKER + System.currentTimeMillis())
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        onDelta(read.toLong())
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!tempFile.renameTo(target)) {
                // 同目录 rename 正常不会失败；失败时退回复制，避免整个分片被丢弃
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
                tempFile.delete()
            }
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            runCatching { if (target.exists()) target.delete() }
            throw t
        }
    }

    private fun entityFor(
        context: Context,
        target: File,
        displayName: String,
        importedAt: Long,
    ): FileEntity =
        FileEntity(
            relativePath = VaultManager.getRelativePath(context, target),
            // name 存清洗后的真实文件名：列表/分享/导出显示的都是它。物理名带的 UUID 尾巴
            // 由 FileUtils.normalizedDisplayName 剥掉，所以这里必须是用户看得懂的那个名字。
            name = displayName,
            size = target.length(),
            category = VaultManager.CAT_OTHERS,
            lastModified = importedAt,
            isImage = false,
            extension = "apk",
            contentHash = null,
            importedAt = importedAt,
        )
}
