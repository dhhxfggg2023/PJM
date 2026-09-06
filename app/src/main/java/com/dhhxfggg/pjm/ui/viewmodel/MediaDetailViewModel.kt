package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
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

        /**
         * Loads the file entity metadata for a given relative path.
         */
        fun loadFile(relativePath: String) {
            viewModelScope.launch {
                _fileEntity.value = fileDao.findByRelativePath(relativePath)
            }
        }
    }
