package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bilibili 缓存收割的控制器（扫描 / 合并 / 导入 / 源文件清理）。
 *
 * 从 SettingsViewModel 中拆出，负责全部 Bili 相关状态与操作。
 * ViewModel 持有本控制器并做门面转发，UI 调用契约不变。
 *
 * @param app Application（用于 getString / contentResolver）
 * @param fileDao 数据库访问
 * @param scope 协程作用域（通常传 viewModelScope）
 * @param settingsProvider 读取当前设置的 lambda（规避控制器对 uiState 的循环依赖）
 */
class BiliSettingsController(
    private val app: Application,
    private val fileDao: FileDao,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> Settings.AppSettings,
) {
    private val _biliItems = MutableStateFlow<List<BiliBridge.BiliCacheItem>?>(null)
    val biliItems: StateFlow<List<BiliBridge.BiliCacheItem>?> = _biliItems.asStateFlow()

    private val _isScanningBili = MutableStateFlow(false)
    val isScanningBili: StateFlow<Boolean> = _isScanningBili.asStateFlow()

    private val _biliMergedVideos = MutableStateFlow<List<BiliBridge.MergedVideoItem>?>(null)
    val biliMergedVideos: StateFlow<List<BiliBridge.MergedVideoItem>?> = _biliMergedVideos.asStateFlow()

    private val _isScanningBiliMerged = MutableStateFlow(false)
    val isScanningBiliMerged: StateFlow<Boolean> = _isScanningBiliMerged.asStateFlow()

    fun scanBiliCache(rootUri: Uri) {
        scope.launch {
            // 核心修复：防重复触发 —— 同任务防连点；不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_SCAN)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            _isScanningBili.value = true
            val statusSearching = app.getString(R.string.status_searching_bili_cache)
            // 核心修复：扫描遍历是【不确定时长】操作，用无限循环进度条（isIndeterminate），
            VaultManager.updateProgress(0f, statusSearching, taskId = VaultManager.TASK_BILI_SCAN, isActive = true, isIndeterminate = true)

            val items =
                try {
                    BiliBridge.scan(app, rootUri) { status ->
                        VaultManager.updateProgress(
                            0f,
                            status,
                            taskId = VaultManager.TASK_BILI_SCAN,
                            isActive = true,
                            isIndeterminate = true,
                        )
                    }
                } catch (e: Exception) {
                    PjmLogger.e("BiliController", "Bili scan failed", e)
                    emptyList()
                }

            if (items.isEmpty()) {
                Toast.makeText(app, app.getString(R.string.toast_bili_no_cache_found), Toast.LENGTH_LONG).show()
            }

            _biliItems.value = if (items.isNotEmpty()) items else null
            _isScanningBili.value = false
            VaultManager.updateProgress(
                1f,
                if (items.isNotEmpty()) app.getString(R.string.status_all_tasks_complete) else statusSearching,
                taskId = VaultManager.TASK_BILI_SCAN,
            )
            delay(800)
            VaultManager.clearProgress(VaultManager.TASK_BILI_SCAN)
            VaultManager.endOperation(VaultManager.TASK_BILI_SCAN)
        }
    }

    fun clearBiliState() {
        _biliItems.value = null
    }

    fun scanBiliMerged(rootUri: Uri) {
        scope.launch {
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_SCAN_MERGED)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                _isScanningBiliMerged.value = true
                VaultManager.updateProgress(
                    0f,
                    app.getString(R.string.status_searching_bili_merged),
                    taskId = VaultManager.TASK_BILI_SCAN_MERGED,
                )

                val items =
                    try {
                        BiliBridge.scanMergedVideos(app, rootUri) { status ->
                            VaultManager.updateProgress(0f, status, taskId = VaultManager.TASK_BILI_SCAN_MERGED)
                        }
                    } catch (e: Exception) {
                        PjmLogger.e("BiliController", "Bili merged scan failed", e)
                        emptyList()
                    }

                if (items.isEmpty()) {
                    Toast.makeText(app, app.getString(R.string.toast_bili_merged_empty), Toast.LENGTH_LONG).show()
                }

                _biliMergedVideos.value = if (items.isNotEmpty()) items else null
                _isScanningBiliMerged.value = false
                VaultManager.clearProgress(VaultManager.TASK_BILI_SCAN_MERGED)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_BILI_SCAN_MERGED)
            }
        }
    }

    fun clearBiliMergedState() {
        _biliMergedVideos.value = null
    }

    fun importBiliMergedVideos(items: List<BiliBridge.MergedVideoItem>) {
        _biliMergedVideos.value = null
        scope.launch {
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_IMPORT_MERGED)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                _biliMergedVideos.value = items
                return@launch
            }
            withContext(VaultManager.PjmDispatchers.IO) {
                val total = items.size
                val mergedAutoDelete = settingsProvider().biliMergedAutoDelete

                // 核心修复：按父文件夹分组
                val folderKey: (BiliBridge.MergedVideoItem) -> String = { item ->
                    item.shizukuPath?.substringBeforeLast('/')
                        ?: item.parentFolder?.let { if (it.scheme == "file") it.path ?: "" else it.toString() }
                        ?: ""
                }
                val itemsByFolder = items.groupBy(folderKey)
                val importedOk = mutableSetOf<BiliBridge.MergedVideoItem>()

                items.forEachIndexed { index, item ->
                    val progress = (index + 1).toFloat() / total
                    VaultManager.updateProgress(
                        progress,
                        app.getString(R.string.status_processing_bili_batch, index + 1, total, item.name),
                        taskId = VaultManager.TASK_BILI_IMPORT_MERGED,
                    )

                    val input =
                        try {
                            app.contentResolver.openInputStream(item.uri)
                        } catch (e: Exception) {
                            null
                        }
                    if (input != null) {
                        VaultManager
                            .digestFileToEntity(
                                context = app,
                                fileName = item.name,
                                inputStream = input,
                                expectedSize = FileUtils.getFileSize(app, item.uri),
                                overrideCategory = VaultManager.CAT_BILI_VIDEOS,
                            ).onSuccess { entity ->
                                fileDao.upsert(entity)
                                ThumbnailCache.generateVideoThumbnail(app, entity)
                                importedOk.add(item)
                            }
                    }
                }

                if (mergedAutoDelete) {
                    itemsByFolder.forEach { (key, folderItems) ->
                        if (key.isNotEmpty() && folderItems.all { it in importedOk }) {
                            deleteMergedSourceFolder(app, folderItems.first())
                        } else {
                            folderItems.filter { it in importedOk }.forEach { deleteMergedSourceFile(app, it) }
                        }
                    }
                }
            }
            BiliBridge.cleanupEmptyBiliDirs(app)
            VaultManager.updateProgress(
                1f,
                app.getString(R.string.status_all_tasks_complete),
                taskId = VaultManager.TASK_BILI_IMPORT_MERGED,
            )
            delay(1000.milliseconds)
            VaultManager.clearProgress(VaultManager.TASK_BILI_IMPORT_MERGED)
            VaultManager.endOperation(VaultManager.TASK_BILI_IMPORT_MERGED)
            VaultManager.triggerRefresh()
        }
    }

    /**
     * 核心修复：删除已合并视频所在的整个 B站 源文件夹，并清理空的父目录。
     * 优先特权模式（shell 身份递归删除），其次 file:// 路径，最后 SAF 删除父目录文档。
     */
    private suspend fun deleteMergedSourceFolder(
        context: Context,
        item: BiliBridge.MergedVideoItem,
    ) {
        try {
            if (item.shizukuPath != null) {
                val folder = item.shizukuPath.substringBeforeLast('/')
                var deleted = ShizukuBridge.deletePath(context, folder)
                if (!deleted) {
                    PjmLogger.w("BiliController", "Merged folder delete retry: $folder")
                    deleted = ShizukuBridge.deletePath(context, folder)
                }
                if (deleted) {
                    PjmLogger.i("BiliController", "Shizuku cleaned merged source folder: $folder")
                    cleanupEmptyBiliParents(context, folder)
                    return
                }
                PjmLogger.e("BiliController", "Shizuku merged folder delete failed: $folder")
            }
            val folderPath =
                item.parentFolder?.takeIf { it.scheme == "file" }?.path
                    ?: item.uri.path?.substringBeforeLast('/')
            if (folderPath != null) {
                val dir = File(folderPath)
                if (dir.exists() && dir.deleteRecursively()) {
                    PjmLogger.i("BiliController", "File cleaned merged source folder: $folderPath")
                    cleanupEmptyBiliParents(context, folderPath)
                    return
                }
            }
            if (item.parentFolder != null) {
                val ok =
                    try {
                        DocumentsContract.deleteDocument(context.contentResolver, item.parentFolder)
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (ok) {
                    PjmLogger.i("BiliController", "SAF cleaned merged source folder: ${item.parentFolder}")
                    return
                }
            }
            deleteMergedSourceFile(context, item)
        } catch (e: Exception) {
            PjmLogger.e("BiliController", "Failed to clean merged source folder", e)
            deleteMergedSourceFile(context, item)
        }
    }

    /** 仅删除单个已合并视频文件（不删除文件夹，用于部分导入成功的保守回退） */
    private suspend fun deleteMergedSourceFile(
        context: Context,
        item: BiliBridge.MergedVideoItem,
    ) {
        try {
            if (item.shizukuPath != null) {
                var deleted = ShizukuBridge.deletePath(context, item.shizukuPath)
                if (!deleted) {
                    PjmLogger.w("BiliController", "Merged delete retry: ${item.name}")
                    deleted = ShizukuBridge.deletePath(context, item.shizukuPath)
                }
                if (deleted) {
                    PjmLogger.i("BiliController", "Shizuku cleaned merged source: ${item.name}")
                    return
                }
            }
            if (item.uri.scheme == "file") {
                File(item.uri.path ?: "").delete()
                PjmLogger.i("BiliController", "File cleaned merged source: ${item.name}")
                return
            }
            val deleted =
                item.parentFolder?.let { parent ->
                    DocumentFile.fromTreeUri(context, parent)?.findFile(item.name)?.delete() ?: false
                } ?: false
            if (!deleted) DocumentFile.fromSingleUri(context, item.uri)?.delete()
            PjmLogger.i("BiliController", "Successfully cleaned merged video source: ${item.name}")
        } catch (e: Exception) {
            PjmLogger.e("BiliController", "Failed to clean merged video source", e)
        }
    }

    fun importBiliItems(items: List<BiliBridge.BiliCacheItem>) {
        _biliItems.value = null
        scope.launch {
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_IMPORT)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                _biliItems.value = items
                return@launch
            }
            withContext(VaultManager.PjmDispatchers.IO) {
                val total = items.size
                val biliAutoDelete = settingsProvider().biliAutoDelete

                items.forEachIndexed { index, item ->
                    val progress = (index + 1).toFloat() / total
                    VaultManager.updateProgress(
                        progress,
                        app.getString(R.string.status_processing_bili_batch, index + 1, total, item.title),
                        taskId = VaultManager.TASK_BILI_IMPORT,
                    )

                    val tempMp4 = File(app.cacheDir, "bili_merge_${System.nanoTime()}.mp4")

                    BiliBridge
                        .merge(app, item, tempMp4)
                        .onSuccess {
                            if (tempMp4.exists() && tempMp4.length() > 0) {
                                VaultManager
                                    .digestFileToEntity(
                                        context = app,
                                        fileName = "${item.title}.mp4",
                                        inputStream = tempMp4.inputStream(),
                                        expectedSize = tempMp4.length(),
                                        overrideCategory = VaultManager.CAT_BILI_VIDEOS,
                                    ).onSuccess { entity ->
                                        fileDao.upsert(entity)
                                        ThumbnailCache.generateVideoThumbnail(app, entity)

                                        if (biliAutoDelete) {
                                            try {
                                                if (item.shizukuParentPath != null) {
                                                    var deleted = ShizukuBridge.deletePath(app, item.shizukuParentPath)
                                                    if (!deleted) {
                                                        PjmLogger.w("BiliController", "Delete retry: ${item.title}")
                                                        deleted = ShizukuBridge.deletePath(app, item.shizukuParentPath)
                                                    }
                                                    if (deleted) {
                                                        PjmLogger.i("BiliController", "Shizuku cleaned Bili source folder: ${item.title}")
                                                    } else {
                                                        PjmLogger.e("BiliController", "Delete still failed: ${item.title}")
                                                        listOfNotNull(item.shizukuVideoPath, item.shizukuAudioPath).forEach { p ->
                                                            try {
                                                                ShizukuBridge.deletePath(app, p)
                                                            } catch (_: Exception) {
                                                            }
                                                        }
                                                    }
                                                    cleanupEmptyBiliParents(app, item.shizukuParentPath)
                                                } else if (item.parentFolder.scheme == "file") {
                                                    val folderPath = item.parentFolder.path ?: ""
                                                    File(folderPath).deleteRecursively()
                                                    cleanupEmptyBiliParents(app, folderPath)
                                                } else {
                                                    val ok =
                                                        try {
                                                            DocumentsContract.deleteDocument(app.contentResolver, item.parentFolder)
                                                            true
                                                        } catch (_: Exception) {
                                                            false
                                                        }
                                                    if (!ok) PjmLogger.e("BiliController", "SAF delete Bili folder failed: ${item.title}")
                                                }
                                            } catch (e: Exception) {
                                                PjmLogger.e("BiliController", "Failed to clean Bili folder", e)
                                            }
                                        }
                                    }
                            }
                            tempMp4.delete()
                        }.onFailure { fail ->
                            PjmLogger.e("BiliController", "Merge fail: ${fail.message}")
                            tempMp4.delete()
                        }
                }
            }
            BiliBridge.cleanupEmptyBiliDirs(app)
            VaultManager.updateProgress(1f, app.getString(R.string.status_all_tasks_complete), taskId = VaultManager.TASK_BILI_IMPORT)
            delay(1000.milliseconds)
            VaultManager.clearProgress(VaultManager.TASK_BILI_IMPORT)
            VaultManager.endOperation(VaultManager.TASK_BILI_IMPORT)
            VaultManager.triggerRefresh()
        }
    }

    /**
     * 删除 B站 视频文件夹后，向上清理空的父目录（download），最多上溯到 Android/data/<pkg> 为止。
     * 只走内置特权服务或 File API；一切异常捕获，列表失败视为"非空"（安全优先）。
     */
    private suspend fun cleanupEmptyBiliParents(
        context: Context,
        deletedVideoDir: String,
    ) {
        try {
            val pkgRoot = deletedVideoDir.substringBefore("/download", missingDelimiterValue = deletedVideoDir)
            var dir = deletedVideoDir.substringBeforeLast('/')
            delay(300)
            repeat(3) {
                if (!dir.startsWith(pkgRoot) || dir.length <= pkgRoot.length) return
                val isEmpty =
                    try {
                        if (EmbeddedPrivilegedIo.isAvailable(context)) {
                            var r = EmbeddedPrivilegedIo.listFiles(context, dir)
                            if (r == null) {
                                delay(500)
                                r = EmbeddedPrivilegedIo.listFiles(context, dir)
                            }
                            r?.isEmpty() == true
                        } else {
                            File(dir).listFiles()?.isEmpty() == true
                        }
                    } catch (_: Exception) {
                        false
                    }
                if (!isEmpty) return
                val ok =
                    try {
                        if (EmbeddedPrivilegedIo.isAvailable(context)) {
                            EmbeddedPrivilegedIo.deletePath(context, dir)
                        } else {
                            File(dir).delete()
                        }
                    } catch (_: Exception) {
                        false
                    }
                if (ok) {
                    PjmLogger.i("BiliController", "Cleaned empty Bili parent dir: $dir")
                } else {
                    return
                }
                dir = dir.substringBeforeLast('/')
            }
        } catch (e: Exception) {
            PjmLogger.e("BiliController", "cleanupEmptyBiliParents failed", e)
        }
    }
}
