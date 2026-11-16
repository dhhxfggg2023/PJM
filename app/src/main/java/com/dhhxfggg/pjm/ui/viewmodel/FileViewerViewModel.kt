package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.memory.MemoryCache
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.repository.CategoryListCache
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Represents an item in the file list, either a date header or a file.
 */
@Immutable
sealed class FileListItem {
    /**
     * A header displaying a specific date.
     */
    data class Header(
        val date: String,
    ) : FileListItem()

    /**
     * A file item displaying file metadata.
     */
    data class FileItem(
        val entity: FileEntity,
    ) : FileListItem()

    companion object {
        /**
         * 与 [com.dhhxfggg.pjm.domain.util.FileUtils.formatFileTime]（默认本地时区的
         * SimpleDateFormat）一致的「本地日历日」桶键。
         *
         * 旧实现用 `floorDiv(lastModified, 86_400_000)` 按 UTC 切天：UTC+8 下本地
         * 0–8 点的文件会被归到前一天，表头日期与文件显示日期自相矛盾
         * （还会出现重复 / 缺失表头）。
         *
         * [cal] 由调用方复用，避免 4 万条文件时每条都新建一个 Calendar。
         */
        internal fun localDayBucket(
            cal: Calendar,
            timestamp: Long,
        ): Long {
            cal.timeInMillis = timestamp
            return cal.get(Calendar.YEAR).toLong() * 10_000L +
                (cal.get(Calendar.MONTH) + 1) * 100L +
                cal.get(Calendar.DAY_OF_MONTH)
        }
    }
}

/**
 * UI State for the File Viewer Screen.
 *
 * @property isBatchMode Whether the UI is in multi-select batch mode.
 */
@Immutable
data class FileViewerUiState(
    val isBatchMode: Boolean = false,
)

/**
 * ViewModel for viewing and managing files within a specific vault category.
 */
