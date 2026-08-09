package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * UI State for the Settings Screen.
 *
 * @property settings The current application settings.
 * @property isInitialized Whether the settings have been loaded from storage.
 * @property duplicateFiles A list of detected duplicate files, or null if no scan has been performed.
 */
@Immutable
data class SettingsUiState(
    val settings: Settings.AppSettings = Settings.AppSettings(),
    val isInitialized: Boolean = false,
    val duplicateFiles: List<FileEntity>? = null
)

/**
 * ViewModel for managing application settings and maintenance tasks.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val settingsManager: SettingsManager,
    private val repository: FileRepository,
    private val fileDao: FileDao
) : AndroidViewModel(app) {

    private val _duplicateFiles = MutableStateFlow<List<FileEntity>?>(null)
    private val _isInitialized = MutableStateFlow(false)

    /**
     * Combined UI state flow for the Settings Screen.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        settingsManager.settings,
        _isInitialized,
        _duplicateFiles
    ) { settings, initialized, duplicates ->
        SettingsUiState(settings, initialized, duplicates)
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

    /**
     * Toggles between Grid and List view modes for file lists.
     */
    fun toggleViewMode() {
        val currentSettings = uiState.value.settings
        val nextMode = if (currentSettings.fileViewMode == "grid") "list" else "grid"
        updateSetting(Settings.KEY_FILE_VIEW_MODE, nextMode)
    }

    /**
     * Scans for duplicate files in the vault based on content hash.
     */
    fun scanForDuplicates() {
        viewModelScope.launch {
            VaultManager.updateProgress(0f, "正在分析重复内容...")
            val duplicates = VaultManager.findDuplicateFiles(app, fileDao) { progress ->
                VaultManager.updateProgress(progress, "正在计算内容指纹...")
            }
            _duplicateFiles.value = duplicates
            VaultManager.clearProgress()
        }
    }

    /**
     * Synchronizes the database with the file system, finding lost files.
     */
    fun syncDatabase() {
        viewModelScope.launch {
            VaultManager.updateProgress(0.1f, "正在快速同步...")
            VaultManager.fullSyncDatabase(app, fileDao) { progress ->
                VaultManager.updateProgress(progress, "正在索引文件...")
            }
            VaultManager.updateProgress(1.0f, "同步完成")
            delay(800.milliseconds)
            VaultManager.clearProgress()
        }
    }

    /**
     * Clears the duplicate file scan results from the UI state.
     */
    fun clearDuplicateState() {
        _duplicateFiles.value = null
    }

    /**
     * Deletes the specified duplicate file entities.
     *
     * @param entities The list of file entities to delete.
     */
    fun performDeleteDuplicates(entities: List<FileEntity>) {
        viewModelScope.launch {
            val paths = entities.map { it.relativePath }
            VaultManager.updateProgress(0.5f, "正在清理副本...")
            VaultManager.deleteFiles(app, paths, fileDao)
            VaultManager.updateProgress(1.0f, "清理完成")
            _duplicateFiles.value = null
            delay(1000.milliseconds)
            VaultManager.clearProgress()
        }
    }

    /**
     * Updates a specific setting by key.
     *
     * @param key The settings key to update.
     * @param value The new value for the setting.
     */
    fun updateSetting(key: String, value: Any?) {
        viewModelScope.launch {
            when (key) {
                Settings.KEY_CUSTOM_BACKGROUND_ENABLED -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_CUSTOM_BACKGROUND_ENABLED, value)
                Settings.KEY_AUTO_DELETE_ORIGINAL -> if (value is Boolean) settingsManager.updateBooleanSetting(SettingsManager.KEY_AUTO_DELETE_ORIGINAL, value)
                Settings.KEY_THEME -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_THEME, value as? String)
                Settings.KEY_CUSTOM_FONT_URI -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_CUSTOM_FONT_URI, value as? String)
                Settings.KEY_CUSTOM_BACKGROUND_URI -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_CUSTOM_BACKGROUND_URI, value as? String)
                Settings.KEY_FILE_VIEW_MODE -> if (value == null || value is String) settingsManager.updateStringSetting(SettingsManager.KEY_FILE_VIEW_MODE, value as? String)
                Settings.KEY_GRID_SPAN_COUNT -> if (value is Int) settingsManager.updateIntSetting(SettingsManager.KEY_GRID_SPAN_COUNT, value)
                Settings.KEY_EXPORT_SPLIT_SIZE -> if (value is Int) settingsManager.updateIntSetting(SettingsManager.KEY_EXPORT_SPLIT_SIZE, value)
                Settings.KEY_BACKGROUND_OPACITY -> if (value is Float) settingsManager.updateFloatSetting(SettingsManager.KEY_BACKGROUND_OPACITY, value)
                Settings.KEY_GLOBAL_UI_SCALE -> if (value is Float) settingsManager.updateFloatSetting(SettingsManager.KEY_GLOBAL_UI_SCALE, value)
            }
        }
    }

    /**
     * Resets all settings to their default values.
     */
    fun resetAllSettings() {
        viewModelScope.launch {
            settingsManager.resetAllSettings()
        }
    }

    /**
     * Performs a full integrity check of the vault.
     * @param onResult Callback for the check results.
     */
    fun checkIntegrity(onResult: (Map<String, List<FileEntity>>) -> Unit) {
        viewModelScope.launch {
            VaultManager.updateProgress(0f, "正在开始系统自检...")
            val results = repository.performIntegrityCheck { progress ->
                VaultManager.updateProgress(progress, "正在校对文件指纹...")
            }
            VaultManager.clearProgress()
            onResult(results)
        }
    }
}
