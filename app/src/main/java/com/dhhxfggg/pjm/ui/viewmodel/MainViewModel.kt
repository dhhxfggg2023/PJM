package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.OperationState
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
 * @property globalOperationState Current status of any global background operations.
 */
@Immutable
data class MainUiState(
    val categoryCounts: Map<String, Int> = emptyMap(),
    val categorySizes: Map<String, Long> = emptyMap(),
    val categoryCovers: Map<String, FileEntity?> = emptyMap(),
    val totalVaultSize: Long = 0L,
    val globalOperationState: OperationState = OperationState()
)

/**
 * ViewModel for the Main Screen, managing vault overview and global operations.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val repository: FileRepository,
) : AndroidViewModel(application) {

    private val _categoryCovers = MutableStateFlow<Map<String, FileEntity?>>(emptyMap())

    /**
     * Combined UI state flow for the Main Screen.
     */
    val uiState: StateFlow<MainUiState> = combine(
        repository.categoryCounts,
        repository.categorySizes,
        _categoryCovers,
        repository.totalSize,
        VaultManager.operationState
    ) { counts, sizes, covers, totalSize, opState ->
        MainUiState(
            categoryCounts = counts,
            categorySizes = sizes,
            categoryCovers = covers,
            totalVaultSize = totalSize,
            globalOperationState = opState
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
                repository.initialize { progress ->
                    VaultManager.updateProgress(progress, "正在找回以前的文件...")
                }
                VaultManager.updateProgress(1.0f, "同步完成")
                delay(800.milliseconds)
                VaultManager.clearProgress()
            }
            refreshCovers()
        }
    }

    /**
     * Refresh the representative cover images for each category.
     */
    fun refreshCovers() {
        viewModelScope.launch(Dispatchers.IO) {
            val covers = mutableMapOf<String, FileEntity?>()
            VaultManager.CATEGORIES.forEach { category ->
                covers[category] = repository.getRandomFileByCategory(category)
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
        val currentState = uiState.value
        if (currentState.globalOperationState.isActive) return
        
        viewModelScope.launch {
            VaultManager.updateProgress(0f, "正在打包导出...")
            val result = repository.exportVault { progress ->
                VaultManager.updateProgress(progress, "正在打包导出...")
            }
            VaultManager.clearProgress()
            withContext(Dispatchers.Main) { 
                onComplete(result.isSuccess) 
            }
        }
    }
}
