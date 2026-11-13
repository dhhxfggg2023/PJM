package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.repository.CategoryListCache
import com.dhhxfggg.pjm.domain.util.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for managing the state of the Media Detail screen.
 * Handles retrieving file metadata and managing media playback states.
 */
@HiltViewModel
class MediaDetailViewModel
    @Inject
    constructor(
        application: Application,
        private val fileDao: FileDao,
    ) : AndroidViewModel(application) {
        private val _fileEntity = MutableStateFlow<FileEntity?>(null)
        val fileEntity: StateFlow<FileEntity?> = _fileEntity

        /** 同分类的文件（按 lastModified 倒序，与文件库列表一致）：查看器靠它左右滑动切下一张 */
        private val _categoryFiles = MutableStateFlow<List<FileEntity>>(emptyList())
        val categoryFiles: StateFlow<List<FileEntity>> = _categoryFiles

        /**
         * Loads the file entity metadata for a given relative path.
         */
        fun loadFile(relativePath: String) {
            viewModelScope.launch {
                val entity = fileDao.findByRelativePath(relativePath)
                _fileEntity.value = entity
                val wantsSiblings =
                    entity != null &&
                        (
                            entity.isImage ||
                                FileUtils.isVideoFile(entity.name) ||
                                FileUtils.isAudioFile(entity.name)
                        )
                _categoryFiles.value =
                    if (wantsSiblings && entity != null) {
                        // 优先用分类缓存：否则每打开一张图都要重新查 4 万行
                        CategoryListCache.get(entity.category)
                            ?: runCatching {
                                fileDao.getFilesByCategoryOnce(entity.category).also {
                                    CategoryListCache.put(entity.category, it)
                                }
                            }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
            }
        }
    }
