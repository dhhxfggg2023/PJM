package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 随机抽取图片后的处理方式。
 */
enum class RandomShareMode {
    /** 打包成单个 `.pjm` 容器存入 PJM 库（好友解开后得到盲盒） */
    PACKAGE_PJM,

    /** 直接把抽取到的源图片交给系统分享（不打包、不改字节） */
    DIRECT_FILES,
}

/**
 * UI State for the Settings Screen.
 */
@Immutable
data class SettingsUiState(
    val settings: Settings.AppSettings = Settings.AppSettings(),
    val isInitialized: Boolean = false,
    val duplicateFiles: List<DuplicateGroup>? = null,
    val biliItems: List<BiliBridge.BiliCacheItem>? = null,
    val isScanningBili: Boolean = false,
    val biliMergedVideos: List<BiliBridge.MergedVideoItem>? = null,
    val isScanningBiliMerged: Boolean = false,
    val shizukuState: ShizukuBridge.AuthState = ShizukuBridge.AuthState.NotInstalled,
    val isShizukuServiceReady: Boolean = false,
)

/**
 * ViewModel for managing application settings and maintenance tasks.
 *
 * 职责边界（这个类曾经 594 行，什么都往里塞）：
 * - Bilibili 缓存扫描 / 合并 / 导入 → [BiliSettingsController]
 * - 查重 / 删除重复 / 重建索引 / 完整性校验 → [VaultMaintenanceController]
 * - 随机抽取图片（打包成 .pjm / 直发源文件） → [RandomPickController]
 *
 * 本类只保留：设置项读写、uiState 组装、以及上面三个控制器的门面转发
 * （转发方法名和参数都保持不变，UI 侧一行都不用改）。
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val app: Application,
        private val settingsManager: SettingsManager,
        private val repository: FileRepository,
        private val fileDao: FileDao,
    ) : AndroidViewModel(app) {
        private val _isInitialized = MutableStateFlow(value = false)

        // 长任务：独占进度条、要在 IO 线程池跑、要处理取消 —— 和设置项读写不是一个关注点
        private val maintenance: VaultMaintenanceController by lazy {
            VaultMaintenanceController(
                app = app,
                fileDao = fileDao,
                repository = repository,
                scope = viewModelScope,
            )
        }

        // 随机抽取：只读数据库 + 打包/分享，不碰设置项
        private val randomPick: RandomPickController by lazy {
            RandomPickController(app = app, fileDao = fileDao, scope = viewModelScope)
        }

        // Bilibili 职责：委托独立控制器（懒加载：需在 uiState 初始化后提供 settings 读取器）
        private val biliController: BiliSettingsController by lazy {
            BiliSettingsController(
                app = app,
                fileDao = fileDao,
                scope = viewModelScope,
                settingsProvider = { uiState.value.settings },
            )
        }

        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsManager.settings,
                _isInitialized,
                maintenance.duplicateFiles,
                biliController.biliItems,
                biliController.isScanningBili,
                biliController.biliMergedVideos,
                biliController.isScanningBiliMerged,
                ShizukuBridge.authState,
                ShizukuBridge.serviceReady,
            ) { values: Array<Any?> ->
                SettingsUiState(
                    settings = values[0] as Settings.AppSettings,
                    isInitialized = values[1] as Boolean,
                    duplicateFiles = values[2] as List<DuplicateGroup>?,
                    biliItems = values[3] as List<BiliBridge.BiliCacheItem>?,
                    isScanningBili = values[4] as Boolean,
                    biliMergedVideos = values[5] as List<BiliBridge.MergedVideoItem>?,
                    isScanningBiliMerged = values[6] as Boolean,
                    shizukuState = values[7] as ShizukuBridge.AuthState,
                    isShizukuServiceReady = values[8] as Boolean,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SettingsUiState(),
            )

        init {
            viewModelScope.launch {
                settingsManager.settings.first()
                _isInitialized.value = true
            }
        }

        fun toggleViewMode() {
            val currentSettings = uiState.value.settings
            val nextMode = if (currentSettings.fileViewMode == "grid") "list" else "grid"
            updateSetting(Settings.KEY_FILE_VIEW_MODE, nextMode)
        }

        // ---------------- 门面转发：库维护 ----------------

        /** 查重（单任务、单进度条、分阶段着色），实现见 [VaultMaintenanceController.scanForDuplicates] */
        fun scanForDuplicates() = maintenance.scanForDuplicates()

        /** 重建索引 */
        fun syncDatabase() = maintenance.syncDatabase()

        fun clearDuplicateState() = maintenance.clearDuplicateState()

        fun performDeleteDuplicates(entities: List<FileEntity>) = maintenance.performDeleteDuplicates(entities)

        fun checkIntegrity(onResult: (Map<String, List<FileEntity>>) -> Unit) = maintenance.checkIntegrity(onResult)

        // ---------------- 门面转发：随机抽取 ----------------

        /** 随机抽图并打包成 .pjm，见 [RandomPickController.randomPickAndEncrypt] */
        fun randomPickAndEncrypt(
            count: Int,
            onDone: (Boolean) -> Unit = {},
        ) = randomPick.randomPickAndEncrypt(count, onDone)

        /** 随机抽图并直接分享源文件，见 [RandomPickController.randomPickForDirectShare] */
        fun randomPickForDirectShare(
            count: Int,
            onPicked: (RandomPickResult) -> Unit = {},
        ) = randomPick.randomPickForDirectShare(count, onPicked)

        // ---------------- 门面转发：Bilibili ----------------

        fun scanBiliCache(rootUri: Uri) {
            biliController.scanBiliCache(rootUri)
        }

        fun clearBiliState() {
            biliController.clearBiliState()
        }

        fun scanBiliMerged(rootUri: Uri) {
            biliController.scanBiliMerged(rootUri)
        }

        fun clearBiliMergedState() {
            biliController.clearBiliMergedState()
        }

        fun importBiliMergedVideos(items: List<BiliBridge.MergedVideoItem>) {
            biliController.importBiliMergedVideos(items)
        }

        fun importBiliItems(items: List<BiliBridge.BiliCacheItem>) {
            biliController.importBiliItems(items)
        }

        // ---------------- 设置项 ----------------

        fun updateSetting(
            key: String,
            value: Any?,
        ) {
            viewModelScope.launch {
                when (key) {
                    Settings.KEY_CUSTOM_BACKGROUND_ENABLED ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_CUSTOM_BACKGROUND_ENABLED,
                                value,
                            )
                        }
                    Settings.KEY_AUTO_DELETE_ORIGINAL ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_AUTO_DELETE_ORIGINAL,
                                value,
                            )
                        }
                    Settings.KEY_THEME ->
                        if (value == null ||
                            value is String
                        ) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_THEME, value as? String)
                        }
                    Settings.KEY_DYNAMIC_COLOR ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_DYNAMIC_COLOR,
                                value,
                            )
                        }
                    Settings.KEY_CUSTOM_BACKGROUND_URI ->
                        if (value == null ||
                            value is String
                        ) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_CUSTOM_BACKGROUND_URI, value as? String)
                        }
                    Settings.KEY_FILE_VIEW_MODE ->
                        if (value == null ||
                            value is String
                        ) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_FILE_VIEW_MODE, value as? String)
                        }
                    Settings.KEY_GRID_SPAN_COUNT ->
                        if (value is Int) {
                            settingsManager.updateIntSetting(
                                SettingsManager.KEY_GRID_SPAN_COUNT,
                                value,
                            )
                        }
                    Settings.KEY_EXPORT_SPLIT_SIZE ->
                        if (value is Int) {
                            settingsManager.updateIntSetting(
                                SettingsManager.KEY_EXPORT_SPLIT_SIZE,
                                value,
                            )
                        }
                    Settings.KEY_ARCHIVE_AUTO_EXTRACTION ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_ARCHIVE_AUTO_EXTRACTION,
                                value,
                            )
                        }
                    Settings.KEY_BACKGROUND_OPACITY ->
                        if (value is Float) {
                            settingsManager.updateFloatSetting(
                                SettingsManager.KEY_BACKGROUND_OPACITY,
                                value,
                            )
                        }
                    Settings.KEY_GLOBAL_UI_SCALE ->
                        if (value is Float) {
                            settingsManager.updateFloatSetting(
                                SettingsManager.KEY_GLOBAL_UI_SCALE,
                                value,
                            )
                        }
                    Settings.KEY_BILI_ROOT_URI ->
                        if (value == null ||
                            value is String
                        ) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_BILI_ROOT_URI, value as? String)
                        }
                    Settings.KEY_BILI_AUTO_DELETE ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_BILI_AUTO_DELETE,
                                value,
                            )
                        }
                    Settings.KEY_BILI_MERGED_AUTO_DELETE ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_BILI_MERGED_AUTO_DELETE,
                                value,
                            )
                        }
                    Settings.KEY_DISCOVERY_DEFAULT_MODE ->
                        if (value == null || value is String) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_DISCOVERY_DEFAULT_MODE, value as? String)
                        }
                    Settings.KEY_COVER_ENABLED ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_COVER_ENABLED,
                                value,
                            )
                        }
                    Settings.KEY_COVER_REFRESH ->
                        if (value == null || value is String) {
                            settingsManager.updateStringSetting(SettingsManager.KEY_COVER_REFRESH, value as? String)
                        }
                    // 之前漏了这个分支：开关点下去没有任何写入，表现就是"关闭失败"
                    Settings.KEY_PREDICTIVE_BACK ->
                        if (value is Boolean) {
                            settingsManager.updateBooleanSetting(
                                SettingsManager.KEY_PREDICTIVE_BACK,
                                value,
                            )
                        }
                }
            }
        }

        fun resetAllSettings() {
            viewModelScope.launch { settingsManager.resetAllSettings() }
        }

        companion object {
            /** 随机抽取数量上限，定义在 [RandomPickController]（唯一的真相） */
            const val MAX_RANDOM_PICK = RandomPickController.MAX_RANDOM_PICK
        }
    }
