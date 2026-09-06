package com.dhhxfggg.pjm.domain.util

import com.dhhxfggg.pjm.MainApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多任务并发进度模型 + 全局信号中心。
 *
 * 从 VaultManager 拆出的职责块：
 * - 不同 taskId 的任务可【同时进行】，各自维护独立进度（UI 并行显示多个进度条）；
 * - 同一 taskId 的任务防连点（tryBeginOperation 返回 false）；
 * - 任务可请求取消（进度条卡住时点 × 中断）。
 * - 全局刷新 / 缓存清除 / 操作结果 三类信号。
 */
object VaultTasks {
    // 多任务并发进度 id：不同任务可并行（各自进度条独立显示），同任务防连点
    const val TASK_DEFAULT = "default"
    const val TASK_DELETE = "delete"
    const val TASK_DUPLICATES_EXACT = "duplicates_exact"
    const val TASK_DUPLICATES_PERCEPTUAL = "duplicates_perceptual"
    const val TASK_SYNC = "sync"
    const val TASK_STORE = "store"
    const val TASK_ENCRYPT = "encrypt"
    const val TASK_EXTRACT = "extract"
    const val TASK_EXPORT = "export"
    const val TASK_BILI_SCAN = "bili_scan"
    const val TASK_BILI_SCAN_MERGED = "bili_scan_merged"
    const val TASK_BILI_IMPORT = "bili_import"
    const val TASK_BILI_IMPORT_MERGED = "bili_import_merged"
    const val TASK_INTEGRITY = "integrity"
    const val TASK_CLEAR_CACHE = "clear_cache"
    const val TASK_CLEAR_LOGS = "clear_logs"
    const val TASK_RESET = "reset"
    const val TASK_RECOVER = "recover"
    const val TASK_INIT = "init"

    private val _operationResults = MutableSharedFlow<OperationResult>(extraBufferCapacity = 16)
    val operationResults = _operationResults.asSharedFlow()

    private val _refreshSignal = MutableSharedFlow<Unit>(replay = 1)
    val refreshSignal = _refreshSignal.asSharedFlow()

    // 缓存清除信号：用户点击清除缓存后广播，各 ViewModel 据此清空内存缓存（如缩略图 LRU）
    private val _cacheClearedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val cacheClearedSignal = _cacheClearedSignal.asSharedFlow()

    // 核心修复：多任务并发进度模型（见对象级注释）
    private val _activeTasks = MutableStateFlow<List<OperationTask>>(emptyList())
    val activeTasks = _activeTasks.asStateFlow()

    // 核心新增：任务取消机制 —— 进度条卡住时用户可点 × 中断任务
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()

    // 每个任务独立的完成态自动清除定时器
    private val autoClearJobs = ConcurrentHashMap<String, Job>()

    /** 请求取消指定任务（任务内部循环检查 [isTaskCancelled] 后提前结束） */
    fun requestCancelTask(taskId: String) {
        cancelFlags.getOrPut(taskId) { AtomicBoolean(false) }.set(true)
    }

    /** 任务是否已被请求取消 */
    fun isTaskCancelled(taskId: String): Boolean = cancelFlags[taskId]?.get() == true

    /** 清除任务的取消标志（任务重新开始时调用） */
    private fun clearTaskCancel(taskId: String) {
        cancelFlags.remove(taskId)
    }

    /**
     * 尝试开始一个任务。同 taskId 已有进行中任务 → false（防连点）；
     * 不同 taskId 允许并发（如查重进行中仍可删除文件）。
     */
    fun tryBeginOperation(taskId: String = "default"): Boolean {
        if (_activeTasks.value.any { it.taskId == taskId && it.isActive }) return false
        clearTaskCancel(taskId) // 重新开始前清除旧取消标志
        return true
    }

    /** 结束/移除一个任务（释放其进度条） */
    fun endOperation(taskId: String = "default") {
        autoClearJobs.remove(taskId)?.cancel()
        clearTaskCancel(taskId)
        _activeTasks.update { list -> list.filterNot { it.taskId == taskId } }
    }

    /** 当前是否有任何操作在进行（缩略图后台同步等据此让位） */
    val isOperationActive: Boolean get() = _activeTasks.value.any { it.isActive }

    /**
     * 更新指定任务的进度。任务不存在则自动创建。
     * 完成态（progress>=1 / 错误 / 非活跃）自动 2.5s 后清除该任务，不影响其他任务。
     */
    fun updateProgress(
        progress: Float,
        message: String,
        taskId: String = "default",
        isActive: Boolean = true,
        isError: Boolean = false,
        isIndeterminate: Boolean = false,
    ) {
        val task =
            OperationTask(
                taskId = taskId,
                progress = progress.coerceIn(0f, 1f),
                message = message,
                isActive = isActive,
                isError = isError,
                isIndeterminate = isIndeterminate,
            )
        _activeTasks.update { list ->
            if (list.any { it.taskId == taskId }) {
                list.map { if (it.taskId == taskId) task else it }
            } else {
                list + task
            }
        }
        val scope = MainApplication.applicationScope
        if (progress >= 1f || isError || !isActive) {
            autoClearJobs[taskId]?.cancel()
            autoClearJobs[taskId] =
                scope.launch {
                    delay(2500)
                    endOperation(taskId)
                    autoClearJobs.remove(taskId)
                }
        } else {
            autoClearJobs.remove(taskId)?.cancel()
        }
    }

    /** 清除指定任务（默认 "default"）。不影响其他进行中的任务。 */
    fun clearProgress(taskId: String = "default") {
        autoClearJobs.remove(taskId)?.cancel()
        _activeTasks.update { list -> list.filterNot { it.taskId == taskId } }
    }

    fun triggerRefresh() {
        _refreshSignal.tryEmit(Unit)
    }

    fun notifyCacheCleared() {
        _cacheClearedSignal.tryEmit(Unit)
    }

    fun notifyResult(result: OperationResult) {
        _operationResults.tryEmit(result)
    }
}
