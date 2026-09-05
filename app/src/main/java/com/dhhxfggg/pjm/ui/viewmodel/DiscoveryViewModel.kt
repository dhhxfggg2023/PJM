package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.PjmLogger
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
 * 每次从 DB 拉取当前分类全部路径打乱排队，顺序消费；
 * 全部轮完（队列耗尽且无新增）才重新打乱开新一轮；
 * 中途新增的文件自动并入当前队列队尾（不等下一轮）。
 * 保证：一轮内所有文件都会轮到、不重复，时间最少。
 */
@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val application: Application,
    private val fileDao: FileDao,
) : AndroidViewModel(application) {

    private val _items = MutableStateFlow<List<DiscoveryItem>>(emptyList())
    private val _discoveryMode = MutableStateFlow(DiscoveryMode.BILI_VIDEOS)
    private val _isLoading = MutableStateFlow(value = false)

    /**
     * Combined UI state for the discovery feature.
     */
    val uiState: StateFlow<DiscoveryUiState> = combine(
        _items, 
        _discoveryMode, 
        _isLoading,
    ) { items, mode, loading ->
        DiscoveryUiState(items, mode, loading)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DiscoveryUiState()
    )

    // 核心修复：全量打乱队列（替代"随机+排除"）。
    // 旧实现：每次随机 10 个 + seenPaths 排除，排除列表 >800 就清空重来 ——
    // 1.3 万张图片一轮只轮到 800 张就重置，剩下 1.2 万张永远轮不到，反复撞上已看的 → 老重复。
    // 新实现：
    //   · pendingQueue = 全库 relativePath 打乱后的待消费队列，顺序取（不重复）；
    //   · seenInRound = 本轮已消费集合；队列耗尽且无新增时 → 全量重新打乱开始新的一轮；
    //   · 中途从外界新增的文件：每次补充队列时检测（全量 - 本轮已看 - 已排队），打乱后追加到队尾，
    //     立即并入当前梯队，不用等下一轮。
    private val pendingQueue = ArrayDeque<String>()
    private val seenInRound = mutableSetOf<String>()
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
        pendingQueue.clear()
        seenInRound.clear()
        loadMoreItems()
    }

    /**
     * 从 DB 拉当前分类全部路径，把"本轮未看过且未排队"的新增文件打乱追加到队列尾部。
     * 队列为空且无新增时（本轮已轮完）→ 全量重新打乱开始新的一轮。
     */
    private suspend fun refillQueue(category: String) {
        val all = fileDao.getAllPathsByCategory(category)
        if (all.isEmpty()) return
        val queued = pendingQueue.toHashSet()
        // 新增 = 全量 - 本轮已消费 - 已排队（尚未消费）
        val fresh = all.filter { it !in seenInRound && it !in queued }
        if (fresh.isNotEmpty()) {
            pendingQueue.addAll(fresh.shuffled())
        } else if (pendingQueue.isEmpty()) {
            // 无新增且队列空 → 本轮全部轮完，开启新一轮（全量重新打乱）
            seenInRound.clear()
            pendingQueue.addAll(all.shuffled())
        }
        // 极端兜底：all 全被 seenInRound 消费但 queue 非空时不重开（本轮还剩排队项）
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
                val currentMode = _discoveryMode.value.value
                val limit = 10
                
                val entities: List<FileEntity> = withContext(Dispatchers.IO) {
                    // 队列不足时补充（全量路径查询是单列 select，万级记录毫秒级）
                    if (pendingQueue.size < limit) refillQueue(currentMode)
                    if (pendingQueue.isEmpty()) return@withContext emptyList()
                    // 顺序取 limit 个（从队头弹出，天然不重复）
                    val paths = mutableListOf<String>()
                    repeat(limit) {
                        pendingQueue.removeFirstOrNull()?.let { paths.add(it) }
                    }
                    if (paths.isEmpty()) return@withContext emptyList()
                    seenInRound.addAll(paths)
                    fileDao.getFilesByPaths(paths)
                }

                // 队列已空且库中无文件 → 无内容可展示
                if (entities.isEmpty()) {
                    _isLoading.value = false
                    return@launch
                }

                // 核心修复：生成条目移到 IO 线程，视频需校验有效性（时长 > 0），
                // 过滤损坏/黑屏视频（0 字节、元数据无法解析、时长 0s），避免发现页出现无法播放的内容。
                // 无效视频已从队列弹出，不会在下一轮重复撞上。
                val newBatch: List<DiscoveryItem> = withContext(Dispatchers.IO) {
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
            try { retriever.release() } catch (_: Exception) {}
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
                withContext(Dispatchers.Main) { Toast.makeText(application, application.getString(R.string.toast_operation_in_progress), Toast.LENGTH_SHORT).show() }
                return@launch
            }
            try {
                // 顶部横幅：删除进度提示（独立进度条，与其他任务并行显示）
                VaultManager.updateProgress(0.3f, application.getString(R.string.status_shredding), taskId = VaultManager.TASK_DELETE)
                VaultManager.deleteFile(application, entity.relativePath, fileDao)
                _items.update { currentItems ->
                    currentItems.filter { it.entity.relativePath != entity.relativePath }
                }
                VaultManager.updateProgress(1f, application.getString(R.string.status_delete_done), taskId = VaultManager.TASK_DELETE)
                delay(1000)
                VaultManager.clearProgress(VaultManager.TASK_DELETE)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_DELETE)
            }
        }
    }
}
