package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.request.ImageRequest
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.FileUtils
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.PjmLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        val thumbnail: Bitmap? = null
    ) : FileListItem()
}

/**
 * UI State for the File Viewer Screen.
 *
 * @property isBatchMode Whether the UI is in multi-select batch mode.
 */
@Immutable
data class FileViewerUiState(
    val isBatchMode: Boolean = false
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

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    private var prewarmJob: Job? = null
    private var metadataJob: Job? = null

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
            
        return filesFlow.combine(_thumbnails) { list, thumbnails ->
            list.map { item ->
                if (item is FileListItem.FileItem) {
                    item.copy(thumbnail = thumbnails[item.entity.relativePath])
                } else item
            }
        }
        .onEach { list ->
            val files = list.filterIsInstance<FileListItem.FileItem>().map { it.entity }
            startPrewarmTask(files.take(50))
            loadMetadataForVisibleFiles(files)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun loadMetadataForVisibleFiles(files: List<FileEntity>) {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch(Dispatchers.IO) {
            files.forEach { file ->
                if (!_thumbnails.value.containsKey(file.relativePath)) {
                    val bitmap = extractMetadataThumbnail(file)
                    if (bitmap != null) {
                        _thumbnails.update { it + (file.relativePath to bitmap) }
                    }
                }
            }
        }
    }

    private fun extractMetadataThumbnail(fileEntity: FileEntity): Bitmap? {
        val file = VaultManager.getFileFromEntity(app, fileEntity)
        if (!file.exists()) return null
        
        return try {
            val absolutePath = file.absolutePath
            when (fileEntity.extension.lowercase()) {
                "apk" -> {
                    val pm = app.packageManager
                    val info = pm.getPackageArchiveInfo(absolutePath, 0)
                    info?.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = absolutePath
                        appInfo.publicSourceDir = absolutePath
                        val iconDrawable = appInfo.loadIcon(pm)
                        if (iconDrawable is android.graphics.drawable.BitmapDrawable) {
                            iconDrawable.bitmap
                        } else null
                    }
                }
                "mp3", "flac", "wav", "m4a" -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(absolutePath)
                    val art = retriever.embeddedPicture
                    val bitmap = if (art != null) {
                        BitmapFactory.decodeByteArray(art, 0, art.size)
                    } else null
                    retriever.release()
                    bitmap
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Toggles batch select mode on or off.
     */
    fun setBatchMode(enabled: Boolean) {
        _uiState.update { it.copy(isBatchMode = enabled) }
    }

    private fun startPrewarmTask(files: List<FileEntity>) {
        prewarmJob?.cancel() 
        prewarmJob = viewModelScope.launch(VaultManager.PjmDispatchers.IO) {
            val loader = app.imageLoader
            files.forEachIndexed { index, file ->
                if (index % 10 == 0) delay(150.milliseconds)
                val absolutePath = VaultManager.getFileFromEntity(app, file).absolutePath
                val request = ImageRequest.Builder(app)
                    .data(absolutePath)
                    .size(300) 
                    .build()
                loader.enqueue(request)
            }
        }
    }
    
    /**
     * Deletes a single file from the vault.
     */
    suspend fun deleteFile(entity: FileEntity) = withContext(VaultManager.PjmDispatchers.IO) {
        val absolutePath = VaultManager.getFileFromEntity(app, entity).absolutePath
        app.imageLoader.diskCache?.remove(absolutePath)
        repository.deleteFile(entity)
    }

    /**
     * Deletes multiple files from the vault in a single operation.
     */
    suspend fun deleteFiles(entities: List<FileEntity>) = withContext(VaultManager.PjmDispatchers.IO) {
        if (entities.isEmpty()) return@withContext
        val loader = app.imageLoader
        entities.forEach { entity ->
            val absolutePath = VaultManager.getFileFromEntity(app, entity).absolutePath
            loader.diskCache?.remove(absolutePath)
            loader.memoryCache?.remove(coil3.memory.MemoryCache.Key(absolutePath))
        }
        repository.deleteFiles(entities)
    }

    /**
     * Triggers a database synchronization if the vault or category appears to be empty,
     * helping to recover files that exist physically but are missing from the index.
     */
    fun syncIfEmpty(category: String) {
        viewModelScope.launch(VaultManager.PjmDispatchers.IO) {
            val dbFiles = repository.getFilesByCategory(category).first()
            PjmLogger.d("FileViewerVM", "Checking category [$category], DB count: ${dbFiles.size}")
            
            if (dbFiles.isEmpty()) {
                val dir = VaultManager.getCategoryDir(app, category)
                val physicalFiles = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".thumbnail") }
                
                PjmLogger.d("FileViewerVM", "Physical path: ${dir.absolutePath}, count: ${physicalFiles?.size ?: 0}")
                
                if (!physicalFiles.isNullOrEmpty()) {
                    VaultManager.updateProgress(0f, "正在自动找回资源...", isActive = true)
                    repository.syncDatabase()
                    VaultManager.updateProgress(1f, "找回完成", isActive = true)
                    delay(1000.milliseconds)
                    VaultManager.clearProgress()
                } else {
                    // 如果当前分类文件夹为空，尝试扫描所有分类
                    PjmLogger.d("FileViewerVM", "Category folder empty, trying full sync check...")
                    repository.syncDatabase()
                }
            }
        }
    }

    /**
     * Extracts the contents of a PJM archive back into the general vault.
     */
    fun extractPjmToVault(entity: FileEntity) {
        viewModelScope.launch { 
            repository.extractPjmToVault(entity) 
        }
    }
}
