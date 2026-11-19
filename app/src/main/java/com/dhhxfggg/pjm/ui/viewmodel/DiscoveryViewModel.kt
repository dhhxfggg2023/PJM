package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.db.ViewHistoryDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.model.ViewHistoryEntity
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.PjmLogger
import com.dhhxfggg.pjm.domain.util.SettingsManager
import com.dhhxfggg.pjm.domain.util.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** 裁剪后保留在「当前页」之前的条数：用户仍能往回翻这么多页。 */
private const val WINDOW_KEEP_BEHIND = 100

/** 「当前页 - 窗口起点」超过这个条数才裁一次（不是每翻一页都搬运列表）。 */
private const val WINDOW_TRIM_ABOVE = 300

/**
 * 发现页浏览队列的「窗口」：[baseIndex] 是窗口起点对应的**全局页序号**，[items] 是窗口内条目。
 *
 * 队列原本只增不减，长会话里内存随观看向后无限增长。裁剪只从**队头**丢弃不可见页：
 * 页序号保持全局（Pager 的 page = [baseIndex] + 窗口内下标），所以裁头只让 [baseIndex] 变大，
 * 当前页及其附近的页号与内容都不变 —— 不会出现可见内容跳变。
 *
 * [baseIndex] 与 [items] 必须放在同一份状态里：拆成两个 Flow 时中间会有一次
 * 「新列表 + 老起点」的发射，那一帧按错位下标取内容，正是要避免的跳变。
 */
@Immutable
data class DiscoveryWindow(
    val baseIndex: Int = 0,
    val items: List<DiscoveryItem> = emptyList(),
) {
    /** Pager 总页数：包含已被裁掉的头部，所以裁剪不会改变当前页的位置。 */
    val pageCount: Int get() = baseIndex + items.size

    /** 全局页序号 → 窗口内条目；超出窗口（正常不会发生）时返回 null，由调用方显示占位。 */
    fun itemAt(globalIndex: Int): DiscoveryItem? = items.getOrNull(globalIndex - baseIndex)
}

/**
 * 计算窗口裁剪后的队头起点（全局页序号）。纯函数，便于单测直接断言。
 *
 * 规则：当前页相对窗口起点超过 [WINDOW_TRIM_ABOVE] 条时裁一次，裁掉「相对页号 - keep」条，
 * 让当前页前方恰好保留 [keep] 条。
 *
 * @param total 窗口内条目总数
 * @param settled 当前停稳页的全局页序号
 * @param base 当前窗口起点对应的全局页序号
 * @param keep 裁剪后要保留在当前页之前的条数
 * @return 裁剪后的窗口起点；不需要裁剪时原样返回 [base]
 */
