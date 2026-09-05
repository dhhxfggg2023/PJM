package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.OperationTask
import com.dhhxfggg.pjm.domain.util.SettingsManager
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
 * Represent the UI state for the Main Screen.
 *
 * @property categoryCounts Map of category names to the number of files in each.
 * @property categorySizes Map of category names to the total size of files in each.
 * @property categoryCovers Map of category names to a representative FileEntity for each.
 * @property totalVaultSize Total size of all files in the vault.
 * @property activeTasks 当前并行的后台任务进度列表（多任务进度模型，可同时显示多个）
 */
@Immutable
data class MainUiState(
    val categoryCounts: Map<String, Int> = emptyMap(),
    val categorySizes: Map<String, Long> = emptyMap(),
    val categoryCovers: Map<String, FileEntity?> = emptyMap(),
    val totalVaultSize: Long = 0L,
    val activeTasks: List<OperationTask> = emptyList(),
    val settings: Settings.AppSettings = Settings.AppSettings()
)

/**
 * ViewModel for the Main Screen, managing vault overview and global operations.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val app: Application,
    private val repository: FileRepository,
    private val settingsManager: SettingsManager
) : AndroidViewModel(app) {

    private val _categoryCovers = MutableStateFlow<Map<String, FileEntity?>>(emptyMap())

    /**
     * Combined UI state flow for the Main Screen.
     */
    val uiState: StateFlow<MainUiState> = combine(
        repository.categoryCounts,
        repository.categorySizes,
        _categoryCovers,
        repository.totalSize,
        VaultManager.activeTasks,
        settingsManager.settings
    ) { args: Array<Any> ->
        MainUiState(
            categoryCounts = args[0] as Map<String, Int>,
            categorySizes = args[1] as Map<String, Long>,
            categoryCovers = args[2] as Map<String, FileEntity?>,
            totalVaultSize = args[3] as Long,
            activeTasks = args[4] as List<OperationTask>,
            settings = args[5] as Settings.AppSettings
        )
    }.flowOn(Dispatchers.IO)
     .stateIn(
         scope = viewModelScope,
         started = SharingStarted.WhileSubscribed(5000),
         initialValue = MainUiState()
     )

    init {
        initializeVault()
    }

    private fun initializeVault() {
        viewModelScope.launch(Dispatchers.IO) {
            val dbFiles = repository.allFiles.first()
            if (dbFiles.isEmpty()) {
                // 核心修复：启动找回独立任务 id —— 与其他任务并发时互不干扰
                val hasLock = VaultManager.tryBeginOperation(VaultManager.TASK_INIT)
                try {
                    repository.initialize { progress ->
                        if (hasLock) VaultManager.updateProgress(progress, app.getString(R.string.status_recovering_files), taskId = VaultManager.TASK_INIT)
                    }
                    if (hasLock) {
                        VaultManager.updateProgress(1.0f, app.getString(R.string.status_sync_complete), taskId = VaultManager.TASK_INIT)
                        delay(800.milliseconds)
                        VaultManager.clearProgress(VaultManager.TASK_INIT)
                    }
                } finally {
                    if (hasLock) VaultManager.endOperation(VaultManager.TASK_INIT)
                }
            }
            refreshCovers()
        }
    }

    /**
     * Refresh the representative cover images for each category.
     * 大库优化：改用 getLatestFileByCategory（走 id 索引，O(log n)），
     * 替代原 ORDER BY RANDOM() 的全表排序（万级记录会卡顿）。
     * 在网大多数安装的库已达到万级，此改动可显著降低主界面刷新延迟。
     */
    fun refreshCovers() {
        viewModelScope.launch(Dispatchers.IO) {
            val covers = mutableMapOf<String, FileEntity?>()
            VaultManager.CATEGORIES.forEach { category ->
                // 点击占用卡片刷新 → 换成该分类最新导入的文件作为封面
                covers[category] = repository.getLatestFileByCategory(category)
            }
            _categoryCovers.value = covers
        }
    }

    /**
     * Start the process of exporting the entire vault as an encrypted archive.
     *
     * @param onComplete Callback invoked when the export process completes, with a success boolean.
     */
    fun startVaultExport(onComplete: (Boolean) -> Unit) {
        // 核心修复：多任务并发 —— 同任务（导出）防连点，其他任务可并行
        if (!VaultManager.tryBeginOperation(VaultManager.TASK_EXPORT)) return
        
        viewModelScope.launch {
            VaultManager.updateProgress(0f, app.getString(R.string.status_exporting_vault), taskId = VaultManager.TASK_EXPORT)
            val result = repository.exportVault { progress ->
                VaultManager.updateProgress(progress, app.getString(R.string.status_exporting_vault), taskId = VaultManager.TASK_EXPORT)
            }
            VaultManager.clearProgress(VaultManager.TASK_EXPORT)
            VaultManager.endOperation(VaultManager.TASK_EXPORT)
            withContext(Dispatchers.Main) { 
                onComplete(result.isSuccess) 
            }
        }
    }
}
