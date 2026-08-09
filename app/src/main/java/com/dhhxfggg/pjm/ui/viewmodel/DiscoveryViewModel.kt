package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Sealed class representing items that can be discovered in the vault.
 */
@Immutable
sealed class DiscoveryItem {
    abstract val displayId: Long
    abstract val entity: FileEntity
    abstract val file: File

    data class Image(
        override val displayId: Long,
        override val file: File,
        override val entity: FileEntity
    ) : DiscoveryItem()

    data class Video(
        override val displayId: Long,
        override val file: File,
        override val entity: FileEntity
    ) : DiscoveryItem()
}

/**
 * Enum for discovery modes.
 */
enum class DiscoveryMode(val value: String) {
    IMAGES("images"),
    VIDEOS("videos")
}

/**
 * UI State for the Discovery Screen.
 */
@Immutable
data class DiscoveryUiState(
    val items: List<DiscoveryItem> = emptyList(),
    val mode: DiscoveryMode = DiscoveryMode.IMAGES,
    val isLoading: Boolean = false
)

/**
 * ViewModel for the Discovery Screen, handling random file exploration.
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val application: Application,
    private val fileDao: FileDao
) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<DiscoveryItem>>(emptyList())
    private val _discoveryMode = MutableStateFlow(DiscoveryMode.IMAGES)
    private val _isLoading = MutableStateFlow(false)

    /**
     * Combined UI state for the discovery feature.
     */
    val uiState: StateFlow<DiscoveryUiState> = combine(
        _items, _discoveryMode, _isLoading
    ) { items, mode, loading ->
        DiscoveryUiState(items, mode, loading)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DiscoveryUiState()
    )

    private val seenPaths = mutableSetOf<String>()
    private val idGenerator = AtomicLong(0)

    init {
        loadMoreItems()
    }

    /**
     * Switches the discovery mode (e.g., from Images to Videos).
     *
     * @param mode The new discovery mode to set.
     */
    fun setMode(mode: DiscoveryMode) {
        if (_discoveryMode.value == mode) return
        _discoveryMode.value = mode
        _items.value = emptyList()
        seenPaths.clear()
        loadMoreItems()
    }

    /**
     * Loads a new batch of random items from the vault.
     */
    fun loadMoreItems() {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val currentMode = _discoveryMode.value.value
                val limit = 10
                
                val entities: List<FileEntity> = withContext(Dispatchers.IO) {
                    if (seenPaths.isEmpty()) {
                        fileDao.getRandomFilesByCategory(currentMode, limit)
                    } else {
                        val excludeList = seenPaths.toList()
                        if (excludeList.size < 900) {
                            fileDao.getRandomFilesByCategoryExcluding(currentMode, excludeList, limit)
                        } else {
                            seenPaths.clear()
                            fileDao.getRandomFilesByCategory(currentMode, limit)
                        }
                    }
                }

                if (entities.isEmpty() && seenPaths.isNotEmpty()) {
                    seenPaths.clear()
                    _isLoading.value = false
                    loadMoreItems()
                    return@launch
                }

                val newBatch: List<DiscoveryItem> = entities.mapNotNull { entity ->
                    val file = VaultManager.getFileFromEntity(application, entity)
                    if (file.exists()) {
                        seenPaths.add(entity.relativePath)
                        val id = idGenerator.incrementAndGet()
                        if (currentMode == "images") {
                            DiscoveryItem.Image(id, file, entity)
                        } else {
                            DiscoveryItem.Video(id, file, entity)
                        }
                    } else null
                }

                _items.value = _items.value + newBatch
            } catch (e: Exception) {
                PjmLogger.e("DiscoveryViewModel", "Load items failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a file from the vault and removes it from the discovery list.
     *
     * @param entity The entity of the file to delete.
     */
    fun deleteFile(entity: FileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            VaultManager.deleteFile(application, entity.relativePath, fileDao)
            _items.update { currentItems ->
                currentItems.filter { it.entity.relativePath != entity.relativePath }
            }
        }
    }
}
