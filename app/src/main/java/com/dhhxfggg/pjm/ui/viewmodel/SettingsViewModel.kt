package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.shizuku.EmbeddedPrivilegedIo
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.*
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI State for the Settings Screen.
 */
@Immutable
data class SettingsUiState(
    val settings: Settings.AppSettings = Settings.AppSettings(),
    val isInitialized: Boolean = false,
    val duplicateFiles: List<DuplicateGroup>? = null,
    val biliItems: List<BiliBridge.BiliCacheItem>? = null,
    val isScanningBili: Boolean = false,
    val biliMergedVideos: List<BiliBridge.MergedVideoItem>? = null,
    val isScanningBiliMerged: Boolean = false,
    val shizukuState: ShizukuBridge.AuthState = ShizukuBridge.AuthState.NotInstalled,
    val isShizukuServiceReady: Boolean = false
)

/**
 * ViewModel for managing application settings and maintenance tasks.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val settingsManager: SettingsManager,
    private val repository: FileRepository,
    private val fileDao: FileDao,
) : AndroidViewModel(app) {

    private val _duplicateFiles = MutableStateFlow<List<DuplicateGroup>?>(null)
    private val _biliItems = MutableStateFlow<List<BiliBridge.BiliCacheItem>?>(null)
    private val _isScanningBili = MutableStateFlow(value = false)
    private val _biliMergedVideos = MutableStateFlow<List<BiliBridge.MergedVideoItem>?>(null)
    private val _isScanningBiliMerged = MutableStateFlow(value = false)
    private val _isInitialized = MutableStateFlow(value = false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsManager.settings,
        _isInitialized,
        _duplicateFiles,
        _biliItems,
        _isScanningBili,
        _biliMergedVideos,
        _isScanningBiliMerged,
        ShizukuBridge.authState,
        ShizukuBridge.serviceReady
    ) { values: Array<Any?> ->
        SettingsUiState(
            settings = values[0] as Settings.AppSettings,
            isInitialized = values[1] as Boolean,
            duplicateFiles = values[2] as List<DuplicateGroup>?,
            biliItems = values[3] as List<BiliBridge.BiliCacheItem>?,
            isScanningBili = values[4] as Boolean,
            biliMergedVideos = values[5] as List<BiliBridge.MergedVideoItem>?,
            isScanningBiliMerged = values[6] as Boolean,
            shizukuState = values[7] as ShizukuBridge.AuthState,
            isShizukuServiceReady = values[8] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    init {
        viewModelScope.launch {
            settingsManager.settings.first()
            _isInitialized.value = true
        }
    }

    fun toggleViewMode() {
        val currentSettings = uiState.value.settings
        val nextMode = if (currentSettings.fileViewMode == "grid") "list" else "grid"
        updateSetting(Settings.KEY_FILE_VIEW_MODE, nextMode)
    }

    fun scanForDuplicates() {
        viewModelScope.launch(VaultManager.PjmDispatchers.IO) {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务（如删除）可并行进行；
            // 核心修复：整个查重在 IO 线程池执行，避免主线程被阻塞卡死
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_DUPLICATES_EXACT)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                // 阶段 1：精确查重（MD5 完全一致 + 视频感知指纹）—— 独立进度条 0-100%
                VaultManager.updateProgress(0f, app.getString(R.string.status_analyzing_duplicates), taskId = VaultManager.TASK_DUPLICATES_EXACT)
                val exactDuplicates = VaultManager.findDuplicateFiles(app, fileDao) { progress: Float ->
                    VaultManager.updateProgress(progress, app.getString(R.string.status_calculating_fingerprints), taskId = VaultManager.TASK_DUPLICATES_EXACT)
                }
                VaultManager.clearProgress(VaultManager.TASK_DUPLICATES_EXACT)
                VaultManager.endOperation(VaultManager.TASK_DUPLICATES_EXACT)

                // 阶段 2：图片感知查重（原图/缩略图）—— 独立任务 id，进度条从 0% 开始（核心修复：不再从 52% 起跳）
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) return@launch
                val similarImages = VaultManager.findSimilarImages(app, fileDao) { progress: Float ->
                    VaultManager.updateProgress(
                        progress,
                        if (progress < 0.6f) app.getString(R.string.status_calculating_image_fingerprints)
                        else app.getString(R.string.status_comparing_images),
                        taskId = VaultManager.TASK_DUPLICATES_PERCEPTUAL
                    )
                }
                VaultManager.clearProgress(VaultManager.TASK_DUPLICATES_PERCEPTUAL)
                VaultManager.endOperation(VaultManager.TASK_DUPLICATES_PERCEPTUAL)

                // 核心修复：若任一阶段被用户取消，不弹结果窗（避免误导为"查完无重复"）
                if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_EXACT) ||
                    VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES_PERCEPTUAL)) {
                    PjmLogger.i("SettingsVM", "查重已取消，不展示结果")
                    return@launch
                }
                // 合并分组：精确查重组 + 图片感知组（各组的文件不重叠，直接拼接）
                _duplicateFiles.value = exactDuplicates + similarImages
            } catch (e: CancellationException) {
                // 用户取消：静默结束，不弹错误
                PjmLogger.i("SettingsVM", "查重已取消: ${e.message}")
            } catch (e: Throwable) {
                // 核心修复：捕获 Throwable（含 OOM）—— 查重异常不崩溃，清理进度后返回
                PjmLogger.e("SettingsVM", "查重异常: ${e.javaClass.simpleName}: ${e.message}", e as? Exception)
            } finally {
                VaultManager.clearProgress(VaultManager.TASK_DUPLICATES_EXACT)
                VaultManager.clearProgress(VaultManager.TASK_DUPLICATES_PERCEPTUAL)
                VaultManager.endOperation(VaultManager.TASK_DUPLICATES_EXACT)
                VaultManager.endOperation(VaultManager.TASK_DUPLICATES_PERCEPTUAL)
            }
        }
    }

    fun syncDatabase() {
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_SYNC)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                VaultManager.updateProgress(0.1f, app.getString(R.string.status_syncing_fast), taskId = VaultManager.TASK_SYNC)
                VaultManager.fullSyncDatabase(app, fileDao) { progress: Float ->
                    VaultManager.updateProgress(progress, app.getString(R.string.status_indexing_files), taskId = VaultManager.TASK_SYNC)
                }
                VaultManager.updateProgress(1.0f, app.getString(R.string.status_sync_complete), taskId = VaultManager.TASK_SYNC)
                delay(800.milliseconds)
                VaultManager.clearProgress(VaultManager.TASK_SYNC)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_SYNC)
            }
        }
    }

    fun clearDuplicateState() { _duplicateFiles.value = null }

    fun scanBiliCache(rootUri: Uri) {
        viewModelScope.launch {
            // 核心修复：防重复触发 —— 同任务防连点；不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_SCAN)) {
                Toast.makeText(app, "操作进行中，请稍候", Toast.LENGTH_SHORT).show()
                return@launch
            }
            _isScanningBili.value = true
            val statusSearching = app.getString(R.string.status_searching_bili_cache)
            // 核心修复：扫描遍历是【不确定时长】操作，用无限循环进度条（isIndeterminate），
            // 不再用固定 +0.03 硬推（回调次数不定导致"90% 才到一半"的错乱观感）。
            // 只有扫描完成才显示 100%。
            VaultManager.updateProgress(0f, statusSearching, taskId = VaultManager.TASK_BILI_SCAN, isActive = true, isIndeterminate = true)
            
            val items = try {
                BiliBridge.scan(app, rootUri) { status ->
                    VaultManager.updateProgress(0f, status, taskId = VaultManager.TASK_BILI_SCAN, isActive = true, isIndeterminate = true)
                }
            } catch (e: Exception) {
                PjmLogger.e("SettingsVM", "Bili scan failed", e)
                emptyList()
            }
            
            if (items.isEmpty()) {
                Toast.makeText(app, "未在选定目录识别到有效的 B站 视频碎片", Toast.LENGTH_LONG).show()
            }
            
            _biliItems.value = if (items.isNotEmpty()) items else null
            _isScanningBili.value = false
            // 扫描结束：关闭不确定进度，显示明确结果
            VaultManager.updateProgress(1f, if (items.isNotEmpty()) app.getString(R.string.status_all_tasks_complete) else statusSearching, taskId = VaultManager.TASK_BILI_SCAN)
            delay(800)
            VaultManager.clearProgress(VaultManager.TASK_BILI_SCAN)
            VaultManager.endOperation(VaultManager.TASK_BILI_SCAN)
        }
    }

    fun clearBiliState() { _biliItems.value = null }

    fun scanBiliMerged(rootUri: Uri) {
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_SCAN_MERGED)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                _isScanningBiliMerged.value = true
                VaultManager.updateProgress(0f, app.getString(R.string.status_searching_bili_merged), taskId = VaultManager.TASK_BILI_SCAN_MERGED)

                val items = try {
                    BiliBridge.scanMergedVideos(app, rootUri) { status ->
                        VaultManager.updateProgress(0f, status, taskId = VaultManager.TASK_BILI_SCAN_MERGED)
                    }
                } catch (e: Exception) {
                    PjmLogger.e("SettingsVM", "Bili merged scan failed", e)
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

    fun clearBiliMergedState() { _biliMergedVideos.value = null }

    fun importBiliMergedVideos(items: List<BiliBridge.MergedVideoItem>) {
        _biliMergedVideos.value = null
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_IMPORT_MERGED)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                _biliMergedVideos.value = items
                return@launch
            }
            // 核心修复：整个导入循环在 IO 线程执行（避免缩略图生成/文件删除等阻塞主线程导致卡顿）
            withContext(VaultManager.PjmDispatchers.IO) {
                val total = items.size
                val mergedAutoDelete = uiState.value.settings.biliMergedAutoDelete

                // 核心修复：按父文件夹分组 —— B站 按 download/<avid>/ 组织缓存目录，
                // 已合并视频、m4s 碎片、entry.json 等都在同一文件夹内。
                // 必须等该文件夹下所有已合并视频全部导入成功后，再整体删除文件夹本身，
                // 否则 111/222/333 空文件夹残留，B站内部仍认为下载未删除。
                val folderKey: (BiliBridge.MergedVideoItem) -> String = { item ->
                    item.shizukuPath?.substringBeforeLast('/')
                        ?: item.parentFolder?.let { if (it.scheme == "file") it.path ?: "" else it.toString() }
                        ?: ""
                }
                val itemsByFolder = items.groupBy(folderKey)
                val importedOk = mutableSetOf<BiliBridge.MergedVideoItem>()

                items.forEachIndexed { index, item ->
                    val progress = (index + 1).toFloat() / total
                    VaultManager.updateProgress(progress, app.getString(R.string.status_processing_bili_batch, index + 1, total, item.name), taskId = VaultManager.TASK_BILI_IMPORT_MERGED)

                    val input = try { app.contentResolver.openInputStream(item.uri) } catch (e: Exception) { null }
                    if (input != null) {
                        VaultManager.digestFileToEntity(
                            context = app,
                            fileName = item.name,
                            inputStream = input,
                            expectedSize = FileUtils.getFileSize(app, item.uri),
                            overrideCategory = VaultManager.CAT_BILI_VIDEOS
                        ).onSuccess { entity ->
                            fileDao.upsert(entity)
                            // 预生成视频缩略图（持久缓存，浏览列表秒开；IO 线程，不阻塞 UI）
                            ThumbnailCache.generateVideoThumbnail(app, entity)
                            importedOk.add(item)
                        }
                    }
                }

                // 核心修复：依据独立开关决定是否删除源文件夹（整文件夹删除，而非只删视频文件）
                if (mergedAutoDelete) {
                    itemsByFolder.forEach { (key, folderItems) ->
                        if (key.isNotEmpty() && folderItems.all { it in importedOk }) {
                            // 该文件夹下所有已合并视频全部导入成功 → 删除整个文件夹（含附属文件）+ 清理空父目录
                            deleteMergedSourceFolder(app, folderItems.first())
                        } else {
                            // 有失败/未选中的文件 → 保守起见仅删除已成功导入的单个文件
                            folderItems.filter { it in importedOk }.forEach { deleteMergedSourceFile(app, it) }
                        }
                    }
                }
            }
            // 核心修复：导入完成后顺手清理 download 下所有无音视频残留文件夹
            // （本批导入删掉源文件夹后，其余残留/空文件夹也应一并清掉）
            BiliBridge.cleanupEmptyBiliDirs(app)
            VaultManager.updateProgress(1f, app.getString(R.string.status_all_tasks_complete), taskId = VaultManager.TASK_BILI_IMPORT_MERGED)
            delay(1000.milliseconds)
            VaultManager.clearProgress(VaultManager.TASK_BILI_IMPORT_MERGED)
            VaultManager.endOperation(VaultManager.TASK_BILI_IMPORT_MERGED)
            VaultManager.triggerRefresh()
        }
    }

    /**
     * 核心修复：删除已合并视频所在的整个 B站 源文件夹（含 entry.json、m4s 碎片等），
     * 并清理空的父目录（download）。优先特权模式（shell 身份递归删除），
     * 其次 file:// 路径，最后 SAF 删除父目录文档。
     */
    private suspend fun deleteMergedSourceFolder(context: Context, item: BiliBridge.MergedVideoItem) {
        try {
            // 1) 特权模式：shell 身份递归删除整个文件夹（长超时 + 失败重试一次）
            if (item.shizukuPath != null) {
                val folder = item.shizukuPath.substringBeforeLast('/')
                var deleted = ShizukuBridge.deletePath(context, folder)
                if (!deleted) {
                    PjmLogger.w("SettingsVM", "Merged folder delete retry: $folder")
                    deleted = ShizukuBridge.deletePath(context, folder)
                }
                if (deleted) {
                    PjmLogger.i("SettingsVM", "Shizuku cleaned merged source folder: $folder")
                    cleanupEmptyBiliParents(context, folder)
                    return
                }
                PjmLogger.e("SettingsVM", "Shizuku merged folder delete failed: $folder")
            }
            // 2) file:// 路径：直接递归删除文件夹
            val folderPath = item.parentFolder?.takeIf { it.scheme == "file" }?.path
                ?: item.uri.path?.substringBeforeLast('/')
            if (folderPath != null) {
                val dir = File(folderPath)
                if (dir.exists() && dir.deleteRecursively()) {
                    PjmLogger.i("SettingsVM", "File cleaned merged source folder: $folderPath")
                    cleanupEmptyBiliParents(context, folderPath)
                    return
                }
            }
            // 3) SAF 兜底：删除父目录文档（parentFolder 是 document URI，需用 deleteDocument）
            if (item.parentFolder != null) {
                val ok = try {
                    DocumentsContract.deleteDocument(context.contentResolver, item.parentFolder)
                    true
                } catch (_: Exception) { false }
                if (ok) {
                    PjmLogger.i("SettingsVM", "SAF cleaned merged source folder: ${item.parentFolder}")
                    return
                }
            }
            // 全部失败 → 回退为单文件删除
            deleteMergedSourceFile(context, item)
        } catch (e: Exception) {
            PjmLogger.e("SettingsVM", "Failed to clean merged source folder", e)
            deleteMergedSourceFile(context, item)
        }
    }

    /** 仅删除单个已合并视频文件（不删除文件夹，用于部分导入成功的保守回退） */
    private suspend fun deleteMergedSourceFile(context: Context, item: BiliBridge.MergedVideoItem) {
        try {
            if (item.shizukuPath != null) {
                var deleted = ShizukuBridge.deletePath(context, item.shizukuPath)
                if (!deleted) {
                    PjmLogger.w("SettingsVM", "Merged delete retry: ${item.name}")
                    deleted = ShizukuBridge.deletePath(context, item.shizukuPath)
                }
                if (deleted) { PjmLogger.i("SettingsVM", "Shizuku cleaned merged source: ${item.name}"); return }
            }
            if (item.uri.scheme == "file") {
                File(item.uri.path ?: "").delete()
                PjmLogger.i("SettingsVM", "File cleaned merged source: ${item.name}")
                return
            }
            // 优先通过父目录树授权删除，其次回退单文件删除
            val deleted = item.parentFolder?.let { parent ->
                DocumentFile.fromTreeUri(context, parent)?.findFile(item.name)?.delete() ?: false
            } ?: false
            if (!deleted) DocumentFile.fromSingleUri(context, item.uri)?.delete()
            PjmLogger.i("SettingsVM", "Successfully cleaned merged video source: ${item.name}")
        } catch (e: Exception) {
            PjmLogger.e("SettingsVM", "Failed to clean merged video source", e)
        }
    }

    fun importBiliItems(items: List<BiliBridge.BiliCacheItem>) {
        _biliItems.value = null
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_BILI_IMPORT)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                _biliItems.value = items
                return@launch
            }
            // 核心修复：整个导入循环在 IO 线程执行（避免缩略图生成/文件删除等阻塞主线程导致卡顿）
            withContext(VaultManager.PjmDispatchers.IO) {
                val total = items.size
                val biliAutoDelete = uiState.value.settings.biliAutoDelete

                items.forEachIndexed { index, item ->
                    val progress = (index + 1).toFloat() / total
                    VaultManager.updateProgress(progress, app.getString(R.string.status_processing_bili_batch, index + 1, total, item.title), taskId = VaultManager.TASK_BILI_IMPORT)

                    val tempMp4 = File(app.cacheDir, "bili_merge_${System.nanoTime()}.mp4")

                    BiliBridge.merge(app, item, tempMp4).onSuccess {
                        if (tempMp4.exists() && tempMp4.length() > 0) {
                            VaultManager.digestFileToEntity(
                                context = app,
                                fileName = "${item.title}.mp4",
                                inputStream = tempMp4.inputStream(),
                                expectedSize = tempMp4.length(),
                                overrideCategory = VaultManager.CAT_BILI_VIDEOS
                            ).onSuccess { entity ->
                                fileDao.upsert(entity)
                                // 预生成视频缩略图（持久缓存，浏览列表秒开；IO 线程，不阻塞 UI）
                                ThumbnailCache.generateVideoThumbnail(app, entity)

                                // 核心修复：按需删除整个视频文件夹及其附属文件 (xml, entry.json等)
                                if (biliAutoDelete) {
                                    try {
                                        // Shizuku 模式：用 shell 身份递归删除（长超时 + 失败重试一次）
                                        if (item.shizukuParentPath != null) {
                                            var deleted = ShizukuBridge.deletePath(app, item.shizukuParentPath)
                                            if (!deleted) {
                                                // 重试一次（可能是删除超时/瞬时失败）
                                                PjmLogger.w("SettingsVM", "Delete retry: ${item.title}")
                                                deleted = ShizukuBridge.deletePath(app, item.shizukuParentPath)
                                            }
                                            if (deleted) PjmLogger.i("SettingsVM", "Shizuku cleaned Bili source folder: ${item.title}")
                                            else {
                                                PjmLogger.e("SettingsVM", "Delete still failed: ${item.title}")
                                                // 核心修复：文件夹删除失败时回退逐文件删除（video/audio），避免内容残留
                                                listOfNotNull(item.shizukuVideoPath, item.shizukuAudioPath).forEach { p ->
                                                    try { ShizukuBridge.deletePath(app, p) } catch (_: Exception) {}
                                                }
                                            }
                                            // 核心修复：删除后清理空的父目录（download），避免漏删最外层空文件夹
                                            cleanupEmptyBiliParents(app, item.shizukuParentPath)
                                        } else if (item.parentFolder.scheme == "file") {
                                            val folderPath = item.parentFolder.path ?: ""
                                            File(folderPath).deleteRecursively()
                                            cleanupEmptyBiliParents(app, folderPath)
                                        } else {
                                            // 核心修复：parentFolder 是 document URI（非 tree URI），
                                            // fromTreeUri 会抛异常导致文件夹漏删，改用 deleteDocument 直接删除文件夹文档
                                            val ok = try {
                                                DocumentsContract.deleteDocument(app.contentResolver, item.parentFolder)
                                                true
                                            } catch (_: Exception) { false }
                                            if (!ok) PjmLogger.e("SettingsVM", "SAF delete Bili folder failed: ${item.title}")
                                        }
                                    } catch (e: Exception) {
                                        PjmLogger.e("SettingsVM", "Failed to clean Bili folder", e)
                                    }
                                }
                            }
                        }
                        tempMp4.delete()
                    }.onFailure { fail ->
                        PjmLogger.e("SettingsVM", "Merge fail: ${fail.message}")
                        tempMp4.delete()
                    }
                }
            }
            // 核心修复：导入完成后顺手清理 download 下所有无音视频残留文件夹
            // （本批导入删掉源文件夹后，其余残留/空文件夹也应一并清掉）
            BiliBridge.cleanupEmptyBiliDirs(app)
            VaultManager.updateProgress(1f, app.getString(R.string.status_all_tasks_complete), taskId = VaultManager.TASK_BILI_IMPORT)
            delay(1000.milliseconds)
            VaultManager.clearProgress(VaultManager.TASK_BILI_IMPORT)
            VaultManager.endOperation(VaultManager.TASK_BILI_IMPORT)
            VaultManager.triggerRefresh()
        }
    }

    /**
     * 删除 B站 视频文件夹后，向上清理空的父目录（download），最多上溯到 Android/data/<pkg> 为止，
     * 避免误删整个应用数据。仅删除【空】目录。
     *
     * 核心修复：
     * 1. 只走内置特权服务（EmbeddedPrivilegedIo）或 File API，绝不经过 ShizukuBridge.listFiles ——
     *    它在内置服务失败时会回退 withService→bindService，无 Shizuku 授权时抛
     *    IllegalStateException，导致整个清理中断（download 没删成）。
     * 2. 删除大目录后服务端可能还在收尾，列表请求会短暂超时；加等待 + 重试，
     *    避免误判"非空"而放弃清理。
     * 3. 一切异常捕获，列表失败视为"非空"（安全优先，不误删）。
     */
    private suspend fun cleanupEmptyBiliParents(context: Context, deletedVideoDir: String) {
        try {
            val pkgRoot = deletedVideoDir.substringBefore("/download", missingDelimiterValue = deletedVideoDir)
            var dir = deletedVideoDir.substringBeforeLast('/')
            // 等内置服务收尾（删除大目录后 list 偶发超时）
            kotlinx.coroutines.delay(300)
            repeat(3) {
                if (!dir.startsWith(pkgRoot) || dir.length <= pkgRoot.length) return
                // 仅内置特权模式或 File API 判断空目录
                val isEmpty = try {
                    if (EmbeddedPrivilegedIo.isAvailable(context)) {
                        // 带重试：服务端收尾中 list 可能超时返回 null，重试一次
                        var r = EmbeddedPrivilegedIo.listFiles(context, dir)
                        if (r == null) { kotlinx.coroutines.delay(500); r = EmbeddedPrivilegedIo.listFiles(context, dir) }
                        r?.isEmpty() == true
                    } else {
                        File(dir).listFiles()?.isEmpty() == true
                    }
                } catch (_: Exception) {
                    false
                }
                if (!isEmpty) return
                val ok = try {
                    if (EmbeddedPrivilegedIo.isAvailable(context)) {
                        EmbeddedPrivilegedIo.deletePath(context, dir)
                    } else {
                        File(dir).delete()
                    }
                } catch (_: Exception) {
                    false
                }
                if (ok) PjmLogger.i("SettingsVM", "Cleaned empty Bili parent dir: $dir")
                else return
                dir = dir.substringBeforeLast('/')
            }
        } catch (e: Exception) {
            PjmLogger.e("SettingsVM", "cleanupEmptyBiliParents failed", e)
        }
    }

    fun performDeleteDuplicates(entities: List<FileEntity>) {
        _duplicateFiles.value = null
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务（删除）防连点，不同任务（如查重）可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                VaultManager.updateProgress(0.5f, app.getString(R.string.status_cleaning_duplicates), taskId = VaultManager.TASK_DELETE)
                VaultManager.deleteFiles(app, entities.map { it.relativePath }, fileDao)
                VaultManager.updateProgress(1.0f, app.getString(R.string.status_all_tasks_complete), taskId = VaultManager.TASK_DELETE)
                delay(1000.milliseconds)
                VaultManager.clearProgress(VaultManager.TASK_DELETE)
                VaultManager.triggerRefresh()
            } finally {
                VaultManager.endOperation(VaultManager.TASK_DELETE)
            }
        }
    }

    /**
     * 随机图片分享：从【PJM 图片库】（images 分类）随机抽取 count 张图片，
     * 加密打包成单个 .pjm 容器存入 PJM 库。
     * 完成后用户可在 PJM 分类分享给好友（好友解密即得随机图片"盲盒"）。
     * @param count 抽取数量
     * @param onDone 完成回调（true=成功；false=图片库为空/失败）
     */
    fun randomPickAndEncrypt(count: Int, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务（加密）防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_ENCRYPT)) {
                Toast.makeText(app, "操作进行中，请稍候", Toast.LENGTH_SHORT).show()
                onDone(false)
                return@launch
            }
            VaultManager.updateProgress(0.05f, app.getString(R.string.status_random_pick), taskId = VaultManager.TASK_ENCRYPT)
            // 从 PJM 图片库（images 分类）随机取 count 张（不足则取全部）
            val entities = fileDao.getRandomFilesByCategory(VaultManager.CAT_IMAGES, count.coerceIn(1, 500))
            if (entities.isEmpty()) {
                VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
                Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                onDone(false)
                VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
                return@launch
            }
            // 实体 → 库内文件 → Uri
            val images = entities.mapNotNull { entity ->
                val f = VaultManager.getFileFromEntity(app, entity)
                if (f.exists()) Uri.fromFile(f) else null
            }
            if (images.isEmpty()) {
                VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
                Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                onDone(false)
                VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
                return@launch
            }
            VaultManager.updateProgress(0.1f, app.getString(R.string.status_encrypting), taskId = VaultManager.TASK_ENCRYPT)
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            // 需求：图片数量少，不需要分卷 —— 直接生成单个 .pjm 容器
            val result = VaultManager.packUrisSingle(
                context = app,
                uris = images,
                category = VaultManager.CAT_PJM,
                baseName = "Random_$timeStamp",
                fileDao = fileDao,
                onProgress = { p -> VaultManager.updateProgress(0.1f + 0.85f * p, app.getString(R.string.status_encrypting), taskId = VaultManager.TASK_ENCRYPT) }
            )
            if (result.isSuccess) {
                VaultManager.updateProgress(1f, app.getString(R.string.toast_random_pick_done, images.size), taskId = VaultManager.TASK_ENCRYPT)
                Toast.makeText(app, app.getString(R.string.toast_random_pick_done, images.size), Toast.LENGTH_LONG).show()
                onDone(true)
            } else {
                VaultManager.updateProgress(0f, app.getString(R.string.error_export_failed_simple), taskId = VaultManager.TASK_ENCRYPT, isError = true)
                onDone(false)
            }
            delay(1500)
            VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
            VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
        }
    }

    fun updateSetting(key: String, value: Any?) {
        viewModelScope.launch {
            when (key) {
                Settings.KEY_CUSTOM_BACKGROUND_ENABLED -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_CUSTOM_BACKGROUND_ENABLED, value)
                Settings.KEY_AUTO_DELETE_ORIGINAL -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_AUTO_DELETE_ORIGINAL, value)
                Settings.KEY_THEME -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_THEME, value as? String)
                Settings.KEY_DYNAMIC_COLOR -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_DYNAMIC_COLOR, value)
                Settings.KEY_CUSTOM_BACKGROUND_URI -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_CUSTOM_BACKGROUND_URI, value as? String)
                Settings.KEY_FILE_VIEW_MODE -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_FILE_VIEW_MODE, value as? String)
                Settings.KEY_GRID_SPAN_COUNT -> if (value is Int) settingsManager.updateIntSetting(SettingsManager.KEY_GRID_SPAN_COUNT, value)
                Settings.KEY_EXPORT_SPLIT_SIZE -> if (value is Int) settingsManager.updateIntSetting(SettingsManager.KEY_EXPORT_SPLIT_SIZE, value)
                Settings.KEY_ARCHIVE_AUTO_EXTRACTION -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_ARCHIVE_AUTO_EXTRACTION, value)
                Settings.KEY_BACKGROUND_OPACITY -> if (value is Float) settingsManager.updateFloatSetting(SettingsManager.KEY_BACKGROUND_OPACITY, value)
                Settings.KEY_GLOBAL_UI_SCALE -> if (value is Float) settingsManager.updateFloatSetting(SettingsManager.KEY_GLOBAL_UI_SCALE, value)
                Settings.KEY_BILI_ROOT_URI -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_BILI_ROOT_URI, value as? String)
                Settings.KEY_BILI_AUTO_DELETE -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_BILI_AUTO_DELETE, value)
                Settings.KEY_BILI_MERGED_AUTO_DELETE -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_BILI_MERGED_AUTO_DELETE, value)
            }
        }
    }

    fun resetAllSettings() { viewModelScope.launch { settingsManager.resetAllSettings() } }

    fun checkIntegrity(onResult: (Map<String, List<FileEntity>>) -> Unit) {
        viewModelScope.launch {
            // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
            if (!VaultManager.tryBeginOperation(VaultManager.TASK_INTEGRITY)) {
                Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                onResult(emptyMap())
                return@launch
            }
            try {
                VaultManager.updateProgress(0f, app.getString(R.string.status_starting_integrity_check), taskId = VaultManager.TASK_INTEGRITY)
                val results = repository.performIntegrityCheck { VaultManager.updateProgress(it, app.getString(R.string.status_verifying_fingerprints), taskId = VaultManager.TASK_INTEGRITY) }
                VaultManager.clearProgress(VaultManager.TASK_INTEGRITY)
                onResult(results)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_INTEGRITY)
            }
        }
    }
}
