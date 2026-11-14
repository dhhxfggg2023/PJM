package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.shizuku.ShizukuBridge
import com.dhhxfggg.pjm.domain.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * 随机抽取图片后的处理方式。
 */
enum class RandomShareMode {
    /** 加密打包成单个 `.pjm` 容器存入 PJM 库（好友解密后得到盲盒） */
    PACKAGE_PJM,

    /** 直接把抽取到的源图片交给系统分享（不打包、不改字节） */
    DIRECT_FILES,
}

/**
 * 直接分享源文件的结果。
 *
 * @param paths    抽取到的源文件绝对路径（交给系统分享用）
 * @param skipped  抽到但文件已不在磁盘上的数量（据实告知用户，而不是静默少发）
 */
@Immutable
data class RandomPickResult(
    val paths: List<String>,
    val skipped: Int,
)

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
 * Bilibili 缓存扫描/导入/清理职责已下沉到 [BiliSettingsController]，
 * 本类仅做门面转发与其余维护操作。
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
        private val _duplicateFiles = MutableStateFlow<List<DuplicateGroup>?>(null)
        private val _isInitialized = MutableStateFlow(value = false)

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
                _duplicateFiles,
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

        /**
         * 查重（单任务、单进度条、分阶段着色）。
         *
         * 核心修复：历史实现把「精确查重」和「图片感知查重」拆成两个 taskId ——
         * 顶部会并排出现两条进度条，第一条走到 100% 消失、第二条从 0% 重新起跳，
         * 用户看到"刚满又归零"，以为第一轮白跑了。
         *
         * 现在两个阶段共用 [VaultManager.TASK_DUPLICATES] 一条进度条，并规划固定区间：
         *   精确查重（MD5 / 视频指纹）  0%  → 45%
         *   图片感知指纹计算            45% → 75%
         *   候选对比对（dHash/灰度/像素） 75% → 92%
         *   分组收尾                    92% → 100%
         * 进度严格单调递增，每个阶段结束显式打点，不出现回跳或长时间停顿。
         */
        fun scanForDuplicates() {
            viewModelScope.launch(VaultManager.PjmDispatchers.IO) {
                // 核心修复：多任务并发 —— 同任务防连点，不同任务（如删除）可并行进行；
                // 核心修复：整个查重在 IO 线程池执行，避免主线程被阻塞卡死
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DUPLICATES)) {
                    Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                try {
                    fun report(
                        progress: Float,
                        message: String,
                    ) {
                        VaultManager.updateProgress(
                            progress = progress,
                            message = message,
                            taskId = VaultManager.TASK_DUPLICATES,
                            stages = DUP_STAGE_BOUNDS,
                        )
                    }

                    // 阶段 1：精确查重（MD5 完全一致 + 视频感知指纹）—— 0% → 45%
                    report(0f, app.getString(R.string.status_analyzing_duplicates))
                    val exactDuplicates =
                        VaultManager.findDuplicateFiles(app, fileDao) { progress: Float ->
                            report(0.45f * progress, app.getString(R.string.status_calculating_fingerprints))
                        }
                    report(0.45f, app.getString(R.string.status_analyzing_duplicates))

                    // 阶段 2/3/4：图片感知查重。findSimilarImages 的内部进度：
                    //   0.0 – 0.6 指纹计算 → 45%–75%；0.6 – 0.8 候选比对 → 75%–92%；
                    //   0.8 – 1.0 分组收尾 → 92%–100%（扫描器在比对结束后打点 0.9f）。
                    val similarImages =
                        VaultManager.findSimilarImages(app, fileDao) { progress: Float ->
                            when {
                                progress <= 0.6f ->
                                    report(
                                        0.45f + 0.30f * (progress / 0.6f),
                                        app.getString(R.string.status_calculating_image_fingerprints),
                                    )

                                progress <= 0.8f ->
                                    report(
                                        0.75f + 0.17f * ((progress - 0.6f) / 0.2f),
                                        app.getString(R.string.status_comparing_images),
                                    )

                                else ->
                                    report(
                                        0.92f + 0.08f * ((progress - 0.8f) / 0.2f),
                                        app.getString(R.string.status_grouping_duplicates),
                                    )
                            }
                        }

                    // 核心修复：被用户取消时不弹结果窗（避免误导为"查完无重复"）
                    if (VaultManager.isTaskCancelled(VaultManager.TASK_DUPLICATES)) {
                        PjmLogger.i("SettingsVM", "查重已取消，不展示结果")
                        return@launch
                    }
                    report(1f, app.getString(R.string.status_all_tasks_complete))
                    // 让进度条停在 100% 一小会儿，再弹结果窗（否则"满格"与"弹窗"同帧，看不出完成感）
                    delay(300.milliseconds)
                    // 合并分组：精确查重组 + 图片感知组（各组的文件不重叠，直接拼接）
                    _duplicateFiles.value = exactDuplicates + similarImages
                } catch (e: CancellationException) {
                    // 用户取消：静默结束，不弹错误
                    PjmLogger.i("SettingsVM", "查重已取消: ${e.message}")
                } catch (e: Throwable) {
                    // 核心修复：捕获 Throwable（含 OOM）—— 查重异常不崩溃，清理进度后返回
                    PjmLogger.e("SettingsVM", "查重异常: ${e.javaClass.simpleName}: ${e.message}", e as? Exception)
                } finally {
                    VaultManager.clearProgress(VaultManager.TASK_DUPLICATES)
                    VaultManager.endOperation(VaultManager.TASK_DUPLICATES)
                }
            }
        }

        fun syncDatabase() {
            viewModelScope.launch {
                // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_SYNC)) {
                    Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                try {
                    VaultManager.updateProgress(0.1f, app.getString(R.string.status_syncing_fast), taskId = VaultManager.TASK_SYNC)
                    VaultManager.fullSyncDatabase(app, fileDao) { progress: Float ->
                        VaultManager.updateProgress(
                            progress,
                            app.getString(R.string.status_indexing_files),
                            taskId = VaultManager.TASK_SYNC,
                        )
                    }
                    VaultManager.updateProgress(1.0f, app.getString(R.string.status_sync_complete), taskId = VaultManager.TASK_SYNC)
                    delay(800.milliseconds)
                    VaultManager.clearProgress(VaultManager.TASK_SYNC)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_SYNC)
                }
            }
        }

        fun clearDuplicateState() {
            _duplicateFiles.value = null
        }

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

        fun performDeleteDuplicates(entities: List<FileEntity>) {
            _duplicateFiles.value = null
            viewModelScope.launch {
                // 核心修复：多任务并发 —— 同任务（删除）防连点，不同任务（如查重）可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
                    Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                try {
                    VaultManager.updateProgress(0.5f, app.getString(R.string.status_cleaning_duplicates), taskId = VaultManager.TASK_DELETE)
                    VaultManager.deleteFiles(app, entities.map { it.relativePath }, fileDao)
                    VaultManager.updateProgress(1.0f, app.getString(R.string.status_all_tasks_complete), taskId = VaultManager.TASK_DELETE)
                    delay(1000.milliseconds)
                    VaultManager.clearProgress(VaultManager.TASK_DELETE)
                    VaultManager.triggerRefresh()
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_DELETE)
                }
            }
        }

        /**
         * 随机图片分享：从【PJM 图片库】（images 分类）随机抽取 count 张图片，
         * 加密打包成单个 .pjm 容器存入 PJM 库。
         * 完成后用户可在 PJM 分类分享给好友（好友解密即得随机图片"盲盒"）。
         * @param count 抽取数量
         * @param onDone 完成回调（true=成功；false=图片库为空/失败）
         */
        fun randomPickAndEncrypt(
            count: Int,
            onDone: (Boolean) -> Unit = {},
        ) {
            viewModelScope.launch {
                // 核心修复：多任务并发 —— 同任务（加密）防连点，不同任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_ENCRYPT)) {
                    Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    onDone(false)
                    return@launch
                }
                VaultManager.updateProgress(0.05f, app.getString(R.string.status_random_pick), taskId = VaultManager.TASK_ENCRYPT)
                // 从 PJM 图片库（images 分类）随机取 count 张（不足则取全部）
                val entities = fileDao.getRandomFilesByCategory(VaultManager.CAT_IMAGES, count.coerceIn(1, MAX_RANDOM_PICK))
                if (entities.isEmpty()) {
                    VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
                    Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                    onDone(false)
                    VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
                    return@launch
                }
                // 实体 → 库内文件 → Uri
                val images =
                    entities.mapNotNull { entity ->
                        val f = VaultManager.getFileFromEntity(app, entity)
                        if (f.exists()) Uri.fromFile(f) else null
                    }
                if (images.isEmpty()) {
                    VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
                    Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                    onDone(false)
                    VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
                    return@launch
                }
                VaultManager.updateProgress(0.1f, app.getString(R.string.status_encrypting), taskId = VaultManager.TASK_ENCRYPT)
                val timeStamp = VaultNaming.readableTimestamp()
                // 需求：图片数量少，不需要分卷 —— 直接生成单个 .pjm 容器
                val result =
                    VaultManager.packUrisSingle(
                        context = app,
                        uris = images,
                        category = VaultManager.CAT_PJM,
                        baseName = "Random_$timeStamp",
                        fileDao = fileDao,
                        onProgress = { p ->
                            VaultManager.updateProgress(
                                0.1f + 0.85f * p,
                                app.getString(R.string.status_encrypting),
                                taskId = VaultManager.TASK_ENCRYPT,
                            )
                        },
                    )
                if (result.isSuccess) {
                    VaultManager.updateProgress(
                        1f,
                        app.getString(R.string.toast_random_pick_done, images.size),
                        taskId = VaultManager.TASK_ENCRYPT,
                    )
                    Toast.makeText(app, app.getString(R.string.toast_random_pick_done, images.size), Toast.LENGTH_LONG).show()
                    onDone(true)
                } else {
                    VaultManager.updateProgress(
                        0f,
                        app.getString(R.string.error_export_failed_simple),
                        taskId = VaultManager.TASK_ENCRYPT,
                        isError = true,
                    )
                    onDone(false)
                }
                delay(1500)
                VaultManager.clearProgress(VaultManager.TASK_ENCRYPT)
                VaultManager.endOperation(VaultManager.TASK_ENCRYPT)
            }
        }

        /**
         * 随机抽取图片并**直接分享源文件**（不打包成 .pjm）。
         *
         * 与 [randomPickAndEncrypt] 的差别只在最后一步：这里不动文件字节，
         * 把抽到的原图逐个交给系统分享。抽到但磁盘上已不存在的会被跳过，
         * 数量经 [onPicked] 据实回报，避免"以为发了 N 张、实际少了"。
         *
         * 分享动作由调用方（Activity/Compose）执行 —— ViewModel 不持有启动 Activity 的职责。
         */
        fun randomPickForDirectShare(
            count: Int,
            onPicked: (RandomPickResult) -> Unit = {},
        ) {
            viewModelScope.launch {
                val entities =
                    runCatching {
                        fileDao.getRandomFilesByCategory(VaultManager.CAT_IMAGES, count.coerceIn(1, MAX_RANDOM_PICK))
                    }.getOrDefault(emptyList())
                if (entities.isEmpty()) {
                    Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                    onPicked(RandomPickResult(emptyList(), 0))
                    return@launch
                }
                val files =
                    entities.mapNotNull { entity ->
                        VaultManager.getFileFromEntity(app, entity).takeIf { it.exists() }
                    }
                if (files.isEmpty()) {
                    Toast.makeText(app, app.getString(R.string.toast_random_pick_empty), Toast.LENGTH_LONG).show()
                    onPicked(RandomPickResult(emptyList(), entities.size))
                    return@launch
                }
                onPicked(RandomPickResult(files.map { it.absolutePath }, entities.size - files.size))
            }
        }

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

        fun checkIntegrity(onResult: (Map<String, List<FileEntity>>) -> Unit) {
            viewModelScope.launch {
                // 核心修复：多任务并发 —— 同任务防连点，不同任务可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_INTEGRITY)) {
                    Toast.makeText(app, app.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    onResult(emptyMap())
                    return@launch
                }
                try {
                    VaultManager.updateProgress(
                        0f,
                        app.getString(R.string.status_starting_integrity_check),
                        taskId = VaultManager.TASK_INTEGRITY,
                    )
                    val results =
                        repository.performIntegrityCheck {
                            VaultManager.updateProgress(
                                it,
                                app.getString(R.string.status_verifying_fingerprints),
                                taskId = VaultManager.TASK_INTEGRITY,
                            )
                        }
                    VaultManager.clearProgress(VaultManager.TASK_INTEGRITY)
                    onResult(results)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_INTEGRITY)
                }
            }
        }

        companion object {
            /**
             * 查重单条进度条的分段边界（累计百分比，最后一项必须是 1f）：
             * 精确查重 0–45% / 图片指纹 45–75% / 候选比对 75–92% / 分组收尾 92–100%。
             * 只在这里维护；UI 依据它把一条进度条切成彩色分段。
             */
            private val DUP_STAGE_BOUNDS = listOf(0.45f, 0.75f, 0.92f, 1f)

            /**
             * 随机抽取的数量上限。
             *
             * 直发源文件时上限不宜太大：接收方对一次性多图有各自的限制，
             * 界面上的快捷档位（5/10/20/50）也正好与之对齐。
             */
            const val MAX_RANDOM_PICK = 50
        }
    }
