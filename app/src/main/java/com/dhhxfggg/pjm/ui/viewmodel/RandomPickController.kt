package com.dhhxfggg.pjm.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Immutable
import com.dhhxfggg.pjm.R
import com.dhhxfggg.pjm.data.db.FileDao
import com.dhhxfggg.pjm.domain.util.VaultManager
import com.dhhxfggg.pjm.domain.util.VaultNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * 「随机图片」的控制器（打包成 .pjm 容器 / 直接分享源文件）。
 *
 * 从 SettingsViewModel 中拆出：抽取、进度上报、失败清理都在这里，
 * ViewModel 只做门面转发，UI 调用契约不变。
 *
 * @param app Application（用于 getString / 库内路径解析）
 * @param fileDao 数据库访问（按分类随机取行）
 * @param scope 协程作用域（通常传 viewModelScope）
 */
class RandomPickController(
    private val app: Application,
    private val fileDao: FileDao,
    private val scope: CoroutineScope,
) {
    /**
     * 随机图片分享：从【PJM 图片库】（images 分类）随机抽取 count 张图片，
     * 打包成单个 .pjm 容器存入 PJM 库。完成后用户可在 PJM 分类分享给好友。
     *
     * @param count 抽取数量
     * @param onDone 完成回调（true=成功；false=图片库为空/失败）
     */
    fun randomPickAndEncrypt(
        count: Int,
        onDone: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            // 核心修复：多任务并发 —— 同任务（打包）防连点，不同任务可并行
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
     * 分享动作由调用方（Activity/Compose）执行 —— 这里不持有启动 Activity 的职责。
     */
    fun randomPickForDirectShare(
        count: Int,
        onPicked: (RandomPickResult) -> Unit = {},
    ) {
        scope.launch {
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

    companion object {
        /**
         * 随机抽取的数量上限。
         *
         * 直发源文件时上限不宜太大：接收方对一次性多图有各自的限制，
         * 界面上的快捷档位（5/10/20/50）也正好与之对齐。
         */
        const val MAX_RANDOM_PICK = 50
    }
}