fun computeWindow(
    total: Int,
    settled: Int,
    base: Int,
    keep: Int = WINDOW_KEEP_BEHIND,
): Int {
    if (total <= 0 || keep < 0) return base
    val lastIndex = base + total - 1
    // 页号越界（正在切换分类/列表刚被清空）时按最近的合法页算，绝不返回非法起点
    val relative = settled.coerceIn(base, lastIndex) - base
    if (relative <= WINDOW_TRIM_ABOVE) return base
    val drop = relative - keep
    return if (drop <= 0) base else base + drop
}

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
        private val repository: FileRepository,
        private val settingsManager: SettingsManager,
    ) : AndroidViewModel(application) {
        private val _window = MutableStateFlow(DiscoveryWindow())
        private val _discoveryMode = MutableStateFlow(DiscoveryMode.BILI_VIDEOS)
        private val _isLoading = MutableStateFlow(value = false)
        private val _audioCover = MutableStateFlow<String?>(null)

        /**
         * Combined UI state for the discovery feature.
         */
        val uiState: StateFlow<DiscoveryUiState> =
            combine(
                _window,
                _discoveryMode,
                _isLoading,
                _audioCover,
            ) { window, mode, loading, audioCover ->
                DiscoveryUiState(window.items, mode, loading, audioCover)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = DiscoveryUiState(),
            )

        /**
         * 浏览队列窗口（全局页起点 + 裁剪后的条目）。
         *
         * Screen 的页数、取页内容、标记浏览都只读这一份：起点与列表来自同一次发射，
         * 不会出现「新列表配老起点」的错位一帧。
         */
        val window: StateFlow<DiscoveryWindow> = _window.asStateFlow()

        // 分类由调用方在取批之初固化后显式传入，DAO 回调绝不读 _discoveryMode ——
        // 否则切分类时在途的旧批次会作用到新分类（详见 DiscoveryQueue 类注释）。
        private val queue =
            DiscoveryQueue(
                unviewedPaths = { category -> viewHistoryDao.getUnviewedPathsByCategory(category) },
                // 只要判断「分类是不是空的」：用 COUNT 聚合，不要把整分类的路径全读进内存
                hasAnyPaths = { category -> fileDao.getCountByCategory(category) > 0 },
                // 整轮看完 → 清掉本分类的浏览记录，下一轮就又是"全新一遍"
                onRoundRestart = { category -> viewHistoryDao.clearByCategory(category) },
            )

        private val idGenerator = AtomicLong(0)

        /** 批次代际：切分类/重新加载会作废在途批次，避免旧分类结果混入新列表 */
        private var loadGeneration = 0

        /** 合并连续删除触发的浏览历史孤儿清理（见 [schedulePurgeOrphans]） */
        private var purgeJob: Job? = null

        init {
            viewModelScope.launch {
                // 先用设置里的默认分类确定模式，再建队列/取内容：
                // 否则会先按 B站 建队列并加载一批，界面先闪一下 B站 才切到用户设的分类。
                val defaultMode =
                    runCatching { settingsManager.settings.first().discoveryDefaultMode }
                        .getOrDefault("bili_videos")
                        .toDiscoveryMode()
                if (defaultMode != _discoveryMode.value) _discoveryMode.value = defaultMode

                // 播种"本次会话交出过什么"：重建（离开该页再回来）后，
                // 已经看过的不该再被推一遍。
                runCatching { viewHistoryDao.getViewedPathsByCategory(_discoveryMode.value.value) }
                    .onSuccess { queue.seedServed(it) }
                    .onFailure { PjmLogger.e("DiscoveryViewModel", "恢复浏览记录失败", it) }
                loadMoreItems()

                // 底部 Tab 用 saveState/restoreState 导航，发现页的 ViewModel 不会被重建，
                // 只在这里读一次设置根本不够：用户在设置里改了默认分类，这个实例还在，
                // 页面永远停在老分类上。所以持续监听「默认分类」这一个字段，变了就切过去。
                // drop(1) 丢掉上面已经用过的当前值，其它设置的变化不会打断正在看的内容。
                settingsManager.settings
                    .map { it.discoveryDefaultMode }
                    .drop(1)
                    .distinctUntilChanged()
                    .collect { name ->
                        val mode = name.toDiscoveryMode()
                        if (mode != _discoveryMode.value) setMode(mode)
                    }
            }
        }

        /**
         * 每次进入发现页都把"默认分类"重新应用一次。
         *
         * 之前的做法是只在 VM 初始化时读一次，用户手动切到别的分类、离开再回来，
         * 页面还是停在手动切过去的那个分类上 —— 看起来就是"默认分类只生效一次"。
         */
        fun applyDefaultMode() {
            viewModelScope.launch {
                val mode =
                    runCatching { settingsManager.settings.first().discoveryDefaultMode }
                        .getOrDefault("bili_videos")
                        .toDiscoveryMode()
                if (mode != _discoveryMode.value) setMode(mode)
            }
        }

        private fun String.toDiscoveryMode(): DiscoveryMode =
            when (this) {
                "images" -> DiscoveryMode.IMAGES
                "videos" -> DiscoveryMode.VIDEOS
                "audios" -> DiscoveryMode.AUDIOS
                else -> DiscoveryMode.BILI_VIDEOS
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
         * 页面停稳：回传全局页序号，并在「不在播放」时裁剪浏览队列的队头。
         *
         * 长会话里队列只增不减，内存随观看向后增长；这里只在当前页前方积压超过
         * [WINDOW_TRIM_ABOVE] 条时裁一次，保留 [WINDOW_KEEP_BEHIND] 条回头余量。
         * 裁剪只抬 [DiscoveryWindow.baseIndex]，页号保持全局，所以可见页内容不跳变；
         * 也完全不碰 [DiscoveryQueue] 的 served 判定（那只看浏览历史，不看 UI 列表）。
         *
         * @param globalIndex 停稳页的全局页序号
         * @param canTrim 当前没有页在播放/缓冲时才为 true（由 Screen 判定并传入）：
         *   播放中不动列表，避免重建页面、打断正在用的解码器；播放一停 Screen 会用同一个
         *   页序号再回调一次，裁剪那时补上。
         */
        fun onPageSettled(
            globalIndex: Int,
            canTrim: Boolean,
        ) {
            if (globalIndex < 0 || !canTrim) return
            _window.update { current ->
                val newBase = computeWindow(current.items.size, globalIndex, current.baseIndex, WINDOW_KEEP_BEHIND)
                val dropped = newBase - current.baseIndex
                if (dropped <= 0) {
                    current
                } else {
                    current.copy(baseIndex = newBase, items = current.items.drop(dropped))
                }
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
            // 换分类 = 换一份列表：窗口起点必须一起归零，否则页号会指到不存在的条目
            _window.value = DiscoveryWindow()
            viewModelScope.launch {
                // 只丢弃"已排队"的部分；浏览历史与分类无关，故意保留，
                // 免得切回来时把刚看过的内容重播一遍。
                queue.dropPending()
                // 作废在途批次：否则旧分类的结果会在切换后追加进新列表
                loadGeneration++
                _isLoading.value = false
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
        private suspend fun takeBatch(
            limit: Int,
            category: String,
        ): List<FileEntity> {
            var paths = queue.nextBatch(limit, category)
            if (paths.isEmpty()) {
                // 先判本轮是否真的走完（只有走完了才允许清历史重开）。
                // 旧写法在这里会把「全量未浏览路径」查两遍（判定 + 补队），空批次最坏连查 4 遍；
                // 现在一个空批次最多两条查询。
                if (queue.isRoundComplete(category)) {
                    if (queue.restartNextRound(category)) {
                        paths = queue.nextBatch(limit, category)
                    }
                } else {
                    // 快速划过的页被标记 served 却从未上屏：放开占位让它们重新入队，否则本轮无法收尾
                    queue.forgetServed()
                    paths = queue.nextBatch(limit, category)
                }
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
            val generation = ++loadGeneration
            val mode = _discoveryMode.value
            _isLoading.value = true
            viewModelScope.launch {
                try {
                    // 音频默认封面：从图片库随机抽一张。只取一次，整轮播放期间不变。
                    if (mode == DiscoveryMode.AUDIOS && _audioCover.value == null) {
                        _audioCover.value =
                            withContext(Dispatchers.IO) {
                                repository.getRandomFileByCategory(VaultManager.CAT_IMAGES)?.relativePath
                            }
                    }
                    val entities: List<FileEntity> = withContext(Dispatchers.IO) { takeBatch(10, mode.value) }
                    // 切分类后旧批次直接丢弃，绝不追加进新模式的列表
                    if (generation != loadGeneration || _discoveryMode.value != mode) return@launch
                    if (entities.isEmpty()) return@launch

                    val newBatch: List<DiscoveryItem> =
                        withContext(Dispatchers.IO) {
                            entities.mapNotNull { entity ->
                                val file = VaultManager.getFileFromEntity(application, entity)
                                if (!file.exists()) return@mapNotNull null
                                val isVideo = entity.category == VaultManager.CAT_BILI_VIDEOS || entity.category == VaultManager.CAT_VIDEOS
                                if (isVideo && !hasValidVideoDuration(file)) return@mapNotNull null
                                if (entity.category == VaultManager.CAT_AUDIOS && file.length() <= 0L) return@mapNotNull null
                                val id = idGenerator.incrementAndGet()
                                when (entity.category) {
                                    VaultManager.CAT_IMAGES -> DiscoveryItem.Image(id, file, entity)
                                    VaultManager.CAT_AUDIOS -> DiscoveryItem.Audio(id, file, entity)
                                    else -> DiscoveryItem.Video(id, file, entity)
                                }
                            }
                        }

                    _window.update { it.copy(items = it.items + newBatch) }
                } catch (e: Exception) {
                    PjmLogger.e("DiscoveryViewModel", "Load items failed", e)
                } finally {
                    if (generation == loadGeneration) _isLoading.value = false
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
                    _window.update { current ->
                        val remaining = current.items.filter { it.entity.relativePath != entity.relativePath }
                        // 删空了就回到初始窗口，别让 baseIndex 停在一个空列表上
                        if (remaining.isEmpty()) DiscoveryWindow() else current.copy(items = remaining)
                    }
                    // 历史记录随之清理，避免"已看过"里残留已删除的路径。
                    // purgeOrphans 是两表全扫：连续删多个文件时合并成一次延时清理（见 schedulePurgeOrphans）
                    schedulePurgeOrphans()
                    VaultManager.updateProgress(1f, application.getString(R.string.status_delete_done), taskId = VaultManager.TASK_DELETE)
                    delay(1000)
                    VaultManager.clearProgress(VaultManager.TASK_DELETE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PjmLogger.e("DiscoveryVM", "删除文件失败: ${e.javaClass.simpleName}", e as? Exception)
                } finally {
                    VaultManager.endOperation(VaultManager.TASK_DELETE)
                }
            }
        }

        /**
         * 合并「删除后清理浏览历史孤儿行」。
         *
         * purgeOrphans 要扫 files + view_history 两张全表，连续删 10 个文件就被放大成 10 次全扫。
         * 这里合并成一次：删除动作停止 5 秒后才清理（期间继续删就顺延）。
         * 孤儿行本身不影响正确性 —— 未看过判定只认 files 表里仍存在的路径 —— 只影响表体积，
         * 所以延时期间 VM 被销毁、清理没跑，也不会让功能出错。
         */
        private fun schedulePurgeOrphans() {
            purgeJob?.cancel()
            purgeJob =
                viewModelScope.launch(Dispatchers.IO) {
                    delay(PURGE_ORPHANS_DELAY_MS)
                    runCatching { viewHistoryDao.purgeOrphans() }
                }
        }

        private companion object {
            /** 删除动作停止后多久做一次孤儿清理（合并连续删除） */
            const val PURGE_ORPHANS_DELAY_MS = 5_000L
        }
    }
