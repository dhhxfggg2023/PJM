package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ViewHistoryDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.ViewHistoryEntity
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * ViewModel for the Discovery Screen, handling random file exploration.
 *
 * 核心：全量打乱队列（Shuffled Round-Robin）——
 * · **一轮内绝不重复**：送出的路径立刻记入 `view_history`，因此不会再次入选；
 * · 一轮 = 分类内每条都看过一遍；全部看过才清空历史、重新洗牌 —— “看完自动重来”；
 * · **进度跨启动延续**：判定只看 `view_history` 有没有记录，
 *   所以库大、分多次看也能接着上次继续，不会每次启动都从头重排。
 *
 * 这里刻意**不保存任何进程内的轮次状态**：发现页的 ViewModel 随导航条目销毁重建，
 * 内存状态会丢，而"看过没有"必须持久 —— 只留一个来源就不会出现两者不一致。
 */
@HiltViewModel
class DiscoveryViewModel
    @Inject
    constructor(
        private val application: Application,
        private val fileDao: FileDao,
        private val viewHistoryDao: ViewHistoryDao,
    ) : AndroidViewModel(application) {
        private val _items = MutableStateFlow<List<DiscoveryItem>>(emptyList())
        private val _discoveryMode = MutableStateFlow(DiscoveryMode.BILI_VIDEOS)
        private val _isLoading = MutableStateFlow(value = false)

        /**
         * Combined UI state for the discovery feature.
         */
        val uiState: StateFlow<DiscoveryUiState> =
            combine(
                _items,
                _discoveryMode,
                _isLoading,
            ) { items, mode, loading ->
                DiscoveryUiState(items, mode, loading)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = DiscoveryUiState(),
            )

        private val queue =
            DiscoveryQueue(
                unviewedPaths = { viewHistoryDao.getUnviewedPathsByCategory(_discoveryMode.value.value) },
                hasAnyPaths = { fileDao.getAllPathsByCategory(_discoveryMode.value.value).isNotEmpty() },
                // 整轮看完 → 清掉本分类的浏览记录，下一轮就又是"全新一遍"
                onRoundRestart = { viewHistoryDao.clearByCategory(_discoveryMode.value.value) },
            )

        private val idGenerator = AtomicLong(0)

        init {
            viewModelScope.launch {
                // 播种"本次会话交出过什么"：重建（离开该页再回来）后，
                // 已经看过的不该再被推一遍。
                runCatching { viewHistoryDao.getViewedPathsByCategory(_discoveryMode.value.value) }
                    .onSuccess { queue.seedServed(it) }
                    .onFailure { PjmLogger.e("DiscoveryViewModel", "恢复浏览记录失败", it) }
                loadMoreItems()
            }
        }

        /**
         * 记录"这一张真的被看了"。
         *
         * **必须逐张调用**，且只在条目真正上屏（分页器停稳）时调用：
         * 早期实现是"取批即写入浏览历史"，用户翻到第 1 张就退出时，
         * 一批 10 张全被记为看过，剩下 9 张再也不会出现 —— 白白少看 9 张。
         *
         * @param relativePath 真正展示过的条目路径
         */
        fun markViewed(relativePath: String) {
            if (relativePath.isBlank()) return
            queue.markServed(relativePath)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    viewHistoryDao.markViewed(
                        ViewHistoryEntity(relativePath, System.currentTimeMillis()),
                    )
                }.onFailure { PjmLogger.e("DiscoveryViewModel", "写入浏览记录失败", it) }
            }
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
            viewModelScope.launch {
                // 只丢弃"已排队"的部分；浏览历史与分类无关，故意保留，
                // 免得切回来时把刚看过的内容重播一遍。
                queue.dropPending()
                loadMoreItems()
            }
        }

        /**
         * 取下一批条目。
         *
         * 队列只产出"还没看过"的路径；取不到内容且本轮确实走完时，才清空本分类历史
         * 重开一轮（"看完自动重来"）—— 重开必须在拿到空批次之后显式做，
         * 否则同一批会跨越轮次边界，轮次就无法归属。
         */
        private suspend fun takeBatch(limit: Int): List<FileEntity> {
            var paths = queue.nextBatch(limit)
            if (paths.isEmpty() && queue.isRoundComplete() && queue.restartNextRound()) {
                paths = queue.nextBatch(limit)
            }
            if (paths.isEmpty()) return emptyList()
            return fileDao.getFilesByPaths(paths)
        }

        /**
         * Loads a new batch of random items from the vault.
         * 从打乱队列顺序取 limit 个（保证一轮内不重复、全部会轮到）。
         */
        fun loadMoreItems() {
            if (_isLoading.value) return
            _isLoading.value = true
            viewModelScope.launch {
                try {
                    val limit = 10

                    val entities: List<FileEntity> =
                        withContext(Dispatchers.IO) { takeBatch(limit) }

                    // 队列已空且库中无文件 → 无内容可展示
                    if (entities.isEmpty()) {
                        _isLoading.value = false
                        return@launch
                    }

                    // 生成条目移到 IO 线程：视频需校验有效性（时长 > 0），
                    // 过滤损坏/黑屏视频（0 字节、元数据无法解析、时长 0s），避免发现页出现无法播放的内容。
                    // 无效视频已从队列弹出，本轮不会再撞上。
                    val newBatch: List<DiscoveryItem> =
                        withContext(Dispatchers.IO) {
                            entities.mapNotNull { entity ->
                                val file = VaultManager.getFileFromEntity(application, entity)
                                if (!file.exists()) return@mapNotNull null
                                val isVideo = entity.category == VaultManager.CAT_BILI_VIDEOS || entity.category == VaultManager.CAT_VIDEOS
                                if (isVideo && !hasValidVideoDuration(file)) return@mapNotNull null
                                val id = idGenerator.incrementAndGet()
                                if (entity.category == VaultManager.CAT_IMAGES) {
                                    DiscoveryItem.Image(id, file, entity)
                                } else {
                                    DiscoveryItem.Video(id, file, entity)
                                }
                            }
                        }

                    _items.value += newBatch
                } catch (e: Exception) {
                    PjmLogger.e("DiscoveryViewModel", "Load items failed", e)
                } finally {
                    _isLoading.value = false
                }
            }
        }

        /**
         * 校验视频是否可正常播放（时长 > 0 且元数据可解析）。
         * 损坏文件、0 字节文件、未完成下载的视频都会被过滤。
         */
        private fun hasValidVideoDuration(file: File): Boolean {
            if (file.length() <= 0) return false
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                duration > 0
            } catch (_: Exception) {
                false
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
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
                // 核心修复：多任务并发 —— 同任务（删除）防连点；查重/扫描等可并行
                if (!VaultManager.tryBeginOperation(VaultManager.TASK_DELETE)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(application, application.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                try {
                    // 顶部横幅：删除进度提示（独立进度条，与其他任务并行显示）
                    VaultManager.updateProgress(0.3f, application.getString(R.string.status_shredding), taskId = VaultManager.TASK_DELETE)
                    VaultManager.deleteFile(application, entity.relativePath, fileDao)
                    _items.update { currentItems ->
                        currentItems.filter { it.entity.relativePath != entity.relativePath }
                    }
                    // 历史记录随之清理，避免"已看过"里残留已删除的路径
                    runCatching { viewHistoryDao.purgeOrphans() }
                    VaultManager.updateProgress(1f, application.getString(R.string.status_delete_done), taskId = VaultManager.TASK_DELETE)
                    delay(1000)
                    VaultManager.clearProgress(VaultManager.TASK_DELETE)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_DELETE)
                }
            }
        }
    }
