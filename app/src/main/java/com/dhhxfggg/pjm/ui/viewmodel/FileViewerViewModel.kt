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
        /** 日期分桶粒度（毫秒/天）；internal 供 getFlattenedFiles 使用 */
        internal const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
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
         * Returns a flattened list of items (headers and files) for a specific category,
         * filtered by the current search query.
         *
         * @param category The category of files to retrieve.
         */
        fun getFlattenedFiles(category: String): StateFlow<List<FileListItem>?> {
            // 先用缓存把首帧顶上：4 万行的查询不值得让用户每次都盯着转圈
            val cached = CategoryListCache.get(category)?.let { buildFlattened(category, it) }
            return repository
                .getFilesByCategory(category)
                // 导入/接管会在短时间内连续写库：合并成一次列表重建，避免 4 万条列表反复重建
                .debounce(200)
                .onEach { CategoryListCache.put(category, it) }
                .map { files -> withContext(Dispatchers.Default) { buildFlattened(category, files) } }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                // initialValue = null 表示「还没查到」：界面据此显示加载而不是「暂无资源」
                .stateIn(viewModelScope, SharingStarted.Eagerly, cached)
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
            files.forEach { entity ->
                val bucket = Math.floorDiv(entity.lastModified, FileListItem.MILLIS_PER_DAY)
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
                    val dbFiles = repository.getFilesByCategory(category).first()
                    PjmLogger.d("FileViewerVM", "Checking category [$category], DB count: ${dbFiles.size}")

                    if (dbFiles.isEmpty()) {
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
                            val totalDb = repository.allFiles.first()
                            if (totalDb.isEmpty()) {
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