@HiltViewModel
class FileViewerViewModel
    @Inject
    constructor(
        private val app: Application,
        private val repository: FileRepository,
    ) : AndroidViewModel(app) {
        private val _uiState = MutableStateFlow(FileViewerUiState())

        /**
         * UI state for the file viewer.
         */
        val uiState: StateFlow<FileViewerUiState> = _uiState.asStateFlow()

        // 内存管理说明：不再在 ViewModel 中持有 Bitmap。
        // 缩略图统一交由 Coil 的三级缓存（内存 → 磁盘）管理，ViewModel 只暴露文件实体，
        // UI 通过 FileThumbnail 的 AsyncImage 按需加载。此举避免 Bitmap 长时间强引用导致的 OOM。
        // 半永久磁盘缩略图仍由 ThumbnailSyncManager 后台补齐，配合 Coil 磁盘缓存可保证滚动流畅。

        /**
         * 每个分类只建一条列表流：重复调用（同一 VM 内重组 / 返回页面）复用同一条，
         * 避免每次调用都新建一个 stateIn 并重复订阅数据库。
         */
        private val flattenedFlows = ConcurrentHashMap<String, StateFlow<List<FileListItem>?>>()

        /**
         * Returns a flattened list of items (headers and files) for a specific category,
         * filtered by the current search query.
         *
         * @param category The category of files to retrieve.
         */
        fun getFlattenedFiles(category: String): StateFlow<List<FileListItem>?> = flattenedFlows.getOrPut(category) { createFlattenedFlow(category) }

        /**
         * 建一条分类列表流（每个分类只建一次，见 [getFlattenedFiles]）。
         *
         * 首帧不再在调用线程（主线程）上同步跑 [buildFlattened]：把「4 万条实体 →
         * 带日期表头的列表」这一步挪到后台线程（[warmFlow]），建好之前先发 null，
         * 界面据此显示加载态而不是「暂无资源」。
         *
         * 命中分类实体缓存时，预热结果会先于数据库结果发出（主线程全程不参与），
         * 首屏几乎立即就绪；真正的列表仍以数据库流为准。
         */
        private fun createFlattenedFlow(category: String): StateFlow<List<FileListItem>?> {
            // 预热：只把「建列表」挪到后台线程，数据来源不变（分类缓存 → 数据库）
            val warmFlow =
                flow {
                    val entities = CategoryListCache.get(category)
                    if (entities != null) {
                        emit(withContext(Dispatchers.Default) { buildFlattened(category, entities) })
                    }
                }
            val liveFlow =
                repository
                    .getFilesByCategory(category)
                    // 导入/接管会在短时间内连续写库：合并成一次列表重建，避免 4 万条列表反复重建
                    .debounce(200)
                    .onEach { CategoryListCache.put(category, it) }
                    .map { files -> withContext(Dispatchers.Default) { buildFlattened(category, files) } }
                    .distinctUntilChanged()
                    .flowOn(Dispatchers.Default)
            val merged: Flow<List<FileListItem>?> =
                flow {
                    emitAll(warmFlow)
                    emitAll(liveFlow)
                }
            // initialValue = null 表示「还没查到」：界面据此显示加载而不是「暂无资源」
            // 核心修复：Eagerly 让页面不可见时 4 万行列表仍持续重建；改生命周期感知的订阅
            return merged.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

        /**
         * 按【天】分桶后再格式化日期。
         *
         * 旧写法对每条文件都跑一次 Date/SimpleDateFormat，4 万条时单次就要上百毫秒；
         * 分桶后格式化次数从 O(文件数) 降到 O(天数)。
         */
        private fun buildFlattened(
            category: String,
            files: List<FileEntity>,
        ): List<FileListItem> {
            val startedAt = System.currentTimeMillis()
            val result = mutableListOf<FileListItem>()
            var currentBucket = Long.MIN_VALUE
            // 本地时区，与表头 formatFileTime 保持同一日历日定义
            val calendar = Calendar.getInstance()
            files.forEach { entity ->
                val bucket = FileListItem.localDayBucket(calendar, entity.lastModified)
                if (bucket != currentBucket) {
                    currentBucket = bucket
                    result.add(FileListItem.Header(FileUtils.formatFileTime(entity.lastModified)))
                }
                result.add(FileListItem.FileItem(entity))
            }
            PjmLogger.d(
                "FileViewerViewModel",
                "构建列表: " + category + " " + files.size + " 条 / " + (System.currentTimeMillis() - startedAt) + "ms",
            )
            return result
        }

        /**
         * Toggles batch select mode on or off.
         */
        fun setBatchMode(enabled: Boolean) {
            _uiState.update { it.copy(isBatchMode = enabled) }
        }

        /**
         * 重命名「库内显示名」。
         *
         * 只改数据库里的 name，磁盘文件名永远是 UUID —— 显示名与物理名彻底分开：
         * 用户能按自己起的名字找文件，磁盘那一侧的命名规则不会被任何人打乱。
         */
        fun renameDisplayName(
            entity: FileEntity,
            newName: String,
        ) {
            val trimmed = newName.trim()
            if (trimmed.isBlank() || trimmed == entity.name) return
            viewModelScope.launch {
                runCatching { repository.updateDisplayName(entity.relativePath, trimmed) }
                    .onFailure { PjmLogger.e("FileViewerViewModel", "重命名失败", it) }
            }
        }

        /**
         * Deletes a single file from the vault.
         */
        suspend fun deleteFile(entity: FileEntity) =
            withContext(VaultManager.PjmDispatchers.IO) {
                // 核心修复：多任务并发 —— 同任务（删除）防连点；查重/扫描等不同任务可并行进行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
                    withContext(
                        Dispatchers.Main,
                    ) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
                    return@withContext
                }
                try {
                    // 顶部横幅：删除进度（独立进度条）
                    VaultManager.updateProgress(0.3f, app.getString(R.string.status_shredding), taskId = VaultManager.TASK_DELETE)
                    val absolutePath = VaultManager.getFileFromEntity(app, entity).absolutePath
                    app.imageLoader.diskCache?.remove(absolutePath)
                    ThumbnailCache.delete(app, entity)
                    repository.deleteFile(entity)
                    VaultManager.updateProgress(1f, app.getString(R.string.status_delete_done), taskId = VaultManager.TASK_DELETE)
                    delay(1000)
                    VaultManager.clearProgress(VaultManager.TASK_DELETE)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_DELETE)
                }
            }

        /**
         * Deletes multiple files from the vault in a single operation.
         */
        suspend fun deleteFiles(entities: List<FileEntity>) =
            withContext(VaultManager.PjmDispatchers.IO) {
                if (entities.isEmpty()) return@withContext
                // 核心修复：多任务并发 —— 同任务（删除）防连点；其他任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
                    withContext(
                        Dispatchers.Main,
                    ) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
                    return@withContext
                }
                try {
                    // 顶部横幅：批量删除进度（独立进度条）
                    VaultManager.updateProgress(0.2f, app.getString(R.string.status_shredding), taskId = VaultManager.TASK_DELETE)
                    val loader = app.imageLoader
                    val pathsToRemove = mutableSetOf<String>()
                    entities.forEach { entity ->
                        val absolutePath = VaultManager.getFileFromEntity(app, entity).absolutePath
                        loader.diskCache?.remove(absolutePath)
                        loader.memoryCache?.remove(MemoryCache.Key(absolutePath))
                        ThumbnailCache.delete(app, entity)
                        pathsToRemove.add(entity.relativePath)
                    }
                    repository.deleteFiles(entities)
                    VaultManager.updateProgress(1f, app.getString(R.string.status_delete_done), taskId = VaultManager.TASK_DELETE)
                    delay(1000)
                    VaultManager.clearProgress(VaultManager.TASK_DELETE)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_DELETE)
                }
            }

        /**
         * Extracts the contents of a PJM archive back into the general vault.
         */
        fun extractPjmToVault(entity: FileEntity) {
            viewModelScope.launch {
                // 核心修复：多任务并发 —— 同任务防连点，其他任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_EXTRACT)) {
                    withContext(
                        Dispatchers.Main,
                    ) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                try {
                    repository.extractPjmToVault(entity)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_EXTRACT)
                }
            }
        }

        /**
         * Triggers a database synchronization if the vault or category appears to be empty,
         * helping to recover files that exist physically but are missing from the index.
         */
        fun syncIfEmpty(category: String) {
            viewModelScope.launch(VaultManager.PjmDispatchers.IO) {
                // 核心修复：多任务并发 —— 同任务防连点，其他任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_RECOVER)) return@launch
                try {
                    // 判空只需要一次聚合查询：原来把该分类的实体全读进内存，只为看 size 是否为 0
                    val dbCount = repository.categoryCounts.first()[category] ?: 0
                    PjmLogger.d("FileViewerVM", "Checking category [$category], DB count: $dbCount")

                    if (dbCount == 0) {
                        val dir = VaultManager.getCategoryDir(app, category)
                        val physicalFiles = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".thumbnail") }

                        PjmLogger.d("FileViewerVM", "Physical path: ${dir.absolutePath}, count: ${physicalFiles?.size ?: 0}")

                        if (!physicalFiles.isNullOrEmpty()) {
                            VaultManager.updateProgress(
                                0f,
                                app.getString(R.string.status_recovering_assets),
                                taskId = VaultManager.TASK_RECOVER,
                                isActive = true,
                            )
                            repository.syncDatabase()
                            VaultManager.updateProgress(
                                1f,
                                app.getString(R.string.status_recovery_complete),
                                taskId = VaultManager.TASK_RECOVER,
                                isActive = true,
                            )
                            delay(1000.milliseconds)
                            VaultManager.clearProgress(VaultManager.TASK_RECOVER)
                        } else {
                            // 如果当前分类文件夹为空，尝试扫描所有分类
                            PjmLogger.d("FileViewerVM", "Category folder empty, trying full sync check...")
                            // 核心修复：为了判空把全库 4 万条实体读进内存，改用 COUNT
                            if (repository.totalFileCount() == 0L) {
                                val root = VaultManager.getCategoryDir(app, VaultManager.CAT_OTHERS).parentFile
                                val anyPhysical =
                                    root?.listFiles()?.any { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) } == true
                                if (anyPhysical) {
                                    VaultManager.updateProgress(
                                        0f,
                                        app.getString(R.string.status_recovering_files),
                                        taskId = VaultManager.TASK_RECOVER,
                                        isActive = true,
                                    )
                                    repository.syncDatabase()
                                    VaultManager.updateProgress(
                                        1f,
                                        app.getString(R.string.status_recovery_complete),
                                        taskId = VaultManager.TASK_RECOVER,
                                        isActive = true,
                                    )
                                    delay(1000.milliseconds)
                                    VaultManager.clearProgress(VaultManager.TASK_RECOVER)
                                }
                            }
                        }
                    }
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_RECOVER)
                }
            }
        }
    }
