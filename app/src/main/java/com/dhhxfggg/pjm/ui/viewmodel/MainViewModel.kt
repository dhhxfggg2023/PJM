package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.Settings
import com.dhhxfggg.pjm.data.repository.CategoryListCache
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.OperationTask
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val settings: Settings.AppSettings = Settings.AppSettings(),
)

/**
 * ViewModel for the Main Screen, managing vault overview and global operations.
 */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        private val app: Application,
        private val repository: FileRepository,
        private val settingsManager: SettingsManager,
    ) : AndroidViewModel(app) {
        private val _categoryCovers = MutableStateFlow<Map<String, FileEntity?>>(emptyMap())

        init {
            // 预热分类列表：主界面点进任何一个分类前就查好，切换时直接命中缓存，几乎是瞬间打开。
            // 顺序按使用频率排：图片最大也最常用，先查；其余分类量很小。
            viewModelScope.launch(Dispatchers.IO) {
                // 首屏先让人用起来：读完大库列表要建大量对象，会带来一次明显 GC。
                // 等界面稳定 1.2s 再预热，冷启动的点击/转场就不会被抢 CPU。
                delay(1200)
                // 音频显示名：库里还是磁盘 UUID 的，用标签标题换掉（只改库内显示名，磁盘不动）
                runCatching { repository.backfillAudioNames() }
                listOf(
                    VaultManager.CAT_IMAGES,
                    VaultManager.CAT_VIDEOS,
                    VaultManager.CAT_BILI_VIDEOS,
                    VaultManager.CAT_TEXTS,
                    VaultManager.CAT_AUDIOS,
                    VaultManager.CAT_OTHERS,
                ).forEach { category ->
                    runCatching {
                        CategoryListCache.put(category, repository.getFilesByCategory(category).first())
                    }
                }
            }
        }

        /**
         * Combined UI state flow for the Main Screen.
         */
        val uiState: StateFlow<MainUiState> =
            combine(
                repository.categoryCounts,
                repository.categorySizes,
                _categoryCovers,
                repository.totalSize,
                VaultManager.activeTasks,
                settingsManager.settings,
            ) { args: Array<Any> ->
                MainUiState(
                    categoryCounts = args[0] as Map<String, Int>,
                    categorySizes = args[1] as Map<String, Long>,
                    categoryCovers = args[2] as Map<String, FileEntity?>,
                    totalVaultSize = args[3] as Long,
                    activeTasks = args[4] as List<OperationTask>,
                    settings = args[5] as Settings.AppSettings,
                )
            }.flowOn(Dispatchers.IO)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = MainUiState(),
                )

        init {
            initializeVault()
        }

        private fun initializeVault() {
            viewModelScope.launch(Dispatchers.IO) {
                // 只查一行 COUNT 判断空库：原来 allFiles.first() 会把 4 万多条实体全读进内存，
                // 每次冷启动都白白分配一次（还触发 GC）
                if (repository.totalFileCount() == 0L) {
                    // 核心修复：启动找回独立任务 id —— 与其他任务并发时互不干扰
                    val hasLock = VaultManager.tryBeginOperation(VaultManager.TASK_INIT)
                    try {
                        repository.initialize { progress ->
                            if (hasLock) {
                                VaultManager.updateProgress(
                                    progress,
                                    app.getString(R.string.status_recovering_files),
                                    taskId = VaultManager.TASK_INIT,
                                )
                            }
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
            // 设置里改了「库封面」开关或更换节奏，回到首页立刻按新设置重算（不用杀进程）
            viewModelScope.launch {
                settingsManager.settings
                    .map { it.coverEnabled to it.coverRefresh }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { refreshCovers() }
            }
        }

        /**
         * Refresh the representative cover images for each category（保留随机封面彩蛋）。
         * 点击占用卡片即刷新：每个分类随机挑一张作封面。
         * 大库性能：底层 getRandomFileByCategory 已改为随机主键定位 + 索引查询（O(log n)），
         * 不再使用 ORDER BY RANDOM() 全表排序（万级记录会卡顿）。
         */
        fun refreshCovers() {
            viewModelScope.launch(Dispatchers.IO) {
                val config = runCatching { settingsManager.settings.first() }.getOrNull()
                // 设置里关掉库封面：首页只显示分类图标，连一次全库随机查询都不做
                if (config != null && !config.coverEnabled) {
                    _categoryCovers.value = emptyMap()
                    return@launch
                }
                // 每日一换：拿「日期」当随机种子，同一天重启多少次封面都不变；
                // 每次一换（默认）：保持原来的随机挑一张。
                val daySeed =
                    if (config?.coverRefresh == "daily") {
                        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
                    } else {
                        null
                    }
                val covers = mutableMapOf<String, FileEntity?>()
                VaultManager.CATEGORIES.forEach { category ->
                    // 图片/视频自己就能当封面；文本、音频、PJM 容器、其他没有缩略图，
                    // 从图片库随机抽一张当封面 —— 首页不再是一排灰色图标。
                    val source =
                        if (category == VaultManager.CAT_IMAGES ||
                            category == VaultManager.CAT_VIDEOS ||
                            category == VaultManager.CAT_BILI_VIDEOS
                        ) {
                            category
                        } else {
                            VaultManager.CAT_IMAGES
                        }
                    // 借图的分类（文本/音频/容器/其他）底层都查「图片」库，
                    // 若直接用同一个 daySeed，借来的封面会全是同一张。
                    // 把「当前分类」混进种子，各分类借到不同的图，同一天内各自稳定。
                    val seed = daySeed?.let { it * 131 + category.hashCode() }
                    covers[category] = repository.getCoverFile(source, seed)
                }
                _categoryCovers.value = covers
            }
        }
    }
