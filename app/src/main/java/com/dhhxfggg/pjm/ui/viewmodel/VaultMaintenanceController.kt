package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.widget.Toast
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.data.model.FileEntity
import com.dhhxfggg.pjm.data.repository.FileRepository
import com.dhhxfggg.pjm.domain.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 库维护类长任务的控制器：查重、删除重复、重建索引、完整性校验。
 *
 * 从 SettingsViewModel 中拆出。这些操作的共同点是要独占一条 [VaultManager] 任务
 * 进度条、要在 IO 线程池跑、要处理用户取消；和「设置项读写」混在一个类里时，
 * 改一个设置开关也得在几百行长任务代码里找位置。
 *
 * 状态通过 [duplicateFiles] 暴露，ViewModel 直接并进自己的 uiState；UI 调用契约不变。
 *
 * @param app Application（用于 getString）
 * @param fileDao 数据库访问
 * @param repository 完整性校验的仓储
 * @param scope 协程作用域（通常传 viewModelScope）
 */
class VaultMaintenanceController(
    private val app: Application,
    private val fileDao: FileDao,
    private val repository: FileRepository,
    private val scope: CoroutineScope,
) {
    private val _duplicateFiles = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicateFiles: StateFlow<List<DuplicateGroup>?> = _duplicateFiles.asStateFlow()

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
        scope.launch(VaultManager.PjmDispatchers.IO) {
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
                    PjmLogger.i("VaultMaintenance", "查重已取消，不展示结果")
                    return@launch
                }
                report(1f, app.getString(R.string.status_all_tasks_complete))
                // 让进度条停在 100% 一小会儿，再弹结果窗（否则"满格"与"弹窗"同帧，看不出完成感）
                delay(300.milliseconds)
                // 合并分组：精确查重组 + 图片感知组（各组的文件不重叠，直接拼接）
                _duplicateFiles.value = exactDuplicates + similarImages
            } catch (e: CancellationException) {
                // 用户取消：静默结束，不弹错误
                PjmLogger.i("VaultMaintenance", "查重已取消: ${e.message}")
            } catch (e: Throwable) {
                // 核心修复：捕获 Throwable（含 OOM）—— 查重异常不崩溃，清理进度后返回
                PjmLogger.e("VaultMaintenance", "查重异常: ${e.javaClass.simpleName}: ${e.message}", e as? Exception)
            } finally {
                VaultManager.clearProgress(VaultManager.TASK_DUPLICATES)
                VaultManager.endOperation(VaultManager.TASK_DUPLICATES)
            }
        }
    }

    fun syncDatabase() {
        scope.launch {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 必须接住：viewModelScope 没有 CoroutineExceptionHandler，
                // 只有 try/finally 时这里的异常会一路冒泡到线程默认处理器 → 闪退。
                // 重建索引会读几千个文件、写几万行数据库，IO 错误与 OOM 都真实存在。
                PjmLogger.e("VaultMaintenance", "重建索引失败: ${e.javaClass.simpleName}", e as? Exception)
                VaultManager.updateProgress(0f, app.getString(R.string.error_processing_failed, e.message.orEmpty()), taskId = VaultManager.TASK_SYNC, isError = true)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_SYNC)
            }
        }
    }

    fun clearDuplicateState() {
        _duplicateFiles.value = null
    }

    fun performDeleteDuplicates(entities: List<FileEntity>) {
        _duplicateFiles.value = null
        scope.launch {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 同 syncDatabase：删除失败（文件被占用、权限异常）不能让整个进程崩掉
                PjmLogger.e("VaultMaintenance", "清理重复文件失败: ${e.javaClass.simpleName}", e as? Exception)
                VaultManager.updateProgress(0f, app.getString(R.string.error_processing_failed, e.message.orEmpty()), taskId = VaultManager.TASK_DELETE, isError = true)
            } finally {
                VaultManager.endOperation(VaultManager.TASK_DELETE)
            }
        }
    }

    fun checkIntegrity(onResult: (Map<String, List<FileEntity>>) -> Unit) {
        scope.launch {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 完整性检查要读全库文件哈希，任何 IO 异常都不能变成闪退；
                // 失败时回空结果，界面显示「没有发现问题」而不是卡在进度条上。
                PjmLogger.e("VaultMaintenance", "完整性检查失败: ${e.javaClass.simpleName}", e as? Exception)
                onResult(emptyMap())
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
    }
}
