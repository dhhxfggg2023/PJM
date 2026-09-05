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
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.ThumbnailCache
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.PjmLogger
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
    data class Header(val date: String) : FileListItem()
    /**
     * A file item displaying file metadata.
     */
    data class FileItem(
        val entity: FileEntity,
    ) : FileListItem()
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
class FileViewerViewModel @Inject constructor(
    private val app: Application,
    private val repository: FileRepository
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
    fun getFlattenedFiles(category: String): StateFlow<List<FileListItem>> {
        val filesFlow = repository.getFilesByCategory(category)
            .map { files ->
                withContext(Dispatchers.Default) {
                    val grouped = files.groupBy { FileUtils.formatFileTime(it.lastModified) }
                    
                    val result = mutableListOf<FileListItem>()
                    grouped.forEach { (date, items) ->
                        result.add(FileListItem.Header(date))
                        items.forEach { result.add(FileListItem.FileItem(it)) }
                    }
                    result
                }
            }
            .distinctUntilChanged()
            
        return filesFlow.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /**
     * Toggles batch select mode on or off.
     */
    fun setBatchMode(enabled: Boolean) {
        _uiState.update { it.copy(isBatchMode = enabled) }
    }
    
    /**
     * Deletes a single file from the vault.
     */
    suspend fun deleteFile(entity: FileEntity) = withContext(VaultManager.PjmDispatchers.IO) {
        // 核心修复：多任务并发 —— 同任务（删除）防连点；查重/扫描等不同任务可并行进行
        if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
            withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
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
    suspend fun deleteFiles(entities: List<FileEntity>) = withContext(VaultManager.PjmDispatchers.IO) {
        if (entities.isEmpty()) return@withContext
        // 核心修复：多任务并发 —— 同任务（删除）防连点；其他任务可并行
        if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
            withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
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
                withContext(Dispatchers.Main) { Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
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
                        VaultManager.updateProgress(0f, app.getString(R.string.status_recovering_assets), taskId = VaultManager.TASK_RECOVER, isActive = true)
                        repository.syncDatabase()
                        VaultManager.updateProgress(1f, app.getString(R.string.status_recovery_complete), taskId = VaultManager.TASK_RECOVER, isActive = true)
                        delay(1000.milliseconds)
                        VaultManager.clearProgress(VaultManager.TASK_RECOVER)
                    } else {
                        // 如果当前分类文件夹为空，尝试扫描所有分类
                        PjmLogger.d("FileViewerVM", "Category folder empty, trying full sync check...")
                        val totalDb = repository.allFiles.first()
                        if (totalDb.isEmpty()) {
                            val root = VaultManager.getCategoryDir(app, VaultManager.CAT_OTHERS).parentFile
                            val anyPhysical = root?.listFiles()?.any { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) } == true
                            if (anyPhysical) {
                                VaultManager.updateProgress(0f, app.getString(R.string.status_recovering_files), taskId = VaultManager.TASK_RECOVER, isActive = true)
                                repository.syncDatabase()
                                VaultManager.updateProgress(1f, app.getString(R.string.status_recovery_complete), taskId = VaultManager.TASK_RECOVER, isActive = true)
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
